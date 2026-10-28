package io.github.ringlink.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearTest {

    private fun ev(skin: Double, steps: Int = 0, charging: Boolean = false, ambient: Ambient = Ambient()) =
        WearRules.evidence(charging, skin, steps, ambient)

    @Test fun `a warm finger is worn and a docked ring is not`() {
        assertEquals(WearEvidence.WORN, ev(33.5))
        assertEquals(WearEvidence.OFF, ev(33.5, charging = true))
    }

    /** The case that started this: walking at 5 am at 15 °C skin. */
    @Test fun `a cold finger outdoors is worn`() {
        assertEquals(WearEvidence.WORN, ev(15.3))
        assertEquals(WearEvidence.WORN, ev(26.0, steps = 300))
    }

    @Test fun `room temperature without steps cannot tell`() {
        assertEquals(WearEvidence.UNSURE, ev(26.3))
    }

    @Test fun `a ring as cold as the air outside was left out, not worn`() {
        assertEquals(WearEvidence.OFF, ev(-2.0, ambient = Ambient(outdoorC = -4.0)))
        assertEquals(WearEvidence.WORN, ev(12.0, ambient = Ambient(outdoorC = -4.0)))
    }

    /** In a hot room a ring on the table reads 30 °C; it takes a warmer reading to mean a finger. */
    @Test fun `a hot room raises the warm threshold`() {
        assertEquals(WearEvidence.UNSURE, ev(30.5, ambient = Ambient(roomC = 29.0)))
        assertEquals(WearEvidence.WORN, ev(32.5, ambient = Ambient(roomC = 29.0)))
    }

    /** In a cool room a ring on the table reads 21 °C, which must not count as a cold finger. */
    @Test fun `a cool room lowers the cold threshold`() {
        assertEquals(WearEvidence.UNSURE, ev(21.0, ambient = Ambient(roomC = 21.0)))
        assertEquals(WearEvidence.WORN, ev(18.5, ambient = Ambient(roomC = 21.0)))
    }

    @Test fun `a room like the one measured changes nothing`() {
        val room = Ambient(roomC = 25.6)
        assertEquals(30.0, WearRules.warmThreshold(room), 0.0)
        assertEquals(23.6, WearRules.coldThreshold(room), 1e-9)
    }

    @Test fun `worn holds through unsure readings for a while, then lapses`() {
        val t = WearTracker()
        assertTrue(t.update(0, WearEvidence.WORN))
        assertTrue(t.update(10 * 60_000L, WearEvidence.UNSURE))
        assertFalse(t.update(16 * 60_000L, WearEvidence.UNSURE))
    }

    @Test fun `unsure never makes a ring worn, and the charger ends it at once`() {
        val t = WearTracker()
        assertFalse(t.update(0, WearEvidence.UNSURE))
        t.update(60_000, WearEvidence.WORN)
        assertFalse(t.update(120_000, WearEvidence.OFF))
        assertFalse(t.update(180_000, WearEvidence.UNSURE))
    }

    /** A hot day outside, a cool office inside: the docked ring knows better than the forecast. */
    @Test fun `a measured room outranks the weather`() {
        assertEquals(WearEvidence.WORN, ev(32.0, ambient = Ambient(roomC = 23.0, outdoorC = 33.0)))
    }

    /** No heat wave, and no forged report, lifts the threshold above an ordinary warm finger. */
    @Test fun `the warm threshold stops at a finger's temperature`() {
        assertEquals(34.0, WearRules.warmThreshold(Ambient(outdoorC = 60.0)), 0.0)
        assertEquals(WearEvidence.WORN, ev(34.5, ambient = Ambient(outdoorC = 60.0)))
    }

    /** On a warm day the outdoors explains no cold reading: a cold finger in air conditioning. */
    @Test fun `a warm day does not turn cold readings into rings left outside`() {
        assertEquals(WearEvidence.WORN, ev(23.0, ambient = Ambient(outdoorC = 28.0)))
        assertEquals(WearEvidence.WORN, ev(20.0, ambient = Ambient(outdoorC = 35.0)))
    }

    @Test fun `only a reading near the outdoor air counts as left outside`() {
        assertEquals(WearEvidence.OFF, ev(-6.0, ambient = Ambient(outdoorC = -4.0)))
        assertEquals(WearEvidence.WORN, ev(8.0, ambient = Ambient(outdoorC = -4.0)))
    }
}
