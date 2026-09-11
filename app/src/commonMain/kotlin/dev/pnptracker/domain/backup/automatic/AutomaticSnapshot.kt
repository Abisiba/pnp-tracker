package dev.pnptracker.domain.backup.automatic

import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.restore.BackupInput
import dev.pnptracker.domain.time.LocalMoment

/**
 * A backup this application took without being asked, and proved it took.
 *
 * The import counterpart of
 * [dev.pnptracker.domain.backup.restore.SafetySnapshot], and the same idea: the
 * rows that went into the file, kept in memory so the transaction that follows
 * can read the database again and refuse to write over anything that moved in
 * between (PLAN 14.4.8).
 *
 * There is no public way to make one. It comes from [VerifiedSnapshotTaker] and
 * only after the file has been written *and* read back by the real reader, so a
 * confirmation cannot be handed something that merely claims to be a backup —
 * and code that never wrote one has nothing to hand it at all. That is PLAN
 * 14.4.13's "fail closed" as a property of the types rather than a rule somebody
 * has to remember.
 */
class AutomaticSnapshot internal constructor(
    /** The name it was written under, never a path (PLAN 14.4.7). */
    val fileName: String,
    val data: BackupData,
    val dataSha256: String,
)

/**
 * Why an automatic snapshot was not taken.
 *
 * Three, because there are three things that can go wrong and each says
 * something different about the machine: the database would not be read, the
 * folder would not take the file, or the file went down and did not come back up
 * as the backup it was written as. All three end the same way — the work that
 * was going to follow does not start — but a user who is told which one it was
 * can do something different about each.
 */
enum class SnapshotProblem {
    /** The database could not be read, so there was nothing to write. */
    DATABASE_NOT_READ,

    /** The file could not be put in the backups folder. */
    NOT_WRITTEN,

    /** The file was written and could not then be read back as a backup. */
    NOT_VERIFIED,
}

/**
 * Thrown when the automatic backup did not happen.
 *
 * Nothing of the user's has changed when this comes out: it is raised before the
 * work it was protecting begins, which is the whole of what it is for.
 */
class SnapshotNotTaken(
    val problem: SnapshotProblem,
    cause: Throwable? = null,
) : Exception("The automatic backup was not taken: $problem", cause)

/**
 * Takes the automatic backup that stands in front of an import (PLAN 14.4.8).
 *
 * An interface so the confirmation can be driven — and made to fail — without a
 * disk, and so nothing on the writing side has to know where backups live.
 */
interface AutomaticSnapshotTaker {
    /**
     * Reads the whole database, writes it, and proves the file is readable.
     *
     * @throws SnapshotNotTaken if any of the three did not happen. The caller
     *   must not go on: PLAN 14.4.13 has the import stop rather than run
     *   unprotected.
     */
    suspend fun takeBeforeImport(): AutomaticSnapshot
}

/**
 * Writes an automatic snapshot into the application's own backups folder.
 *
 * The user chooses nothing here — not the folder, not the name, not the moment.
 * What comes back is the written file offered as something to read again, which
 * is how verification reads what really landed on disk rather than the bytes
 * that were meant to (PLAN 14.4.7).
 */
interface AutomaticSnapshotWriter {
    /**
     * @return the file as the reader takes it: a name and its bytes, no path.
     * @throws dev.pnptracker.domain.backup.BackupException if it could not be
     *   written. Nothing half written is left behind when this throws.
     */
    suspend fun writeImportSnapshot(
        bytes: ByteArray,
        moment: LocalMoment,
    ): BackupInput
}
