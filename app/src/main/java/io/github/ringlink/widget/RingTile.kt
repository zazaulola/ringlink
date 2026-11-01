package io.github.ringlink.widget

/** What the widget knows about one ring, gathered from the service and the database. */
data class RingTile(
    val name: String,
    val battery: Int?,
    /** Whether the link is up. Without the service running there is no link at all. */
    val connected: Boolean,
    val charging: Boolean,
    /** Whether this run of the service has had a reading to judge wear by; until then, say nothing. */
    val wearKnown: Boolean,
    val worn: Boolean,
    /** When the ring's latest reading was taken. */
    val lastSeenAt: Long?,
    /** Whether the ring sits in its case now, by its latest reading. */
    val docked: Boolean,
    /** The case's charge: live while the ring sits in it, else as last seen. */
    val casePercent: Int?,
    val casePlugged: Boolean,
    /** When the case was last seen, through a ring docked in it. */
    val caseSeenAt: Long?,
)

enum class BatteryTone { NORMAL, LOW, CHARGING, UNKNOWN }

/**
 * The widget's words for a ring, kept apart from the drawing so they can be tested.
 *
 * Everything the widget shows can be old: the ring talks only while connected, and the case only
 * through a ring docked in it. So nothing stale is shown as current — a ring not heard for a while
 * says so, with when it was, and its last charge stands as the last known one.
 */
object RingTileText {

    /** A ring this long without a reading is not described as it was. */
    const val STALE_MS = 10 * 60_000L

    /** The soonest a silent ring's "… ago" is redrawn: the launcher is not woken every minute. */
    const val AGO_STEP_MS = 5 * 60_000L

    fun heard(t: RingTile, now: Long): Boolean =
        t.connected && t.lastSeenAt != null && now - t.lastSeenAt <= STALE_MS

    fun battery(t: RingTile): String = t.battery?.let { "$it%" } ?: "—"

    fun status(t: RingTile, now: Long): String = when {
        !heard(t, now) -> t.lastSeenAt?.let { "not heard · ${ago(it, now)}" } ?: "not heard yet"
        t.charging && (t.battery ?: 0) >= 100 -> "charged"
        t.charging -> "charging"
        // Connected, but nothing yet to judge by: say so rather than guess "off finger".
        !t.wearKnown -> "connected"
        t.worn -> "worn"
        else -> "off finger"
    }

    /** The case is live only while its ring is heard sitting in it; otherwise it is a memory. */
    fun case(t: RingTile, now: Long): String? {
        val percent = t.casePercent ?: return null
        val seen = t.caseSeenAt ?: return null
        return if (heard(t, now) && t.docked) {
            "case $percent%" + if (t.casePlugged) " · plugged in" else ""
        } else {
            "case was $percent% · ${ago(seen, now)}"
        }
    }

    fun tone(t: RingTile, lowPercent: Int, now: Long): BatteryTone {
        val battery = t.battery ?: return BatteryTone.UNKNOWN
        return when {
            !heard(t, now) -> if (battery <= lowPercent) BatteryTone.LOW else BatteryTone.UNKNOWN
            t.charging -> BatteryTone.CHARGING
            battery <= lowPercent -> BatteryTone.LOW
            else -> BatteryTone.NORMAL
        }
    }

    /**
     * When the words drawn now next change with nothing else happening: a heard ring going quiet,
     * or a silent one's "… ago" moving on. Null when nothing is due to change.
     */
    fun nextChange(tiles: List<RingTile>, now: Long): Long? = tiles.flatMap { t ->
        buildList {
            val seen = t.lastSeenAt
            if (seen != null) {
                if (heard(t, now)) add(seen + STALE_MS + 1_000) else add(maxOf(agoChanges(seen, now), now + AGO_STEP_MS))
            }
            val caseSeen = t.caseSeenAt
            if (caseSeen != null && t.casePercent != null && !(heard(t, now) && t.docked)) {
                add(maxOf(agoChanges(caseSeen, now), now + AGO_STEP_MS))
            }
        }
    }.minOrNull()

    fun ago(at: Long, now: Long): String {
        val minutes = (now - at) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    /** When [ago] for [at] next reads differently. */
    private fun agoChanges(at: Long, now: Long): Long {
        val minutes = (now - at) / 60_000
        val unit = when {
            minutes < 60 -> 60_000L
            minutes < 48 * 60 -> 3_600_000L
            else -> 86_400_000L
        }
        return at + ((now - at) / unit + 1) * unit
    }
}
