package io.github.ringlink.data

import io.github.ringlink.protocol.Descriptor
import io.github.ringlink.protocol.EpochRecord
import io.github.ringlink.protocol.RecordSink
import io.github.ringlink.protocol.SportRecord
import io.github.ringlink.weather.WeatherStore
import kotlinx.coroutines.flow.Flow

/**
 * The durable landing zone for everything drained off a ring.
 *
 * This class is what makes the sync engine safe: acknowledging a history page tells the ring to
 * discard it, so every write here commits before returning. Nothing waits in memory for a "commit
 * at the end of the sync".
 *
 * Every row carries the identity of the ring that produced it. With two rings that is not
 * bookkeeping but correctness — both number their epochs from the same base, so their counters
 * collide by design.
 */
class RingRepository(private val dao: RingDao) {

    /**
     * One detector for the whole app.
     *
     * Descriptors arrive by two routes — the idle loop and a sync — and both land here, so deciding
     * charging in this one place is what keeps the stored history and the screen from disagreeing.
     */
    private val charge = ChargeDetector()

    /** The latest charging verdict for a ring, for callers that need it without a descriptor. */
    fun isCharging(ringId: String): Boolean = charge.isCharging(ringId)

    /** Whether each ring is on a finger, judged as its readings arrive — see [WearRules]. */
    private val wear = HashMap<String, WearTracker>()

    /** Each docked ring's current stay on the charger: since when, last seen, and its temperature. */
    private val docked = HashMap<String, Docked>()

    /** When each ring last gave fresh evidence of a finger — see [isWornNow]. */
    private val freshWornAt = HashMap<String, Long>()

    /** Each ring's last step count and the quarter it was in, to see the counter move. */
    private val lastSteps = HashMap<String, Pair<Long, Int>>()

    private data class Docked(val since: Long, val lastAt: Long, val tempC: Double)

    fun isWorn(ringId: String): Boolean = synchronized(wear) { wear[ringId]?.isWorn ?: false }

    /**
     * Whether the ring is on a finger by what it says right now, not by a state held over.
     *
     * The held state is right for a label, wrong for choosing which ring to buzz: just taken off
     * after a walk, a ring keeps its quarter's step count and its hold for up to half an hour,
     * and would take every buzz while the ring now on the finger, cool and still, took none. Here
     * steps count only while the counter moves, and the evidence must be minutes old at most.
     */
    fun isWornNow(ringId: String, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(wear) { freshWornAt[ringId]?.let { now - it <= FRESH_MS } ?: false }

    /**
     * Store a descriptor, with the link's signal strength when it was read.
     *
     * Charging and wear are both decided here, where descriptors from the idle loop and from a
     * sync meet, so the stored history and the screen cannot disagree.
     */
    suspend fun recordDescriptor(ringId: String, descriptor: Descriptor, rssi: Int?) {
        val now = System.currentTimeMillis()
        val charging = charge.update(ringId, descriptor)
        synchronized(wear) {
            if (charging) {
                val prev = docked[ringId]
                val since = if (prev != null && now - prev.lastAt <= DOCK_GAP_MS) prev.since else now
                docked[ringId] = Docked(since, now, descriptor.skinTempA)
            } else {
                docked.remove(ringId)
            }
            // Another ring on its charger says what the room is — once it has cooled from the
            // finger it came off, and while its reading is fresh.
            val room = docked.filterKeys { it != ringId }.values
                .filter { now - it.since >= DOCK_SETTLE_MS && now - it.lastAt < ROOM_FRESH_MS }
                .maxByOrNull { it.lastAt }?.tempC
            val ambient = Ambient(roomC = room, outdoorC = WeatherStore.cachedAt(now))
            val evidence = WearRules.evidence(charging, descriptor.skinTempA, descriptor.steps, ambient)
            wear.getOrPut(ringId) { WearTracker() }.update(now, evidence)

            val quarter = StepQuarters.floor(now)
            val before = lastSteps[ringId]
            lastSteps[ringId] = quarter to descriptor.steps
            val stepping = descriptor.steps > 0 && (before == null || before.first != quarter || descriptor.steps > before.second)
            val byTemperature = WearRules.evidence(charging, descriptor.skinTempA, 0, ambient) == WearEvidence.WORN
            if (!charging && (stepping || byTemperature)) freshWornAt[ringId] = now
            if (charging) freshWornAt.remove(ringId)
        }
        dao.insertDeviceState(
            DeviceStateEntity(
                ringId = ringId,
                recordedAt = now,
                batteryPercent = descriptor.batteryPercent,
                steps = descriptor.steps,
                skinTempA = descriptor.skinTempA,
                skinTempB = descriptor.skinTempB,
                batteryMillivolts = descriptor.batteryMillivolts,
                onCharger = charging,
                state = descriptor.state,
                caseBattery = descriptor.caseBatteryPercent,
                caseCharging = if (descriptor.inChargingCase) descriptor.caseCharging else null,
                rssi = rssi,
            ),
        )
    }

    /** A sink bound to one ring, so a sync can only write rows attributed to the ring it drained. */
    fun sinkFor(ringId: String): RecordSink = RingSink(ringId)

    private inner class RingSink(private val ringId: String) : RecordSink {

        override suspend fun onEpochs(channel: Int, records: List<EpochRecord>) {
            if (records.isEmpty()) return
            dao.upsertEpochs(
                records.map {
                    EpochEntity(
                        ringId = ringId,
                        counter = it.counter,
                        channel = channel,
                        layout = it.layout.name,
                        heartRate = it.heartRate,
                        hrvRmssd = it.hrvRmssd,
                        confidence = it.confidence,
                        respiratoryRate = it.respiratoryRate,
                        spo2 = it.spo2,
                        motionSum = it.motion.sum(),
                    )
                },
            )
        }

        override suspend fun onSport(records: List<SportRecord>) {
            if (records.isEmpty()) return
            dao.insertSport(
                records.map {
                    SportEntity(
                        ringId = ringId,
                        counter = it.counter,
                        heartRate = it.heartRate,
                        steps = it.steps,
                    )
                },
            )
        }

        override suspend fun onDescriptor(descriptor: Descriptor) = recordDescriptor(ringId, descriptor, rssi = null)
    }

    fun epochCount(): Flow<Int> = dao.epochCount()
    fun pendingExportCount(): Flow<Int> = dao.pendingExportCount()
    fun latestDeviceState(ringId: String): Flow<DeviceStateEntity?> = dao.latestDeviceState(ringId)

    /** The last time this ring was seen in its case, for "how was the case" once it is out. */
    suspend fun latestCaseReading(ringId: String): DeviceStateEntity? = dao.latestCaseReading(ringId)
    suspend fun newestCounter(ringId: String): Long? = dao.newestCounter(ringId)

    suspend fun unexportedEpochs(limit: Int) = dao.unexportedEpochs(limit)
    suspend fun unexportedSport(limit: Int) = dao.unexportedSport(limit)
    suspend fun unexportedDeviceStates(limit: Int) = dao.unexportedDeviceStates(limit)

    /** Mark exported rows, grouped by ring since the key is (ring, counter). */
    suspend fun markExported(
        epochs: List<EpochEntity>,
        sport: List<SportEntity>,
        states: List<DeviceStateEntity>,
    ) {
        val now = System.currentTimeMillis()
        epochs.groupBy { it.ringId }.forEach { (ring, rows) ->
            dao.markEpochsExported(ring, rows.map { it.counter }, now)
        }
        sport.groupBy { it.ringId }.forEach { (ring, rows) ->
            dao.markSportExported(ring, rows.map { it.counter }, now)
        }
        states.groupBy { it.ringId }.forEach { (ring, rows) ->
            dao.markDeviceStatesExported(ring, rows.map { it.recordedAt }, now)
        }
    }

    suspend fun resetExports() {
        dao.clearEpochExports()
        dao.clearSportExports()
        dao.clearDeviceStateExports()
    }

    suspend fun epochsBetween(from: Long, to: Long) = dao.epochsBetween(from, to)
    fun epochsSince(counter: Long): Flow<List<EpochEntity>> = dao.epochsSince(counter)
    fun restingHeartRate(from: Long, to: Long, sampleCount: Int = 12): Flow<Int?> =
        dao.restingHeartRateBetween(from, to, sampleCount)
    suspend fun epochsForRingSince(ringId: String, since: Long) = dao.epochsForRingSince(ringId, since)
    suspend fun knownRings() = dao.knownRings()
    suspend fun deviceStateRings() = dao.deviceStateRings()
    suspend fun deviceStatesSince(ringId: String, since: Long) = dao.deviceStatesSince(ringId, since)
    fun deviceStatesSinceFlow(since: Long): Flow<List<DeviceStateEntity>> = dao.deviceStatesSinceFlow(since)
    fun summarySince(counter: Long): Flow<Summary?> = dao.summarySince(counter)

    private companion object {
        /** A docked ring's reading this old no longer says what the room is now. */
        const val ROOM_FRESH_MS = 30 * 60_000L
        /** Straight off a finger a ring is at skin temperature; it reads the room after this. */
        const val DOCK_SETTLE_MS = 20 * 60_000L
        /** Docked readings further apart than this are two stays on the charger, not one. */
        const val DOCK_GAP_MS = 10 * 60_000L
        /** Fresh evidence for choosing which ring to buzz. */
        const val FRESH_MS = 3 * 60_000L
    }
}
