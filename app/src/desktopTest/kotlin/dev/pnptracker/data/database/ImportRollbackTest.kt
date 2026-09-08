package dev.pnptracker.data.database

import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.data.repository.PoolStore
import dev.pnptracker.domain.importrollback.CellObstacle
import dev.pnptracker.domain.importrollback.ImportRollbackException
import dev.pnptracker.domain.importrollback.ImportRollbackFailure
import dev.pnptracker.domain.importrollback.TaskObstacle
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Taking a confirmed import back, against a real database.
 *
 * PLAN 11.4.4 allows two outcomes and no third, and both are asked here in the
 * only way that means anything: against real rows, through the real transaction.
 * A refusal has to leave the database byte for byte as it was, and a rollback
 * has to leave the cells reading exactly what they read before the import — with
 * the user's own pieces still carrying their own identities, not rebuilt into a
 * string that happens to match.
 *
 * The fixture is deliberately the awkward shape: a cell that already held the
 * user's writing **and** a task of their own, with the import's work appended
 * after it. A rollback that flattened the cell into one run of text would pass a
 * check on the words and destroy a task, so what is asserted is the pieces.
 */
class ImportRollbackTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private val importedAt = Instant.fromEpochMilliseconds(1_780_000_000_000)
    private val takenBackAt = Instant.fromEpochMilliseconds(1_780_900_000_000)

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val importDao get() = database.importDao()

    private fun rollback(clock: StoppedClock = StoppedClock(takenBackAt)) = ImportRollbackStore(importDao, IdGenerator.Random, clock)

    // ------------------------------------------------------------------ fixture

    private var gameId: EntityId = IdGenerator.Random.newId()
    private var cellId: EntityId = IdGenerator.Random.newId()
    private var batchId: EntityId = IdGenerator.Random.newId()
    private var handwrittenTaskId: EntityId = IdGenerator.Random.newId()
    private var rows = 0
    private var batches = 0

    /** The fill a spreadsheet marks a finished game with (PLAN 11.5). */
    private val greenFill = 0xFF92D050.toInt()

    /** What the cell reads before any import touches it. */
    private val documentBefore = "Önce Elle görev sonra"

    /**
     * One game with a 3D cell the user has already filled in themselves: some
     * words, a task of their own, and some more words after it.
     */
    private suspend fun givenACellTheUserFilledIn() {
        val game = aGame(name = "Harmonies")
        database.gameDao().insert(game)
        gameId = game.id
        database.cellSegmentDao().saveDocumentText(
            gameId,
            CellColumnType.THREE_D,
            "",
            "Önce ",
            StoppedClock(createdAt),
            IdGenerator.Random,
        )
        cellId = assertNotNull(database.gameCellDao().cellOfGame(gameId, CellColumnType.THREE_D)).id
        val handwritten = aTask(name = "Elle görev")
        database.taskDao().addTaskToCell(handwritten, cellId, IdGenerator.Random.newId(), createdAt)
        handwrittenTaskId = handwritten.id
        database.cellSegmentDao().saveDocumentText(
            gameId,
            CellColumnType.THREE_D,
            "Önce Elle görev",
            documentBefore,
            StoppedClock(createdAt),
            IdGenerator.Random,
        )
    }

    /** A draft import aimed at that cell, not yet confirmed. */
    private suspend fun aDraftBatch(vararg taskNames: String): EntityId {
        val batch = anImportBatch(rawBlockCount = taskNames.size, sha256 = "%064x".format(batches++))
        importDao.insertBatch(batch)
        taskNames.forEach { name ->
            rows++
            val block =
                aRawImportBlock(
                    batch.id,
                    rowIndex = rows,
                    columnIndex = 1,
                    rawText = "15 KIRMIZI $name",
                    sourceColumnType = SourceColumnType.THREE_D,
                )
            importDao.insertRawBlock(block)
            importDao.setRawBlockProcessed(block.id, true, updatedAt)
            val draft = aDraftTask(block.id, name = name).copy(requiredQuantity = 20, createdAt = createdAt + rows.seconds)
            importDao.addDraftTask(draft)
            importDao.setDraftTargetUnderReview(draft.id, cellId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH, updatedAt)
        }
        return batch.id
    }

    /** The ordinary starting point: a cell the user filled in, plus one confirmed import. */
    private suspend fun givenAConfirmedImport(vararg taskNames: String = arrayOf("Kırmızı ev", "Mavi ev")) {
        givenACellTheUserFilledIn()
        batchId = aDraftBatch(*taskNames)
        importDao.confirmDraftBatch(batchId, true, StoppedClock(importedAt), IdGenerator.Random)
    }

    // ------------------------------------------------------------------ readings

    private data class Piece(
        val id: EntityId,
        val orderIndex: Int,
        val kind: SegmentKind,
        val text: String?,
        val taskId: EntityId?,
    )

    private suspend fun piecesOf(cell: EntityId = cellId): List<Piece> =
        database.cellSegmentDao().segmentsOfCell(cell).map {
            Piece(it.id, it.orderIndex, it.kind, it.text, it.taskId)
        }

    private suspend fun documentOf(cell: EntityId = cellId): String =
        buildString {
            database.cellSegmentDao().segmentsOfCell(cell).forEach { piece ->
                append(
                    piece.text
                        ?: assertNotNull(database.taskDao().taskByIdIncludingDeleted(assertNotNull(piece.taskId))).name,
                )
            }
        }

    private suspend fun importedTasks() = importDao.tasksOfConfirmedBatch(batchId)

    private suspend fun eventKinds() = database.historyDao().allEvents().map { it.kind }

    // ------------------------------------------------------------- the happy path

    @Test
    fun `an untouched import is offered for taking back, and says how much would go`() =
        runBlocking<Unit> {
            givenAConfirmedImport()

            val preview = rollback().previewRollback(batchId)

            assertTrue(preview.canRollBack, "an untouched import was refused: ${preview.blockingFailure}")
            assertEquals(ImportBatchStatus.CONFIRMED, preview.status)
            assertEquals(2, preview.taskCount)
            assertEquals(1, preview.cellCount)
            assertEquals(1, preview.gameCount)
            assertEquals(emptyList(), preview.blockedTasks)
            assertEquals(emptyList(), preview.blockedCells)
        }

    @Test
    fun `taking it back puts the cell back piece for piece`() =
        runBlocking<Unit> {
            givenACellTheUserFilledIn()
            val before = piecesOf()
            batchId = aDraftBatch("Kırmızı ev", "Mavi ev")
            importDao.confirmDraftBatch(batchId, true, StoppedClock(importedAt), IdGenerator.Random)
            assertEquals("$documentBefore Kırmızı ev Mavi ev", documentOf(), "the fixture is not what these are about")

            val result = rollback().rollBack(batchId)

            assertEquals(2, result.removedTaskCount)
            assertEquals(1, result.restoredCellCount)
            assertEquals(1, result.affectedGameCount)
            // Not merely the same words: the same rows, with the same identities,
            // the same kinds, the same numbering and the user's own task still
            // anchored to the very piece it was anchored to.
            assertEquals(before, piecesOf(), "the cell was rebuilt rather than put back")
            assertEquals(documentBefore, documentOf())
            assertEquals(
                listOf(SegmentKind.PLAIN_TEXT, SegmentKind.TASK, SegmentKind.PLAIN_TEXT),
                piecesOf().map { it.kind },
            )
            assertEquals(handwrittenTaskId, piecesOf().single { it.kind == SegmentKind.TASK }.taskId)
        }

    @Test
    fun `the tasks it made go out of view without being destroyed`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            val made = importedTasks().map { it.id }
            val colorsBefore = importDao.taskColorsOfConfirmedBatch(batchId)
            val stagesBefore = importDao.taskStagesOfConfirmedBatch(batchId)

            rollback().rollBack(batchId)

            val after = importedTasks()
            assertEquals(made.toSet(), after.map { it.id }.toSet(), "a task row was destroyed")
            assertTrue(after.all { it.deletedAt == takenBackAt }, "a task was left in view")
            assertTrue(after.all { it.updatedAt == takenBackAt })
            // PLAN 5.2: a tombstone leaves everything hanging off the row alone.
            assertEquals(colorsBefore, importDao.taskColorsOfConfirmedBatch(batchId))
            assertEquals(stagesBefore, importDao.taskStagesOfConfirmedBatch(batchId))
        }

    @Test
    fun `a clock finer than a millisecond does not break the rollback`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            // The application runs on `Clock.System`, which carries microseconds,
            // and the column holds milliseconds — so a stored moment comes back
            // rounded. Every test above uses a whole millisecond and would never
            // meet that; the first real rollback would have.
            val finer = takenBackAt + 375.microseconds
            val store = ImportRollbackStore(importDao, IdGenerator.Random, StoppedClock(finer))

            val result = store.rollBack(batchId)

            assertEquals(2, result.removedTaskCount)
            assertEquals(ImportBatchStatus.ROLLED_BACK, assertNotNull(importDao.batchById(batchId)).status)
            assertTrue(importedTasks().all { it.deletedAt != null }, "a task was left in view")
            // Rounded on the way to disk, and the same rounded moment on every
            // row the one transaction wrote.
            val moments = importedTasks().mapNotNull { it.deletedAt } + database.historyDao().allEvents().map { it.occurredAt }
            assertEquals(setOf(takenBackAt), moments.toSet() - importedAt, "the transaction's rows carry different moments")
            assertEquals(documentBefore, documentOf())
        }

    @Test
    fun `the batch becomes rolled back and the pools stop showing its work`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            val pools = PoolStore(database.poolDao())
            assertEquals(3, activePoolTasks(assertNotNull(pools.observePool(PoolType.THREE_D).first())).size)

            rollback().rollBack(batchId)

            assertEquals(ImportBatchStatus.ROLLED_BACK, assertNotNull(importDao.batchById(batchId)).status)
            val remaining = activePoolTasks(assertNotNull(pools.observePool(PoolType.THREE_D).first()))
            assertEquals(listOf(handwrittenTaskId), remaining.map { it.taskId }, "the import's work is still in the pool")
        }

    @Test
    fun `a second attempt is refused rather than quietly succeeding`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            rollback().rollBack(batchId)
            val settled = piecesOf()
            val history = database.historyDao().allEvents()

            val refusal = assertFailsWith<ImportRollbackException> { rollback().rollBack(batchId) }

            assertEquals(ImportRollbackFailure.ALREADY_ROLLED_BACK, refusal.failure)
            assertEquals(settled, piecesOf())
            assertEquals(history, database.historyDao().allEvents(), "a refused repeat wrote to the history")
            assertEquals(
                ImportRollbackFailure.ALREADY_ROLLED_BACK,
                rollback().previewRollback(batchId).blockingFailure,
            )
        }

    @Test
    fun `a draft and a batch that is not there are refused apart`() =
        runBlocking<Unit> {
            givenACellTheUserFilledIn()
            val draft = aDraftBatch("Kırmızı ev")

            assertEquals(
                ImportRollbackFailure.BATCH_NOT_CONFIRMED,
                assertFailsWith<ImportRollbackException> { rollback().rollBack(draft) }.failure,
            )
            assertEquals(
                ImportRollbackFailure.BATCH_NOT_FOUND,
                assertFailsWith<ImportRollbackException> { rollback().rollBack(IdGenerator.Random.newId()) }.failure,
            )
        }

    // ------------------------------------------------------------ what stops it

    /** Runs [interfere], then proves the rollback refuses and changes nothing. */
    private suspend fun refusedAndUnchanged(
        expected: ImportRollbackFailure,
        interfere: suspend () -> Unit,
    ): ImportRollbackException {
        interfere()
        val pieces = piecesOf()
        val tasks = database.taskDao().allTasksIncludingDeleted()
        val history = database.historyDao().allEvents()
        val batch = assertNotNull(importDao.batchById(batchId))

        val refusal = assertFailsWith<ImportRollbackException> { rollback().rollBack(batchId) }

        assertEquals(expected, refusal.failure)
        assertEquals(pieces, piecesOf(), "a refused rollback changed the cell")
        assertEquals(tasks, database.taskDao().allTasksIncludingDeleted(), "a refused rollback touched a task")
        assertEquals(history, database.historyDao().allEvents(), "a refused rollback wrote to the history")
        assertEquals(batch, assertNotNull(importDao.batchById(batchId)), "a refused rollback moved the batch")
        assertEquals(expected, rollback().previewRollback(batchId).blockingFailure)
        return refusal
    }

    @Test
    fun `one task edited by hand stops all of it, and the safe ones stay`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            val touched = importedTasks().last()

            val refusal =
                refusedAndUnchanged(ImportRollbackFailure.TASKS_WERE_EDITED) {
                    database.taskEditDao().editTask(
                        taskId = touched.id,
                        name = "Elle değiştirildi",
                        colorIds = emptyList(),
                        requiredQuantity = 20,
                        notes = null,
                        trackingMode = TrackingMode.THREE_D_BATCH,
                        flags = null,
                        clock = StoppedClock(takenBackAt),
                    )
                }

            assertEquals(listOf(touched.id), refusal.blockedTasks.map { it.taskId })
            // The other task of the same import is untouched and stays that way.
            assertEquals(TaskObstacle.EDITED, refusal.blockedTasks.single().obstacle)
            assertTrue(importedTasks().all { it.deletedAt == null }, "a safe task was removed anyway")
        }

    @Test
    fun `a shortage reported against one task stops all of it`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            val reported = importedTasks().first()

            val refusal =
                refusedAndUnchanged(ImportRollbackFailure.TASKS_WERE_EDITED) {
                    database.taskProgressDao().reportFailure(
                        eventId = IdGenerator.Random.newId(),
                        taskId = reported.id,
                        quantity = 3,
                        clock = StoppedClock(takenBackAt),
                    )
                }

            // Reporting a shortage moves `updatedAt` as well, so the reason has
            // to be the more specific one rather than merely "edited".
            assertEquals(TaskObstacle.EDITED, refusal.blockedTasks.single().obstacle)
        }

    @Test
    fun `a task the user deleted stops all of it`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            val removed = importedTasks().first()

            val refusal =
                refusedAndUnchanged(ImportRollbackFailure.TASKS_WERE_EDITED) {
                    database.taskDao().softDelete(removed.id, takenBackAt)
                }

            assertEquals(TaskObstacle.DELETED, refusal.blockedTasks.single().obstacle)
        }

    @Test
    fun `a task turned back into text stops all of it`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            val converted = importedTasks().last()

            val refusal =
                refusedAndUnchanged(ImportRollbackFailure.TASKS_WERE_EDITED) {
                    database.taskEditDao().convertTaskToText(
                        converted.id,
                        StoppedClock(takenBackAt),
                        IdGenerator.Random,
                    )
                }

            // Converting soft deletes as its mechanism, so this is reported as a
            // deletion. Either way the user's decision stands.
            assertEquals(TaskObstacle.DELETED, refusal.blockedTasks.single().obstacle)
        }

    @Test
    fun `a single space typed into the cell stops all of it`() =
        runBlocking<Unit> {
            givenAConfirmedImport()

            val refusal =
                refusedAndUnchanged(ImportRollbackFailure.CELLS_WERE_EDITED) {
                    database.cellSegmentDao().saveDocumentText(
                        gameId,
                        CellColumnType.THREE_D,
                        "$documentBefore Kırmızı ev Mavi ev",
                        "$documentBefore  Kırmızı ev Mavi ev",
                        StoppedClock(takenBackAt),
                        IdGenerator.Random,
                    )
                }

            assertEquals(CellObstacle.DOCUMENT_CHANGED, refusal.blockedCells.single().obstacle)
            assertEquals(gameId, refusal.blockedCells.single().gameId)
            assertEquals("Harmonies", refusal.blockedCells.single().gameName)
            assertEquals(CellColumnType.THREE_D, refusal.blockedCells.single().columnType)
        }

    @Test
    fun `words added to the cell without touching a task stop all of it`() =
        runBlocking<Unit> {
            givenAConfirmedImport()

            refusedAndUnchanged(ImportRollbackFailure.CELLS_WERE_EDITED) {
                database.cellSegmentDao().saveDocumentText(
                    gameId,
                    CellColumnType.THREE_D,
                    "$documentBefore Kırmızı ev Mavi ev",
                    "$documentBefore Kırmızı ev Mavi ev — sonradan eklendi",
                    StoppedClock(takenBackAt),
                    IdGenerator.Random,
                )
            }
            // Every task of the import is still untouched: this is the cell rule
            // on its own, which is why PLAN 11.4.4 states it separately.
            assertTrue(importedTasks().all { it.updatedAt == it.createdAt })
        }

    @Test
    fun `a task added to the cell by hand stops all of it`() =
        runBlocking<Unit> {
            givenAConfirmedImport()

            refusedAndUnchanged(ImportRollbackFailure.CELLS_WERE_EDITED) {
                database.taskDao().addTaskToCell(
                    aTask(name = "Sonradan elle"),
                    cellId,
                    IdGenerator.Random.newId(),
                    takenBackAt,
                )
            }
        }

    // ------------------------------------------------- one cell, two imports

    @Test
    fun `a second import into the same cell blocks the first, and the second still goes`() =
        runBlocking<Unit> {
            givenAConfirmedImport("Kırmızı ev")
            val first = batchId
            batchId = aDraftBatch("Mavi ev")
            importDao.confirmDraftBatch(batchId, true, StoppedClock(importedAt), IdGenerator.Random)
            val second = batchId
            val afterBoth = piecesOf()

            // The earlier import's work is no longer a suffix of the cell, so
            // taking it back would have to reach past somebody else's task.
            val refusal = assertFailsWith<ImportRollbackException> { rollback().rollBack(first) }
            assertEquals(ImportRollbackFailure.CELLS_WERE_EDITED, refusal.failure)
            assertEquals(CellObstacle.STRUCTURE_CHANGED, refusal.blockedCells.single().obstacle)
            assertEquals(afterBoth, piecesOf(), "a refused rollback changed the cell")

            // The later one still is a suffix, and taking it back leaves the
            // earlier import's task exactly where it was.
            rollback().rollBack(second)

            assertEquals("$documentBefore Kırmızı ev", documentOf())
            assertEquals(ImportBatchStatus.CONFIRMED, assertNotNull(importDao.batchById(first)).status)
            assertEquals(1, importDao.tasksOfConfirmedBatch(first).count { it.deletedAt == null })
        }

    @Test
    fun `a batch spread over two cells puts both back and writes one line per game`() =
        runBlocking<Unit> {
            givenACellTheUserFilledIn()
            val other = aGame(name = "Wingspan")
            database.gameDao().insert(other)
            val otherCell = aCell(gameId = other.id, columnType = CellColumnType.THREE_D)
            database.gameCellDao().insert(otherCell)

            val batch = anImportBatch(rawBlockCount = 2, sha256 = "%064x".format(batches++))
            importDao.insertBatch(batch)
            batchId = batch.id
            listOf(cellId to "Kırmızı ev", otherCell.id to "Sarı kuş").forEach { (target, name) ->
                rows++
                val block =
                    aRawImportBlock(batch.id, rowIndex = rows, columnIndex = 1, sourceColumnType = SourceColumnType.THREE_D)
                importDao.insertRawBlock(block)
                importDao.setRawBlockProcessed(block.id, true, updatedAt)
                val draft = aDraftTask(block.id, name = name).copy(requiredQuantity = 5, createdAt = createdAt + rows.seconds)
                importDao.addDraftTask(draft)
                importDao.setDraftTargetUnderReview(draft.id, target, PoolType.THREE_D, TrackingMode.THREE_D_BATCH, updatedAt)
            }
            importDao.confirmDraftBatch(batchId, true, StoppedClock(importedAt), IdGenerator.Random)

            val result = rollback().rollBack(batchId)

            assertEquals(2, result.restoredCellCount)
            assertEquals(2, result.affectedGameCount, "two games got one line between them")
            assertEquals(documentBefore, documentOf())
            assertEquals("", documentOf(otherCell.id))
            assertEquals(emptyList(), piecesOf(otherCell.id), "an emptied cell was left holding a piece")
        }

    // ------------------------------------------------------------------ history

    @Test
    fun `confirming writes one line per game and taking back writes one per game and task`() =
        runBlocking<Unit> {
            givenAConfirmedImport()

            assertEquals(listOf(HistoryEventKind.IMPORT_CONFIRMED), eventKinds(), "confirming wrote the wrong history")

            rollback().rollBack(batchId)

            val events = database.historyDao().allEvents()
            assertEquals(
                mapOf(
                    HistoryEventKind.IMPORT_CONFIRMED to 1,
                    HistoryEventKind.TASK_ROLLED_BACK to 2,
                    HistoryEventKind.IMPORT_ROLLED_BACK to 1,
                ),
                events.groupingBy { it.kind }.eachCount(),
            )
            // Everything one transaction wrote carries the one moment it ran at.
            val written = events.filterNot { it.kind == HistoryEventKind.IMPORT_CONFIRMED }
            assertEquals(setOf(takenBackAt), written.map { it.occurredAt }.toSet())
            assertEquals(setOf(gameId), written.map { it.gameId }.toSet())
            // The game's own line names no task; each task's line names its task.
            assertNull(written.single { it.kind == HistoryEventKind.IMPORT_ROLLED_BACK }.taskId)
            assertEquals(
                importedTasks().map { it.id }.toSet(),
                written.filter { it.kind == HistoryEventKind.TASK_ROLLED_BACK }.mapNotNull { it.taskId }.toSet(),
            )
            // The confirmation's own line is still there, unedited.
            assertEquals(importedAt, events.single { it.kind == HistoryEventKind.IMPORT_CONFIRMED }.occurredAt)
        }

    @Test
    fun `six tasks in one game are one confirmation line and one rollback line`() =
        runBlocking<Unit> {
            givenAConfirmedImport("Bir", "İki", "Üç", "Dört", "Beş", "Altı")

            assertEquals(1, eventKinds().count { it == HistoryEventKind.IMPORT_CONFIRMED })

            rollback().rollBack(batchId)

            assertEquals(1, eventKinds().count { it == HistoryEventKind.IMPORT_ROLLED_BACK })
            assertEquals(6, eventKinds().count { it == HistoryEventKind.TASK_ROLLED_BACK })
        }

    @Test
    fun `the games the import finished stay finished`() =
        runBlocking<Unit> {
            givenACellTheUserFilledIn()
            batchId = aDraftBatch("Kırmızı ev")
            // PLAN 11.5's green cell, accepted by the user. Confirming the
            // import is what actually finishes the game (PLAN 5.3).
            val green =
                aRawImportBlock(
                    batchId,
                    rowIndex = 99,
                    columnIndex = 0,
                    rawText = "Harmonies",
                    sourceColumnType = SourceColumnType.GAME,
                    fillColorArgb = greenFill,
                )
            importDao.insertRawBlock(green)
            importDao.setGameCompletionDecisionUnderReview(green.id, HintDecision.ACCEPTED, gameId, StoppedClock(updatedAt))
            importDao.confirmDraftBatch(batchId, true, StoppedClock(importedAt), IdGenerator.Random)
            val marked = assertNotNull(database.gameDao().gameByIdIncludingDeleted(gameId))
            assertNotNull(marked.completedAt, "the fixture never finished the game")
            assertTrue(marked.isManuallyCompleted)

            rollback().rollBack(batchId)

            // PLAN 11.4.4 is explicit: the mark is the user's own statement and
            // a rollback is not entitled to take it back. The moment it was made
            // is checked too, because rewriting it would move a date they set.
            val after = assertNotNull(database.gameDao().gameByIdIncludingDeleted(gameId))
            assertEquals(marked.completedAt, after.completedAt, "the rollback undid a completion the user had accepted")
            assertEquals(marked.isManuallyCompleted, after.isManuallyCompleted)
            // And it really did take the import back, so this is not a test that
            // passes because nothing happened.
            assertEquals(documentBefore, documentOf())
        }

    @Test
    fun `nothing that was recorded before is destroyed`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            // A shortage on the user's own task, and its own history line, both
            // of which have nothing to do with the import and must survive it.
            database.taskProgressDao().reportFailure(
                eventId = IdGenerator.Random.newId(),
                taskId = handwrittenTaskId,
                quantity = 4,
                clock = StoppedClock(importedAt),
                note = "kenar bozuk",
            )
            val progress = database.taskProgressDao().progressEventsOfTask(handwrittenTaskId)
            val historyBefore = database.historyDao().allEvents().size

            rollback().rollBack(batchId)

            assertEquals(progress, database.taskProgressDao().progressEventsOfTask(handwrittenTaskId))
            assertEquals(
                historyBefore + 3,
                database.historyDao().allEvents().size,
                "the history lost a line or gained one it should not have",
            )
            assertEquals(handwrittenTaskId, piecesOf().single { it.kind == SegmentKind.TASK }.taskId)
            assertNull(assertNotNull(database.taskDao().taskByIdIncludingDeleted(handwrittenTaskId)).deletedAt)
        }
}
