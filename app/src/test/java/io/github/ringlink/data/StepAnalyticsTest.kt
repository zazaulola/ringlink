package io.github.ringlink.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class StepAnalyticsTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 10, 27)

    /** A quarter starting at [hh]:[mm] local time on [day]. */
    private fun q(day: LocalDate, hh: Int, mm: Int, steps: Int, ring: String = "F749", worn: Boolean = true, zone: ZoneId = moscow) =
        StoredQuarter(ring, day.atTime(hh, mm).atZone(zone).toInstant().toEpochMilli(), steps, worn)

    private fun merged(vararg qs: StoredQuarter) = StepAnalytics.merge(qs.toList())

    @Test fun `an hour is the sum of its four quarters`() {
        val hours = StepAnalytics.hourly(
            merged(q(today, 9, 0, 100), q(today, 9, 15, 200), q(today, 9, 30, 50), q(today, 9, 45, 0)),
            today, moscow,
        )
        assertEquals(24, hours.size)
        assertEquals(350, hours[9].steps)
        assertEquals(4, hours[9].heardQuarters)
        assertFalse("nothing was heard at 10:00", hours[10].heard)
        assertEquals(listOf(100, 200, 50, 0), hours[9].quarters.map { it?.steps })
    }

    /** Swapping rings mid-hour: steps from both add up, the docked one adds nothing. */
    @Test fun `two rings add up and either being heard counts`() {
        val m = merged(
            q(today, 14, 0, 300, ring = "F749"),
            q(today, 14, 0, 0, ring = "CEF0", worn = false),
            q(today, 14, 30, 0, ring = "F749", worn = false),
            q(today, 14, 30, 120, ring = "CEF0"),
        )
        val hour = StepAnalytics.hourly(m, today, moscow)[14]
        assertEquals(420, hour.steps)
        assertEquals(2, hour.heardQuarters)
        assertEquals(setOf("F749", "CEF0"), StepAnalytics.daily(m, today, today, moscow).single().rings)
    }

    @Test fun `a ring that is not worn and counts nothing is not heard`() {
        val day = StepAnalytics.daily(merged(q(today, 3, 0, 0, worn = false)), today, today, moscow).single()
        assertEquals(0, day.heardQuarters)
        assertFalse(day.heard)
    }

    /** Fingers get cold outdoors; a ring counting steps is clearly being carried whatever it reads. */
    @Test fun `a quarter with steps is heard even when the skin read cold`() {
        val day = StepAnalytics.daily(merged(q(today, 8, 0, 400, worn = false)), today, today, moscow).single()
        assertEquals(1, day.heardQuarters)
        assertEquals(400, day.steps)
    }

    @Test fun `days run from the first date to the last, empty ones included`() {
        val days = StepAnalytics.daily(merged(q(today, 10, 0, 500)), today.minusDays(2), today, moscow)
        assertEquals(listOf(0, 0, 500), days.map { it.steps })
    }

    @Test fun `days follow local midnight`() {
        val days = StepAnalytics.daily(
            merged(q(today, 23, 45, 70), q(today.plusDays(1), 0, 0, 30)),
            today, today.plusDays(1), moscow,
        )
        assertEquals(listOf(70, 30), days.map { it.steps })
    }

    @Test fun `a spring-forward day has 23 hours and loses none of its steps`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val change = LocalDate.of(2026, 3, 29)
        val hours = StepAnalytics.hourly(merged(q(change, 4, 0, 40, zone = berlin)), change, berlin)
        assertEquals(23, hours.size)
        assertEquals(40, hours.single { it.hour == 4 }.steps)
    }

    /** Average over full days only: a day the phone was elsewhere is not a day of few steps. */
    @Test fun `averages leave out days that were not heard for long enough`() {
        val monday = LocalDate.of(2026, 10, 19)
        val p = StepAnalytics.period(
            listOf(DaySteps(monday, 8000, 60), DaySteps(monday.plusDays(1), 900, 8), DaySteps(monday.plusDays(2), 4000, 56)),
            fullDayQuarters = 52,
        )!!
        assertEquals(12900L, p.total)
        assertEquals(3, p.heardDays)
        assertEquals(2, p.fullDays)
        assertEquals(6000, p.averagePerFullDay)
        assertEquals(monday, p.best?.date)
    }

    @Test fun `no full day means no average`() {
        assertNull(StepAnalytics.period(listOf(DaySteps(today, 500, 4)), 52)!!.averagePerFullDay)
    }

    @Test fun `weeks start on the day the locale starts them`() {
        val sunday = LocalDate.of(2026, 10, 25)
        val days = listOf(DaySteps(sunday, 100, 60), DaySteps(sunday.plusDays(1), 200, 60))
        assertEquals(
            listOf(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26)),
            StepAnalytics.weeks(days, DayOfWeek.MONDAY, 52).map { it.first },
        )
        assertEquals(listOf(sunday), StepAnalytics.weeks(days, DayOfWeek.SUNDAY, 52).map { it.first })
    }

    @Test fun `a week spans seven days even when only some are given`() {
        val week = StepAnalytics.weeks(listOf(DaySteps(today, 100, 60)), DayOfWeek.MONDAY, 52).single()
        assertEquals(LocalDate.of(2026, 10, 26), week.first)
        assertEquals(LocalDate.of(2026, 11, 1), week.last)
    }

    @Test fun `months group by calendar month`() {
        val days = listOf(DaySteps(LocalDate.of(2026, 9, 30), 100, 60), DaySteps(LocalDate.of(2026, 10, 1), 300, 60))
        assertEquals(listOf(100L, 300L), StepAnalytics.months(days, 52).map { it.second.total })
    }

    /** Today is still being walked; not having reached the goal yet does not end the streak. */
    @Test fun `streaks count goal days and forgive an unfinished today`() {
        val goal = 1000
        val days = listOf(1200, 0, 1500, 1100, 1300, 400).mapIndexed { i, s -> DaySteps(today.minusDays(5L - i), s, 60) }
        assertEquals(Streaks(current = 3, longest = 3), StepAnalytics.streaks(days, goal))
        val reached = days.dropLast(1) + DaySteps(today, 1000, 60)
        assertEquals(Streaks(current = 4, longest = 4), StepAnalytics.streaks(reached, goal))
    }

    @Test fun `by this time yesterday counts only quarters already over`() {
        val yesterday = today.minusDays(1)
        val m = merged(q(yesterday, 9, 0, 100), q(yesterday, 14, 15, 50), q(yesterday, 14, 30, 70), q(yesterday, 20, 0, 999))
        assertEquals(Span(150, 2), StepAnalytics.before(m, yesterday, LocalTime.of(14, 30), moscow))
        assertFalse(StepAnalytics.before(m, yesterday, LocalTime.of(9, 0), moscow).heard)
    }

    /** Today is not over: however long it has been heard, it is not a full day for averages yet. */
    @Test fun `today never counts as a full day`() {
        val m = merged(*(0 until 60).map { q(today, it / 4, (it % 4) * 15, 100) }.toTypedArray(), q(today.minusDays(1), 10, 0, 50))
        val days = StepAnalytics.daily(m, today.minusDays(1), today, moscow, today = today)
        assertTrue(days.first().complete)
        assertFalse(days.last().isFull(52))
        assertNull(StepAnalytics.period(days, 52)!!.averagePerFullDay)
    }

    /** A fall-back day has two 02:00s; they share a slot, so yesterday and today still line up. */
    @Test fun `clock hours line up across a daylight-saving change`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val back = LocalDate.of(2026, 10, 25)
        val hours = StepAnalytics.hourly(merged(q(back, 3, 0, 30, zone = berlin)), back, berlin)
        assertEquals(25, hours.size)
        val slots = StepAnalytics.clockHours(hours)
        assertEquals(24, slots.size)
        assertEquals(8, slots[2]!!.quarters.size)
        assertEquals(30, slots[3]!!.steps)

        val forward = LocalDate.of(2026, 3, 29)
        val spring = StepAnalytics.clockHours(StepAnalytics.hourly(emptyMap(), forward, berlin))
        assertNull("02:00 does not happen that day", spring[2])
        assertEquals(23, spring.count { it != null })
    }

    @Test fun `a docked ring adds nothing to its quarter`() {
        assertTrue(StepAnalytics.merge(listOf(q(today, 1, 0, 0, worn = false))).values.none { it.heard })
    }
}
