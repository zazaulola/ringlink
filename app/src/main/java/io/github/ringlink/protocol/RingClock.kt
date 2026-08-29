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
        // Bootstrap only, and never again.
        //
        // The tempting rule — "records look persistently stale, so the ring's base must have
        // shifted" — is unsound, because the ring stops recording entirely when it is off the
        // finger. Measured: 8.6 minutes with the ring off produced zero new epochs. So "stale
        // records" is the normal signature of a ring in a drawer, and re-anchoring on it would
        // silently re-date the entire archive every time the user takes the ring off for an
        // afternoon. Shifting the other way is worse still: a record from the future is impossible,
        // so that can only ever be a mis-parse.
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
         * 2019-12-31 16:00:00 UTC — the anchor for RECORD COUNTERS.
         *
         * Two constants circulate, exactly 14400 s apart, and the disagreement is real rather than
         * one project simply being wrong. Measured on a Gen 3 (FR05):
         *
         *  - the cursor the vendor app writes in its 02 00 sync-open matches its capture time
         *    exactly under this constant, and the ring must read that cursor in the same space it
         *    numbers records in, since the cursor means "drain up to about here";
         *  - a 1049-record dataset ends 3.7 minutes before the sync that fetched it under this
         *    constant, and four hours stale under the other.
         *
         * A smaller earlier dataset pointed the other way, which is why [calibrate] can still
         * re-anchor: the ring's base itself appears to shift by a whole 4 hours, seemingly when the
         * vendor app sets the ring's clock.
         */
        const val DEFAULT_EPOCH = 1_577_808_000L

        /** The other constant in circulation, 4 h earlier. See the class comment. */
        const val ALTERNATE_EPOCH = 1_577_793_600L

        /** Accept only anchors within a day of the documented candidates. */
        private const val MIN_EPOCH = DEFAULT_EPOCH - 86_400L
        private const val MAX_EPOCH = DEFAULT_EPOCH + 86_400L

        /** Allowance for clock skew between phone and ring. */
        private const val FUTURE_TOLERANCE = 300L
    }
}
