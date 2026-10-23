package io.github.ringlink.backup

import io.github.ringlink.data.DeviceStateEntity
import io.github.ringlink.data.EpochEntity
import io.github.ringlink.data.SportEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** An in-memory stand-in for the database, paging exactly as the real queries do. */
private class FakeSource(
    val epochs: List<EpochEntity>,
    val states: List<DeviceStateEntity>,
    val sport: List<SportEntity>,
    val settings: String,
) : BackupSource {
    override suspend fun epochsAfter(afterRing: String, afterCounter: Long, limit: Int) =
        epochs.sortedWith(compareBy({ it.ringId }, { it.counter }))
            .filter { it.ringId > afterRing || (it.ringId == afterRing && it.counter > afterCounter) }
            .take(limit)
    override suspend fun deviceStatesAfter(afterRing: String, afterAt: Long, limit: Int) =
        states.sortedWith(compareBy({ it.ringId }, { it.recordedAt }))
            .filter { it.ringId > afterRing || (it.ringId == afterRing && it.recordedAt > afterAt) }
            .take(limit)
    override suspend fun allSport() = sport
    override fun settingsJson() = settings
    override suspend fun ringNames() = mapOf("A" to "RingConn Gen3-F749", "B" to "RingConn Gen2-CEF0")
}

/**
 * The whole path a backup takes — written, opened, checked, read back — with nothing mocked in
 * between. A test like this is what would have caught every backup being refused on restore
 * because an indented settings file was counted as one row.
 */
class BackupRoundTripTest {

    /** Two rings writing the same counters at once, as they really do: 6609 such slots measured. */
    private val epochs = (0 until 4500).flatMap { i ->
        listOf("A", "B").map { ring ->
            EpochEntity(
                ringId = ring, counter = 210_000_000L + i * 150, channel = i % 2 * 3, layout = "ACTIVITY",
                heartRate = if (ring == "A") 60 + i % 40 else null, hrvRmssd = null, confidence = 5,
                respiratoryRate = if (i % 3 == 0) 15.5 else null, spo2 = if (i % 4 == 0) 96 else null,
                motionSum = 5 + i % 7,
            )
        }
    }
    private val states = (0 until 2300).map { i ->
        DeviceStateEntity(
            ringId = if (i % 2 == 0) "A" else "B", recordedAt = 1_761_000_000_000L + (i / 2) * 1000L,
            batteryPercent = 50 + i % 50, steps = i % 300, skinTempA = 34.5, skinTempB = 34.2,
            batteryMillivolts = 3900, onCharger = i % 2 == 1, state = if (i % 2 == 1) 4 else 2,
            caseBattery = if (i % 2 == 1) 80 else null, caseCharging = if (i % 2 == 1) false else null,
        )
    }
    private val sport = listOf(SportEntity("A", 210_000_150L, 88, 14))

    /** Settings deliberately spread over several lines, as the first version wrote them. */
    private val settings = "{\n  \"ringlink\": {\"step_goal\": {\"t\": \"i\", \"v\": 9000}},\n  \"ringlink_watch\": {}\n}"

    private suspend fun writeBackup(): File {
        val file = File.createTempFile("backup", ".zip")
        file.outputStream().use { BackupWriter(FakeSource(epochs, states, sport, settings), "test").write(it, now = 1L) }
        return file
    }

    @Test fun `a fresh backup passes its own checks`() = runTest {
        BackupFile.fromFile(writeBackup()).use { assertEquals(emptyList<String>(), it.problems()) }
    }

    @Test fun `every row comes back, including both rings at the same counter`() = runTest {
        BackupFile.fromFile(writeBackup()).use { b ->
            val restored = b.lines(BackupFormat.EPOCHS).map(BackupFormat::decodeEpoch).toList()
            assertEquals(epochs.size, restored.size)
            assertEquals(epochs.toSet(), restored.toSet())
            assertEquals(states.toSet(), b.lines(BackupFormat.DEVICE_STATE).map(BackupFormat::decodeDeviceState).toSet())
            assertEquals(sport, b.lines(BackupFormat.SPORT).map(BackupFormat::decodeSport).toList())
            assertEquals(epochs.size.toLong(), b.manifest.rows(BackupFormat.EPOCHS))
        }
    }

    /** A cloud folder that had not finished syncing: the file simply ends early. */
    @Test fun `a truncated backup is refused`() = runTest {
        val file = writeBackup()
        RandomAccessFile(file, "rw").use { it.setLength(it.length() * 2 / 3) }
        val refused = runCatching { BackupFile.fromFile(file).use { it.problems() } }
        assertTrue(refused.isFailure || refused.getOrThrow().isNotEmpty())
    }

    /** One row lost from the middle of a table, the manifest untouched. */
    @Test fun `a table that lost a row is refused`() = runTest {
        val good = writeBackup()
        val bad = File.createTempFile("tampered", ".zip")
        ZipFile(good).use { src ->
            ZipOutputStream(bad.outputStream()).use { out ->
                for (e in src.entries()) {
                    out.putNextEntry(ZipEntry(e.name))
                    var bytes = src.getInputStream(e).readBytes()
                    if (e.name == BackupFormat.EPOCHS) {
                        val lines = String(bytes).lines().filter { it.isNotEmpty() }
                        bytes = (lines.take(100) + lines.drop(101)).joinToString("\n", postfix = "\n").toByteArray()
                    }
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        BackupFile.fromFile(bad).use { assertTrue(it.problems().any { p -> p.startsWith(BackupFormat.EPOCHS) }) }
    }

    @Test fun `a backup from a newer format is refused before anything else`() = runTest {
        val good = writeBackup()
        val newer = File.createTempFile("newer", ".zip")
        ZipFile(good).use { src ->
            ZipOutputStream(newer.outputStream()).use { out ->
                for (e in src.entries()) {
                    out.putNextEntry(ZipEntry(e.name))
                    var bytes = src.getInputStream(e).readBytes()
                    if (e.name == BackupFormat.MANIFEST) {
                        bytes = String(bytes).replace("\"format\": ${BackupFormat.VERSION}", "\"format\": ${BackupFormat.VERSION + 1}").toByteArray()
                    }
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        BackupFile.fromFile(newer).use { assertTrue(it.problems().single().contains("newer version")) }
    }

    @Test fun `a file that is not a backup is refused`() {
        val junk = File.createTempFile("junk", ".zip").apply { writeText("not a zip at all") }
        assertTrue(runCatching { BackupFile.fromFile(junk) }.exceptionOrNull() is BackupException)
    }
}
