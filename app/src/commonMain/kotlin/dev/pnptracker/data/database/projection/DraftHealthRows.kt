package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.data.database.entity.ImportBatchEntity
import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.importhealth.DraftRecords
import dev.pnptracker.domain.importhealth.draftContradictionsOf
import dev.pnptracker.domain.model.EntityId
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

/** [DraftHealthFacts] for one draft import, named, as the whole list reads them. */
data class DraftBatchHealthRow(
    @ColumnInfo(name = "batch_id")
    val batchId: EntityId,
    @ColumnInfo(name = "raw_block_rows")
    val rawBlockRows: Int,
    @ColumnInfo(name = "materialized_draft_count")
    val materializedDraftCount: Int,
    @ColumnInfo(name = "cell_snapshot_count")
    val cellSnapshotCount: Int,
    @ColumnInfo(name = "sourced_task_count")
    val sourcedTaskCount: Int,
    @ColumnInfo(name = "sourced_game_count")
    val sourcedGameCount: Int,
    @ColumnInfo(name = "hint_outside_game_count")
    val hintOutsideGameCount: Int,
) {
    val facts: DraftHealthFacts
        get() =
            DraftHealthFacts(
                rawBlockRows = rawBlockRows,
                materializedDraftCount = materializedDraftCount,
                cellSnapshotCount = cellSnapshotCount,
                sourcedTaskCount = sourcedTaskCount,
                sourcedGameCount = sourcedGameCount,
                hintOutsideGameCount = hintOutsideGameCount,
            )
}

/** A [SelectionCandidateRow] of some draft import, naming which. */
data class DraftBatchSelectionRow(
    @ColumnInfo(name = "batch_id")
    val batchId: EntityId,
    @ColumnInfo(name = "raw_text")
    val rawText: String,
    @ColumnInfo(name = "selection_end_index")
    val selectionEndIndex: Int,
)

/** One draft import and what its records say about each other. */
data class DraftBatchHealth(
    val batch: ImportBatchEntity,
    val health: DraftHealth,
)

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
        draftContradictionsOf(
            DraftRecords(
                createdTaskCount = batch.createdTaskCount,
                createdGameCount = batch.createdGameCount,
                rawBlockCount = batch.rawBlockCount,
                rawBlockRows = facts.rawBlockRows,
                materializedDraftCount = facts.materializedDraftCount,
                cellSnapshotCount = facts.cellSnapshotCount,
                sourcedTaskCount = facts.sourcedTaskCount,
                sourcedGameCount = facts.sourcedGameCount,
                hintOutsideGameCount = facts.hintOutsideGameCount,
                selectionBeyondItsText = selections.any { it.isBeyondItsText },
            ),
        )
    return if (contradictions.isEmpty()) DraftHealth.Sound(batch.id) else DraftHealth.Contradicting(batch.id, contradictions)
}
