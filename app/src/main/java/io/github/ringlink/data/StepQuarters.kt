package io.github.ringlink.data

/**
 * One step reading from one ring's descriptor, at a phone wall-clock time.
 *
 * [worn] says whether the ring looked like it was on a finger at the time. It does not change the
 * count; it is what lets a quarter of zero steps be told apart from a quarter nobody was wearing
 * the ring for.
 */
data class StepSample(val atMillis: Long, val steps: Int, val worn: Boolean = true)

/**
 * Steps walked in one wall-clock quarter-hour, as far as the readings show.
 *
 * [worn] is true when at least one reading in the quarter came from a ring on a finger. A quarter
 * with readings only from a ring in its case shows 0 steps, but says nothing about the walking
 * done that quarter — the steps went to a ring that was not being heard.
 */
data class QuarterSteps(val startMillis: Long, val steps: Int, val worn: Boolean = true) {
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
 * Each reading belongs to the phone-clock quarter it was taken in, with two corrections for the
 * ring's clock sitting a little off the phone's:
 *  - a reading up to [LAG_MS] after the phone's boundary that shows no drop yet still carries the
 *    old quarter's count — the ring has not reset. Measured on these rings: still stale 11 s past
 *    the boundary, reset by 30 s. Bucketed by phone time alone it would be counted twice.
 *  - a drop up to [LEAD_MS] before the phone's boundary is the ring resetting early, and starts the
 *    next quarter.
 * A drop in the middle of a quarter is a counter cleared for some other reason; both parts count.
 *
 * An earlier version split readings on drops alone and dated each run by its midpoint. A ring
 * sitting at zero never drops, so a night of zeros and the first steps of the morning became one
 * run — dated to the middle of the night — and two quarters whose second count overtook the first
 * before a reading arrived were merged, losing the first. On 59 days of real readings that lost 3%
 * of the steps and put 7 500 of them in the wrong hour.
 *
 * Quarters with no readings at all cannot be recovered: the ring keeps no step history that its
 * sync hands over. Coverage therefore depends on the phone being connected.
 */
object StepQuarters {

    const val QUARTER_MS = 15 * 60_000L
    const val LAG_MS = 30_000L
    const val LEAD_MS = 30_000L

    /** Quarters for ONE ring's readings, in any order. */
    fun quarters(samples: List<StepSample>): List<QuarterSteps> {
        if (samples.isEmpty()) return emptyList()
        val ordered = samples.sortedBy { it.atMillis }

        val steps = HashMap<Long, Int>()
        val worn = HashMap<Long, Boolean>()
        var label = floor(ordered.first().atMillis)
        var runStart = ordered.first().atMillis
        var runMax = 0
        var runWorn = false

        fun close() {
            steps[label] = (steps[label] ?: 0) + runMax
            worn[label] = (worn[label] ?: false) || runWorn
        }
        fun startRun(next: Long, at: Long) {
            close()
            label = next
            runStart = at
            runMax = 0
            runWorn = false
        }

        var last: StepSample? = null
        for (s in ordered) {
            val previous = last
            if (previous != null) {
                val nominal = floor(s.atMillis)
                if (s.steps < previous.steps) {
                    when {
                        nominal > label -> startRun(nominal, s.atMillis)
                        label + QUARTER_MS - s.atMillis <= LEAD_MS -> startRun(label + QUARTER_MS, s.atMillis)
                        // The run opened this quarter after a gap, every reading of it inside the
                        // ring's lag, and now the ring resets: those readings were the previous
                        // quarter's count. Nothing was there to be stale against when they came.
                        runStart >= label && previous.atMillis - label <= LAG_MS -> {
                            val earlier = label - QUARTER_MS
                            // The same quarter of the same counter, so the larger count, not a sum.
                            steps[earlier] = maxOf(steps[earlier] ?: 0, runMax)
                            worn[earlier] = (worn[earlier] ?: false) || runWorn
                            runStart = s.atMillis
                            runMax = 0
                            runWorn = false
                        }
                        else -> startRun(label, s.atMillis)
                    }
                } else if (nominal > label) {
                    val stale = nominal == label + QUARTER_MS &&
                        s.atMillis - nominal <= LAG_MS &&
                        s.atMillis - previous.atMillis <= QUARTER_MS
                    if (!stale) startRun(nominal, s.atMillis)
                }
            }
            runMax = maxOf(runMax, s.steps)
            runWorn = runWorn || s.worn
            last = s
        }
        close()
        return steps.map { (start, count) -> QuarterSteps(start, count, worn[start] ?: false) }
            .sortedBy { it.startMillis }
    }

    fun floor(millis: Long) = Math.floorDiv(millis, QUARTER_MS) * QUARTER_MS

    /**
     * Whether a reading at [millis] is clear of both corrections, so the quarter it lands in is
     * its own whatever came before. Deriving from such a reading on gives the same quarters as
     * deriving from the very first reading — which is what lets a window be derived on its own.
     */
    fun settled(millis: Long): Boolean {
        val into = Math.floorMod(millis, QUARTER_MS)
        return into > LAG_MS && QUARTER_MS - into > LEAD_MS
    }

    /**
     * Quarters starting in [from, to). Give it readings from a [settled] one before [from], and
     * one quarter past [to], so the quarters at both edges are judged as they would be in full.
     */
    fun quartersIn(samples: List<StepSample>, from: Long, to: Long): List<QuarterSteps> =
        quarters(samples).filter { it.startMillis >= from && it.startMillis < to }

    fun total(quarters: List<QuarterSteps>): Int = quarters.sumOf { it.steps }

    /** Steps for any number of rings, each counted on its own; worn or not, they simply add up. */
    fun totalAcrossRings(byRing: Map<String, List<StepSample>>): Int =
        byRing.values.sumOf { total(quarters(it)) }
}
