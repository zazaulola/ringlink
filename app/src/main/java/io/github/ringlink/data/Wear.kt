package io.github.ringlink.data

/** What one reading says about whether its ring is on a finger. */
enum class WearEvidence { WORN, OFF, UNSURE }

/** Temperatures around the ring, when known: the room's, from a docked ring, and outdoors. */
data class Ambient(val roomC: Double? = null, val outdoorC: Double? = null)

/**
 * Whether a ring is on a finger, from what its readings say.
 *
 * The ring does not report it, and skin temperature alone misleads in both directions. Checked
 * against the one thing that does know — the ring records a pulse only on a finger — a 30 °C
 * threshold missed 16% of worn readings: fingers are cold outdoors, and cool indoors too. Below
 * 24 °C every reading came from a finger, since a ring lying indoors sits at room temperature;
 * between 24 and 30 °C a cool finger and a ring on the table read the same. What settles that band
 * is movement — a ring that counted steps this quarter is being carried — and failing that, a
 * ring that was on a finger a few minutes ago probably still is.
 *
 * On 12 718 readings with a pulse to judge by: the 30 °C threshold recognised 84.5% of worn
 * readings, these rules 99.1%, at the cost of calling an idle ring worn 10.4% of the time
 * instead of 8.6% — mostly in the minutes after it was taken off.
 */
object WearRules {
    /** A finger is at least this warm when the hand is warm. */
    const val WARM_C = 30.0
    /** Colder than any room a ring was found lying in. */
    const val COLD_C = 24.0
    /** A worn ring reads at least this much above the air around it. */
    const val ABOVE_AMBIENT_C = 3.0
    /** A ring lying in a room reads about the room; this much below it is a cold finger. */
    const val BELOW_ROOM_C = 2.0
    /** A ring left out in the cold reads within this of the outdoor air. */
    const val NEAR_OUTDOOR_C = 3.0
    /** How long a worn ring stays worn on readings that cannot tell. */
    const val HOLD_MS = 15 * 60_000L

    /** However hot the air, a finger at this temperature is a finger: the warm threshold stops here. */
    const val WARM_CAP_C = 34.0

    /**
     * The surroundings only ever raise the warm threshold and lower the cold one: in a hot room a
     * ring lying about reads 30 °C, in a cool one 22 °C. Where they say nothing new — this user's
     * 25.6 °C room — the thresholds stay as measured.
     *
     * The room, when a docked ring measures it, is the air the rings are actually in; the outdoor
     * temperature stands in only without it. Either way the threshold stops at [WARM_CAP_C], so no
     * heat wave — nor a forged weather report — makes an ordinary warm finger look bare.
     */
    fun warmThreshold(a: Ambient): Double {
        val air = a.roomC ?: a.outdoorC ?: return WARM_C
        return maxOf(WARM_C, minOf(WARM_CAP_C, air + ABOVE_AMBIENT_C))
    }

    fun coldThreshold(a: Ambient): Double = a.roomC?.let { minOf(COLD_C, it - BELOW_ROOM_C) } ?: COLD_C

    fun evidence(onCharger: Boolean, skinC: Double, steps: Int, ambient: Ambient = Ambient()): WearEvidence = when {
        onCharger -> WearEvidence.OFF
        steps > 0 -> WearEvidence.WORN
        skinC >= warmThreshold(ambient) -> WearEvidence.WORN
        skinC < coldThreshold(ambient) -> if (leftOutside(skinC, ambient)) WearEvidence.OFF else WearEvidence.WORN
        else -> WearEvidence.UNSURE
    }

    /**
     * As cold as the air outside, on a day colder outside than in: left in a car or on a balcony,
     * not on a cold hand. A reading far from the outdoor temperature is not one, and on a warm day
     * the outdoors explains no cold reading at all.
     */
    private fun leftOutside(skinC: Double, a: Ambient): Boolean {
        val outdoor = a.outdoorC ?: return false
        return outdoor < coldThreshold(a) && kotlin.math.abs(skinC - outdoor) <= NEAR_OUTDOOR_C
    }
}

/** One ring's wear state over time: evidence decides, and an unsure reading keeps a recent "worn". */
class WearTracker(private val holdMs: Long = WearRules.HOLD_MS) {
    private var worn = false
    private var lastWornAt = Long.MIN_VALUE

    val isWorn: Boolean get() = worn

    fun update(atMillis: Long, evidence: WearEvidence): Boolean {
        when (evidence) {
            WearEvidence.WORN -> {
                worn = true
                lastWornAt = atMillis
            }
            WearEvidence.OFF -> worn = false
            WearEvidence.UNSURE -> if (worn && atMillis - lastWornAt > holdMs) worn = false
        }
        return worn
    }
}
