package io.github.ringlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ringlink.data.DaySteps
import io.github.ringlink.data.HourSteps
import io.github.ringlink.data.MergedQuarter
import io.github.ringlink.data.PeriodSteps
import io.github.ringlink.data.Span
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Steps as tables and charts, over the whole period of use.
 *
 * Every chart has a table under it: the chart shows the shape, the table the numbers. And every
 * number says how much of it was heard — the ring's steps only reach the phone while the two are
 * connected, so a quiet-looking hour may simply be one the phone was elsewhere for. Hours nobody
 * heard show "—", never 0; "2/4" means two of the hour's four quarters were heard.
 */
@Composable
fun StepsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val view by vm.steps.view.collectAsState()
    val allTime by vm.steps.allTime.collectAsState()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val v = view
        if (v == null) {
            Text("Loading steps…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        TodayStepsCard(v, onFullDayHours = vm.steps::setFullDayHours)
        HoursCard(v)
        WeekCard(v)
        v.months.forEach { MonthCard(it, v) }
        allTime?.let { AllTimeCard(it, v) }
    }
}

@Composable
private fun TodayStepsCard(v: StepsView, onFullDayHours: (Int) -> Unit) {
    SectionCard("Today") {
        val steps = v.todayDay.steps
        Text("${steps.grouped()} steps", style = MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(
            progress = { if (v.goal > 0) (steps.toFloat() / v.goal).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("${percent(steps, v.goal)} of the ${v.goal.grouped()} goal", style = MaterialTheme.typography.bodySmall)
        val until = v.comparedUntil.format(CLOCK)
        Text(byNowLine(until, v.todayByNow, v.yesterdayByNow), style = MaterialTheme.typography.bodyMedium)
        Text(
            "Heard ${hoursOf(v.todayDay.heardQuarters)} of the ${hoursOf(v.elapsedQuarters)} so far",
            style = MaterialTheme.typography.bodySmall,
        )
        if (v.rings.size > 1) RingLegend(v.rings)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "A day counts in averages once heard for ${v.fullDayHours} h",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onFullDayHours(v.fullDayHours - 1) }, enabled = v.fullDayHours > 1) { Text("−") }
            TextButton(onClick = { onFullDayHours(v.fullDayHours + 1) }, enabled = v.fullDayHours < 24) { Text("+") }
        }
        Text(
            "Steps reach the phone only while it is connected to a ring on a finger. \"—\" is an " +
                "hour nobody heard, not an hour without walking; ◔ ◑ ◕ mark an hour heard for a " +
                "quarter, half or three quarters of it. Averages leave out days heard for less than " +
                "a full day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HoursCard(v: StepsView) {
    // Keyed on the day, so a selection made yesterday does not carry over to a different day.
    var selected by remember(v.today) { mutableStateOf<Int?>(null) }
    var expanded by remember(v.today) { mutableStateOf(emptySet<Int>()) }
    val now = v.nowMillis
    val yesterdayColor = ChartColors.steps.copy(alpha = 0.35f)

    SectionCard("Yesterday and today by hour") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Swatch(yesterdayColor, "Yesterday")
            Swatch(ChartColors.steps, "Today")
        }
        BarChart(
            groups = (0 until 24).map { h ->
                val today = v.todayHours[h]
                val yesterday = v.yesterdayHours[h]
                listOf(
                    Bar(if (yesterday == null) 0 else yesterday.takeIf { it.heard }?.steps, yesterdayColor),
                    Bar(
                        if (today == null || today.startMillis > now) 0 else today.takeIf { it.heard }?.steps,
                        ChartColors.steps,
                    ),
                )
            },
            selected = selected,
            onSelect = { selected = if (selected == it) null else it },
            xLabel = { h -> if (h % 6 == 0) hourLabel(h) else null },
        )
        selected?.let { h ->
            Text(
                "${hourLabel(h)}–${hourLabel((h + 1) % 24)} · yesterday ${v.yesterdayHours[h].describe(now)} · " +
                    "today ${v.todayHours[h].describe(now)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val weights = listOf(1.1f, 1f, 1f, 0.9f)
        TableRow(listOf(Cell("Hour"), Cell("Yesterday"), Cell("Today"), Cell("Δ")), weights, header = true)
        for (h in 0 until 24) {
            val today = v.todayHours[h]
            val yesterday = v.yesterdayHours[h]
            val future = today == null || today.startMillis > now
            val current = !future && now < today!!.startMillis + today.quarters.size * QUARTER_MS
            val open = h in expanded
            val twice = (today?.quarters?.size ?: 4) > 4 || (yesterday?.quarters?.size ?: 4) > 4
            TableRow(
                listOf(
                    Cell((if (open) "▾ " else "▸ ") + hourLabel(h), note = if (twice) "×2" else null),
                    hourCell(yesterday),
                    when {
                        future -> Cell("")
                        current -> hourCell(today).copy(note = "now")
                        else -> hourCell(today)
                    },
                    if (future || current) Cell("") else deltaCell(yesterday, today),
                ),
                weights,
                shaded = h % 2 == 1,
                onClick = { expanded = if (open) expanded - h else expanded + h },
            )
            if (open) {
                val count = maxOf(today?.quarters?.size ?: 0, yesterday?.quarters?.size ?: 0)
                for (q in 0 until count) {
                    val qStart = today?.let { it.startMillis + q * QUARTER_MS }
                    val yq = yesterday?.quarters?.getOrNull(q)
                    val tq = today?.quarters?.getOrNull(q)
                    val tFuture = qStart == null || qStart > now
                    TableRow(
                        listOf(
                            Cell((if (q >= 4) "    2nd " else "    ") + ":%02d".format((q % 4) * 15), dim = true),
                            if (yesterday == null || q >= yesterday.quarters.size) Cell("") else quarterCell(yq),
                            if (tFuture || q >= today!!.quarters.size) Cell("") else quarterCell(tq),
                            if (tFuture || qStart!! + QUARTER_MS > now) Cell("") else quarterDelta(yq, tq),
                        ),
                        weights,
                    )
                }
            }
        }
        HorizontalDivider()
        TableRow(
            listOf(
                Cell("Total"),
                Cell(v.yesterdayDay.steps.grouped()),
                Cell(v.todayDay.steps.grouped()),
                Cell(""),
            ),
            weights,
            bold = true,
        )
        TableRow(
            listOf(
                Cell("By ${v.comparedUntil.format(CLOCK)}"),
                spanCell(v.yesterdayByNow),
                spanCell(v.todayByNow),
                if (v.todayByNow.heard && v.yesterdayByNow.heard) {
                    Cell(signed(v.todayByNow.steps - v.yesterdayByNow.steps), dim = true)
                } else {
                    Cell("")
                },
            ),
            weights,
        )
        TableRow(
            listOf(
                Cell("Heard"),
                Cell(hoursOf(v.yesterdayDay.heardQuarters)),
                Cell(hoursOf(v.todayDay.heardQuarters)),
                Cell(""),
            ),
            weights,
        )
        Text(
            "Tap an hour for its quarters. \"By ${v.comparedUntil.format(CLOCK)}\" compares the same quarters of both days.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekCard(v: StepsView) {
    var selected by remember(v.today) { mutableIntStateOf(v.week.lastIndex) }
    SectionCard("Last 7 days") {
        HourHeatmap(
            rows = v.week.map { dayLabel(it.day.date, v.today) to it.hours },
            selected = selected,
            onSelect = { selected = it },
        )
        val day = v.week.getOrNull(selected) ?: v.week.last()
        Text(
            "${dayLabel(day.day.date, v.today)} by hour · ${day.day.steps.grouped()} steps",
            style = MaterialTheme.typography.titleSmall,
        )
        BarChart(
            groups = day.hours.map { listOf(Bar(if (it == null) 0 else it.takeIf { h -> h.heard }?.steps, ChartColors.steps)) },
            height = 100.dp,
            xLabel = { h -> if (h % 6 == 0) hourLabel(h) else null },
        )

        val weights = listOf(1.2f, 1f, 0.8f, 0.7f, 1.1f)
        TableRow(listOf(Cell("Day"), Cell("Steps"), Cell("Heard"), Cell("Goal"), Cell("Peak hour")), weights, header = true)
        v.week.forEachIndexed { i, wd ->
            val d = wd.day
            val full = d.isFull(v.fullDayQuarters)
            TableRow(
                listOf(
                    Cell(dayLabel(d.date, v.today), dots = ringDots(d, v)),
                    if (d.heard) Cell(d.steps.grouped(), dim = !full) else Cell("—", dim = true),
                    Cell(if (d.heard) hoursOf(d.heardQuarters) else "", dim = !full),
                    Cell(if (d.heard) percent(d.steps, v.goal) else "", dim = !full),
                    wd.peak?.let { Cell(hourLabel(it.hour), note = "· ${it.steps.grouped()}") } ?: Cell(""),
                ),
                weights,
                shaded = i == selected,
                onClick = { selected = i },
            )
        }
        HorizontalDivider()
        v.weekSummary?.let { PeriodLine(it, v) }
    }
}

@Composable
private fun MonthCard(m: MonthView, v: StepsView) {
    SectionCard(m.month.format(MONTH).replaceFirstChar { it.titlecase() } + if (m.current) " so far" else "") {
        val byDate = m.days.associateBy { it.date }
        BarChart(
            groups = (1..m.month.lengthOfMonth()).map { dom ->
                val d = byDate[m.month.atDay(dom)]
                val bar = when {
                    d == null -> Bar(0, ChartColors.steps)
                    !d.heard -> Bar(null, ChartColors.steps)
                    d.steps >= v.goal -> Bar(d.steps, ChartColors.goalMet)
                    d.isFull(v.fullDayQuarters) -> Bar(d.steps, ChartColors.steps)
                    else -> Bar(d.steps, ChartColors.steps.copy(alpha = 0.35f))
                }
                listOf(bar)
            },
            goal = v.goal,
            average = m.summary?.averagePerFullDay,
            xLabel = { i -> (i + 1).takeIf { it == 1 || it % 7 == 1 }?.toString() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Swatch(ChartColors.goalMet, "Goal met")
            Swatch(ChartColors.steps, "Full day")
            Swatch(ChartColors.steps.copy(alpha = 0.35f), "Partly heard")
        }
        Text(
            "Dashed line: goal · solid line: average per full day, over the days heard in full",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        m.summary?.let { PeriodLine(it, v) } ?: Text("Nothing heard this month.", style = MaterialTheme.typography.bodySmall)

        Text("By week", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        val weekWeights = listOf(1.5f, 1f, 1.1f, 0.8f)
        TableRow(listOf(Cell("Week"), Cell("Total"), Cell("Per full day"), Cell("Full")), weekWeights, header = true)
        m.weeks.forEachIndexed { i, w ->
            val inProgress = !v.today.isAfter(w.last)
            TableRow(
                listOf(
                    Cell(weekLabel(w), note = if (inProgress) "so far" else null),
                    Cell(w.total.grouped()),
                    Cell(w.averagePerFullDay?.grouped() ?: "—", dim = w.averagePerFullDay == null),
                    w.daysEnded(v.today).let { ended -> if (ended == 0) Cell("") else Cell("${w.fullDays}/$ended") },
                ),
                weekWeights,
                shaded = i % 2 == 1,
            )
        }
        Text(
            "Weeks start on ${v.firstDayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())} " +
                "and are counted whole, including days from the next or previous month.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("By day", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        val dayWeights = listOf(1.2f, 1f, 0.8f, 0.7f)
        TableRow(listOf(Cell("Day"), Cell("Steps"), Cell("Heard"), Cell("Goal")), dayWeights, header = true)
        m.days.forEachIndexed { i, d ->
            if (i == 0 || d.date.dayOfWeek == v.firstDayOfWeek) {
                m.weeks.firstOrNull { !d.date.isBefore(it.first) && !d.date.isAfter(it.last) }?.let { w ->
                    TableRow(listOf(Cell(weekLabel(w), dim = true)), listOf(1f), shaded = true)
                }
            }
            val full = d.isFull(v.fullDayQuarters)
            TableRow(
                listOf(
                    Cell(dayLabel(d.date, v.today), dots = ringDots(d, v)),
                    if (d.heard) Cell(d.steps.grouped(), dim = !full) else Cell("—", dim = true),
                    Cell(if (d.heard) hoursOf(d.heardQuarters) else "", dim = !full),
                    Cell(if (d.heard) percent(d.steps, v.goal) else "", dim = !full),
                ),
                dayWeights,
            )
        }
    }
}

@Composable
private fun AllTimeCard(a: AllTimeView, v: StepsView) {
    SectionCard("Whole period") {
        val s = a.summary
        if (s == null) {
            Text("No steps heard yet.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        Text(
            "Since ${s.first.format(DATE_LONG)} · ${s.heardDays} days heard, ${s.fullDays} of them full",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Total ${s.total.grouped()} · ${s.averagePerFullDay?.let { "${it.grouped()} per full day" } ?: "no full day yet"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        s.best?.let {
            Text("Best day: ${it.date.format(DATE_LONG)} — ${it.steps.grouped()}", style = MaterialTheme.typography.bodySmall)
        }
        a.bestWeek?.let {
            Text("Best week: ${weekLabel(it)} — ${it.total.grouped()}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Goal streak: ${a.streaks.current} ${days(a.streaks.current)} now · longest ${a.streaks.longest}",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("By month", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        val weights = listOf(1.2f, 1.1f, 1.1f, 0.6f, 1.2f)
        TableRow(listOf(Cell("Month"), Cell("Total"), Cell("Per full day"), Cell("Full"), Cell("Best day")), weights, header = true)
        a.months.forEachIndexed { i, (month, p) ->
            TableRow(
                listOf(
                    Cell(month.format(MONTH_SHORT)),
                    Cell(p.total.grouped()),
                    Cell(p.averagePerFullDay?.grouped() ?: "—", dim = p.averagePerFullDay == null),
                    Cell("${p.fullDays}"),
                    p.best?.let { Cell(it.steps.grouped(), note = "· ${it.date.dayOfMonth}") } ?: Cell(""),
                ),
                weights,
                shaded = i % 2 == 1,
            )
        }
    }
}

@Composable
private fun PeriodLine(p: PeriodSteps, v: StepsView) {
    val average = p.averagePerFullDay?.let { "${it.grouped()} per full day (${p.fullDays} ${days(p.fullDays)})" }
        ?: "no full day to average"
    Text("Total ${p.total.grouped()} · $average", style = MaterialTheme.typography.bodyMedium)
    p.best?.let {
        Text(
            "Best: ${dayLabel(it.date, v.today)} — ${it.steps.grouped()} · ${p.heardDays} ${days(p.heardDays)} heard",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// --- table -------------------------------------------------------------------------------------

private data class Cell(
    val text: String,
    /** Drawn at full size beside the text, dimmed — for symbols too small to read as a note. */
    val mark: String? = null,
    val note: String? = null,
    val dim: Boolean = false,
    val dots: List<Color> = emptyList(),
)

/** One row. The first column is a label and aligns left; the rest are numbers and align right. */
@Composable
private fun TableRow(
    cells: List<Cell>,
    weights: List<Float>,
    header: Boolean = false,
    shaded: Boolean = false,
    bold: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val base = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall
    val style = base.merge(TextStyle(fontFeatureSettings = "tnum", fontWeight = if (bold) FontWeight.SemiBold else null))
    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (shaded) Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, cell ->
            Row(
                Modifier.weight(weights.getOrElse(i) { 1f }),
                horizontalArrangement = if (i == 0) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                cell.dots.forEach { color ->
                    Box(Modifier.size(6.dp).background(color, CircleShape))
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    buildAnnotatedString {
                        append(cell.text)
                        cell.mark?.let { withStyle(SpanStyle(color = dimColor)) { append(" $it") } }
                        cell.note?.let { withStyle(SpanStyle(fontSize = 9.sp, color = dimColor)) { append(" $it") } }
                    },
                    style = style,
                    color = if (cell.dim || header) dimColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RingLegend(rings: List<RingLabel>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        rings.forEach { Swatch(ringColor(it.colorIndex), it.name) }
    }
}

private fun ringColor(i: Int) = ChartColors.rings[i % ChartColors.rings.size]

/** Dots only on days two rings shared, so a swap shows; one ring needs no marking. */
private fun ringDots(d: DaySteps, v: StepsView): List<Color> =
    if (d.rings.size < 2) emptyList() else v.rings.filter { it.address in d.rings }.map { ringColor(it.colorIndex) }

// --- cells -------------------------------------------------------------------------------------

private fun hourCell(h: HourSteps?): Cell = when {
    h == null -> Cell("")
    !h.heard -> Cell("—", dim = true)
    else -> Cell(h.steps.grouped(), mark = if (h.heardQuarters < h.quarters.size) pie(h.heardQuarters, h.quarters.size) else null)
}

/**
 * How much of an hour was heard, as a quarter-filled circle. A fraction like "1/4" next to a count
 * reads as part of the number — "35 1/4" looks like thirty-five and a quarter.
 */
private fun pie(heard: Int, of: Int): String =
    when ((heard * 4.0 / of).roundToInt().coerceIn(1, 3)) { 1 -> "◔"; 2 -> "◑"; else -> "◕" }

private fun spanCell(s: Span): Cell =
    if (!s.heard) Cell("—", dim = true) else Cell(s.steps.grouped(), note = "· ${hoursOf(s.heardQuarters)}")

/** "By 14:30" for both days, only as far as each was heard; a day nobody heard is not a zero. */
private fun byNowLine(until: String, today: Span, yesterday: Span): String = when {
    !yesterday.heard && !today.heard -> "By $until: neither today nor yesterday heard yet"
    !yesterday.heard -> "By $until: ${today.steps.grouped()} today; yesterday was not heard by then"
    !today.heard -> "By $until: nothing heard today yet; yesterday ${yesterday.steps.grouped()}"
    else -> {
        val diff = today.steps - yesterday.steps
        "By $until: ${today.steps.grouped()} today, ${yesterday.steps.grouped()} yesterday — " +
            if (diff >= 0) "${diff.grouped()} ahead" else "${(-diff).grouped()} behind"
    }
}

private fun deltaCell(yesterday: HourSteps?, today: HourSteps?): Cell =
    if (yesterday?.heard == true && today?.heard == true) Cell(signed(today.steps - yesterday.steps), dim = true) else Cell("")

private fun quarterCell(q: MergedQuarter?): Cell = when {
    q == null || !q.heard -> Cell("—", dim = true)
    else -> Cell(q.steps.grouped())
}

private fun quarterDelta(y: MergedQuarter?, t: MergedQuarter?): Cell =
    if (y?.heard == true && t?.heard == true) Cell(signed(t.steps - y.steps), dim = true) else Cell("")

private fun HourSteps?.describe(now: Long): String = when {
    this == null -> "no such hour"
    startMillis > now -> "not yet"
    !heard -> "not heard"
    heardQuarters < quarters.size -> "${steps.grouped()} (heard $heardQuarters/${quarters.size})"
    else -> steps.grouped()
}

/** Days of the week already over, for "full days out of how many" — today is not over yet. */
private fun PeriodSteps.daysEnded(today: LocalDate): Int =
    if (today.isAfter(last)) 7 else java.time.temporal.ChronoUnit.DAYS.between(first, today).toInt().coerceIn(0, 7)

// --- formatting --------------------------------------------------------------------------------

private const val HOUR_MS = 3_600_000L
private const val QUARTER_MS = 900_000L

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val DATE_LONG = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
private val MONTH = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
private val MONTH_SHORT = DateTimeFormatter.ofPattern("LLL yyyy", Locale.getDefault())

private fun Int.grouped(): String = NumberFormat.getIntegerInstance().format(this)
private fun Long.grouped(): String = NumberFormat.getIntegerInstance().format(this)
private fun signed(n: Int): String = if (n > 0) "+${n.grouped()}" else if (n < 0) "−${(-n).grouped()}" else "0"
private fun percent(steps: Int, goal: Int): String = if (goal <= 0) "" else "${steps * 100L / goal}%"
private fun hourLabel(hour: Int) = "%02d:00".format(hour)
private fun days(n: Int) = if (n == 1) "day" else "days"

/** Quarters as hours, in the quarters they were heard in: "9¼ h". */
private fun hoursOf(quarters: Int): String {
    val whole = quarters / 4
    val part = when (quarters % 4) { 1 -> "¼"; 2 -> "½"; 3 -> "¾"; else -> "" }
    return if (whole == 0 && part.isNotEmpty()) "$part h" else "$whole$part h"
}

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(DAY)
}

private fun weekLabel(w: PeriodSteps): String =
    if (w.first.month == w.last.month) "${w.first.dayOfMonth}–${w.last.format(DAY_MONTH)}"
    else "${w.first.format(DAY_MONTH)} – ${w.last.format(DAY_MONTH)}"

@Suppress("unused")
private fun YearMonth.label(): String = format(MONTH)
