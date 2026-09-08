package dev.pnptracker.platform.files

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val TEMPORARY_PREFIX = ".pnp-"

/** The suffix a half-written file carries while it is still half written. */
const val DEFAULT_TEMPORARY_SUFFIX: String = ".part"

/**
 * Why a file could not be written, in terms that belong to no feature.
 *
 * Deliberately about the file system and nothing else. What a user is told about
 * a failed export and what they are told about a failed backup are different
 * sentences, and both are decided by the feature rather than here.
 */
enum class AtomicWriteFailure {
    /** The place cannot be written to at all. */
    NOT_WRITABLE,

    /** The temporary file beside the destination could not be made. */
    TEMPORARY_FILE_FAILED,

    /** The destination stopped being there while the file was being written. */
    TARGET_UNAVAILABLE,

    /** The bytes could not be written, flushed or closed. */
    WRITE_FAILED,

    /**
     * The file system cannot replace the destination in one step.
     *
     * Deleting the old file first and moving the new one into place afterwards
     * would turn a failed write into a lost file, so it is refused instead.
     */
    NOT_ATOMIC,
}

/** Carries [failure] and the cause a developer would want; never shown to a user. */
class AtomicWriteException(
    val failure: AtomicWriteFailure,
    cause: Throwable? = null,
) : Exception("The file could not be written: $failure", cause)

/**
 * Writes a file so that it either appears whole or does not appear at all.
 *
 * Writing straight into the destination is the obvious way and the one that
 * loses data: a full disk, a closed lid or a thrown exception halfway through
 * leaves the user with a file that has their old file's name and half of a new
 * one's contents, and nothing to tell them which. So the bytes go into a
 * temporary file beside the destination — beside it, so both are on the same
 * file system — and only a completed file is moved into place.
 *
 * The move is atomic or it does not happen. Deleting the old file and moving the
 * new one over would leave a window in which the user has neither, and turning a
 * failed write into a lost file is exactly what this exists to prevent; a file
 * system that cannot do it is told so rather than worked around.
 *
 * Two features write through this one class — the task export and the backup —
 * and it knows about neither. What it throws is [AtomicWriteException], which
 * each of them turns into its own answer; a second writer with the same three
 * steps would be a second place for the guarantee to be got wrong.
 *
 * The three steps are seams so a test can make each of them fail and watch what
 * survives. Every failure leaves the destination byte for byte as it was and no
 * temporary file behind.
 */
class AtomicFileWriter(
    private val temporarySuffix: String = DEFAULT_TEMPORARY_SUFFIX,
    private val createTemporary: (directory: Path) -> Path = { directory ->
        Files.createTempFile(directory, TEMPORARY_PREFIX, temporarySuffix)
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
    /** Writes [content] as UTF-8. @throws AtomicWriteException if it could not be written. */
    fun write(
        target: Path,
        content: String,
    ) = write(target, content.toByteArray(StandardCharsets.UTF_8))

    /**
     * Writes [bytes] exactly as they are.
     *
     * The backup hands over the bytes its checksum was taken of, so nothing
     * between the digest and the disk can re-encode them.
     *
     * @throws AtomicWriteException if the file could not be written.
     */
    fun write(
        target: Path,
        bytes: ByteArray,
    ) {
        val directory =
            target.parent ?: throw AtomicWriteException(AtomicWriteFailure.NOT_WRITABLE)

        val temporary =
            try {
                createTemporary(directory)
            } catch (cause: AccessDeniedException) {
                throw AtomicWriteException(AtomicWriteFailure.NOT_WRITABLE, cause)
            } catch (cause: IOException) {
                throw AtomicWriteException(AtomicWriteFailure.TEMPORARY_FILE_FAILED, cause)
            }

        try {
            writeBytes(temporary, bytes)
            moveIntoPlace(temporary, target)
        } catch (cause: AtomicMoveNotSupportedException) {
            deleteQuietly(temporary)
            throw AtomicWriteException(AtomicWriteFailure.NOT_ATOMIC, cause)
        } catch (cause: NoSuchFileException) {
            // The folder went away underneath us — a removable disk unplugged
            // mid-write is the ordinary way this happens.
            deleteQuietly(temporary)
            throw AtomicWriteException(AtomicWriteFailure.TARGET_UNAVAILABLE, cause)
        } catch (cause: IOException) {
            deleteQuietly(temporary)
            throw AtomicWriteException(AtomicWriteFailure.WRITE_FAILED, cause)
        } catch (cause: RuntimeException) {
            // A seam handed in by a caller may fail in any way at all. Whatever
            // it was, the half-written file goes before it travels.
            deleteQuietly(temporary)
            throw AtomicWriteException(AtomicWriteFailure.WRITE_FAILED, cause)
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
}
