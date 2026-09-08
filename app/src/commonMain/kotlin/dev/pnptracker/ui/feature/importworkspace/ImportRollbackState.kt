package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.data.repository.SettledImport
import dev.pnptracker.domain.importrollback.BlockedCell
import dev.pnptracker.domain.importrollback.BlockedTask
import dev.pnptracker.domain.importrollback.ImportRollbackFailure
import dev.pnptracker.domain.importrollback.ImportRollbackPreview
import dev.pnptracker.domain.importrollback.ImportRollbackResult
import dev.pnptracker.domain.model.EntityId

/**
 * What stands in the way of taking one import back, however it was found out.
 *
 * The engine says it twice in two shapes — as the refusal on a preview and as
 * the exception a transaction throws — and the screen has one thing to draw for
 * both. Gathering them here is the whole of the difference: no rule is decided
 * again, and nothing is added that the engine did not say.
 */
data class RollbackBlockage(
    val failure: ImportRollbackFailure,
    val blockedTasks: List<BlockedTask> = emptyList(),
    val blockedCells: List<BlockedCell> = emptyList(),
)

/** Where reading the list of confirmed imports has got to. */
sealed interface SettledImportsState {
    /** The list has not come back yet. */
    data object Loading : SettledImportsState

    /** There are none: nothing has been confirmed yet. */
    data object Empty : SettledImportsState

    data class Ready(
        val imports: List<SettledImport>,
    ) : SettledImportsState

    /** Storage would not answer. Its own case, because it is not an empty list. */
    data object Unreadable : SettledImportsState
}

/**
 * Where one attempt at taking an import back has got to.
 *
 * Each step is its own case rather than a handful of booleans, because the
 * combinations booleans allow — a refusal shown beside an offer, a success and a
 * blockage at once — are states PLAN 11.4.4 does not have. Exactly one import is
 * being decided about at a time.
 */
sealed interface RollbackFlowState {
    /** No surface is open. */
    data object Closed : RollbackFlowState

    /** The preview is being read; nothing has been written and nothing will be. */
    data class Asking(
        val batchId: EntityId,
    ) : RollbackFlowState

    /** The import can be taken back, and this is what it would cost. */
    data class Offered(
        val batchId: EntityId,
        val preview: ImportRollbackPreview,
    ) : RollbackFlowState

    /** It cannot be taken back, and this is what is in the way. */
    data class Blocked(
        val batchId: EntityId,
        val blockage: RollbackBlockage,
    ) : RollbackFlowState

    /** The transaction is running. */
    data class TakingBack(
        val batchId: EntityId,
        val preview: ImportRollbackPreview,
    ) : RollbackFlowState

    /**
     * The transaction refused, and nothing was written.
     *
     * The preview it was offered under is kept, so the panel can stay open
     * showing what the user was looking at when they pressed the button, with
     * the reason it did not happen beside it.
     */
    data class Refused(
        val batchId: EntityId,
        val preview: ImportRollbackPreview,
        val blockage: RollbackBlockage,
    ) : RollbackFlowState

    /** It happened, and this is what it did. */
    data class TakenBack(
        val batchId: EntityId,
        val result: ImportRollbackResult,
    ) : RollbackFlowState

    /** Which import the open surface is about, or null when none is open. */
    val openBatchId: EntityId?
        get() =
            when (this) {
                Closed -> null
                is Asking -> batchId
                is Offered -> batchId
                is Blocked -> batchId
                is TakingBack -> batchId
                is Refused -> batchId
                is TakenBack -> batchId
            }

    /** True while something is on its way to or from the database. */
    val isBusy: Boolean get() = this is Asking || this is TakingBack
}
