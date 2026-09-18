package dev.pnptracker.domain.importrollback

import dev.pnptracker.domain.games.TASK_SEPARATOR
import dev.pnptracker.domain.games.taskNeedsSeparatorAfter
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ImportBatchStatus
import kotlin.time.Instant

/**
 * One task the import created, as the rollback has to see it.
 *
 * Everything PLAN 11.4.4's four criteria ask about, gathered so the decision can
 * be made without going back to the database per task.
 */
data class RollbackTaskFacts(
    val taskId: EntityId,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val hasProgressEvent: Boolean,
    val hasHistoryEvent: Boolean,
    /** The raw cell the task's own record says it came from; null for a task a person wrote. */
    val sourceRawImportBlockId: EntityId?,
)

/** One draft of the batch, as far as its ties to a task and a cell go (PLAN 14.7.5). */
data class RollbackDraftFacts(
    val rawImportBlockId: EntityId,
    val targetCellId: EntityId?,
    val materializedTaskId: EntityId?,
)

/** One piece of a target cell, as it stands today. */
data class RollbackSegmentFacts(
    val segmentId: EntityId,
    val orderIndex: Int,
    val isTask: Boolean,
    /** The piece's own text; null on a task piece, which has none of its own. */
    val text: String?,
    val taskId: EntityId?,
    /** What this piece contributes to the document: its text, or its task's name. */
    val documentText: String,
)

/** One cell the import wrote into: what it said then, and what it holds now. */
data class RollbackCellFacts(
    val cellId: EntityId,
    val gameId: EntityId?,
    val gameName: String?,
    val columnType: CellColumnType?,
    /** What the confirmation recorded; null when it recorded nothing (pre-v8). */
    val documentBefore: String?,
    val segments: List<RollbackSegmentFacts>,
)

/**
 * Everything one rollback decision is made from, read in bulk beforehand.
 *
 * A plain value with no database in it, so the same decision can be reached from
 * a read-only preview and from inside the writing transaction, by the one
 * function below. Two implementations of "is this safe" would be two answers
 * waiting to disagree, and the one that mattered would be the one nobody tested.
 */
data class ImportRollbackFacts(
    val batchId: EntityId,
    /** Null when the batch is not in the database at all. */
    val status: ImportBatchStatus?,
    val draftCount: Int,
    /** How many of those drafts record the task they produced. */
    val materializedDraftCount: Int,
    /** What the batch says it created when it was confirmed. */
    val createdTaskCount: Int,
    val drafts: List<RollbackDraftFacts>,
    val tasks: List<RollbackTaskFacts>,
    /** Where each task of this batch is written: task identity to cell identity. */
    val anchors: Map<EntityId, EntityId>,
    val cells: List<RollbackCellFacts>,
)

/** One cell put back the way it was, and the pieces that go to do it. */
data class PlannedCellRestore(
    val cellId: EntityId,
    val gameId: EntityId,
    val documentBefore: String,
    /** The pieces this import added, in reading order. Only these are removed. */
    val segmentIdsToRemove: List<EntityId>,
    /** The pieces that were there first, untouched — kept to check they stayed. */
    val keptSegmentIds: List<EntityId>,
)

/**
 * What taking one import back would do, or why it cannot be done.
 *
 * Both at once on purpose: the same value answers the screen's question ("what
 * is in the way?") and the transaction's ("what do I write?"), so the two can
 * never be computed from different rules.
 */
data class ImportRollbackPlan(
    val batchId: EntityId,
    /** The tasks to tombstone, in a fixed order so the events are repeatable. */
    val taskIds: List<EntityId>,
    /**
     * Which game each task is in, settled before anything is removed.
     *
     * A task reaches its game through its piece of a cell, and the rollback
     * takes that piece away — so asking afterwards would be asking a question
     * whose answer the rollback itself destroyed.
     */
    val gameOfTask: Map<EntityId, EntityId>,
    val cells: List<PlannedCellRestore>,
    /** The games touched, each once, in a fixed order. */
    val gameIds: List<EntityId>,
    val blockedTasks: List<BlockedTask>,
    val blockedCells: List<BlockedCell>,
    val failure: ImportRollbackFailure?,
) {
    val canRollBack: Boolean get() = failure == null
}

/**
 * Decides whether an import can be taken back, and what it would take.
 *
 * Pure: no clock, no database, no identity generation. Everything it needs was
 * read in bulk before it ran, which is what lets the preview and the transaction
 * share it.
 *
 * The order of the refusals is the order PLAN 11.4.4 states them in, and it is
 * not arbitrary. A batch that is not confirmed is not a batch with conflicts; a
 * batch with no record of its cells cannot even be asked whether its cells
 * changed. Only when those are past does the question become the one PLAN cares
 * about — is anything the user did in the way — and then a touched task and a
 * changed cell are two independent answers, either of which stops all of it.
 */
@Suppress("ReturnCount")
fun planImportRollback(facts: ImportRollbackFacts): ImportRollbackPlan {
    val status = facts.status ?: return refusal(facts.batchId, ImportRollbackFailure.BATCH_NOT_FOUND)
    when (status) {
        ImportBatchStatus.DRAFT -> return refusal(facts.batchId, ImportRollbackFailure.BATCH_NOT_CONFIRMED)
        ImportBatchStatus.ROLLED_BACK -> return refusal(facts.batchId, ImportRollbackFailure.ALREADY_ROLLED_BACK)
        ImportBatchStatus.CONFIRMED -> Unit
    }

    // A confirmed batch has exactly one task per draft, and every draft records
    // which one. Anything else is a broken trail rather than something the user
    // did, and it is refused rather than worked around.
    val provenanceHolds =
        facts.draftCount > 0 &&
            facts.materializedDraftCount == facts.draftCount &&
            facts.tasks.size == facts.draftCount
    if (!provenanceHolds) return refusal(facts.batchId, ImportRollbackFailure.PROVENANCE_BROKEN)

    // A confirmed batch that wrote tasks and recorded no cell at all was
    // confirmed before there was anywhere to record one.
    if (facts.cells.none { it.documentBefore != null }) {
        return refusal(facts.batchId, ImportRollbackFailure.NO_CELL_SNAPSHOT)
    }

    // The batch's own records have to agree about what it made and where
    // (PLAN 14.7.5, C2–C4). Measured in Dilim 5: a restored backup can carry a
    // batch whose draft points at a task not recorded as coming from it, or
    // whose recorded cells are not its drafts' targets — and a rollback trusting
    // that would tombstone a task the import did not make or rewrite a cell it
    // never wrote into. Asked only after the cell record exists, so an import
    // confirmed before schema 8 keeps its old reason.
    if (!recordsAgree(facts)) return refusal(facts.batchId, ImportRollbackFailure.PROVENANCE_BROKEN)

    val batchTaskIds = facts.tasks.mapTo(LinkedHashSet()) { it.taskId }
    val recordedCellIds = facts.cells.mapTo(mutableSetOf()) { it.cellId }
    val blockedTasks =
        facts.tasks.mapNotNull { task ->
            obstacleOf(task, facts.anchors[task.taskId], recordedCellIds)
                ?.let { BlockedTask(task.taskId, task.name, it) }
        }

    val restores = mutableListOf<PlannedCellRestore>()
    val blockedCells = mutableListOf<BlockedCell>()
    facts.cells.forEach { cell ->
        when (val outcome = restoreOf(cell, batchTaskIds, facts.anchors)) {
            is CellOutcome.Restorable -> restores += outcome.restore
            is CellOutcome.Blocked ->
                blockedCells +=
                    BlockedCell(
                        cellId = cell.cellId,
                        gameId = cell.gameId,
                        gameName = cell.gameName,
                        columnType = cell.columnType,
                        obstacle = outcome.obstacle,
                    )
        }
    }

    val failure =
        when {
            blockedCells.any { it.obstacle == CellObstacle.SNAPSHOT_MISSING } ->
                ImportRollbackFailure.NO_CELL_SNAPSHOT

            blockedTasks.any { it.obstacle == TaskObstacle.PROVENANCE_MISSING } ->
                ImportRollbackFailure.PROVENANCE_BROKEN

            blockedTasks.isNotEmpty() -> ImportRollbackFailure.TASKS_WERE_EDITED
            blockedCells.isNotEmpty() -> ImportRollbackFailure.CELLS_WERE_EDITED
            else -> null
        }

    val gameOfCell = facts.cells.mapNotNull { cell -> cell.gameId?.let { cell.cellId to it } }.toMap()
    val gameOfTask =
        facts.tasks
            .mapNotNull { task ->
                val cellId = facts.anchors[task.taskId] ?: return@mapNotNull null
                val gameId = gameOfCell[cellId] ?: return@mapNotNull null
                task.taskId to gameId
            }.toMap()

    // A blocked plan carries nothing to do, and that is the type saying what
    // PLAN 11.4.4 says: one conflict stops the whole operation, so there is no
    // such thing as the part of it that could still go ahead. Leaving the lists
    // filled in would leave a caller something to act on by mistake.
    if (failure != null) {
        return refusal(facts.batchId, failure).copy(blockedTasks = blockedTasks, blockedCells = blockedCells)
    }

    return ImportRollbackPlan(
        batchId = facts.batchId,
        taskIds = facts.tasks.map { it.taskId },
        gameOfTask = gameOfTask,
        cells = restores,
        // One line per game however many of its cells the import touched, and in
        // the cells' own reading order so a repeat produces the same history.
        gameIds = restores.map { it.gameId }.distinct(),
        blockedTasks = blockedTasks,
        blockedCells = blockedCells,
        failure = null,
    )
}

/**
 * C2, C3 and C4 of PLAN 14.7.5, from facts already read — no statement of its own.
 *
 * C2  the batch counted exactly its drafts;
 * C3  every task a draft produced says it came from that draft's raw cell;
 * C4  the recorded cells are exactly the drafts' targets, and every draft has one.
 */
private fun recordsAgree(facts: ImportRollbackFacts): Boolean {
    if (facts.createdTaskCount != facts.draftCount) return false
    val sourceOf = facts.tasks.associate { it.taskId to it.sourceRawImportBlockId }
    val madeWhereItSays =
        facts.drafts.all { draft ->
            val made = draft.materializedTaskId ?: return@all true
            sourceOf.containsKey(made) && sourceOf[made] == draft.rawImportBlockId
        }
    if (!madeWhereItSays) return false
    val targets = facts.drafts.map { it.targetCellId }
    return targets.none { it == null } && targets.toSet() == facts.cells.mapTo(mutableSetOf<EntityId?>()) { it.cellId }
}

/**
 * Which of PLAN 11.4.4's criteria one task falls foul of, or none.
 *
 * Deliberately conservative and ordered from the most specific outwards: a
 * deleted task also has a moved `updatedAt`, and reporting it as merely edited
 * would tell the user something less true than what happened. What the import
 * itself wrote trips none of these — confirmation writes `updatedAt` equal to
 * `createdAt` and appends no progress line — so an untouched task is recognised
 * reliably rather than by luck.
 */
private fun obstacleOf(
    task: RollbackTaskFacts,
    anchorCellId: EntityId?,
    recordedCellIds: Set<EntityId>,
): TaskObstacle? =
    when {
        task.deletedAt != null -> TaskObstacle.DELETED
        // Turned back into text, or otherwise left without the piece of a cell
        // that is the only thing tying it to a game.
        anchorCellId == null -> TaskObstacle.NOT_ANCHORED
        anchorCellId !in recordedCellIds -> TaskObstacle.PROVENANCE_MISSING
        task.updatedAt != task.createdAt -> TaskObstacle.EDITED
        task.hasProgressEvent -> TaskObstacle.HAS_PROGRESS_EVENT
        task.hasHistoryEvent -> TaskObstacle.HAS_HISTORY_EVENT
        else -> null
    }

private sealed interface CellOutcome {
    data class Restorable(
        val restore: PlannedCellRestore,
    ) : CellOutcome

    data class Blocked(
        val obstacle: CellObstacle,
    ) : CellOutcome
}

/**
 * Whether one cell can be put back, and which of its pieces would go.
 *
 * The import only ever appended (PLAN 11.4.2), so what it added is a *suffix* of
 * the cell's pieces and the words in front of that suffix are the user's. That
 * gives a boundary rather than a guess: the pieces whose text, laid end to end,
 * spells exactly the recorded document are the ones that were there first, and
 * everything after them is what this import wrote.
 *
 * The boundary is unique because no piece contributes an empty string — a task
 * has a name and an emptied stretch of text leaves no row (PLAN 16) — so the
 * running length strictly increases and can land on the recorded length once.
 *
 * Two separate things are then checked, and both must hold:
 *
 * * **The words.** The document rebuilt from the recorded text plus this
 *   import's task names, spaced by the very same [taskNeedsSeparatorAfter] the
 *   confirmation used, must equal what the cell reads today, character for
 *   character. This catches a space, a line break, a letter, a user's own
 *   sentence, and a task somebody added by hand.
 * * **The pieces.** The suffix must consist only of this import's task pieces
 *   and the single spaces it wrote between them; none of this import's tasks may
 *   sit in the prefix; and the numbering must still be `0..N-1`. This catches
 *   what the words cannot: another import writing the same names into the same
 *   cell, or a piece moved without the reading changing.
 */
@Suppress("ReturnCount")
private fun restoreOf(
    cell: RollbackCellFacts,
    batchTaskIds: Set<EntityId>,
    anchors: Map<EntityId, EntityId>,
): CellOutcome {
    val documentBefore = cell.documentBefore ?: return CellOutcome.Blocked(CellObstacle.SNAPSHOT_MISSING)
    val gameId = cell.gameId ?: return CellOutcome.Blocked(CellObstacle.STRUCTURE_CHANGED)
    if (cell.segments.map { it.orderIndex } != cell.segments.indices.toList()) {
        return CellOutcome.Blocked(CellObstacle.STRUCTURE_CHANGED)
    }

    val boundary =
        boundaryAfter(cell.segments, documentBefore)
            ?: return CellOutcome.Blocked(CellObstacle.DOCUMENT_CHANGED)
    val prefix = cell.segments.take(boundary)
    val suffix = cell.segments.drop(boundary)

    // The user's own writing has to be exactly what was recorded, and none of
    // this import's tasks may be hiding inside it.
    if (prefix.joinToString(separator = "") { it.documentText } != documentBefore) {
        return CellOutcome.Blocked(CellObstacle.DOCUMENT_CHANGED)
    }
    if (prefix.any { it.taskId in batchTaskIds }) return CellOutcome.Blocked(CellObstacle.STRUCTURE_CHANGED)

    // Everything after it has to be something this import wrote: one of its
    // tasks, or one of the single spaces it put between them.
    val suffixIsOurs =
        suffix.all { piece ->
            if (piece.isTask) piece.taskId in batchTaskIds else piece.text == TASK_SEPARATOR
        }
    if (!suffixIsOurs) return CellOutcome.Blocked(CellObstacle.STRUCTURE_CHANGED)

    // And it has to be all of what this import wrote here — a task of this batch
    // anchored in this cell but missing from the suffix means the cell was rebuilt.
    val anchoredHere = batchTaskIds.filter { anchors[it] == cell.cellId }.toSet()
    val inSuffix = suffix.mapNotNull { it.taskId }
    if (inSuffix.toSet() != anchoredHere || inSuffix.size != anchoredHere.size) {
        return CellOutcome.Blocked(CellObstacle.STRUCTURE_CHANGED)
    }

    val expected =
        suffix.filter { it.isTask }.fold(documentBefore) { text, task ->
            text + (if (taskNeedsSeparatorAfter(text)) TASK_SEPARATOR else "") + task.documentText
        }
    if (expected != cell.segments.joinToString(separator = "") { it.documentText }) {
        return CellOutcome.Blocked(CellObstacle.DOCUMENT_CHANGED)
    }

    return CellOutcome.Restorable(
        PlannedCellRestore(
            cellId = cell.cellId,
            gameId = gameId,
            documentBefore = documentBefore,
            segmentIdsToRemove = suffix.map { it.segmentId },
            keptSegmentIds = prefix.map { it.segmentId },
        ),
    )
}

/**
 * How many pieces of the cell spell exactly [documentBefore], or null if none do.
 *
 * Null means the boundary falls inside a piece, which is a cell whose words no
 * longer begin with what was recorded — the user rewrote something in front of
 * the import's work, or a task was renamed.
 */
private fun boundaryAfter(
    segments: List<RollbackSegmentFacts>,
    documentBefore: String,
): Int? {
    if (documentBefore.isEmpty()) return 0
    var length = 0
    segments.forEachIndexed { index, piece ->
        length += piece.documentText.length
        if (length == documentBefore.length) return index + 1
        if (length > documentBefore.length) return null
    }
    return null
}

private fun refusal(
    batchId: EntityId,
    failure: ImportRollbackFailure,
): ImportRollbackPlan =
    ImportRollbackPlan(
        batchId = batchId,
        taskIds = emptyList(),
        gameOfTask = emptyMap(),
        cells = emptyList(),
        gameIds = emptyList(),
        blockedTasks = emptyList(),
        blockedCells = emptyList(),
        failure = failure,
    )
