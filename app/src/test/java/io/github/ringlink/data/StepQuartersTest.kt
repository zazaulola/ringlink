package io.github.ringlink.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StepQuartersTest {

    private val base = 1_760_000_400_000L          // a quarter boundary
    private fun at(min: Int, sec: Int = 0) = base + min * 60_000L + sec * 1000L
    private fun s(min: Int, sec: Int, steps: Int) = StepSample(at(min, sec), steps)

    /** The counter climbs through a quarter and is cleared at the next boundary. */
    @Test fun `a day is the sum of each quarter's highest count`() {
        val samples = listOf(
            s(1, 0, 10), s(7, 0, 40), s(14, 24, 66),     // quarter 1
            s(15, 6, 0), s(20, 0, 12), s(29, 0, 30),      // quarter 2
        )
        assertEquals(96, StepQuarters.total(StepQuarters.quarters(samples)))
    }

    /**
     * The reason quarters are split on the drop rather than the phone's clock: a reading a few
     * seconds after the phone's boundary can still show the old count. Bucketed by phone time it
     * would be counted in both quarters.
     */
    @Test fun `a stale reading just past the boundary is not counted twice`() {
        val samples = listOf(
            s(14, 24, 66),
            s(15, 3, 66),      // phone says new quarter, ring has not reset yet
            s(15, 40, 4),      // now it has
            s(29, 0, 30),
        )
        val q = StepQuarters.quarters(samples)
        assertEquals(96, StepQuarters.total(q))
        assertEquals(listOf(at(0), at(15)), q.map { it.startMillis })
    }

    /** No drop seen, but an hour passed: resets happened unseen, so these are separate quarters. */
    @Test fun `a long gap starts a new quarter even without a drop`() {
        val samples = listOf(s(5, 0, 30), s(70, 0, 50))
        assertEquals(80, StepQuarters.total(StepQuarters.quarters(samples)))
    }

    @Test fun `a ring on the charger walks nothing`() {
        val samples = (0 until 40).map { s(it, 0, 0) }
        assertEquals(0, StepQuarters.total(StepQuarters.quarters(samples)))
    }

    /** One ring on the finger, the other at zero in its case: the day is the worn ring's. */
    @Test fun `rings add up independently`() {
        val worn = listOf(s(1, 0, 20), s(14, 0, 70), s(16, 0, 5), s(29, 0, 25))
        val charging = (0 until 30).map { s(it, 30, 0) }
        assertEquals(95, StepQuarters.totalAcrossRings(mapOf("worn" to worn, "spare" to charging)))
    }

    @Test fun `order of readings does not matter`() {
        val samples = listOf(s(29, 0, 30), s(1, 0, 10), s(15, 6, 0), s(14, 24, 66), s(7, 0, 40))
        assertEquals(96, StepQuarters.total(StepQuarters.quarters(samples)))
    }
}
