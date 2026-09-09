package dev.pnptracker.platform.backupfiles

import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.files.AtomicFileWriter
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val MOMENT = LocalMoment(2026, 9, 9, 14, 33, 55)

private val BYTES = """{"format":"pnp-tracker-backup"}""".toByteArray()

/**
 * The file that is the user's way back, and the two ways it could quietly not be.
 *
 * It could be overwritten by the next restore, which would leave one copy where
 * the user thinks there are two. And it could half exist — an empty file with a
 * backup's name, or a `.part` beside it — which is worse, because a way back that
 * looks like a way back and is not is the failure this whole design is arranged
 * to prevent.
 *
 * So most of what is checked here is what is on disk afterwards, in every case,
 * including the ones where nothing worked.
 */
class DesktopSafetyBackupWriterTest {
    private lateinit var directory: Path

    @BeforeTest
    fun createDirectory() {
        directory = Files.createTempDirectory("pnp-tracker-safety-test")
    }

    @AfterTest
    fun deleteDirectory() {
        Files.walk(directory).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `the backup lands under the name of the moment, whole`() =
        runBlocking<Unit> {
            val name = DesktopSafetyBackupWriter(directory).writeSafetyBackup(BYTES, MOMENT)

            assertEquals("pnp-oncesi-2026-09-09-143355.json", name)
            assertEquals(listOf(name), namesInDirectory())
            assertTrue(Files.readAllBytes(directory.resolve(name)).contentEquals(BYTES))
        }

    @Test
    fun `a name already taken is never written over`() =
        runBlocking<Unit> {
            // Two restores in the same second. The first file is somebody's only
            // copy of what they had, and replacing it would be the one thing this
            // must not do (PLAN 14.4.4).
            val writer = DesktopSafetyBackupWriter(directory)
            val first = writer.writeSafetyBackup("first".toByteArray(), MOMENT)

            val second = writer.writeSafetyBackup("second".toByteArray(), MOMENT)

            assertEquals("pnp-oncesi-2026-09-09-143355.json", first)
            assertEquals("pnp-oncesi-2026-09-09-143355-2.json", second)
            assertEquals("first", Files.readString(directory.resolve(first)))
            assertEquals("second", Files.readString(directory.resolve(second)))
        }

    @Test
    fun `a file somebody else left under the name is stepped around, not replaced`() =
        runBlocking<Unit> {
            // Not written by this application at all: whatever it is, it is not
            // ours to remove.
            val taken = directory.resolve("pnp-oncesi-2026-09-09-143355.json")
            Files.writeString(taken, "somebody else's")

            val name = DesktopSafetyBackupWriter(directory).writeSafetyBackup(BYTES, MOMENT)

            assertEquals("pnp-oncesi-2026-09-09-143355-2.json", name)
            assertEquals("somebody else's", Files.readString(taken))
        }

    @Test
    fun `a name is claimed before a single byte is written`() =
        runBlocking<Unit> {
            // The claim is what makes two restores in one second safe, and it has
            // to happen before the writing rather than after it. A writer that
            // stops on the way through leaves nothing behind, so the only way to
            // see the claim is to look from inside the write itself.
            var namesWhileWriting = emptyList<String>()
            val writer =
                DesktopSafetyBackupWriter(
                    directory,
                    AtomicFileWriter(
                        temporarySuffix = ".json.part",
                        writeBytes = { file, bytes ->
                            namesWhileWriting = namesInDirectory()
                            Files.write(file, bytes)
                        },
                    ),
                )

            writer.writeSafetyBackup(BYTES, MOMENT)

            assertTrue(
                "pnp-oncesi-2026-09-09-143355.json" in namesWhileWriting,
                "the name was still free while the bytes were going down: $namesWhileWriting",
            )
        }

    @Test
    fun `a write that fails leaves neither a backup nor a half of one`() =
        runBlocking<Unit> {
            val writer =
                DesktopSafetyBackupWriter(
                    directory,
                    AtomicFileWriter(
                        temporarySuffix = ".json.part",
                        writeBytes = { _, _ -> throw IOException("the disk is full") },
                    ),
                )

            val refused = assertFailsWith<BackupException> { writer.writeSafetyBackup(BYTES, MOMENT) }

            assertEquals(BackupFailure.WRITE_FAILED, refused.failure)
            // Nothing at all: not the claimed name, which would look like a
            // backup and be empty, and not the half-written file beside it.
            assertEquals(emptyList(), namesInDirectory())
        }

    @Test
    fun `a move that cannot be atomic leaves nothing, and says which`() =
        runBlocking<Unit> {
            val writer =
                DesktopSafetyBackupWriter(
                    directory,
                    AtomicFileWriter(
                        temporarySuffix = ".json.part",
                        moveIntoPlace = { _, _ -> throw java.nio.file.AtomicMoveNotSupportedException(null, null, "no") },
                    ),
                )

            val refused = assertFailsWith<BackupException> { writer.writeSafetyBackup(BYTES, MOMENT) }

            assertEquals(BackupFailure.NOT_ATOMIC, refused.failure)
            assertEquals(emptyList(), namesInDirectory())
        }

    @Test
    fun `a directory that is not there is a failure and not an exception the user sees`() =
        runBlocking<Unit> {
            val writer = DesktopSafetyBackupWriter(directory.resolve("nowhere"))

            val refused = assertFailsWith<BackupException> { writer.writeSafetyBackup(BYTES, MOMENT) }

            assertEquals(BackupFailure.NOT_WRITABLE, refused.failure)
        }

    @Test
    fun `no path reaches the caller, only a name`() =
        runBlocking<Unit> {
            val name = DesktopSafetyBackupWriter(directory).writeSafetyBackup(BYTES, MOMENT)

            assertTrue("/" !in name, "the writer handed back a path: $name")
            assertTrue(directory.toString() !in name)
        }

    private fun namesInDirectory(): List<String> =
        Files.list(directory).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }
}
