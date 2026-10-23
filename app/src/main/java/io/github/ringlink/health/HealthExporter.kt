package io.github.ringlink.health

import android.content.Context
import androidx.health.connect.client.records.Record
import io.github.ringlink.data.DeviceStateEntity
import io.github.ringlink.data.StepSample
import io.github.ringlink.data.StepQuarters
import io.github.ringlink.data.EpochEntity
import io.github.ringlink.data.RingRepository
import io.github.ringlink.data.Settings
import io.github.ringlink.protocol.Descriptor
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
            val n = exportBatch(clock)
            if (n == 0) break
            total += n
        }
        return total
    }

    /**
     * Export everything waiting, not just one batch.
     *
     * Batching bounds memory, but stopping after a single batch meant a backlog drained at 500 rows
     * per sync — after an outage that is days of catching up. This keeps going until nothing is
     * pending, with a ceiling so a row that somehow never gets marked cannot spin forever.
     */
    suspend fun exportAllPending(clock: RingClock): Int {
        var total = 0
        repeat(MAX_BATCHES) {
            val n = exportBatch(clock)
            if (n == 0) return total
            total += n
        }
        return total
    }

    /** Returns how many source rows were exported. */
    suspend fun exportBatch(clock: RingClock): Int {
        if (!settings.exportToHealthConnect) return 0
        if (!hasPermissions()) return 0

        val epochs = repo.unexportedEpochs(BATCH)
        val states = repo.unexportedDeviceStates(BATCH)
        val sport = repo.unexportedSport(BATCH)
        if (epochs.isEmpty() && states.isEmpty() && sport.isEmpty()) return 0

        val records = ArrayList<Record>()
        records += writer.mapEpochs(epochs, clock)
        records += writer.mapSport(sport, clock)

        if (records.isNotEmpty()) writer.insert(records)

        // Only mark exported once the insert has returned — a failure leaves the rows pending.
        repo.markExported(epochs = epochs, sport = sport, states = states)
        return epochs.size + states.size + sport.size
    }

    /**
     * Skin temperature, one record per ring per finished hour.
     *
     * Recomputed over a window, like steps, because an hour's record needs all of that hour's
     * readings; built from whatever rows happened to be waiting, an hour split across two exports
     * would be written twice under different ids.
     *
     * Only readings from a ring that looks worn are sent. A ring on a table reads the room — 25 °C
     * is a fine room and a dangerous finger — and filed as skin temperature it is simply wrong.
     */
    suspend fun exportTemperature(windowMillis: Long? = STEPS_WINDOW_MS): Int {
        if (!settings.exportToHealthConnect || !hasPermissions() || !writer.skinTemperatureSupported()) return 0
        val now = System.currentTimeMillis()
        // From an hour boundary. Starting mid-hour, the oldest hour was rebuilt from only the
        // readings after the window start and, under the same id, replaced the complete record
        // written earlier — on every sync.
        val since = if (windowMillis == null) 0L else Math.floorDiv(now - windowMillis, HOUR_MS) * HOUR_MS
        var written = 0
        for (ringId in repo.deviceStateRings()) {
            val worn = repo.deviceStatesSince(ringId, since)
                .filter { !it.onCharger && it.skinTempA >= Descriptor.WORN_SKIN_TEMP_C && it.skinTempA <= MAX_SKIN_TEMP }
            val records = worn.groupBy { Math.floorDiv(it.recordedAt, HOUR_MS) * HOUR_MS }
                .filterKeys { hour -> hour + HOUR_MS <= now }
                .mapNotNull { (hour, rows) -> writer.mapSkinTemperatureHour(ringId, hour, rows) }
            if (records.isEmpty()) continue
            records.chunked(BATCH).forEach { writer.insert(it) }
            written += records.size
        }
        return written
    }

    /**
     * Gated exactly like [exportTemperature]: deleting what cannot then be rewritten — export
     * switched off, or the feature unavailable — would just erase the history from Health Connect.
     */
    suspend fun deleteExportedSkinTemperature(): Boolean = runCatching {
        if (!settings.exportToHealthConnect || !hasPermissions() || !writer.skinTemperatureSupported()) return false
        writer.deleteAllSkinTemperature()
        true
    }.getOrDefault(false)

    /**
     * Write an estimate of when the wearer slept.
     *
     * Run over a window rather than the export batch, because deciding whether a given minute was
     * sleep needs the surrounding day for context. Sessions carry a deterministic id, so re-running
     * refines an estimate in place instead of stacking duplicates.
     */
    suspend fun exportSleep(clock: RingClock, windowSeconds: Long? = SLEEP_WINDOW_SECONDS): Int {
        if (!settings.estimateSleep || !settings.exportToHealthConnect || !hasPermissions()) return 0
        // A null window means all of history — what a re-export needs, having just deleted every
        // session it wrote. Rewriting only the last week would have lost every older night.
        val since = if (windowSeconds == null) {
            0L
        } else {
            clock.cursorForNow(System.currentTimeMillis() / 1000) - windowSeconds
        }
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
     * Steps for every ring, one record per completed quarter-hour.
     *
     * Recomputed over a window rather than from rows waiting to be exported, because a quarter's
     * count is a property of all its readings together. Only finished quarters are written; the
     * current one is still counting.
     */
    suspend fun exportSteps(windowMillis: Long? = STEPS_WINDOW_MS): Int {
        if (!settings.exportToHealthConnect || !hasPermissions()) return 0
        val now = System.currentTimeMillis()
        // From a quarter boundary: a window starting mid-quarter would see only that quarter's tail
        // and could rewrite it under the same id with less than it really held.
        val since = if (windowMillis == null) 0L else Math.floorDiv(now - windowMillis, StepQuarters.QUARTER_MS) * StepQuarters.QUARTER_MS
        var written = 0
        for (ringId in repo.deviceStateRings()) {
            val samples = repo.deviceStatesSince(ringId, since).map { StepSample(it.recordedAt, it.steps) }
            val finished = StepQuarters.quarters(samples).filter { it.endMillis <= now }
            val records = writer.mapQuarterSteps(ringId, finished)
            if (records.isEmpty()) continue
            records.chunked(BATCH).forEach { writer.insert(it) }
            written += records.size
        }
        return written
    }

    /** Remove every steps record written so far — older versions wrote them on a wrong basis. */
    suspend fun deleteExportedSteps(): Boolean = runCatching {
        if (!settings.exportToHealthConnect || !hasPermissions()) return false
        writer.deleteAllSteps()
        true
    }.getOrDefault(false)

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
        if (!settings.estimateSleep || !settings.exportToHealthConnect || !hasPermissions()) return false
        writer.deleteAllSleepSessions()
        true
    }.getOrDefault(false)

    private companion object {
        const val BATCH = 500
        const val MAX_BATCHES = 200
        /** Above this the sensor is not reading a finger. */
        const val MAX_SKIN_TEMP = 42.0
        const val HOUR_MS = 3_600_000L
        const val SLEEP_WINDOW_SECONDS = 7 * 24 * 3600L
        /** Quarters change only while they are current; two days covers any late sync. */
        const val STEPS_WINDOW_MS = 2 * 24 * 3600_000L
        /** Two missing epochs still counts as the same night. */
        const val MAX_GAP_SECONDS = 450L
        const val MIN_SESSION_SECONDS = 30 * 60L
    }
}
