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
}
