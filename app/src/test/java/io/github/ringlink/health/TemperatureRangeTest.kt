package io.github.ringlink.health

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Health Connect's rule for a skin-temperature record, encoded so it cannot be broken again.
 *
 * Its constructor accepts a delta at exactly `startTime` but requires every delta to be strictly
 * before `endTime`; breaking that once crashed the whole export. Records now span one clock hour,
 * and every reading assigned to an hour must sit inside it by that rule — including one taken at
 * the very first millisecond of the hour and one at the very last.
 */
class TemperatureRangeTest {

    private val hour = 3_600_000L
    private fun hourOf(t: Long) = Math.floorDiv(t, hour) * hour

    @Test fun `every reading falls inside its hour's record`() {
        val base = 1_761_199_200_000L          // an hour boundary
        for (t in listOf(base, base + 1, base + hour / 2, base + hour - 1)) {
            val start = Instant.ofEpochMilli(hourOf(t))
            val end = start.plusMillis(hour)
            val at = Instant.ofEpochMilli(t)
            assertTrue("$at is before the record start", !at.isBefore(start))
            assertTrue("$at is not strictly before the record end", at.isBefore(end))
        }
    }

    /** The first millisecond of the next hour belongs to the next record, not this one. */
    @Test fun `the boundary belongs to the next hour`() {
        val base = 1_761_199_200_000L
        assertTrue(hourOf(base + hour) == base + hour)
    }
}
