package io.github.ringlink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.github.ringlink.data.HourSteps
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** One measurement on a chart: a wall-clock instant and a value. */
data class Point(
    val timeSeconds: Long,
    val value: Float,
    /** The ring this came from. A line is never drawn between readings from different rings. */
    val source: String? = null,
)

/**
 * A compact line chart.
 *
 * Gaps are drawn as gaps. A smart ring is taken off, runs out of battery and loses connection, so
 * joining two samples an hour apart with a straight line would invent readings that were never
 * measured.
 */
@Composable
fun Sparkline(
    title: String,
    points: List<Point>,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    gapSeconds: Long = 900,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val values = points.map { it.value }
        val label = if (values.isEmpty()) {
            "no data"
        } else {
            "${values.min().toInt()}–${values.max().toInt()} $unit  ·  avg ${values.average().toInt()}"
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.bodySmall)

        if (points.size < 2) return@Column

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(top = 6.dp),
        ) {
            val tMin = points.first().timeSeconds
            val tMax = points.last().timeSeconds
            val span = max(1L, tMax - tMin)
            var lo = values.min()
            var hi = values.max()
            if (hi - lo < 1f) { lo -= 1f; hi += 1f }        // flat series still needs a band
            val range = hi - lo

            fun x(t: Long) = ((t - tMin).toFloat() / span) * size.width
            fun y(v: Float) = size.height - ((v - lo) / range) * size.height

            val path = Path()
            var penDown = false
            var previousTime = 0L
            var previousSource: String? = null
            for (p in points) {
                // Two rings are two sensors: joining a reading from one to the next reading from
                // the other draws a jump neither of them measured.
                val newSegment = !penDown ||
                    (p.timeSeconds - previousTime) > gapSeconds ||
                    p.source != previousSource
                if (newSegment) path.moveTo(x(p.timeSeconds), y(p.value))
                else path.lineTo(x(p.timeSeconds), y(p.value))
                penDown = true
                previousTime = p.timeSeconds
                previousSource = p.source
            }
            drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

            // Mark the most recent reading so "now" is findable at a glance.
            val last = points.last()
            drawCircle(color, radius = 3.5f, center = Offset(x(last.timeSeconds), y(last.value)))
        }
    }
}

/**
 * Collapse a burst-sampled series into one point per bucket, using the median.
 *
 * Live-descriptor readings arrive in clusters whenever the phone is connected, which drawn raw look
 * like a picket fence rather than a trend. A median per hour is both readable and robust: a single
 * reading taken as the ring came off the finger cannot drag the hour with it.
 */
fun List<Point>.bucketed(bucketSeconds: Long = 3600): List<Point> =
    groupBy { it.timeSeconds / bucketSeconds }
        .toSortedMap()
        .map { (bucket, points) ->
            val values = points.map { it.value }.sorted()
            Point(bucket * bucketSeconds, values[values.size / 2])
        }

/** Chart colours kept together so the screens stay visually consistent. */
object ChartColors {
    val heartRate = Color(0xFFD32F2F)
    val spo2 = Color(0xFF1976D2)
    val hrv = Color(0xFF7B1FA2)
    val respiratory = Color(0xFF00796B)
    val temperature = Color(0xFFE65100)
    val battery = Color(0xFF455A64)
    val steps = Color(0xFF1E88E5)
    val goalMet = Color(0xFF43A047)

    /** One colour per ring, in a fixed order, for the coverage strip. */
    val rings = listOf(Color(0xFF3949AB), Color(0xFFF4511E), Color(0xFF00897B), Color(0xFF8E24AA))
}

internal fun clampWindow(value: Long, lo: Long, hi: Long) = min(max(value, lo), hi)

/** One bar. A null value means nothing was heard, and is drawn as a tick, never as a zero. */
data class Bar(val value: Int?, val color: Color)

/**
 * Bars in groups: one bar per group, or two side by side to compare days.
 *
 * Tapping a group selects it. The chart is for shape; the numbers belong to the table that sits
 * beside every chart on the Steps screen, which is also what makes it readable without sight.
 */
@Composable
fun BarChart(
    groups: List<List<Bar>>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    goal: Int? = null,
    average: Int? = null,
    selected: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    xLabel: (Int) -> String? = { null },
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val missing = MaterialTheme.colorScheme.outline
    val highlight = MaterialTheme.colorScheme.surfaceVariant
    val goalColor = ChartColors.goalMet
    val averageColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tapModifier = if (onSelect == null || groups.isEmpty()) Modifier else Modifier.pointerInput(groups.size) {
        detectTapGestures { offset ->
            val axis = AXIS_WIDTH.toPx()
            val w = (size.width - axis) / groups.size
            if (offset.x >= axis) onSelect(((offset.x - axis) / w).toInt().coerceIn(0, groups.size - 1))
        }
    }
    Canvas(modifier.fillMaxWidth().height(height).then(tapModifier)) {
        if (groups.isEmpty()) return@Canvas
        val axis = AXIS_WIDTH.toPx()
        val bottom = size.height - 14.dp.toPx()
        val top = 6.dp.toPx()
        val values = groups.flatten().mapNotNull { it.value } + listOfNotNull(goal, average)
        val scale = niceCeiling(values.maxOrNull() ?: 0)
        fun y(v: Int) = bottom - (v.toFloat() / scale) * (bottom - top)

        for (step in 0..2) {
            val v = scale * step / 2
            drawLine(grid, Offset(axis, y(v)), Offset(size.width, y(v)), strokeWidth = 1f)
            val text = measurer.measure(compact(v), labelStyle)
            drawText(text, topLeft = Offset(axis - text.size.width - 4.dp.toPx(), y(v) - text.size.height / 2))
        }

        val groupWidth = (size.width - axis) / groups.size
        selected?.let {
            drawRect(highlight, Offset(axis + it * groupWidth, top), Size(groupWidth, bottom - top))
        }
        val gap = groupWidth * 0.2f
        groups.forEachIndexed { i, bars ->
            val barWidth = (groupWidth - gap) / bars.size.coerceAtLeast(1)
            bars.forEachIndexed { j, bar ->
                val x = axis + i * groupWidth + gap / 2 + j * barWidth
                val value = bar.value
                if (value == null) {
                    drawLine(missing, Offset(x, bottom), Offset(x + barWidth * 0.8f, bottom), strokeWidth = 1.5f)
                } else if (value > 0) {
                    drawRect(bar.color, Offset(x, y(value)), Size(barWidth * 0.9f, bottom - y(value)))
                }
            }
            xLabel(i)?.let { label ->
                val text = measurer.measure(label, labelStyle)
                val cx = axis + i * groupWidth + groupWidth / 2 - text.size.width / 2
                drawText(text, topLeft = Offset(cx.coerceIn(axis, size.width - text.size.width), bottom + 2.dp.toPx()))
            }
        }

        goal?.let {
            drawLine(
                goalColor, Offset(axis, y(it)), Offset(size.width, y(it)), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
        }
        average?.let { drawLine(averageColor, Offset(axis, y(it)), Offset(size.width, y(it)), strokeWidth = 1.5f) }
    }
}

/**
 * Seven days by hour as rows of cells, darker for more steps.
 *
 * 168 bars side by side cannot be read on a phone; a grid keeps each day on its own line and makes
 * the daily rhythm show. A cell nobody heard is left empty, outlined, so it is not mistaken for an
 * hour without walking.
 */
@Composable
fun HourHeatmap(
    /** Each day by clock hour, 0 to 23; an hour a clock change skipped is null. */
    rows: List<Pair<String, List<HourSteps?>>>,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    onSelect: (Int) -> Unit = {},
    color: Color = ChartColors.steps,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val selectedStyle = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface)
    val outline = MaterialTheme.colorScheme.outlineVariant
    val rowHeight = 22.dp
    val header = 14.dp
    Canvas(
        modifier
            .fillMaxWidth()
            .height(header + rowHeight * rows.size)
            .pointerInput(rows.size) {
                detectTapGestures { offset ->
                    val row = ((offset.y - header.toPx()) / rowHeight.toPx()).toInt()
                    if (row in rows.indices) onSelect(row)
                }
            },
    ) {
        if (rows.isEmpty()) return@Canvas
        val labelWidth = rows.maxOf { measurer.measure(it.first, labelStyle).size.width } + 6.dp.toPx()
        val cell = (size.width - labelWidth) / 24f
        val max = rows.flatMap { it.second }.maxOfOrNull { it?.steps ?: 0 }?.coerceAtLeast(1) ?: 1
        for (h in listOf(0, 6, 12, 18)) {
            drawText(measurer.measure("$h", labelStyle), topLeft = Offset(labelWidth + h * cell, 0f))
        }
        rows.forEachIndexed { r, (label, hours) ->
            val top = header.toPx() + r * rowHeight.toPx()
            val text = measurer.measure(label, if (r == selected) selectedStyle else labelStyle)
            drawText(text, topLeft = Offset(0f, top + (rowHeight.toPx() - text.size.height) / 2))
            hours.forEachIndexed { i, hour ->
                val origin = Offset(labelWidth + i * cell + 1f, top + 1f)
                val box = Size(cell - 2f, rowHeight.toPx() - 2f)
                if (hour == null || !hour.heard) {
                    drawRect(outline, origin, box, style = Stroke(width = 1f))
                } else {
                    val alpha = 0.08f + 0.92f * (hour.steps.toFloat() / max)
                    drawRect(color.copy(alpha = alpha), origin, box)
                }
            }
            if (r == selected) {
                drawRect(
                    color, Offset(labelWidth, top), Size(size.width - labelWidth, rowHeight.toPx()),
                    style = Stroke(width = 2f),
                )
            }
        }
    }
}

private val AXIS_WIDTH = 34.dp

/** A round number at or above [v] for the top of a chart: 1, 2 or 5 times a power of ten. */
internal fun niceCeiling(v: Int): Int {
    if (v <= 0) return 10
    var magnitude = 1
    while (magnitude * 10 <= v) magnitude *= 10
    return listOf(1, 2, 5, 10).map { it * magnitude }.first { it >= v }
}

/** 12 400 as "12k", for axis labels where space is short. */
internal fun compact(v: Int): String = when {
    v >= 10_000 -> "${v / 1000}k"
    v >= 1_000 && v % 1000 == 0 -> "${v / 1000}k"
    v >= 1_000 -> "%.1fk".format(v / 1000f)
    else -> "$v"
}
