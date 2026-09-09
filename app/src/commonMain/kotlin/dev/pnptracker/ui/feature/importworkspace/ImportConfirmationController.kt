package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.ImportConfirmation
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.TargetCellChoice
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.onlyTrackingModeOf
import dev.pnptracker.ui.StaleSurfaces
import kotlinx.coroutines.flow.collect

/**
 * Drives turning one reviewed import into real tasks.
 *
 * Kept apart from [ImportReviewController] because the two answer different
 * questions: that one is about reading a file and taking notes on it, this one
 * is about writing to the production tables. Nothing here creates a game or an
 * cell; it only points drafts at the cells the user already opened.
 *
 * Everything this class shows is advisory. The decision is made by the store,
 * inside the transaction, which re-checks all of it.
 */
class ImportConfirmationController(
    private val confirmation: ImportConfirmation,
) : StaleSurfaces {
    var state: ImportConfirmationState by mutableStateOf(ImportConfirmationState.Loading)
        private set

    /** Every cell the user has opened that can hold a task, for the target picker. */
    var targetCells: List<TargetCellChoice> by mutableStateOf(emptyList())
        private set

    /** True while a change or a confirmation is on its way to the database. */
    var isBusy: Boolean by mutableStateOf(false)
        private set

    /**
     * True while the "are you sure" step is open.
     *
     * Opening it writes nothing and closing it writes nothing; it exists so that
     * confirming is a deliberate second action rather than one stray click.
     */
    var isAsking: Boolean by mutableStateOf(false)
        private set

    /** Whether the user has said to go ahead despite cells they never reviewed. */
    var hasAcknowledgedUnprocessed: Boolean by mutableStateOf(false)
        private set

    /** Collects the list of cells to aim drafts at, until cancelled. */
    suspend fun observeTargetCells() {
        confirmation.observeTargetCells().collect { targetCells = it }
    }

    /** Which import the refusal on screen was about, so it cannot outlive it. */
    private var failedBatchId: EntityId? = null

    /**
     * Reads what confirming this import would do now.
     *
     * Called again after every change, because the summary is a snapshot and
     * anything the user does to a draft changes it.
     *
     * A refusal already on screen is kept, so re-reading does not wipe out what
     * the user was told to fix — but only for the import it was about. Opening a
     * different one starts clean: a sentence about another file would be an
     * error the user cannot act on and cannot make go away.
     */
    suspend fun refresh(batchId: EntityId) {
        val summary = confirmation.summarize(batchId)
        val standing = (state as? ImportConfirmationState.Ready)?.failure?.takeIf { failedBatchId == batchId }
        if (standing == null) failedBatchId = null
        state =
            when {
                summary == null -> ImportConfirmationState.Unavailable
                else -> ImportConfirmationState.Ready(summary = summary, failure = standing)
            }
    }

    /**
     * Records where one draft's task will go.
     *
     * The tracking mode is not asked for when the pool allows only one: there is
     * nothing to choose, and the value is still set explicitly rather than being
     * left for the confirmation to guess. A pool that allows more than one keeps
     * the choice.
     */
    suspend fun aim(
        batchId: EntityId,
        draftId: EntityId,
        targetCellId: EntityId?,
        poolType: PoolType?,
        trackingMode: TrackingMode? = poolType?.let(::onlyTrackingModeOf),
    ) {
        if (isBusy) return
        isBusy = true
        try {
            confirmation.aimDraft(draftId, targetCellId, poolType, trackingMode)
            clearFailure()
            refresh(batchId)
        } catch (failure: ImportConfirmationException) {
            reportFailure(batchId, failure)
        } finally {
            isBusy = false
        }
    }

    /** Opens the "are you sure" step. Writes nothing. */
    fun ask() {
        if (isBusy) return
        isAsking = true
    }

    /** Closes the "are you sure" step without confirming. Writes nothing at all. */
    fun stopAsking() {
        isAsking = false
        hasAcknowledgedUnprocessed = false
    }

    /** Lets go of the "are you sure", which was about a draft this database no longer has. */
    override fun abandonOpenWork() {
        isAsking = false
        hasAcknowledgedUnprocessed = false
    }

    fun acknowledgeUnprocessed(acknowledged: Boolean) {
        hasAcknowledgedUnprocessed = acknowledged
    }

    /**
     * Confirms the import, once.
     *
     * A confirmation already in flight makes this do nothing, so a second click
     * cannot start a second one. A refusal is shown as such and never as a
     * half success: nothing was written when one comes back.
     */
    suspend fun confirm(batchId: EntityId) {
        if (isBusy) return
        isBusy = true
        try {
            val result = confirmation.confirm(batchId, hasAcknowledgedUnprocessed)
            isAsking = false
            hasAcknowledgedUnprocessed = false
            state = ImportConfirmationState.Confirmed(result)
        } catch (failure: ImportConfirmationException) {
            isAsking = false
            // The summary is re-read so the screen shows what the database
            // really holds now, with the refusal beside it.
            refresh(batchId)
            reportFailure(batchId, failure)
        } finally {
            isBusy = false
        }
    }

    private fun clearFailure() {
        failedBatchId = null
        val ready = state as? ImportConfirmationState.Ready ?: return
        state = ready.copy(failure = null)
    }

    private fun reportFailure(
        batchId: EntityId,
        failure: ImportConfirmationException,
    ) {
        failedBatchId = batchId
        val ready = state as? ImportConfirmationState.Ready ?: return
        state = ready.copy(failure = failure.failure)
    }
}
