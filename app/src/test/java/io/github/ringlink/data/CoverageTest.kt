package io.github.ringlink.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTest {

    private fun epoch(ring: String, counter: Long, hr: Int?) = EpochEntity(
        ringId = ring, counter = counter, channel = 0, layout = "ACTIVITY",
        heartRate = hr, hrvRmssd = null, confidence = 0, respiratoryRate = null,
        spo2 = null, motionSum = 5,
    )

    /** A run of epochs every 150 s from [from], [count] long. */
    private fun run(ring: String, from: Long, count: Int, hr: Int?) =
        (0 until count).map { epoch(ring, from + it * 150L, hr) }

    /**
     * The real pattern: both rings write epochs at once, but only the worn one has a pulse.
     * The ring in the case must not show up as a source.
     */
    @Test fun `a ring writing empty epochs on the charger is not a source`() {
        val worn = run("F749", 1_000, 40, hr = 72)
        val charging = run("CEF0", 1_000, 40, hr = null)
        val spans = Coverage.spans(worn + charging)
        assertEquals(1, spans.size)
        assertEquals("F749", spans.single().ringId)
    }

    /** The swap routine: one ring, then the other, each a span of its own. */
    @Test fun `swapping rings gives one span per ring in time order`() {
        val first = run("F749", 1_000, 40, hr = 70)
        val second = run("CEF0", 1_000 + 40 * 150L, 40, hr = 74)
        val spans = Coverage.spans(second + first)   // order of input must not matter
        assertEquals(listOf("F749", "CEF0"), spans.map { it.ringId })
        assertEquals(40 * 150L, spans[0].seconds)
    }

    @Test fun `a short gap inside one ring's wear is bridged`() {
        val a = run("F749", 1_000, 10, hr = 70)
        val b = run("F749", 1_000 + 10 * 150L + 20 * 60L, 10, hr = 70)   // 20 min later
        assertEquals(1, Coverage.spans(a + b).size)
    }

    @Test fun `a long gap splits the wear`() {
        val a = run("F749", 1_000, 10, hr = 70)
        val b = run("F749", 1_000 + 10 * 150L + 3 * 3600L, 10, hr = 70)  // 3 h later
        assertEquals(2, Coverage.spans(a + b).size)
    }

    @Test fun `totals add up per ring`() {
        val spans = Coverage.spans(run("F749", 1_000, 24, 70) + run("CEF0", 90_000, 12, 70))
        val totals = Coverage.totals(spans)
        assertEquals(24 * 150L, totals["F749"])
        assertEquals(12 * 150L, totals["CEF0"])
    }

    @Test fun `no pulse anywhere means no coverage`() {
        assertTrue(Coverage.spans(run("F749", 1_000, 10, hr = null)).isEmpty())
    }
}
