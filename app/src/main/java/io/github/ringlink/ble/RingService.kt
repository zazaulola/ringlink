package io.github.ringlink.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.ringlink.L
import io.github.ringlink.data.Ring
import io.github.ringlink.data.RingDatabase
import io.github.ringlink.data.RingRepository
import io.github.ringlink.data.Settings
import io.github.ringlink.health.HealthExporter
import io.github.ringlink.protocol.Descriptor
import io.github.ringlink.protocol.Opcodes
import io.github.ringlink.protocol.RingClock
import io.github.ringlink.protocol.SyncSession
import io.github.ringlink.trigger.CallMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds a live connection to every configured ring.
 *
 * Several rings at once is the point, not a curiosity: one charges while the other is worn, and the
 * user swaps when the worn one runs low. Android is happy to hold multiple GATT links, so all of
 * them stay connected and a notification buzzes whichever ring is actually on a finger.
 *
 * Runs as a `connectedDevice` foreground service — the one Bluetooth-relevant service type with no
 * runtime cap and no restriction on starting from boot.
 */
class RingService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var settings: Settings
    private lateinit var repo: RingRepository
    private lateinit var exporter: HealthExporter
    private lateinit var clock: RingClock

    private val clients = ConcurrentHashMap<String, RingBleClient>()
    private val connectLocks = ConcurrentHashMap<String, Mutex>()
    private val idleJobs = ConcurrentHashMap<String, Job>()
    private val lastBuzz = HashMap<String, Long>()
    private val syncing = Mutex()
    private var watchdogJob: Job? = null
    private var callMonitor: CallMonitor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        repo = RingRepository(RingDatabase.get(this).dao())
        exporter = HealthExporter(this, repo, settings)
        clock = RingClock(settings.epochAnchor, settings.epochCalibrated)
        if (settings.buzzOnCalls) {
            callMonitor = CallMonitor(this) { scope.launch { buzz(null) } }.also { it.start() }
        }
        startForegroundNotification()
        publishRings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SYNC -> scope.launch { syncAll(force = true) }
            ACTION_BUZZ -> {
                val key = intent.getStringExtra(EXTRA_KEY)
                scope.launch { buzz(key) }
            }
            ACTION_REEXPORT -> scope.launch { reExport() }
            else -> scope.launch { connectAll() }
        }
        startWatchdog()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        idleJobs.values.forEach { it.cancel() }
        watchdogJob?.cancel()
        callMonitor?.stop()
        clients.values.forEach { it.disconnect() }
        scope.cancel()
        super.onDestroy()
    }

    // --- connections ------------------------------------------------------------------------------

    private fun clientFor(address: String): RingBleClient =
        clients.getOrPut(address) {
            RingBleClient(this).apply {
                onConnectionChange = { up ->
                    updateRing(address) { it.copy(connected = up) }
                    if (up) startIdleLoop(address) else idleJobs.remove(address)?.cancel()
                }
            }
        }

    private suspend fun connectAll() {
        settings.rings.forEach { ring -> ensureConnected(ring.address) }
    }

    private suspend fun ensureConnected(address: String): Boolean {
        val client = clientFor(address)
        if (client.isConnected) return true

        val lock = connectLocks.getOrPut(address) { Mutex() }
        val ok = lock.withLock {
            if (client.isConnected) return@withLock true
            L.i("connecting to $address")
            val connected = client.connect(address)
            L.i("connect $address -> $connected")
            updateRing(address) { it.copy(connected = connected) }
            if (connected) startIdleLoop(address)
            connected
        }

        // Outside the lock: syncAll() calls ensureConnected() again and a Mutex is not reentrant.
        if (ok && shouldAutoSync()) scope.launch { syncAll(force = false) }
        return ok
    }

    /**
     * While not syncing, someone has to answer the ring: it sends a heartbeat every ~2.5 min and a
     * descriptor every ~30-60 s. Left unanswered the link goes stale and frames pile up.
     */
    private fun startIdleLoop(address: String) {
        idleJobs.remove(address)?.cancel()
        val client = clientFor(address)
        idleJobs[address] = scope.launch {
            while (isActive) {
                val frame = withTimeoutOrNull(30_000) { client.incoming.receive() } ?: continue
                if (frame.isEmpty()) continue
                when (frame[0].toInt() and 0xff) {
                    Opcodes.RESP_HEARTBEAT -> client.write(Opcodes.HEARTBEAT_ACK)
                    Opcodes.RESP_DESCRIPTOR_QUERY, Opcodes.RESP_DESCRIPTOR_FETCH ->
                        Descriptor.parse(frame)?.let { d ->
                            repo.sinkFor(address).onDescriptor(d)
                            updateRing(address) {
                                it.copy(battery = d.batteryPercent, onCharger = d.onCharger)
                            }
                        }
                }
            }
        }
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                settings.rings.forEach { ring ->
                    val client = clientFor(ring.address)
                    if (!client.isConnected && !client.backgroundConnectArmed) {
                        L.d("watchdog: ${ring.address} is down, reconnecting")
                        ensureConnected(ring.address)
                    }
                }
            }
        }
    }

    // --- actions ---------------------------------------------------------------------------------

    /**
     * Buzz the ring the user is actually wearing.
     *
     * A ring sitting in its charger should stay silent — buzzing it is both useless and the reason
     * the spare exists. Rings report charging state in their descriptor, so the worn ones are simply
     * the connected ones that are not charging; if that leaves nobody, fall back to any connection
     * rather than dropping the notification.
     */
    suspend fun buzz(key: String?) {
        val requestedAt = System.currentTimeMillis()
        if (key != null && recentlyBuzzed(key, requestedAt)) {
            L.d("buzz skipped: $key buzzed moments ago")
            return
        }

        settings.rings.forEach { ring -> ensureConnected(ring.address) }

        val connected = settings.rings.filter { clientFor(it.address).isConnected }
        if (connected.isEmpty()) {
            L.w("buzz dropped: no ring connected")
            return
        }
        val worn = connected.filterNot { state.value.ringOrNull(it.address)?.onCharger == true }
        val targets = worn.ifEmpty { connected }

        if (System.currentTimeMillis() - requestedAt > STALE_BUZZ_MS) {
            L.w("buzz dropped: connecting took too long to still be useful")
            return
        }

        var delivered = false
        targets.forEach { ring ->
            if (clientFor(ring.address).writeReliably(Opcodes.VIBRATE)) {
                delivered = true
                L.i("buzz delivered to ${ring.shortName}${if (key != null) " ($key)" else ""}")
            } else {
                L.w("buzz dropped: ${ring.shortName} did not accept the write")
            }
        }
        if (delivered && key != null) synchronized(lastBuzz) { lastBuzz[key] = System.currentTimeMillis() }
    }

    private fun recentlyBuzzed(key: String, now: Long): Boolean = synchronized(lastBuzz) {
        val previous = lastBuzz[key]
        if (previous != null && now - previous < BUZZ_COOLDOWN_MS) return true
        if (lastBuzz.size >= MAX_TRACKED_KEYS) {
            lastBuzz.entries.minByOrNull { it.value }?.let { lastBuzz.remove(it.key) }
        }
        false
    }

    private fun shouldAutoSync(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (settings.isQuietHour(hour)) return false
        return System.currentTimeMillis() - settings.lastSyncAt > MIN_SYNC_INTERVAL_MS
    }

    suspend fun syncAll(force: Boolean) {
        if (!force && !shouldAutoSync()) return
        syncing.withLock {
            state.value = state.value.copy(syncing = true, status = "Syncing…")
            var total = 0
            try {
                for (ring in settings.rings) {
                    if (!ensureConnected(ring.address)) {
                        L.w("sync skipped for ${ring.shortName}: unreachable")
                        continue
                    }
                    total += syncOne(ring)
                }
                settings.epochAnchor = clock.epoch()
                settings.epochCalibrated = clock.isCalibrated()
                settings.lastSyncAt = System.currentTimeMillis()
                state.value = state.value.copy(
                    status = "Synced $total records",
                    lastSyncAt = settings.lastSyncAt,
                )
                if (settings.exportToHealthConnect) {
                    val exported = exporter.exportPending(clock)
                    L.i("health connect export: $exported rows")
                }
            } finally {
                state.value = state.value.copy(syncing = false)
            }
        }
    }

    private suspend fun syncOne(ring: Ring): Int {
        val client = clientFor(ring.address)
        val job = idleJobs.remove(ring.address)
        job?.cancelAndJoin()
        return try {
            val session = SyncSession(ring.address, client, repo.sinkFor(ring.address), clock)
            if (!session.authenticate()) {
                L.e("auth failed for ${ring.shortName}")
                return 0
            }
            val stats = session.syncHistory()
            L.i("${ring.shortName}: ${stats.epochs} epochs, ${stats.pages} pages")
            stats.epochs
        } finally {
            delay(200)
            startIdleLoop(ring.address)
        }
    }

    private suspend fun reExport() {
        state.value = state.value.copy(status = "Re-exporting…")
        if (exporter.deleteExportedSleepSessions()) L.i("removed previously exported sleep sessions")
        val n = runCatching { exporter.reExportAll(clock) }
            .onFailure { L.e("re-export failed", it) }
            .getOrDefault(0)
        L.i("re-exported $n rows")
        state.value = state.value.copy(status = "Re-exported $n records")
    }

    // --- state ------------------------------------------------------------------------------------

    /**
     * Rebuild the published ring list.
     *
     * Connection flags start false: the state flow is static and outlives the service, so carrying
     * the old values over would show a ring as connected when the process has just restarted and
     * holds no link at all.
     */
    private fun publishRings() {
        state.value = state.value.copy(
            rings = settings.rings.map { r ->
                val known = state.value.ringOrNull(r.address)
                RingState(r.address, r.name, connected = false, battery = known?.battery)
            },
        )
    }

    private fun updateRing(address: String, transform: (RingState) -> RingState) {
        val current = state.value.rings
        val existing = current.firstOrNull { it.address == address }
            ?: RingState(address, settings.rings.firstOrNull { it.address == address }?.name ?: address)
        state.value = state.value.copy(
            rings = (current.filterNot { it.address == address } + transform(existing))
                .sortedBy { it.address },
        )
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ring connection", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) },
            )
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RingLink")
            .setContentText("Keeping your rings connected")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    data class RingState(
        val address: String,
        val name: String,
        val connected: Boolean = false,
        val battery: Int? = null,
        val onCharger: Boolean = false,
    ) {
        val shortName: String get() = name.substringAfterLast('-', name)
    }

    data class State(
        val rings: List<RingState> = emptyList(),
        val syncing: Boolean = false,
        val status: String = "Idle",
        val lastSyncAt: Long = 0,
    ) {
        fun ringOrNull(address: String) = rings.firstOrNull { it.address == address }
    }

    companion object {
        const val ACTION_SYNC = "io.github.ringlink.SYNC"
        const val ACTION_BUZZ = "io.github.ringlink.BUZZ"
        const val ACTION_STOP = "io.github.ringlink.STOP"
        const val ACTION_REEXPORT = "io.github.ringlink.REEXPORT"
        const val EXTRA_KEY = "key"

        private const val CHANNEL_ID = "ring_link"
        private const val NOTIFICATION_ID = 1
        private const val MIN_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val STALE_BUZZ_MS = 15_000L
        private const val WATCHDOG_INTERVAL_MS = 45_000L
        private const val BUZZ_COOLDOWN_MS = 3_000L
        private const val MAX_TRACKED_KEYS = 64

        @Volatile
        var instance: RingService? = null
            private set

        val state = MutableStateFlow(State())
        val observable: StateFlow<State> get() = state

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, RingService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun buzzFor(context: Context, key: String) {
            val i = Intent(context, RingService::class.java).apply {
                action = ACTION_BUZZ
                putExtra(EXTRA_KEY, key)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun listenerComponent(context: Context) = android.content.ComponentName(
            context, io.github.ringlink.trigger.RingNotificationListener::class.java,
        )
    }
}
