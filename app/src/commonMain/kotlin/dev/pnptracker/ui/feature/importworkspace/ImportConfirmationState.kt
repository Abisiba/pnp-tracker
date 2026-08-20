package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importconfirm.ImportConfirmationResult
import dev.pnptracker.domain.importconfirm.ImportConfirmationSummary

/**
 * Where confirming one import has got to.
 *
 * [Ready] carries a failure beside the summary rather than replacing it: a
 * refused confirmation leaves everything on screen still true, and the user has
 * to be able to see what to fix without losing what they were looking at.
 */
sealed interface ImportConfirmationState {
    /** The summary has not been read yet. */
    data object Loading : ImportConfirmationState

    /** The import is not in the database any more. */
    data object Unavailable : ImportConfirmationState

    /**
     * What confirming would do, and what stopped the last attempt if one was
     * refused. A confirmed import also lands here, reporting itself as such.
     */
    data class Ready(
        val summary: ImportConfirmationSummary,
        val failure: ImportConfirmationFailure? = null,
    ) : ImportConfirmationState

    /** A confirmation went through, with what it actually created. */
    data class Confirmed(
        val result: ImportConfirmationResult,
    ) : ImportConfirmationState
}
