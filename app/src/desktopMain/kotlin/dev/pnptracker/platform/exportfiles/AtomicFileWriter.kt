package dev.pnptracker.platform.exportfiles

import dev.pnptracker.domain.export.ExportFailure
import dev.pnptracker.domain.export.TaskExportException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val TEMPORARY_PREFIX = ".pnp-export-"
private const val TEMPORARY_SUFFIX = ".csv.part"

/**
 * Writes a file so that it either appears whole or does not appear at all.
 *
 * Writing straight into the destination is the obvious way and the one that
 * loses data: a full disk, a closed lid or a thrown exception halfway through
 * leaves the user with a file that has their old export's name and half of a new
 * one's contents, and nothing to tell them which. So the bytes go into a
 * temporary file beside the destination — beside it, so both are on the same
 * file system — and only a completed file is moved into place.
 *
 * The move is atomic or it does not happen. Deleting the old file and moving the
 * new one over would leave a window in which the user has neither, and turning a
 * failed export into a lost file is exactly what this exists to prevent; a file
 * system that cannot do it is told so rather than worked around.
 *
 * The three steps are seams so a test can make each of them fail and watch what
 * survives. Every failure leaves the destination byte for byte as it was and no
 * temporary file behind.
 */
class AtomicFileWriter(
    private val createTemporary: (directory: Path) -> Path = { directory ->
        Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX)
    },
    private val writeBytes: (Path, ByteArray) -> Unit = { file, bytes ->
        Files.newOutputStream(file).use { stream ->
            stream.write(bytes)
            stream.flush()
        }
    },
    private val moveIntoPlace: (Path, Path) -> Unit = { temporary, target ->
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) {
    /** @throws TaskExportException if the file could not be written. */
    fun write(
        target: Path,
        content: String,
    ) {
        val directory =
            target.parent ?: throw TaskExportException(ExportFailure.NOT_WRITABLE)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)

        val temporary =
            try {
                createTemporary(directory)
            } catch (cause: AccessDeniedException) {
                throw failed(ExportFailure.NOT_WRITABLE, cause)
            } catch (cause: IOException) {
                throw failed(ExportFailure.NOT_WRITABLE, cause)
            }

        try {
            writeBytes(temporary, bytes)
            moveIntoPlace(temporary, target)
        } catch (cause: AtomicMoveNotSupportedException) {
            deleteQuietly(temporary)
            throw failed(ExportFailure.NOT_ATOMIC, cause)
        } catch (cause: IOException) {
            deleteQuietly(temporary)
            throw failed(ExportFailure.WRITE_FAILED, cause)
        } catch (cause: RuntimeException) {
            // A seam handed in by a caller may fail in any way at all. Whatever
            // it was, the half-written file goes before it travels.
            deleteQuietly(temporary)
            throw failed(ExportFailure.WRITE_FAILED, cause)
        }
    }

    /**
     * Removes the half-written file, and says nothing if it cannot.
     *
     * The failure being reported is the one worth telling the user about; a
     * temporary file that could not be removed is not something they can act on,
     * and letting it replace the real reason would be a worse answer.
     */
    private fun deleteQuietly(file: Path) {
        runCatching { Files.deleteIfExists(file) }
    }

    private fun failed(
        failure: ExportFailure,
        cause: Throwable,
    ): TaskExportException = TaskExportException(failure).apply { initCause(cause) }
}
