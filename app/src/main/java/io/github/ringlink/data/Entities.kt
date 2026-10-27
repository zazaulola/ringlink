package io.github.ringlink.data

import androidx.room.Entity
import androidx.room.Index

/**
 * One 2.5-minute epoch drained from the ring.
 *
 * The ring's counter is the primary key: history re-delivers a small overlap on every sync, and the
 * counter is the only stable identity. Storing raw counters (not wall-clock) means a later epoch
 * re-calibration re-dates existing rows correctly instead of corrupting them.
 */
@Entity(tableName = "epochs", primaryKeys = ["ringId", "counter"])
data class EpochEntity(
    /**
     * Which ring produced this. Part of the key, not decoration: two rings number their epochs from
     * the same base, so at the same moment their counters nearly coincide — keyed on the counter
     * alone, the second ring's data would be silently discarded.
     */
    val ringId: String,
    val counter: Long,
    val channel: Int,
    val layout: String,
    val heartRate: Int?,
    val hrvRmssd: Int?,
    val confidence: Int,
    val respiratoryRate: Double?,
    val spo2: Int?,
    val motionSum: Int,
    val exportedAt: Long? = null,
)

@Entity(tableName = "sport", primaryKeys = ["ringId", "counter"])
data class SportEntity(
    val ringId: String,
    val counter: Long,
    val heartRate: Int?,
    val steps: Int,
    val exportedAt: Long? = null,
)

/** A snapshot of the live descriptor the ring emits every 30-60 s while connected. */
@Entity(tableName = "device_state", primaryKeys = ["ringId", "recordedAt"])
data class DeviceStateEntity(
    val ringId: String,
    val recordedAt: Long,
    val batteryPercent: Int,
    val steps: Int,
    val skinTempA: Double,
    val skinTempB: Double,
    val batteryMillivolts: Int,
    val onCharger: Boolean,
    /**
     * The ring's raw state byte.
     *
     * Only 0x04 (charging) is known for certain. The rest is kept because the mapping can only be
     * learned by correlating these values against periods the ring was known to be worn — and that
     * is impossible if the byte is discarded on arrival, which it was.
     */
    val state: Int = 0,
    /**
     * The charging case's own charge, as reported by a ring docked in it; null while the ring is
     * out of the case. The case has no radio, so this is the only way it is ever seen — and storing
     * it is what lets the app still say how the case was doing after the ring is taken out.
     */
    val caseBattery: Int? = null,
    /** Whether the case itself was plugged in; null while the ring is out of the case. */
    val caseCharging: Boolean? = null,
    val exportedAt: Long? = null,
)

/**
 * Steps in one quarter-hour from one ring, derived from its [DeviceStateEntity] readings.
 *
 * Derived, not source data: it can always be rebuilt from device_state, so it is left out of
 * backups and rebuilt after a restore. It is kept because the step tables reach back over the
 * whole period of use, and a year of readings is some 700 000 rows to re-derive on every screen.
 */
@Entity(tableName = "step_quarters", primaryKeys = ["ringId", "startMillis"], indices = [Index("startMillis")])
data class StepQuarterEntity(
    val ringId: String,
    /** Phone wall-clock start of the quarter. */
    val startMillis: Long,
    val steps: Int,
    /** Whether any reading in the quarter came from the ring on a finger. */
    val worn: Boolean,
)

/** The fields of a reading that steps are derived from. */
data class StepReading(
    val recordedAt: Long,
    val steps: Int,
    val onCharger: Boolean,
    val skinTempA: Double,
)
