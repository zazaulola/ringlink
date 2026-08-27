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
            SectionCard("Ring") {
                if (ui.ring == null) {
                    Text("No ring selected.")
                    Text(
                        "Rings already paired with this phone appear below — pairing is shared " +
                            "between apps, so there is nothing to scan for.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ui.candidates.forEach { ring ->
                        OutlinedButton(onClick = { vm.selectRing(ring) }, Modifier.fillMaxWidth()) {
                            Text("${ring.name}  (${ring.address})")
                        }
                    }
                    if (ui.candidates.isEmpty()) {
                        Text(
                            "Nothing found. Pair the ring first (the vendor app does this), " +
                                "then grant Nearby devices permission and refresh.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(ui.ring!!.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (service.connected) "Connected" else "Disconnected")
                    service.battery?.let { Text("Battery $it%") }
                    Text(service.status, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.connect() }) { Text("Connect") }
                        Button(onClick = { vm.syncNow() }, enabled = !service.syncing) { Text("Sync now") }
                        OutlinedButton(onClick = { vm.testBuzz() }) { Text("Buzz") }
                    }
                }
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
                        Text("Connected — heart rate, HRV, SpO₂, respiratory rate, sleep and steps.")
                    else -> {
                        Text("Permission needed to write your ring data.")
                        Button(onClick = { healthPermissions.launch(vm.exporter.permissions) }) {
                            Text("Grant")
                        }
                    }
                }
                ToggleRow("Export to Health Connect", ui.exportToHealthConnect, vm::setExport)
                OutlinedButton(onClick = { vm.reExport() }, Modifier.fillMaxWidth()) {
                    Text("Re-export everything")
                }
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
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    add(Manifest.permission.READ_PHONE_STATE)
}.toTypedArray()
