package io.github.ringlink.protocol

import io.github.ringlink.health.SleepDetector
import io.github.ringlink.health.SleepInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Built from a real night measured on a Gen 3: still awake around 90 bpm with the hand moving, then
 * motion pinned at the hardware floor of 5 while the rate falls to ~75, then a spike to 106 bpm and
 * 276 motion on getting up.
 */
class SleepDetectorTest {

    private fun epochs(vararg spans: Triple<Int, Int, Int>): List<SleepInput> {
        var counter = 210_000_000L
        val out = ArrayList<SleepInput>()
        for ((count, hr, motion) in spans) {
            repeat(count) {
                out += SleepInput(counter, hr, motion)
                counter += 150
            }
        }
        return out
    }

    @Test fun `finds a night and excludes the restless evening around it`() {
        val night = epochs(
            Triple(40, 90, 60),    // evening: settled but moving
            Triple(110, 76, 5),    // asleep: still, rate down
            Triple(40, 100, 250),  // morning: up and about
        )
        val periods = SleepDetector.detect(night)
        assertEquals(1, periods.size)
        val hours = periods.first().seconds / 3600.0
        assertTrue("expected roughly the 4.6 h asleep stretch, got $hours h", hours in 4.0..5.0)
    }

    /** Sitting perfectly still with a normal heart rate is not sleep. */
    @Test fun `stillness alone is not enough`() {
        val sofa = epochs(
            Triple(60, 92, 5),
            Triple(60, 92, 5),
        )
        assertTrue(SleepDetector.detect(sofa).isEmpty())
    }

    /** A brief stirring should not split one night into two. */
    @Test fun `a short waking does not split the night`() {
        val night = epochs(
            Triple(40, 90, 60),
            Triple(60, 76, 5),
            Triple(4, 95, 120),    // ten minutes awake
            Triple(60, 76, 5),
            Triple(40, 100, 250),
        )
        assertEquals(1, SleepDetector.detect(night).size)
    }

    @Test fun `too little data yields nothing rather than a guess`() {
        assertTrue(SleepDetector.detect(epochs(Triple(5, 70, 5))).isEmpty())
    }
}
