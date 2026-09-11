package dev.pnptracker.platform.backupfiles

import dev.pnptracker.domain.backup.BACKUP_EXTENSION
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.retention.MIGRATION_DATABASE_EXTENSION
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.AtomicWriteException
import dev.pnptracker.platform.files.AtomicWriteFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

/** What a half-written automatic backup is called while it is still half written. */
internal const val AUTOMATIC_BACKUP_SUFFIX = ".json.part"

/**
 * How many names one moment may be asked for before this gives up.
 *
 * A bound rather than a loop that keeps going: a directory that answers "already
 * there" to every name it is offered is a directory something is wrong with, and
 * trying for ever would hang the work instead of failing it. Two automatic
 * backups within one second of each other is already unusual; sixteen is not
 * something to keep trying past.
 */
internal const val AUTOMATIC_NAME_ATTEMPTS: Int = 16

/**
 * Writes a file under the first name of this moment nothing else has taken.
 *
 * Shared by both backups the application takes without being asked — the one in
 * front of a restore and the one in front of an import — because the rule is the
 * same for both and a second copy of it would be a second chance to get it
 * wrong.
 *
 * The name is claimed before the bytes are written, and claimed by creating the
 * file rather than by asking whether it exists. Asking first and writing after is
 * two steps with a gap in the middle, and a second backup starting inside that
 * gap would be told the name is free and then replace the first one's file —
 * silently, because an atomic move replaces whatever it lands on. Creating the
 * file is the file system's own answer to "is this name mine", given once and
 * indivisibly, so two attempts at the same second get different names.
 *
 * The claim is a real, empty file, and the atomic move lands on top of it. If
 * anything goes wrong between the two the claim is removed again, so a failed
 * backup leaves neither an empty `.json` that looks like one nor a `.part`
 * beside it.
 */
internal class ClaimedNameWriter(
    private val folder: Path,
    private val writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = AUTOMATIC_BACKUP_SUFFIX),
    private val attempts: Int = AUTOMATIC_NAME_ATTEMPTS,
) {
    /**
     * @param nameFor the name for a given attempt, 1 upwards.
     * @return the name it was written under and the file it now is.
     * @throws BackupException if no name could be claimed or the bytes could not
     *   be written.
     */
    suspend fun write(
        bytes: ByteArray,
        nameFor: (Int) -> String,
    ): Pair<String, Path> =
        withContext(Dispatchers.IO) {
            val (name, claimed) = claimAName(nameFor)
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
            name to claimed
        }

    private fun claimAName(nameFor: (Int) -> String): Pair<String, Path> {
        repeat(attempts) { attempt ->
            val name = nameFor(attempt + 1)
            val file = folder.resolve(name)
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
 * The two names one migration snapshot set is known by, both taken at once.
 *
 * A set is two files and PLAN 14.4.11 requires them to carry the **same**
 * ordinal, so they cannot be claimed one at a time and hoped to agree: the
 * `.db` could take the plain name while something else already held the `.json`,
 * and what was left would be two halves that nothing can pair up again.
 *
 * So an attempt is all or nothing. Both names are created for the same ordinal,
 * and if the second one is already taken the first is given back before the next
 * ordinal is tried. What comes out is a pair that nothing else can be holding,
 * claimed by the same indivisible [Files.createFile] a single backup uses.
 */
internal class ClaimedSetNames(
    val setName: String,
    val database: Path,
    val document: Path,
)

/**
 * Takes the first pair of names for this set that nothing else has taken.
 *
 * @param nameFor the set name, without extension, for a given attempt from 1 up.
 * @throws BackupException if no pair could be claimed.
 */
internal fun claimSetNames(
    folder: Path,
    attempts: Int = AUTOMATIC_NAME_ATTEMPTS,
    nameFor: (Int) -> String,
): ClaimedSetNames {
    repeat(attempts) { attempt ->
        val setName = nameFor(attempt + 1)
        val database = folder.resolve("$setName$MIGRATION_DATABASE_EXTENSION")
        val document = folder.resolve("$setName$BACKUP_EXTENSION")
        val tookTheDatabase =
            try {
                Files.createFile(database)
                true
            } catch (taken: FileAlreadyExistsException) {
                false
            } catch (cannot: IOException) {
                throw BackupException(BackupFailure.NOT_WRITABLE, cannot)
            }
        if (!tookTheDatabase) return@repeat
        try {
            Files.createFile(document)
            return ClaimedSetNames(setName, database, document)
        } catch (taken: FileAlreadyExistsException) {
            // Half a claim is worse than none: give back the half we took so the
            // folder does not collect empty files nobody will ever pair up.
            runCatching { Files.deleteIfExists(database) }
        } catch (cannot: IOException) {
            runCatching { Files.deleteIfExists(database) }
            throw BackupException(BackupFailure.NOT_WRITABLE, cannot)
        }
    }
    throw BackupException(BackupFailure.WRITE_FAILED)
}

/**
 * What a failed automatic backup is, in the words the backup already has for it.
 *
 * The distinctions the writer makes are kept rather than folded together, exactly
 * as the manual backup keeps them: a folder that cannot be written to and a disk
 * that went away call for different things, and these are the files a user will
 * want most if what follows then does not happen.
 */
private fun backupFailureOf(failure: AtomicWriteFailure): BackupFailure =
    when (failure) {
        AtomicWriteFailure.NOT_WRITABLE -> BackupFailure.NOT_WRITABLE
        AtomicWriteFailure.TEMPORARY_FILE_FAILED -> BackupFailure.TEMPORARY_FILE_FAILED
        AtomicWriteFailure.TARGET_UNAVAILABLE -> BackupFailure.TARGET_UNAVAILABLE
        AtomicWriteFailure.WRITE_FAILED -> BackupFailure.WRITE_FAILED
        AtomicWriteFailure.NOT_ATOMIC -> BackupFailure.NOT_ATOMIC
    }
