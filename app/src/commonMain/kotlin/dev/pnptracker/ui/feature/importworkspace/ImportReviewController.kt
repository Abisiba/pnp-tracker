package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.EarlierImport
import dev.pnptracker.data.repository.ImportReview
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewWorkspace
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.flow.collect

/**
 * Drives the two-pane review of one saved import.
 *
 * The workspace is read as a stream rather than fetched, so marking a cell done
 * or adding a draft simply arrives; nothing here has to remember to reload, and
 * two views of the same import cannot drift apart.
 *
 * Nothing in this class creates a game, a cell or a task, and nothing moves the
 * import out of being a draft. Marking a cell reviewed and making a draft are
 * separate decisions: neither one triggers the other.
 */
class ImportReviewController(
    private val review: ImportReview,
) {
    var state: ImportReviewState by mutableStateOf(ImportReviewState.Loading)
        private set

    /** Imports the user can come back to, kept fresh by [observeDraftBatches]. */
    var draftBatches: List<EarlierImport> by mutableStateOf(emptyList())
        private set

    /**
     * The draft the user is writing, if any.
     *
     * Kept apart from [state], which mirrors the database: this is what someone is
     * typing and has not saved, so it must not be replaced when a new workspace
     * arrives, and it must leave no trace if they change their mind.
     */
    var composer: DraftComposer? by mutableStateOf(null)
        private set

    private var selectedBlockId: EntityId? = null
    private var lastWorkspace: ImportReviewWorkspace? = null

    /** True while a change is on its way to the database. */
    var isSaving: Boolean by mutableStateOf(false)
        private set

    /** Collects the list of resumable imports until cancelled. */
    suspend fun observeDraftBatches() {
        review.observeDraftBatches().collect { draftBatches = it }
    }

    /**
     * Collects one import's workspace until cancelled.
     *
     * The selection is cleared first: a cell of the import being left behind must
     * never stay selected in the one being opened.
     */
    suspend fun observe(batchId: EntityId) {
        selectedBlockId = null
        lastWorkspace = null
        composer = null
        state = ImportReviewState.Loading
        review.observeWorkspace(batchId).collect { workspace -> show(workspace) }
    }

    /** Puts a cell in focus. An unknown or absent id simply clears the selection. */
    fun select(blockId: EntityId?) {
        val workspace = lastWorkspace ?: return
        selectedBlockId = blockId?.takeIf { id -> workspace.rawBlocks.any { it.id == id } }
        show(workspace)
    }

    /**
     * Starts a draft from a cell, with the cell's text already in it.
     *
     * The text is a starting point and not a decision: everything the file said is
     * there, and the user cuts it down to the task they actually mean.
     */
    fun startDraft(blockId: EntityId) {
        val block = lastWorkspace?.rawBlocks?.firstOrNull { it.id == blockId } ?: return
        composer = DraftComposer(blockId = blockId, name = block.rawText)
    }

    fun editDraftName(name: String) {
        composer = composer?.copy(name = name)
    }

    /** Changes nothing anywhere; the draft was never written. */
    fun cancelDraft() {
        composer = null
    }

    /**
     * Saves the draft being written, if it says anything.
     *
     * A name that is empty or only spaces is refused before anything is written,
     * because a draft with no name tells the user nothing later. A draft that does
     * not save leaves the form open with what they typed still in it.
     */
    suspend fun saveDraft() {
        val current = composer ?: return
        if (!current.canSave || isSaving) return
        isSaving = true
        try {
            review.addDraftTask(current.blockId, current.name)
            composer = null
            clearFailure()
        } catch (failure: ImportReviewException) {
            reportFailure(failure)
        } finally {
            isSaving = false
        }
    }

    /**
     * Marks a cell reviewed, or takes the mark off again.
     *
     * Reversible on purpose: this records how far someone has read, and reading
     * can be revisited. If the write fails the old value stands and the screen
     * says so, because the stream will re-emit what the database really holds.
     */
    suspend fun setProcessed(
        blockId: EntityId,
        isProcessed: Boolean,
    ) {
        if (isSaving) return
        isSaving = true
        try {
            review.setProcessed(blockId, isProcessed)
            clearFailure()
        } catch (failure: ImportReviewException) {
            reportFailure(failure)
        } finally {
            isSaving = false
        }
    }

    /** A task draft in the making, before it is anywhere but on screen. */
    data class DraftComposer(
        val blockId: EntityId,
        val name: String,
    ) {
        val canSave: Boolean get() = name.isNotBlank()
    }

    private fun show(workspace: ImportReviewWorkspace?) {
        lastWorkspace = workspace
        state =
            when {
                workspace == null -> ImportReviewState.Unavailable
                workspace.rawBlocks.isEmpty() ->
                    ImportReviewState.Empty(workspace.fileName, workspace.sheetName)

                else -> {
                    // A cell can disappear under the selection only if the import
                    // was discarded elsewhere; the selection follows it out.
                    selectedBlockId = selectedBlockId?.takeIf { id -> workspace.rawBlocks.any { it.id == id } }
                    ImportReviewState.Content(
                        workspace = workspace,
                        selectedBlockId = selectedBlockId,
                        failure = (state as? ImportReviewState.Content)?.failure,
                    )
                }
            }
    }

    private fun clearFailure() {
        val content = state as? ImportReviewState.Content ?: return
        state = content.copy(failure = null)
    }

    private fun reportFailure(failure: ImportReviewException) {
        val content = state as? ImportReviewState.Content ?: return
        state = content.copy(failure = failure.failure)
    }
}
