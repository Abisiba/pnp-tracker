package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.UnfinishedImports
import dev.pnptracker.data.repository.UnfinishedImportsUnreadable
import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.ui.StaleSurfaces

/**
 * Drives the list of unfinished imports: continuing one, and removing one.
 *
 * It decides nothing about the drafts themselves. Whether a draft's records
 * agree is [DraftHealth], decided by the one classifier the confirmation also
 * uses; whether a removal may go ahead is decided inside the removal's own
 * transaction. What this class owns is which surface is open, that exactly one
 * is, that a second press cannot start a second transaction, and that nothing
 * reaches the review screen on the strength of a list that may be stale.
 */
class UnfinishedImportsController(
    private val imports: UnfinishedImports,
) : StaleSurfaces {
    var list: UnfinishedImportsState by mutableStateOf(UnfinishedImportsState.Loading)
        private set

    var opening: OpenAttempt by mutableStateOf(OpenAttempt.Idle)
        private set

    var removal: RemovalFlowState by mutableStateOf(RemovalFlowState.Closed)
        private set

    /**
     * A draft that passed its fresh check and may be opened now.
     *
     * A request rather than an action: the section decides what opening means,
     * and clears the token with [openHonoured] so it cannot fire twice.
     */
    var readyToOpen: EntityId? by mutableStateOf(null)
        private set

    /** Where the keyboard should go once the open surface has closed. */
    var focus: RowFocus? by mutableStateOf(null)
        private set

    private var neighbour: EntityId? = null

    fun openHonoured() {
        readyToOpen = null
    }

    fun focusHonoured() {
        focus = null
    }

    /**
     * Collects the list until cancelled.
     *
     * Cancelling the collection is the whole of the clean-up: no scope is held
     * here, so a screen that goes away leaves nothing running behind it.
     */
    suspend fun observeUnfinishedImports() {
        try {
            imports.observeUnfinishedImports().collect { rows ->
                list = UnfinishedImportsState.Ready(rows.filterNot { it.isContradicting }, rows.filter { it.isContradicting })
            }
        } catch (refused: UnfinishedImportsUnreadable) {
            // Only what the store turned into an answer; a defect travels on.
            list = UnfinishedImportsState.Unreadable
        }
    }

    /**
     * Opens one draft, but only once its records have been read again.
     *
     * The row was chosen from a list that describes the database as it was when
     * the list was read; between then and the click the draft may have been
     * removed, confirmed, or — through a restore — replaced by one whose records
     * contradict each other. None of those reaches the review screen.
     */
    suspend fun open(batchId: EntityId) {
        if (opening is OpenAttempt.Checking || removal !is RemovalFlowState.Closed) return
        val entry = rowOf(batchId) ?: return
        opening = OpenAttempt.Checking(entry)
        val refusal =
            try {
                when (imports.healthOf(batchId)) {
                    is DraftHealth.Sound -> null
                    is DraftHealth.Contradicting -> OpenRefusal.RECORDS_CONTRADICT
                    is DraftHealth.NotFound -> OpenRefusal.NO_LONGER_THERE
                    is DraftHealth.NotADraft -> OpenRefusal.NO_LONGER_A_DRAFT
                }
            } catch (unreadable: UnfinishedImportsUnreadable) {
                OpenRefusal.COULD_NOT_READ
            }
        if (refusal == null) {
            opening = OpenAttempt.Idle
            readyToOpen = batchId
        } else {
            opening = OpenAttempt.Refused(entry, refusal)
        }
    }

    /**
     * Opens the question for removing one draft.
     *
     * Only from [RemovalFlowState.Closed], so a double click and a repeated Enter
     * open one question rather than two.
     */
    fun askToRemove(batchId: EntityId) {
        if (removal !is RemovalFlowState.Closed || opening is OpenAttempt.Checking) return
        val entry = rowOf(batchId) ?: return
        opening = OpenAttempt.Idle
        removal = RemovalFlowState.Offered(entry)
    }

    /**
     * Removes the draft the open question is about, once.
     *
     * Only from [RemovalFlowState.Offered]: the first press moves the surface to
     * [RemovalFlowState.Removing], and a second press finds no offer to accept.
     * Whatever the engine answers is what is shown — it re-reads everything
     * inside its transaction, so a list that had gone stale cannot turn into a
     * removal made on old facts.
     */
    suspend fun remove() {
        val offered = removal as? RemovalFlowState.Offered ?: return
        removal = RemovalFlowState.Removing(offered.entry)
        // Worked out before the transaction, from the rows as they are drawn now:
        // once it commits, the new reading may arrive at any moment and the
        // removed row's place in it is gone.
        neighbour = neighbourOf(offered.entry.batchId)
        removal =
            when (val outcome = imports.remove(offered.entry.batchId)) {
                is DraftRemovalOutcome.Removed -> RemovalFlowState.Removed(offered.entry, outcome)
                is DraftRemovalOutcome.Refused -> RemovalFlowState.Refused(offered.entry, outcome.refusal)
            }
    }

    /**
     * Closes the open surface. Writes nothing.
     *
     * Refused while the transaction is running: closing then would take away the
     * only place its answer can land. The keyboard goes back to the row the user
     * came from — or, once that row has gone, to its neighbour.
     */
    fun close() {
        val closing = removal
        if (closing.isBusy || closing is RemovalFlowState.Closed) return
        focus =
            if (closing is RemovalFlowState.Removed) {
                neighbour?.let { RowFocus(it, RowFocusTarget.FIRST) }
            } else {
                closing.openBatchId?.let { RowFocus(it, RowFocusTarget.REMOVE) }
            }
        removal = RemovalFlowState.Closed
    }

    /** Lets go of every open surface; the drafts it was about belong to another database now. */
    override fun abandonOpenWork() {
        removal = RemovalFlowState.Closed
        opening = OpenAttempt.Idle
        readyToOpen = null
        focus = null
    }

    private fun rowOf(batchId: EntityId) = (list as? UnfinishedImportsState.Ready)?.drawnInOrder?.firstOrNull { it.batchId == batchId }

    /**
     * The row to put the keyboard on once [removed]'s row has gone: the one after
     * it on screen, or the one before when it was the last.
     */
    private fun neighbourOf(removed: EntityId): EntityId? {
        val rows = (list as? UnfinishedImportsState.Ready)?.drawnInOrder ?: return null
        val at = rows.indexOfFirst { it.batchId == removed }
        val remaining = rows.filterNot { it.batchId == removed }
        if (remaining.isEmpty()) return null
        if (at < 0) return null
        return remaining[at.coerceAtMost(remaining.lastIndex)].batchId
    }
}
