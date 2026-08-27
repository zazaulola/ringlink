package io.github.ringlink.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings as AndroidSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ringlink.ble.RingBleClient
import io.github.ringlink.ble.RingService
import io.github.ringlink.data.RingDatabase
import io.github.ringlink.data.RingRepository
import io.github.ringlink.data.Settings
import io.github.ringlink.data.EpochEntity
import io.github.ringlink.data.Summary
import io.github.ringlink.health.HealthExporter
import io.github.ringlink.protocol.RingClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class BondedRing(val name: String, val address: String)

/** How far back the history screen looks. */
enum class HistoryWindow(val label: String, val seconds: Long) {
    DAY("24 h", 24 * 3600),
    WEEK("7 d", 7 * 24 * 3600),
    MONTH("30 d", 30 * 24 * 3600),
}

data class UiState(
    val ring: BondedRing? = null,
    val candidates: List<BondedRing> = emptyList(),
    val storedEpochs: Int = 0,
    val pendingExport: Int = 0,
    val healthConnectAvailable: Boolean = false,
    val healthConnectGranted: Boolean = false,
    val notificationAccess: Boolean = false,
    val buzzOnNotifications: Boolean = true,
    val buzzOnCalls: Boolean = true,
    val exportToHealthConnect: Boolean = true,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val summary: StateFlow<Summary?> = window
        .flatMapLatest { repo.summarySince(cursorFor(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectWindow(w: HistoryWindow) { window.value = w }

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
        val saved = settings.ringAddress?.let { BondedRing(settings.ringName ?: it, it) }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                ring = saved,
                candidates = bondedRings(),
                healthConnectAvailable = exporter.isAvailable(),
                healthConnectGranted = runCatching { exporter.hasPermissions() }.getOrDefault(false),
                notificationAccess = hasNotificationAccess(app),
                buzzOnNotifications = settings.buzzOnNotifications,
                buzzOnCalls = settings.buzzOnCalls,
                exportToHealthConnect = settings.exportToHealthConnect,
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

    fun selectRing(ring: BondedRing) {
        settings.ringAddress = ring.address
        settings.ringName = ring.name
        _ui.value = _ui.value.copy(ring = ring)
        RingService.start(getApplication())
    }

    fun setBuzzOnNotifications(on: Boolean) {
        settings.buzzOnNotifications = on
        _ui.value = _ui.value.copy(buzzOnNotifications = on)
    }

    fun setBuzzOnCalls(on: Boolean) {
        settings.buzzOnCalls = on
        _ui.value = _ui.value.copy(buzzOnCalls = on)
    }

    fun setExport(on: Boolean) {
        settings.exportToHealthConnect = on
        _ui.value = _ui.value.copy(exportToHealthConnect = on)
    }

    fun connect() = RingService.start(getApplication())
    fun syncNow() = RingService.start(getApplication(), RingService.ACTION_SYNC)
    fun testBuzz() = RingService.start(getApplication(), RingService.ACTION_BUZZ)
    fun reExport() = RingService.start(getApplication(), RingService.ACTION_REEXPORT)

    private fun hasNotificationAccess(context: Context): Boolean {
        val enabled = AndroidSettings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners",
        ) ?: return false
        return enabled.contains(context.packageName)
    }
}
