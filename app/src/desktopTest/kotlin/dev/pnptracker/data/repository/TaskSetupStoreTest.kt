package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aRawImportBlock
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.deletedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskSetupException
import dev.pnptracker.domain.tasks.TaskSetupFailure
import dev.pnptracker.domain.tasks.TaskSummary
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
import kotlin.time.Instant

/**
 * Creating tasks by hand against a real database in a temporary directory.
 *
 * What cannot be shown against a stand in is exactly what matters here: that a
 * game's tasks really are kept to that game by the query, that a deleted item or
 * game is refused by the transaction rather than let through by a foreign key
 * that only sees the row is still present, and that what the user typed is still
 * there after the application closes.
 */
class TaskSetupStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: TaskSetupStore
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_781_000_000_000)

    @BeforeTest
    fun openTemporaryDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = TaskSetupStore(database.taskDao(), clock = StoppedClock(moment))
    }

    @AfterTest
    fun closeAndDeleteTemporaryDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    /** A game with one open cell in it, ready to write tasks into. */
    private suspend fun aGameWithACell(
        gameName: String = "Harmonies",
        columnType: CellColumnType = CellColumnType.THREE_D,
    ): Pair<EntityId, EntityId> {
        val game = aGame(name = gameName)
        val cell = aCell(gameId = game.id, columnType = columnType)
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        return game.id to cell.id
    }

    private suspend fun tasksOf(gameId: EntityId): List<TaskSummary> = store.observeTasks(gameId).first()

    private suspend fun everyTaskRow() = database.taskDao().allTasksIncludingDeleted()

    @Test
    fun `a task the user typed is written into the cell and comes back in the list`() =
        runBlocking {
            val (gameId, cellId) = aGameWithACell()

            val id =
                store.createTask(
                    cellId = cellId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                )

            val saved = tasksOf(gameId).single()
            assertEquals(id, saved.id)
            assertEquals(cellId, saved.cellId)
            assertEquals(CellColumnType.THREE_D, saved.columnType)
            assertEquals("Gri token", saved.name)
            assertEquals(PoolType.THREE_D, saved.poolType)
            assertEquals(TrackingMode.THREE_D_BATCH, saved.trackingMode)
        }

    @Test
    fun `every field the form offers reaches the row unchanged`() =
        runBlocking {
            val (_, cellId) = aGameWithACell(columnType = CellColumnType.CARD)

            val id =
                store.createTask(
                    cellId = cellId,
                    name = "Mavi kart",
                    poolType = PoolType.CARD,
                    trackingMode = TrackingMode.PIPELINE,
                    requiredQuantity = 24,
                    notes = "Arka yüz mat",
                )

            val row = assertNotNull(database.taskDao().activeTaskById(id))
            assertEquals(cellId, assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(id)).cellId)
            assertEquals("Mavi kart", row.name)
            assertEquals(PoolType.CARD, row.poolType)
            assertEquals(TrackingMode.PIPELINE, row.trackingMode)
            assertEquals(24, row.requiredQuantity)
            assertEquals("Arka yüz mat", row.notes)
            assertNull(row.deletedAt, "a new task is not deleted")
        }

    @Test
    fun `the name is trimmed at the ends and left alone inside`() =
        runBlocking {
            val (_, cellId) = aGameWithACell()

            val id =
                store.createTask(
                    cellId = cellId,
                    name = "  Gri  büyük  token  ",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                )

            assertEquals("Gri  büyük  token", assertNotNull(database.taskDao().activeTaskById(id)).name)
        }

    @Test
    fun `a note that is only spaces is the same as no note`() =
        runBlocking {
            val (_, cellId) = aGameWithACell()

            val id =
                store.createTask(
                    cellId = cellId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    notes = "   ",
                )

            assertNull(assertNotNull(database.taskDao().activeTaskById(id)).notes)
        }

    @Test
    fun `a task the user typed carries no imported cell behind it`() =
        runBlocking {
            val (_, cellId) = aGameWithACell()

            val id =
                store.createTask(
                    cellId = cellId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                )

            val row = assertNotNull(database.taskDao().activeTaskById(id))
            assertNull(row.sourceRawImportBlockId, "a hand made task must not look like an imported one")
        }

    @Test
    fun `the row is created and updated at the one moment the act happened`() =
        runBlocking {
            val (_, cellId) = aGameWithACell()

            val id =
                store.createTask(
                    cellId = cellId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                )

            val row = assertNotNull(database.taskDao().activeTaskById(id))
            assertEquals(moment, row.createdAt)
            assertEquals(moment, row.updatedAt)
        }

    @Test
    fun `a quantity left unknown is stored as unknown rather than as zero`() =
        runBlocking {
            val (_, cellId) = aGameWithACell()

            val id =
                store.createTask(
                    cellId = cellId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = null,
                )

            assertNull(assertNotNull(database.taskDao().activeTaskById(id)).requiredQuantity)
        }

    @Test
    fun `a blank name is refused and nothing at all is written`() =
        runBlocking {
            val (_, cellId) = aGameWithACell()

            assertFailsWith<IllegalArgumentException> {
                store.createTask(
                    cellId = cellId,
                    name = "   ",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                )
            }

            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `a quantity of zero is refused and nothing at all is written`() =
        runBlocking {
            val (_, cellId) = aGameWithACell()

            assertFailsWith<IllegalArgumentException> {
                store.createTask(
                    cellId = cellId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = 0,
                )
            }

            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `a negative quantity is refused and nothing at all is written`() =
        runBlocking {
            val (_, cellId) = aGameWithACell()

            assertFailsWith<IllegalArgumentException> {
                store.createTask(
                    cellId = cellId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = -3,
                )
            }

            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `a tracking mode the pool does not allow is refused and nothing is written`() =
        runBlocking {
            val (_, cellId) = aGameWithACell()

            assertFailsWith<IllegalArgumentException> {
                store.createTask(
                    cellId = cellId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.CHECKLIST,
                )
            }

            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `a broken rule comes out as itself rather than as a saving problem`() =
        runBlocking {
            val (_, cellId) = aGameWithACell()

            // The three refusals below are invariants, not storage failures, and
            // not one of the cell cases either. Were they reported as
            // TaskSetupException the screen would tell the user the database was
            // at fault and offer them nothing to fix — which is exactly what a
            // blanket `catch (IllegalArgumentException)` around the write would
            // do, and why there is not one.
            val refusals =
                listOf<suspend () -> Unit>(
                    { store.createTask(cellId, " ", PoolType.THREE_D, TrackingMode.THREE_D_BATCH) },
                    { store.createTask(cellId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH, 0) },
                    { store.createTask(cellId, "Gri token", PoolType.CARD, TrackingMode.COUNTED) },
                )
            refusals.forEach { attempt -> assertFailsWith<IllegalArgumentException> { attempt() } }
        }

    @Test
    fun `the notes column refuses a task, and says that is why`() =
        runBlocking {
            val (_, notesCellId) = aGameWithACell(columnType = CellColumnType.NOTES)

            val refusal =
                assertFailsWith<TaskSetupException> {
                    store.createTask(notesCellId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
                }

            assertEquals(TaskSetupFailure.CELL_DOES_NOT_HOLD_TASKS, refusal.failure)
            assertEquals(emptyList(), everyTaskRow())
            assertEquals(0, database.cellSegmentDao().segmentCountOfCell(notesCellId))
        }

    @Test
    fun `a 3D task refuses the card column, and says that is why`() =
        runBlocking {
            val (_, cardCellId) = aGameWithACell(columnType = CellColumnType.CARD)

            val refusal =
                assertFailsWith<TaskSetupException> {
                    store.createTask(cardCellId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
                }

            assertEquals(TaskSetupFailure.CELL_POOL_MISMATCH, refusal.failure)
            // Neither half of the pair survives a refusal: a task nothing points
            // at would be unreachable, and a segment naming no task undrawable.
            assertEquals(emptyList(), everyTaskRow())
            assertEquals(0, database.cellSegmentDao().segmentCountOfCell(cardCellId))
        }

    @Test
    fun `no refusal leaves half of a task behind`() =
        runBlocking {
            val (gameId, threeDCellId) = aGameWithACell()
            val cardCell = aCell(gameId = gameId, columnType = CellColumnType.CARD)
            val notesCell = aCell(gameId = gameId, columnType = CellColumnType.NOTES)
            database.gameCellDao().insert(cardCell)
            database.gameCellDao().insert(notesCell)

            val refused =
                listOf<suspend () -> Unit>(
                    { store.createTask(cardCell.id, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH) },
                    { store.createTask(notesCell.id, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH) },
                    {
                        store.createTask(
                            IdGenerator.Random.newId(),
                            "Gri token",
                            PoolType.THREE_D,
                            TrackingMode.THREE_D_BATCH,
                        )
                    },
                )
            refused.forEach { attempt -> assertFailsWith<TaskSetupException> { attempt() } }

            assertEquals(emptyList(), everyTaskRow())
            listOf(threeDCellId, cardCell.id, notesCell.id).forEach { cellId ->
                assertEquals(0, database.cellSegmentDao().segmentCountOfCell(cellId), "a segment was left in $cellId")
            }
        }

    @Test
    fun `a cell whose game was deleted refuses the task even though its row is still there`() =
        runBlocking {
            val (gameId, cellId) = aGameWithACell()
            assertEquals(1, database.gameDao().softDelete(gameId, deletedAt))

            val refusal =
                assertFailsWith<TaskSetupException> {
                    store.createTask(cellId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
                }

            assertEquals(TaskSetupFailure.CELL_NOT_AVAILABLE, refusal.failure)
            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `a game that was deleted refuses a task in its cell`() =
        runBlocking {
            val (gameId, cellId) = aGameWithACell()
            assertEquals(1, database.gameDao().softDelete(gameId, deletedAt))

            val refusal =
                assertFailsWith<TaskSetupException> {
                    store.createTask(cellId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
                }

            assertEquals(TaskSetupFailure.CELL_NOT_AVAILABLE, refusal.failure)
            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `a cell that was never there refuses the task`() =
        runBlocking {
            aGameWithACell()

            val refusal =
                assertFailsWith<TaskSetupException> {
                    store.createTask(
                        IdGenerator.Random.newId(),
                        "Gri token",
                        PoolType.THREE_D,
                        TrackingMode.THREE_D_BATCH,
                    )
                }

            assertEquals(TaskSetupFailure.CELL_NOT_AVAILABLE, refusal.failure)
            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `one game's tasks never include another game's`() =
        runBlocking {
            val (firstGame, firstCell) = aGameWithACell(gameName = "Harmonies")
            val (secondGame, secondCell) = aGameWithACell(gameName = "Root")
            store.createTask(firstCell, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
            store.createTask(secondCell, "Kedi meeple", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertEquals(listOf("Gri token"), tasksOf(firstGame).map { it.name })
            assertEquals(listOf("Kedi meeple"), tasksOf(secondGame).map { it.name })
        }

    @Test
    fun `each row names the column it is really written in`() =
        runBlocking {
            val (gameId, tokens) = aGameWithACell()
            val cards = aCell(gameId = gameId, columnType = CellColumnType.CARD)
            database.gameCellDao().insert(cards)
            store.createTask(tokens, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
            store.createTask(cards.id, "Olay kartı", PoolType.CARD, TrackingMode.PIPELINE)

            val byName = tasksOf(gameId).associateBy { it.name }
            assertEquals(CellColumnType.THREE_D, assertNotNull(byName["Gri token"]).columnType)
            assertEquals(CellColumnType.CARD, assertNotNull(byName["Olay kartı"]).columnType)
            assertEquals(cards.id, assertNotNull(byName["Olay kartı"]).cellId)
        }

    @Test
    fun `a deleted task drops out of the game's list`() =
        runBlocking {
            val (gameId, cellId) = aGameWithACell()
            val id = store.createTask(cellId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertEquals(1, database.taskDao().softDelete(id, deletedAt))

            assertEquals(emptyList(), tasksOf(gameId))
        }

    @Test
    fun `a task written into another game's cell stays out of this game's list`() =
        runBlocking {
            val (gameId, _) = aGameWithACell(gameName = "Harmonies")
            val (_, otherCell) = aGameWithACell(gameName = "Root")
            store.createTask(otherCell, "Kedi meeple", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertEquals(emptyList(), tasksOf(gameId))
        }

    @Test
    fun `a deleted game takes its tasks out of its own list`() =
        runBlocking {
            val (gameId, cellId) = aGameWithACell()
            store.createTask(cellId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertEquals(1, database.gameDao().softDelete(gameId, deletedAt))

            assertEquals(emptyList(), tasksOf(gameId))
        }

    @Test
    fun `a deleted task stays on disk after dropping out of the list`() =
        runBlocking<Unit> {
            val (gameId, cellId) = aGameWithACell()
            val id = store.createTask(cellId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertEquals(1, database.taskDao().softDelete(id, deletedAt))

            assertEquals(emptyList(), tasksOf(gameId))
            assertNotNull(database.taskDao().taskByIdIncludingDeleted(id), "the row was really removed")
        }

    @Test
    fun `the row order is the same on every read`() =
        runBlocking {
            val (gameId, tokens) = aGameWithACell()
            val cards = aCell(gameId = gameId, columnType = CellColumnType.CARD)
            database.gameCellDao().insert(cards)
            store.createTask(cards.id, "Olay kartı", PoolType.CARD, TrackingMode.PIPELINE)
            store.createTask(tokens, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
            store.createTask(cards.id, "Anlaşma kartı", PoolType.CARD, TrackingMode.PIPELINE)

            // Cells come in the order the table is read in — 3D before cards,
            // whatever order the tasks were written in and whatever the stored
            // text would sort as — and inside a cell the pieces come in the order
            // they were written.
            val expected = listOf("Gri token", "Olay kartı", "Anlaşma kartı")
            assertEquals(expected, tasksOf(gameId).map { it.name })
            assertEquals(expected, tasksOf(gameId).map { it.name }, "a second read reordered the list")
        }

    @Test
    fun `two tasks may carry the same name`() =
        runBlocking {
            val (gameId, cellId) = aGameWithACell()

            val first = store.createTask(cellId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
            val second = store.createTask(cellId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertTrue(first != second, "the two tasks are separate rows")
            assertEquals(2, tasksOf(gameId).size)
        }

    @Test
    fun `what the user typed is still there after the database is closed and opened`() =
        runBlocking {
            val (gameId, cellId) = aGameWithACell(columnType = CellColumnType.SPECIAL)
            store.createTask(
                cellId = cellId,
                name = "Gri token",
                poolType = PoolType.SPECIAL,
                trackingMode = TrackingMode.COUNTED,
                requiredQuantity = 7,
                notes = "Kutu içi ayraç",
            )

            database.close()
            database = DatabaseFactory().open(directory.databaseFile)
            store = TaskSetupStore(database.taskDao(), clock = StoppedClock(moment))

            val saved = tasksOf(gameId).single()
            assertEquals("Gri token", saved.name)
            assertEquals(PoolType.SPECIAL, saved.poolType)
            assertEquals(TrackingMode.COUNTED, saved.trackingMode)
            assertEquals(7, saved.requiredQuantity)
            assertEquals("Kutu içi ayraç", saved.notes)
        }

    @Test
    fun `a hand made task and an imported one sit in the same list and stay told apart`() =
        runBlocking {
            val (gameId, cellId) = aGameWithACell()
            val batch = anImportBatch()
            val block = aRawImportBlock(importBatchId = batch.id)
            database.importDao().insertBatch(batch)
            database.importDao().insertRawBlock(block)
            database.taskDao().addTaskToCell(
                task = aTask(name = "İçe aktarılan token").copy(sourceRawImportBlockId = block.id),
                cellId = cellId,
                segmentId = IdGenerator.Random.newId(),
                moment = createdAt,
            )

            store.createTask(cellId, "Elle yazılan token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            val byName = tasksOf(gameId).associateBy { it.name }
            assertEquals(2, byName.size)
            assertTrue(assertNotNull(byName["İçe aktarılan token"]).isFromImport, "the imported task lost its source")
            assertTrue(!assertNotNull(byName["Elle yazılan token"]).isFromImport, "a typed task gained a source")
            assertEquals(
                block.id,
                assertNotNull(
                    database.taskDao().activeTaskById(assertNotNull(byName["İçe aktarılan token"]).id),
                ).sourceRawImportBlockId,
                "the audit trail behind the imported task must survive untouched",
            )
        }

    @Test
    fun `creating a task by hand leaves the import tables exactly as they were`() =
        runBlocking {
            val (_, cellId) = aGameWithACell()
            val batch = anImportBatch(rawBlockCount = 1)
            val block = aRawImportBlock(importBatchId = batch.id)
            database.importDao().insertBatch(batch)
            database.importDao().insertRawBlock(block)
            val batchesBefore = database.importDao().allBatches()
            val blocksBefore = database.importDao().rawBlocksOfBatch(batch.id)
            val draftsBefore = database.importDao().draftTasksOfBatch(batch.id)

            store.createTask(cellId, "Elle yazılan token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertEquals(batchesBefore, database.importDao().allBatches())
            assertEquals(blocksBefore, database.importDao().rawBlocksOfBatch(batch.id))
            assertEquals(draftsBefore, database.importDao().draftTasksOfBatch(batch.id))
            assertEquals(0, assertNotNull(database.importDao().batchById(batch.id)).createdTaskCount)
        }
}
