package io.github.ringlink.health

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Health Connect's rule for a skin-temperature record, encoded so it cannot be broken again.
 *
 * Its constructor accepts a delta at exactly `startTime`, but requires every delta to be strictly
 * before `endTime`. Ending a record on its last reading's own instant therefore throws
 * `IllegalArgumentException: deltas can not be out of parent time range` — which crashed the export
 * coroutine and, with it, every other record type in the same batch.
 */
class TemperatureRangeTest {

    /** Mirrors the range HealthConnectWriter.mapSkinTemperature builds for a group of readings. */
    private fun rangeFor(readingTimes: List<Long>): Pair<Instant, Instant> {
        val ordered = readingTimes.sorted()
        val start = Instant.ofEpochMilli(ordered.first())
        val end = Instant.ofEpochMilli(ordered.last()).plusSeconds(1)
        return start to end
    }

    @Test fun `every reading falls inside the record's range`() {
        val readings = listOf(1_700_000_000_000L, 1_700_000_030_000L, 1_700_000_060_000L)
        val (start, end) = rangeFor(readings)
        for (t in readings) {
            val at = Instant.ofEpochMilli(t)
            assertTrue("$at is before the record start", !at.isBefore(start))
            assertTrue("$at is not strictly before the record end", at.isBefore(end))
        }
    }

    /** A single reading still has to produce a usable, non-empty range. */
    @Test fun `a lone reading still yields a valid range`() {
        val only = 1_700_000_000_000L
        val (start, end) = rangeFor(listOf(only))
        assertTrue(end.isAfter(start))
        assertTrue(Instant.ofEpochMilli(only).isBefore(end))
    }
}
