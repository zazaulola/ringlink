package io.github.ringlink.protocol

/**
 * Converts the ring's 32-bit counters to wall-clock time.
 *
 * The absolute anchor is genuinely uncertain: the two independent reverse-engineering efforts use
 * epochs 4 hours apart (1577793600 vs 1577808000), which smells like one of them baked in a
 * timezone. The counter arithmetic itself is not in doubt. So rather than trusting either constant,
 * calibrate against the ring: the newest record in a drain is approximately "ring now", so the
 * residual between it and the phone's clock, rounded to a whole hour, recovers the ring's
 * convention.
 */
class RingClock(
    private var epochSeconds: Long = DEFAULT_EPOCH,
    private var calibrated: Boolean = false,
) {

    fun epoch(): Long = epochSeconds

    fun isCalibrated(): Boolean = calibrated

    fun toUnixSeconds(counter: Long): Long = counter + epochSeconds

    fun toEpochMillis(counter: Long): Long = (counter + epochSeconds) * 1000L

    /** Cursor meaning "drain everything up to about now". */
    fun cursorForNow(nowUnixSeconds: Long): Long = nowUnixSeconds - epochSeconds

    /**
     * Re-anchor from the newest counter actually seen. Returns true if the epoch moved.
     * Only whole-hour corrections are accepted, and only within a sane window, so ordinary
     * clock skew or a stale record can't drag the anchor around.
     */
    fun calibrate(newestCounter: Long, nowUnixSeconds: Long): Boolean {
        if (newestCounter <= 0) return false
        // Bootstrap only. A shifted anchor makes a wrongly-dated record look like "now", so a
        // recalibration can always justify itself — which means repeated calibration is a way to
        // drift, not to stay accurate. The ring's convention does not change, so settle it once.
        if (calibrated) return false
        calibrated = true
        val residual = nowUnixSeconds - newestCounter
        val hours = Math.round((residual - epochSeconds) / 3600.0)
        if (hours == 0L) return false
        val candidate = epochSeconds + hours * 3600L
        if (candidate !in MIN_EPOCH..MAX_EPOCH) return false
        // A record cannot come from the future. Rejecting that outright stops a single malformed
        // or mis-parsed counter from dragging the whole archive's timestamps.
        if (newestCounter + candidate > nowUnixSeconds + FUTURE_TOLERANCE) return false
        epochSeconds = candidate
        return true
    }

    companion object {
        /**
         * 2019-12-31 12:00:00 UTC — the anchor for RECORD COUNTERS.
         *
         * The long-standing "4-hour ambiguity" between reverse-engineering projects turns out not
         * to be a disagreement about one number: the ring uses two time spaces 14400 s apart.
         * Measured on a Gen 3 (FR05):
         *
         *  - record counters in 0x4c pages anchor at 1577793600 — a record decoded with it lands
         *    3.6 minutes before the sync that fetched it, which is exactly right;
         *  - the cursor the vendor app puts in its 02 00 sync-open anchors at 1577808000.
         *
         * Health data is timestamped from record counters, so this is the anchor that matters.
         */
        const val DEFAULT_EPOCH = 1_577_793_600L

        /** The vendor app's sync-open cursor space, 4 h ahead of record counters. */
        const val CURSOR_EPOCH = 1_577_808_000L

        /** Accept only anchors within a day of the documented candidates. */
        private const val MIN_EPOCH = DEFAULT_EPOCH - 86_400L
        private const val MAX_EPOCH = DEFAULT_EPOCH + 86_400L

        /** Allowance for clock skew between phone and ring. */
        private const val FUTURE_TOLERANCE = 300L
    }
}
