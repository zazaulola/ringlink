package io.github.ringlink.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Names for backup files, and which old ones to let go.
 *
 * Automatic backups land in a folder the user chose, which may hold anything else at all. So only
 * files whose names match this app's exact pattern are ever considered for deletion — a near miss
 * such as "ringlink-backup-old.zip", or the same name with " (1)" added by a file manager, is left
 * alone.
 *
 * Names carry UTC time with a trailing Z, so that sorting them as text sorts them in time even
 * across a time-zone change or a clock change; local time would let a backup made after a flight
 * sort as older than one made before it, and rotation would delete the newer.
 *
 * A backup being written is called by its [partialName] until it has been checked, which does not
 * match the pattern. A file cut off by a crash therefore never counts as one of the kept backups.
 */
object BackupRotation {

    private val PATTERN = Regex("""^ringlink-backup-\d{8}-\d{6}Z\.zip$""")
    /** A trailing ".zip" is tolerated: some providers append the type's extension to any name. */
    private val PARTIAL = Regex("""^ringlink-backup-\d{8}-\d{6}Z\.zip\.partial(\.zip)?$""")

    /** Manual backups are named apart, so rotation in a shared folder never counts or removes them. */
    private const val MANUAL_PREFIX = "ringlink-manual-"

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis)) + "Z"

    fun nameFor(millis: Long): String = "ringlink-backup-${stamp(millis)}.zip"

    fun partialName(millis: Long): String = nameFor(millis) + ".partial"

    fun manualNameFor(millis: Long): String = "$MANUAL_PREFIX${stamp(millis)}.zip"

    fun isOurs(name: String): Boolean = PATTERN.matches(name)

    fun isOurPartial(name: String): Boolean = PARTIAL.matches(name)

    /** The backups to delete so that only the newest [keep] remain. Never anything not ours. */
    fun toDelete(names: Collection<String>, keep: Int): List<String> {
        val ours = names.filter(::isOurs).sortedDescending()
        return if (keep < 1) emptyList() else ours.drop(keep)
    }
}
