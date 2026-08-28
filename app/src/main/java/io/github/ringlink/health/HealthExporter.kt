package io.github.ringlink.health

import android.content.Context
import androidx.health.connect.client.records.Record
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

        if (records.isNotEmpty()) writer.insert(records)

        // Only mark exported once the insert has returned — a failure leaves the rows pending.
        repo.markExported(
            epochs = epochs.map { it.counter },
            sport = emptyList(),
            states = states.map { it.recordedAt },
        )
        return epochs.size + states.size
    }

    /**
     * Sleep sessions are deliberately NOT exported.
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
        /** Two missing epochs still counts as the same night. */
        const val MAX_GAP_SECONDS = 450L
        const val MIN_SESSION_SECONDS = 30 * 60L
    }
}
