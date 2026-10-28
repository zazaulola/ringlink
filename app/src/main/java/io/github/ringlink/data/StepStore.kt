package io.github.ringlink.data

import io.github.ringlink.L
import kotlinx.coroutines.NonCancellable
import io.github.ringlink.weather.WeatherStore
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
        var stop = end
        while (stop > first) {
            val start = maxOf(first, stop - CHUNK_MS)
            derive(ring, start, stop)
            stop = start
        }
        return dao.stepQuartersForRing(ring, first, end).size
    }

    /**
     * Re-derive one ring's quarters from [from] on — after a sync brought epochs whose pulse now
     * says when the ring was worn. Skipped when a full rebuild is due anyway, which will cover it.
     */
    suspend fun rederive(ring: String, from: Long, now: Long = System.currentTimeMillis()) = lock.withLock {
        if (settings.stepQuartersBuiltGeneration != settings.stepQuartersGeneration) return@withLock
        val end = StepQuarters.floor(now) + Q
        var start = StepQuarters.floor(from)
        var wrote = false
        while (start < end) {
            val stop = minOf(start + CHUNK_MS, end)
            if (derive(ring, start, stop)) wrote = true
            start = stop
        }
        // Past days changed, which views of the whole period load only once a day.
        if (wrote) rebuildCount.update { it + 1 }
    }

    /**
     * Re-derive one ring's quarters starting in [start, stop), writing them only when they changed
     * — every write wakes every screen watching the table.
     *
     * Readings are taken from the last settled one at least two quarters before the window, which
     * puts the derivation in exactly the state it would have reached starting from the first
     * reading ever — for the quarters and for the wear held across unsure readings — and to a
     * quarter past the end, so the last quarter is judged with what follows it.
     */
    /** Returns whether anything stored changed. */
    private suspend fun derive(ring: String, start: Long, stop: Long): Boolean {
        val anchor = dao.settledReadingBefore(ring, start - 2 * Q) ?: Long.MIN_VALUE
        val readings = dao.stepReadings(ring, anchor, stop + Q)
        val fresh = StepQuarters.quartersIn(samples(ring, readings), start, stop)
            .map { StepQuarterEntity(ring, it.startMillis, it.steps, it.worn) }
        if (fresh == dao.stepQuartersForRing(ring, start, stop)) return false
        dao.replaceStepQuarters(ring, start, stop, fresh)
        return true
    }

    /**
     * Readings as step samples, each judged worn or not.
     *
     * The pulse decides where there is one: the ring measures a pulse only on a finger, so an epoch
     * with a heart rate beside the reading means worn, and epochs without one mean it was off.
     * Where no epoch is near — not synced yet, or drained by another app — [WearRules] judge from
     * temperature and steps, with a docked ring's temperature for the room and the weather for the
     * outdoors when they are known.
     */
    private suspend fun samples(ring: String, readings: List<StepReading>): List<StepSample> {
        if (readings.isEmpty()) return emptyList()
        val first = readings.first().recordedAt
        val last = readings.last().recordedAt
        val epochSeconds = settings.epochAnchor
        val pulse = dao.pulseEpochs(ring, first / 1000 - epochSeconds - PULSE_NEAR_S, last / 1000 - epochSeconds + PULSE_NEAR_S + 1)
            .map { (it.counter + epochSeconds) * 1000 to (it.heartRate != null && it.heartRate > 0) }
        // Far enough back that a docked reading in use can be checked for having settled.
        val docked = dao.readingRings().filter { it != ring }
            .flatMap { settled(dao.dockedReadings(it, first - ROOM_FRESH_MS - DOCK_SETTLE_MS, last + 1)) }
            .sortedBy { it.recordedAt }
        val weather = if (!settings.weatherEnabled) {
            emptyMap()
        } else {
            dao.weatherBetween(StepQuarters.floor(first) - HOUR_MS, last + HOUR_MS)
                .filter { WeatherStore.usable(it.hourStart, it.receivedAt) }
                .associate { it.hourStart to it.tempC }
        }

        val tracker = WearTracker()
        var p = 0
        var d = 0
        var room: DockedReading? = null
        return readings.map { r ->
            val t = r.recordedAt
            while (p < pulse.size && pulse[p].first < t - PULSE_NEAR_MS) p++
            var q = p
            var near = false
            var beating = false
            while (q < pulse.size && pulse[q].first <= t + PULSE_NEAR_MS) {
                near = true
                if (pulse[q].second) beating = true
                q++
            }
            while (d < docked.size && docked[d].recordedAt <= t) room = docked[d++]
            val evidence = when {
                r.onCharger -> WearEvidence.OFF
                near && beating -> WearEvidence.WORN
                near -> WearEvidence.OFF
                else -> WearRules.evidence(
                    onCharger = false,
                    skinC = r.skinTempA,
                    steps = r.steps,
                    ambient = Ambient(
                        roomC = room?.takeIf { t - it.recordedAt < ROOM_FRESH_MS }?.skinTempA,
                        outdoorC = weather[Math.floorDiv(t, HOUR_MS) * HOUR_MS],
                    ),
                )
            }
            StepSample(t, r.steps, worn = tracker.update(t, evidence))
        }
    }

    /**
     * The readings of one docked ring that say what the room is: those taken once it had been on
     * its charger [DOCK_SETTLE_MS] without a break — before that it is cooling from the finger it
     * came off. The same rule as RingRepository's, so history and the screen agree.
     */
    private fun settled(readings: List<DockedReading>): List<DockedReading> {
        val out = ArrayList<DockedReading>()
        var since = Long.MIN_VALUE
        var previous = Long.MIN_VALUE
        for (r in readings) {
            if (previous == Long.MIN_VALUE || r.recordedAt - previous > DOCK_GAP_MS) since = r.recordedAt
            previous = r.recordedAt
            if (r.recordedAt - since >= DOCK_SETTLE_MS) out += r
        }
        return out
    }

    companion object {
        private const val Q = StepQuarters.QUARTER_MS
        private const val CHUNK_MS = 7 * 24 * 3600_000L
        private const val HOUR_MS = 3_600_000L

        /** Epochs are 2.5 minutes apart: one within half of that either side covers a reading. */
        private const val PULSE_NEAR_S = 150L
        private const val PULSE_NEAR_MS = PULSE_NEAR_S * 1000

        /** As in RingRepository: a docked ring's reading this old no longer says what the room is. */
        private const val ROOM_FRESH_MS = 30 * 60_000L
        private const val DOCK_SETTLE_MS = 20 * 60_000L
        private const val DOCK_GAP_MS = 10 * 60_000L

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
