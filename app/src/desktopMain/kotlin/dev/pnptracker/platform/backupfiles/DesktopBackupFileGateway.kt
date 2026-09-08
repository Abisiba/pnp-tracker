package dev.pnptracker.platform.backupfiles

import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.BackupFileGateway
import dev.pnptracker.domain.backup.BackupFileHandle
import dev.pnptracker.domain.backup.jsonFileNameOf
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.AtomicWriteException
import dev.pnptracker.platform.files.AtomicWriteFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** What a half-written backup is called while it is still half written. */
private const val TEMPORARY_SUFFIX = ".json.part"

/**
 * The desktop end of saving a backup: it knows about paths, and nothing above it
 * does.
 *
 * A chosen destination becomes a [BackupFileHandle] that exposes only the file
 * name. The [Path] stays in here, which is what keeps it out of the screen, out
 * of every message and out of the log.
 *
 * The name the user typed is settled here too, by the same rule wherever it came
 * from: a name with no extension gets `.json`, one that already ends in `.json`
 * keeps it, and anything else is refused rather than written as a backup under a
 * name that says otherwise.
 */
class DesktopBackupFileGateway(
    private val picker: BackupFilePicker,
    private val writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = TEMPORARY_SUFFIX),
) : BackupFileGateway {
    override suspend fun chooseDestination(suggestedName: String): BackupFileHandle? {
        val chosen = picker.chooseDestination(suggestedName) ?: return null
        val name = chosen.fileName?.toString().orEmpty()
        if (name.isEmpty()) throw BackupException(BackupFailure.NO_DESTINATION)
        // Throws for a name this does not write, before anything is read or made.
        val target = chosen.resolveSibling(jsonFileNameOf(name))
        return PathBackupFileHandle(target, writer)
    }
}

private class PathBackupFileHandle(
    private val file: Path,
    private val writer: AtomicFileWriter,
) : BackupFileHandle {
    override val fileName: String = file.fileName?.toString().orEmpty()

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) { Files.exists(file) }

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                writer.write(file, bytes)
            } catch (refused: AtomicWriteException) {
                throw BackupException(backupFailureOf(refused.failure), refused)
            }
        }
    }
}

/**
 * What the backup tells the user about a file system that would not cooperate.
 *
 * One name each: unlike the task export, a backup keeps the writer's finer
 * distinctions, because they do lead to different things to do — a folder that
 * cannot be written to calls for another folder, while a disk that went away
 * calls for plugging it back in.
 */
private fun backupFailureOf(failure: AtomicWriteFailure): BackupFailure =
    when (failure) {
        AtomicWriteFailure.NOT_WRITABLE -> BackupFailure.NOT_WRITABLE
        AtomicWriteFailure.TEMPORARY_FILE_FAILED -> BackupFailure.TEMPORARY_FILE_FAILED
        AtomicWriteFailure.TARGET_UNAVAILABLE -> BackupFailure.TARGET_UNAVAILABLE
        AtomicWriteFailure.WRITE_FAILED -> BackupFailure.WRITE_FAILED
        AtomicWriteFailure.NOT_ATOMIC -> BackupFailure.NOT_ATOMIC
    }
