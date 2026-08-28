package io.github.ringlink.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Percentage
import io.github.ringlink.data.DeviceStateEntity
import io.github.ringlink.data.EpochEntity
import io.github.ringlink.protocol.RingClock
import java.time.Instant
import java.time.ZoneId

/**
 * Exports locally stored ring data into Health Connect — the on-device health store that replaced
 * Google Fit (whose APIs are deprecated and supported only to the end of 2026).
 *
 * Everything is written with a deterministic `clientRecordId` derived from the ring's own counter,
 * so re-syncing an overlapping range upserts instead of duplicating. Health Connect stamps the
 * writing app as the data origin, and the user can inspect or delete any of it.
 */
class HealthConnectWriter(private val context: Context) {

    private val ringDevice = Device(
        manufacturer = "RingConn",
        model = "Gen 3",
        type = Device.TYPE_RING,
    )

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        HealthPermission.getWritePermission(RespiratoryRateRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
    )

    fun availability(): Int = HealthConnectClient.getSdkStatus(context)

    fun isAvailable(): Boolean = availability() == HealthConnectClient.SDK_AVAILABLE

    fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun grantedPermissions(): Set<String> =
        client().permissionController.getGrantedPermissions()

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(permissions)

    /**
     * Insert in batches. Health Connect caps a single call at 1000 records and the whole batch is
     * transactional, so a smaller chunk also limits the blast radius of one bad record.
     */
    suspend fun insert(records: List<Record>) {
        if (records.isEmpty()) return
        val c = client()
        records.chunked(CHUNK).forEach { c.insertRecords(it) }
    }

    /** Map stored epochs to Health Connect records. Only decoded, trustworthy fields are exported. */
    fun mapEpochs(rows: List<EpochEntity>, clock: RingClock): List<Record> {
        val out = ArrayList<Record>(rows.size * 2)
        for (row in rows) {
            val start = Instant.ofEpochSecond(clock.toUnixSeconds(row.counter))
            val end = start.plusSeconds(EPOCH_SECONDS)
            val zone = zoneFor(start)
            val id = { kind: String -> "epoch-$kind-${row.counter}" }

            row.heartRate?.let { hr ->
                out += HeartRateRecord(
                    startTime = start,
                    startZoneOffset = zone,
                    endTime = end,
                    endZoneOffset = zone,
                    samples = listOf(HeartRateRecord.Sample(time = start, beatsPerMinute = hr.toLong())),
                    metadata = meta(id("hr")),
                )
            }
            row.hrvRmssd?.let { hrv ->
                out += HeartRateVariabilityRmssdRecord(
                    time = start,
                    zoneOffset = zone,
                    heartRateVariabilityMillis = hrv.toDouble(),
                    metadata = meta(id("hrv")),
                )
            }
            row.spo2?.let { spo2 ->
                out += OxygenSaturationRecord(
                    time = start,
                    zoneOffset = zone,
                    percentage = Percentage(spo2.toDouble()),
                    metadata = meta(id("spo2")),
                )
            }
            row.respiratoryRate?.let { rr ->
                out += RespiratoryRateRecord(
                    time = start,
                    zoneOffset = zone,
                    rate = rr,
                    metadata = meta(id("rr")),
                )
            }
        }
        return out
    }

    /**
     * Steps come from the live descriptor's running daily total, so consecutive snapshots are
     * differenced into interval records. A decrease means the ring rolled over to a new day.
     */
    fun mapSteps(rows: List<DeviceStateEntity>): List<Record> {
        val out = ArrayList<Record>()
        for (i in 1 until rows.size) {
            val prev = rows[i - 1]
            val cur = rows[i]
            val delta = cur.steps - prev.steps
            if (delta <= 0) continue
            val start = Instant.ofEpochMilli(prev.recordedAt)
            val end = Instant.ofEpochMilli(cur.recordedAt)
            if (!end.isAfter(start)) continue
            out += StepsRecord(
                startTime = start,
                startZoneOffset = zoneFor(start),
                endTime = end,
                endZoneOffset = zoneFor(end),
                count = delta.toLong(),
                metadata = meta("steps-${cur.recordedAt}"),
            )
        }
        return out
    }

    /**
     * Remove every sleep session this app wrote. An app may only delete its own records, so a
     * user's other sleep data is untouched.
     */
    suspend fun deleteAllSleepSessions() {
        client().deleteRecords(
            SleepSessionRecord::class,
            TimeRangeFilter.between(
                Instant.ofEpochSecond(0),
                Instant.now().plusSeconds(86_400),
            ),
        )
    }

    /**
     * A sleep session covering a run of sleep-channel epochs.
     *
     * No stages are attached on purpose: the ring never transmits a hypnogram — the vendor app
     * computes stages itself — so claiming Light/Deep/REM here would be inventing data.
     */
    fun sleepSession(startCounter: Long, endCounter: Long, clock: RingClock): SleepSessionRecord {
        val start = Instant.ofEpochSecond(clock.toUnixSeconds(startCounter))
        val end = Instant.ofEpochSecond(clock.toUnixSeconds(endCounter) + EPOCH_SECONDS)
        return SleepSessionRecord(
            startTime = start,
            startZoneOffset = zoneFor(start),
            endTime = end,
            endZoneOffset = zoneFor(end),
            metadata = meta("sleep-$startCounter"),
        )
    }

    /**
     * Health Connect upserts on `clientRecordId`, keeping whichever write has the higher
     * `clientRecordVersion`. Versioning by wall-clock means a later export always wins, so a
     * re-export can correct records already written — for instance after a clock correction moved
     * their timestamps. Without a rising version the corrected copy would simply be ignored.
     */
    private fun meta(clientRecordId: String) = Metadata.autoRecorded(
        device = ringDevice,
        clientRecordId = clientRecordId,
        clientRecordVersion = exportVersion,
    )

    /** One version stamp per writer instance, so a whole re-export shares it. */
    private val exportVersion: Long = System.currentTimeMillis()

    /** Written explicitly so readers can reconstruct local civil time for backfilled history. */
    private fun zoneFor(instant: Instant) = ZoneId.systemDefault().rules.getOffset(instant)

    private companion object {
        const val CHUNK = 500
        const val EPOCH_SECONDS = 150L
    }
}
