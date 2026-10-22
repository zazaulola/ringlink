package io.github.ringlink.data

/** A stretch of time during which one ring was on a finger and so is the source of the data. */
data class WornSpan(val ringId: String, val startCounter: Long, val endCounter: Long) {
    val seconds: Long get() = endCounter - startCounter
}

/**
 * Works out which ring the data for each stretch of time came from.
 *
 * Both rings record epochs whether or not they are worn, so "this ring has a record here" says
 * nothing. What separates them is the pulse: measured across 6609 slots where both rings wrote an
 * epoch, 99.6% had a heart rate from exactly one ring — the one on the finger — and an empty epoch
 * from the other, sitting in its case or on a table. A ring is therefore taken as the source for
 * the stretches where it measured a pulse, and nowhere else.
 */
object Coverage {

    /** Shorter gaps than this inside one ring's record are one stretch of wear, not two. */
    const val DEFAULT_GAP_SECONDS = 30 * 60L

    private const val EPOCH_SECONDS = 150L

    /**
     * Worn stretches for every ring, oldest first.
     *
     * [rows] may mix rings in any order. Only epochs with a heart rate count, so the empty epochs a
     * ring writes while charging never show up as coverage.
     */
    fun spans(rows: List<EpochEntity>, gapSeconds: Long = DEFAULT_GAP_SECONDS): List<WornSpan> {
        val out = ArrayList<WornSpan>()
        rows.asSequence()
            .filter { it.heartRate != null }
            .groupBy { it.ringId }
            .forEach { (ring, epochs) ->
                val counters = epochs.map { it.counter }.sorted()
                var start = counters.first()
                var last = start
                for (c in counters.drop(1)) {
                    if (c - last > gapSeconds) {
                        out += WornSpan(ring, start, last + EPOCH_SECONDS)
                        start = c
                    }
                    last = c
                }
                out += WornSpan(ring, start, last + EPOCH_SECONDS)
            }
        return out.sortedBy { it.startCounter }
    }

    /** Total worn time per ring, in seconds. */
    fun totals(spans: List<WornSpan>): Map<String, Long> =
        spans.groupBy { it.ringId }.mapValues { (_, s) -> s.sumOf { it.seconds } }
}
