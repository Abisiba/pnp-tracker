package dev.pnptracker.ui.feature.settings

import dev.pnptracker.domain.backup.BackupFailure

/**
 * Where saving a backup has got to.
 *
 * Each step somebody can be waiting on, or answering, is its own case. Two
 * booleans would let "the database is being read" and "the file is being
 * written" be true at once, and there is no such moment; a case each means the
 * screen reads the state rather than working it out.
 *
 * Reading the database and writing the file are separate on purpose. They fail
 * for different reasons and they take different amounts of time, and a user
 * waiting on a large library deserves to be told which of the two is happening.
 */
sealed interface BackupScreenState {
    /** Nothing started. */
    data object Idle : BackupScreenState

    /** The save dialog is open. */
    data object ChoosingDestination : BackupScreenState

    /** Something is already there and the user has to say whether to replace it. */
    data class ConfirmingOverwrite(
        val fileName: String,
    ) : BackupScreenState

    /** The database is being read and the document built. Nothing is on disk yet. */
    data object Preparing : BackupScreenState

    /** The bytes are going to the file. */
    data class Writing(
        val fileName: String,
    ) : BackupScreenState

    /** The backup is on disk. */
    data class Saved(
        val fileName: String,
    ) : BackupScreenState

    /** Nothing was written, and why. */
    data class Failed(
        val failure: BackupFailure,
    ) : BackupScreenState

    /** True while an action is in flight, so the screen can refuse a second one. */
    val isBusy: Boolean
        get() = this is ChoosingDestination || this is Preparing || this is Writing
}
