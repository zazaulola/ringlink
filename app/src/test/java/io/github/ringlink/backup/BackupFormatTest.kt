package io.github.ringlink.backup

import io.github.ringlink.data.DeviceStateEntity
import io.github.ringlink.data.EpochEntity
import io.github.ringlink.data.SportEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A backup is only as good as its round trip. Every field must come back exactly, including the
 * ones that are often null — an epoch off the finger has no pulse, a ring out of its case has no
 * case reading — because a restore that quietly turns a null into 0 invents health data.
 */
class BackupFormatTest {

    private fun epoch(hr: Int?, spo2: Int?, rr: Double?) = EpochEntity(
        ringId = "F8:79:99:21:F7:49", counter = 210_141_032L, channel = 3, layout = "SLEEP_VITALS",
        heartRate = hr, hrvRmssd = hr?.let { 41 }, confidence = 7, respiratoryRate = rr,
        spo2 = spo2, motionSum = 5, exportedAt = 1_760_000_000_000L,
    )

    @Test fun `an epoch survives the round trip`() {
        val e = epoch(hr = 62, spo2 = 96, rr = 15.5)
        assertEquals(e.copy(exportedAt = null), BackupFormat.decodeEpoch(BackupFormat.encode(e)))
    }

    @Test fun `an empty epoch keeps its nulls`() {
        val back = BackupFormat.decodeEpoch(BackupFormat.encode(epoch(hr = null, spo2 = null, rr = null)))
        assertNull(back.heartRate)
        assertNull(back.spo2)
        assertNull(back.respiratoryRate)
        assertNull(back.hrvRmssd)
    }

    /** Whether a row reached Health Connect belongs to one phone, and is not carried across. */
    @Test fun `the export mark is not carried`() {
        assertNull(BackupFormat.decodeEpoch(BackupFormat.encode(epoch(70, 97, 14.0))).exportedAt)
    }

    @Test fun `a device reading survives the round trip, in or out of the case`() {
        val docked = DeviceStateEntity(
            ringId = "F8:79:99:F8:CE:F0", recordedAt = 1_761_000_000_123L, batteryPercent = 93,
            steps = 66, skinTempA = 35.3, skinTempB = 35.1, batteryMillivolts = 4180,
            onCharger = true, state = 4, caseBattery = 90, caseCharging = true,
        )
        assertEquals(docked, BackupFormat.decodeDeviceState(BackupFormat.encode(docked)))
        val worn = docked.copy(onCharger = false, state = 2, caseBattery = null, caseCharging = null)
        assertEquals(worn, BackupFormat.decodeDeviceState(BackupFormat.encode(worn)))
    }

    @Test fun `a sport interval survives the round trip`() {
        val s = SportEntity(ringId = "F8:79:99:21:F7:49", counter = 210_000_150L, heartRate = null, steps = 14)
        assertEquals(s, BackupFormat.decodeSport(BackupFormat.encode(s)))
    }

    @Test fun `the manifest survives the round trip`() {
        val m = BackupManifest(
            formatVersion = 1, createdAt = 1_761_200_000_000L, appVersion = "0.1.0",
            rings = mapOf("F8:79:99:21:F7:49" to "RingConn Gen3-F749"),
            files = mapOf("epochs.jsonl" to BackupManifest.FileInfo(35_757, "ab".repeat(32))),
        )
        assertEquals(m, BackupManifest.parse(m.toJson()))
    }

    @Test fun `weather hours survive a round trip`() {
        val w = io.github.ringlink.data.WeatherHourEntity(1_793_000_000_000L, -3.15, 1_792_999_000_000L)
        assertEquals(w, BackupFormat.decodeWeather(BackupFormat.encode(w)))
    }

    @Test fun `signal strength survives a round trip, and old rows have none`() {
        val d = io.github.ringlink.data.DeviceStateEntity("F749", 1L, 50, 10, 33.0, 33.1, 3900, false, rssi = -61)
        assertEquals(-61, BackupFormat.decodeDeviceState(BackupFormat.encode(d)).rssi)
        val old = BackupFormat.encode(d.copy(rssi = null))
        assertEquals(null, BackupFormat.decodeDeviceState(old).rssi)
    }
}
