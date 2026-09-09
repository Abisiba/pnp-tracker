package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.BackupData

/**
 * Why a backup that was good could still not be put back.
 *
 * Separate from [BackupProblem] because the two answer different questions. A
 * [BackupProblem] says the file cannot be used and the remedy is another file; a
 * [RestoreProblem] says the file was fine and something about this machine, this
 * moment or this database stopped it, and the remedy is almost always to try
 * again. Folding them together would put "choose another backup" in front of
 * somebody whose backup is perfectly good.
 *
 * Every one of them leaves the user's data exactly as it was. That is not a
 * coincidence of the current code but the shape of the flow: nothing is written
 * until the safety backup is on disk, and what is written after that is one
 * transaction.
 */
enum class RestoreProblem {
    /** The database could not be read, so there is no safety backup to fall back on. */
    SAFETY_BACKUP_NOT_MADE,

    /** The safety backup could not be put on disk, so the restore never started. */
    SAFETY_BACKUP_NOT_WRITTEN,

    /**
     * The data changed between the safety backup and the transaction.
     *
     * The backup on disk no longer describes the database it was taken from, so
     * going ahead would replace something nobody has a copy of.
     */
    DATA_CHANGED_MEANWHILE,

    /** The transaction would not go through; every table is as it was. */
    COULD_NOT_APPLY,

    /** The rows went in but did not hold together, so all of them were taken out again. */
    REFERENCES_NOT_WHOLE,

    /**
     * The rows went in and the database could not then be read to confirm it.
     *
     * The one outcome here that does not say what the data is now. It is not the
     * postcondition failing — that happens inside the transaction and rolls the
     * whole thing back — but storage refusing to answer afterwards, which says
     * nothing either way. The safety backup is the way back and the message says
     * so.
     */
    NOT_VERIFIED_AFTERWARDS,
}

/**
 * The live database as it stood when the safety backup was taken.
 *
 * Held in memory across the write of the safety file and handed to the
 * transaction, which reads the fifteen tables again as its first act and refuses
 * to write anything if they no longer say this. Without it there is a window —
 * short, but real — in which a change made while the safety file was being
 * written would be replaced by the backup and exist in no file anywhere.
 *
 * There is no public way to make one: it comes from the same reading that
 * produced the safety backup's bytes, so the two cannot describe different
 * moments.
 */
class SafetySnapshot internal constructor(
    val fileName: String,
    val data: BackupData,
    val dataSha256: String,
)

/**
 * Puts a validated backup into the live database, or refuses to.
 *
 * The only production route from a backup file to somebody's data, and it is
 * deliberately narrow: it takes a [ValidatedBackup], which cannot be constructed
 * outside the reader, and a [SafetySnapshot], which cannot be constructed outside
 * the safety backup. Raw records, a parsed document, a file name or a path
 * cannot be handed to it at all.
 */
interface BackupRestorer {
    /**
     * @return the reason nothing was written, or null when the database now
     *   holds exactly what [backup] says.
     */
    suspend fun restore(
        backup: ValidatedBackup,
        asItWas: SafetySnapshot,
    ): RestoreProblem?
}
