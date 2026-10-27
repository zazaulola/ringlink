package io.github.ringlink.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import io.github.ringlink.data.DeviceStateEntity
import io.github.ringlink.data.QuarterSteps
import java.util.concurrent.ConcurrentHashMap
import io.github.ringlink.data.Settings
import io.github.ringlink.data.RingNames
import io.github.ringlink.data.SportEntity
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

    private val names = RingNames(context, Settings(context))
    private val devices = ConcurrentHashMap<String, Device>()

    /**
     * The device a record came from, per ring.
     *
     * Every record used to be stamped with one fixed "RingConn Gen 3", so readings from the Gen 2
     * were labelled as a Gen 3 and nothing in Health Connect could tell the two rings apart. Each
     * ring now appears as itself — "Gen 3 · F749", "Gen 2 · CEF0" — so the source of every reading
     * can be seen there too.
     */
    private fun deviceFor(ringId: String): Device = devices.getOrPut(ringId) {
        val ring = names.ring(ringId)
        Device(
            manufacturer = "RingConn",
            model = listOfNotNull(ring.generation?.let { "Gen $it" }, ring.shortName).joinToString(" · "),
            type = Device.TYPE_RING,
        )
    }


    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        HealthPermission.getWritePermission(RespiratoryRateRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(SkinTemperatureRecord::class),
    )

    /**
     * Skin temperature is behind a feature flag, so its availability has to be checked rather than
     * assumed. It is also the only honest home for this reading: the ring measures the surface of a
     * finger, and filing that as body temperature would misreport a 35 °C skin reading as a
     * dangerously low core temperature.
     */
    fun skinTemperatureSupported(): Boolean = runCatching {
        client().features.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

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
            // The ring's identity belongs in the key: two rings' counters collide, and without it
            // one ring's readings would overwrite the other's inside Health Connect.
            val id = { kind: String -> "epoch-${row.ringId}-$kind-${row.counter}" }

            row.heartRate?.let { hr ->
                out += HeartRateRecord(
                    startTime = start,
                    startZoneOffset = zone,
                    endTime = end,
                    endZoneOffset = zone,
                    samples = listOf(HeartRateRecord.Sample(time = start, beatsPerMinute = hr.toLong())),
                    metadata = meta(id("hr"), row.ringId),
                )
            }
            row.hrvRmssd?.let { hrv ->
                out += HeartRateVariabilityRmssdRecord(
                    time = start,
                    zoneOffset = zone,
                    heartRateVariabilityMillis = hrv.toDouble(),
                    metadata = meta(id("hrv"), row.ringId),
                )
            }
            row.spo2?.let { spo2 ->
                out += OxygenSaturationRecord(
                    time = start,
                    zoneOffset = zone,
                    percentage = Percentage(spo2.toDouble()),
                    metadata = meta(id("spo2"), row.ringId),
                )
            }
            row.respiratoryRate?.let { rr ->
                out += RespiratoryRateRecord(
                    time = start,
                    zoneOffset = zone,
                    rate = rr,
                    metadata = meta(id("rr"), row.ringId),
                )
            }
        }
        return out
    }

    /**
     * Steps come from the live descriptor's running daily total, so consecutive snapshots are
     * differenced into interval records. A decrease means the ring rolled over to a new day.
     */
    /**
     * The ring's own activity log: steps and heart rate per interval, from the sport channel.
     *
     * This is the better source for steps. The descriptor-derived counterpart below only sees the
     * periods the phone happened to be connected, whereas the ring records this whether or not
     * anything was listening.
     */
    fun mapSport(rows: List<SportEntity>, clock: RingClock): List<Record> {
        val out = ArrayList<Record>()
        val ordered = rows.sortedWith(compareBy({ it.ringId }, { it.counter }))
        for ((index, row) in ordered.withIndex()) {
            val start = Instant.ofEpochSecond(clock.toUnixSeconds(row.counter))
            // Measure the interval from the data rather than assuming it: the sport channel's
            // cadence is not something this project has confirmed, and a wrong constant would
            // stretch or squash every interval in Health Connect. The gap to the next record from
            // the same ring is the interval; a lone or distant record falls back to a short,
            // conservative span so it cannot overlap its neighbours.
            val next = ordered.getOrNull(index + 1)?.takeIf { it.ringId == row.ringId }
            val gap = next?.let { clock.toUnixSeconds(it.counter) - start.epochSecond }
            val span = gap?.takeIf { it in 1..MAX_SPORT_SPAN_SECONDS } ?: FALLBACK_SPORT_SPAN_SECONDS
            val end = start.plusSeconds(span)
            if (row.steps > 0) {
                out += StepsRecord(
                    startTime = start,
                    startZoneOffset = zoneFor(start),
                    endTime = end,
                    endZoneOffset = zoneFor(end),
                    count = row.steps.toLong(),
                    metadata = meta("sport-steps-${row.ringId}-${row.counter}", row.ringId),
                )
            }
            row.heartRate?.let { hr ->
                out += HeartRateRecord(
                    startTime = start,
                    startZoneOffset = zoneFor(start),
                    endTime = end,
                    endZoneOffset = zoneFor(end),
                    samples = listOf(HeartRateRecord.Sample(time = start, beatsPerMinute = hr.toLong())),
                    metadata = meta("sport-hr-${row.ringId}-${row.counter}", row.ringId),
                )
            }
        }
        return out
    }

    /**
     * Steps as one record per ring per quarter-hour.
     *
     * Replaces differences between consecutive descriptor readings, which lost the steps walked
     * after each quarter's reset until the next reading, and — fed a batch mixing two rings —
     * subtracted one ring's count from the other's. A quarter's record carries a deterministic id,
     * so writing it again replaces it rather than adding to it.
     */
    fun mapQuarterSteps(ringId: String, quarters: List<QuarterSteps>): List<Record> =
        quarters.filter { it.steps > 0 }.map { q ->
            val start = Instant.ofEpochMilli(q.startMillis)
            val end = Instant.ofEpochMilli(q.endMillis)
            StepsRecord(
                startTime = start,
                startZoneOffset = zoneFor(start),
                endTime = end,
                endZoneOffset = zoneFor(end),
                count = q.steps.toLong(),
                metadata = meta("steps-q-$ringId-${q.startMillis}", ringId),
            )
        }

    /** Remove the steps records this app wrote in a span; others' steps are untouched. */
    suspend fun deleteSteps(from: Instant, to: Instant) {
        client().deleteRecords(StepsRecord::class, TimeRangeFilter.between(from, to))
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
     * Skin temperature for one ring and one finished hour.
     *
     * One record per hour with an id taken from the ring and the hour, so writing the same hour again
     * replaces it. The earlier records were cut from whatever batch of rows happened to be waiting,
     * and named after its first row — so the same readings exported in a different batch, as any
     * re-export does, landed as a second, overlapping record instead of replacing the first.
     *
     * The baseline is the hour's own median, which makes each record complete on its own:
     * baseline plus delta is the reading.
     */
    fun mapSkinTemperatureHour(ringId: String, hourStartMillis: Long, readings: List<DeviceStateEntity>): Record? {
        if (readings.isEmpty()) return null
        val ordered = readings.sortedBy { it.recordedAt }
        val values = ordered.map { it.skinTempA }.sorted()
        val baseline = values[values.size / 2]
        val start = Instant.ofEpochMilli(hourStartMillis)
        // Every reading in the hour is strictly before its end, as Health Connect requires.
        val end = start.plusMillis(HOUR_MS)
        return SkinTemperatureRecord(
            startTime = start,
            startZoneOffset = zoneFor(start),
            endTime = end,
            endZoneOffset = zoneFor(end),
            metadata = meta("skintemp-h-$ringId-$hourStartMillis", ringId),
            deltas = ordered.map {
                SkinTemperatureRecord.Delta(
                    time = Instant.ofEpochMilli(it.recordedAt),
                    delta = TemperatureDelta.celsius(it.skinTempA - baseline),
                )
            },
            baseline = Temperature.celsius(baseline),
            measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_FINGER,
        )
    }

    /** Remove every skin-temperature record this app wrote; others' are untouched. */
    suspend fun deleteSkinTemperature(from: Instant, to: Instant) {
        client().deleteRecords(SkinTemperatureRecord::class, TimeRangeFilter.between(from, to))
    }

    /**
     * A sleep session covering a run of sleep-channel epochs.
     *
     * No stages are attached on purpose: the ring never transmits a hypnogram — the vendor app
     * computes stages itself — so claiming Light/Deep/REM here would be inventing data.
     */
    fun sleepSession(
        ringId: String,
        startCounter: Long,
        endCounter: Long,
        clock: RingClock,
    ): SleepSessionRecord {
        val start = Instant.ofEpochSecond(clock.toUnixSeconds(startCounter))
        val end = Instant.ofEpochSecond(clock.toUnixSeconds(endCounter))
        return SleepSessionRecord(
            startTime = start,
            startZoneOffset = zoneFor(start),
            endTime = end,
            endZoneOffset = zoneFor(end),
            title = "Estimated from movement and heart rate",
            metadata = meta("sleep-$ringId-$startCounter", ringId),
        )
    }

    /**
     * Health Connect upserts on `clientRecordId`, keeping whichever write has the higher
     * `clientRecordVersion`. Versioning by wall-clock means a later export always wins, so a
     * re-export can correct records already written — for instance after a clock correction moved
     * their timestamps. Without a rising version the corrected copy would simply be ignored.
     */
    private fun meta(clientRecordId: String, ringId: String) = Metadata.autoRecorded(
        device = deviceFor(ringId),
        clientRecordId = clientRecordId,
        // Stamped per write: Health Connect keeps the higher version, so a record written again
        // later — a corrected quarter, a re-export — always replaces the earlier copy.
        clientRecordVersion = System.currentTimeMillis(),
    )


    /** Written explicitly so readers can reconstruct local civil time for backfilled history. */
    private fun zoneFor(instant: Instant) = ZoneId.systemDefault().rules.getOffset(instant)

    private companion object {
        const val CHUNK = 500
        const val HOUR_MS = 3_600_000L
        const val MAX_SPORT_SPAN_SECONDS = 300L
        const val FALLBACK_SPORT_SPAN_SECONDS = 10L
        const val EPOCH_SECONDS = 150L
    }
}
