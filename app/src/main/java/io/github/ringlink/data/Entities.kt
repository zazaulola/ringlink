package io.github.ringlink.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One 2.5-minute epoch drained from the ring.
 *
 * The ring's counter is the primary key: history re-delivers a small overlap on every sync, and the
 * counter is the only stable identity. Storing raw counters (not wall-clock) means a later epoch
 * re-calibration re-dates existing rows correctly instead of corrupting them.
 */
@Entity(tableName = "epochs")
data class EpochEntity(
    @PrimaryKey val counter: Long,
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

@Entity(tableName = "sport")
data class SportEntity(
    @PrimaryKey val counter: Long,
    val heartRate: Int?,
    val steps: Int,
    val exportedAt: Long? = null,
)

/** A snapshot of the live descriptor the ring emits every 30-60 s while connected. */
@Entity(tableName = "device_state")
data class DeviceStateEntity(
    @PrimaryKey val recordedAt: Long,
    val batteryPercent: Int,
    val steps: Int,
    val skinTempA: Double,
    val skinTempB: Double,
    val batteryMillivolts: Int,
    val onCharger: Boolean,
    val exportedAt: Long? = null,
)
