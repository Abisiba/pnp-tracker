package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.importreview.ImportReviewWorkspace
import dev.pnptracker.domain.model.EntityId

/**
 * Where the review workspace is.
 *
 * The four cases are kept apart rather than folded into one nullable workspace,
 * because "still reading", "nothing to review", "no longer there" and "here it
 * is" ask the screen for four different things to say.
 */
sealed interface ImportReviewState {
    /** The first read has not come back yet. */
    data object Loading : ImportReviewState

    /**
     * The workspace, with whichever cell the user is looking at.
     *
     * [failure] is a change that did not save. It sits beside the content rather
     * than replacing it, because everything on screen is still true and still
     * usable — only the last change did not happen.
     */
    data class Content(
        val workspace: ImportReviewWorkspace,
        val selectedBlockId: EntityId?,
        val failure: ImportReviewFailure? = null,
    ) : ImportReviewState {
        val selectedBlock get() = workspace.blockOrNull(selectedBlockId)

        /** Drafts of the selected cell, or every draft when nothing is selected. */
        val visibleDrafts get() = selectedBlockId?.let(workspace::draftsOf) ?: workspace.draftTasks
    }

    /** The import is there but read no cells, so there is nothing to review. */
    data class Empty(
        val fileName: String,
        val sheetName: String,
    ) : ImportReviewState

    /** The import this workspace was opened for is not in the database any more. */
    data object Unavailable : ImportReviewState
}
