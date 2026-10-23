package io.github.ringlink.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rotation deletes files in a folder the user chose. It must never touch anything not its own. */
class BackupRotationTest {

    @Test fun `only the newest are kept`() {
        val names = listOf(
            "ringlink-backup-20261020-030000Z.zip",
            "ringlink-backup-20261022-030000Z.zip",
            "ringlink-backup-20261021-030000Z.zip",
            "ringlink-backup-20261023-030000Z.zip",
        )
        assertEquals(
            listOf("ringlink-backup-20261021-030000Z.zip", "ringlink-backup-20261020-030000Z.zip"),
            BackupRotation.toDelete(names, keep = 2),
        )
    }

    @Test fun `files that are not ours are never deleted`() {
        val names = listOf(
            "ringlink-backup-20261020-030000Z.zip",
            "ringlink-backup-20261021-030000Z.zip",
            "holiday.jpg",
            "ringlink-backup-old.zip",
            "ringlink-backup-20261019-030000 (1).zip",
            "ringlink-backup-20261018-030000Z.zip.part",
            "my-ringlink-backup-20261017-030000Z.zip",
        )
        assertEquals(listOf("ringlink-backup-20261020-030000Z.zip"), BackupRotation.toDelete(names, keep = 1))
    }

    @Test fun `fewer backups than the limit means nothing is deleted`() {
        assertTrue(BackupRotation.toDelete(listOf("ringlink-backup-20261020-030000Z.zip"), keep = 7).isEmpty())
    }

    @Test fun `a nonsensical limit deletes nothing rather than everything`() {
        val names = listOf("ringlink-backup-20261020-030000Z.zip", "ringlink-backup-20261021-030000Z.zip")
        assertTrue(BackupRotation.toDelete(names, keep = 0).isEmpty())
    }

    @Test fun `generated names are recognised as ours`() {
        assertTrue(BackupRotation.isOurs(BackupRotation.nameFor(1_761_200_000_000L)))
        assertFalse(BackupRotation.isOurs("ringlink-backup.zip"))
    }

    /** A file still being written, or cut off by a crash, must never count as a kept backup. */
    @Test fun `a partial file is neither kept nor deleted by rotation`() {
        val partial = BackupRotation.partialName(1_761_200_000_000L)
        assertFalse(BackupRotation.isOurs(partial))
        assertTrue(BackupRotation.isOurPartial(partial))
        val names = listOf(partial, "ringlink-backup-20261020-030000Z.zip")
        assertTrue(BackupRotation.toDelete(names, keep = 1).isEmpty())
    }

    /** A local folder appends ".zip" to any name; the sweep must still recognise the leftover. */
    @Test fun `a partial with an appended extension is still recognised`() {
        assertTrue(BackupRotation.isOurPartial(BackupRotation.partialName(1_761_200_000_000L) + ".zip"))
        assertFalse(BackupRotation.isOurPartial("holiday.zip.partial"))
    }

    /** Manual backups live by their own name, out of rotation's reach. */
    @Test fun `manual backups are never rotated`() {
        val manual = BackupRotation.manualNameFor(1_761_200_000_000L)
        assertFalse(BackupRotation.isOurs(manual))
        assertTrue(BackupRotation.toDelete(listOf(manual), keep = 1).isEmpty())
    }

    /** Names are UTC, so they sort in true time order whatever zone the phone is in. */
    @Test fun `names are in UTC`() {
        assertEquals("ringlink-backup-20261023-060000Z.zip", BackupRotation.nameFor(1_792_735_200_000L))
    }
}
