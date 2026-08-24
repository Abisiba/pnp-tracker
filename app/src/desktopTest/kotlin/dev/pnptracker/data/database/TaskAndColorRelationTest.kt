package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskSetupException
import dev.pnptracker.domain.tasks.TaskSetupFailure
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Tasks, where they are written, and the colours they are produced in. */
class TaskAndColorRelationTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

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

    private fun newId() = IdGenerator.Random.newId()

    private suspend fun colorId(name: String) = assertNotNull(database.colorDao().resolve(name)).id

    @Test
    fun `deleting a task leaves it on disk but out of the active list`() =
        runBlocking<Unit> {
            val task = insertGameCellAndTask(database)

            assertEquals(1, database.taskDao().softDelete(task.id, deletedAt))

            assertNull(database.taskDao().activeTaskById(task.id))
            assertEquals(1, database.taskDao().allTasksIncludingDeleted().size)
            assertEquals(deletedAt, assertNotNull(database.taskDao().taskByIdIncludingDeleted(task.id)).deletedAt)
        }

    @Test
    fun `deleting a task twice changes no timestamp`() =
        runBlocking<Unit> {
            val task = insertGameCellAndTask(database)
            database.taskDao().softDelete(task.id, deletedAt)

            assertEquals(0, database.taskDao().softDelete(task.id, updatedAt))

            assertEquals(deletedAt, assertNotNull(database.taskDao().taskByIdIncludingDeleted(task.id)).deletedAt)
        }

    @Test
    fun `deleting the game takes its tasks out of the active list`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            val task = aTask()
            database.taskDao().addTaskToCell(task, cell.id, newId(), createdAt)

            database.gameDao().softDelete(cell.gameId, deletedAt)

            assertNull(database.taskDao().activeTaskById(task.id))
            assertEquals(emptyList(), database.taskDao().activeTasks())
            assertEquals(1, database.taskDao().allTasksIncludingDeleted().size)
        }

    @Test
    fun `tasks can be listed per cell and per pool`() =
        runBlocking<Unit> {
            val threeD = insertGameCellAndTask(database)
            val cardCell = insertGameAndCell(database, gameName = "Wingspan", columnType = CellColumnType.CARD)
            val card =
                aTask(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE, name = "Bird Cards")
            database.taskDao().addTaskToCell(card, cardCell.id, newId(), createdAt)

            assertEquals(listOf(card), database.taskDao().activeTasksOfCell(cardCell.id))
            assertEquals(listOf(threeD), database.taskDao().activeTasksInPool(PoolType.THREE_D))
            assertEquals(listOf(card), database.taskDao().activeTasksInPool(PoolType.CARD))
        }

    @Test
    fun `a task cannot be written into the notes column`() =
        runBlocking<Unit> {
            val notes = insertGameAndCell(database, columnType = CellColumnType.NOTES)

            val refusal =
                assertFailsWith<TaskSetupException> {
                    database.taskDao().addTaskToCell(aTask(), notes.id, newId(), createdAt)
                }

            // Which refusal it was, not just that there was one: the screen shows
            // a different sentence for each and can only pick the right one if
            // this is carried out rather than flattened into a general failure.
            assertEquals(TaskSetupFailure.CELL_DOES_NOT_HOLD_TASKS, refusal.failure)
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
            assertEquals(0, database.cellSegmentDao().segmentCountOfCell(notes.id))
        }

    @Test
    fun `a task cannot be written into a column its pool does not match`() =
        runBlocking<Unit> {
            val cardCell = insertGameAndCell(database, columnType = CellColumnType.CARD)

            val refusal =
                assertFailsWith<TaskSetupException> {
                    // aTask() is a 3D task, and this is the card column.
                    database.taskDao().addTaskToCell(aTask(), cardCell.id, newId(), createdAt)
                }

            assertEquals(TaskSetupFailure.CELL_POOL_MISMATCH, refusal.failure)
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
            assertEquals(0, database.cellSegmentDao().segmentCountOfCell(cardCell.id))
        }

    @Test
    fun `a task cannot be written into a cell that is not there`() =
        runBlocking<Unit> {
            val refusal =
                assertFailsWith<TaskSetupException> {
                    database.taskDao().addTaskToCell(aTask(), newId(), newId(), createdAt)
                }

            assertEquals(TaskSetupFailure.CELL_NOT_AVAILABLE, refusal.failure)
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
        }

    @Test
    fun `a task is named by exactly one segment`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            val task = aTask()
            database.taskDao().addTaskToCell(task, cell.id, newId(), createdAt)

            val segment = assertNotNull(database.cellSegmentDao().segmentOfTask(task.id))
            assertEquals(cell.id, segment.cellId)
            assertEquals(SegmentKind.TASK, segment.kind)
            assertEquals(0, segment.orderIndex)
            assertNull(segment.text)
        }

    @Test
    fun `a second segment cannot name a task another segment already names`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            val task = aTask()
            database.taskDao().addTaskToCell(task, cell.id, newId(), createdAt)

            assertFailsWith<SQLiteException> {
                database.cellSegmentDao().insert(
                    CellSegmentEntity.task(newId(), cell.id, orderIndex = 1, taskId = task.id, moment = createdAt),
                )
            }

            assertEquals(1, database.cellSegmentDao().segmentCountOfCell(cell.id))
        }

    @Test
    fun `two segments of one cell cannot claim the same place`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            database.cellSegmentDao().insert(
                CellSegmentEntity.plainText(newId(), cell.id, orderIndex = 0, text = "Knight", moment = createdAt),
            )

            assertFailsWith<SQLiteException> {
                database.cellSegmentDao().insert(
                    CellSegmentEntity.plainText(newId(), cell.id, orderIndex = 0, text = "Token", moment = createdAt),
                )
            }

            assertEquals(1, database.cellSegmentDao().segmentCountOfCell(cell.id))
        }

    @Test
    fun `a plain text segment carries text and no task, and a task segment the other way round`() {
        val cellId = newId()
        val taskId = newId()
        assertFailsWith<IllegalArgumentException> {
            CellSegmentEntity(
                id = newId(),
                cellId = cellId,
                orderIndex = 0,
                kind = SegmentKind.PLAIN_TEXT,
                text = null,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CellSegmentEntity(
                id = newId(),
                cellId = cellId,
                orderIndex = 0,
                kind = SegmentKind.PLAIN_TEXT,
                text = "Knight",
                taskId = taskId,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CellSegmentEntity(
                id = newId(),
                cellId = cellId,
                orderIndex = 0,
                kind = SegmentKind.TASK,
                text = "Knight",
                taskId = taskId,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CellSegmentEntity(
                id = newId(),
                cellId = cellId,
                orderIndex = 0,
                kind = SegmentKind.TASK,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }
    }

    @Test
    fun `a game has at most one cell per column`() =
        runBlocking<Unit> {
            val game = aGame()
            database.gameDao().insert(game)
            database.gameCellDao().insert(aCell(gameId = game.id, columnType = CellColumnType.THREE_D))

            assertFailsWith<SQLiteException> {
                database.gameCellDao().insert(aCell(gameId = game.id, columnType = CellColumnType.THREE_D))
            }

            assertEquals(1, database.gameCellDao().cellsOfGame(game.id).size)
        }

    @Test
    fun `asking for the same column twice hands back the same cell`() =
        runBlocking<Unit> {
            val game = aGame()
            database.gameDao().insert(game)

            val first = database.gameCellDao().cellFor(game.id, CellColumnType.CARD, newId(), createdAt)
            val second = database.gameCellDao().cellFor(game.id, CellColumnType.CARD, newId(), updatedAt)

            assertEquals(first, second)
            assertEquals(1, database.gameCellDao().cellsOfGame(game.id).size)
        }

    @Test
    fun `an unknown required quantity is allowed but zero or less is not`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            val unknown = aTask(requiredQuantity = null)
            database.taskDao().addTaskToCell(unknown, cell.id, newId(), createdAt)

            assertNull(assertNotNull(database.taskDao().activeTaskById(unknown.id)).requiredQuantity)
            assertFailsWith<IllegalArgumentException> { aTask(requiredQuantity = 0) }
            assertFailsWith<IllegalArgumentException> { aTask(requiredQuantity = -3) }
        }

    @Test
    fun `a blank task name is rejected`() {
        assertFailsWith<IllegalArgumentException> { aTask(name = "  ") }
    }

    @Test
    fun `enums are stored as text names`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database, columnType = CellColumnType.CARD)
            val task = aTask(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            database.taskDao().addTaskToCell(task, cell.id, newId(), createdAt)

            val stored =
                database.useReaderConnection { transactor ->
                    transactor.usePrepared(
                        "SELECT tasks.pool_type, tasks.tracking_mode, game_cells.column_type, cell_segments.kind " +
                            "FROM tasks " +
                            "INNER JOIN cell_segments ON cell_segments.task_id = tasks.id " +
                            "INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id",
                    ) { statement ->
                        statement.step()
                        (0..3).map(statement::getText)
                    }
                }

            assertEquals(listOf("CARD", "PIPELINE", "CARD", "TASK"), stored)
        }

    @Test
    fun `colour variants are three independent tasks, each with its own quantity`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            val variants =
                listOf("Gri" to 14, "Sarı" to 15, "Yeşil" to 15).map { (colorName, quantity) ->
                    val task = aTask(name = "$colorName token", requiredQuantity = quantity)
                    database.taskDao().addTaskToCell(task, cell.id, newId(), createdAt)
                    database.taskColorDao().addColorToTask(task.id, colorId(colorName))
                    task
                }

            assertEquals(3, database.taskDao().activeTasksOfCell(cell.id).size)
            variants.forEach { task ->
                val colors = database.taskColorDao().colorsOfTask(task.id)
                assertEquals(1, colors.size, "a variant borrowed another one's colour")
                assertEquals(0, colors.single().slotIndex)
            }
            // Nothing ties the three together: no shared row, no shared column.
            assertEquals(3, variants.map { it.id }.toSet().size)
            assertEquals(listOf(14, 15, 15), variants.map { it.requiredQuantity })
        }

    @Test
    fun `a single item in several colours is one task with the colours in order`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            val task = aTask(name = "Kılıç", requiredQuantity = 10)
            database.taskDao().addTaskToCell(task, cell.id, newId(), createdAt)

            database.taskColorDao().addColorToTask(task.id, colorId("Gri"))
            database.taskColorDao().addColorToTask(task.id, colorId("Siyah"))

            val colors = database.taskColorDao().colorsOfTask(task.id)
            assertEquals(listOf(0, 1), colors.map { it.slotIndex })
            assertEquals(listOf(colorId("Gri"), colorId("Siyah")), colors.map { it.colorId })
            assertEquals(1, database.taskDao().activeTasksOfCell(cell.id).size, "a colour became a second task")
            assertEquals(10, assertNotNull(database.taskDao().activeTaskById(task.id)).requiredQuantity)
        }

    @Test
    fun `two colours of one task cannot claim the same place`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            val task = aTask()
            database.taskDao().addTaskToCell(task, cell.id, newId(), createdAt)
            database.taskColorDao().addColorToTask(task.id, colorId("Gri"))

            assertFailsWith<SQLiteException> {
                database.taskColorDao().insert(
                    TaskColorEntity(taskId = task.id, colorId = colorId("Siyah"), slotIndex = 0),
                )
            }

            assertEquals(1, database.taskColorDao().colorsOfTask(task.id).size)
        }

    @Test
    fun `the same colour cannot be added to one task twice`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            val task = aTask()
            database.taskDao().addTaskToCell(task, cell.id, newId(), createdAt)
            database.taskColorDao().addColorToTask(task.id, colorId("Gri"))

            assertFailsWith<IllegalArgumentException> {
                database.taskColorDao().addColorToTask(task.id, colorId("Gri"))
            }

            assertEquals(1, database.taskColorDao().colorsOfTask(task.id).size)
        }

    @Test
    fun `a colour slot cannot start below zero`() {
        assertFailsWith<IllegalArgumentException> {
            TaskColorEntity(taskId = newId(), colorId = newId(), slotIndex = -1)
        }
    }

    @Test
    fun `a task with no colour at all is a task waiting for one`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            val task = aTask(name = "Whale", requiredQuantity = 5)
            database.taskDao().addTaskToCell(task, cell.id, newId(), createdAt)

            assertEquals(emptyList(), database.taskColorDao().colorsOfTask(task.id))
            assertEquals(listOf(task), database.taskDao().activeTasksAwaitingAColor(PoolType.THREE_D))
        }

    @Test
    fun `a deleted task is not waiting for a colour`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            val task = aTask(name = "Whale", requiredQuantity = 5)
            database.taskDao().addTaskToCell(task, cell.id, newId(), createdAt)
            assertEquals(listOf(task), database.taskDao().activeTasksAwaitingAColor(PoolType.THREE_D))

            database.taskDao().softDelete(task.id, deletedAt)

            // Having no colour is a reason to be listed; having been deleted is a
            // reason not to be, and the second wins.
            assertEquals(emptyList(), database.taskDao().activeTasksAwaitingAColor(PoolType.THREE_D))
        }

    @Test
    fun `a task of a deleted game is not waiting for a colour either`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            val task = aTask(name = "Whale", requiredQuantity = 5)
            database.taskDao().addTaskToCell(task, cell.id, newId(), createdAt)
            assertEquals(listOf(task), database.taskDao().activeTasksAwaitingAColor(PoolType.THREE_D))

            // The task itself is untouched: it is the game above its cell that went.
            database.gameDao().softDelete(cell.gameId, deletedAt)

            assertEquals(emptyList(), database.taskDao().activeTasksAwaitingAColor(PoolType.THREE_D))
            assertNotNull(database.taskDao().taskByIdIncludingDeleted(task.id))
        }

    @Test
    fun `a task that has a colour is not waiting for one`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database)
            val task = aTask()
            database.taskDao().addTaskToCell(task, cell.id, newId(), createdAt)
            database.taskColorDao().addColorToTask(task.id, colorId("Gri"))

            assertEquals(emptyList(), database.taskDao().activeTasksAwaitingAColor(PoolType.THREE_D))
        }
}
