package io.github.ringlink.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RingDao {

    // IGNORE, not REPLACE: a re-synced overlap must not clobber a row we already exported.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpochs(rows: List<EpochEntity>): List<Long>

    /**
     * Fill in fields a later channel supplied for a row that already exists.
     *
     * The sleep and all-day channels both describe the same instants but carry different fields —
     * one layout has SpO2 and HRV, the other does not — and the primary key is (ring, counter) with
     * the channel deliberately outside it, so a plain insert silently discarded whichever channel
     * arrived second. COALESCE keeps the existing value wherever there is one and only fills gaps,
     * so this can never overwrite good data with a null.
     *
     * `exportedAt` is cleared when something was actually added, so an already-exported row is
     * re-sent with its new fields; Health Connect upserts on the same client record id.
     */
    @Query(
        """
        UPDATE epochs SET
            heartRate = COALESCE(heartRate, :heartRate),
            hrvRmssd = COALESCE(hrvRmssd, :hrvRmssd),
            respiratoryRate = COALESCE(respiratoryRate, :respiratoryRate),
            spo2 = COALESCE(spo2, :spo2),
            exportedAt = CASE
                WHEN (heartRate IS NULL AND :heartRate IS NOT NULL)
                  OR (hrvRmssd IS NULL AND :hrvRmssd IS NOT NULL)
                  OR (respiratoryRate IS NULL AND :respiratoryRate IS NOT NULL)
                  OR (spo2 IS NULL AND :spo2 IS NOT NULL)
                THEN NULL ELSE exportedAt END
        WHERE ringId = :ringId AND counter = :counter
        """,
    )
    suspend fun mergeEpoch(
        ringId: String,
        counter: Long,
        heartRate: Int?,
        hrvRmssd: Int?,
        respiratoryRate: Double?,
        spo2: Int?,
    )

    /** Insert what is new, and merge the rest into the rows already there. */
    @Transaction
    suspend fun upsertEpochs(rows: List<EpochEntity>) {
        val ids = insertEpochs(rows)
        rows.forEachIndexed { i, row ->
            if (ids.getOrNull(i) == -1L) {
                mergeEpoch(
                    ringId = row.ringId,
                    counter = row.counter,
                    heartRate = row.heartRate,
                    hrvRmssd = row.hrvRmssd,
                    respiratoryRate = row.respiratoryRate,
                    spo2 = row.spo2,
                )
            }
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSport(rows: List<SportEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeviceState(row: DeviceStateEntity)

    @Query("SELECT * FROM epochs WHERE exportedAt IS NULL ORDER BY counter LIMIT :limit")
    suspend fun unexportedEpochs(limit: Int): List<EpochEntity>

    @Query("SELECT * FROM sport WHERE exportedAt IS NULL ORDER BY counter LIMIT :limit")
    suspend fun unexportedSport(limit: Int): List<SportEntity>

    @Query("SELECT * FROM device_state WHERE exportedAt IS NULL ORDER BY recordedAt LIMIT :limit")
    suspend fun unexportedDeviceStates(limit: Int): List<DeviceStateEntity>

    @Query("UPDATE epochs SET exportedAt = :at WHERE ringId = :ringId AND counter IN (:counters)")
    suspend fun markEpochsExported(ringId: String, counters: List<Long>, at: Long)

    @Query("UPDATE sport SET exportedAt = :at WHERE ringId = :ringId AND counter IN (:counters)")
    suspend fun markSportExported(ringId: String, counters: List<Long>, at: Long)

    @Query("UPDATE device_state SET exportedAt = :at WHERE ringId = :ringId AND recordedAt IN (:times)")
    suspend fun markDeviceStatesExported(ringId: String, times: List<Long>, at: Long)

    @Query("UPDATE epochs SET exportedAt = NULL")
    suspend fun clearEpochExports()

    @Query("UPDATE sport SET exportedAt = NULL")
    suspend fun clearSportExports()

    @Query("UPDATE device_state SET exportedAt = NULL")
    suspend fun clearDeviceStateExports()

    @Query("SELECT COUNT(*) FROM epochs")
    fun epochCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM epochs WHERE exportedAt IS NULL")
    fun pendingExportCount(): Flow<Int>

    @Query("SELECT MAX(counter) FROM epochs WHERE ringId = :ringId")
    suspend fun newestCounter(ringId: String): Long?

    @Query("SELECT * FROM device_state WHERE ringId = :ringId ORDER BY recordedAt DESC LIMIT 1")
    fun latestDeviceState(ringId: String): Flow<DeviceStateEntity?>

    @Query("SELECT * FROM epochs WHERE counter BETWEEN :from AND :to ORDER BY counter")
    suspend fun epochsBetween(from: Long, to: Long): List<EpochEntity>

    /** Everything newer than a cursor, oldest first — what the history screen charts. */
    @Query("SELECT * FROM epochs WHERE counter >= :since ORDER BY counter")
    fun epochsSince(since: Long): Flow<List<EpochEntity>>

    /** Sleep detection needs a window of context, not just the rows waiting to be exported. */
    @Query("SELECT * FROM epochs WHERE ringId = :ringId AND counter >= :since ORDER BY counter")
    suspend fun epochsForRingSince(ringId: String, since: Long): List<EpochEntity>

    /** Steps the ring itself logged in a counter range — the sport channel, not descriptor deltas. */
    @Query("SELECT COALESCE(SUM(steps), 0) FROM sport WHERE counter BETWEEN :from AND :to")
    fun stepsBetween(from: Long, to: Long): Flow<Int>

    /**
     * Resting heart rate: the top of the lowest [sampleCount] still readings.
     *
     * Taking the outright minimum would report a single artifact as the day's resting rate, so this
     * is deliberately a low percentile instead — MAX over the lowest slice is the slice's own
     * ceiling, which is robust to one bad sample but still firmly in the resting range.
     */
    @Query(
        """
        SELECT MAX(heartRate) FROM (
            SELECT heartRate FROM epochs
            WHERE counter BETWEEN :from AND :to AND heartRate IS NOT NULL AND motionSum <= 6
            ORDER BY heartRate LIMIT :sampleCount
        )
        """,
    )
    fun restingHeartRateBetween(from: Long, to: Long, sampleCount: Int): Flow<Int?>

    @Query("SELECT * FROM epochs WHERE counter > :after ORDER BY counter LIMIT :limit")
    suspend fun epochsAfter(after: Long, limit: Int): List<EpochEntity>

    @Query("SELECT * FROM device_state ORDER BY recordedAt")
    suspend fun allDeviceStates(): List<DeviceStateEntity>

    @Query("SELECT * FROM sport ORDER BY counter")
    suspend fun allSport(): List<SportEntity>

    @Query("SELECT DISTINCT ringId FROM epochs")
    suspend fun knownRings(): List<String>

    @Query("SELECT * FROM device_state WHERE ringId = :ringId AND recordedAt >= :since ORDER BY recordedAt")
    suspend fun deviceStatesSince(ringId: String, since: Long): List<DeviceStateEntity>

    /** Live-descriptor history for the charts: temperature, battery and steps. */
    @Query("SELECT * FROM device_state WHERE recordedAt >= :since ORDER BY recordedAt")
    fun deviceStatesSinceFlow(since: Long): Flow<List<DeviceStateEntity>>

    @Query(
        """
        SELECT MIN(counter) AS firstCounter,
               MAX(counter) AS lastCounter,
               COUNT(*) AS samples,
               AVG(heartRate) AS avgHeartRate,
               MIN(heartRate) AS minHeartRate,
               MAX(heartRate) AS maxHeartRate,
               AVG(hrvRmssd) AS avgHrv,
               AVG(spo2) AS avgSpo2,
               MIN(spo2) AS minSpo2,
               AVG(respiratoryRate) AS avgRespiratoryRate
        FROM epochs WHERE counter >= :since
        """,
    )
    fun summarySince(since: Long): Flow<Summary?>
}

/** Aggregate over a window, computed in SQL so the UI never walks the whole table. */
data class Summary(
    val firstCounter: Long?,
    val lastCounter: Long?,
    val samples: Int,
    val avgHeartRate: Double?,
    val minHeartRate: Int?,
    val maxHeartRate: Int?,
    val avgHrv: Double?,
    val avgSpo2: Double?,
    val minSpo2: Int?,
    val avgRespiratoryRate: Double?,
)
