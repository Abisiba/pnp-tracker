package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.data.repository.UnfinishedImport
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.model.EntityId

/** Where reading the list of unfinished imports has got to. */
sealed interface UnfinishedImportsState {
    /** The list has not come back yet. */
    data object Loading : UnfinishedImportsState

    /**
     * The drafts, in the two groups PLAN 11.4.5 keeps apart.
     *
     * Both keep the list's own order, newest first.
     */
    data class Ready(
        val sound: List<UnfinishedImport>,
        val contradicting: List<UnfinishedImport>,
    ) : UnfinishedImportsState {
        val isEmpty: Boolean get() = sound.isEmpty() && contradicting.isEmpty()

        /** Every row on the screen, sound ones first, in the order they are drawn. */
        val drawnInOrder: List<UnfinishedImport> get() = sound + contradicting
    }

    /** Storage would not answer. Its own case, because it is not an empty list. */
    data object Unreadable : UnfinishedImportsState
}

/** Why a draft the user asked to open was not opened. */
enum class OpenRefusal {
    /** Its records contradict each other (PLAN 11.4.5); it cannot be opened or confirmed. */
    RECORDS_CONTRADICT,

    /** It is not there any more — removed since the list was read. */
    NO_LONGER_THERE,

    /** It has been confirmed since the list was read. */
    NO_LONGER_A_DRAFT,

    /** Storage would not answer; nothing was written. */
    COULD_NOT_READ,
}

/** Where the last attempt at opening a draft has got to. */
sealed interface OpenAttempt {
    data object Idle : OpenAttempt

    /** Its records are being read again before the review screen may have it. */
    data class Checking(
        val entry: UnfinishedImport,
    ) : OpenAttempt

    /** It was not opened, and why. Stays until the next attempt of either kind. */
    data class Refused(
        val entry: UnfinishedImport,
        val refusal: OpenRefusal,
    ) : OpenAttempt
}

/**
 * Where one attempt at removing a draft has got to.
 *
 * Each step its own case, as in [RollbackFlowState]: the combinations a set of
 * booleans allows — an outcome beside an offer, a refusal while a transaction
 * runs — are not states PLAN 11.4.5 has.
 */
sealed interface RemovalFlowState {
    /** No surface is open. */
    data object Closed : RemovalFlowState

    /** The question PLAN 11.4.5 wants asked is on screen. */
    data class Offered(
        val entry: UnfinishedImport,
    ) : RemovalFlowState

    /** The transaction is running. */
    data class Removing(
        val entry: UnfinishedImport,
    ) : RemovalFlowState

    /** It happened, and this is what it took. */
    data class Removed(
        val entry: UnfinishedImport,
        val outcome: DraftRemovalOutcome.Removed,
    ) : RemovalFlowState

    /** The engine refused, and nothing was written. */
    data class Refused(
        val entry: UnfinishedImport,
        val refusal: DraftRemovalRefusal,
    ) : RemovalFlowState

    /** Which draft the open surface is about, or null when none is open. */
    val openBatchId: EntityId?
        get() =
            when (this) {
                Closed -> null
                is Offered -> entry.batchId
                is Removing -> entry.batchId
                is Removed -> entry.batchId
                is Refused -> entry.batchId
            }

    /** True while the transaction is on its way to or from the database. */
    val isBusy: Boolean get() = this is Removing
}

/** Which row the keyboard should go back to, and to which of its buttons. */
data class RowFocus(
    val batchId: EntityId,
    val target: RowFocusTarget,
)

enum class RowFocusTarget {
    /** The row's own removal button — where the user came from. */
    REMOVE,

    /** The row's first button — a neighbour, after the row the user came from went. */
    FIRST,
}
