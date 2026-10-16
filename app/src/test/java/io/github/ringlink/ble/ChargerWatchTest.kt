package io.github.ringlink.ble

import io.github.ringlink.ble.ChargerWatch.Event
import io.github.ringlink.ble.ChargerWatch.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargerWatchTest {

    private fun docked(battery: Int, case: Int = 80, plugged: Boolean = false, charging: Boolean = true) =
        Reading("spare", battery, charging, case, plugged)

    private fun outOfCase(battery: Int) = Reading("spare", battery, charging = false, casePercent = null, casePluggedIn = false)

    @Test fun `the spare ring reaching full is announced once`() {
        val w = ChargerWatch()
        assertTrue(w.update(docked(98)).isEmpty())
        assertEquals(listOf(Event.RingCharged("spare", 100)), w.update(docked(100)))
        // Still sitting full in the case: no repeat.
        repeat(5) { assertTrue(w.update(docked(100)).isEmpty()) }
    }

    @Test fun `taking it off and charging again announces again`() {
        val w = ChargerWatch()
        w.update(docked(100))
        w.update(outOfCase(97))
        assertEquals(listOf(Event.RingCharged("spare", 100)), w.update(docked(100)))
    }

    @Test fun `a full ring that is not charging is not announced`() {
        val w = ChargerWatch()
        assertTrue(w.update(docked(100, charging = false)).isEmpty())
    }

    @Test fun `a case going flat is announced once`() {
        val w = ChargerWatch()
        assertTrue(w.update(docked(60, case = 25)).isEmpty())
        assertEquals(listOf(Event.CaseLow("spare", 20)), w.update(docked(61, case = 20)))
        assertTrue(w.update(docked(62, case = 18)).isEmpty())
    }

    /** A plugged-in case is already being dealt with, however low it reads. */
    @Test fun `a low case that is plugged in is not announced`() {
        val w = ChargerWatch()
        assertTrue(w.update(docked(60, case = 10, plugged = true)).isEmpty())
    }

    @Test fun `plugging the case in re-arms the warning`() {
        val w = ChargerWatch()
        w.update(docked(60, case = 15))
        w.update(docked(61, case = 15, plugged = true))
        assertEquals(listOf(Event.CaseLow("spare", 14)), w.update(docked(62, case = 14)))
    }

    /** Lifting the ring out and putting it back into the same flat case is not news. */
    @Test fun `re-docking into the same flat case does not repeat`() {
        val w = ChargerWatch()
        w.update(docked(60, case = 15))
        w.update(outOfCase(60))
        assertTrue(w.update(docked(60, case = 15)).isEmpty())
    }

    @Test fun `two rings are watched independently`() {
        val w = ChargerWatch()
        w.update(Reading("a", 100, true, 80, false))
        assertEquals(
            listOf(Event.RingCharged("b", 100)),
            w.update(Reading("b", 100, true, 80, false)),
        )
    }
}
