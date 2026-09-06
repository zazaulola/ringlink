package io.github.ringlink.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings as AndroidSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ringlink.ble.DiscoveredRing
import io.github.ringlink.ble.RingBleClient
import io.github.ringlink.ble.RingScanner
import io.github.ringlink.ble.RingService
import io.github.ringlink.L
import io.github.ringlink.data.CsvExport
import io.github.ringlink.data.Ring
import java.util.Calendar
import io.github.ringlink.data.RingDatabase
import io.github.ringlink.data.RingRepository
import io.github.ringlink.data.Settings
import io.github.ringlink.data.DeviceStateEntity
import io.github.ringlink.data.EpochEntity
import io.github.ringlink.data.Summary
import io.github.ringlink.health.HealthExporter
import io.github.ringlink.health.SleepDetector
import io.github.ringlink.health.SleepInput
import io.github.ringlink.watch.AlarmNotifier
import io.github.ringlink.watch.CheckIn
import io.github.ringlink.watch.WatchSettings
import io.github.ringlink.protocol.RingClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class BondedRing(val name: String, val address: String)

/** How far back the history screen looks. */
enum class HistoryWindow(val label: String, val seconds: Long) {
    DAY("24 h", 24 * 3600),
    WEEK("7 d", 7 * 24 * 3600),
    MONTH("30 d", 30 * 24 * 3600),
}

data class UiState(
    val rings: List<Ring> = emptyList(),
    val candidates: List<BondedRing> = emptyList(),
    val discovered: List<DiscoveredRing> = emptyList(),
    val scanning: Boolean = false,
    val storedEpochs: Int = 0,
    val pendingExport: Int = 0,
    val healthConnectAvailable: Boolean = false,
    val healthConnectGranted: Boolean = false,
    val notificationAccess: Boolean = false,
    val buzzOnNotifications: Boolean = true,
    val buzzOnCalls: Boolean = true,
    val exportToHealthConnect: Boolean = true,
    val estimateSleep: Boolean = true,
    val stepGoal: Int = 10_000,
    val exportMessage: String? = null,
    val watchEnabled: Boolean = false,
    val daysSinceExposure: Long? = null,
    val checkInPending: Boolean = false,
    val lastCheckIn: Long = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)
    private val watch = WatchSettings(app)
    private val repo = RingRepository(RingDatabase.get(app).dao())
    val exporter = HealthExporter(app, repo, settings)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val clock = RingClock(settings.epochAnchor)

    /** Counters are stored raw, so a window is converted through the ring's own clock. */
    private val window = MutableStateFlow(HistoryWindow.DAY)
    val selectedWindow: StateFlow<HistoryWindow> = window

    private fun cursorFor(w: HistoryWindow): Long =
        clock.cursorForNow(System.currentTimeMillis() / 1000) - w.seconds

    @OptIn(ExperimentalCoroutinesApi::class)
    val history: StateFlow<List<EpochEntity>> = window
        .flatMapLatest { repo.epochsSince(cursorFor(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Live-descriptor history. Unlike epochs these carry phone wall-clock times, so they need no
     * ring-clock conversion — and they exist only while the phone was connected, which is why the
     * temperature trace has gaps that the heart-rate one does not.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val deviceStates: StateFlow<List<DeviceStateEntity>> = window
        .flatMapLatest { repo.deviceStatesSinceFlow(System.currentTimeMillis() - it.seconds * 1000) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val summary: StateFlow<Summary?> = window
        .flatMapLatest { repo.summarySince(cursorFor(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectWindow(w: HistoryWindow) { window.value = w }

    /**
     * Midnight today, in the ring's counter space.
     *
     * Counters are stored raw, so every "today" query has to be expressed in them rather than in
     * wall-clock milliseconds.
     */
    private fun todayStartCounter(): Long {
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000
        return clock.cursorForNow(midnight)
    }

    private val today = MutableStateFlow(todayStartCounter())

    val stepsToday: StateFlow<Int> = today
        .flatMapLatest { repo.stepsBetween(it, it + DAY_SECONDS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Over the last day rather than since midnight: a morning reading needs last night to exist. */
    val restingHeartRate: StateFlow<Int?> = today
        .flatMapLatest { repo.restingHeartRate(it - DAY_SECONDS, it + DAY_SECONDS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refreshToday() { today.value = todayStartCounter() }

    /** A detected night with the vitals measured during it — what a sleep screen is expected to show. */
    data class Night(
        val startUnix: Long,
        val endUnix: Long,
        val averageHeartRate: Int?,
        val lowestHeartRate: Int?,
        val averageSpo2: Int?,
        val averageHrv: Int?,
    ) {
        val hours: Double get() = (endUnix - startUnix) / 3600.0
    }

    /**
     * Nights in the selected window, newest first.
     *
     * Recomputed from stored epochs rather than read back from Health Connect, so the app does not
     * depend on an export having succeeded — and so turning the estimate off makes it disappear from
     * both places at once.
     */
    val nights: StateFlow<List<Night>> = combine(window, history) { _, rows -> rows }
        .map { rows ->
            if (!settings.estimateSleep) return@map emptyList()
            val periods = SleepDetector.detect(
                rows.map { SleepInput(it.counter, it.heartRate, it.motionSum) },
            )
            periods.map { period ->
                val inside = rows.filter { it.counter in period.startCounter..period.endCounter }
                val hr = inside.mapNotNull { it.heartRate }
                val spo2 = inside.mapNotNull { it.spo2 }
                val hrv = inside.mapNotNull { it.hrvRmssd }
                Night(
                    startUnix = clock.toUnixSeconds(period.startCounter),
                    endUnix = clock.toUnixSeconds(period.endCounter),
                    averageHeartRate = hr.averageOrNull(),
                    lowestHeartRate = hr.minOrNull(),
                    averageSpo2 = spo2.averageOrNull(),
                    averageHrv = hrv.averageOrNull(),
                )
            }.sortedByDescending { it.startUnix }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Convert a stored counter to a wall-clock instant for charting. */
    fun timeOf(counter: Long): Long = clock.toUnixSeconds(counter)

    val service: StateFlow<RingService.State> = RingService.observable

    init {
        repo.epochCount().onEach { n -> _ui.value = _ui.value.copy(storedEpochs = n) }.launchIn(viewModelScope)
        repo.pendingExportCount().onEach { n -> _ui.value = _ui.value.copy(pendingExport = n) }.launchIn(viewModelScope)
        refresh()
    }

    fun refresh() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val configured = settings.rings
            _ui.value = _ui.value.copy(
                rings = configured,
                // Offer only rings that are not configured yet.
                candidates = bondedRings().filterNot { c ->
                    configured.any { it.address.equals(c.address, ignoreCase = true) }
                },
                healthConnectAvailable = exporter.isAvailable(),
                healthConnectGranted = runCatching { exporter.hasPermissions() }.getOrDefault(false),
                notificationAccess = hasNotificationAccess(app),
                buzzOnNotifications = settings.buzzOnNotifications,
                buzzOnCalls = settings.buzzOnCalls,
                exportToHealthConnect = settings.exportToHealthConnect,
                estimateSleep = settings.estimateSleep,
                stepGoal = settings.stepGoal,
                watchEnabled = watch.enabled,
                daysSinceExposure = watch.daysSinceExposure,
                checkInPending = watch.pendingSince != 0L,
                lastCheckIn = watch.lastCheckInAt,
            )
        }
    }

    /**
     * Rings that this phone is already bonded with. BLE bonds live in the Bluetooth stack and are
     * shared by every app, so a ring paired through the vendor app shows up here with no scanning
     * (and therefore no scan or location permission).
     */
    @SuppressLint("MissingPermission")
    private fun bondedRings(): List<BondedRing> {
        val app = getApplication<Application>()
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices
                .filter { RingBleClient.looksLikeRing(it.name) }
                .map { BondedRing(it.name ?: it.address, it.address) }
        }.getOrDefault(emptyList())
    }

    private var scanJob: kotlinx.coroutines.Job? = null

    /**
     * Look for rings that are not paired yet.
     *
     * Adopting a new ring is the only thing that needs a scan; once bonded it is reachable without
     * one. Connecting to a discovered ring is what creates the bond — the ring pairs with
     * "Just Works", so there is no code to enter.
     */
    fun startScan() {
        if (scanJob?.isActive == true) return
        _ui.value = _ui.value.copy(scanning = true, discovered = emptyList())
        scanJob = viewModelScope.launch {
            val known = settings.rings.map { it.address.lowercase() }.toSet()
            withTimeoutOrNull(SCAN_MILLIS) {
                RingScanner(getApplication()).scan().collect { list ->
                    _ui.value = _ui.value.copy(
                        discovered = list.filterNot { it.address.lowercase() in known },
                    )
                }
            }
            _ui.value = _ui.value.copy(scanning = false)
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _ui.value = _ui.value.copy(scanning = false)
    }

    /** Adopt a discovered ring: storing it makes the service connect, which performs the bonding. */
    fun addDiscovered(ring: DiscoveredRing) {
        settings.addRing(Ring(ring.address, ring.name))
        stopScan()
        refresh()
        RingService.start(getApplication())
    }

    fun addRing(ring: BondedRing) {
        settings.addRing(Ring(ring.address, ring.name))
        refresh()
        RingService.start(getApplication())
    }

    fun removeRing(address: String) {
        settings.removeRing(address)
        refresh()
    }

    fun setBuzzOnNotifications(on: Boolean) {
        settings.buzzOnNotifications = on
        _ui.value = _ui.value.copy(buzzOnNotifications = on)
    }

    fun setBuzzOnCalls(on: Boolean) {
        settings.buzzOnCalls = on
        _ui.value = _ui.value.copy(buzzOnCalls = on)
    }

    fun setWatchEnabled(on: Boolean) {
        watch.enabled = on
        if (on) {
            if (watch.exposureAt == 0L) watch.exposureAt = System.currentTimeMillis()
            watch.lastCheckInAt = System.currentTimeMillis()
            CheckIn.schedule(getApplication())
        } else {
            CheckIn.cancel(getApplication())
        }
        refresh()
    }

    fun acknowledgeCheckIn() {
        CheckIn.acknowledge(getApplication())
        refresh()
    }

    /** Fire a check-in right now, so the alarm path can be proven before it is relied on. */
    fun testAlarm() {
        AlarmNotifier(getApplication()).raise(
            title = "Test alarm",
            body = "This is what a real alert will look and sound like.",
        )
        RingService.start(getApplication(), RingService.ACTION_BUZZ)
    }

    fun setStepGoal(goal: Int) {
        settings.stepGoal = goal
        refresh()
    }

    fun setEstimateSleep(on: Boolean) {
        settings.estimateSleep = on
        _ui.value = _ui.value.copy(estimateSleep = on)
    }

    fun setExport(on: Boolean) {
        settings.exportToHealthConnect = on
        _ui.value = _ui.value.copy(exportToHealthConnect = on)
    }

    fun connect() = RingService.start(getApplication())
    fun syncNow() = RingService.start(getApplication(), RingService.ACTION_SYNC)
    fun testBuzz() = RingService.start(getApplication(), RingService.ACTION_BUZZ)
    fun reExport() = RingService.start(getApplication(), RingService.ACTION_REEXPORT)

    fun exportCsv(uri: android.net.Uri) {
        viewModelScope.launch {
            val rows = runCatching {
                CsvExport(getApplication(), RingDatabase.get(getApplication()).dao())
                    .writeTo(uri, clock)
            }.onFailure { L.e("csv export failed", it) }.getOrDefault(0)
            _ui.value = _ui.value.copy(
                exportMessage = if (rows > 0) "Exported ${'$'}rows rows" else "Export failed",
            )
        }
    }
    fun measureHeartRate() = RingService.measure(getApplication(), spo2 = false)
    fun measureSpo2() = RingService.measure(getApplication(), spo2 = true)
    fun findRing() = RingService.start(getApplication(), RingService.ACTION_FIND)

    private companion object {
        const val SCAN_MILLIS = 20_000L
        const val DAY_SECONDS = 24 * 3600L
    }

    private fun hasNotificationAccess(context: Context): Boolean {
        val enabled = AndroidSettings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners",
        ) ?: return false
        return enabled.contains(context.packageName)
    }
}

/** Mean as a whole number, or null when there is nothing to average. */
private fun List<Int>.averageOrNull(): Int? = if (isEmpty()) null else (sum().toDouble() / size).toInt()
