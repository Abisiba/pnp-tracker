package dev.pnptracker.domain.importhealth

import dev.pnptracker.domain.backup.BackupData

/**
 * What a draft batch's own rows say about it: the facts D1–D9 are decided from
 * (PLAN 11.4.5), whether they were counted by a query or read off a document.
 */
data class DraftRecords(
    val createdTaskCount: Int,
    val createdGameCount: Int,
    val rawBlockCount: Int,
    /** How many raw cells the batch really has. */
    val rawBlockRows: Int,
    val materializedDraftCount: Int,
    val cellSnapshotCount: Int,
    /** Tasks, deleted or not, whose source is one of the batch's raw cells. */
    val sourcedTaskCount: Int,
    /** Games, deleted or not, whose source is the batch. */
    val sourcedGameCount: Int,
    /** Raw cells outside the game column carrying a game completion hint. */
    val hintOutsideGameCount: Int,
    /** Whether some draft's selection ends beyond its cell's text (UTF-16). */
    val selectionBeyondItsText: Boolean,
)

/**
 * D1–D9 of PLAN 11.4.5 — the one definition. The database's classifier and the
 * restore gate both call this; a second copy would be two answers waiting to
 * disagree (PLAN 14.7.5 decision 2).
 */
fun draftContradictionsOf(records: DraftRecords): Set<DraftContradiction> =
    buildSet {
        if (records.createdTaskCount != 0) add(DraftContradiction.TASKS_COUNTED_BEFORE_CONFIRMATION)
        if (records.createdGameCount != 0) add(DraftContradiction.GAMES_COUNTED_BEFORE_CONFIRMATION)
        if (records.rawBlockCount != records.rawBlockRows) add(DraftContradiction.RAW_CELL_COUNT_DISAGREES)
        if (records.materializedDraftCount > 0) add(DraftContradiction.DRAFT_ALREADY_MATERIALIZED)
        if (records.cellSnapshotCount > 0) add(DraftContradiction.CELL_RECORDED_BEFORE_CONFIRMATION)
        if (records.sourcedTaskCount > 0) add(DraftContradiction.TASK_SOURCED_FROM_DRAFT)
        if (records.sourcedGameCount > 0) add(DraftContradiction.GAME_SOURCED_FROM_DRAFT)
        if (records.selectionBeyondItsText) add(DraftContradiction.SELECTION_BEYOND_ITS_TEXT)
        if (records.hintOutsideGameCount > 0) add(DraftContradiction.COMPLETION_HINT_OUTSIDE_GAME_COLUMN)
    }

/**
 * PLAN 14.7.5's relations for confirmed and rolled back imports, and for every
 * status. Dilim 5 measured all thirteen: the application's own paths keep them,
 * legitimate old states are not counted, and each can reach a live database
 * through a restore — so all of them are the set `L`.
 */
enum class LifecycleContradiction {
    /** C1: every draft produced a task, and there is at least one. */
    C1,

    /** C2: the batch counted exactly its drafts. */
    C2,

    /** C3: every task a draft produced says it came from that draft's raw cell. */
    C3,

    /** C4: the recorded cells are exactly the drafts' targets, and every draft has one. */
    C4,

    /** C5: no game counted or sourced from the batch. */
    C5,

    /** RB1: a rolled back batch recorded its cells. */
    RB1,

    /** RB2: a rolled back batch keeps C1 … C5. */
    RB2,

    /** RB3: every task it made is tombstoned. */
    RB3,

    /** RB4: no piece of a cell points at a task it made. */
    RB4,

    /** RB5: every task it made has a TASK_ROLLED_BACK line. */
    RB5,

    /** U1: a completion hint only on a GAME cell. */
    U1,

    /** U2: a selection ends inside its cell's text. */
    U2,

    /** U3: the raw cell count is the number of raw cells. */
    U3,
}

/** Everything one batch of a document contradicts; empty sets mean it is sound. */
data class ImportRecordsHealth(
    val batchId: String,
    val draft: Set<DraftContradiction>,
    val lifecycle: Set<LifecycleContradiction>,
) {
    val isSound: Boolean get() = draft.isEmpty() && lifecycle.isEmpty()
}

/**
 * Every import batch of [data], held against `L` (PLAN 14.7.5): D1–D9 for a
 * draft through [draftContradictionsOf], C1–C5, RB1–RB5 and U1–U3 for the rest.
 *
 * Read off the document's own rows, in memory and in one pass over each table:
 * no database, no file. A confirmed batch that recorded no cells claims no C
 * relation at all — that is an import confirmed before schema 8, or one
 * `Migration3To4` emptied — and is left as it is.
 */
fun importRecordsHealthIn(data: BackupData): List<ImportRecordsHealth> {
    val blocksOf = data.rawImportBlocks.groupBy { it.importBatchId }
    val draftsOf = data.draftTasks.groupBy { it.rawImportBlockId }
    val tasks = data.tasks.associateBy { it.id }
    val cellsOf = data.importBatchCells.groupBy { it.importBatchId }
    val sourcedTasksOf =
        data.tasks
            .mapNotNull { it.sourceRawImportBlockId }
            .groupingBy { it }
            .eachCount()
    val sourcedGamesOf =
        data.games
            .mapNotNull { it.sourceImportBatchId }
            .groupingBy { it }
            .eachCount()
    val pointedAt = data.cellSegments.mapNotNullTo(HashSet()) { it.taskId }
    val rolledBackLines = data.historyEvents.filter { it.kind == "TASK_ROLLED_BACK" }.mapNotNullTo(HashSet()) { it.taskId }

    return data.importBatches.map { batch ->
        val blocks = blocksOf[batch.id].orEmpty()
        val textOf = blocks.associate { it.id to it.rawText }
        val drafts = blocks.flatMap { draftsOf[it.id].orEmpty() }
        val made = drafts.filter { it.materializedTaskId != null }
        val cells = cellsOf[batch.id].orEmpty()
        val hintOutsideGame = blocks.count { it.sourceColumnType != "GAME" && it.gameCompletionHint != "NONE" }
        val selectionBeyond =
            drafts.any { draft ->
                draft.selectionEndIndex?.let { it > textOf.getValue(draft.rawImportBlockId).length } ==
                    true
            }

        fun confirmedRelations(): Set<LifecycleContradiction> =
            buildSet {
                if (drafts.isEmpty() || made.size != drafts.size) add(LifecycleContradiction.C1)
                if (batch.createdTaskCount != drafts.size) add(LifecycleContradiction.C2)
                if (made.any { tasks[it.materializedTaskId]?.sourceRawImportBlockId != it.rawImportBlockId }) add(LifecycleContradiction.C3)
                val targets = drafts.map { it.targetCellId }
                if (targets.any { it == null } || cells.mapTo(HashSet()) { it.cellId } != targets.toSet()) add(LifecycleContradiction.C4)
                if (batch.createdGameCount != 0 || batch.id in sourcedGamesOf) add(LifecycleContradiction.C5)
            }

        val draft =
            if (batch.status != "DRAFT") {
                emptySet()
            } else {
                draftContradictionsOf(
                    DraftRecords(
                        createdTaskCount = batch.createdTaskCount,
                        createdGameCount = batch.createdGameCount,
                        rawBlockCount = batch.rawBlockCount,
                        rawBlockRows = blocks.size,
                        materializedDraftCount = made.size,
                        cellSnapshotCount = cells.size,
                        sourcedTaskCount = blocks.sumOf { sourcedTasksOf[it.id] ?: 0 },
                        sourcedGameCount = sourcedGamesOf[batch.id] ?: 0,
                        hintOutsideGameCount = hintOutsideGame,
                        selectionBeyondItsText = selectionBeyond,
                    ),
                )
            }
        val lifecycle =
            buildSet {
                when (batch.status) {
                    "CONFIRMED" -> if (cells.isNotEmpty()) addAll(confirmedRelations())
                    "ROLLED_BACK" -> {
                        if (cells.isEmpty()) add(LifecycleContradiction.RB1)
                        if (confirmedRelations().isNotEmpty()) add(LifecycleContradiction.RB2)
                        if (made.any { tasks[it.materializedTaskId]?.deletedAt == null }) add(LifecycleContradiction.RB3)
                        if (made.any { it.materializedTaskId in pointedAt }) add(LifecycleContradiction.RB4)
                        if (made.any { it.materializedTaskId !in rolledBackLines }) add(LifecycleContradiction.RB5)
                    }
                }
                // For a draft these are D9, D8 and D3, already asked above.
                if (batch.status != "DRAFT") {
                    if (hintOutsideGame > 0) add(LifecycleContradiction.U1)
                    if (selectionBeyond) add(LifecycleContradiction.U2)
                    if (batch.rawBlockCount != blocks.size) add(LifecycleContradiction.U3)
                }
            }
        ImportRecordsHealth(batch.id, draft, lifecycle)
    }
}
