package dev.pnptracker.domain.importreview

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType

/**
 * One source cell as the review screen sees it.
 *
 * [rawText] is carried through untouched — line breaks, `**` markers, leading and
 * trailing spaces and all — because the whole point of the left pane is to show
 * the user what the file really said.
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
    val isProcessed: Boolean = false,
)

/** One task draft as the review screen sees it. */
data class ReviewDraftTask(
    val id: EntityId,
    val rawImportBlockId: EntityId,
    val name: String,
    val suggestedPoolType: PoolType? = null,
    val completionHint: HintDecision = HintDecision.NONE,
)

/**
 * Everything the two panes of the review screen show, for one import.
 *
 * Both lists arrive already ordered and already restricted to this import, so the
 * screen never has to filter and can never show a cell or a draft belonging to a
 * different file.
 *
 * Nothing here creates a game, an item or a task. Reviewing is reading and note
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
