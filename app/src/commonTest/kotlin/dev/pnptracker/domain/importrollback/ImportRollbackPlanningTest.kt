package dev.pnptracker.domain.importrollback

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The decision itself, taken apart from the database.
 *
 * Everything PLAN 11.4.4 refuses on is a judgement about rows that have already
 * been read, so it is made by one pure function and asked here directly. The
 * transaction and the preview both call it, which is the point: a rule proved
 * here is proved for both, and there is no second copy to drift.
 *
 * The shapes below are the ones a real import leaves. A confirmation writes
 * `updatedAt` equal to `createdAt`, appends no progress line, and adds a single
 * space before a task only where the writing did not already end in one — so a
 * batch nobody has touched looks exactly like `untouched()`, and every case
 * after it changes one thing.
 */
class ImportRollbackPlanningTest {
    private val batchId = IdGenerator.Random.newId()
    private val cellId = IdGenerator.Random.newId()
    private val gameId = IdGenerator.Random.newId()
    private val firstTaskId = IdGenerator.Random.newId()
    private val secondTaskId = IdGenerator.Random.newId()

    private val born = Instant.fromEpochMilliseconds(1_780_000_000_000)
    private val later = Instant.fromEpochMilliseconds(1_780_000_500_000)

    private fun task(
        id: EntityId,
        name: String,
        updatedAt: Instant = born,
        deletedAt: Instant? = null,
        hasProgressEvent: Boolean = false,
        hasHistoryEvent: Boolean = false,
    ) = RollbackTaskFacts(
        taskId = id,
        name = name,
        createdAt = born,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        hasProgressEvent = hasProgressEvent,
        hasHistoryEvent = hasHistoryEvent,
    )

    private var nextOrder = 0

    private fun text(words: String) =
        RollbackSegmentFacts(
            segmentId = IdGenerator.Random.newId(),
            orderIndex = nextOrder++,
            isTask = false,
            text = words,
            taskId = null,
            documentText = words,
        )

    private fun taskPiece(
        id: EntityId,
        name: String,
    ) = RollbackSegmentFacts(
        segmentId = IdGenerator.Random.newId(),
        orderIndex = nextOrder++,
        isTask = true,
        text = null,
        taskId = id,
        documentText = name,
    )

    private fun cell(
        documentBefore: String?,
        vararg segments: RollbackSegmentFacts,
    ) = RollbackCellFacts(
        cellId = cellId,
        gameId = gameId,
        gameName = "Harmonies",
        columnType = CellColumnType.THREE_D,
        documentBefore = documentBefore,
        segments = segments.toList(),
    )

    private fun facts(
        status: ImportBatchStatus? = ImportBatchStatus.CONFIRMED,
        tasks: List<RollbackTaskFacts>,
        anchors: Map<EntityId, EntityId> = tasks.associate { it.taskId to cellId },
        cells: List<RollbackCellFacts>,
        draftCount: Int = tasks.size,
        materializedDraftCount: Int = tasks.size,
    ) = ImportRollbackFacts(
        batchId = batchId,
        status = status,
        draftCount = draftCount,
        materializedDraftCount = materializedDraftCount,
        tasks = tasks,
        anchors = anchors,
        cells = cells,
    )

    /**
     * A cell the user had written in, that one import then added two tasks to.
     *
     * `Not` did not end in a space, so the import put one in front of the first
     * task; the first task's name does not either, so another went in front of
     * the second. That is exactly what the confirmation does, and it is what the
     * planner has to be able to recognise as its own work.
     */
    private fun untouched(): ImportRollbackFacts {
        nextOrder = 0
        return facts(
            tasks = listOf(task(firstTaskId, "Kırmızı ev"), task(secondTaskId, "Mavi ev")),
            cells =
                listOf(
                    cell(
                        "Not",
                        text("Not"),
                        text(" "),
                        taskPiece(firstTaskId, "Kırmızı ev"),
                        text(" "),
                        taskPiece(secondTaskId, "Mavi ev"),
                    ),
                ),
        )
    }

    // ------------------------------------------------------------ the batch itself

    @Test
    fun `an untouched import can be taken back whole`() {
        val plan = planImportRollback(untouched())

        assertTrue(plan.canRollBack, "an import nobody touched was refused: ${plan.blockedTasks} ${plan.blockedCells}")
        assertEquals(listOf(firstTaskId, secondTaskId), plan.taskIds)
        assertEquals(listOf(gameId), plan.gameIds, "one game got more than one line")
        assertEquals(mapOf(firstTaskId to gameId, secondTaskId to gameId), plan.gameOfTask)
        val restore = plan.cells.single()
        assertEquals("Not", restore.documentBefore)
        // The four pieces the import added, and only those. The user's own word
        // keeps its row.
        assertEquals(4, restore.segmentIdsToRemove.size)
        assertEquals(1, restore.keptSegmentIds.size)
    }

    @Test
    fun `a batch that is not there, still a draft, or already taken back is refused apart`() {
        assertEquals(
            ImportRollbackFailure.BATCH_NOT_FOUND,
            planImportRollback(untouched().copy(status = null)).failure,
        )
        assertEquals(
            ImportRollbackFailure.BATCH_NOT_CONFIRMED,
            planImportRollback(untouched().copy(status = ImportBatchStatus.DRAFT)).failure,
        )
        assertEquals(
            ImportRollbackFailure.ALREADY_ROLLED_BACK,
            planImportRollback(untouched().copy(status = ImportBatchStatus.ROLLED_BACK)).failure,
        )
    }

    @Test
    fun `a batch that recorded no cell cannot be taken back`() {
        nextOrder = 0
        val plan =
            planImportRollback(
                facts(
                    tasks = listOf(task(firstTaskId, "Kırmızı ev")),
                    cells = listOf(cell(null, text("Not"), text(" "), taskPiece(firstTaskId, "Kırmızı ev"))),
                ),
            )

        // The shape a version 7 confirmation leaves. PLAN 11.4.4 refuses rather
        // than working the former text out backwards.
        assertEquals(ImportRollbackFailure.NO_CELL_SNAPSHOT, plan.failure)
    }

    @Test
    fun `a broken trail from the batch to its tasks is refused as a defect`() {
        val orphanedDraft = untouched().let { it.copy(materializedDraftCount = it.draftCount - 1) }
        assertEquals(ImportRollbackFailure.PROVENANCE_BROKEN, planImportRollback(orphanedDraft).failure)

        val missingTask = untouched().let { it.copy(tasks = it.tasks.dropLast(1)) }
        assertEquals(ImportRollbackFailure.PROVENANCE_BROKEN, planImportRollback(missingTask).failure)
    }

    // --------------------------------------------------------- one task is enough

    @Test
    fun `each of PLAN's four criteria blocks the whole import on its own`() {
        val cases =
            mapOf(
                TaskObstacle.EDITED to task(secondTaskId, "Mavi ev", updatedAt = later),
                TaskObstacle.HAS_PROGRESS_EVENT to task(secondTaskId, "Mavi ev", hasProgressEvent = true),
                TaskObstacle.HAS_HISTORY_EVENT to task(secondTaskId, "Mavi ev", hasHistoryEvent = true),
                TaskObstacle.DELETED to task(secondTaskId, "Mavi ev", updatedAt = later, deletedAt = later),
            )

        cases.forEach { (obstacle, touched) ->
            val base = untouched()
            val plan = planImportRollback(base.copy(tasks = listOf(base.tasks.first(), touched)))

            assertEquals(ImportRollbackFailure.TASKS_WERE_EDITED, plan.failure, "$obstacle did not block")
            assertEquals(listOf(BlockedTask(secondTaskId, "Mavi ev", obstacle)), plan.blockedTasks)
            // The other task is untouched and stays that way: PLAN 11.4.4 stops
            // the operation rather than removing what still looks safe.
            assertEquals(emptyList(), plan.cells, "a blocked rollback still planned to change a cell")
            assertEquals(emptyList(), plan.taskIds)
        }
    }

    @Test
    fun `a task that lost its piece of a cell blocks the import`() {
        val base = untouched()
        val plan = planImportRollback(base.copy(anchors = mapOf(firstTaskId to cellId)))

        assertEquals(ImportRollbackFailure.TASKS_WERE_EDITED, plan.failure)
        assertEquals(TaskObstacle.NOT_ANCHORED, plan.blockedTasks.single().obstacle)
    }

    @Test
    fun `a task written somewhere the import never recorded is a broken trail`() {
        val base = untouched()
        val elsewhere = IdGenerator.Random.newId()
        val plan =
            planImportRollback(
                base.copy(anchors = mapOf(firstTaskId to cellId, secondTaskId to elsewhere)),
            )

        assertEquals(ImportRollbackFailure.PROVENANCE_BROKEN, plan.failure)
        assertEquals(TaskObstacle.PROVENANCE_MISSING, plan.blockedTasks.single().obstacle)
    }

    @Test
    fun `a task the import made finished is not mistaken for one somebody edited`() {
        // PLAN 11.5 lets an accepted `**` produce a task that is already done.
        // The confirmation still writes `updatedAt` equal to `createdAt` and no
        // progress line, so nothing about being born finished may look like a
        // change the user made.
        val base = untouched()
        val bornFinished = task(secondTaskId, "Mavi ev")
        val plan = planImportRollback(base.copy(tasks = listOf(base.tasks.first(), bornFinished)))

        assertTrue(plan.canRollBack, "a task born finished was read as one somebody had edited")
    }

    // ------------------------------------------------------------ one cell is too

    @Test
    fun `a single character the user added or removed blocks the import`() {
        listOf(
            "Not ",
            "Nol",
            "No",
            "Not\n",
            "Nоt",
        ).forEach { edited ->
            nextOrder = 0
            val plan =
                planImportRollback(
                    facts(
                        tasks = listOf(task(firstTaskId, "Kırmızı ev")),
                        cells =
                            listOf(
                                cell(
                                    "Not",
                                    text(edited),
                                    text(" "),
                                    taskPiece(firstTaskId, "Kırmızı ev"),
                                ),
                            ),
                    ),
                )

            assertEquals(ImportRollbackFailure.CELLS_WERE_EDITED, plan.failure, "'$edited' was accepted as 'Not'")
            assertEquals(CellObstacle.DOCUMENT_CHANGED, plan.blockedCells.single().obstacle)
        }
    }

    @Test
    fun `words the user added after the import block it`() {
        nextOrder = 0
        val plan =
            planImportRollback(
                facts(
                    tasks = listOf(task(firstTaskId, "Kırmızı ev")),
                    cells =
                        listOf(
                            cell(
                                "Not",
                                text("Not"),
                                text(" "),
                                taskPiece(firstTaskId, "Kırmızı ev"),
                                text(" sonradan yazıldı"),
                            ),
                        ),
                ),
            )

        assertEquals(ImportRollbackFailure.CELLS_WERE_EDITED, plan.failure)
    }

    @Test
    fun `a task somebody else put in the same cell blocks the import`() {
        nextOrder = 0
        val strangerId = IdGenerator.Random.newId()
        val plan =
            planImportRollback(
                facts(
                    tasks = listOf(task(firstTaskId, "Kırmızı ev")),
                    cells =
                        listOf(
                            cell(
                                "Not",
                                text("Not"),
                                text(" "),
                                taskPiece(firstTaskId, "Kırmızı ev"),
                                text(" "),
                                taskPiece(strangerId, "Elle eklenen"),
                            ),
                        ),
                ),
            )

        assertEquals(ImportRollbackFailure.CELLS_WERE_EDITED, plan.failure)
        assertEquals(CellObstacle.STRUCTURE_CHANGED, plan.blockedCells.single().obstacle)
    }

    @Test
    fun `a cell that reads correctly but is built wrongly is still refused`() {
        // The very words the import left, and a piece of this batch sitting in
        // front of the recorded text instead of after it. Comparing the string
        // alone would pass this; comparing the pieces does not.
        nextOrder = 0
        val plan =
            planImportRollback(
                facts(
                    tasks = listOf(task(firstTaskId, "Not")),
                    cells =
                        listOf(
                            cell(
                                "Not",
                                taskPiece(firstTaskId, "Not"),
                            ),
                        ),
                ),
            )

        assertEquals(ImportRollbackFailure.CELLS_WERE_EDITED, plan.failure)
        assertEquals(CellObstacle.STRUCTURE_CHANGED, plan.blockedCells.single().obstacle)
    }

    @Test
    fun `a cell numbered with a gap is refused`() {
        nextOrder = 0
        val misnumbered =
            listOf(text("Not"), text(" "), taskPiece(firstTaskId, "Kırmızı ev"))
                .mapIndexed { at, piece -> if (at == 2) piece.copy(orderIndex = 7) else piece }
        val plan =
            planImportRollback(
                facts(
                    tasks = listOf(task(firstTaskId, "Kırmızı ev")),
                    cells = listOf(cell("Not", *misnumbered.toTypedArray())),
                ),
            )

        assertEquals(CellObstacle.STRUCTURE_CHANGED, plan.blockedCells.single().obstacle)
    }

    @Test
    fun `an empty cell the import filled comes back to empty`() {
        nextOrder = 0
        val plan =
            planImportRollback(
                facts(
                    tasks = listOf(task(firstTaskId, "Kırmızı ev")),
                    // Nothing was there, so the import wrote no separator either.
                    cells = listOf(cell("", taskPiece(firstTaskId, "Kırmızı ev"))),
                ),
            )

        assertTrue(plan.canRollBack)
        val restore = plan.cells.single()
        assertEquals("", restore.documentBefore)
        assertEquals(emptyList(), restore.keptSegmentIds)
        assertEquals(1, restore.segmentIdsToRemove.size)
    }

    @Test
    fun `a cell whose writing already ended in a space keeps its single separator`() {
        nextOrder = 0
        val plan =
            planImportRollback(
                facts(
                    tasks = listOf(task(firstTaskId, "Kırmızı ev")),
                    // "Not " ends in whitespace, so the confirmation added none.
                    cells = listOf(cell("Not ", text("Not "), taskPiece(firstTaskId, "Kırmızı ev"))),
                ),
            )

        assertTrue(plan.canRollBack, "the separator rule was applied differently from the confirmation's")
        val restore = plan.cells.single()
        assertEquals(1, restore.segmentIdsToRemove.size)
    }

    @Test
    fun `a touched task and a changed cell are reported together`() {
        nextOrder = 0
        val plan =
            planImportRollback(
                facts(
                    tasks = listOf(task(firstTaskId, "Kırmızı ev", updatedAt = later)),
                    cells = listOf(cell("Not", text("Not!"), text(" "), taskPiece(firstTaskId, "Kırmızı ev"))),
                ),
            )

        // The task is reported first because it is the more specific answer, but
        // the user is shown both.
        assertEquals(ImportRollbackFailure.TASKS_WERE_EDITED, plan.failure)
        assertEquals(1, plan.blockedTasks.size)
        assertEquals(1, plan.blockedCells.size)
    }
}
