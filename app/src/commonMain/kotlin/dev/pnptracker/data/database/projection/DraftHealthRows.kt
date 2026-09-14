package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.data.database.entity.ImportBatchEntity
import dev.pnptracker.domain.importhealth.DraftContradiction
import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.model.ImportBatchStatus

/**
 * The counts behind six of the nine contradictions, for one draft, in one read.
 *
 * The other three need nothing but the batch row itself (D1–D3's counters) or a
 * text length SQLite cannot give (D8, see [SelectionCandidateRow]).
 */
data class DraftHealthFacts(
    /** How many raw cells the batch really has, to hold against its counter (D3). */
    @ColumnInfo(name = "raw_block_rows")
    val rawBlockRows: Int,
    /** Drafts of the batch that name a task they became (D4). */
    @ColumnInfo(name = "materialized_draft_count")
    val materializedDraftCount: Int,
    /** Cell snapshots recorded for the batch (D5). */
    @ColumnInfo(name = "cell_snapshot_count")
    val cellSnapshotCount: Int,
    /** Tasks, deleted or not, whose source is one of the batch's raw cells (D6). */
    @ColumnInfo(name = "sourced_task_count")
    val sourcedTaskCount: Int,
    /** Games, deleted or not, whose source is the batch (D7). */
    @ColumnInfo(name = "sourced_game_count")
    val sourcedGameCount: Int,
    /** Raw cells outside the game column carrying a game completion hint (D9). */
    @ColumnInfo(name = "hint_outside_game_count")
    val hintOutsideGameCount: Int,
)

/**
 * A draft's selection that might end past its raw cell's text (D8).
 *
 * PLAN measures a selection in UTF-16 units, the way Kotlin strings are indexed,
 * and SQLite's `length()` counts code points. A UTF-16 length is never shorter
 * than the code point count, so an end past the UTF-16 length is always past
 * `length()` too: the query keeps every real case and may keep a few more (text
 * outside the Basic Multilingual Plane, or text holding a NUL, which `length()`
 * stops at). [isBeyondItsText] then decides exactly.
 */
data class SelectionCandidateRow(
    @ColumnInfo(name = "raw_text")
    val rawText: String,
    @ColumnInfo(name = "selection_end_index")
    val selectionEndIndex: Int,
) {
    val isBeyondItsText: Boolean get() = selectionEndIndex > rawText.length
}

/**
 * Classifies one import from what was read about it.
 *
 * Pure: the same reading gives the same answer, and nothing here reads, writes
 * or throws for a damaged draft. [facts] and [selections] are ignored for
 * anything but a draft, and may be empty for one.
 */
fun draftHealthOf(
    batch: ImportBatchEntity,
    facts: DraftHealthFacts,
    selections: List<SelectionCandidateRow>,
): DraftHealth {
    if (batch.status != ImportBatchStatus.DRAFT) return DraftHealth.NotADraft(batch.id, batch.status)
    val contradictions =
        buildSet {
            if (batch.createdTaskCount != 0) add(DraftContradiction.TASKS_COUNTED_BEFORE_CONFIRMATION)
            if (batch.createdGameCount != 0) add(DraftContradiction.GAMES_COUNTED_BEFORE_CONFIRMATION)
            if (batch.rawBlockCount != facts.rawBlockRows) add(DraftContradiction.RAW_CELL_COUNT_DISAGREES)
            if (facts.materializedDraftCount > 0) add(DraftContradiction.DRAFT_ALREADY_MATERIALIZED)
            if (facts.cellSnapshotCount > 0) add(DraftContradiction.CELL_RECORDED_BEFORE_CONFIRMATION)
            if (facts.sourcedTaskCount > 0) add(DraftContradiction.TASK_SOURCED_FROM_DRAFT)
            if (facts.sourcedGameCount > 0) add(DraftContradiction.GAME_SOURCED_FROM_DRAFT)
            if (selections.any { it.isBeyondItsText }) add(DraftContradiction.SELECTION_BEYOND_ITS_TEXT)
            if (facts.hintOutsideGameCount > 0) add(DraftContradiction.COMPLETION_HINT_OUTSIDE_GAME_COLUMN)
        }
    return if (contradictions.isEmpty()) DraftHealth.Sound(batch.id) else DraftHealth.Contradicting(batch.id, contradictions)
}
