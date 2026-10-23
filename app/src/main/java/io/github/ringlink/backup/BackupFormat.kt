package io.github.ringlink.backup

import io.github.ringlink.data.DeviceStateEntity
import io.github.ringlink.data.EpochEntity
import io.github.ringlink.data.SportEntity
import org.json.JSONObject

/**
 * The backup file, described.
 *
 * A zip holding one manifest, the settings, and each table as JSON lines — one row per line, so a
 * file of any size streams in and out without being held whole in memory. Rows are written field
 * by field rather than as a database file: a SQLite file copied while the app is writing can be
 * internally inconsistent, and a row format survives the schema changing between the app version
 * that wrote the backup and the one restoring it.
 *
 * The manifest carries a row count and a SHA-256 for every file, so a backup that was cut short —
 * a cloud folder that had not finished syncing, say — is refused instead of half-restored.
 */
object BackupFormat {

    /** Bumped only when an older app could no longer read what a newer one writes. */
    const val VERSION = 1

    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val EPOCHS = "epochs.jsonl"
    const val DEVICE_STATE = "device_state.jsonl"
    const val SPORT = "sport.jsonl"

    val TABLES = listOf(EPOCHS, DEVICE_STATE, SPORT)

    // ---- rows -------------------------------------------------------------------------------
    //
    // `exportedAt` is deliberately not carried. Whether a row has reached Health Connect is a
    // fact about one phone; restored somewhere new, it has not, and writing it again is harmless
    // because Health Connect replaces records by their id.

    fun encode(e: EpochEntity): String = JSONObject()
        .put("ring", e.ringId)
        .put("counter", e.counter)
        .put("channel", e.channel)
        .put("layout", e.layout)
        .putOpt("hr", e.heartRate)
        .putOpt("hrv", e.hrvRmssd)
        .put("confidence", e.confidence)
        .putOpt("rr", e.respiratoryRate)
        .putOpt("spo2", e.spo2)
        .put("motion", e.motionSum)
        .toString()

    fun decodeEpoch(line: String): EpochEntity = JSONObject(line).run {
        EpochEntity(
            ringId = getString("ring"),
            counter = getLong("counter"),
            channel = getInt("channel"),
            layout = getString("layout"),
            heartRate = intOrNull("hr"),
            hrvRmssd = intOrNull("hrv"),
            confidence = getInt("confidence"),
            respiratoryRate = doubleOrNull("rr"),
            spo2 = intOrNull("spo2"),
            motionSum = getInt("motion"),
        )
    }

    fun encode(d: DeviceStateEntity): String = JSONObject()
        .put("ring", d.ringId)
        .put("at", d.recordedAt)
        .put("battery", d.batteryPercent)
        .put("steps", d.steps)
        .put("tempA", d.skinTempA)
        .put("tempB", d.skinTempB)
        .put("mv", d.batteryMillivolts)
        .put("charging", d.onCharger)
        .put("state", d.state)
        .putOpt("caseBattery", d.caseBattery)
        .putOpt("caseCharging", d.caseCharging)
        .toString()

    fun decodeDeviceState(line: String): DeviceStateEntity = JSONObject(line).run {
        DeviceStateEntity(
            ringId = getString("ring"),
            recordedAt = getLong("at"),
            batteryPercent = getInt("battery"),
            steps = getInt("steps"),
            skinTempA = getDouble("tempA"),
            skinTempB = getDouble("tempB"),
            batteryMillivolts = getInt("mv"),
            onCharger = getBoolean("charging"),
            state = optInt("state", 0),
            caseBattery = intOrNull("caseBattery"),
            caseCharging = if (has("caseCharging") && !isNull("caseCharging")) getBoolean("caseCharging") else null,
        )
    }

    fun encode(s: SportEntity): String = JSONObject()
        .put("ring", s.ringId)
        .put("counter", s.counter)
        .putOpt("hr", s.heartRate)
        .put("steps", s.steps)
        .toString()

    fun decodeSport(line: String): SportEntity = JSONObject(line).run {
        SportEntity(
            ringId = getString("ring"),
            counter = getLong("counter"),
            heartRate = intOrNull("hr"),
            steps = getInt("steps"),
        )
    }

    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.doubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
}

/** What a backup says about itself. */
data class BackupManifest(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    /** Ring address to name, so a restore can say what it holds before anything is touched. */
    val rings: Map<String, String>,
    /** File name to its row count and SHA-256. */
    val files: Map<String, FileInfo>,
) {
    data class FileInfo(val rows: Long, val sha256: String)

    fun rows(file: String): Long = files[file]?.rows ?: 0

    fun toJson(): String {
        val ringsJson = JSONObject()
        rings.forEach { (address, name) -> ringsJson.put(address, name) }
        val filesJson = JSONObject()
        files.forEach { (name, info) ->
            filesJson.put(name, JSONObject().put("rows", info.rows).put("sha256", info.sha256))
        }
        return JSONObject()
            .put("format", formatVersion)
            .put("createdAt", createdAt)
            .put("app", appVersion)
            .put("rings", ringsJson)
            .put("files", filesJson)
            .toString(2)
    }

    companion object {
        fun parse(json: String): BackupManifest {
            val o = JSONObject(json)
            val rings = o.optJSONObject("rings")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) } }
                ?: emptyMap()
            val files = o.getJSONObject("files").let { f ->
                f.keys().asSequence().associateWith {
                    val info = f.getJSONObject(it)
                    FileInfo(info.getLong("rows"), info.getString("sha256"))
                }
            }
            return BackupManifest(
                formatVersion = o.getInt("format"),
                createdAt = o.getLong("createdAt"),
                appVersion = o.optString("app", "?"),
                rings = rings,
                files = files,
            )
        }
    }
}
