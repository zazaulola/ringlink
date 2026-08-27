package io.github.ringlink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** One measurement on a chart: a wall-clock instant and a value. */
data class Point(val timeSeconds: Long, val value: Float)

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
            for (p in points) {
                val newSegment = !penDown || (p.timeSeconds - previousTime) > gapSeconds
                if (newSegment) path.moveTo(x(p.timeSeconds), y(p.value))
                else path.lineTo(x(p.timeSeconds), y(p.value))
                penDown = true
                previousTime = p.timeSeconds
            }
            drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

            // Mark the most recent reading so "now" is findable at a glance.
            val last = points.last()
            drawCircle(color, radius = 3.5f, center = Offset(x(last.timeSeconds), y(last.value)))
        }
    }
}

/** Chart colours kept together so the screens stay visually consistent. */
object ChartColors {
    val heartRate = Color(0xFFD32F2F)
    val spo2 = Color(0xFF1976D2)
    val hrv = Color(0xFF7B1FA2)
    val respiratory = Color(0xFF00796B)
}

internal fun clampWindow(value: Long, lo: Long, hi: Long) = min(max(value, lo), hi)
