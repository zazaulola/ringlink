package io.github.ringlink.data

import io.github.ringlink.protocol.Descriptor
import io.github.ringlink.protocol.EpochRecord
import io.github.ringlink.protocol.RecordSink
import io.github.ringlink.protocol.SportRecord
import kotlinx.coroutines.flow.Flow

/**
 * The durable landing zone for everything drained off the ring.
 *
 * This class is the reason the sync engine is safe: acknowledging a history page tells the ring to
 * discard it, so every method here writes to SQLite and only returns once the write has committed.
 * Nothing is buffered in memory waiting for a "commit at the end of the sync".
 */
class RingRepository(private val dao: RingDao) : RecordSink {

    override suspend fun onEpochs(channel: Int, records: List<EpochRecord>) {
        if (records.isEmpty()) return
        dao.insertEpochs(
            records.map {
                EpochEntity(
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
            records.map { SportEntity(counter = it.counter, heartRate = it.heartRate, steps = it.steps) },
        )
    }

    override suspend fun onDescriptor(descriptor: Descriptor) {
        dao.insertDeviceState(
            DeviceStateEntity(
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

    fun epochCount(): Flow<Int> = dao.epochCount()
    fun pendingExportCount(): Flow<Int> = dao.pendingExportCount()
    fun latestDeviceState(): Flow<DeviceStateEntity?> = dao.latestDeviceState()
    suspend fun newestCounter(): Long? = dao.newestCounter()

    suspend fun unexportedEpochs(limit: Int) = dao.unexportedEpochs(limit)
    suspend fun unexportedSport(limit: Int) = dao.unexportedSport(limit)
    suspend fun unexportedDeviceStates(limit: Int) = dao.unexportedDeviceStates(limit)

    suspend fun markExported(epochs: List<Long>, sport: List<Long>, states: List<Long>) {
        val now = System.currentTimeMillis()
        if (epochs.isNotEmpty()) dao.markEpochsExported(epochs, now)
        if (sport.isNotEmpty()) dao.markSportExported(sport, now)
        if (states.isNotEmpty()) dao.markDeviceStatesExported(states, now)
    }

    /** Contiguous runs of sleep-channel epochs, used to derive sleep sessions. */
    suspend fun epochsBetween(from: Long, to: Long) = dao.epochsBetween(from, to)
}
