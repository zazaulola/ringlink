package io.github.ringlink.trigger

/**
 * Decides how many buzzes an arrival of notifications is worth.
 *
 * Fifteen notifications delivered at once are a single event to the person wearing the ring, and
 * fifteen buzzes for it is a nuisance rather than information. So a burst is worth at most
 * [maxPerBurst] buzzes, however many notifications make it up.
 *
 * Notifications arriving close together belong to the same burst. Once things have been quiet for
 * [burstGapMs], the next one is genuinely separate news and gets a fresh budget.
 *
 * Only genuinely new notifications should be offered here — re-posts of ones already seen are
 * filtered earlier, so the budget is never spent on something the wearer has already been told.
 */
class BurstLimiter(
    private val maxPerBurst: Int = DEFAULT_MAX_PER_BURST,
    private val burstGapMs: Long = DEFAULT_BURST_GAP_MS,
) {
    private val lock = Any()
    private var spent = 0
    private var lastArrivalAt = Long.MIN_VALUE

    /**
     * Whether a notification arriving at [now] may buzz.
     *
     * Every arrival keeps the burst alive, whether or not it buzzes — otherwise a long enough flood
     * would quietly roll over into a new burst and start buzzing again halfway through.
     */
    fun allows(now: Long): Boolean = synchronized(lock) {
        if (now - lastArrivalAt >= burstGapMs) spent = 0
        lastArrivalAt = now
        spent < maxPerBurst
    }

    /**
     * Record that a buzz actually reached the ring.
     *
     * Kept separate from [allows] deliberately: a buzz that never arrived was never felt, and must
     * not spend budget that the next notification needs.
     */
    fun spend() = synchronized(lock) { spent++ }

    companion object {
        const val DEFAULT_MAX_PER_BURST = 3
        const val DEFAULT_BURST_GAP_MS = 20_000L
    }
}
