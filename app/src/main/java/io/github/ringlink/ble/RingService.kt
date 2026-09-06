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
import io.github.ringlink.protocol.LiveMeasurement
import io.github.ringlink.protocol.LiveMode
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
    private val measuring = Mutex()
    private var watchdogJob: Job? = null
    private var callMonitor: CallMonitor? = null
    private var notificationStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        repo = RingRepository(RingDatabase.get(this).dao())
        exporter = HealthExporter(this, repo, settings)
        clock = RingClock(settings.epochAnchor, settings.epochCalibrated)
        if (settings.buzzOnCalls) {
            callMonitor = CallMonitor(this) {
                scope.launch { buzz(null, BuzzPattern.CALL) }
            }.also { it.start() }
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
            ACTION_MEASURE -> {
                val mode = if (intent.getStringExtra(EXTRA_MODE) == MODE_SPO2) {
                    LiveMode.SPO2
                } else {
                    LiveMode.HEART_RATE
                }
                scope.launch { measureNow(mode) }
            }
            ACTION_FIND -> scope.launch { findRing() }
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
            if (connected) {
                // Read the ring's own identity once per connection: standard GATT, and the only
                // trustworthy source for generation and firmware.
                runCatching { client.readDeviceInfo() }.getOrNull()?.let { info ->
                    L.i("$address is ${info.summary()}")
                    updateRing(address) { it.copy(info = info) }
                }
                startIdleLoop(address)
            }
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
                                it.copy(
                                    battery = d.batteryPercent,
                                    onCharger = d.onCharger,
                                    skinTemp = d.skinTempA,
                                )
                            }
                            checkBattery(address, d.batteryPercent, d.onCharger)
                        }
                }
            }
        }
    }

    /**
     * Warn once per discharge that a ring is running low.
     *
     * A ring that dies stops recording, and the gap is not recoverable afterwards — so this is worth
     * interrupting for. The flag is cleared when the ring goes on charge, which is what makes it a
     * once-per-discharge warning rather than a repeating nag.
     */
    private fun checkBattery(address: String, percent: Int, onCharger: Boolean) {
        val threshold = settings.lowBatteryPercent
        if (onCharger) {
            if (settings.lowBatteryWarned(address)) settings.setLowBatteryWarned(address, false)
            return
        }
        if (threshold <= 0 || percent > threshold) return
        if (settings.lowBatteryWarned(address)) return

        settings.setLowBatteryWarned(address, true)
        val name = settings.rings.firstOrNull { it.address == address }?.shortName ?: address
        L.i("low battery on $name: $percent%")
        notifyLowBattery(name, percent)
    }

    private fun notifyLowBattery(ringName: String, percent: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    BATTERY_CHANNEL_ID,
                    "Ring battery",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val notification = Notification.Builder(this, BATTERY_CHANNEL_ID)
            .setContentTitle("$ringName is at $percent%")
            .setContentText("Charge it soon — a flat ring records nothing, and that gap cannot be recovered.")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(BATTERY_NOTIFICATION_ID + ringName.hashCode() % 100, notification) }
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
    /**
     * How an alert feels on the finger.
     *
     * Built from repeats of the one vibrate command verified on this hardware rather than from the
     * protocol's pattern byte, of which only 0x01 has been confirmed here. Repetition is
     * distinguishable on a finger and cannot misfire on a ring whose firmware reads that byte
     * differently.
     */
    enum class BuzzPattern(val pulses: Int, val gapMs: Long) {
        NOTIFICATION(1, 0),
        CALL(3, 260),
    }

    suspend fun buzz(key: String?, pattern: BuzzPattern = BuzzPattern.NOTIFICATION) {
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
            val client = clientFor(ring.address)
            val ok = if (ring.canVibrate) {
                var any = false
                repeat(pattern.pulses) { pulse ->
                    if (client.writeReliably(Opcodes.VIBRATE)) any = true
                    if (pulse < pattern.pulses - 1) delay(pattern.gapMs)
                }
                any
            } else {
                // Only Gen 3 has a motor, but every generation has the Find-My-Ring LED — so an
                // older ring signals with light rather than saying nothing at all.
                blink(client)
            }
            if (ok) {
                delivered = true
                val how = if (ring.canVibrate) "buzz" else "blink"
                L.i("$how delivered to ${ring.shortName}${if (key != null) " ($key)" else ""}")
            } else {
                L.w("alert dropped: ${ring.shortName} did not accept the write")
            }
        }
        if (delivered && key != null) synchronized(lastBuzz) { lastBuzz[key] = System.currentTimeMillis() }
    }

    /** Pulse the locator LED: the only signal a ring without a motor can give. */
    private suspend fun blink(client: RingBleClient): Boolean {
        if (!client.writeReliably(Opcodes.LED_ON)) return false
        delay(LED_ON_MS)
        // Best effort: the light times out on its own, so a failed off is not a failed alert.
        client.writeReliably(Opcodes.LED_OFF)
        return true
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
                    val sleep = exporter.exportSleep(clock)
                    L.i("health connect export: $exported rows, $sleep sleep sessions")
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

    /**
     * Take a reading right now, the way the vendor app's "measure" button does.
     *
     * The measurement owns the link while it runs — one consumer per frame channel — so the idle
     * loop is stopped first and restarted afterwards, exactly as a sync does. Only a worn ring is
     * measured: a ring in its charger has no finger against the sensor and would report nothing.
     */
    suspend fun measureNow(mode: LiveMode) {
        val ring = settings.rings.firstOrNull { r ->
            clientFor(r.address).isConnected && state.value.ringOrNull(r.address)?.onCharger != true
        } ?: settings.rings.firstOrNull { clientFor(it.address).isConnected }

        if (ring == null) {
            state.value = state.value.copy(measuring = null, status = "No ring connected")
            return
        }
        if (!measuring.tryLock()) {
            L.d("measurement already running")
            return
        }
        try {
            val client = clientFor(ring.address)
            idleJobs.remove(ring.address)?.cancelAndJoin()
            state.value = state.value.copy(
                measuring = Measuring(mode, ring.shortName, null, 0),
                status = "Measuring ${mode.label.lowercase()}…",
            )
            L.i("live ${mode.name} measurement on ${ring.shortName}")

            val result = LiveMeasurement(client).measure(mode) { sample ->
                state.value = state.value.copy(
                    measuring = Measuring(mode, ring.shortName, sample.value, sample.elapsedSeconds),
                )
            }

            state.value = state.value.copy(
                measuring = null,
                lastMeasurement = result?.let { LastMeasurement(mode, it, System.currentTimeMillis()) }
                    ?: state.value.lastMeasurement,
                status = if (result != null) {
                    "${mode.label}: $result${if (mode == LiveMode.SPO2) "%" else " bpm"}"
                } else {
                    "No reading — make sure the ring is snug on your finger"
                },
            )
            L.i("live ${mode.name} result=$result")
        } finally {
            measuring.unlock()
            startIdleLoop(ring.address)
        }
    }

    /**
     * Blink every connected ring's locator LED, to find one that has been put down somewhere.
     *
     * Deliberately not restricted to worn rings: a ring you are looking for is by definition not on
     * your finger.
     */
    suspend fun findRing() {
        val connected = settings.rings.filter { clientFor(it.address).isConnected }
        if (connected.isEmpty()) {
            state.value = state.value.copy(status = "No ring connected")
            return
        }
        state.value = state.value.copy(status = "Blinking ${connected.size} ring(s)…")
        repeat(FIND_BLINKS) {
            connected.forEach { ring ->
                val client = clientFor(ring.address)
                client.writeReliably(Opcodes.LED_ON)
                // Gen 3 can buzz as well; on a ring without a motor the light is the whole signal.
                if (ring.canVibrate) client.writeReliably(Opcodes.VIBRATE)
            }
            delay(FIND_ON_MS)
            connected.forEach { clientFor(it.address).writeReliably(Opcodes.LED_OFF) }
            delay(FIND_OFF_MS)
        }
        state.value = state.value.copy(status = "Connected")
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
        updateNotification()
    }

    private fun updateRing(address: String, transform: (RingState) -> RingState) {
        val current = state.value.rings
        val existing = current.firstOrNull { it.address == address }
            ?: RingState(address, settings.rings.firstOrNull { it.address == address }?.name ?: address)
        state.value = state.value.copy(
            rings = (current.filterNot { it.address == address } + transform(existing))
                .sortedBy { it.address },
        )
        updateNotification()
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ring connection", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) },
            )
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    /**
     * Keep the ongoing notification honest about what is actually reachable.
     *
     * This notification has to exist anyway — a foreground service requires one — so it may as well
     * be the status display. A ring that has quietly dropped off is otherwise invisible until a
     * notification fails to reach it, which is exactly the wrong moment to find out.
     */
    private fun updateNotification() {
        if (!notificationStarted) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        notificationStarted = true
        val rings = state.value.rings
        val unreachable = rings.filter { !it.connected }

        val title = when {
            rings.isEmpty() -> "No ring configured"
            unreachable.isEmpty() -> "Rings connected"
            unreachable.size == rings.size -> "Ring unreachable"
            else -> "${unreachable.joinToString(", ") { it.shortName }} unreachable"
        }

        val detail = rings.joinToString(" · ") { ring ->
            val charge = ring.battery?.let { "$it%" }
            // Charging state only becomes known once the ring sends a descriptor, so until then
            // say nothing about it rather than asserting "worn" on no evidence.
            val where = when {
                !ring.connected -> "offline"
                ring.battery == null -> "connected"
                ring.onCharger -> "charging"
                else -> "worn"
            }
            listOfNotNull(ring.shortName, charge, where).joinToString(" ")
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail.ifEmpty { "Open RingLink to add a ring" })
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    data class RingState(
        val address: String,
        val name: String,
        val connected: Boolean = false,
        val battery: Int? = null,
        val onCharger: Boolean = false,
        val skinTemp: Double? = null,
        val info: DeviceInfo? = null,
    ) {
        val shortName: String get() = name.substringAfterLast('-', name)
        val canVibrate: Boolean get() = Ring(address, name).canVibrate
    }

    /** A measurement in flight, so the UI can show the value settling. */
    data class Measuring(
        val mode: LiveMode,
        val ringName: String,
        val latest: Int?,
        val elapsedSeconds: Int,
    )

    data class LastMeasurement(val mode: LiveMode, val value: Int, val at: Long)

    data class State(
        val rings: List<RingState> = emptyList(),
        val measuring: Measuring? = null,
        val lastMeasurement: LastMeasurement? = null,
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
        const val ACTION_MEASURE = "io.github.ringlink.MEASURE"
        const val ACTION_FIND = "io.github.ringlink.FIND"
        const val EXTRA_MODE = "mode"
        const val MODE_SPO2 = "spo2"
        const val EXTRA_KEY = "key"

        private const val CHANNEL_ID = "ring_link"
        private const val NOTIFICATION_ID = 1
        private const val BATTERY_CHANNEL_ID = "ring_battery"
        private const val BATTERY_NOTIFICATION_ID = 100
        private const val MIN_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val STALE_BUZZ_MS = 15_000L
        private const val WATCHDOG_INTERVAL_MS = 45_000L
        private const val BUZZ_COOLDOWN_MS = 3_000L
        private const val LED_ON_MS = 400L
        private const val MAX_TRACKED_KEYS = 64
        private const val FIND_BLINKS = 6
        private const val FIND_ON_MS = 500L
        private const val FIND_OFF_MS = 400L

        @Volatile
        var instance: RingService? = null
            private set

        val state = MutableStateFlow(State())
        val observable: StateFlow<State> get() = state

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, RingService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun measure(context: Context, spo2: Boolean) {
            val i = Intent(context, RingService::class.java).apply {
                action = ACTION_MEASURE
                if (spo2) putExtra(EXTRA_MODE, MODE_SPO2)
            }
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
