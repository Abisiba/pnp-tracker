package dev.pnptracker.data.database

import dev.pnptracker.domain.backup.BackupData

/** PLAN 14.7.5's candidate relations for confirmed and rolled back imports, and for every status. */
enum class LifecycleCandidate {
    /** |made(b)| = |drafts(b)| ≥ 1 */
    C1,

    /** created_task_count = |drafts(b)| */
    C2,

    /** task(d).source_raw_import_block_id = d.raw_import_block_id for every made draft */
    C3,

    /** the recorded cells are exactly the drafts' targets, and no target is empty */
    C4,

    /** created_game_count = 0 and no game names the batch as its source */
    C5,

    /** a rolled back batch recorded its cells */
    RB1,

    /** a rolled back batch keeps C1 … C5 */
    RB2,

    /** every task it made is tombstoned */
    RB3,

    /** no piece of a cell points at a task it made */
    RB4,

    /** every task it made has a TASK_ROLLED_BACK line */
    RB5,

    /** a completion hint only on a GAME cell */
    U1,

    /** a selection ends inside its cell's text (UTF-16) */
    U2,

    /** raw_block_count = |blocks(b)| */
    U3,
}

/**
 * Which candidates each batch of [data] breaks, by PLAN 14.7.5's definitions,
 * read off a backup document's rows alone.
 *
 * Only a measuring instrument for Dilim 5: it decides nothing and gates
 * nothing. `CONFIRMED` with no recorded cells claims no relation at all — that
 * is a batch confirmed before schema 8, or one `Migration3To4` emptied — so C1–C5
 * are asked only of a confirmed batch that recorded its cells.
 */
fun lifecycleCandidatesBrokenIn(data: BackupData): Map<String, Set<LifecycleCandidate>> {
    val blocksOf = data.rawImportBlocks.groupBy { it.importBatchId }
    val draftsOf = data.draftTasks.groupBy { it.rawImportBlockId }
    val tasks = data.tasks.associateBy { it.id }
    val cellsOf = data.importBatchCells.groupBy { it.importBatchId }
    val claimed = data.games.mapNotNullTo(mutableSetOf()) { it.sourceImportBatchId }
    val pointedAt = data.cellSegments.mapNotNullTo(mutableSetOf()) { it.taskId }
    val rolledBackLines = data.historyEvents.filter { it.kind == "TASK_ROLLED_BACK" }.mapNotNullTo(mutableSetOf()) { it.taskId }

    return data.importBatches.associate { batch ->
        val blocks = blocksOf[batch.id].orEmpty()
        val textOf = blocks.associate { it.id to it.rawText }
        val drafts = blocks.flatMap { draftsOf[it.id].orEmpty() }
        val made = drafts.filter { it.materializedTaskId != null }
        val cells = cellsOf[batch.id].orEmpty()
        val broken = mutableSetOf<LifecycleCandidate>()

        fun confirmedRelations(): Set<LifecycleCandidate> =
            buildSet {
                if (drafts.isEmpty() || made.size != drafts.size) add(LifecycleCandidate.C1)
                if (batch.createdTaskCount != drafts.size) add(LifecycleCandidate.C2)
                if (made.any { tasks[it.materializedTaskId]?.sourceRawImportBlockId != it.rawImportBlockId }) add(LifecycleCandidate.C3)
                val targets = drafts.map { it.targetCellId }
                if (targets.any { it == null } || cells.map { it.cellId }.toSet() != targets.toSet()) add(LifecycleCandidate.C4)
                if (batch.createdGameCount != 0 || batch.id in claimed) add(LifecycleCandidate.C5)
            }

        when (batch.status) {
            "CONFIRMED" -> if (cells.isNotEmpty()) broken += confirmedRelations()
            "ROLLED_BACK" -> {
                if (cells.isEmpty()) broken += LifecycleCandidate.RB1
                if (confirmedRelations().isNotEmpty()) broken += LifecycleCandidate.RB2
                if (made.any { tasks[it.materializedTaskId]?.deletedAt == null }) broken += LifecycleCandidate.RB3
                if (made.any { it.materializedTaskId in pointedAt }) broken += LifecycleCandidate.RB4
                if (made.any { it.materializedTaskId !in rolledBackLines }) broken += LifecycleCandidate.RB5
            }
        }
        if (blocks.any { it.sourceColumnType != "GAME" && it.gameCompletionHint != "NONE" }) broken += LifecycleCandidate.U1
        if (drafts.any { draft -> draft.selectionEndIndex?.let { it > textOf.getValue(draft.rawImportBlockId).length } == true }) {
            broken += LifecycleCandidate.U2
        }
        if (batch.rawBlockCount != blocks.size) broken += LifecycleCandidate.U3
        batch.id to broken
    }
}
