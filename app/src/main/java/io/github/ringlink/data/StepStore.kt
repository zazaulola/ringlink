package io.github.ringlink.data

import io.github.ringlink.L
import io.github.ringlink.protocol.Descriptor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps step_quarters in step with the readings they are derived from, and reads them back.
 *
 * Each refresh re-derives only the tail, from the last stored quarter on, since the quarter in
 * progress keeps growing. The whole history is derived again when it is not current — the first
 * time, and after a restore, which can add readings anywhere in the past. A rebuild replaces one
 * week at a time, newest first, so the table is never empty and today is right first.
 */
class StepStore(private val dao: RingDao, private val settings: Settings) {

    /** Returns true when it rebuilt the whole history rather than just the tail. */
    suspend fun refresh(now: Long = System.currentTimeMillis()): Boolean = lock.withLock {
        val end = StepQuarters.floor(now) + Q
        val rings = dao.readingRings()
        val wanted = settings.stepQuartersGeneration
        if (settings.stepQuartersBuiltGeneration != wanted) {
            // Seen through even if whoever asked has gone: half a rebuild would be redone in full.
            withContext(NonCancellable) {
                var quarters = 0
                for (ring in rings) quarters += rebuild(ring, end)
                dao.deleteStepQuartersExcept(rings)
                settings.stepQuartersBuiltGeneration = wanted
                rebuildCount.update { it + 1 }
                L.i("step quarters rebuilt: $quarters")
            }
            return@withLock true
        }
        for (ring in rings) {
            var newest = dao.newestStepQuarter(ring)
            if (newest != null && newest >= end) {
                // The phone's clock was ahead and has been put right. Quarters dated in the future
                // would hold the tail back until real time caught up with them.
                dao.deleteStepQuarters(ring, end, Long.MAX_VALUE)
                newest = dao.newestStepQuarter(ring)
            }
            val from = newest?.let { it - Q } ?: dao.oldestDeviceStateAt(ring)?.let(StepQuarters::floor) ?: continue
            // Nothing past a ring's last reading to derive — a ring left in a drawer costs nothing.
            val last = dao.newestDeviceStateAt(ring) ?: continue
            val to = minOf(end, StepQuarters.floor(last) + 2 * Q)
            var start = from
            while (start < to) {
                val stop = minOf(start + CHUNK_MS, to)
                derive(ring, start, stop)
                start = stop
            }
        }
        false
    }

    /** The earliest reading of any ring, which is as far back as steps can be derived. */
    suspend fun oldestReading(): Long? = dao.readingRings().mapNotNull { dao.oldestDeviceStateAt(it) }.minOrNull()

    fun quartersFlow(from: Long, to: Long): Flow<List<StepQuarterEntity>> = dao.stepQuartersFlow(from, to)

    suspend fun quarters(from: Long, to: Long): List<StepQuarterEntity> = dao.stepQuartersBetween(from, to)

    private suspend fun rebuild(ring: String, end: Long): Int {
        val oldest = dao.oldestDeviceStateAt(ring) ?: return 0
        val first = StepQuarters.floor(oldest)
        dao.deleteStepQuarters(ring, Long.MIN_VALUE, first)
        dao.deleteStepQuarters(ring, end, Long.MAX_VALUE)
        var count = 0
        var stop = end
        while (stop > first) {
            val start = maxOf(first, stop - CHUNK_MS)
            count += derive(ring, start, stop)
            stop = start
        }
        return count
    }

    /**
     * Re-derive one ring's quarters starting in [start, stop), writing them only when they changed
     * — every write wakes every screen watching the table.
     *
     * Readings are taken from the last settled one before the window, which puts the derivation in
     * exactly the state it would have reached starting from the first reading ever, and to a
     * quarter past the end, so the last quarter is judged with what follows it.
     */
    private suspend fun derive(ring: String, start: Long, stop: Long): Int {
        val anchor = dao.settledReadingBefore(ring, start - Q) ?: Long.MIN_VALUE
        val readings = dao.stepReadings(ring, anchor, stop + Q)
        val fresh = StepQuarters.quartersIn(readings.map { it.toSample() }, start, stop)
            .map { StepQuarterEntity(ring, it.startMillis, it.steps, it.worn) }
        if (fresh != dao.stepQuartersForRing(ring, start, stop)) {
            dao.replaceStepQuarters(ring, start, stop, fresh)
        }
        return fresh.size
    }

    private fun StepReading.toSample() =
        StepSample(recordedAt, steps, worn = !onCharger && skinTempA >= Descriptor.WORN_SKIN_TEMP_C)

    companion object {
        private const val Q = StepQuarters.QUARTER_MS
        private const val CHUNK_MS = 7 * 24 * 3600_000L

        /** One for the process: the service and the screens both refresh. */
        private val lock = Mutex()

        private val rebuildCount = MutableStateFlow(0)

        /**
         * Counts completed rebuilds, whoever ran them, so anything holding a view of the past
         * reloads it — the service often rebuilds while a screen is watching.
         */
        val rebuilds: StateFlow<Int> = rebuildCount
    }
}
