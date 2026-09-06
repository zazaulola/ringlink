package io.github.ringlink.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ringlink.health.HealthExporter
import io.github.ringlink.protocol.LiveMeasurement
import io.github.ringlink.protocol.LiveMode
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RingLinkApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RingLinkApp(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val service by vm.service.collectAsState()

    val runtimePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.refresh() }

    // Storage Access Framework: the user picks where the file goes, so the app needs no storage
    // permission and the export lands somewhere they chose rather than in private app storage.
    val exportCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { vm.exportCsv(it) } }

    val healthPermissions = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { vm.refresh() }

    var tab by remember { mutableIntStateOf(0) }

    Scaffold(topBar = { TopAppBar(title = { Text("RingLink") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Status") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
            }
            Column(
                Modifier
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (tab == 1) {
                    HistoryScreen(vm)
                    return@Column
                }
            TodayCard(vm)

            SectionCard("Rings") {
                if (ui.rings.isEmpty()) {
                    Text("No rings yet.")
                    Text(
                        "Rings already paired with this phone appear below — pairing is shared " +
                            "between apps, so there is nothing to scan for.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    ui.rings.forEach { ring ->
                        val live = service.rings.firstOrNull { it.address == ring.address }
                        RingRow(
                            title = ring.shortName,
                            connected = live?.connected == true,
                            battery = live?.battery,
                            onCharger = live?.onCharger == true,
                            canVibrate = ring.canVibrate,
                            skinTemp = live?.skinTemp,
                            info = live?.info?.summary(),
                            caseBattery = live?.caseBattery,
                            onRemove = { vm.removeRing(ring.address) },
                        )
                    }
                    Text(service.status, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.connect() }) { Text("Connect") }
                        Button(onClick = { vm.syncNow() }, enabled = !service.syncing) { Text("Sync now") }
                        OutlinedButton(onClick = { vm.testBuzz() }) { Text("Buzz") }
                    }
                }

                Text("Add a ring", style = MaterialTheme.typography.titleSmall)
                ui.candidates.forEach { candidate ->
                    OutlinedButton(onClick = { vm.addRing(candidate) }, Modifier.fillMaxWidth()) {
                        Text("${candidate.name}  (paired)")
                    }
                }
                ui.discovered.forEach { found ->
                    OutlinedButton(onClick = { vm.addDiscovered(found) }, Modifier.fillMaxWidth()) {
                        Text("${found.name}  (${found.rssi} dBm)")
                    }
                }
                if (ui.scanning) {
                    Text("Searching… take the ring off its charger and hold it near the phone.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { vm.stopScan() }, Modifier.fillMaxWidth()) { Text("Stop") }
                } else {
                    OutlinedButton(onClick = { vm.startScan() }, Modifier.fillMaxWidth()) {
                        Text("Search for a new ring")
                    }
                }
            }

            SectionCard("Measure now") {
                val live = service.measuring
                if (live != null) {
                    Text(
                        live.latest?.let { "$it${if (live.mode == LiveMode.SPO2) " %" else " bpm"}" }
                            ?: "…",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "${live.mode.label} on ${live.ringName} · ${live.elapsedSeconds}s of " +
                            "${LiveMeasurement.DEFAULT_DURATION_SECONDS}s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = {
                            live.elapsedSeconds.toFloat() /
                                LiveMeasurement.DEFAULT_DURATION_SECONDS
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    service.lastMeasurement?.let { last ->
                        val unit = if (last.mode == LiveMode.SPO2) "%" else " bpm"
                        Text("Last: ${last.mode.label} ${last.value}$unit")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.measureHeartRate() }) { Text("Heart rate") }
                        Button(onClick = { vm.measureSpo2() }) { Text("Blood oxygen") }
                    }
                    Text(
                        "Takes about ${LiveMeasurement.DEFAULT_DURATION_SECONDS} seconds. Keep the " +
                            "ring snug and your hand still — the sensor needs a few seconds to " +
                            "settle, so the reading moves before it lands.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = { vm.findRing() }, Modifier.fillMaxWidth()) {
                    Text("Find my ring")
                }
                Text(
                    "Blinks the locator light (and buzzes, on a ring that can) for a few seconds.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard("Data") {
                Text("${ui.storedEpochs} records stored locally")
                Text("${ui.pendingExport} waiting to export")
                if (service.lastSyncAt > 0) {
                    Text(
                        "Last sync: " + DateFormat.getDateTimeInstance()
                            .format(Date(service.lastSyncAt)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "History is a destructive read — the ring drops each page once it is " +
                        "acknowledged, so everything is written here first and exported afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard("Health Connect") {
                when {
                    !ui.healthConnectAvailable ->
                        Text("Not available on this device.")
                    ui.healthConnectGranted ->
                        Text("Connected — heart rate, HRV, SpO₂, respiratory rate, skin temperature, steps and estimated sleep.")
                    else -> {
                        Text("Permission needed to write your ring data.")
                        Button(onClick = { healthPermissions.launch(vm.exporter.permissions) }) {
                            Text("Grant")
                        }
                    }
                }
                ToggleRow("Export to Health Connect", ui.exportToHealthConnect, vm::setExport)
                ToggleRow("Estimate sleep", ui.estimateSleep, vm::setEstimateSleep)
                Text(
                    "The ring does not report sleep, so it is inferred from stillness and a " +
                        "drop in heart rate — a good estimate, not a measurement.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { vm.reExport() }, Modifier.fillMaxWidth()) {
                    Text("Re-export everything")
                }
                OutlinedButton(
                    onClick = { exportCsv.launch("ringlink-${System.currentTimeMillis() / 1000}.csv") },
                    Modifier.fillMaxWidth(),
                ) { Text("Export all data as CSV") }
                ui.exportMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    "Everything the app holds, with each reading's raw ring counter alongside its " +
                        "timestamp — so the file stays correctable if the time anchor ever is.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Rewrites all stored records at their current timestamps — use it after a " +
                        "clock correction, or once permission is granted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard("Buzz the ring") {
                ToggleRow("On notifications", ui.buzzOnNotifications, vm::setBuzzOnNotifications)
                ToggleRow("On incoming calls", ui.buzzOnCalls, vm::setBuzzOnCalls)
                if (!ui.notificationAccess) {
                    Text(
                        "Notification access is off, so notifications cannot buzz the ring.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val context = androidx.compose.ui.platform.LocalContext.current
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("Open notification access") }
                }
            }

            SectionCard("Watch") {
                ToggleRow("Periodic check-in", ui.watchEnabled, vm::setWatchEnabled)
                Text(
                    "Asks you to confirm you are alright at intervals, with an alarm that sounds " +
                        "through silent mode and buzzes the ring. An unanswered check-in escalates " +
                        "and keeps asking — silence is the signal, since an illness that clouds " +
                        "judgement is exactly when you would not raise the alarm yourself.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ui.daysSinceExposure?.let { Text("Day $it since the recorded exposure.") }
                if (ui.checkInPending) {
                    Button(onClick = { vm.acknowledgeCheckIn() }, Modifier.fillMaxWidth()) {
                        Text("I am alright")
                    }
                }
                OutlinedButton(onClick = { vm.testAlarm() }, Modifier.fillMaxWidth()) {
                    Text("Test the alarm now")
                }
                Text(
                    "This is a prompt, not a safety net: it cannot call anyone for you.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard("Permissions") {
                Button(onClick = { runtimePermissions.launch(requiredPermissions()) }, Modifier.fillMaxWidth()) {
                    Text("Grant Bluetooth, phone and notification permissions")
                }
                OutlinedButton(onClick = { vm.refresh() }, Modifier.fillMaxWidth()) { Text("Refresh") }
            }
            }
        }
    }
}

/**
 * One ring: whether it is reachable, its charge, and whether it is sitting in a charger.
 *
 * The charging marker matters — a charging ring is deliberately not buzzed, so showing it here
 * explains why a notification went to the other one.
 */
@Composable
private fun RingRow(
    title: String,
    connected: Boolean,
    battery: Int?,
    onCharger: Boolean,
    canVibrate: Boolean,
    skinTemp: Double?,
    info: String?,
    caseBattery: Int?,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            val charge = battery?.let { "$it%" } ?: "—"
            val where = when {
                !connected -> "disconnected"
                battery == null -> "connected"
                onCharger -> "charging (will not buzz)"
                else -> "worn"
            }
            val motor = if (canVibrate) "" else " · no motor, signals with its LED"
            val temp = skinTemp?.let { " · %.1f °C".format(it) } ?: ""
            Text("$charge · $where$temp$motor", style = MaterialTheme.typography.bodySmall)
            caseBattery?.let {
                Text("Charging case $it%", style = MaterialTheme.typography.bodySmall)
            }
            info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedButton(onClick = onRemove) { Text("Forget") }
    }
}

/**
 * Today at a glance — the view a ring app is expected to open on.
 *
 * Steps come from the ring's own activity log rather than from descriptor deltas, so the count does
 * not depend on how long the phone happened to be connected.
 */
@Composable
private fun TodayCard(vm: MainViewModel) {
    val steps by vm.stepsToday.collectAsState()
    val resting by vm.restingHeartRate.collectAsState()
    val ui by vm.ui.collectAsState()

    LaunchedEffect(Unit) { vm.refreshToday() }

    SectionCard("Today") {
        val goal = ui.stepGoal
        Text("$steps steps", style = MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(
            progress = { if (goal > 0) (steps.toFloat() / goal).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (steps >= goal) "Goal of $goal reached" else "${goal - steps} to go of $goal",
            style = MaterialTheme.typography.bodySmall,
        )
        resting?.let {
            Text("Resting heart rate  $it bpm", style = MaterialTheme.typography.bodyMedium)
        }
        if (steps == 0) {
            Text(
                "Nothing logged yet today — sync the ring to pull what it has recorded.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    add(Manifest.permission.READ_PHONE_STATE)
}.toTypedArray()
