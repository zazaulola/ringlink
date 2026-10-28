package io.github.ringlink.backup

import android.content.Context
import androidx.room.withTransaction
import io.github.ringlink.BuildConfig
import io.github.ringlink.L
import io.github.ringlink.data.RingDao
import io.github.ringlink.data.SportEntity
import io.github.ringlink.data.EpochEntity
import io.github.ringlink.data.DeviceStateEntity
import io.github.ringlink.data.RingDatabase
import io.github.ringlink.data.RingNames
import io.github.ringlink.data.Settings
import io.github.ringlink.data.WeatherHourEntity
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Writes a backup.
 *
 * The local database is the only full copy of what the rings recorded: reading history off a ring
 * is destructive, and Health Connect receives derived records, not the raw readings, counters and
 * ring states. So this file is what stands between a lost phone and a lost history.
 */
/** Where a backup's contents come from — the database in the app, a fake in tests. */
interface BackupSource {
    /** Pages in primary-key order: by ring, then counter. */
    suspend fun epochsAfter(afterRing: String, afterCounter: Long, limit: Int): List<EpochEntity>
    /** Pages in primary-key order: by ring, then time. */
    suspend fun deviceStatesAfter(afterRing: String, afterAt: Long, limit: Int): List<DeviceStateEntity>
    suspend fun allSport(): List<SportEntity>
    suspend fun allWeather(): List<WeatherHourEntity> = emptyList()
    fun settingsJson(): String
    suspend fun ringNames(): Map<String, String>
}

/** The real source: this app's database and settings. */
class AppBackupSource(private val context: Context) : BackupSource {
    private val dao: RingDao = RingDatabase.get(context).dao()
    override suspend fun epochsAfter(afterRing: String, afterCounter: Long, limit: Int) =
        dao.epochsAfter(afterRing, afterCounter, limit)
    override suspend fun deviceStatesAfter(afterRing: String, afterAt: Long, limit: Int) =
        dao.deviceStatesAfter(afterRing, afterAt, limit)
    override suspend fun allSport() = dao.allSport()
    override suspend fun allWeather() = dao.weatherBetween(Long.MIN_VALUE, Long.MAX_VALUE)
    // Compact on purpose: every entry is checked line by line on restore, and an indented
    // settings file spread over many lines once made every backup fail that check.
    override fun settingsJson(): String = SettingsSnapshot.capture(context).toString()
    override suspend fun ringNames(): Map<String, String> {
        val names = RingNames(context, Settings(context))
        return (dao.knownRings() + dao.deviceStateRings()).distinct().associateWith { names.ring(it).name }
    }
}

/**
 * Writes a backup.
 *
 * The local database is the only full copy of what the rings recorded: reading history off a ring
 * is destructive, and Health Connect receives derived records, not the raw readings, counters and
 * ring states. So this file is what stands between a lost phone and a lost history.
 */
class BackupWriter(private val source: BackupSource, private val appVersion: String = BuildConfig.VERSION_NAME) {

    constructor(context: Context) : this(AppBackupSource(context))

    suspend fun write(out: OutputStream, now: Long = System.currentTimeMillis()): BackupManifest {
        val files = LinkedHashMap<String, BackupManifest.FileInfo>()
        val zip = ZipOutputStream(out.buffered())

        files[BackupFormat.EPOCHS] = entry(zip, BackupFormat.EPOCHS) { emit ->
            var afterCounter = Long.MIN_VALUE
            var afterRing = ""
            while (true) {
                val page = source.epochsAfter(afterRing, afterCounter, PAGE)
                if (page.isEmpty()) break
                page.forEach { emit(BackupFormat.encode(it)) }
                afterCounter = page.last().counter
                afterRing = page.last().ringId
            }
        }
        files[BackupFormat.DEVICE_STATE] = entry(zip, BackupFormat.DEVICE_STATE) { emit ->
            var afterAt = Long.MIN_VALUE
            var afterRing = ""
            while (true) {
                val page = source.deviceStatesAfter(afterRing, afterAt, PAGE)
                if (page.isEmpty()) break
                page.forEach { emit(BackupFormat.encode(it)) }
                afterAt = page.last().recordedAt
                afterRing = page.last().ringId
            }
        }
        files[BackupFormat.SPORT] = entry(zip, BackupFormat.SPORT) { emit ->
            source.allSport().forEach { emit(BackupFormat.encode(it)) }
        }
        files[BackupFormat.WEATHER] = entry(zip, BackupFormat.WEATHER) { emit ->
            source.allWeather().forEach { emit(BackupFormat.encode(it)) }
        }
        val settings = source.settingsJson()
        files[BackupFormat.SETTINGS] = entry(zip, BackupFormat.SETTINGS) { emit -> emit(settings) }

        val manifest = BackupManifest(
            formatVersion = BackupFormat.VERSION,
            createdAt = now,
            appVersion = appVersion,
            rings = source.ringNames(),
            files = files,
        )
        zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
        zip.write(manifest.toJson().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        zip.flush()
        return manifest
    }

    /**
     * Write one entry line by line. Rows are counted as the newlines actually written, the same
     * thing restore counts — so the two cannot disagree whatever a row contains.
     */
    private suspend fun entry(
        zip: ZipOutputStream,
        name: String,
        body: suspend (emit: (String) -> Unit) -> Unit,
    ): BackupManifest.FileInfo {
        zip.putNextEntry(ZipEntry(name))
        val digest = MessageDigest.getInstance("SHA-256")
        var rows = 0L
        body { line ->
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            digest.update(bytes)
            zip.write(bytes)
            rows += bytes.count { it == NEWLINE }
        }
        zip.closeEntry()
        return BackupManifest.FileInfo(rows, digest.digest().toHex())
    }

    private companion object {
        const val PAGE = 2000
        const val NEWLINE = '\n'.code.toByte()
    }
}

/**
 * A backup file, opened for checking and restoring.
 *
 * The stream from the file picker is copied to a private temporary file first, because checking
 * every entry against the manifest before touching anything needs to read the file twice.
 */
class BackupFile private constructor(private val file: File, private val deleteOnClose: Boolean) : Closeable {

    private val zip = ZipFile(file)
    val manifest: BackupManifest = try {
        zip.getEntry(BackupFormat.MANIFEST)
            ?.let { zip.getInputStream(it).bufferedReader().readText() }
            ?.let(BackupManifest::parse)
            ?: throw BackupException("This is not a RingLink backup: it has no manifest.")
    } catch (t: Throwable) {
        runCatching { zip.close() }
        throw if (t is BackupException) t else BackupException("The backup's manifest could not be read: ${t.message}")
    }

    /**
     * Everything that would make restoring this file unsafe, or an empty list.
     *
     * Each file is re-hashed and its lines counted against what the manifest promised. A backup
     * that was truncated or altered is refused as a whole rather than restored in part.
     */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        if (manifest.formatVersion > BackupFormat.VERSION) {
            out += "It was made by a newer version of RingLink (format ${manifest.formatVersion}); update the app first."
            return out
        }
        for (required in BackupFormat.TABLES + BackupFormat.SETTINGS) {
            if (required !in manifest.files) out += "$required is not listed in the manifest."
        }
        for ((name, info) in manifest.files) {
            val entry = zip.getEntry(name)
            if (entry == null) {
                out += "$name is missing."
                continue
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var lines = 0L
            zip.getInputStream(entry).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    for (i in 0 until n) if (buf[i] == '\n'.code.toByte()) lines++
                }
            }
            if (lines != info.rows) out += "$name has $lines rows, the manifest says ${info.rows}."
            if (digest.digest().toHex() != info.sha256) out += "$name does not match its checksum."
        }
        return out
    }

    fun lines(name: String): Sequence<String> {
        val entry = zip.getEntry(name) ?: return emptySequence()
        return zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }
    }

    fun text(name: String): String? = zip.getEntry(name)?.let { zip.getInputStream(it).bufferedReader().readText() }

    override fun close() {
        runCatching { zip.close() }
        if (deleteOnClose) file.delete()
    }

    companion object {
        /** Where incoming backups are copied while being checked and restored. */
        fun stagingDir(context: Context) = File(context.filesDir, "restore-staging").apply { mkdirs() }

        /**
         * Copy a picked backup into private storage and open it.
         *
         * Private files rather than cache: the cache can be cleared by the system mid-restore.
         * The copy is removed on any failure, including one part-way through copying.
         */
        fun open(context: Context, input: InputStream): BackupFile {
            val tmp = File(stagingDir(context), "incoming-${System.currentTimeMillis()}.zip")
            try {
                input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
                return BackupFile(tmp, deleteOnClose = true)
            } catch (t: Throwable) {
                tmp.delete()
                throw if (t is BackupException) t else BackupException("The file could not be read as a backup: ${t.message}")
            }
        }

        /** Open a backup already on disk, leaving the file in place. */
        fun fromFile(file: File): BackupFile = try {
            BackupFile(file, deleteOnClose = false)
        } catch (t: Throwable) {
            throw if (t is BackupException) t else BackupException("The file could not be read as a backup: ${t.message}")
        }
    }
}

class BackupException(message: String) : Exception(message)

/** What a restore actually changed. */
data class RestoreReport(
    val epochsAdded: Long,
    val readingsAdded: Long,
    val sportAdded: Long,
    val settings: SettingsSnapshot.Applied,
)

/**
 * Brings a backup back by merging it into what is already here.
 *
 * Merging rather than replacing is the point. A backup is a copy from some moment; restoring an
 * old one onto a phone that has recorded since must not throw away what came after. Rows are only
 * ever added — an epoch already present keeps its values, filling in only fields it was missing —
 * so restoring is safe to repeat, and restoring onto an empty install brings everything back.
 *
 * Restored rows are marked as not yet in Health Connect, so they are written there on the next
 * sync. That is harmless where they already are: records are replaced by id, not duplicated.
 */
class Restorer(private val context: Context) {

    private val db = RingDatabase.get(context)
    private val dao = db.dao()

    suspend fun restore(backup: BackupFile): RestoreReport {
        val problems = backup.problems()
        if (problems.isNotEmpty()) throw BackupException(problems.joinToString(" "))

        // Settings first: quick, and if the restore is interrupted part-way, at least the rings
        // come back — the rows can be completed by simply restoring again.
        val settings = backup.text(BackupFormat.SETTINGS)
            ?.let { SettingsSnapshot.apply(context, JSONObject(it)) }
            ?: SettingsSnapshot.Applied(replacedAll = false, ringsAdded = emptyList())

        val epochsBefore = dao.countEpochs()
        val statesBefore = dao.countDeviceStates()
        val sportBefore = dao.countSport()

        backup.lines(BackupFormat.EPOCHS).map(BackupFormat::decodeEpoch).chunked(CHUNK).forEach { chunk ->
            db.withTransaction { dao.upsertEpochs(chunk) }
        }
        // Readings may land anywhere in the past, where the per-quarter steps derived from them are
        // not looked at again unless rebuilt. Asked for before the first row goes in, so a restore
        // cut short still gets its rows derived; and again after, so a rebuild that ran meanwhile
        // and passed their weeks does not count as covering them.
        // Weather before readings, so the rebuild that follows judges them with it. Only hours not
        // already here: rows are only ever added, and this phone's own reports are newer.
        backup.lines(BackupFormat.WEATHER).map(BackupFormat::decodeWeather).chunked(CHUNK).forEach { chunk ->
            dao.insertWeatherIfAbsent(chunk)
        }

        val local = Settings(context)
        local.invalidateStepQuarters()
        backup.lines(BackupFormat.DEVICE_STATE).map(BackupFormat::decodeDeviceState).chunked(CHUNK).forEach { chunk ->
            db.withTransaction { dao.insertDeviceStatesIfAbsent(chunk) }
        }
        local.invalidateStepQuarters()
        backup.lines(BackupFormat.SPORT).map(BackupFormat::decodeSport).chunked(CHUNK).forEach { chunk ->
            db.withTransaction { dao.insertSport(chunk) }
        }

        return RestoreReport(
            epochsAdded = dao.countEpochs() - epochsBefore,
            readingsAdded = dao.countDeviceStates() - statesBefore,
            sportAdded = dao.countSport() - sportBefore,
            settings = settings,
        ).also { L.i("restore: $it") }
    }

    private companion object {
        const val CHUNK = 1000
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
