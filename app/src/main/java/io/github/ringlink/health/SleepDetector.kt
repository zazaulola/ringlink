package io.github.ringlink.health

/** One epoch as the detector sees it. */
data class SleepInput(val counter: Long, val heartRate: Int?, val motion: Int)

/** A detected stretch of sleep, in ring-counter space. */
data class SleepPeriod(val startCounter: Long, val endCounter: Long) {
    val seconds: Long get() = endCounter - startCounter
}

/**
 * Estimates when the wearer was asleep.
 *
 * The ring transmits no hypnogram and does not mark sleep at all — the vendor app infers it from
 * the same raw signals available here. This is therefore an estimate, and it is labelled as one
 * wherever it surfaces.
 *
 * Two signals carry it, and both were checked against real nights before this was written:
 *
 *  - **Motion at its floor.** Each epoch reports five 30-second motion counts whose resting value is
 *    1, so a `motionSum` of 5 means the hand did not move at all for 2.5 minutes. Measured over a
 *    real night this sits pinned at exactly 5 from 00:15 to 05:00 and jumps to 276 on waking.
 *  - **A heart-rate dip.** Lying still awake is not sleep: across the same night the rate fell from
 *    ~90 while settling to ~75 once asleep, then spiked to 106 on getting up. Requiring the dip is
 *    what stops a quiet evening on the sofa from being counted — a motion-only rule reported that
 *    night as nine hours instead of five.
 */
object SleepDetector {

    /** Motion at or below this means the hand was still; 5 is the hardware floor. */
    private const val STILL = 6

    /** How far below the wearer's own resting rate counts as asleep. */
    private const val DIP_BPM = 5

    /** Baseline window: half a day either side, so it tracks the person rather than the population. */
    private const val BASELINE_WINDOW_SECONDS = 43_200L

    /** Baselines are computed on this grid, not per reading. */
    private const val BASELINE_BUCKET_SECONDS = 3_600L

    private const val EPOCH_SECONDS = 150L

    /** Brief stirrings do not end a night. */
    private const val MERGE_GAP_SECONDS = 30 * 60L

    /**
     * Shorter than this is not reported.
     *
     * Tuned against four days of real data: at 45 minutes the detector also reported hour-long
     * daytime stretches of sitting perfectly still, and raising the floor removed every one of them
     * while keeping each genuine night. Widening the merge gap instead was tried and was worse — it
     * glued evening, night and morning into single 9- to 14-hour "sessions".
     */
    private const val MIN_SESSION_SECONDS = 90 * 60L

    fun detect(epochs: List<SleepInput>): List<SleepPeriod> {
        val usable = epochs.filter { it.heartRate != null }.sortedBy { it.counter }
        if (usable.size < MIN_EPOCHS) return emptyList()

        // Baselines are computed once per hour rather than once per epoch. Recomputing per epoch
        // meant rescanning — and re-sorting — every reading in a 24-hour window for every reading in
        // the series: quadratic, and at a 30-day window some 300 million operations with a sort
        // inside each, which freezes whatever thread it lands on. The baseline is a smooth
        // 24-hour statistic, so an hourly grid loses nothing real.
        val baselines = hourlyBaselines(usable)
        val asleep = usable.map { epoch ->
            val baseline = baselines[epoch.counter / BASELINE_BUCKET_SECONDS]
            epoch.counter to (
                baseline != null &&
                    epoch.motion <= STILL &&
                    (epoch.heartRate ?: Int.MAX_VALUE) <= baseline - DIP_BPM
                )
        }

        val runs = ArrayList<SleepPeriod>()
        var start: Long? = null
        var last = 0L
        for ((counter, sleeping) in asleep) {
            if (sleeping) {
                if (start == null) start = counter
                last = counter
            } else if (start != null && counter - last > MERGE_GAP_SECONDS) {
                runs += SleepPeriod(start, last + EPOCH_SECONDS)
                start = null
            }
        }
        start?.let { runs += SleepPeriod(it, last + EPOCH_SECONDS) }

        return runs.filter { it.seconds >= MIN_SESSION_SECONDS }
    }

    /**
     * The awake baseline for every hour the data covers.
     *
     * [usable] is sorted by counter, so each hour's window is a contiguous slice found by binary
     * search rather than by filtering the whole series.
     */
    private fun hourlyBaselines(usable: List<SleepInput>): Map<Long, Int?> {
        val counters = usable.map { it.counter }
        val firstBucket = usable.first().counter / BASELINE_BUCKET_SECONDS
        val lastBucket = usable.last().counter / BASELINE_BUCKET_SECONDS
        val out = HashMap<Long, Int?>()
        for (bucket in firstBucket..lastBucket) {
            val centre = bucket * BASELINE_BUCKET_SECONDS
            val from = counters.lowerBoundOf(centre - BASELINE_WINDOW_SECONDS)
            val to = counters.lowerBoundOf(centre + BASELINE_WINDOW_SECONDS + 1)
            if (to - from < MIN_BASELINE_SAMPLES) { out[bucket] = null; continue }
            val nearby = ArrayList<Int>(to - from)
            for (i in from until to) usable[i].heartRate?.let { nearby += it }
            if (nearby.size < MIN_BASELINE_SAMPLES) { out[bucket] = null; continue }
            nearby.sort()
            out[bucket] = nearby[(nearby.size * AWAKE_PERCENTILE / 100).coerceAtMost(nearby.size - 1)]
        }
        return out
    }

    /** Index of the first counter >= [target]. */
    private fun List<Long>.lowerBoundOf(target: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (this[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * The wearer's awake heart rate around a moment.
     *
     * Deliberately personal and local: a fixed threshold would call one person asleep all day and
     * another never, and a person's own rate drifts with illness, caffeine and fitness.
     *
     * Taken as an upper percentile rather than a median, and over all epochs rather than still ones.
     * That is not cosmetic — computing it from still epochs makes the baseline follow the sleep it
     * is supposed to detect, because sleep is exactly when the wearer is still. On a night that is
     * mostly sleep, such a baseline sinks to the sleeping rate and nothing is ever "below" it.
     */
    private fun awakeBaseline(all: List<SleepInput>, counter: Long): Int? {
        val nearby = all.asSequence()
            .filter { kotlin.math.abs(it.counter - counter) <= BASELINE_WINDOW_SECONDS }
            .mapNotNull { it.heartRate }
            .sorted()
            .toList()
        if (nearby.size < MIN_BASELINE_SAMPLES) return null
        return nearby[(nearby.size * AWAKE_PERCENTILE / 100).coerceAtMost(nearby.size - 1)]
    }

    /** Where the awake rate sits in the window's distribution. */
    private const val AWAKE_PERCENTILE = 70

    private const val MIN_EPOCHS = 20
    private const val MIN_BASELINE_SAMPLES = 20
}
