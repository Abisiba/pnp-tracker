package dev.pnptracker.data.database

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.domain.games.GameCompletionSnapshot
import dev.pnptracker.domain.games.GameTaskSnapshot
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * What a half-finished game leaves behind, when the database itself gives up.
 *
 * PLAN 16 will not have a game left partly finished, and the only way to find
 * out whether one transaction really means that is to break it in the middle.
 * Each of these lets the write get as far as it can and then refuses one
 * statement, and then reads everything back through a connection that knows
 * nothing about the trap: the game's own mark, every task, every stage, and the
 * history.
 *
 * The counts are gathered with a frequency map rather than a set, because what
 * matters here is *how many* of a statement ran — a set would hide a second
 * write behind the first and report a transaction that stopped when it did not.
 */
class GameCompletionRollbackTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var driver: FailingSqliteDriver
    private lateinit var counter: CountingSqliteDriver
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        driver = FailingSqliteDriver()
        // Counted outside the trap, so what is measured is every statement the
        // transaction really got as far as running — the refused one included.
        counter = CountingSqliteDriver(driver)
        database = DatabaseFactory(driver = counter).open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        driver.disarm()
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val progress get() = database.taskProgressDao()
    private val clock = StoppedClock(updatedAt)

    // ------------------------------------------------------------- fixtures

    private class Fixture(
        val gameId: EntityId,
        val cells: MutableMap<CellColumnType, EntityId> = mutableMapOf(),
    )

    private suspend fun aGameCalled(): Fixture {
        val game = aGame(name = "Harmonies ${IdGenerator.Random.newId()}")
        database.gameDao().insert(game)
        return Fixture(game.id)
    }

    private suspend fun Fixture.writing(
        poolType: PoolType = PoolType.THREE_D,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        name: String = "Gri token",
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
        return task
    }

    /**
     * A game holding three open 3D tasks, two of them owing something.
     *
     * Enough to reach every statement a bulk completion writes: two settling
     * events, three task rows, and the game at the end of them.
     */
    private suspend fun aGameOfThree(): Pair<Fixture, List<TaskEntity>> {
        val fixture = aGameCalled()
        val tasks = (1..3).map { fixture.writing(name = "Token $it") }
        tasks.take(2).forEach {
            progress.reportFailure(IdGenerator.Random.newId(), it.id, quantity = 2, clock = StoppedClock(createdAt))
        }
        return fixture to tasks
    }

    /** Everything about the game a bulk completion is allowed to move. */
    private suspend fun stateOf(
        fixture: Fixture,
        tasks: List<TaskEntity>,
    ): List<Any?> {
        val game = assertNotNull(database.gameDao().gameByIdIncludingDeleted(fixture.gameId))
        return buildList {
            add(game.isManuallyCompleted)
            add(game.completedAt)
            add(game.updatedAt)
            tasks.forEach { task ->
                val row = assertNotNull(progress.taskById(task.id))
                add(listOf(row.isCompleted, row.completedAt, row.currentMissingQuantity, row.primaryBatchCompleted, row.updatedAt))
                add(progress.stagesOfTask(task.id))
                add(progress.progressEventsOfTask(task.id))
                add(database.taskColorDao().colorsOfTask(task.id))
            }
        }
    }

    private suspend fun finish(
        fixture: Fixture,
        clock: Clock = this.clock,
        idGenerator: IdGenerator = IdGenerator.Random,
        expected: GameCompletionSnapshot? = null,
    ) = progress.completeGame(fixture.gameId, clock, idGenerator, expected)

    // -------------------------------------------------------------- the traps

    @Test
    fun `a second task that will not go in takes the first back out with it`() =
        runBlocking<Unit> {
            val (fixture, tasks) = aGameOfThree()
            val before = stateOf(fixture, tasks)
            driver.failOn(occurrence = 2) { it.contains("UPDATE tasks SET") }

            assertFailsWith<SQLiteException> { finish(fixture) }

            driver.disarm()
            assertEquals(before, stateOf(fixture, tasks), "a half finished game was committed")
        }

    @Test
    fun `the last task refusing takes the whole game back out with it`() =
        runBlocking<Unit> {
            val (fixture, tasks) = aGameOfThree()
            val before = stateOf(fixture, tasks)
            driver.failOn(occurrence = 3) { it.contains("UPDATE tasks SET") }

            assertFailsWith<SQLiteException> { finish(fixture) }

            driver.disarm()
            assertEquals(before, stateOf(fixture, tasks))
        }

    @Test
    fun `a stage that will not go in takes the tasks back out with it`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val card = fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE, name = "Kartlar")
            val token = fixture.writing(name = "Gri token")
            progress.setStageQuantities(card.id, mapOf(ProductionStage.PRINT to 8), StoppedClock(createdAt))
            val tasks = listOf(card, token)
            val before = stateOf(fixture, tasks)
            driver.failOn(occurrence = 2) { it.contains("UPDATE task_stages") }

            assertFailsWith<SQLiteException> { finish(fixture) }

            driver.disarm()
            assertEquals(before, stateOf(fixture, tasks))
            assertEquals(listOf(8, 0, 0), progress.stagesOfTask(card.id).map { it.completedQuantity })
        }

    @Test
    fun `a settling event that will not go in leaves every task unfinished`() =
        runBlocking<Unit> {
            val (fixture, tasks) = aGameOfThree()
            val before = stateOf(fixture, tasks)
            driver.failOn(occurrence = 2) { it.contains("INSERT") && it.contains("progress_events") }

            assertFailsWith<SQLiteException> { finish(fixture) }

            driver.disarm()
            assertEquals(before, stateOf(fixture, tasks), "a debt was settled by a save that fell over")
        }

    @Test
    fun `a game row that will not take the mark takes all its work back out`() =
        runBlocking<Unit> {
            val (fixture, tasks) = aGameOfThree()
            val before = stateOf(fixture, tasks)
            // Everything under the game is written by now; what will not go is
            // the row that says the game is finished.
            driver.failOn { it.contains("UPDATE games") }

            assertFailsWith<SQLiteException> { finish(fixture) }

            driver.disarm()
            assertEquals(before, stateOf(fixture, tasks), "the work outlived the game that was meant to carry it")
        }

    @Test
    fun `a game row that will not be reopened takes the shortage back out with it`() =
        runBlocking<Unit> {
            val (fixture, tasks) = aGameOfThree()
            finish(fixture)
            val before = stateOf(fixture, tasks)
            driver.failOn { it.contains("UPDATE games") }

            assertFailsWith<SQLiteException> {
                progress.reportFailure(IdGenerator.Random.newId(), tasks.first().id, quantity = 4, clock = clock)
            }

            driver.disarm()
            assertEquals(before, stateOf(fixture, tasks), "a task was reopened inside a game that stayed finished")
        }

    @Test
    fun `a settling name already taken takes the whole game down`() =
        runBlocking<Unit> {
            val (fixture, tasks) = aGameOfThree()
            // Every settling event handed the name of an event that is already
            // there. The first one clashes, which is not a retry of anything.
            val taken = progress.progressEventsOfTask(tasks.first().id).single().id
            val before = stateOf(fixture, tasks)

            assertFailsWith<IllegalStateException> { finish(fixture, idGenerator = IdGenerator { taken }) }

            assertEquals(before, stateOf(fixture, tasks))
        }

    @Test
    fun `a stale answer writes nothing whatever it would have written`() =
        runBlocking<Unit> {
            val (fixture, tasks) = aGameOfThree()
            val stale =
                GameCompletionSnapshot(
                    isGameCompleted = false,
                    tasks = tasks.map { GameTaskSnapshot(it.id, isCompleted = false, currentMissingQuantity = 0, requiredQuantity = 20) },
                )
            val before = stateOf(fixture, tasks)

            assertFailsWith<Exception> { finish(fixture, expected = stale) }

            assertEquals(before, stateOf(fixture, tasks))
        }

    @Test
    fun `a save that fell over ran the writes it got to and left none of them`() =
        runBlocking<Unit> {
            val (fixture, tasks) = aGameOfThree()
            driver.failOn(occurrence = 2) { it.contains("UPDATE tasks SET") }

            counter.start()
            assertFailsWith<SQLiteException> { finish(fixture) }
            val ran = counter.stop().writes()

            driver.disarm()
            // Two settling events went in, two task rows were attempted and the
            // second is the one that would not go, and the game was never
            // reached. And none of it is there.
            assertEquals(2, ran["INSERT progress_events"], "the events were not written before the tasks: $ran")
            assertEquals(2, ran["UPDATE tasks"], "the transaction ran on past the statement that failed: $ran")
            assertEquals(null, ran["UPDATE games"], "the game was marked after a task had already failed")
            assertTrue(tasks.none { assertNotNull(progress.taskById(it.id)).isCompleted })
        }

    /** Every write that ran, counted by kind. A frequency map, never a set. */
    private fun List<String>.writes(): Map<String, Int> =
        filterNot { "room_table_modification" in it.lowercase() }
            .groupingBy { statement ->
                val head = statement.trimStart().uppercase()
                when {
                    head.startsWith("UPDATE") && "TASK_STAGES" in head -> "UPDATE task_stages"
                    head.startsWith("UPDATE") && "GAMES" in head -> "UPDATE games"
                    head.startsWith("UPDATE") && "TASKS" in head -> "UPDATE tasks"
                    head.startsWith("INSERT") && "PROGRESS_EVENTS" in head -> "INSERT progress_events"
                    head.startsWith("INSERT") -> "INSERT"
                    else -> "read"
                }
            }.eachCount()
}
