package dev.pnptracker.domain.importreview

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode

/**
 * One source cell as the review screen sees it.
 *
 * [rawText] is carried through untouched — line breaks, `**` markers, leading and
 * trailing spaces and all — because the whole point of the left pane is to show
 * the user what the file really said.
 *
 * [completionTargetGameId] is the game an accepted green cell was said to be
 * about. It comes back from the database rather than from anywhere on screen, so
 * closing the review and opening it again finds the same answer.
 */
data class ReviewRawBlock(
    val id: EntityId,
    val rawText: String,
    val sheetName: String,
    val rowIndex: Int,
    val columnIndex: Int,
    val sourceColumnType: SourceColumnType,
    val fillColorArgb: Int? = null,
    val gameCompletionHint: HintDecision = HintDecision.NONE,
    val completionTargetGameId: EntityId? = null,
    val isProcessed: Boolean = false,
) {
    /**
     * True for an acceptance that does not say which game it is about.
     *
     * The one shape a version 5 database can hand over: it recorded that the
     * user said yes but had nowhere to put the game they meant. Reported as a
     * question still open rather than quietly turned back into a no.
     */
    val needsCompletionTarget: Boolean
        get() = gameCompletionHint == HintDecision.ACCEPTED && completionTargetGameId == null
}

/**
 * One task draft as the review screen sees it.
 *
 * The three `selected` fields are what the user has decided so far; they are all
 * needed before the draft can become a real task, and the screen shows which are
 * still missing. [materializedTaskId] is set once a confirmation has turned this
 * draft into a task, which is also what makes the draft read only.
 *
 * [colorIds] is in the user's own order, which is the order a single-item
 * multi-colour task is drawn in (PLAN 5.10). An empty list is a real answer, not
 * an unanswered question: PLAN 11.6 leaves a task colourless when the source
 * text never said which colour it meant.
 */
data class ReviewDraftTask(
    val id: EntityId,
    val rawImportBlockId: EntityId,
    val name: String,
    val suggestedPoolType: PoolType? = null,
    val completionHint: HintDecision = HintDecision.NONE,
    val targetCellId: EntityId? = null,
    val selectedPoolType: PoolType? = null,
    val selectedTrackingMode: TrackingMode? = null,
    /** The total, or null for one the user has left unknown (PLAN 11.7). */
    val requiredQuantity: Int? = null,
    /** The user's own words, kept exactly, or null when they wrote none. */
    val notes: String? = null,
    val selectionStartIndex: Int? = null,
    val selectionEndIndex: Int? = null,
    /** PLAN 10: came from the `Eksik` column. A note about the work, not a shortage. */
    val isMissing: Boolean = false,
    /** PLAN 10: came from the `Ödünç Parçalar` column. */
    val isBorrowed: Boolean = false,
    /** PLAN 11.7: something the work needs is still unknown. */
    val needsInfo: Boolean = false,
    /** PLAN 10: the pool will be the user's own decision rather than the column's. */
    val needsClassification: Boolean = false,
    val colorIds: List<EntityId> = emptyList(),
    val materializedTaskId: EntityId? = null,
) {
    /** True when the draft has everything a task needs. */
    val isReady: Boolean
        get() =
            targetCellId != null &&
                selectedPoolType != null &&
                selectedTrackingMode != null &&
                completionHint != HintDecision.PENDING

    /** True when the draft was cut out of the cell rather than typed by hand. */
    val cameFromSelection: Boolean get() = selectionStartIndex != null && selectionEndIndex != null
}

/**
 * Everything the two panes of the review screen show, for one import.
 *
 * Both lists arrive already ordered and already restricted to this import, so the
 * screen never has to filter and can never show a cell or a draft belonging to a
 * different file.
 *
 * Nothing here creates a game, a cell or a task. Reviewing is reading and note
 * taking; turning any of it into real records is a later step.
 */
data class ImportReviewWorkspace(
    val batchId: EntityId,
    val fileName: String,
    val sheetName: String,
    val status: ImportBatchStatus,
    val rawBlocks: List<ReviewRawBlock>,
    val draftTasks: List<ReviewDraftTask>,
) {
    init {
        require(draftTasks.all { draft -> rawBlocks.any { it.id == draft.rawImportBlockId } }) {
            "Every draft in a workspace belongs to one of its raw cells."
        }
    }

    val processedBlockCount: Int get() = rawBlocks.count { it.isProcessed }

    val rawBlockCount: Int get() = rawBlocks.size

    val draftTaskCount: Int get() = draftTasks.size

    val isStillADraft: Boolean get() = status == ImportBatchStatus.DRAFT

    /** The drafts made from one cell, in the same order as the whole list. */
    fun draftsOf(blockId: EntityId): List<ReviewDraftTask> = draftTasks.filter { it.rawImportBlockId == blockId }

    fun blockOrNull(blockId: EntityId?): ReviewRawBlock? = rawBlocks.firstOrNull { it.id == blockId }
}
