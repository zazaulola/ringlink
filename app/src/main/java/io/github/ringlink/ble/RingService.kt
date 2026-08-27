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
    private var callMonitor: CallMonitor? = null
    private val busy = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        repo = RingRepository(RingDatabase.get(this).dao())
        exporter = HealthExporter(this, repo, settings)
        clock = RingClock(settings.epochAnchor)
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
            ACTION_BUZZ -> scope.launch { buzz() }
            else -> scope.launch { ensureConnected() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        idleJob?.cancel()
        callMonitor?.stop()
        client.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    // --- connection ------------------------------------------------------------------------------

    private suspend fun ensureConnected(): Boolean {
        if (client.isConnected) return true
        val address = settings.ringAddress ?: return false
        state.value = state.value.copy(status = "Connecting…")
        val ok = client.connect(address)
        state.value = state.value.copy(connected = ok, status = if (ok) "Connected" else "Not connected")
        if (ok) {
            startIdleLoop()
            // A stale ring is worth draining as soon as it shows up, but never mid-night.
            if (shouldAutoSync()) syncNow(force = false)
        }
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
    suspend fun buzz() {
        if (!ensureConnected()) return
        client.write(Opcodes.VIBRATE)
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
                if (!session.authenticate()) {
                    state.value = state.value.copy(status = "Authentication failed")
                    return@withLock
                }
                val stats = session.syncHistory()
                settings.epochAnchor = clock.epoch()
                settings.lastSyncAt = System.currentTimeMillis()
                state.value = state.value.copy(
                    status = "Synced ${stats.epochs} records",
                    lastSyncAt = settings.lastSyncAt,
                )
                if (settings.exportToHealthConnect) {
                    val exported = exporter.exportPending(clock)
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
        private const val CHANNEL_ID = "ring_link"
        private const val NOTIFICATION_ID = 1
        private const val MIN_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /** Simple shared state so the UI can observe the service without binding. */
        val state = MutableStateFlow(State())
        val observable: StateFlow<State> get() = state

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, RingService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}
