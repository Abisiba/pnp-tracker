package dev.pnptracker.ui.feature.export

import dev.pnptracker.domain.export.ExportFailure

/**
 * Where an export has got to.
 *
 * Each step somebody can be waiting on, or answering, is its own case, so the
 * screen never has to work out from a handful of booleans whether a write is in
 * flight or whether an overwrite has been agreed to.
 */
sealed interface ExportScreenState {
    /** Nothing started. */
    data object Idle : ExportScreenState

    /** The save dialog is open. */
    data object ChoosingDestination : ExportScreenState

    /** Something is already there and the user has to say whether to replace it. */
    data class ConfirmingOverwrite(
        val fileName: String,
    ) : ExportScreenState

    /** The database is being read and the file written. */
    data object Writing : ExportScreenState

    /** The file is on disk. */
    data class Written(
        val fileName: String,
        val taskCount: Int,
    ) : ExportScreenState

    /** Nothing was written, and why. */
    data class Failed(
        val failure: ExportFailure,
    ) : ExportScreenState
}
