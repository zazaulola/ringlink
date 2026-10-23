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
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
import io.github.ringlink.ble.RingService
import io.github.ringlink.backup.BackupJobs
import io.github.ringlink.backup.BackupRotation
import io.github.ringlink.backup.PendingRestore
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
    val backupTo = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { vm.backupTo(it) } }
    // Some providers report a zip as octet-stream, so offer everything and let the check decide.
    val restoreFrom = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.openRestore(it) } }
    val backupFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { vm.chooseBackupFolder(it) } }

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
                            caseLine = live?.let { caseLine(it) },
                            worn = live?.worn == true,
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

            SectionCard("Backups") {
                val b = ui.backup
                val job by vm.backupJob.collectAsState()
                Text(
                    "This phone holds the only complete copy of what your rings recorded: reading " +
                        "history off a ring erases it there, and Health Connect only gets derived " +
                        "records. A backup keeps every reading and your settings in one file.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { backupTo.launch(BackupRotation.manualNameFor(System.currentTimeMillis())) },
                        enabled = !job.busy,
                    ) { Text("Back up now") }
                    OutlinedButton(
                        onClick = { restoreFrom.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        enabled = !job.busy,
                    ) { Text("Restore…") }
                }
                Text(
                    "Restoring merges: readings are only ever added, never overwritten, so restoring " +
                        "an old backup cannot lose anything recorded since.",
                    style = MaterialTheme.typography.bodySmall,
                )

                ToggleRow("Automatic daily backup", b.autoEnabled, vm::setAutoBackup)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            b.folderName == null -> "No folder chosen"
                            !b.folderReachable -> "Folder ${b.folderName} — no longer reachable"
                            else -> "Folder: ${b.folderName}"
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { backupFolder.launch(null) }) { Text("Choose") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep the last ${b.keep}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { vm.setBackupKeep(b.keep - 1) }, enabled = b.keep > 1) { Text("−") }
                    OutlinedButton(onClick = { vm.setBackupKeep(b.keep + 1) }) { Text("+") }
                }
                when {
                    b.autoEnabled && b.folderName == null ->
                        Text("Choose a folder to start backing up.", style = MaterialTheme.typography.bodySmall)
                    b.lastAt > 0 ->
                        Text(
                            "Last automatic backup ${ago(b.lastAt)} · ${b.lastResult ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
                OutlinedButton(
                    onClick = { vm.backupToFolderNow() },
                    enabled = !job.busy && b.folderName != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back up to the folder now") }
                Text(
                    "Choose any folder the file picker offers — a cloud folder such as Drive or " +
                        "Nextcloud keeps copies off the phone. Backups are not encrypted: they " +
                        "contain your health data, so keep them somewhere private.",
                    style = MaterialTheme.typography.bodySmall,
                )
                job.message?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                    if (msg == BackupJobs.INTERRUPTED) {
                        TextButton(onClick = vm::dismissBackupNotice) { Text("Got it") }
                    }
                }
                job.pending?.let { p ->
                    RestoreDialog(p, onConfirm = vm::confirmRestore, onCancel = vm::cancelRestore)
                }
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
    caseLine: String?,
    worn: Boolean,
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
                onCharger -> "charging"
                battery == null -> "connected"
                worn -> "worn"
                // Not "worn" by default: a ring can perfectly well be connected and sitting on a
                // desk, and saying otherwise was simply wrong.
                else -> "off finger"
            }
            val motor = if (canVibrate) "" else " · no motor, signals with its LED"
            val temp = skinTemp?.let { " · %.1f °C".format(it) } ?: ""
            Text("$charge · $where$temp$motor", style = MaterialTheme.typography.bodySmall)
            caseLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
        Text(
            "Counted while the phone is connected to the ring: the ring tallies steps a quarter-hour " +
                "at a time and keeps no step history its sync hands over, so a quarter nobody heard " +
                "cannot be recovered later.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * One line about the ring's charging case.
 *
 * The case is only ever seen through a ring docked in it, so while the ring is out this says what
 * the case was last time rather than going blank — that is exactly when it matters, because the
 * next charge depends on it.
 */
private fun caseLine(ring: RingService.RingState): String? {
    val live = ring.caseBattery
    if (live != null) {
        return "Charging case $live%" + if (ring.caseCharging) " · plugged in" else ""
    }
    val last = ring.caseLastPercent ?: return null
    val at = ring.caseLastSeenAt ?: return "Charging case was $last%"
    return "Charging case was $last% · ${ago(at)}"
}

private fun ago(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/** What a backup holds, shown before anything is touched. */
@Composable
private fun RestoreDialog(p: PendingRestore, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val made = remember(p.createdAt) {
        java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(p.createdAt))
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (p.problems.isEmpty()) "Restore this backup?" else "Cannot restore") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Made $made")
                if (p.rings.isNotEmpty()) Text("Rings: ${p.rings.joinToString(", ")}")
                Text("${p.epochs} epochs, ${p.readings} device readings")
                if (p.problems.isEmpty()) {
                    Text(
                        "It will be merged into what is here: anything missing is added, nothing " +
                            "already here is changed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    p.problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (p.problems.isEmpty()) TextButton(onClick = onConfirm) { Text("Merge into this phone") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
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
