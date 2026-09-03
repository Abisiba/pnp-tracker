package dev.pnptracker.platform.exportfiles

import dev.pnptracker.domain.export.ExportFailure
import dev.pnptracker.domain.export.TaskExportException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What survives when writing a file goes wrong, on a real file system.
 *
 * Each of the three steps is made to fail in turn, and what is checked every
 * time is the same two things: the file that was there before is byte for byte
 * what it was, and nothing half-written is left lying beside it. Those are the
 * whole reason this class exists — writing straight into the destination is
 * simpler and loses somebody's last export.
 */
class AtomicFileWriterTest {
    private lateinit var directory: Path
    private lateinit var target: Path

    private val existing = "eski içerik\r\n"
    private val fresh = "yeni içerik\r\n"

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("pnp-export-writer")
        target = directory.resolve("gorevler.csv")
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        check(directory.startsWith(Path.of(System.getProperty("java.io.tmpdir")))) {
            "refusing to delete $directory, which is not under the temporary directory"
        }
        directory.deleteRecursively()
    }

    private fun writeExisting() {
        Files.write(target, existing.toByteArray(StandardCharsets.UTF_8))
    }

    /** Everything in the directory apart from the destination itself. */
    private fun leftovers(): List<String> =
        Files.list(directory).use { stream ->
            stream.map { it.fileName.toString() }.toList().filterNot { it == target.fileName.toString() }
        }

    @Test
    fun `a new file is written whole`() {
        AtomicFileWriter().write(target, fresh)

        assertEquals(fresh, Files.readString(target, StandardCharsets.UTF_8))
        assertEquals(emptyList(), leftovers(), "a temporary file was left behind")
    }

    @Test
    fun `the file is written as UTF-8`() {
        AtomicFileWriter().write(target, "\uFEFFIŞIK 😀 ığşçöü")

        assertContentEquals(
            "\uFEFFIŞIK 😀 ığşçöü".toByteArray(StandardCharsets.UTF_8),
            Files.readAllBytes(target),
        )
    }

    @Test
    fun `an existing file is replaced whole`() {
        writeExisting()

        AtomicFileWriter().write(target, fresh)

        assertEquals(fresh, Files.readString(target, StandardCharsets.UTF_8))
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `a failure while writing leaves the old file exactly as it was`() {
        writeExisting()
        val before = Files.readAllBytes(target)
        val writer = AtomicFileWriter(writeBytes = { _, _ -> throw IOException("disk full") })

        val refused = assertFailsWith<TaskExportException> { writer.write(target, fresh) }

        assertEquals(ExportFailure.WRITE_FAILED, refused.failure)
        assertContentEquals(before, Files.readAllBytes(target), "the old file was damaged")
        assertEquals(emptyList(), leftovers(), "a half-written file was left behind")
    }

    @Test
    fun `a failure while closing is a failure, and leaves nothing behind`() {
        writeExisting()
        val writer =
            AtomicFileWriter(
                // Written, then the close fails: the bytes are on disk and the
                // file is still not one anybody should be given.
                writeBytes = { file, bytes ->
                    Files.write(file, bytes)
                    throw IOException("could not flush")
                },
            )

        val refused = assertFailsWith<TaskExportException> { writer.write(target, fresh) }

        assertEquals(ExportFailure.WRITE_FAILED, refused.failure)
        assertEquals(existing, Files.readString(target, StandardCharsets.UTF_8))
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `a file system that cannot replace in one step is refused, not worked around`() {
        writeExisting()
        val writer =
            AtomicFileWriter(
                moveIntoPlace = { _, _ -> throw AtomicMoveNotSupportedException("t", "g", "no atomic move") },
            )

        val refused = assertFailsWith<TaskExportException> { writer.write(target, fresh) }

        assertEquals(ExportFailure.NOT_ATOMIC, refused.failure)
        assertEquals(existing, Files.readString(target, StandardCharsets.UTF_8), "the old file was deleted anyway")
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `a move that fails some other way keeps the old file too`() {
        writeExisting()
        val writer = AtomicFileWriter(moveIntoPlace = { _, _ -> throw IOException("gone") })

        val refused = assertFailsWith<TaskExportException> { writer.write(target, fresh) }

        assertEquals(ExportFailure.WRITE_FAILED, refused.failure)
        assertEquals(existing, Files.readString(target, StandardCharsets.UTF_8))
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `a place that cannot be written to is said so before anything is made`() {
        val writer = AtomicFileWriter(createTemporary = { throw IOException("read only") })

        val refused = assertFailsWith<TaskExportException> { writer.write(target, fresh) }

        assertEquals(ExportFailure.NOT_WRITABLE, refused.failure)
        assertTrue(!Files.exists(target), "a destination was created for a file that could not be written")
    }

    @Test
    fun `the same write can simply be tried again after a failure`() {
        writeExisting()
        val failing = AtomicFileWriter(moveIntoPlace = { _, _ -> throw IOException("gone") })
        assertFailsWith<TaskExportException> { failing.write(target, fresh) }

        AtomicFileWriter().write(target, fresh)

        assertEquals(fresh, Files.readString(target, StandardCharsets.UTF_8))
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `the temporary file is made beside the destination, so the move stays atomic`() {
        var madeIn: Path? = null
        val writer =
            AtomicFileWriter(
                createTemporary = { directory ->
                    madeIn = directory
                    Files.createTempFile(directory, ".probe-", ".part")
                },
            )

        writer.write(target, fresh)

        assertEquals(directory, madeIn, "the temporary file was made on some other file system")
    }
}
