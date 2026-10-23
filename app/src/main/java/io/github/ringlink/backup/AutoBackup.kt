package io.github.ringlink.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.ringlink.L
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy

/**
 * Settings for automatic backups. Kept in their own file, which backups never carry: a folder
 * permission belongs to the device that granted it.
 */
class BackupSettings(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit { putBoolean("enabled", v) }

    var folder: Uri?
        get() = prefs.getString("folder", null)?.let(Uri::parse)
        set(v) = prefs.edit { putString("folder", v?.toString()) }

    var keep: Int
        get() = prefs.getInt("keep", DEFAULT_KEEP)
        set(v) = prefs.edit { putInt("keep", v.coerceIn(1, 60)) }

    var lastAt: Long
        get() = prefs.getLong("last_at", 0)
        set(v) = prefs.edit { putLong("last_at", v) }

    /** Set while a restore runs, so one cut short by the process dying is reported next launch. */
    var restoreStartedAt: Long
        get() = prefs.getLong("restore_started_at", 0)
        set(v) = prefs.edit(commit = true) { putLong("restore_started_at", v) }

    /** A short human-readable outcome of the last automatic backup. */
    var lastResult: String?
        get() = prefs.getString("last_result", null)
        set(v) = prefs.edit { putString("last_result", v) }

    companion object {
        const val FILE = "ringlink_backup"
        const val DEFAULT_KEEP = 7
    }
}

/** A folder the user picked, reached through the Storage Access Framework. */
class BackupFolder(private val context: Context, private val tree: Uri) {

    private val resolver = context.contentResolver
    private val root: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    fun displayName(): String? = runCatching {
        resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** Still allowed to write here — the permission can be revoked, or the folder deleted. */
    fun reachable(): Boolean =
        resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission } && displayName() != null

    /** Names and document ids of everything in the folder. */
    private fun children(): List<Pair<String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val out = ArrayList<Pair<String, String>>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getString(1)
        }
        return out
    }

    /**
     * Write a new backup, and only then trim old ones.
     *
     * The backup is first written and checked in private storage, so what reaches the folder is
     * known to be whole. It goes into the folder under a partial name that rotation ignores and is
     * renamed only once completely copied: a crash part-way leaves a file rotation will neither keep
     * nor count. Old backups are trimmed last, and a failure listing or deleting them does not undo
     * a good backup — at worst one too many is kept.
     */
    suspend fun writeBackup(keep: Int): BackupManifest {
        // Names have one-second resolution; two backups back to back must still not share one.
        val existing = runCatching { children().map { it.first }.toSet() }.getOrDefault(emptySet())
        var now = System.currentTimeMillis()
        while (BackupRotation.nameFor(now) in existing) now += 1000
        val staged = stagedBackup(context, now)
        try {
            sweepPartials()
            val finalName = BackupRotation.nameFor(now)
            // An untyped stream, so the provider keeps the name exactly: given "application/zip", a
            // local folder appends ".zip" to "….zip.partial", and the sweep below could miss it.
            val partial = DocumentsContract.createDocument(resolver, root, "application/octet-stream", BackupRotation.partialName(now))
                ?: throw BackupException("Could not create a file in the backup folder.")
            try {
                copyInto(staged.file, partial)
                val renamed = runCatching { DocumentsContract.renameDocument(resolver, partial, finalName) }.getOrNull()
                if (renamed == null) {
                    // Some providers cannot rename. Write the final name directly instead, then read
                    // it back: without a rename there is no partial name to hide behind, so the copy
                    // is only trusted once it is known to match.
                    val direct = DocumentsContract.createDocument(resolver, root, "application/zip", finalName)
                        ?: throw BackupException("Could not create the backup file.")
                    try {
                        copyInto(staged.file, direct)
                        if (hashOf(direct) != staged.file.inputStream().use(::sha256)) {
                            throw BackupException("The copy in the backup folder does not match the backup.")
                        }
                    } catch (t: Throwable) {
                        runCatching { DocumentsContract.deleteDocument(resolver, direct) }
                        throw t
                    }
                    runCatching { DocumentsContract.deleteDocument(resolver, partial) }
                }
            } catch (t: Throwable) {
                runCatching { DocumentsContract.deleteDocument(resolver, partial) }
                throw t
            }
            runCatching { rotate(keep, finalName) }.onFailure { L.w("rotation skipped: ${it.message}") }
            return staged.manifest
        } finally {
            staged.file.delete()
        }
    }

    private fun hashOf(doc: Uri): String? =
        runCatching { resolver.openInputStream(doc)?.use(::sha256) }.getOrNull()

    private fun copyInto(file: java.io.File, doc: Uri) {
        (openForWrite(resolver, doc) ?: throw BackupException("Could not open the backup file for writing."))
            .use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /** Partial files can only be left by an earlier run that died; writes are never concurrent. */
    private fun sweepPartials() = runCatching {
        children().filter { BackupRotation.isOurPartial(it.first) }.forEach { (_, id) ->
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
        }
    }

    private fun rotate(keep: Int, justWritten: String) {
        val byName = children().toMap()
        for (old in BackupRotation.toDelete(byName.keys, keep)) {
            if (old == justWritten) continue
            val id = byName[old] ?: continue
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
                .onFailure { L.w("could not delete old backup $old: ${it.message}") }
        }
    }
}

internal fun sha256(input: java.io.InputStream): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
    }
    return digest.digest().toHex()
}

/** A backup written to private storage and checked, ready to be copied wherever it is going. */
class StagedBackup(val file: java.io.File, val manifest: BackupManifest)

/**
 * Write a backup into private storage and check it before it goes anywhere.
 *
 * Checking it here — the same check a restore runs — is what makes "OK" mean the backup can
 * actually be restored. A backup that only claimed success is the failure that matters most,
 * because it is discovered at exactly the moment it is needed.
 */
suspend fun stagedBackup(context: Context, now: Long): StagedBackup {
    val dir = java.io.File(context.filesDir, "backup-staging").apply { mkdirs() }
    val file = java.io.File(dir, "backup-$now.zip")
    try {
        val manifest = file.outputStream().use { BackupWriter(AppBackupSource(context)).write(it, now) }
        val problems = BackupFile.fromFile(file).use { it.problems() }
        if (problems.isNotEmpty()) throw BackupException("The backup failed its own check: ${problems.joinToString(" ")}")
        return StagedBackup(file, manifest)
    } catch (t: Throwable) {
        file.delete()
        throw t
    }
}

/**
 * Open a document for writing from the start, truncating whatever was there.
 *
 * Plain "w" may or may not truncate depending on the provider; writing a smaller backup over a
 * larger file without truncating would leave the old file's tail after the new one, and a
 * corrupt zip.
 */
fun openForWrite(resolver: android.content.ContentResolver, uri: Uri): java.io.OutputStream? =
    runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri, "w")

object AutoBackup {

    private const val WORK = "ringlink-auto-backup"
    private const val WORK_ONCE = "ringlink-auto-backup-now"
    private const val CHANNEL = "backup"
    private const val NOTICE_ID = 300

    /**
     * One backup or restore at a time, across the daily job and everything started from the
     * screen. Two backups racing for the same folder could otherwise each rotate the other away.
     */
    val lock = Mutex()

    /** Daily while enabled; cancelled when switched off. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!BackupSettings(context).enabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** A backup as soon as possible — used when a folder has just been chosen. */
    fun runSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_ONCE,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().build(),
        )
    }

    /** Take the folder the user picked, keeping the permission across reboots. */
    fun useFolder(context: Context, tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
        val settings = BackupSettings(context)
        settings.folder?.takeIf { it != tree }?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        settings.folder = tree
    }

    class NoFolder : Exception("Choose a folder to start backing up.")

    /** Run one backup into the folder now, waiting for any other backup or restore to finish. */
    suspend fun runNow(context: Context): Result<BackupManifest> = lock.withLock { runLocked(context) }

    /**
     * The backup itself, for callers already holding [lock].
     *
     * The folder and the number to keep are read here, under the lock, not before waiting for it:
     * a run that queued behind another job must use the folder chosen meanwhile, not the one it
     * saw when it started waiting.
     *
     * Cancellation is passed on, not recorded: a backup stopped because the user switched
     * automatic backups off, or the system stopped the job, has not failed.
     */
    suspend fun runLocked(context: Context): Result<BackupManifest> {
        val settings = BackupSettings(context)
        val tree = settings.folder ?: return Result.failure(NoFolder())
        val result = runCatching {
            val folder = BackupFolder(context, tree)
            if (!folder.reachable()) {
                throw BackupException("The backup folder can no longer be reached — choose it again.")
            }
            folder.writeBackup(settings.keep)
        }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        settings.lastAt = System.currentTimeMillis()
        settings.lastResult = result.fold(
            onSuccess = { m -> "OK · ${m.rows(BackupFormat.EPOCHS) + m.rows(BackupFormat.DEVICE_STATE)} rows" },
            onFailure = { "Failed: ${it.message}" },
        )
        result.onFailure { L.e("automatic backup failed", it) }
        return result
    }

    /**
     * Say so when an automatic backup fails.
     *
     * A backup that silently stopped working is worse than none, because it is trusted — the
     * moment it is needed is the moment the gap is discovered.
     */
    fun notifyFailure(context: Context, reason: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Backups", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Automatic backup failed")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTICE_ID, n) }
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!BackupSettings(applicationContext).enabled) return Result.success()
        return AutoBackup.runNow(applicationContext).fold(
            onSuccess = { Result.success() },
            onFailure = {
                // No folder yet is a setup step, not a failure: the screen already says so.
                if (it is AutoBackup.NoFolder) return Result.success()
                if (isStopped) return Result.success()
                AutoBackup.notifyFailure(applicationContext, it.message ?: "Unknown error")
                // A folder that cannot be reached will not fix itself by retrying; anything else might.
                if (it is BackupException) Result.failure() else Result.retry()
            },
        )
    }
}
