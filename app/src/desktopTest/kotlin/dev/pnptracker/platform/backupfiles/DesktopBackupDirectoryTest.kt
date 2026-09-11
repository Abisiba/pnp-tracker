package dev.pnptracker.platform.backupfiles

import dev.pnptracker.domain.backup.backupDocumentOf
import dev.pnptracker.domain.backup.restore.anEmptyBackup
import dev.pnptracker.domain.backup.retention.AutomaticBackupKind
import dev.pnptracker.domain.backup.retention.BACKUP_HEADER_BYTES
import dev.pnptracker.domain.backup.retention.beginsLikeABackupDocument
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

private val WRITTEN_AT = Instant.fromEpochMilliseconds(1_757_320_364_031)

/**
 * The folder rotation works in, with real files in it.
 *
 * The engine's own tests hand it a listing and ask what it decides. This asks
 * the other half of the question: given a real directory holding the things a
 * real directory holds — a link, a pipe, a folder, somebody's own notes, a
 * half-written `.part` — does what reaches the engine describe them truthfully.
 * Everything that keeps a file safe depends on that description, so it is worth
 * having the file system answer rather than a fake.
 */
class DesktopBackupDirectoryTest {
    private lateinit var folder: Path

    @BeforeTest
    fun createFolder() {
        folder = Files.createTempDirectory("pnp-tracker-backups-test")
    }

    @AfterTest
    fun deleteFolder() {
        val absolute = folder.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
        Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun aDocument(): ByteArray =
        backupDocumentOf(anEmptyBackup(), appVersion = "0.1.0", sourceSchemaVersion = 8, createdAt = WRITTEN_AT)
            .json
            .encodeToByteArray()

    private fun write(
        fileName: String,
        bytes: ByteArray = aDocument(),
    ): Path = folder.resolve(fileName).also { Files.write(it, bytes) }

    @Test
    fun `only files this application names are even looked at`() =
        runBlocking<Unit> {
            write("pnp-otomatik-import-2026-09-09-143355.json")
            write("pnp-oncesi-2026-09-09-143355.json")
            write("pnp-otomatik-migration-v3-v8-2026-09-09-143355.json")
            // None of these is ours, and none of them should appear.
            write("pnp-yedek-2026-09-09.json")
            write("notlar.txt", "kendi notlarım".encodeToByteArray())
            write("pnp-otomatik-import-2026-09-09-143355.json.part")
            write("pnp-otomatik-import-2026-13-09-143355.json")

            val seen = DesktopBackupDirectory(folder).inspect().map { it.name.fileName }.sorted()

            assertEquals(
                listOf(
                    "pnp-oncesi-2026-09-09-143355.json",
                    "pnp-otomatik-import-2026-09-09-143355.json",
                    "pnp-otomatik-migration-v3-v8-2026-09-09-143355.json",
                ),
                seen,
            )
        }

    @Test
    fun `what is read is enough to recognise a document and no more`() =
        runBlocking<Unit> {
            val document = aDocument()
            write("pnp-otomatik-import-2026-09-09-143355.json", document)

            val found = DesktopBackupDirectory(folder).inspect().single()

            assertEquals(AutomaticBackupKind.IMPORT, found.name.kind)
            assertTrue(found.ordinaryFile)
            // Bounded: a backup can be tens of megabytes and housekeeping must
            // not read one to decide whether it may go.
            assertEquals(BACKUP_HEADER_BYTES, found.header.size)
            assertTrue(document.size > found.header.size)
            assertTrue(beginsLikeABackupDocument(found.header))
        }

    @Test
    fun `a symbolic link is reported as something rotation may not remove`() =
        runBlocking<Unit> {
            // The case this exists for: a link under one of our names, pointing
            // at something that is not ours at all.
            val elsewhere = write("gercek-veri.json")
            val link = folder.resolve("pnp-otomatik-import-2026-09-09-143355.json")
            Files.createSymbolicLink(link, elsewhere)

            val found = DesktopBackupDirectory(folder).inspect().single()

            assertFalse(found.ordinaryFile, "a link was reported as an ordinary file")
            assertEquals(0, found.header.size, "a link's target was read")
            // And even asked directly, it will not go.
            assertFalse(DesktopBackupDirectory(folder).remove(link.fileName.toString()))
            assertTrue(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.exists(elsewhere))
        }

    @Test
    fun `a directory under one of our names is not an ordinary file`() =
        runBlocking<Unit> {
            val directory = folder.resolve("pnp-otomatik-import-2026-09-09-143355.json")
            Files.createDirectory(directory)
            Files.write(directory.resolve("icinde.txt"), "bir şey".encodeToByteArray())

            val found = DesktopBackupDirectory(folder).inspect().single()

            assertFalse(found.ordinaryFile)
            assertFalse(DesktopBackupDirectory(folder).remove(directory.fileName.toString()))
            assertTrue(Files.isDirectory(directory))
        }

    @Test
    fun `a file shorter than the header is described by what there is of it`() =
        runBlocking<Unit> {
            val short = "kısa".encodeToByteArray()
            write("pnp-otomatik-import-2026-09-09-143355.json", short)

            val found = DesktopBackupDirectory(folder).inspect().single()

            assertEquals(short.size, found.header.size)
            assertFalse(beginsLikeABackupDocument(found.header))
        }

    @Test
    fun `an empty file is not shown to be ours`() =
        runBlocking<Unit> {
            write("pnp-oncesi-2026-09-09-143355.json", ByteArray(0))

            val found = DesktopBackupDirectory(folder).inspect().single()

            assertTrue(found.ordinaryFile)
            assertEquals(0, found.header.size)
            assertFalse(beginsLikeABackupDocument(found.header))
        }

    @Test
    fun `removing takes a name and will not take a path`() =
        runBlocking<Unit> {
            val ours = write("pnp-otomatik-import-2026-09-09-143355.json")
            val outside = Files.createTempFile("pnp-tracker-elsewhere", ".json")
            try {
                val directory = DesktopBackupDirectory(folder)

                // Nothing that is not a plain name in this one folder is
                // reachable, however it is spelled.
                assertFalse(directory.remove("../${outside.fileName}"))
                assertFalse(directory.remove(outside.toAbsolutePath().toString()))
                assertFalse(directory.remove("alt/klasor/dosya.json"))
                assertTrue(Files.exists(outside))

                assertTrue(directory.remove(ours.fileName.toString()))
                assertFalse(Files.exists(ours))
            } finally {
                Files.deleteIfExists(outside)
            }
        }

    @Test
    fun `removing what is not there is reported, not thrown`() =
        runBlocking<Unit> {
            val directory = DesktopBackupDirectory(folder)

            // PLAN 14.4.13 makes a removal that does not happen a thing to
            // report; the work rotation followed has already succeeded.
            assertFalse(directory.remove("pnp-otomatik-import-2026-09-09-143355.json"))
        }

    @Test
    fun `a folder that is not there yet is empty rather than an error`() =
        runBlocking<Unit> {
            val missing = folder.resolve("henuz-yok")

            assertEquals(emptyList(), DesktopBackupDirectory(missing).inspect())
            assertFalse(DesktopBackupDirectory(missing).remove("pnp-oncesi-2026-09-09-143355.json"))
        }

    @Test
    fun `a migration pair is read as two files that name each other`() =
        runBlocking<Unit> {
            val setName = "pnp-otomatik-migration-v3-v8-2026-09-09-143355"
            write("$setName.json")
            write("$setName.db", databaseBytes(userVersion = 3))

            val found = DesktopBackupDirectory(folder).inspect()

            assertEquals(2, found.size)
            // One set name across both, which is how the pair is put back
            // together after a directory listing has taken it apart.
            assertEquals(setOf(setName), found.map { it.name.setName }.toSet())
            assertEquals(setOf(".json", ".db"), found.map { it.name.extension }.toSet())
            assertEquals(setOf(3), found.map { it.name.fromSchemaVersion }.toSet())
            assertEquals(setOf(8), found.map { it.name.toSchemaVersion }.toSet())
            assertEquals(setOf(1), found.map { it.name.attempt }.toSet())
        }

    @Test
    fun `a pipe under one of our names is not an ordinary file either`() =
        runBlocking<Unit> {
            // Reading a pipe blocks until somebody writes to it, so mistaking
            // one for a backup would hang the housekeeping rather than fail it.
            val pipe = folder.resolve("pnp-oncesi-2026-09-09-143355.json")
            val made = ProcessBuilder("mkfifo", pipe.toString()).start().waitFor()
            check(made == 0) { "mkfifo could not make a pipe to test with" }

            val found = DesktopBackupDirectory(folder).inspect().single()

            assertFalse(found.ordinaryFile)
            assertEquals(0, found.header.size)
            assertFalse(DesktopBackupDirectory(folder).remove(pipe.fileName.toString()))
            assertTrue(Files.exists(pipe, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        }

    /** A plausible first page of a SQLite database still on [userVersion]. */
    private fun databaseBytes(userVersion: Int): ByteArray {
        val page = ByteArray(4096)
        "SQLite format 3".encodeToByteArray().copyInto(page)
        page[16] = 0x10
        page[63] = userVersion.toByte()
        return page
    }
}
