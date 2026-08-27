package io.github.ringlink.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RingDao {

    // IGNORE, not REPLACE: a re-synced overlap must not clobber a row we already exported.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpochs(rows: List<EpochEntity>): List<Long>

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

    @Query("UPDATE epochs SET exportedAt = :at WHERE counter IN (:counters)")
    suspend fun markEpochsExported(counters: List<Long>, at: Long)

    @Query("UPDATE sport SET exportedAt = :at WHERE counter IN (:counters)")
    suspend fun markSportExported(counters: List<Long>, at: Long)

    @Query("UPDATE device_state SET exportedAt = :at WHERE recordedAt IN (:times)")
    suspend fun markDeviceStatesExported(times: List<Long>, at: Long)

    @Query("SELECT COUNT(*) FROM epochs")
    fun epochCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM epochs WHERE exportedAt IS NULL")
    fun pendingExportCount(): Flow<Int>

    @Query("SELECT MAX(counter) FROM epochs")
    suspend fun newestCounter(): Long?

    @Query("SELECT * FROM device_state ORDER BY recordedAt DESC LIMIT 1")
    fun latestDeviceState(): Flow<DeviceStateEntity?>

    @Query("SELECT * FROM epochs WHERE counter BETWEEN :from AND :to ORDER BY counter")
    suspend fun epochsBetween(from: Long, to: Long): List<EpochEntity>

    /** Everything newer than a cursor, oldest first — what the history screen charts. */
    @Query("SELECT * FROM epochs WHERE counter >= :since ORDER BY counter")
    fun epochsSince(since: Long): Flow<List<EpochEntity>>

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
