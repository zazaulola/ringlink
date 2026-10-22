package io.github.ringlink.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import io.github.ringlink.data.Coverage
import io.github.ringlink.data.DeviceStateEntity
import io.github.ringlink.data.EpochEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val rows by vm.visibleHistory.collectAsState()
    val rings by vm.ringsInWindow.collectAsState()
    val selectedRing by vm.selectedRing.collectAsState()
    val states by vm.deviceStates.collectAsState()
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

        if (rings.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedRing == null,
                    onClick = { vm.selectRing(null) },
                    label = { Text("All rings") },
                )
                rings.forEach { ring ->
                    FilterChip(
                        selected = selectedRing == ring.address,
                        onClick = { vm.selectRing(ring.address) },
                        label = { Text(ring.shortName) },
                    )
                }
            }
        }

        SourcesCard(vm)

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

        NightsCard(vm)

        if (states.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Skin temperature", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Shown per day rather than as a trend line: these readings only arrive " +
                            "while the phone is connected to the ring, and drawing a line through " +
                            "clusters hours apart would invent a shape the data does not have.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    DailyTemperature(states)
                    Text(
                        "Lows near room temperature are the ring off the finger — skin temperature " +
                            "only means something while it is worn.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text(
            "Heart rate and its companions come from the ring's stored history, so they cover the " +
                "whole period. Temperature and battery arrive only while the phone is connected, " +
                "so those traces are sparser. Gaps are real gaps.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Which ring recorded which stretch of the window.
 *
 * Both rings write records all the time, worn or not, so the honest answer is the one the pulse
 * gives: a ring is the source only where it measured one. Drawn on the window's own time axis, one
 * strip per ring, with the most recent stretches spelled out underneath.
 */
@Composable
private fun SourcesCard(vm: MainViewModel) {
    val spans by vm.coverage.collectAsState()
    if (spans.isEmpty()) return
    val ringIds = spans.map { it.ringId }.distinct().sorted()
    val colorOf = { id: String -> ChartColors.rings[ringIds.indexOf(id) % ChartColors.rings.size] }
    val (from, to) = vm.windowBounds()
    val totals = Coverage.totals(spans)
    val format = remember { SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Which ring recorded what", style = MaterialTheme.typography.titleMedium)

            ringIds.forEach { id ->
                val ring = vm.ringOf(id)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ring.shortName, Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
                    Canvas(
                        Modifier
                            .weight(1f)
                            .height(14.dp),
                    ) {
                        val span = (to - from).coerceAtLeast(1).toFloat()
                        drawRect(color = Color.Gray.copy(alpha = 0.15f), size = size)
                        spans.filter { it.ringId == id }.forEach { s ->
                            val start = vm.timeOf(s.startCounter).coerceIn(from, to)
                            val end = vm.timeOf(s.endCounter).coerceIn(from, to)
                            if (end <= start) return@forEach
                            val x0 = (start - from) / span * size.width
                            val x1 = (end - from) / span * size.width
                            drawRect(
                                color = colorOf(id),
                                topLeft = Offset(x0, 0f),
                                size = Size((x1 - x0).coerceAtLeast(1.5f), size.height),
                            )
                        }
                    }
                    Text(
                        "%d h".format((totals[id] ?: 0L) / 3600),
                        Modifier.width(52.dp).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            spans.sortedByDescending { it.startCounter }.take(RECENT_SPANS).forEach { s ->
                val ring = vm.ringOf(s.ringId)
                Text(
                    "${format.format(Date(vm.timeOf(s.startCounter) * 1000))} – " +
                        "${format.format(Date(vm.timeOf(s.endCounter) * 1000))}  ·  " +
                        "${ring.shortName}  ·  %.1f h".format(s.seconds / 3600.0),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorOf(s.ringId),
                )
            }

            Text(
                "A ring counts as the source only where it measured a pulse. A ring in its case " +
                    "or on a table still writes empty records, and those are not shown as data.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val RECENT_SPANS = 6

/**
 * Detected nights, with the vitals measured during each.
 *
 * Labelled as an estimate throughout, because it is one: the ring reports no sleep of its own, so
 * these are inferred from stillness and a drop below the wearer's own awake heart rate.
 */
@Composable
private fun NightsCard(vm: MainViewModel) {
    val nights by vm.nights.collectAsState()
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val day = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sleep (estimated)", style = MaterialTheme.typography.titleMedium)
            if (nights.isEmpty()) {
                Text(
                    "No nights detected in this window. Sleep is inferred from stillness and a " +
                        "drop in heart rate, so a night the ring was not worn leaves no trace.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            val average = nights.sumOf { it.hours } / nights.size
            Text("%.1f h average over %d night%s".format(average, nights.size, if (nights.size == 1) "" else "s"))
            nights.forEach { night ->
                val start = Date(night.startUnix * 1000)
                val end = Date(night.endUnix * 1000)
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${day.format(start)}  ·  ${vm.ringOf(night.ringId).shortName}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "%.1f h · %s–%s".format(night.hours, clock.format(start), clock.format(end)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val detail = listOfNotNull(
                        night.lowestHeartRate?.let { "low ${'$'}it bpm" },
                        night.averageHeartRate?.let { "avg ${'$'}it bpm" },
                        night.averageSpo2?.let { "SpO₂ ${'$'}it%" },
                        night.averageHrv?.let { "HRV ${'$'}it ms" },
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(
                "No stages: the ring never sends a hypnogram, and Light/Deep/REM would be invented.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Per-day skin temperature: the range, and a median that a single off-finger reading cannot skew.
 *
 * A table rather than a chart because the underlying sampling is clustered, not continuous — this
 * is the honest shape of the data, and it is the shape you actually watch a temperature in.
 */
@Composable
private fun DailyTemperature(states: List<DeviceStateEntity>) {
    val worn = states.filter { it.skinTempA in 20.0..42.0 }
    if (worn.isEmpty()) {
        Text("No temperature readings in this window.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val format = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    worn.groupBy { format.format(Date(it.recordedAt)) }
        .entries
        .sortedByDescending { it.value.first().recordedAt }
        .forEach { (day, rows) ->
            val values = rows.map { it.skinTempA }.sorted()
            val median = values[values.size / 2]
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(day, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "%.1f–%.1f °C · median %.1f".format(values.first(), values.last(), median),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
}

/** Project stored rows onto a chart series, dropping epochs where the field was not measured. */
private inline fun List<EpochEntity>.points(
    vm: MainViewModel,
    select: (EpochEntity) -> Float?,
): List<Point> = mapNotNull { row ->
    select(row)?.let { Point(vm.timeOf(row.counter), it, source = row.ringId) }
}
