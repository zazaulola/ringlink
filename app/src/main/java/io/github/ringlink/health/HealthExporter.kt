package io.github.ringlink.health

import android.content.Context
import androidx.health.connect.client.records.Record
import io.github.ringlink.data.DeviceStateEntity
import io.github.ringlink.data.EpochEntity
import io.github.ringlink.data.RingRepository
import io.github.ringlink.data.Settings
import io.github.ringlink.protocol.RingClock

/**
 * Moves stored ring data into Health Connect and marks it exported.
 *
 * Export is deliberately decoupled from the sync: the ring's history is a destructive read, so it
 * must land in local storage first and reach Health Connect afterwards. If Health Connect is
 * unavailable or permissions are revoked, the data is still safe locally and will be exported later.
 */
class HealthExporter(
    context: Context,
    private val repo: RingRepository,
    private val settings: Settings,
) {

    private val writer = HealthConnectWriter(context)

    val permissions: Set<String> get() = writer.permissions
    fun isAvailable(): Boolean = writer.isAvailable()
    suspend fun hasPermissions(): Boolean = writer.isAvailable() && writer.hasAllPermissions()

    /**
     * Re-send everything, overwriting what is already in Health Connect.
     *
     * Records keep their raw ring counters, so if the clock anchor was corrected the rewrite lands
     * at the corrected times; the rising client-record version makes Health Connect accept the
     * replacement rather than ignore it as a duplicate.
     */
    suspend fun reExportAll(clock: RingClock): Int {
        if (!hasPermissions()) return 0
        repo.resetExports()
        var total = 0
        while (true) {
            val n = exportPending(clock)
            if (n == 0) break
            total += n
        }
        return total
    }

    /** Returns how many source rows were exported. */
    suspend fun exportPending(clock: RingClock): Int {
        if (!settings.exportToHealthConnect) return 0
        if (!hasPermissions()) return 0

        val epochs = repo.unexportedEpochs(BATCH)
        val states = repo.unexportedDeviceStates(BATCH)
        if (epochs.isEmpty() && states.isEmpty()) return 0

        val records = ArrayList<Record>()
        records += writer.mapEpochs(epochs, clock)
        records += writer.mapSteps(states)
        records += temperatureRecords(states)

        if (records.isNotEmpty()) writer.insert(records)

        // Only mark exported once the insert has returned — a failure leaves the rows pending.
        repo.markExported(epochs = epochs, sport = emptyList(), states = states)
        return epochs.size + states.size
    }

    /**
     * Skin temperature, grouped per ring so each gets its own personal baseline.
     *
     * Health Connect stores this as a deviation from a baseline rather than an absolute, which
     * suits the measurement: a finger's surface temperature says little on its own and a lot when
     * compared against the same finger's norm.
     */
    private fun temperatureRecords(states: List<DeviceStateEntity>): List<Record> {
        if (!writer.skinTemperatureSupported()) return emptyList()
        return states.groupBy { it.ringId }.flatMap { (_, rows) ->
            val usable = rows.filter { it.skinTempA in PLAUSIBLE_SKIN_TEMP }
            if (usable.size < 2) return@flatMap emptyList()
            val baseline = usable.map { it.skinTempA }.sorted()[usable.size / 2]
            writer.mapSkinTemperature(usable, baseline)
        }
    }

    /**
     * Write an estimate of when the wearer slept.
     *
     * Run over a window rather than the export batch, because deciding whether a given minute was
     * sleep needs the surrounding day for context. Sessions carry a deterministic id, so re-running
     * refines an estimate in place instead of stacking duplicates.
     */
    suspend fun exportSleep(clock: RingClock): Int {
        if (!settings.estimateSleep || !hasPermissions()) return 0
        val since = clock.cursorForNow(System.currentTimeMillis() / 1000) - SLEEP_WINDOW_SECONDS
        var written = 0
        for (ringId in repo.knownRings()) {
            val epochs = repo.epochsForRingSince(ringId, since)
            val periods = SleepDetector.detect(
                epochs.map { SleepInput(it.counter, it.heartRate, it.motionSum) },
            )
            if (periods.isEmpty()) continue
            val records = periods.map {
                writer.sleepSession(ringId, it.startCounter, it.endCounter, clock)
            }
            writer.insert(records)
            written += records.size
        }
        return written
    }

    /**
     * Sleep STAGES are deliberately NOT exported.
     *
     * The obvious-looking derivation — treat a contiguous run on the ring's "sleep" channel (0x00)
     * as a night — is wrong. Measured over 43 hours of real data, the ring streams that channel
     * continuously whether or not anyone is asleep, and the SpO2-bearing epochs are spread evenly
     * across all 24 hours. Applying the contiguity rule to that data produced a single 32-hour
     * "sleep session".
     *
     * The ring also never transmits a hypnogram: the vendor app derives sleep from the same raw
     * signals we already store. Detecting sleep from heart rate and motion is therefore possible,
     * but it is analysis rather than protocol, and until it is validated against something it would
     * be inventing sleep the user did not have. Health data is exactly where guessing is worst.
     */
    suspend fun deleteExportedSleepSessions(): Boolean = runCatching {
        if (!hasPermissions()) return false
        writer.deleteAllSleepSessions()
        true
    }.getOrDefault(false)

    private companion object {
        const val BATCH = 500
        /** Anything outside this is not a finger. */
        val PLAUSIBLE_SKIN_TEMP = 20.0..42.0
        const val SLEEP_WINDOW_SECONDS = 7 * 24 * 3600L
        /** Two missing epochs still counts as the same night. */
        const val MAX_GAP_SECONDS = 450L
        const val MIN_SESSION_SECONDS = 30 * 60L
    }
}
