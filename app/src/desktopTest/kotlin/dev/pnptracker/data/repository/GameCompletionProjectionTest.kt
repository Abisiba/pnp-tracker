package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.updatedAt
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the rest of the application sees after a game is finished.
 *
 * Finishing a game writes to three tables, and every screen reads its own view
 * of them. So the question here is not what was written but what is now
 * *reflected*: which view the row appears in, which pools its tasks are in, and
 * whether a task in several colours is still one task. PLAN 12.10 makes the
 * pools a reflection and never a store of their own — so a bulk completion that
 * left a pool disagreeing with the table would be the pool telling a lie nothing
 * could correct.
 */
class GameCompletionProjectionTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var pools: PoolStore
    private lateinit var table: GameTableStore
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        pools = PoolStore(database.poolDao())
        table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao())
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val progress get() = database.taskProgressDao()

    private class Fixture(
        val gameId: EntityId,
        val cells: MutableMap<CellColumnType, EntityId> = mutableMapOf(),
    )

    private suspend fun aGameCalled(name: String): Fixture {
        val game = aGame(name = name)
        database.gameDao().insert(game)
        return Fixture(game.id)
    }

    private suspend fun Fixture.writing(
        poolType: PoolType = PoolType.THREE_D,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        name: String = "Token",
        colors: List<EntityId> = emptyList(),
    ): TaskEntity {
        val columnType = CellColumnType.of(poolType)
        val cellId =
            cells.getOrPut(columnType) {
                val cell = aCell(gameId = gameId, columnType = columnType)
                database.gameCellDao().insert(cell)
                cell.id
            }
        val task = aTask(poolType = poolType, trackingMode = trackingMode, name = name, requiredQuantity = 20)
        database.taskDao().addTaskToCell(task, cellId, IdGenerator.Random.newId(), createdAt)
        colors.forEachIndexed { slot, colorId -> database.taskColorDao().insert(TaskColorEntity(task.id, colorId, slot)) }
        return task
    }

    private suspend fun colorId(name: String): EntityId = assertNotNull(database.colorDao().resolve(name)).id

    private suspend fun rows(): List<GameTableRow> = table.observeTable().first()

    private suspend fun namesIn(view: GameTableView): List<String> = rows().filter(view::includes).map { it.gameName }

    private suspend fun idsIn(poolType: PoolType): List<EntityId> =
        pools
            .observePool(poolType)
            .first()
            .tasks
            .map { it.taskId }

    private suspend fun finish(gameId: EntityId) = progress.completeGame(gameId, StoppedClock(updatedAt), IdGenerator.Random)

    @Test
    fun `a finished game leaves the ongoing view and stays in the other two`() =
        runBlocking<Unit> {
            val finished = aGameCalled("Harmonies")
            finished.writing()
            aGameCalled("Wingspan").writing()

            finish(finished.gameId)

            assertEquals(listOf("Wingspan"), namesIn(GameTableView.ONGOING))
            assertEquals(listOf("Harmonies"), namesIn(GameTableView.COMPLETED))
            assertEquals(listOf("Harmonies", "Wingspan"), namesIn(GameTableView.ALL))
        }

    @Test
    fun `the tasks a bulk completion finished leave their pools`() =
        runBlocking<Unit> {
            val fixture = aGameCalled("Harmonies")
            fixture.writing(poolType = PoolType.THREE_D)
            fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE, name = "Kartlar")
            fixture.writing(poolType = PoolType.BOARD, trackingMode = TrackingMode.PIPELINE, name = "Tahta")
            val elsewhere = aGameCalled("Wingspan").writing()

            finish(fixture.gameId)

            assertEquals(listOf(elsewhere.id), idsIn(PoolType.THREE_D), "another game's work left its pool")
            assertEquals(emptyList(), idsIn(PoolType.CARD))
            assertEquals(emptyList(), idsIn(PoolType.BOARD))
        }

    @Test
    fun `the tasks stay in their cells, ticked rather than taken away`() =
        runBlocking<Unit> {
            // PLAN 5.6 and 12.9: finishing is not archiving and not deleting.
            val fixture = aGameCalled("Harmonies")
            val task = fixture.writing(name = "Gri token")

            finish(fixture.gameId)

            val cell = rows().single().cell(CellColumnType.THREE_D)
            val piece = cell.tasks.single()
            assertEquals(task.id, piece.taskId)
            assertEquals("Gri token", piece.text, "the name changed when the game was finished")
            assertTrue(piece.isCompletedTask, "the task is not shown as done")
        }

    @Test
    fun `a task the user opened by hand stays in its pool though the game is finished`() =
        runBlocking<Unit> {
            // PLAN 3.5 and scenario 5a: a finished game may hold active tasks.
            val fixture = aGameCalled("Harmonies")
            val task = fixture.writing()
            finish(fixture.gameId)
            assertEquals(emptyList(), idsIn(PoolType.THREE_D))

            progress.reopenTask(task.id, StoppedClock(updatedAt))

            assertEquals(listOf(task.id), idsIn(PoolType.THREE_D))
            assertEquals(listOf("Harmonies"), namesIn(GameTableView.COMPLETED), "the game left its view too")
        }

    @Test
    fun `a shortage brings the task back to its pool and the game back to ongoing`() =
        runBlocking<Unit> {
            val fixture = aGameCalled("Harmonies")
            val reported = fixture.writing(name = "Gri token")
            val other = fixture.writing(name = "Mavi token")
            finish(fixture.gameId)

            progress.reportFailure(IdGenerator.Random.newId(), reported.id, quantity = 2, clock = StoppedClock(updatedAt))

            assertEquals(listOf(reported.id), idsIn(PoolType.THREE_D), "only the reported task belongs back in the pool")
            assertEquals(listOf("Harmonies"), namesIn(GameTableView.ONGOING))
            assertEquals(emptyList(), namesIn(GameTableView.COMPLETED))
            assertTrue(assertNotNull(progress.taskById(other.id)).isCompleted, "another task was reopened")
        }

    @Test
    fun `a task made in several colours comes back to every one of its pools once`() =
        runBlocking<Unit> {
            // PLAN 6.3: one task, one write, and it is in every colour group it
            // belongs to — never twice in any of them.
            val fixture = aGameCalled("Harmonies")
            val colors = listOf("Gri", "Mavi", "Yeşil").map { colorId(it) }
            val task = fixture.writing(name = "Ev", colors = colors)
            finish(fixture.gameId)
            assertEquals(emptyList(), idsIn(PoolType.THREE_D))

            progress.reportFailure(IdGenerator.Random.newId(), task.id, quantity = 2, clock = StoppedClock(updatedAt))

            assertEquals(listOf(task.id), idsIn(PoolType.THREE_D), "one task came back as several")
            val snapshot = pools.observePool(PoolType.THREE_D).first()
            val groups =
                snapshot.tasks
                    .single()
                    .colors
                    .map { it.colorId }
            assertEquals(colors, groups, "the task came back to the wrong colour groups")
        }

    @Test
    fun `finishing one game leaves every other game exactly as it was`() =
        runBlocking<Unit> {
            val finished = aGameCalled("Harmonies")
            finished.writing()
            val untouched = aGameCalled("Wingspan")
            val theirs = untouched.writing()

            finish(finished.gameId)

            val row = assertNotNull(rows().firstOrNull { it.gameName == "Wingspan" })
            assertFalse(row.isCompleted)
            assertFalse(assertNotNull(progress.taskById(theirs.id)).isCompleted)
        }
}
