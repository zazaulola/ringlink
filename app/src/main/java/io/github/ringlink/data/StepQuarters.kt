package io.github.ringlink.data

/** One step reading from one ring's descriptor, at a phone wall-clock time. */
data class StepSample(val atMillis: Long, val steps: Int)

/** Steps walked in one wall-clock quarter-hour, as far as the readings show. */
data class QuarterSteps(val startMillis: Long, val steps: Int) {
    val endMillis: Long get() = startMillis + StepQuarters.QUARTER_MS
}

/**
 * Turns the ring's step counter into steps per quarter-hour.
 *
 * The counter in the descriptor is not a day total. It counts steps since the last wall-clock
 * :00, :15, :30 or :45 and is cleared at each one — established across 10 327 descriptor frames
 * from two other rings, and matching these rings, whose counter dropped to zero dozens of times a
 * day and never went much above a thousand. So a day's steps are the sum, over its quarters, of the
 * highest count seen in each.
 *
 * Quarters are told apart by the counter dropping, not by the phone's clock. The ring resets on
 * its own clock, which can sit seconds away from the phone's, so a reading taken just after the
 * phone's boundary can still carry the previous quarter's count; bucketing by phone time would
 * count it twice. A drop is unambiguous. A gap longer than a quarter also starts a new one, since
 * at least one reset must have passed unseen.
 *
 * Quarters with no readings at all cannot be recovered: the ring keeps no step history that its
 * sync hands over. Coverage therefore depends on the phone being connected.
 */
object StepQuarters {

    const val QUARTER_MS = 15 * 60_000L

    /** Quarters for ONE ring's readings, in any order. */
    fun quarters(samples: List<StepSample>): List<QuarterSteps> {
        if (samples.isEmpty()) return emptyList()
        val ordered = samples.sortedBy { it.atMillis }

        val segments = ArrayList<MutableList<StepSample>>()
        var current = mutableListOf(ordered.first())
        for (s in ordered.drop(1)) {
            val prev = current.last()
            val reset = s.steps < prev.steps || s.atMillis - prev.atMillis > QUARTER_MS
            if (reset) {
                segments += current
                current = mutableListOf()
            }
            current += s
        }
        segments += current

        // Each segment is labelled by the quarter holding the midpoint of its first and last
        // reading, which a few seconds of clock skew at either end cannot move far. And since every
        // segment boundary is a reset — a new quarter — labels must strictly increase; a segment
        // that would land on its predecessor's quarter is moved to the next one. That resolves the
        // case where a stale reading just past the boundary drags a short segment's midpoint over.
        val byQuarter = LinkedHashMap<Long, Int>()
        var previous = Long.MIN_VALUE
        for (seg in segments) {
            val midpoint = (seg.first().atMillis + seg.last().atMillis) / 2
            var start = Math.floorDiv(midpoint, QUARTER_MS) * QUARTER_MS
            if (start <= previous) start = previous + QUARTER_MS
            byQuarter[start] = seg.maxOf { it.steps }
            previous = start
        }
        return byQuarter.map { (start, steps) -> QuarterSteps(start, steps) }.sortedBy { it.startMillis }
    }

    fun total(quarters: List<QuarterSteps>): Int = quarters.sumOf { it.steps }

    /** Steps for any number of rings, each counted on its own; worn or not, they simply add up. */
    fun totalAcrossRings(byRing: Map<String, List<StepSample>>): Int =
        byRing.values.sumOf { total(quarters(it)) }
}
