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

/**
 * Owns the ring connection for as long as the user wants it.
 *
 * Runs as a `connectedDevice` foreground service: that is the one Bluetooth-relevant service type
 * with no runtime cap and no restriction on starting from boot, which is exactly what a wearable
 * link needs.
 */
class RingService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var client: RingBleClient
    private lateinit var settings: Settings
    private lateinit var repo: RingRepository
    private lateinit var exporter: HealthExporter
    private lateinit var clock: RingClock

    private var idleJob: Job? = null
    private var watchdogJob: Job? = null
    private var callMonitor: CallMonitor? = null
    private val busy = Mutex()

    /**
     * Serialises connection attempts. RingBleClient.connect() tears down any existing link before
     * starting a fresh one, so two callers racing here — say three notifications arriving while the
     * ring is away — would each destroy the others' half-open connection and all of them would
     * fail. Concurrent callers now queue and share whichever attempt is already in flight.
     */
    private val connecting = Mutex()

    /** Per-notification-group debounce, keyed by group or package. */
    private val lastBuzz = HashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        repo = RingRepository(RingDatabase.get(this).dao())
        exporter = HealthExporter(this, repo, settings)
        clock = RingClock(settings.epochAnchor, settings.epochCalibrated)
        client = RingBleClient(this)
        client.onConnectionChange = { up -> state.value = state.value.copy(connected = up) }
        if (settings.buzzOnCalls) {
            callMonitor = CallMonitor(this) { scope.launch { buzz() } }.also { it.start() }
        }
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SYNC -> scope.launch { syncNow(force = true) }
            ACTION_BUZZ -> {
                val key = intent.getStringExtra(EXTRA_KEY)
                scope.launch { buzz(key) }
            }
            ACTION_REEXPORT -> scope.launch { reExport() }
            else -> scope.launch { ensureConnected() }
        }
        startWatchdog()
        return START_STICKY
    }

    /**
     * Keeps the link warm.
     *
     * A cold connect takes 10-20 s because the ring only advertises periodically, which is long
     * enough that the first notification after an idle spell misses its buzz entirely. Reconnecting
     * in the background means the link is already up when a notification actually arrives.
     */
    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (settings.ringAddress != null && !client.isConnected) {
                    L.d("watchdog: link is down, reconnecting")
                    ensureConnected()
                }
            }
        }
    }

    override fun onDestroy() {
        instance = null
        idleJob?.cancel()
        watchdogJob?.cancel()
        callMonitor?.stop()
        client.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    // --- connection ------------------------------------------------------------------------------

    private suspend fun ensureConnected(): Boolean {
        if (client.isConnected) return true

        val ok = connecting.withLock {
            // Re-check inside the lock: while we were queued, another caller may have connected.
            if (client.isConnected) return@withLock true
            val address = settings.ringAddress ?: return@withLock false
            state.value = state.value.copy(status = "Connecting…")
            L.i("connecting to $address")
            val connected = client.connect(address)
            L.i("connect -> $connected")
            state.value = state.value.copy(
                connected = connected,
                status = if (connected) "Connected" else "Not connected",
            )
            if (connected) startIdleLoop()
            connected
        }

        // Deliberately OUTSIDE the lock. syncNow() calls ensureConnected() itself, and a Kotlin
        // Mutex is not reentrant — doing this inside the lock deadlocked the service permanently,
        // taking every later buzz and sync down with it.
        if (ok && shouldAutoSync()) scope.launch { syncNow(force = false) }
        return ok
    }

    /**
     * While not syncing, someone still has to answer the ring: it sends a heartbeat every ~2.5 min
     * and a descriptor every ~30-60 s. Left unanswered the link goes stale and the frames pile up.
     */
    private fun startIdleLoop() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (isActive) {
                val frame = withTimeoutOrNull(30_000) { client.incoming.receive() } ?: continue
                if (frame.isEmpty()) continue
                when (frame[0].toInt() and 0xff) {
                    Opcodes.RESP_HEARTBEAT -> client.write(Opcodes.HEARTBEAT_ACK)
                    Opcodes.RESP_DESCRIPTOR_QUERY, Opcodes.RESP_DESCRIPTOR_FETCH ->
                        Descriptor.parse(frame)?.let {
                            repo.onDescriptor(it)
                            state.value = state.value.copy(battery = it.batteryPercent)
                        }
                }
            }
        }
    }

    // --- actions ---------------------------------------------------------------------------------

    /** Buzz the ring. Safe to call at any time; the transport serialises it against a sync. */
    /**
     * Buzz the ring, de-duplicating per [key] (a notification group or package).
     *
     * The cooldown is stamped only after the ring actually accepts the write. Debouncing on the
     * attempt instead would let a buzz that never arrived — because the link was down — silence the
     * next real notification, which is the worst of both worlds.
     */
    suspend fun buzz(key: String? = null) {
        val requestedAt = System.currentTimeMillis()
        if (key != null && recentlyBuzzed(key, requestedAt)) {
            L.d("buzz skipped: $key buzzed moments ago")
            return
        }
        if (!ensureConnected()) {
            L.w("buzz dropped: ring not connected")
            state.value = state.value.copy(status = "Buzz missed — ring not connected")
            return
        }
        // Buzzing long after the notification is worse than not buzzing: the user has already seen
        // the phone, and a stray pulse minutes later is just confusing.
        val waited = System.currentTimeMillis() - requestedAt
        if (waited > STALE_BUZZ_MS) {
            L.w("buzz dropped: took ${waited}ms to connect, too late to be useful")
            return
        }
        val ok = client.writeReliably(Opcodes.VIBRATE)
        if (ok) {
            if (key != null) synchronized(lastBuzz) { lastBuzz[key] = System.currentTimeMillis() }
            L.i("buzz delivered${if (key != null) " ($key)" else ""}")
        } else {
            L.w("buzz dropped: ring did not accept the write")
        }
    }

    private fun recentlyBuzzed(key: String, now: Long): Boolean = synchronized(lastBuzz) {
        val previous = lastBuzz[key]
        if (previous != null && now - previous < BUZZ_COOLDOWN_MS) return true
        // Bounded: a phone sees many packages over a long uptime, and this map must not grow with
        // them. Oldest entry goes when it is full.
        if (lastBuzz.size >= MAX_TRACKED_KEYS) {
            lastBuzz.entries.minByOrNull { it.value }?.let { lastBuzz.remove(it.key) }
        }
        false
    }

    /** Rewrite every stored record into Health Connect at its current (corrected) timestamp. */
    private suspend fun reExport() {
        state.value = state.value.copy(status = "Re-exporting…")
        // Older builds wrote sleep sessions derived from channel contiguity, which turned out to be
        // unsound. Clear them out so the correction reaches anyone who already ran that version.
        if (exporter.deleteExportedSleepSessions()) L.i("removed previously exported sleep sessions")
        val n = runCatching { exporter.reExportAll(clock) }
            .onFailure { L.e("re-export failed", it) }
            .getOrDefault(0)
        L.i("re-exported $n rows")
        state.value = state.value.copy(status = "Re-exported $n records")
    }

    private fun shouldAutoSync(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (settings.isQuietHour(hour)) return false
        return System.currentTimeMillis() - settings.lastSyncAt > MIN_SYNC_INTERVAL_MS
    }

    suspend fun syncNow(force: Boolean) {
        if (!force && !shouldAutoSync()) return
        if (!ensureConnected()) return
        busy.withLock {
            // The sync owns the incoming stream while it runs.
            idleJob?.cancelAndJoin()
            state.value = state.value.copy(syncing = true, status = "Syncing…")
            try {
                val session = SyncSession(settings.ringAddress!!, client, repo, clock)
                L.i("authenticating")
                if (!session.authenticate()) {
                    L.e("auth failed")
                    state.value = state.value.copy(status = "Authentication failed")
                    return@withLock
                }
                L.i("auth ok, draining history")
                val stats = session.syncHistory()
                L.i("sync done: epochs=${stats.epochs} pages=${stats.pages} sport=${stats.sportIntervals} newest=${stats.newestCounter} epochAnchor=${clock.epoch()}")
                settings.epochAnchor = clock.epoch()
                settings.epochCalibrated = clock.isCalibrated()
                settings.lastSyncAt = System.currentTimeMillis()
                state.value = state.value.copy(
                    status = "Synced ${stats.epochs} records",
                    lastSyncAt = settings.lastSyncAt,
                )
                if (settings.exportToHealthConnect) {
                    val exported = exporter.exportPending(clock)
                    L.i("health connect export: $exported rows")
                    if (exported > 0) {
                        state.value = state.value.copy(status = "Synced ${stats.epochs}, exported $exported")
                    }
                }
            } finally {
                state.value = state.value.copy(syncing = false)
                delay(200)
                startIdleLoop()
            }
        }
    }

    // --- foreground notification ------------------------------------------------------------------

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
            .setContentText("Keeping your ring connected")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    data class State(
        val connected: Boolean = false,
        val syncing: Boolean = false,
        val battery: Int? = null,
        val status: String = "Idle",
        val lastSyncAt: Long = 0,
    )

    companion object {
        const val ACTION_SYNC = "io.github.ringlink.SYNC"
        const val ACTION_BUZZ = "io.github.ringlink.BUZZ"
        const val ACTION_STOP = "io.github.ringlink.STOP"
        const val ACTION_REEXPORT = "io.github.ringlink.REEXPORT"
        private const val CHANNEL_ID = "ring_link"
        private const val NOTIFICATION_ID = 1
        private const val MIN_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val STALE_BUZZ_MS = 15_000L
        private const val WATCHDOG_INTERVAL_MS = 45_000L
        private const val BUZZ_COOLDOWN_MS = 3_000L
        private const val MAX_TRACKED_KEYS = 64

        const val EXTRA_KEY = "key"

        /**
         * The running instance, if any.
         *
         * Android 12+ forbids starting a foreground service from the background, so the periodic
         * sync worker cannot spin this up on its own — it reaches the already-running service
         * through here instead, and simply retries later if the service is not up.
         */
        @Volatile
        var instance: RingService? = null
            private set

        /** Simple shared state so the UI can observe the service without binding. */
        val state = MutableStateFlow(State())
        val observable: StateFlow<State> get() = state

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, RingService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        /** Ask for a buzz on behalf of one notification group. */
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
