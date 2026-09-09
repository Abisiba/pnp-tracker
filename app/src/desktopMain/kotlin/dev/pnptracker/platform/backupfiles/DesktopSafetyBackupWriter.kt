package dev.pnptracker.platform.backupfiles

import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.restore.SAFETY_BACKUP_NAME_ATTEMPTS
import dev.pnptracker.domain.backup.restore.SafetyBackupWriter
import dev.pnptracker.domain.backup.restore.safetyBackupFileName
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.AtomicWriteException
import dev.pnptracker.platform.files.AtomicWriteFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

/** What a half-written safety backup is called while it is still half written. */
private const val TEMPORARY_SUFFIX = ".json.part"

/**
 * Writes the pre-restore backup into the application's own backups directory.
 *
 * The same canonical bytes and the same atomic writer as a manual backup — PLAN
 * 14.4.4 is explicit that there is no second format — so the file this leaves is
 * an ordinary backup that the ordinary reader will open and the ordinary restore
 * will put back. That is the whole point of it: a way back that only a special
 * code path could use would not be one.
 *
 * The name is claimed before the bytes are written, and claimed by creating the
 * file rather than by asking whether it exists. Asking first and writing after is
 * two steps with a gap in the middle, and a second restore starting inside that
 * gap would be told the name is free and then replace the first one's file —
 * silently, because an atomic move replaces whatever it lands on. Creating the
 * file is the file system's own answer to "is this name mine", given once and
 * indivisibly, so two attempts at the same second get different names.
 *
 * The claim is a real, empty file, and the atomic move lands on top of it. If
 * anything goes wrong between the two the claim is removed again, so a failed
 * safety backup leaves neither an empty `.json` that looks like a backup nor a
 * `.part` beside it.
 */
class DesktopSafetyBackupWriter(
    private val backupsDirectory: Path,
    private val writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = TEMPORARY_SUFFIX),
) : SafetyBackupWriter {
    override suspend fun writeSafetyBackup(
        bytes: ByteArray,
        moment: LocalMoment,
    ): String =
        withContext(Dispatchers.IO) {
            val (name, claimed) = claimAName(moment)
            try {
                writer.write(claimed, bytes)
            } catch (refused: AtomicWriteException) {
                // The claim was ours and nothing was ever written into it.
                runCatching { Files.deleteIfExists(claimed) }
                throw BackupException(backupFailureOf(refused.failure), refused)
            } catch (refused: RuntimeException) {
                runCatching { Files.deleteIfExists(claimed) }
                throw refused
            }
            name
        }

    /**
     * Takes the first name of this moment nothing else has taken.
     *
     * @return the name and the file now reserved under it.
     */
    private fun claimAName(moment: LocalMoment): Pair<String, Path> {
        repeat(SAFETY_BACKUP_NAME_ATTEMPTS) { attempt ->
            val name = safetyBackupFileName(moment, attempt + 1)
            val file = backupsDirectory.resolve(name)
            try {
                Files.createFile(file)
                return name to file
            } catch (taken: FileAlreadyExistsException) {
                // Somebody else has this second. Try the next name rather than
                // writing over a backup that may be the only copy of something.
            } catch (cannot: IOException) {
                throw BackupException(BackupFailure.NOT_WRITABLE, cannot)
            }
        }
        // Sixteen names of the same second all taken is a file that could not be
        // written, which the backup vocabulary already has a word and a sentence
        // for; a reason of its own here would be a distinction with nothing the
        // user could do differently about it.
        throw BackupException(BackupFailure.WRITE_FAILED)
    }
}

/**
 * What a failed safety backup is, in the words the backup already has for it.
 *
 * The distinctions the writer makes are kept rather than folded together, exactly
 * as the manual backup keeps them: a folder that cannot be written to and a disk
 * that went away call for different things, and this file is the one the user
 * will want most if the restore then does not happen.
 */
private fun backupFailureOf(failure: AtomicWriteFailure): BackupFailure =
    when (failure) {
        AtomicWriteFailure.NOT_WRITABLE -> BackupFailure.NOT_WRITABLE
        AtomicWriteFailure.TEMPORARY_FILE_FAILED -> BackupFailure.TEMPORARY_FILE_FAILED
        AtomicWriteFailure.TARGET_UNAVAILABLE -> BackupFailure.TARGET_UNAVAILABLE
        AtomicWriteFailure.WRITE_FAILED -> BackupFailure.WRITE_FAILED
        AtomicWriteFailure.NOT_ATOMIC -> BackupFailure.NOT_ATOMIC
    }
