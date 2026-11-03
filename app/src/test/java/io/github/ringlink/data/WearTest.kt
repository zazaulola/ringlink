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

    /** Out of the case and still at room temperature: on its way onto a finger. */
    @Test fun `a ring just off its charger counts as worn before it has warmed`() {
        assertEquals(WearEvidence.LEFT_CHARGER, ev2(26.1, leftCharger = true))
        assertEquals(WearEvidence.UNSURE, ev2(26.1, leftCharger = false))
        assertEquals(WearEvidence.WORN, ev2(32.2, leftCharger = true))
        assertEquals(WearEvidence.OFF, WearRules.evidence(true, 26.1, 0, Ambient(), leftCharger = true))
    }

    /** Left on the table instead, it does not stay "worn" for long. */
    @Test fun `off the charger and never warmed lapses sooner than a worn ring`() {
        val t = WearTracker()
        assertTrue(t.update(0, WearEvidence.LEFT_CHARGER))
        assertTrue(t.update(7 * 60_000L, WearEvidence.UNSURE))
        assertFalse(t.update(9 * 60_000L, WearEvidence.UNSURE))
    }

    /** A finger warming it takes over, with the full hold from then on. */
    @Test fun `off the charger then warmed holds like any worn ring`() {
        val t = WearTracker()
        t.update(0, WearEvidence.LEFT_CHARGER)
        t.update(60_000, WearEvidence.WORN)
        assertTrue(t.update(14 * 60_000L, WearEvidence.UNSURE))
    }

    private fun ev2(skin: Double, leftCharger: Boolean) = WearRules.evidence(false, skin, 0, Ambient(), leftCharger)

    @Test fun `a charger exit needs the ring heard on the charger moments before`() {
        val min = 60_000L
        assertTrue(WearRules.leftCharger(wasDocked = true, wasAt = 0, docked = false, now = 4 * min))
        assertFalse("hours of silence: it left at some unknown time", WearRules.leftCharger(true, 0, false, 69 * 60 * min))
        assertFalse(WearRules.leftCharger(false, 0, false, min))
        assertFalse(WearRules.leftCharger(true, 0, true, min))
    }

    /** A battery that rose a percent on a table is no charger; only the ring's own word is. */
    @Test fun `docked is what the ring states`() {
        assertTrue(WearRules.docked(state = 4, caseBattery = null))
        assertTrue(WearRules.docked(state = 1, caseBattery = 30))
        assertFalse(WearRules.docked(state = 2, caseBattery = null))
    }

    @Test fun `a ring just off its charger joins the worn one but cannot take the buzz from a cool one`() {
        val fresh = setOf("A")
        val off = setOf("B")
        assertEquals(listOf("A", "B"), WearRules.buzzTargets(listOf("A", "B"), { it in fresh }, { it in off }))
        // A is on a cool, still finger: no fresh evidence. B must not take the buzz from it.
        assertEquals(listOf("A", "B"), WearRules.buzzTargets(listOf("A", "B"), { false }, { it in off }))
        // Evidence for A, nothing for the ring on the table.
        assertEquals(listOf("A"), WearRules.buzzTargets(listOf("A", "C"), { it in fresh }, { it in off }))
    }
}
