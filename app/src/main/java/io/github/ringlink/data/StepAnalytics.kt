package io.github.ringlink.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** One stored quarter-hour of steps from one ring. */
data class StoredQuarter(val ringId: String, val startMillis: Long, val steps: Int, val worn: Boolean) {
    /**
     * Whether the quarter says anything about the walking done in it: the ring was on a finger, or
     * it counted steps — which a ring lying on a table or docked does not do.
     */
    val heard: Boolean get() = worn || steps > 0
}

/** Every ring's steps in one quarter-hour, together. */
data class MergedQuarter(val startMillis: Long, val steps: Int, val heard: Boolean, val rings: Set<String>)

/** Steps in one clock hour, and its four quarters for a closer look; a quarter nobody heard is null. */
data class HourSteps(
    val startMillis: Long,
    val hour: Int,
    val steps: Int,
    val heardQuarters: Int,
    val quarters: List<MergedQuarter?>,
) {
    val heard: Boolean get() = heardQuarters > 0
}

/**
 * Steps on one day, and how much of it was heard.
 *
 * Steps are only counted while the phone is near a ring on a finger, so a low total can mean a
 * quiet day or a day the phone was elsewhere. [heardQuarters] is what tells the two apart.
 */
data class DaySteps(
    val date: LocalDate,
    val steps: Int,
    val heardQuarters: Int,
    /** Rings heard that day — two on a day the worn ring was swapped. */
    val rings: Set<String> = emptySet(),
    /** False for today: half a day heard all morning is not a full day, however long it was heard. */
    val complete: Boolean = true,
) {
    val heardHours: Double get() = heardQuarters / 4.0
    val heard: Boolean get() = heardQuarters > 0
    fun isFull(fullDayQuarters: Int): Boolean = complete && heardQuarters >= fullDayQuarters
}

/** Steps over part of a day, and how much of it was heard. */
data class Span(val steps: Int, val heardQuarters: Int) {
    val heard: Boolean get() = heardQuarters > 0
}

/** A run of days — a week, a month, the whole period of use. */
data class PeriodSteps(
    val first: LocalDate,
    val last: LocalDate,
    val total: Long,
    val heardDays: Int,
    val fullDays: Int,
    /**
     * Over full days only. A day heard for two hours is not a day of few steps, and averaged in it
     * drags the average down; null when no day was full.
     */
    val averagePerFullDay: Int?,
    val best: DaySteps?,
)

data class Streaks(
    /** Days in a row up to today that reached the goal; today counts once it has. */
    val current: Int,
    val longest: Int,
)

/**
 * Turns stored quarter-hours into hours, days, weeks, months and the whole period.
 *
 * Rings are combined by time. Their steps in a quarter add up: each ring counts only what it was
 * carried for, and a docked ring's counter stands still, so a quarter split by a swap is the sum
 * of both. Days and hours follow the phone's time zone, including the 23- and 25-hour days a
 * daylight-saving change makes; every zone's offset is a whole number of quarters, so quarters
 * always fall cleanly into local hours.
 */
object StepAnalytics {

    private const val Q = StepQuarters.QUARTER_MS

    fun merge(quarters: List<StoredQuarter>): Map<Long, MergedQuarter> {
        val out = HashMap<Long, MergedQuarter>()
        for (q in quarters) {
            val prev = out[q.startMillis]
            out[q.startMillis] = MergedQuarter(
                startMillis = q.startMillis,
                steps = (prev?.steps ?: 0) + q.steps,
                heard = (prev?.heard ?: false) || q.heard,
                rings = (prev?.rings ?: emptySet()) + if (q.heard) setOf(q.ringId) else emptySet(),
            )
        }
        return out
    }

    /** Every clock hour of [date], in order — 24 of them, or 23 or 25 across a clock change. */
    fun hourly(merged: Map<Long, MergedQuarter>, date: LocalDate, zone: ZoneId): List<HourSteps> {
        val out = ArrayList<HourSteps>()
        val end = date.plusDays(1).atStartOfDay(zone)
        var hour = date.atStartOfDay(zone)
        while (hour.isBefore(end)) {
            val from = hour.toInstant().toEpochMilli()
            val quarters = (0 until 4).map { merged[from + it * Q]?.takeIf { q -> q.heard || q.steps > 0 } }
            out += HourSteps(
                startMillis = from,
                hour = hour.hour,
                steps = quarters.sumOf { it?.steps ?: 0 },
                heardQuarters = quarters.count { it?.heard == true },
                quarters = quarters,
            )
            hour = hour.plusHours(1)
        }
        return out
    }

    /**
     * The hours of a day by wall-clock hour, 0 to 23, so two days line up hour for hour. A clock
     * change makes one hour happen twice — both are summed into its slot, with eight quarters —
     * or not at all, which leaves its slot null.
     */
    fun clockHours(hours: List<HourSteps>): List<HourSteps?> = (0 until 24).map { h ->
        val same = hours.filter { it.hour == h }
        when (same.size) {
            0 -> null
            1 -> same.single()
            else -> HourSteps(
                startMillis = same.first().startMillis,
                hour = h,
                steps = same.sumOf { it.steps },
                heardQuarters = same.sumOf { it.heardQuarters },
                quarters = same.flatMap { it.quarters },
            )
        }
    }

    /** One entry per day from [from] to [to], both included, in order; [today] on is incomplete. */
    fun daily(
        merged: Map<Long, MergedQuarter>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
        today: LocalDate? = null,
    ): List<DaySteps> {
        val steps = HashMap<LocalDate, Int>()
        val heard = HashMap<LocalDate, Int>()
        val rings = HashMap<LocalDate, MutableSet<String>>()
        for (q in merged.values) {
            val day = Instant.ofEpochMilli(q.startMillis).atZone(zone).toLocalDate()
            if (day < from || day > to) continue
            steps[day] = (steps[day] ?: 0) + q.steps
            if (q.heard) heard[day] = (heard[day] ?: 0) + 1
            rings.getOrPut(day) { HashSet() } += q.rings
        }
        val out = ArrayList<DaySteps>()
        var day = from
        while (!day.isAfter(to)) {
            out += DaySteps(day, steps[day] ?: 0, heard[day] ?: 0, rings[day].orEmpty(), complete = today == null || day < today)
            day = day.plusDays(1)
        }
        return out
    }

    /**
     * Steps on [date] in the quarters over by [time] of day — for "by this time yesterday", where
     * today's quarter in progress has no counterpart yet.
     */
    fun before(merged: Map<Long, MergedQuarter>, date: LocalDate, time: LocalTime, zone: ZoneId): Span {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        val inside = merged.values.filter { it.startMillis >= from && it.startMillis + Q <= until }
        return Span(inside.sumOf { it.steps }, inside.count { it.heard })
    }

    /** Totals over [days]; null when there are none. */
    fun period(days: List<DaySteps>, fullDayQuarters: Int): PeriodSteps? {
        if (days.isEmpty()) return null
        val full = days.filter { it.isFull(fullDayQuarters) }
        return PeriodSteps(
            first = days.minOf { it.date },
            last = days.maxOf { it.date },
            total = days.sumOf { it.steps.toLong() },
            heardDays = days.count { it.heard },
            fullDays = full.size,
            averagePerFullDay = if (full.isEmpty()) null else (full.sumOf { it.steps.toLong() } / full.size).toInt(),
            best = days.filter { it.steps > 0 }.maxByOrNull { it.steps },
        )
    }

    /**
     * Calendar weeks over [days], starting on [firstDay] — the user's locale's, not a rolling seven
     * days, which is what people complain about most in the apps that do that. A week is reported
     * over whichever of its days are in [days], so give whole weeks for whole-week figures.
     */
    fun weeks(days: List<DaySteps>, firstDay: DayOfWeek, fullDayQuarters: Int): List<PeriodSteps> =
        days.groupBy { it.date.with(TemporalAdjusters.previousOrSame(firstDay)) }
            .toSortedMap()
            .mapNotNull { (start, inWeek) ->
                period(inWeek, fullDayQuarters)?.copy(first = start, last = start.plusDays(6))
            }

    fun months(days: List<DaySteps>, fullDayQuarters: Int): List<Pair<YearMonth, PeriodSteps>> =
        days.groupBy { YearMonth.from(it.date) }
            .toSortedMap()
            .mapNotNull { (month, inMonth) -> period(inMonth, fullDayQuarters)?.let { month to it } }

    /**
     * Goal streaks over consecutive [days] ending today. Today is still being walked, so not having
     * reached the goal yet does not break the streak running up to yesterday.
     */
    fun streaks(days: List<DaySteps>, goal: Int): Streaks {
        var longest = 0
        var run = 0
        for (d in days) {
            run = if (d.steps >= goal) run + 1 else 0
            longest = maxOf(longest, run)
        }
        val past = if (days.lastOrNull()?.let { it.steps < goal } == true) days.dropLast(1) else days
        val current = past.asReversed().takeWhile { it.steps >= goal }.size
        return Streaks(current, longest)
    }
}
