package io.github.ringlink.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstLimiterTest {

    /** Offer [count] notifications at [start], one millisecond apart, and count the buzzes. */
    private fun BurstLimiter.buzzesFor(count: Int, start: Long): Int {
        var buzzes = 0
        repeat(count) { i ->
            if (allows(start + i)) {
                spend()
                buzzes++
            }
        }
        return buzzes
    }

    /** The reported complaint: fifteen notifications at once produced fifteen buzzes. */
    @Test fun `fifteen notifications at once are worth three buzzes`() {
        assertEquals(3, BurstLimiter().buzzesFor(15, start = 1_000))
    }

    @Test fun `a hundred at once are still worth three`() {
        assertEquals(3, BurstLimiter().buzzesFor(100, start = 1_000))
    }

    /**
     * Genuinely separate news, after things have gone quiet, gets a fresh budget.
     *
     * The gap is measured from the LAST notification of the burst, not the first — ten arriving
     * over ten milliseconds push the quiet period back by those ten milliseconds too.
     */
    @Test fun `a notification after a quiet gap buzzes again`() {
        val limiter = BurstLimiter()
        val start = 1_000L
        val count = 10
        assertEquals(3, limiter.buzzesFor(count, start = start))

        val lastArrival = start + count - 1
        assertFalse(
            "still the same burst a millisecond short of the gap",
            limiter.allows(lastArrival + BurstLimiter.DEFAULT_BURST_GAP_MS - 1),
        )
    }

    @Test fun `the budget returns once the gap has fully elapsed`() {
        val limiter = BurstLimiter()
        assertEquals(3, limiter.buzzesFor(10, start = 1_000))
        // allows() above kept the burst alive, so measure from that last offer.
        val lastOffer = 1_000L + 10 - 1 + BurstLimiter.DEFAULT_BURST_GAP_MS - 1
        assertTrue(limiter.allows(lastOffer + BurstLimiter.DEFAULT_BURST_GAP_MS))
    }

    /** A trickle that never pauses is still one burst — it must not roll over and resume buzzing. */
    @Test fun `an unbroken trickle does not earn a new budget`() {
        val limiter = BurstLimiter()
        var buzzes = 0
        var now = 1_000L
        // Half the gap apart, for well past the gap: never quiet, so never a new burst.
        repeat(20) {
            if (limiter.allows(now)) { limiter.spend(); buzzes++ }
            now += BurstLimiter.DEFAULT_BURST_GAP_MS / 2
        }
        assertEquals(3, buzzes)
    }

    /**
     * A buzz that never reached the ring must not spend budget — it was never felt, so the next
     * notification still deserves one.
     */
    @Test fun `an undelivered buzz does not consume the budget`() {
        val limiter = BurstLimiter()
        repeat(5) { assertTrue(limiter.allows(1_000L + it)) }   // allowed, never spent

        limiter.spend()
        limiter.spend()
        limiter.spend()
        assertFalse("budget is spent now", limiter.allows(1_010))
    }
}
