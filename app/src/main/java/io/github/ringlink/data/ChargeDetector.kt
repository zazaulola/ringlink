package io.github.ringlink.data

import io.github.ringlink.protocol.Descriptor

/**
 * Works out whether a ring is charging, from evidence rather than from one byte.
 *
 * The descriptor's state byte was the only thing consulted before, and on this hardware it never
 * once said charging — not even while a ring sat in its case going from flat to full. So charging is
 * decided here from three signals, strongest first:
 *
 *  - **The charge went up.** A ring whose battery rises is charging; there is no other way for that
 *    to happen, and it needs no protocol knowledge to be sure of.
 *  - **The ring is in its case**, which it tells us by reporting the case's own charge.
 *  - **The state byte**, kept as a hint in case it does mean charging on some firmware.
 *
 * The verdict is sticky, because the strongest signal is an edge: a full battery stops rising while
 * the ring is still on charge. Once charging, it stays charging until the battery actually falls,
 * which only happens off the charger.
 */
class ChargeDetector {

    private data class Ring(val lastBattery: Int, val charging: Boolean)

    private val rings = HashMap<String, Ring>()

    fun update(ringId: String, descriptor: Descriptor): Boolean {
        val battery = descriptor.batteryPercent
        val previous = rings[ringId]

        val rose = previous != null && battery > previous.lastBattery
        val fell = previous != null && battery < previous.lastBattery

        val charging = when {
            rose -> true
            descriptor.inChargingCase -> true
            descriptor.stateSaysCharging -> true
            // Falling charge is the one thing that positively rules charging out.
            fell -> false
            // Unchanged and no other evidence: keep whatever we concluded before.
            else -> previous?.charging ?: false
        }

        rings[ringId] = Ring(lastBattery = battery, charging = charging)
        return charging
    }

    /** The latest verdict, without a new reading. */
    fun isCharging(ringId: String): Boolean = rings[ringId]?.charging ?: false

    /** Forget a ring, so a re-added one is not judged on stale history. */
    fun forget(ringId: String) {
        rings.remove(ringId)
    }
}
