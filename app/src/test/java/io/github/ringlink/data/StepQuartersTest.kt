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

    /**
     * A ring at zero never drops, so the morning's first steps continue the night's run. They must
     * still land in the quarter they were walked in, not the middle of the night.
     */
    @Test fun `steps after a long run of zeros stay in their own quarter`() {
        val night = (0 until 60).map { s(it, 0, 0) }
        val q = StepQuarters.quarters(night + listOf(s(62, 0, 120), s(70, 0, 300)))
        assertEquals(300, q.single { it.startMillis == at(60) }.steps)
        assertEquals(300, StepQuarters.total(q))
    }

    /** Every quarter heard is its own entry, even at zero — that is what coverage is counted from. */
    @Test fun `an hour of zeros is four quarters`() {
        val q = StepQuarters.quarters((0 until 60).map { s(it, 30, 0) })
        assertEquals(listOf(at(0), at(15), at(30), at(45)), q.map { it.startMillis })
    }

    /**
     * Two quarters with no reading near the boundary: the second count overtook the first before
     * anything was heard, so there is no drop. Both quarters still count.
     */
    @Test fun `a reset hidden by a higher count still splits the quarters`() {
        val q = StepQuarters.quarters(listOf(s(8, 59, 277), s(19, 12, 430), s(29, 43, 1207)))
        assertEquals(277 + 1207, StepQuarters.total(q))
        assertEquals(listOf(at(0), at(15)), q.map { it.startMillis })
    }

    /** Still climbing a second past the boundary: the ring has not reset, it is the old quarter. */
    @Test fun `a stale reading that still climbs belongs to the old quarter`() {
        val q = StepQuarters.quarters(listOf(s(14, 43, 153), s(15, 1, 179), s(15, 40, 3), s(20, 0, 60)))
        assertEquals(listOf(179, 60), q.map { it.steps })
    }

    @Test fun `a ring resetting just before the phone's boundary starts the next quarter`() {
        val q = StepQuarters.quarters(listOf(s(10, 0, 400), s(14, 45, 0), s(20, 0, 80)))
        assertEquals(listOf(at(0) to 400, at(15) to 80), q.map { it.startMillis to it.steps })
    }

    /** Cleared mid-quarter for some other reason: what came before the drop is not thrown away. */
    @Test fun `a drop in the middle of a quarter keeps both parts`() {
        val q = StepQuarters.quarters(listOf(s(2, 0, 100), s(5, 0, 0), s(10, 0, 50)))
        assertEquals(listOf(at(0) to 150), q.map { it.startMillis to it.steps })
    }

    @Test fun `a quarter is worn if any reading in it was`() {
        val q = StepQuarters.quarters(
            listOf(StepSample(at(1), 0, worn = false), StepSample(at(5), 10, worn = true), StepSample(at(16), 0, worn = false)),
        )
        assertEquals(listOf(true, false), q.map { it.worn })
    }

    /** Cut at a boundary, a window must not open with a stale reading as its own quarter. */
    @Test fun `a window keeps only quarters starting inside it`() {
        val samples = listOf(s(14, 24, 66), s(15, 3, 66), s(15, 40, 4), s(29, 0, 30))
        val q = StepQuarters.quartersIn(samples, at(15), at(30))
        assertEquals(listOf(at(15) to 30), q.map { it.startMillis to it.steps })
    }

    /**
     * Back in range a few seconds after a boundary, the first reading still carries the previous
     * quarter's count — the ring has not reset — with nothing before it to be stale against. The
     * reset that follows shows it; the count goes back a quarter, not into this one.
     */
    @Test fun `a stale reading after a gap goes back to its own quarter`() {
        val q = StepQuarters.quarters(listOf(s(5, 0, 120), s(75, 5, 1046), s(75, 19, 4), s(80, 0, 300)))
        assertEquals(listOf(at(0) to 120, at(60) to 1046, at(75) to 300), q.map { it.startMillis to it.steps })
    }

    /** The reset can arrive well after the lag; it is the stale readings that must be inside it. */
    @Test fun `the reset that exposes a stale run may come much later`() {
        val q = StepQuarters.quarters(listOf(s(5, 0, 120), s(75, 10, 900), s(77, 0, 50)))
        assertEquals(listOf(at(0) to 120, at(60) to 900, at(75) to 50), q.map { it.startMillis to it.steps })
    }

    @Test fun `readings clear of both corrections are settled`() {
        assertEquals(false, StepQuarters.settled(at(15, 20)))
        assertEquals(true, StepQuarters.settled(at(15, 31)))
        assertEquals(false, StepQuarters.settled(at(29, 40)))
    }

    /** Any window, derived from a settled reading before it, matches deriving the whole day. */
    @Test fun `a window from a settled reading matches the whole derivation`() {
        val random = java.util.Random(7)
        var t = base
        var count = 0
        val samples = ArrayList<StepSample>()
        repeat(4000) {
            t += if (random.nextInt(40) == 0) 60_000L * (5 + random.nextInt(40)) else 1000L * (5 + random.nextInt(110))
            val quarter = Math.floorDiv(t - 12_000, StepQuarters.QUARTER_MS)        // ring 12 s behind
            if (samples.isNotEmpty() && quarter != Math.floorDiv(samples.last().atMillis - 12_000, StepQuarters.QUARTER_MS)) count = 0
            count += random.nextInt(30)
            samples += StepSample(t, count)
        }
        val whole = StepQuarters.quarters(samples)
        val first = StepQuarters.floor(samples.first().atMillis)
        val end = StepQuarters.floor(samples.last().atMillis) + StepQuarters.QUARTER_MS
        val q = StepQuarters.QUARTER_MS
        var from = first
        while (from < end) {
            val to = minOf(from + 8 * q, end)
            val anchor = samples.lastOrNull { it.atMillis < from - q && StepQuarters.settled(it.atMillis) }?.atMillis ?: Long.MIN_VALUE
            val window = samples.filter { it.atMillis >= anchor && it.atMillis < to + q }
            assertEquals(
                "window at ${(from - first) / q}",
                whole.filter { it.startMillis >= from && it.startMillis < to },
                StepQuarters.quartersIn(window, from, to),
            )
            from = to
        }
    }
}
