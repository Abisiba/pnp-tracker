package dev.pnptracker.ui.feature.settings

import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.BackupSummary
import dev.pnptracker.domain.backup.restore.RestoreProblem

/**
 * Where putting a backup back has got to.
 *
 * A case for every step somebody can be waiting on or answering, and no booleans
 * beside them: "the safety backup is being written" and "the restore is being
 * applied" must never be able to be true at once, and a case each makes that
 * impossible rather than merely unlikely.
 *
 * The three steps after the confirmation are separate because they are three
 * different promises. While [CreatingSafetyBackup] and [WritingSafetyBackup] are
 * on screen not one row of the user's data has been touched; the moment
 * [Applying] appears, their way back exists on disk. Somebody watching a long
 * operation deserves to know which of those they are in.
 *
 * Checking the file is one state and not four. The reader answers with a single
 * result — size, encoding, JSON, envelope, checksum, values, graph and a
 * throwaway database, all of it or none of it — so there is no honest way to say
 * which gate it is at. A percentage or a list of ticks here would be invented,
 * and inventing progress on the screen that is about to replace somebody's data
 * is exactly the wrong place to start.
 */
sealed interface RestoreScreenState {
    /** Nothing started. */
    data object Idle : RestoreScreenState

    /** The open dialog is on screen. */
    data object ChoosingSource : RestoreScreenState

    /** The file is being read and checked. Nothing has been decided. */
    data object Validating : RestoreScreenState

    /**
     * The file is a backup, and this is the question.
     *
     * [token] belongs to the one [dev.pnptracker.domain.backup.restore.ValidatedBackup]
     * this question was asked about. An answer carrying any other token has been
     * overtaken — another file chosen, the question already answered — and
     * applies to nothing.
     */
    data class Confirming(
        val token: Int,
        val summary: BackupSummary,
    ) : RestoreScreenState

    /** The database is being read for the safety backup. Nothing is on disk yet. */
    data object CreatingSafetyBackup : RestoreScreenState

    /** The safety backup is going to disk. The live data is still untouched. */
    data object WritingSafetyBackup : RestoreScreenState

    /** The safety backup is written, and the one transaction is running. */
    data class Applying(
        val safetyFileName: String,
    ) : RestoreScreenState

    /** The database now holds the backup. */
    data class Restored(
        val fileName: String,
        val safetyFileName: String,
    ) : RestoreScreenState

    /** The file cannot be used, and why. Nothing was read from the database. */
    data class Rejected(
        val problem: BackupProblem,
    ) : RestoreScreenState

    /**
     * The file was good and the restore did not happen, and why.
     *
     * [safetyFileName] is the file already on disk when there is one. It is null
     * only when the failure was making that file in the first place, which is
     * also the only case in which nothing at all was written.
     */
    data class Failed(
        val problem: RestoreProblem,
        val safetyFileName: String?,
    ) : RestoreScreenState

    /** True while something is in flight, so the screen can refuse a second one. */
    val isBusy: Boolean
        get() =
            this is ChoosingSource ||
                this is Validating ||
                this is CreatingSafetyBackup ||
                this is WritingSafetyBackup ||
                this is Applying

    /**
     * True once the user has agreed and the work cannot be called off.
     *
     * From here the flow owns itself: Escape, a click outside and a second press
     * all do nothing, because there is no half of this that could be abandoned
     * safely (PLAN 14.4.3).
     */
    val isUnstoppable: Boolean
        get() = this is CreatingSafetyBackup || this is WritingSafetyBackup || this is Applying
}
