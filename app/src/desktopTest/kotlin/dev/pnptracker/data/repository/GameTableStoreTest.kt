package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CommittedSchema
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.deletedAt
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.insertSegmentDirectly
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the whole game table out of a real database.
 *
 * The two things worth proving here are that a row is complete however little of
 * it has been written, and that reading a hundred games costs what reading two
 * costs. Both are properties of the read rather than of any screen, so both are
 * tested against the database instead of against a fake.
 */
class GameTableStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: GameTableStore
    private var realDatabaseExistedBefore = false

    private val ids = IdGenerator.Random

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao())
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun addGame(
        name: String,
        isCompleted: Boolean = false,
        deleted: Boolean = false,
    ): GameEntity {
        val game =
            aGame(name = name).copy(
                isManuallyCompleted = isCompleted,
                completedAt = createdAt.takeIf { isCompleted },
                deletedAt = deletedAt.takeIf { deleted },
            )
        database.gameDao().insert(game)
        return game
    }

    private suspend fun addCell(
        gameId: EntityId,
        columnType: CellColumnType,
    ): EntityId {
        val cell = aCell(gameId = gameId, columnType = columnType)
        database.gameCellDao().insert(cell)
        return cell.id
    }

    private suspend fun addText(
        cellId: EntityId,
        text: String,
        orderIndex: Int,
    ) {
        insertSegmentDirectly(
            database,
            CellSegmentEntity.plainText(
                id = ids.newId(),
                cellId = cellId,
                orderIndex = orderIndex,
                text = text,
                moment = createdAt,
            ),
        )
    }

    private suspend fun addTask(
        cellId: EntityId,
        name: String,
        poolType: PoolType = PoolType.THREE_D,
    ): EntityId {
        val task =
            aTask(
                name = name,
                poolType = poolType,
                trackingMode =
                    when (poolType) {
                        PoolType.THREE_D -> TrackingMode.THREE_D_BATCH
                        PoolType.CARD, PoolType.BOARD -> TrackingMode.PIPELINE
                        PoolType.SPECIAL -> TrackingMode.COUNTED
                    },
            )
        database.taskDao().addTaskToCell(task, cellId, ids.newId(), createdAt)
        return task.id
    }

    private fun table(): List<GameTableRow> = runBlocking { store.observeTable().first() }

    private fun rowNamed(name: String): GameTableRow = assertNotNull(table().firstOrNull { it.gameName == name }, "no row for $name")

    // ------------------------------------------------------------- the rows

    @Test
    fun `a game with nothing written in it still has all five columns`() =
        runBlocking<Unit> {
            addGame("Harmonies")

            val row = rowNamed("Harmonies")

            assertEquals(CellColumnType.entries, row.cells.map { it.columnType })
            row.cells.forEach { cell ->
                assertTrue(cell.isEmpty, "${cell.columnType} was not empty")
                assertNull(cell.cellId, "${cell.columnType} had a cell nobody opened")
            }
        }

    @Test
    fun `reading the table opens no cells`() =
        runBlocking<Unit> {
            // A read that wrote five rows per game would be a read that changed
            // the thing it was reading.
            addGame("Harmonies")

            repeat(3) { table() }

            assertEquals(0, CommittedSchema.countRowsOf(directory.databaseFile, "game_cells"))
        }

    @Test
    fun `the columns are in table order whatever order the cells were opened in`() =
        runBlocking<Unit> {
            val game = addGame("Harmonies")
            // Opened back to front, and the alphabet SQLite would sort them by
            // puts BOARD first, so neither can be what decides the order.
            listOf(CellColumnType.NOTES, CellColumnType.BOARD, CellColumnType.THREE_D).forEach {
                addCell(game.id, it)
            }

            assertEquals(
                listOf(
                    CellColumnType.THREE_D,
                    CellColumnType.CARD,
                    CellColumnType.BOARD,
                    CellColumnType.SPECIAL,
                    CellColumnType.NOTES,
                ),
                rowNamed("Harmonies").cells.map { it.columnType },
            )
        }

    @Test
    fun `an opened but empty cell is known by its identity and still reads as empty`() =
        runBlocking<Unit> {
            val game = addGame("Harmonies")
            val cellId = addCell(game.id, CellColumnType.NOTES)

            val cell = rowNamed("Harmonies").cell(CellColumnType.NOTES)

            assertEquals(cellId, cell.cellId)
            assertTrue(cell.isEmpty)
        }

    // -------------------------------------------------------- what is inside

    @Test
    fun `a cell previews its pieces in the order they sit in it`() =
        runBlocking<Unit> {
            val game = addGame("Harmonies")
            val cellId = addCell(game.id, CellColumnType.THREE_D)
            addText(cellId, "40 gri token", orderIndex = 0)
            addText(cellId, "kutu ölçüsü 30×30", orderIndex = 1)

            assertEquals(
                listOf("40 gri token", "kutu ölçüsü 30×30"),
                rowNamed("Harmonies").cell(CellColumnType.THREE_D).segments.map { it.text },
            )
        }

    @Test
    fun `a task piece previews the task's name`() =
        runBlocking<Unit> {
            val game = addGame("Harmonies")
            val cellId = addCell(game.id, CellColumnType.THREE_D)
            addText(cellId, "önce", orderIndex = 0)
            val taskId = addTask(cellId, "Gri token")

            val segments = rowNamed("Harmonies").cell(CellColumnType.THREE_D).segments

            assertEquals(listOf("önce", "Gri token"), segments.map { it.text })
            assertEquals(listOf(null, taskId), segments.map { it.taskId })
            assertTrue(segments.last().isTask)
        }

    @Test
    fun `a finished task stays in its cell`() =
        runBlocking<Unit> {
            // PLAN 5.6: finishing a task takes it out of the active pool and
            // leaves it exactly where it was written.
            val game = addGame("Harmonies")
            val cellId = addCell(game.id, CellColumnType.THREE_D)
            val taskId = addTask(cellId, "Gri token")
            database.taskProgressDao().completeTask(taskId, StoppedClock(createdAt), ids)

            val segment = rowNamed("Harmonies").cell(CellColumnType.THREE_D).segments.single()

            assertEquals("Gri token", segment.text)
            assertTrue(segment.isCompletedTask, "a finished task was not marked as one")
        }

    @Test
    fun `a deleted task leaves the preview and the pieces around it stay`() =
        runBlocking<Unit> {
            val game = addGame("Harmonies")
            val cellId = addCell(game.id, CellColumnType.THREE_D)
            addText(cellId, "önce", orderIndex = 0)
            addTask(cellId, "Gri token").also { database.taskDao().softDelete(it, deletedAt) }
            addText(cellId, "sonra", orderIndex = 2)

            val segments = rowNamed("Harmonies").cell(CellColumnType.THREE_D).segments

            assertEquals(listOf("önce", "sonra"), segments.map { it.text })
            assertTrue(segments.none { it.isTask }, "a deleted task was previewed anyway")
        }

    @Test
    fun `one game's cells never show up in another game's row`() =
        runBlocking<Unit> {
            val first = addGame("Harmonies")
            val second = addGame("Wingspan")
            addText(addCell(first.id, CellColumnType.THREE_D), "gri", orderIndex = 0)
            addText(addCell(second.id, CellColumnType.CARD), "kuş kartları", orderIndex = 0)

            assertEquals(listOf("gri"), rowNamed("Harmonies").cell(CellColumnType.THREE_D).segments.map { it.text })
            assertTrue(rowNamed("Harmonies").cell(CellColumnType.CARD).isEmpty)
            assertEquals(
                listOf("kuş kartları"),
                rowNamed("Wingspan").cell(CellColumnType.CARD).segments.map { it.text },
            )
        }

    @Test
    fun `a task appears once however the cells are joined`() =
        runBlocking<Unit> {
            val game = addGame("Harmonies")
            val cellId = addCell(game.id, CellColumnType.THREE_D)
            addTask(cellId, "Gri token")

            assertEquals(1, rowNamed("Harmonies").cell(CellColumnType.THREE_D).segments.size)
        }

    // ----------------------------------------------------------- the views

    @Test
    fun `a deleted game is in none of the three views`() =
        runBlocking<Unit> {
            addGame("Harmonies")
            addGame("Silinmiş", deleted = true)
            addGame("Wingspan", isCompleted = true)

            val rows = table()

            GameTableView.entries.forEach { view ->
                assertTrue(
                    rows.filter(view::includes).none { it.gameName == "Silinmiş" },
                    "a deleted game showed up in $view",
                )
            }
            assertEquals(listOf("Harmonies", "Wingspan"), rows.map { it.gameName })
        }

    @Test
    fun `the views split the same rows without reading them again`() =
        runBlocking<Unit> {
            addGame("Harmonies")
            addGame("Wingspan", isCompleted = true)

            val rows = table()

            assertEquals(listOf("Harmonies"), rows.filter(GameTableView.ONGOING::includes).map { it.gameName })
            assertEquals(listOf("Wingspan"), rows.filter(GameTableView.COMPLETED::includes).map { it.gameName })
            assertEquals(listOf("Harmonies", "Wingspan"), rows.filter(GameTableView.ALL::includes).map { it.gameName })
        }

    @Test
    fun `a finished game keeps its cells and its tasks`() =
        runBlocking<Unit> {
            // PLAN 5.3: a finished game is not hidden, moved or emptied.
            val game = addGame("Wingspan", isCompleted = true)
            val cellId = addCell(game.id, CellColumnType.CARD)
            addText(cellId, "170 kart", orderIndex = 0)
            addTask(cellId, "Bird Cards", poolType = PoolType.CARD)

            val row = rowNamed("Wingspan")

            assertTrue(row.isCompleted)
            assertEquals(listOf("170 kart", "Bird Cards"), row.cell(CellColumnType.CARD).segments.map { it.text })
        }

    @Test
    fun `rows come back in a fixed order`() =
        runBlocking<Unit> {
            listOf("Wingspan", "Harmonies", "Azul").forEach { addGame(it) }

            assertEquals(listOf("Azul", "Harmonies", "Wingspan"), table().map { it.gameName })
            assertEquals(table().map { it.gameId }, table().map { it.gameId }, "two reads disagreed on the order")
        }

    // ------------------------------------------------------- the cost of it

    @Test
    fun `the table is read with a fixed number of queries however many games there are`() =
        runBlocking<Unit> {
            // A query per game, per cell or per segment would turn drawing the
            // table into a storm of round trips. The count below is what makes
            // that a rule rather than a hope.
            val counting =
                CountingGameTable(database.gameDao(), database.gameCellDao(), database.gameTableDao())
            repeat(2) { index ->
                val game = addGame("Oyun $index")
                CellColumnType.entries.forEach { columnType ->
                    addText(addCell(game.id, columnType), "metin $index", orderIndex = 0)
                }
            }
            counting.observeTable().first()
            val forTwoGames = counting.queries

            repeat(40) { index ->
                val game = addGame("Sonraki $index")
                CellColumnType.entries.forEach { columnType ->
                    addText(addCell(game.id, columnType), "metin $index", orderIndex = 0)
                }
            }
            counting.reset()
            val rows = counting.observeTable().first()

            assertEquals(42, rows.size)
            assertEquals(forTwoGames, counting.queries, "the number of queries grew with the number of games")
            assertEquals(3, counting.queries, "the table is meant to be three whole-table reads")
        }

    @Test
    fun `every read of the table is a read and never a write`() =
        runBlocking<Unit> {
            val game = addGame("Harmonies")
            addText(addCell(game.id, CellColumnType.THREE_D), "gri", orderIndex = 0)
            val before =
                listOf("games", "game_cells", "cell_segments", "tasks").associateWith {
                    CommittedSchema.countRowsOf(directory.databaseFile, it)
                }

            repeat(5) { table() }

            assertEquals(
                before,
                before.keys.associateWith { CommittedSchema.countRowsOf(directory.databaseFile, it) },
                "reading the table changed what was stored",
            )
            assertTrue(
                rowNamed("Harmonies").cell(CellColumnType.CARD).isEmpty,
                "a column nobody wrote in gained content by being read",
            )
        }
}
