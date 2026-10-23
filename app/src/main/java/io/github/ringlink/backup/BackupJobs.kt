package io.github.ringlink.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.ringlink.L
import io.github.ringlink.ble.RingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File

/** A backup picked for restoring, checked and waiting for confirmation. */
data class PendingRestore(
    val createdAt: Long,
    val rings: List<String>,
    val epochs: Long,
    val readings: Long,
    val problems: List<String>,
)

/**
 * Backups and restores started from the screen, run where the screen cannot cut them short.
 *
 * They used to run in the screen's own scope, so leaving it — Back, or swiping the app away —
 * cancelled them part-way: a half-written backup left looking like a real one, or a restore that
 * stopped after some rows with nothing to say so. Here they run in a scope that lives as long as
 * the process, one at a time under the same lock as automatic backups. If the process itself dies
 * mid-restore, a marker records it and the next launch says so; restoring is safe to repeat.
 */
object BackupJobs {

    data class State(
        val busy: Boolean = false,
        val message: String? = null,
        val pending: PendingRestore? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var pendingFile: BackupFile? = null

    private fun run(start: String, block: suspend () -> String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = start) }
        scope.launch {
            val message = runCatching { AutoBackup.lock.withLock { block() } }
                .getOrElse { L.e("backup job failed", it); "Failed: ${it.message}" }
            _state.update { it.copy(busy = false, message = message) }
        }
    }

    /**
     * A one-off backup to wherever the user picked.
     *
     * Written and checked in private storage first, then copied over. On failure, the picked file
     * is deleted only if this run made it what it is — created empty by the picker, or already
     * overwritten. If the user chose an existing backup to replace and the failure came before a
     * byte of it was touched, it is left exactly as it was.
     */
    fun backupTo(context: Context, uri: Uri) = run("Backing up…") {
        val app = context.applicationContext
        val resolver = app.contentResolver
        val startedEmpty = sizeOf(app, uri) == 0L
        var overwriting = false
        var staged: StagedBackup? = null
        try {
            staged = stagedBackup(app, System.currentTimeMillis())
            val out = openForWrite(resolver, uri) ?: throw BackupException("Could not open the file for writing.")
            overwriting = true
            out.use { o -> staged.file.inputStream().use { it.copyTo(o) } }
        } catch (t: Throwable) {
            if (startedEmpty || overwriting) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw t
        } finally {
            staged?.file?.delete()
        }
        val m = staged.manifest
        "Backed up ${m.rows(BackupFormat.EPOCHS)} epochs and ${m.rows(BackupFormat.DEVICE_STATE)} readings."
    }

    /** A backup into the automatic-backup folder now, shown like any other job. */
    fun backupToFolder(context: Context) = run("Backing up to the folder…") {
        AutoBackup.runLocked(context.applicationContext).fold(
            onSuccess = { "Backed up to the folder." },
            onFailure = { throw it },
        )
    }

    private fun sizeOf(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }.getOrNull()

    /** Check a backup before anything is touched, and show what it holds. */
    fun openRestore(context: Context, uri: Uri) = run("Checking the backup…") {
        val app = context.applicationContext
        closePending()
        val file = app.contentResolver.openInputStream(uri)?.let { BackupFile.open(app, it) }
            ?: throw BackupException("Could not open the file.")
        val problems = try {
            file.problems()
        } catch (t: Throwable) {
            file.close()
            throw t
        }
        val m = file.manifest
        pendingFile = file
        _state.update {
            it.copy(
                pending = PendingRestore(
                    createdAt = m.createdAt,
                    rings = m.rings.values.map { name -> name.substringAfterLast('-', name) },
                    epochs = m.rows(BackupFormat.EPOCHS),
                    readings = m.rows(BackupFormat.DEVICE_STATE),
                    problems = problems,
                ),
            )
        }
        if (problems.isEmpty()) "Backup checked." else "This backup cannot be restored."
    }

    fun cancelRestore() {
        if (_state.value.busy) return
        closePending()
        _state.update { it.copy(pending = null, message = null) }
    }

    fun confirmRestore(context: Context) {
        val file = pendingFile ?: return
        val app = context.applicationContext
        val settings = BackupSettings(app)
        // Set before waiting for the lock, so a process that dies while the restore is still queued
        // behind another job is reported too — the screen already said "Restoring".
        settings.restoreStartedAt = System.currentTimeMillis()
        _state.update { it.copy(pending = null) }
        run("Restoring… keep RingLink open until this finishes.") {
            val report = try {
                Restorer(app).restore(file)
            } finally {
                // Cleared whether it finished or failed: the marker means only "the process died
                // during it". A failure is reported where it happens, with its real reason.
                settings.restoreStartedAt = 0
                closePending()
            }
            // Side effects after the report, so they cannot turn a finished restore into a "failure".
            // Restored rows reach Health Connect on their own; sleep, steps and temperature are
            // recomputed over windows, so they need the whole history written explicitly.
            if (report.epochsAdded > 0 || report.readingsAdded > 0) {
                runCatching { RingService.start(app, RingService.ACTION_EXPORT_HISTORY) }
                    .onFailure { L.w("could not start the history export after restore: ${it.message}") }
            } else {
                runCatching { RingService.start(app, RingService.ACTION_SYNC) }
                    .onFailure { L.w("could not start sync after restore: ${it.message}") }
            }
            buildString {
                append("Restored: ${report.epochsAdded} epochs and ${report.readingsAdded} readings added")
                if (report.epochsAdded == 0L && report.readingsAdded == 0L) append(" — everything was already here")
                append(".")
                if (report.settings.replacedAll) append(" Settings restored.")
                else if (report.settings.ringsAdded.isNotEmpty()) append(" ${report.settings.ringsAdded.size} ring(s) added.")
            }
        }
    }

    /** The user has seen the notice about an interrupted restore. */
    fun dismissNotice(context: Context) {
        BackupSettings(context.applicationContext).restoreStartedAt = 0
        _state.update { it.copy(message = null) }
    }

    private fun closePending() {
        pendingFile?.close()
        pendingFile = null
    }

    /**
     * Tidy up at app start: report a restore the process died during, and remove staging files
     * left by any job that did not finish.
     */
    fun onAppStart(context: Context) {
        // The marker is not cleared here. The process often starts in the background — a sync, the
        // daily backup, a restart of the service — and if that process died before the screen was
        // opened, clearing it here would lose the only record that a restore was cut short. It
        // stays until the user dismisses the notice or another restore finishes.
        if (BackupSettings(context).restoreStartedAt != 0L) {
            _state.update {
                it.copy(
                    message = INTERRUPTED,
                )
            }
        }
        if (!_state.value.busy) {
            listOf(File(context.filesDir, "restore-staging"), File(context.filesDir, "backup-staging"), File(context.cacheDir, "restore"))
                .forEach { dir -> dir.listFiles()?.forEach { it.delete() } }
        }
    }

    const val INTERRUPTED = "A restore did not finish — the app was closed while it ran. Restoring " +
        "is safe to repeat: pick the same backup again."
}
