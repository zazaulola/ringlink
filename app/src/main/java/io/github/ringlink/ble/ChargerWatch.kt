package io.github.ringlink.ble

/**
 * What the charging case, seen through a ring sitting in it, needs to tell the wearer.
 *
 * The case has no radio of its own. Everything known about it arrives through a docked ring: the
 * descriptor's byte 17 carries the case's charge and whether the case itself is plugged in, and
 * reads `0xff` whenever the ring is out of the case. So this is fed one ring reading at a time.
 *
 * It exists for the two-ring routine — one ring on the finger, the other in its case — where the
 * useful moments are "the spare is ready" and "the case is going flat and will not charge it next
 * time". Each is announced once per occurrence: the latch for "charged" lifts when the ring leaves
 * the charger, and the one for "case low" when the case is plugged in or climbs back above the
 * threshold.
 */
class ChargerWatch(
    private val caseLowPercent: Int = DEFAULT_CASE_LOW_PERCENT,
) {
    /** Something worth telling the wearer about. */
    sealed interface Event {
        val ringId: String

        /** A ring sitting on its charger has reached full. */
        data class RingCharged(override val ringId: String, val battery: Int) : Event

        /** The case a ring is docked in is running down and is not plugged in. */
        data class CaseLow(override val ringId: String, val casePercent: Int) : Event
    }

    /** One reading from one ring, reduced to what matters for the charger. */
    data class Reading(
        val ringId: String,
        val battery: Int,
        val charging: Boolean,
        /** Case charge while docked, null when the ring is out of the case. */
        val casePercent: Int?,
        val casePluggedIn: Boolean,
    )

    private val announcedCharged = HashSet<String>()
    private val announcedCaseLow = HashSet<String>()

    fun update(r: Reading): List<Event> {
        val events = ArrayList<Event>(2)

        if (!r.charging) {
            announcedCharged.remove(r.ringId)
        } else if (r.battery >= FULL_PERCENT && announcedCharged.add(r.ringId)) {
            events += Event.RingCharged(r.ringId, r.battery)
        }

        val case = r.casePercent
        when {
            // Out of the case: nothing can be said about it, so keep whatever latch there was —
            // re-docking into the same flat case must not repeat the warning.
            case == null -> Unit
            r.casePluggedIn || case > caseLowPercent -> announcedCaseLow.remove(r.ringId)
            announcedCaseLow.add(r.ringId) -> events += Event.CaseLow(r.ringId, case)
        }

        return events
    }

    companion object {
        /**
         * Where the vendor's own case light turns orange: Gen 3's case shows solid orange "below
         * 20%", so warning at the same point keeps the app and the hardware telling the same story.
         */
        const val DEFAULT_CASE_LOW_PERCENT = 20
        const val FULL_PERCENT = 100
    }
}
