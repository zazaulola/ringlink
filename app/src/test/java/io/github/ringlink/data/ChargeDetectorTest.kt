package io.github.ringlink.data

import io.github.ringlink.protocol.Descriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun descriptor(
    battery: Int,
    state: Int = 0x02,
    caseByte: Int = 0xff,
    skinTemp: Double = 34.0,
) = Descriptor(
    batteryPercent = battery,
    state = state,
    steps = 0,
    skinTempA = skinTemp,
    skinTempB = skinTemp,
    batteryMillivolts = 3900,
    caseByte = caseByte,
)

class ChargeDetectorTest {

    /** The state byte is the answer, confirmed against 11152 readings from two rings. */
    @Test fun `state 0x04 means charging`() {
        val d = ChargeDetector()
        assertTrue(d.update("ring", descriptor(50, state = 0x04)))
    }

    @Test fun `the other observed states mean not charging`() {
        val d = ChargeDetector()
        for (state in listOf(0x00, 0x02, 0x03)) {
            assertFalse("state $state should not read as charging", d.update("r$state", descriptor(50, state = state)))
        }
    }

    /** Belt and braces for firmware that might number its states differently. */
    @Test fun `a rising battery means charging whatever the state byte says`() {
        val d = ChargeDetector()
        assertFalse(d.update("ring", descriptor(40)))
        assertTrue("battery went up, so it is on charge", d.update("ring", descriptor(43)))
    }

    /**
     * A full battery stops rising, but the state byte keeps saying charging — measured as thousands
     * of consecutive readings while a ring sat in its case at 100%.
     */
    @Test fun `a full ring in the case still reads as charging`() {
        val d = ChargeDetector()
        repeat(5) { assertTrue(d.update("ring", descriptor(100, state = 0x04))) }
    }

    /** Off the charger the state byte changes, and that is what ends it. */
    @Test fun `charging ends when the state byte does`() {
        val d = ChargeDetector()
        assertTrue(d.update("ring", descriptor(100, state = 0x04)))
        assertFalse(d.update("ring", descriptor(99, state = 0x02)))
    }

    /** Sitting in the case is stated directly: the ring reports the case's own charge from inside. */
    @Test fun `being in the case counts immediately`() {
        val d = ChargeDetector()
        assertTrue(d.update("ring", descriptor(50, caseByte = 0x80 or 60)))
    }

    @Test fun `ordinary discharge never looks like charging`() {
        val d = ChargeDetector()
        var charging = false
        for (battery in 90 downTo 60 step 3) charging = d.update("ring", descriptor(battery))
        assertFalse(charging)
    }

    /** Two rings are judged separately — one on charge must not make the other look charged. */
    @Test fun `rings are tracked independently`() {
        val d = ChargeDetector()
        d.update("worn", descriptor(80))
        d.update("spare", descriptor(20))
        assertTrue(d.update("spare", descriptor(25)))
        assertFalse(d.update("worn", descriptor(79)))
    }
}
