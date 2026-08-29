package io.github.ringlink.data

import io.github.ringlink.protocol.Descriptor
import io.github.ringlink.protocol.EpochRecord
import io.github.ringlink.protocol.RecordSink
import io.github.ringlink.protocol.SportRecord
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

    /** A sink bound to one ring, so a sync can only write rows attributed to the ring it drained. */
    fun sinkFor(ringId: String): RecordSink = RingSink(ringId)

    private inner class RingSink(private val ringId: String) : RecordSink {

        override suspend fun onEpochs(channel: Int, records: List<EpochRecord>) {
            if (records.isEmpty()) return
            dao.insertEpochs(
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

        override suspend fun onDescriptor(descriptor: Descriptor) {
            dao.insertDeviceState(
                DeviceStateEntity(
                    ringId = ringId,
                    recordedAt = System.currentTimeMillis(),
                    batteryPercent = descriptor.batteryPercent,
                    steps = descriptor.steps,
                    skinTempA = descriptor.skinTempA,
                    skinTempB = descriptor.skinTempB,
                    batteryMillivolts = descriptor.batteryMillivolts,
                    onCharger = descriptor.onCharger,
                ),
            )
        }
    }

    fun epochCount(): Flow<Int> = dao.epochCount()
    fun pendingExportCount(): Flow<Int> = dao.pendingExportCount()
    fun latestDeviceState(ringId: String): Flow<DeviceStateEntity?> = dao.latestDeviceState(ringId)
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
    fun summarySince(counter: Long): Flow<Summary?> = dao.summarySince(counter)
}
