package dev.pnptracker.domain.backup.retention

/** The fewest automatic backups of one kind that may be kept (PLAN 14.4.12). */
const val MINIMUM_AUTOMATIC_BACKUPS: Int = 1

/** The most (PLAN 14.4.12). */
const val MAXIMUM_AUTOMATIC_BACKUPS: Int = 50

/** What is kept until the user has said otherwise (PLAN 14.4.12). */
const val DEFAULT_AUTOMATIC_BACKUPS: Int = 7

/**
 * One file in the backups folder, as much as rotation is allowed to know of it.
 *
 * [ordinaryFile] is false for a directory, a symbolic link, a pipe and anything
 * else that is not a plain file. It is carried rather than assumed because the
 * whole point of rotation is that it deletes things: PLAN 14.4.11 will not have
 * it following a link out of the folder, and a link is refused here rather than
 * resolved.
 *
 * [header] is the first [BACKUP_HEADER_BYTES] of the file, or empty when it
 * could not be read. Empty means the file is not shown to be ours, which means
 * it stays.
 */
data class InspectedBackupFile(
    val name: AutomaticBackupName,
    val ordinaryFile: Boolean,
    val header: ByteArray,
) {
    // A data class over a ByteArray gets identity equality for the array, which
    // is not what anybody reading `==` on this would expect. Both are written
    // out rather than left to surprise somebody later.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InspectedBackupFile) return false
        return name == other.name && ordinaryFile == other.ordinaryFile && header.contentEquals(other.header)
    }

    override fun hashCode(): Int = 31 * (31 * name.hashCode() + ordinaryFile.hashCode()) + header.contentHashCode()
}

/**
 * One automatic backup, whole: a single document, or a migration's matched pair.
 *
 * Rotation counts and deletes these rather than files, because PLAN 14.4.11
 * makes a migration's `.db` and `.json` one thing. Counting files would keep
 * half as many migrations as the user asked for, and deleting files would leave
 * an orphan that the next rotation then refuses to touch for ever.
 */
data class OwnedBackup(
    val kind: AutomaticBackupKind,
    val setName: String,
    val stamp: String,
    val attempt: Int,
    /** Every file this backup is made of: one name, or two for a migration. */
    val fileNames: List<String>,
)

/** What one pass of rotation did. */
data class RotationOutcome(
    /** Files that are no longer there because this removed them. */
    val removed: List<String> = emptyList(),
    /**
     * Files this tried to remove and could not.
     *
     * Never a failure of the work rotation followed. PLAN 14.4.13 is explicit
     * that an old backup which will not go away puts nobody's data at risk, so
     * it is reported and not thrown: the import or the migration that has just
     * been protected carries on, and the next rotation tries again.
     */
    val couldNotRemove: List<String> = emptyList(),
    /**
     * True when nothing was removed because the new backup could not be found.
     *
     * The structural half of "no old file goes before the new one is safely
     * written" (PLAN 14.4.11). See [AutomaticBackupRotation.rotateAfter].
     */
    val refused: Boolean = false,
)

/** The folder rotation works in, seen from the side that has no files. */
interface BackupDirectory {
    /**
     * Every file in the folder whose **name** this application would have
     * written, with what is needed to tell whether it really did.
     *
     * Names that are not ours are not listed. Nothing outside the folder is
     * looked at, and no link is followed out of it.
     */
    suspend fun inspect(): List<InspectedBackupFile>

    /** @return true when [fileName] is gone; false is reported, never thrown. */
    suspend fun remove(fileName: String): Boolean
}

/**
 * Keeps the newest few automatic backups of each kind and removes the rest.
 *
 * Three counts, not one (PLAN 14.4.11). Import snapshots, pre-restore safety
 * backups and migration sets are kept apart and each keeps its own newest few,
 * so an afternoon of imports cannot quietly evict the one file somebody would
 * need to undo a restore. Manual backups are in neither count and are never
 * reachable from here: `automaticBackupNameOf` does not answer for them, so they
 * are not even listed.
 *
 * Nothing here writes a backup. What it deletes is decided entirely from names
 * and from the first few bytes of files, and it keeps no record between runs —
 * every pass reads the folder again, which is what makes it safe to run after a
 * crash and safe to run twice (PLAN 14.4.11).
 *
 * The retention count is a parameter rather than something this reads. Where the
 * number comes from is the settings file's business and that is the next slice's
 * work; passing it in keeps this decidable, testable and unable to be wrong
 * about a value it never owned.
 */
class AutomaticBackupRotation(
    private val directory: BackupDirectory,
) {
    /**
     * Removes what is now surplus, having first checked the new backup is there.
     *
     * [justWritten] is the set name of the backup whose successful, verified
     * write is the reason this is running. It is not decoration: this looks for
     * it among the backups it can prove are its own, and **deletes nothing at
     * all** if it is not there. That turns PLAN 14.4.11's "no old file goes
     * before the new one is complete" from a rule somebody has to remember into
     * one this cannot be made to break — a caller who has not written and
     * verified anything has no set name to offer, and an invented one removes
     * nothing.
     *
     * @param keep how many of each kind to keep, the new one included.
     */
    suspend fun rotateAfter(
        justWritten: String,
        keep: Int,
    ): RotationOutcome {
        require(keep >= MINIMUM_AUTOMATIC_BACKUPS) { "Keeping fewer than $MINIMUM_AUTOMATIC_BACKUPS is not retention" }
        require(keep <= MAXIMUM_AUTOMATIC_BACKUPS) { "Keeping more than $MAXIMUM_AUTOMATIC_BACKUPS was not asked for" }

        val owned = ownedBackupsIn(directory.inspect())
        if (owned.none { it.setName == justWritten }) return RotationOutcome(refused = true)

        val removed = mutableListOf<String>()
        val couldNotRemove = mutableListOf<String>()
        surplusOf(owned, keep).forEach { backup ->
            backup.fileNames.forEach { fileName ->
                if (directory.remove(fileName)) removed += fileName else couldNotRemove += fileName
            }
        }
        return RotationOutcome(removed = removed, couldNotRemove = couldNotRemove)
    }
}

/**
 * The backups among [inspected] this application can show are its own.
 *
 * Both halves of PLAN 14.4.11's test are applied here, and a file has to pass
 * both. Anything else — an odd file type, a document that does not begin the way
 * the writer begins one, a migration missing its other half — simply does not
 * appear in the answer, and what does not appear is never deleted.
 */
internal fun ownedBackupsIn(inspected: List<InspectedBackupFile>): List<OwnedBackup> {
    val usable = inspected.filter { it.ordinaryFile }
    val documents = usable.filter { it.name.extension != MIGRATION_DATABASE_EXTENSION }
    val plain =
        documents
            .filter { it.name.kind != AutomaticBackupKind.MIGRATION && beginsLikeABackupDocument(it.header) }
            .map { file ->
                OwnedBackup(
                    kind = file.name.kind,
                    setName = file.name.setName,
                    stamp = file.name.stamp,
                    attempt = file.name.attempt,
                    fileNames = listOf(file.name.fileName),
                )
            }
    return plain + migrationSetsIn(usable)
}

/**
 * The migration sets that are whole.
 *
 * A set is two files that name each other by sharing everything but the
 * extension. One without the other is not half a set but a thing of unknown
 * standing: it may be a write that was interrupted, or the survivor of a
 * deletion that failed part way. PLAN 14.4.11 will not have either removed on a
 * guess, so an unpaired file is left exactly where it is.
 */
private fun migrationSetsIn(usable: List<InspectedBackupFile>): List<OwnedBackup> =
    usable
        .filter { it.name.kind == AutomaticBackupKind.MIGRATION }
        .groupBy { it.name.setName }
        .mapNotNull { (setName, files) ->
            val document = files.singleOrNull { it.name.extension != MIGRATION_DATABASE_EXTENSION } ?: return@mapNotNull null
            val database = files.singleOrNull { it.name.extension == MIGRATION_DATABASE_EXTENSION } ?: return@mapNotNull null
            val wasOn = database.name.fromSchemaVersion ?: return@mapNotNull null
            if (!beginsLikeABackupDocument(document.header)) return@mapNotNull null
            if (!beginsLikeADatabaseOfVersion(database.header, wasOn)) return@mapNotNull null
            OwnedBackup(
                kind = AutomaticBackupKind.MIGRATION,
                setName = setName,
                stamp = document.name.stamp,
                attempt = document.name.attempt,
                fileNames = listOf(database.name.fileName, document.name.fileName).sorted(),
            )
        }

/**
 * The backups past the newest [keep] of their own kind.
 *
 * Ordered by the stamp and then the ordinal, both read out of the name, because
 * PLAN 14.4.11 refuses to sort by `mtime`: copying a folder, restoring it from
 * somewhere else or syncing it rewrites every modification time and rewrites no
 * name. The ordinal is compared as a number so that the tenth backup of one
 * second comes after the second rather than before it, and the set name settles
 * the rest so that two runs over one folder always choose the same files.
 */
private fun surplusOf(
    owned: List<OwnedBackup>,
    keep: Int,
): List<OwnedBackup> =
    owned
        .groupBy { it.kind }
        .values
        .flatMap { ofOneKind ->
            ofOneKind
                .sortedWith(compareByDescending<OwnedBackup> { it.stamp }.thenByDescending { it.attempt }.thenByDescending { it.setName })
                .drop(keep)
        }
