package io.github.ringlink.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingTileTextTest {

    private val now = 1_793_000_000_000L
    private fun tile(
        battery: Int? = 80,
        connected: Boolean = true,
        charging: Boolean = false,
        wearKnown: Boolean = true,
        worn: Boolean = false,
        seen: Long? = now - 30_000,
        docked: Boolean = false,
        case: Int? = null,
        plugged: Boolean = false,
        caseSeen: Long? = null,
    ) = RingTile("F749", battery, connected, charging, wearKnown, worn, seen, docked, case, plugged, caseSeen)

    @Test fun `a heard ring says what it is doing`() {
        assertEquals("worn", RingTileText.status(tile(worn = true), now))
        assertEquals("off finger", RingTileText.status(tile(), now))
        assertEquals("charging", RingTileText.status(tile(charging = true, battery = 64), now))
        assertEquals("charged", RingTileText.status(tile(charging = true, battery = 100), now))
    }

    /** Freshly connected, nothing to judge by yet: no guessing "off finger". */
    @Test fun `without a reading this run, wear is not guessed`() {
        assertEquals("connected", RingTileText.status(tile(wearKnown = false), now))
    }

    /** Old news is not shown as current: a silent ring says since when. */
    @Test fun `a ring not heard for a while says so`() {
        assertEquals("not heard · 2 h ago", RingTileText.status(tile(seen = now - 2 * 3_600_000, worn = true), now))
        assertEquals("not heard · just now", RingTileText.status(tile(connected = false, seen = now - 10_000), now))
        assertEquals("not heard yet", RingTileText.status(tile(seen = null), now))
    }

    @Test fun `the case is live only while its ring is heard sitting in it`() {
        assertEquals(
            "case 64% · plugged in",
            RingTileText.case(tile(charging = true, docked = true, case = 64, plugged = true, caseSeen = now - 20_000), now),
        )
        assertEquals("case was 64% · 3 h ago", RingTileText.case(tile(case = 64, caseSeen = now - 3 * 3_600_000), now))
        assertNull(RingTileText.case(tile(), now))
    }

    /** Just out of the case and on a finger: the case is now a memory, however recent. */
    @Test fun `a ring just taken out of its case shows the case as it was`() {
        assertEquals("case was 64% · 3 min ago", RingTileText.case(tile(worn = true, case = 64, caseSeen = now - 3 * 60_000), now))
    }

    @Test fun `battery tone flags low and charging, and leaves old readings neutral`() {
        assertEquals(BatteryTone.LOW, RingTileText.tone(tile(battery = 12), 15, now))
        assertEquals(BatteryTone.CHARGING, RingTileText.tone(tile(battery = 12, charging = true), 15, now))
        assertEquals(BatteryTone.UNKNOWN, RingTileText.tone(tile(battery = 80, seen = now - 3_600_000), 15, now))
        assertEquals(BatteryTone.LOW, RingTileText.tone(tile(battery = 5, seen = now - 3_600_000), 15, now))
    }

    /** A heard ring turns silent by time alone; the widget must redraw at that moment. */
    @Test fun `the next change is when a heard ring goes quiet`() {
        val seen = now - 4 * 60_000
        assertEquals(seen + RingTileText.STALE_MS + 1_000, RingTileText.nextChange(listOf(tile(seen = seen)), now))
    }

    @Test fun `a silent ring is redrawn as its age moves on, but not every minute`() {
        val next = RingTileText.nextChange(listOf(tile(seen = now - 20 * 60_000)), now)!!
        assertEquals(now + RingTileText.AGO_STEP_MS, next)
        val hours = RingTileText.nextChange(listOf(tile(seen = now - (3 * 3_600_000 + 10 * 60_000))), now)!!
        assertEquals(now + 50 * 60_000, hours)
    }
}
