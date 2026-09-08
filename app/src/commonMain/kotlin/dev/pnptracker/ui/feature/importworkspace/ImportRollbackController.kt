package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.ImportRollback
import dev.pnptracker.data.repository.SettledImport
import dev.pnptracker.domain.importrollback.ImportRollbackException
import dev.pnptracker.domain.importrollback.ImportRollbackFailure
import dev.pnptracker.domain.importrollback.ImportRollbackPreview
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ImportBatchStatus

/**
 * Drives showing the imports that have been confirmed, and taking one back.
 *
 * It decides nothing about whether a rollback is safe. PLAN 11.4.4's rules live
 * in one pure function the engine calls twice — once for the preview this shows
 * and once inside the transaction — and a third opinion up here would be a
 * fourth answer waiting to disagree with them. What this class owns is only
 * which surface is open, that exactly one is, and that a second press cannot
 * start a second transaction.
 *
 * The preview is advisory and is treated as such: pressing the button calls the
 * engine again, which reads every row afresh. A cell edited while the user was
 * reading the confirmation comes back as a refusal, not as a rollback made on
 * yesterday's facts.
 */
class ImportRollbackController(
    private val rollback: ImportRollback,
) {
    var imports: SettledImportsState by mutableStateOf(SettledImportsState.Loading)
        private set

    var flow: RollbackFlowState by mutableStateOf(RollbackFlowState.Closed)
        private set

    /**
     * The row whose button opened the surface, so the keyboard can go back to it.
     *
     * A request rather than an action, exactly as [ImportReviewController]'s
     * focus token is: the controller knows which control the user came from, the
     * screen knows where it is drawn, and the token is cleared once it has been
     * honoured so it cannot fire a second time.
     */
    var lastAsked: EntityId? by mutableStateOf(null)
        private set

    /** Told by the screen that the keyboard has been put back where it came from. */
    fun focusHonoured() {
        lastAsked = null
    }

    /**
     * Collects the list of confirmed imports until cancelled.
     *
     * Cancelling the collection is the whole of the clean-up: nothing is
     * subscribed anywhere else and no scope is held here, so a screen that goes
     * away leaves nothing running behind it.
     */
    suspend fun observeSettledImports() {
        try {
            rollback.observeSettledImports().collect { settled ->
                imports = if (settled.isEmpty()) SettledImportsState.Empty else SettledImportsState.Ready(settled)
                reconcileOpenSurface(settled)
            }
        } catch (refused: ImportRollbackException) {
            // Only what the store turned into an answer reaches here; an
            // invariant broken anywhere below travels on untouched.
            imports = SettledImportsState.Unreadable
        }
    }

    /**
     * Opens the surface for one import and reads what taking it back would do.
     *
     * Refuses while any surface is open, so a double click and a repeated Enter
     * start one reading rather than two.
     */
    suspend fun ask(batchId: EntityId) {
        if (flow !is RollbackFlowState.Closed) return
        lastAsked = batchId
        flow = RollbackFlowState.Asking(batchId)

        val preview =
            try {
                rollback.previewRollback(batchId)
            } catch (refused: ImportRollbackException) {
                if (isStillReading(batchId)) flow = RollbackFlowState.Blocked(batchId, blockageOf(refused))
                return
            }
        // The list underneath can settle the surface while the preview is on its
        // way — an import taken back in another way, or gone. That answer is
        // newer than this one and is not overwritten by it.
        if (!isStillReading(batchId)) return
        flow =
            when (val blockage = blockageOf(preview)) {
                null -> RollbackFlowState.Offered(batchId, preview)
                else -> RollbackFlowState.Blocked(batchId, blockage)
            }
    }

    /**
     * Takes back the import the open surface is offering, once.
     *
     * Only from [RollbackFlowState.Offered], which is what makes a second press
     * do nothing: the first one moved the surface to [RollbackFlowState.TakingBack]
     * and there is no offer left to accept.
     */
    suspend fun takeBack() {
        val offered = flow as? RollbackFlowState.Offered ?: return
        flow = RollbackFlowState.TakingBack(offered.batchId, offered.preview)
        try {
            val result = rollback.rollBack(offered.batchId)
            flow = RollbackFlowState.TakenBack(offered.batchId, result)
        } catch (refused: ImportRollbackException) {
            // The panel stays open with what the user was looking at, and the
            // reason beside it: nothing was written, so there is nothing to
            // recover from and everything to explain.
            flow = RollbackFlowState.Refused(offered.batchId, offered.preview, blockageOf(refused))
        }
    }

    /**
     * Closes the open surface. Writes nothing, and undoes nothing.
     *
     * Refused while a reading or a transaction is in flight: closing then would
     * take away the only place its answer has to land, and Escape is not a way
     * to abandon a write that is already happening.
     */
    fun close() {
        if (flow.isBusy) return
        flow = RollbackFlowState.Closed
    }

    private fun isStillReading(batchId: EntityId): Boolean = (flow as? RollbackFlowState.Asking)?.batchId == batchId

    /**
     * Keeps an open surface truthful when the list underneath it moves.
     *
     * A new reading of the table is not a reason to close what somebody is
     * reading, so an offer that is still an offer is left exactly where it is.
     * What cannot be left is an offer for an import that is no longer there to
     * take back: pressing the button then would fail for a reason the screen
     * already knew, so the reason is shown instead.
     */
    private fun reconcileOpenSurface(settled: List<SettledImport>) {
        val batchId = flow.openBatchId ?: return
        // A finished rollback describes something that already happened, and a
        // running one owns its surface until it comes back.
        if (flow is RollbackFlowState.TakenBack || flow is RollbackFlowState.TakingBack) return

        val row = settled.firstOrNull { it.batchId == batchId }
        val failure =
            when {
                row == null -> ImportRollbackFailure.BATCH_NOT_FOUND
                row.status == ImportBatchStatus.ROLLED_BACK -> ImportRollbackFailure.ALREADY_ROLLED_BACK
                else -> return
            }
        flow = RollbackFlowState.Blocked(batchId, RollbackBlockage(failure))
    }

    private fun blockageOf(preview: ImportRollbackPreview): RollbackBlockage? {
        val failure = preview.blockingFailure ?: return null
        return RollbackBlockage(failure, preview.blockedTasks, preview.blockedCells)
    }

    private fun blockageOf(refused: ImportRollbackException) = RollbackBlockage(refused.failure, refused.blockedTasks, refused.blockedCells)
}
