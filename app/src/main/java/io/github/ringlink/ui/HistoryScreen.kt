package io.github.ringlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ringlink.data.EpochEntity

@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val rows by vm.history.collectAsState()
    val summary by vm.summary.collectAsState()
    val window by vm.selectedWindow.collectAsState()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryWindow.entries.forEach { w ->
                FilterChip(
                    selected = w == window,
                    onClick = { vm.selectWindow(w) },
                    label = { Text(w.label) },
                )
            }
        }

        if (rows.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("No data in this window", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Sync the ring to pull its history. Note the ring only hands each record " +
                            "over once, so anything the vendor app already collected is gone from " +
                            "the ring.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            return@Column
        }

        summary?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Summary", style = MaterialTheme.typography.titleMedium)
                    Text("${s.samples} records")
                    s.avgHeartRate?.let {
                        Text("Heart rate  ${it.toInt()} avg  ·  ${s.minHeartRate}–${s.maxHeartRate} bpm")
                    }
                    s.avgHrv?.let { Text("HRV  ${it.toInt()} ms avg") }
                    s.avgSpo2?.let { Text("SpO₂  ${it.toInt()}% avg  ·  low ${s.minSpo2}%") }
                    s.avgRespiratoryRate?.let { Text("Respiratory  ${"%.1f".format(it)} /min avg") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Sparkline(
                    title = "Heart rate",
                    points = rows.points(vm) { it.heartRate?.toFloat() },
                    unit = "bpm",
                    color = ChartColors.heartRate,
                )
                Sparkline(
                    title = "SpO₂",
                    points = rows.points(vm) { it.spo2?.toFloat() },
                    unit = "%",
                    color = ChartColors.spo2,
                )
                Sparkline(
                    title = "HRV (RMSSD)",
                    points = rows.points(vm) { it.hrvRmssd?.toFloat() },
                    unit = "ms",
                    color = ChartColors.hrv,
                )
                Sparkline(
                    title = "Respiratory rate",
                    points = rows.points(vm) { it.respiratoryRate?.toFloat() },
                    unit = "/min",
                    color = ChartColors.respiratory,
                )
            }
        }

        Text(
            "Charts show only measured samples — gaps are real gaps, when the ring was off, " +
                "charging or out of range.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Project stored rows onto a chart series, dropping epochs where the field was not measured. */
private inline fun List<EpochEntity>.points(
    vm: MainViewModel,
    select: (EpochEntity) -> Float?,
): List<Point> = mapNotNull { row ->
    select(row)?.let { Point(vm.timeOf(row.counter), it) }
}
