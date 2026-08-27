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
class RingClock(private var epochSeconds: Long = DEFAULT_EPOCH) {

    fun epoch(): Long = epochSeconds

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
        val residual = nowUnixSeconds - newestCounter
        val hours = Math.round((residual - epochSeconds) / 3600.0)
        if (hours == 0L) return false
        val candidate = epochSeconds + hours * 3600L
        if (candidate !in MIN_EPOCH..MAX_EPOCH) return false
        epochSeconds = candidate
        return true
    }

    companion object {
        /** 2019-12-31 12:00:00 UTC — OpenCircuit's constant. */
        const val DEFAULT_EPOCH = 1_577_793_600L

        /** Accept only anchors within a day of the documented candidates. */
        private const val MIN_EPOCH = DEFAULT_EPOCH - 86_400L
        private const val MAX_EPOCH = DEFAULT_EPOCH + 86_400L
    }
}
