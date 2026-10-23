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
import io.github.ringlink.protocol.RecordSink
import io.github.ringlink.protocol.RingClock
import io.github.ringlink.protocol.SyncSession
import io.github.ringlink.trigger.BurstLimiter
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
    private val notificationBurst = BurstLimiter()
    private val chargerWatch = ChargerWatch()
    private val lastDescriptorAt = ConcurrentHashMap<String, Long>()
    private val lastWakeAt = ConcurrentHashMap<String, Long>()
    @Volatile private var lastFallbackSyncAt = 0L
    private val syncing = Mutex()
    private val measuring = Mutex()
    private var watchdogJob: Job? = null
    private var callMonitor: CallMonitor? = null
    private var callAlertJob: Job? = null
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
            callMonitor = CallMonitor(
                context = this,
                onRingingStarted = { startCallAlert() },
                onRingingStopped = { stopCallAlert() },
            ).also { it.start() }
        }
        startForegroundNotification()
        publishRings()
        scope.launch { restoreCaseHistory() }
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
            ACTION_EXPORT_HISTORY -> {
                scope.launch { connectAll() }
                scope.launch { exportHistory() }
            }
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
        callAlertJob?.cancel()
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
                onReady = { scope.launch { onRingReady(address) } }
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
            // Identity, the idle loop and waking the ring's telemetry happen in onRingReady, which
            // runs for every connection — including background connects nobody is awaiting.
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
                        Descriptor.parse(frame)?.let { handleDescriptor(address, it) }
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
        notifyLowBattery(address, name, percent)
    }

    private fun notifyLowBattery(address: String, ringName: String, percent: Int) {
        // In the two-ring routine the useful thing to say is which ring to put on instead.
        val spare = state.value.rings.firstOrNull {
            it.address != address && it.connected && (it.battery ?: 0) >= SWAP_READY_PERCENT
        }
        val text = if (spare != null) {
            "Swap to ${spare.shortName} — it is at ${spare.battery}%."
        } else {
            "Charge it soon — a flat ring records nothing, and that gap cannot be recovered."
        }
        postRingNotice(KIND_LOW_BATTERY, address, "$ringName is at $percent%", text)
    }

    /**
     * Follow the charging case through the ring docked in it.
     *
     * The case has no radio; a docked ring reports its charge in descriptor byte 17. That is all
     * the "connection to the charger" there can be, so this is where it is turned into something
     * useful: remembering the case once the ring is out, and saying when the spare is ready or the
     * case is about to stop being able to charge it.
     */
    private fun watchCharger(address: String, d: Descriptor, charging: Boolean) {
        val casePercent = d.caseBatteryPercent
        if (casePercent != null) {
            updateRing(address) {
                it.copy(caseLastPercent = casePercent, caseLastSeenAt = System.currentTimeMillis())
            }
        }
        val name = settings.rings.firstOrNull { it.address == address }?.shortName ?: address
        val events = chargerWatch.update(
            ChargerWatch.Reading(
                ringId = address,
                battery = d.batteryPercent,
                charging = charging,
                casePercent = casePercent,
                casePluggedIn = d.caseCharging,
            ),
        )
        for (event in events) {
            when (event) {
                is ChargerWatch.Event.RingCharged -> {
                    L.i("$name charged to ${event.battery}%")
                    postRingNotice(KIND_CHARGED, address, "$name is charged", "Ready to swap onto your finger.")
                }
                is ChargerWatch.Event.CaseLow -> {
                    L.i("$name's case is at ${event.casePercent}%")
                    postRingNotice(
                        KIND_CASE_LOW,
                        address,
                        "$name's charging case is at ${event.casePercent}%",
                        "Plug the case in — when it runs flat it cannot charge the ring.",
                    )
                }
            }
        }
    }

    /** Pre-fill what was last known about each ring's case, so it shows before the ring is docked. */
    private suspend fun restoreCaseHistory() {
        for (ring in settings.rings) {
            val last = runCatching { repo.latestCaseReading(ring.address) }.getOrNull() ?: continue
            updateRing(ring.address) {
                it.copy(caseLastPercent = last.caseBattery, caseLastSeenAt = last.recordedAt)
            }
        }
    }

    /**
     * Post a notice about one ring.
     *
     * The id is derived from the kind and the ring with floorMod, so it is never negative and two
     * rings never overwrite each other's notice — the previous `hashCode() % 100` could do both.
     */
    private fun postRingNotice(kind: Int, address: String, title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    BATTERY_CHANNEL_ID,
                    "Ring battery and charger",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val notification = Notification.Builder(this, BATTERY_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .build()
        val id = BATTERY_NOTIFICATION_ID + kind * 1000 + Math.floorMod(address.hashCode(), 1000)
        runCatching { nm.notify(id, notification) }
    }

    /**
     * Everything that follows from one descriptor, whichever route it arrived by.
     *
     * Descriptors come both from the idle loop and in the middle of a sync. They used to take two
     * paths, and the sync path only stored them — so a reading that arrived during a sync never
     * reached the battery check or the charger watch.
     */
    private suspend fun handleDescriptor(address: String, d: Descriptor) {
        repo.sinkFor(address).onDescriptor(d)
        lastDescriptorAt[address] = System.currentTimeMillis()
        val charging = repo.isCharging(address)
        updateRing(address) {
            it.copy(
                battery = d.batteryPercent,
                onCharger = charging,
                worn = d.looksWorn,
                skinTemp = d.skinTempA,
                caseBattery = d.caseBatteryPercent,
                caseCharging = d.caseCharging,
            )
        }
        checkBattery(address, d.batteryPercent, charging)
        watchCharger(address, d, charging)
    }

    /**
     * Everything a fresh connection needs, however it came about.
     *
     * This used to run only after a direct connect. A ring that came back through the background
     * connect — most of the time, since the ring advertises only in short windows — got the idle
     * loop and nothing else, so its battery, temperature and case stayed blank until the watchdog
     * noticed the silence.
     */
    private suspend fun onRingReady(address: String) {
        val client = clientFor(address)
        // Standard GATT, and the only trustworthy source for generation and firmware.
        runCatching { client.readDeviceInfo() }.getOrNull()?.let { info ->
            L.i("$address is ${info.summary()}")
            updateRing(address) { it.copy(info = info) }
        }
        startIdleLoop(address)
        // A new connection deserves its first wake straight away; the rate limit is there to stop
        // repeated wakes of one silent connection, not to delay the first one after a reconnect.
        lastWakeAt.remove(address)
        wakeTelemetry(address)
    }

    /** The ring's sink for a sync, with descriptors routed through [handleDescriptor]. */
    private fun liveSink(address: String): RecordSink {
        val base = repo.sinkFor(address)
        return object : RecordSink by base {
            override suspend fun onDescriptor(descriptor: Descriptor) = handleDescriptor(address, descriptor)
        }
    }

    /**
     * Get the ring reporting its state — battery, temperature, charging, and the case.
     *
     * A connection on its own is not enough. On a Gen 3 the ring sends no descriptor at all until a
     * session is open: a documented observation, and what was seen here, where battery and case
     * stayed blank after every reconnect until the next sync. That left the charger effectively out
     * of reach for hours at a time.
     *
     * So after connecting this authenticates and asks, trying the two documented state requests in
     * turn — `07 00 00` for a `0x87`, then `d0 00 00` for a `0x10`. Neither is destructive: history
     * is only lost when a page is acknowledged, and nothing here acknowledges anything. If the ring
     * still says nothing, a sync is the one thing known to wake it, so that is the fallback —
     * outside quiet hours, which exist to leave the night's backlog alone.
     */
    private suspend fun wakeTelemetry(address: String) {
        if (measuring.isLocked) return
        // At most once per stale period per ring. Without this a ring that never answers would be
        // woken on every watchdog tick, each failure falling back to a full sync — a sync every
        // 45 seconds, for as long as it stayed silent.
        val now = System.currentTimeMillis()
        val last = lastWakeAt[address]
        if (last != null && now - last < TELEMETRY_STALE_MS) return
        lastWakeAt[address] = now
        // A sync opens its own session, which is all this does; never run alongside one.
        if (!syncing.tryLock()) return
        var flowing = false
        try {
            val client = clientFor(address)
            if (!client.isConnected) return
            idleJobs.remove(address)?.cancelAndJoin()
            val session = SyncSession(address, client, liveSink(address), clock)
            if (!session.authenticate()) {
                L.w("telemetry: authentication failed for $address")
                return
            }
            // 07 first: measured on a Gen 3 (FR05.010), d0 drew no answer in 6 s while 07 brought a
            // descriptor within a second. d0 stays as the fallback, being what other firmware is
            // documented to answer.
            for ((label, request) in listOf("07" to Opcodes.FETCH, "d0" to Opcodes.STATUS_QUERY)) {
                client.write(request)
                val d = awaitDescriptor(client, TELEMETRY_WAIT_MS)
                if (d != null) {
                    handleDescriptor(address, d)
                    L.i("telemetry flowing for $address after $label")
                    flowing = true
                    return
                }
            }
            L.w("telemetry: $address sent no state after 07 or d0")
        } finally {
            syncing.unlock()
            startIdleLoop(address)
            val nowMs = System.currentTimeMillis()
            if (!flowing &&
                !settings.isQuietHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) &&
                nowMs - lastFallbackSyncAt > FALLBACK_SYNC_MIN_GAP_MS
            ) {
                // Bounded separately from the wake: a link that keeps dropping and coming back must
                // not turn into a sync on every reconnect.
                lastFallbackSyncAt = nowMs
                scope.launch { syncAll(force = true) }
            }
        }
    }

    /** Wait for a descriptor, answering heartbeats meanwhile so the link stays healthy. */
    private suspend fun awaitDescriptor(client: RingBleClient, timeoutMs: Long): Descriptor? =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val frame = client.incoming.receive()
                if (frame.isEmpty()) continue
                when (frame[0].toInt() and 0xff) {
                    Opcodes.RESP_HEARTBEAT -> client.write(Opcodes.HEARTBEAT_ACK)
                    Opcodes.RESP_DESCRIPTOR_QUERY, Opcodes.RESP_DESCRIPTOR_FETCH ->
                        Descriptor.parse(frame)?.let { return@withTimeoutOrNull it }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                // A measurement or sync owns the link while it runs, and reconnecting under it
                // tears the session down — which is exactly how a measurement came back empty.
                if (measuring.isLocked || syncing.isLocked) continue
                val now = System.currentTimeMillis()
                settings.rings.forEach { ring ->
                    val lastState = lastDescriptorAt[ring.address]
                    val quietFor = if (lastState == null) Long.MAX_VALUE else now - lastState
                    if (clientFor(ring.address).isConnected && quietFor > TELEMETRY_STALE_MS) {
                        val since = if (lastState == null) "since connecting" else "for ${quietFor / 1000}s"
                        L.d("watchdog: no state from ${ring.address} $since, waking it")
                        wakeTelemetry(ring.address)
                    }
                }
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
     * Keep buzzing for as long as the phone rings.
     *
     * A notification is a fact to be told once; a ringing phone is a thing waiting to be answered,
     * and the ring is often the only part of it the wearer can feel. So this repeats until the call
     * is picked up or gives up, rather than announcing itself once and leaving.
     *
     * It is bounded anyway: no real call rings past [MAX_CALL_ALERT_MS], and if the end of the call
     * were ever missed the ring must not be left buzzing indefinitely.
     */
    private fun startCallAlert() {
        callAlertJob?.cancel()
        callAlertJob = scope.launch {
            val until = System.currentTimeMillis() + MAX_CALL_ALERT_MS
            while (isActive && System.currentTimeMillis() < until) {
                buzz(null, BuzzPattern.CALL)
                delay(CALL_ALERT_INTERVAL_MS)
            }
        }
    }

    private fun stopCallAlert() {
        callAlertJob?.cancel()
        callAlertJob = null
    }

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
        if (key != null && !notificationBurst.allows(requestedAt)) {
            L.d("buzz skipped: burst budget spent ($key)")
            return
        }

        settings.rings.forEach { ring -> ensureConnected(ring.address) }

        val connected = settings.rings.filter { clientFor(it.address).isConnected }
        if (connected.isEmpty()) {
            L.w("buzz dropped: no ring connected")
            return
        }
        // Prefer rings that look worn; fall back to any that is merely not charging, so a ring
        // whose temperature has not arrived yet is not passed over.
        val notCharging = connected.filterNot { state.value.ringOrNull(it.address)?.onCharger == true }
        val worn = notCharging.filter { state.value.ringOrNull(it.address)?.worn == true }
            .ifEmpty { notCharging }
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
        // Spend the budget only on a buzz the ring actually took: one that never arrived was not
        // felt, and must not silence the next notification.
        // Spend the budget only on a buzz the ring actually took: one that never arrived was not
        // felt, and must not silence the next notification.
        if (delivered && key != null) notificationBurst.spend()
    }

    /** Pulse the locator LED: the only signal a ring without a motor can give. */
    private suspend fun blink(client: RingBleClient): Boolean {
        if (!client.writeReliably(Opcodes.LED_ON)) return false
        delay(LED_ON_MS)
        // Best effort: the light times out on its own, so a failed off is not a failed alert.
        client.writeReliably(Opcodes.LED_OFF)
        return true
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
                    val exported = exporter.exportAllPending(clock)
                    val sleep = exporter.exportSleep(clock)
                    val steps = exporter.exportSteps()
                    val temperature = if (!settings.temperatureHourlyMigrated) {
                        // Records written by earlier versions carry batch-derived ids and include
                        // room-temperature readings; the hourly ones would sit on top of them. Once,
                        // remove them all and rewrite the whole history the new way.
                        if (exporter.deleteExportedSkinTemperature()) {
                            L.i("migrating skin temperature to hourly records")
                            val n = exporter.exportTemperature(windowMillis = null)
                            settings.temperatureHourlyMigrated = true
                            n
                        } else {
                            exporter.exportTemperature()
                        }
                    } else {
                        exporter.exportTemperature()
                    }
                    L.i("health connect export: $exported rows, $sleep sleep sessions, $steps step quarters, $temperature temperature hours")
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
            val session = SyncSession(ring.address, client, liveSink(ring.address), clock)
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

            val result = LiveMeasurement(client).measure(
                mode = mode,
                stillConnected = { client.isConnected },
            ) { sample ->
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

    /**
     * Rewrite everything this app has put into Health Connect.
     *
     * Sleep and steps are deleted first and recomputed over all of history: both are derived
     * rather than stored, and older versions derived them on a wrong basis. Recomputing only a
     * recent window — as this once did for sleep — would have deleted every older night outright.
     */
    private suspend fun reExport() {
        state.value = state.value.copy(status = "Re-exporting…")
        val n = runCatching { exporter.reExportAll(clock) }
            .onFailure { L.e("re-export failed", it) }
            .getOrDefault(0)
        // Each derived kind is deleted immediately before it is rewritten, under the same gates as
        // the rewrite, so nothing is ever deleted that is not then written back.
        val (sleep, steps, temperature) = rewriteDerived(fresh = true)
        L.i("re-exported $n rows, $sleep sleep sessions, $steps step quarters, $temperature temperature hours")
        state.value = state.value.copy(status = "Re-exported $n records, $sleep nights, $steps step quarters")
    }

    /**
     * Write sleep, steps and temperature for the whole history.
     *
     * With [fresh], what this app wrote before is removed first — needed when the way a kind is
     * derived has changed. Without it, records are only added or replaced in place by their ids.
     */
    private suspend fun rewriteDerived(fresh: Boolean): Triple<Int, Int, Int> {
        if (fresh && exporter.deleteExportedSleepSessions()) L.i("removed previously exported sleep sessions")
        val sleep = runCatching { exporter.exportSleep(clock, windowSeconds = null) }
            .onFailure { L.e("sleep export failed", it) }.getOrDefault(0)
        if (fresh && exporter.deleteExportedSteps()) L.i("removed previously exported steps")
        val steps = runCatching { exporter.exportSteps(windowMillis = null) }
            .onFailure { L.e("steps export failed", it) }.getOrDefault(0)
        if (fresh && exporter.deleteExportedSkinTemperature()) L.i("removed previously exported skin temperature")
        val temperature = runCatching { exporter.exportTemperature(windowMillis = null) }
            .onFailure { L.e("temperature export failed", it) }.getOrDefault(0)
        return Triple(sleep, steps, temperature)
    }

    /** After a restore: everything waiting, plus the derived kinds over all of history. */
    private suspend fun exportHistory() {
        state.value = state.value.copy(status = "Writing restored history to Health Connect…")
        val rows = runCatching { exporter.exportAllPending(clock) }
            .onFailure { L.e("history export failed", it) }.getOrDefault(0)
        val (sleep, steps, temperature) = rewriteDerived(fresh = false)
        L.i("history export: $rows rows, $sleep sleep sessions, $steps step quarters, $temperature temperature hours")
        state.value = state.value.copy(status = "Restored history written to Health Connect")
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
                ring.onCharger -> "charging"
                ring.battery == null -> "connected"
                ring.worn -> "worn"
                else -> "off finger"
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
        /** Judged from skin temperature, not stated by the ring — see Descriptor.looksWorn. */
        val worn: Boolean = false,
        val skinTemp: Double? = null,
        val info: DeviceInfo? = null,
        val caseBattery: Int? = null,
        val caseCharging: Boolean = false,
        /** The case's charge the last time this ring sat in it, kept after the ring is taken out. */
        val caseLastPercent: Int? = null,
        val caseLastSeenAt: Long? = null,
    ) {
        /** In the case right now, as opposed to merely remembered. */
        val docked: Boolean get() = caseBattery != null
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
        const val ACTION_EXPORT_HISTORY = "io.github.ringlink.EXPORT_HISTORY"
        const val ACTION_MEASURE = "io.github.ringlink.MEASURE"
        const val ACTION_FIND = "io.github.ringlink.FIND"
        const val EXTRA_MODE = "mode"
        const val MODE_SPO2 = "spo2"
        const val EXTRA_KEY = "key"

        private const val CHANNEL_ID = "ring_link"
        private const val NOTIFICATION_ID = 1
        private const val BATTERY_CHANNEL_ID = "ring_battery"
        private const val BATTERY_NOTIFICATION_ID = 100
        private const val KIND_LOW_BATTERY = 1
        private const val KIND_CHARGED = 2
        private const val KIND_CASE_LOW = 3

        /** A spare at least this full is worth suggesting as the one to swap to. */
        private const val SWAP_READY_PERCENT = 80

        /** How long to wait for the ring to answer a state request. */
        private const val TELEMETRY_WAIT_MS = 6_000L

        /**
         * Connected but silent for this long means the ring has stopped reporting. Descriptors
         * normally come every 30-60 s, so this is several missed ones, not one late one.
         */
        private const val TELEMETRY_STALE_MS = 10 * 60_000L

        /** A sync used only to wake a silent ring, at most this often. */
        private const val FALLBACK_SYNC_MIN_GAP_MS = 30 * 60_000L
        private const val MIN_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val STALE_BUZZ_MS = 15_000L
        private const val WATCHDOG_INTERVAL_MS = 45_000L
        /** Gap between buzzes while the phone is ringing. */
        private const val CALL_ALERT_INTERVAL_MS = 3_000L

        /** A backstop, in case the end of a call is ever missed. */
        private const val MAX_CALL_ALERT_MS = 90_000L
        private const val LED_ON_MS = 400L
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
