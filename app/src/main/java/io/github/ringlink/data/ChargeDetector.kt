package io.github.ringlink.data

import io.github.ringlink.protocol.Descriptor

/**
 * Decides whether a ring is charging.
 *
 * The descriptor's state byte is the answer, and it has been checked against what the battery
 * actually did. Over 11152 readings from two rings:
 *
 * | state | readings | battery rose | battery fell |
 * |-------|----------|--------------|--------------|
 * | 0x04  | 5720     | 94           | 3            |
 * | 0,2,3 | 5432     | 5            | 151          |
 *
 * So `0x04` means charging and the others do not, consistently on both a Gen 2 and a Gen 3. It also
 * holds steady throughout — thousands of consecutive readings while a ring sat in its case — so
 * there is no need to latch it or to infer anything from the battery in the ordinary case.
 *
 * A rising battery is still accepted as charging on its own. That costs nothing, and it is the one
 * statement that cannot be wrong whatever a future firmware does with the state byte.
 */
class ChargeDetector {

    private val lastBattery = HashMap<String, Int>()
    private val charging = HashMap<String, Boolean>()

    fun update(ringId: String, descriptor: Descriptor): Boolean {
        val battery = descriptor.batteryPercent
        val previous = lastBattery.put(ringId, battery)
        val rose = previous != null && battery > previous

        val verdict = descriptor.stateSaysCharging || descriptor.inChargingCase || rose
        charging[ringId] = verdict
        return verdict
    }

    /** The latest verdict, without a new reading. */
    fun isCharging(ringId: String): Boolean = charging[ringId] ?: false

    /** Forget a ring, so a re-added one is not judged on stale history. */
    fun forget(ringId: String) {
        lastBattery.remove(ringId)
        charging.remove(ringId)
    }
}
