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
) {
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

    /**
     * Reads what confirming this import would do now.
     *
     * Called again after every change, because the summary is a snapshot and
     * anything the user does to a draft changes it.
     */
    suspend fun refresh(batchId: EntityId) {
        val summary = confirmation.summarize(batchId)
        state =
            when {
                summary == null -> ImportConfirmationState.Unavailable
                else ->
                    ImportConfirmationState.Ready(
                        summary = summary,
                        failure = (state as? ImportConfirmationState.Ready)?.failure,
                    )
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
            reportFailure(failure)
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
            reportFailure(failure)
        } finally {
            isBusy = false
        }
    }

    private fun clearFailure() {
        val ready = state as? ImportConfirmationState.Ready ?: return
        state = ready.copy(failure = null)
    }

    private fun reportFailure(failure: ImportConfirmationException) {
        val ready = state as? ImportConfirmationState.Ready ?: return
        state = ready.copy(failure = failure.failure)
    }
}
