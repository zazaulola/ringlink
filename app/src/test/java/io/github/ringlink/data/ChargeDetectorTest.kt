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

    /** The reported problem: a ring on charge was never recognised as charging. */
    @Test fun `a rising battery means charging, whatever the state byte says`() {
        val d = ChargeDetector()
        assertFalse(d.update("ring", descriptor(40)))
        assertTrue("battery went up, so it is on charge", d.update("ring", descriptor(43)))
    }

    /**
     * A full battery stops rising while the ring is still in its case, so the verdict has to hold
     * rather than flipping back the moment the charge levels off.
     */
    @Test fun `charging holds once the battery stops rising`() {
        val d = ChargeDetector()
        d.update("ring", descriptor(90))
        assertTrue(d.update("ring", descriptor(95)))
        assertTrue("still on charge at a steady 100", d.update("ring", descriptor(100)))
        assertTrue(d.update("ring", descriptor(100)))
        assertTrue(d.update("ring", descriptor(100)))
    }

    /** Taken off the charger, the battery falls — the one thing that rules charging out. */
    @Test fun `a falling battery ends charging`() {
        val d = ChargeDetector()
        d.update("ring", descriptor(90))
        d.update("ring", descriptor(100))
        assertTrue(d.update("ring", descriptor(100)))
        assertFalse("charge is being used, so it is off the charger", d.update("ring", descriptor(99)))
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
