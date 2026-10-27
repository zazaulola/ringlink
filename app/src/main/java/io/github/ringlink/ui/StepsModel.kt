package io.github.ringlink.ui

import androidx.core.text.util.LocalePreferences
import io.github.ringlink.L
import io.github.ringlink.data.DaySteps
import io.github.ringlink.data.HourSteps
import io.github.ringlink.data.PeriodSteps
import io.github.ringlink.data.Settings
import io.github.ringlink.data.StepAnalytics
import io.github.ringlink.data.StepQuarterEntity
import io.github.ringlink.data.StepQuarters
import io.github.ringlink.data.StepStore
import io.github.ringlink.data.Span
import io.github.ringlink.data.StoredQuarter
import io.github.ringlink.data.Streaks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** A day of the last seven, with its hours by clock hour for the chart. */
data class WeekDay(val day: DaySteps, val hours: List<HourSteps?>) {
    val peak: HourSteps? get() = hours.filterNotNull().filter { it.steps > 0 }.maxByOrNull { it.steps }
}

data class MonthView(
    val month: YearMonth,
    /** Days of the month up to today. */
    val days: List<DaySteps>,
    /** Every calendar week that touches the month, reported over the whole week. */
    val weeks: List<PeriodSteps>,
    val summary: PeriodSteps?,
    val current: Boolean,
)

data class RingLabel(val address: String, val name: String, val colorIndex: Int)

data class StepsView(
    val today: LocalDate,
    val nowMillis: Long,
    val goal: Int,
    val fullDayHours: Int,
    val firstDayOfWeek: DayOfWeek,
    /** By clock hour, 0 to 23 — see StepAnalytics.clockHours. */
    val todayHours: List<HourSteps?>,
    val yesterdayHours: List<HourSteps?>,
    val todayDay: DaySteps,
    val yesterdayDay: DaySteps,
    /** The end of the last quarter that is over: both days are compared up to it. */
    val comparedUntil: LocalTime,
    val todayByNow: Span,
    val yesterdayByNow: Span,
    /** Quarters of today so far, the one in progress included. */
    val elapsedQuarters: Int,
    val week: List<WeekDay>,
    val weekSummary: PeriodSteps?,
    /** This month, then the one before. */
    val months: List<MonthView>,
    val rings: List<RingLabel>,
) {
    val fullDayQuarters: Int get() = fullDayHours * 4
}

data class AllTimeView(
    val summary: PeriodSteps?,
    /** Newest first. */
    val months: List<Pair<YearMonth, PeriodSteps>>,
    val bestWeek: PeriodSteps?,
    val streaks: Streaks,
)

/**
 * Everything the Steps screen shows, kept current while it is on screen.
 *
 * Quarters are re-derived from the newest readings every [REFRESH_MS] while anyone is watching,
 * and the view is rebuilt every minute as well, since "by now" and the hour in progress move on
 * whether or not a reading arrives. Recent views — the two months on screen — follow the table
 * live; the whole period is loaded once a day, plus today, since a year of quarters is not
 * something to re-read every half minute.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StepsModel(
    scope: CoroutineScope,
    private val store: StepStore,
    private val settings: Settings,
    private val ringName: (String) -> String,
) {
    private data class Prefs(val goal: Int, val fullDayHours: Int)

    /** The day, time zone and week the views are built for; moved on when any of them change. */
    private data class Where(val date: LocalDate, val zone: ZoneId, val firstDay: DayOfWeek)

    private val where = MutableStateFlow(currentWhere())
    private val prefs = MutableStateFlow(readPrefs())

    private fun currentWhere(): Where {
        val zone = ZoneId.systemDefault()
        return Where(LocalDate.now(zone), zone, firstDayOfWeek())
    }

    private fun checkWhere() {
        val now = currentWhere()
        if (now != where.value) where.value = now
    }

    private val refresher: Flow<Nothing> = flow {
        while (true) {
            checkWhere()
            try {
                store.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e("steps refresh failed", e)
            }
            delay(REFRESH_MS)
        }
    }

    /** The time, once a minute on the minute. */
    private val clock: Flow<Long> = flow {
        while (true) {
            checkWhere()
            val now = System.currentTimeMillis()
            emit(now)
            delay(MINUTE_MS - now % MINUTE_MS)
        }
    }

    val view: StateFlow<StepsView?> = merge(
        refresher,
        combine(where, prefs) { w, p -> w to p }.flatMapLatest { (w, p) ->
            val from = windowStart(w.date, w.firstDay)
            combine(store.quartersFlow(millis(from, w.zone), millis(w.date.plusDays(1), w.zone)), clock) { rows, now ->
                build(rows, w, p, from, now)
            }
        },
    )
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private val past: StateFlow<List<DaySteps>?> = combine(where, StepStore.rebuilds) { w, _ -> w }
        .mapLatest { w -> loadPast(w) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val allTime: StateFlow<AllTimeView?> = combine(past, view) { p, v ->
        if (p == null || v == null) null else buildAllTime(p + v.todayDay, v)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Re-read the goal and full-day threshold — after they are set here or restored from a backup. */
    fun prefsChanged() {
        prefs.value = readPrefs()
    }

    fun setFullDayHours(hours: Int) {
        settings.fullDayHours = hours
        prefsChanged()
    }

    private fun readPrefs() = Prefs(settings.stepGoal, settings.fullDayHours)

    private fun build(rows: List<StepQuarterEntity>, w: Where, p: Prefs, from: LocalDate, now: Long): StepsView {
        val today = w.date
        val zone = w.zone
        val merged = StepAnalytics.merge(rows.map { StoredQuarter(it.ringId, it.startMillis, it.steps, it.worn) })
        val fullQ = p.fullDayHours * 4
        val days = StepAnalytics.daily(merged, from, today, zone, today = today)
        val byDate = days.associateBy { it.date }
        val yesterday = today.minusDays(1)
        fun hours(date: LocalDate) = StepAnalytics.clockHours(StepAnalytics.hourly(merged, date, zone))

        val week = (6L downTo 0L).map { today.minusDays(it) }.map { WeekDay(byDate.getValue(it), hours(it)) }

        val allWeeks = StepAnalytics.weeks(days, w.firstDay, fullQ)
        val months = listOf(YearMonth.from(today), YearMonth.from(today).minusMonths(1)).map { ym ->
            val inMonth = days.filter { YearMonth.from(it.date) == ym }
            MonthView(
                month = ym,
                days = inMonth,
                weeks = allWeeks.filter { !it.first.isAfter(ym.atEndOfMonth()) && !it.last.isBefore(ym.atDay(1)) },
                summary = StepAnalytics.period(inMonth, fullQ),
                current = ym == YearMonth.from(today),
            )
        }

        val compared = Instant.ofEpochMilli(StepQuarters.floor(now)).atZone(zone).toLocalTime()
        val midnight = millis(today, zone)
        val seen = rows.map { it.ringId }.distinct()
        val ordered = (settings.rings.map { it.address } + seen).distinct().filter { it in seen }
        return StepsView(
            today = today,
            nowMillis = now,
            goal = p.goal,
            fullDayHours = p.fullDayHours,
            firstDayOfWeek = w.firstDay,
            todayHours = hours(today),
            yesterdayHours = hours(yesterday),
            todayDay = byDate.getValue(today),
            yesterdayDay = byDate.getValue(yesterday),
            comparedUntil = compared,
            todayByNow = StepAnalytics.before(merged, today, compared, zone),
            yesterdayByNow = StepAnalytics.before(merged, yesterday, compared, zone),
            elapsedQuarters = ((now - midnight + StepQuarters.QUARTER_MS - 1) / StepQuarters.QUARTER_MS).toInt().coerceAtLeast(0),
            week = week,
            weekSummary = StepAnalytics.period(week.map { it.day }, fullQ),
            months = months,
            rings = ordered.mapIndexed { i, address -> RingLabel(address, ringName(address), i) },
        )
    }

    private suspend fun loadPast(w: Where): List<DaySteps> {
        val rows = store.quarters(0, millis(w.date, w.zone))
        if (rows.isEmpty()) return emptyList()
        val merged = StepAnalytics.merge(rows.map { StoredQuarter(it.ringId, it.startMillis, it.steps, it.worn) })
        val first = Instant.ofEpochMilli(rows.first().startMillis).atZone(w.zone).toLocalDate()
        if (!first.isBefore(w.date)) return emptyList()
        return StepAnalytics.daily(merged, first, w.date.minusDays(1), w.zone)
    }

    private fun buildAllTime(days: List<DaySteps>, v: StepsView): AllTimeView {
        // Leading days before anything was heard are not part of the period of use.
        val used = days.dropWhile { !it.heard }
        return AllTimeView(
            summary = StepAnalytics.period(used, v.fullDayQuarters),
            months = StepAnalytics.months(used, v.fullDayQuarters).asReversed(),
            bestWeek = StepAnalytics.weeks(used, v.firstDayOfWeek, v.fullDayQuarters).maxByOrNull { it.total },
            streaks = StepAnalytics.streaks(used, v.goal),
        )
    }

    private companion object {
        const val REFRESH_MS = 30_000L
        const val MINUTE_MS = 60_000L

        fun millis(date: LocalDate, zone: ZoneId) = date.atStartOfDay(zone).toInstant().toEpochMilli()

        /** Far enough back for the previous month's first week and the last seven days. */
        fun windowStart(today: LocalDate, firstDay: DayOfWeek): LocalDate {
            val monthWeek = today.minusMonths(1).withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(firstDay))
            return minOf(monthWeek, today.minusDays(6))
        }

        /** The user's own first day of the week where Android lets them choose it, else the locale's. */
        fun firstDayOfWeek(): DayOfWeek {
            val days = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
            val chosen = runCatching { LocalePreferences.getFirstDayOfWeek() }.getOrNull()
            val i = days.indexOf(chosen)
            return if (i >= 0) DayOfWeek.of(i + 1) else WeekFields.of(Locale.getDefault()).firstDayOfWeek
        }
    }
}
