package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What finishing a whole game costs, counted at the driver.
 *
 * The writes are allowed to grow: PLAN 12.9 finishes every task and every stage,
 * so a game of forty two tasks writes forty two task rows and that is the work
 * itself. What may not grow is the number of questions asked to decide — a read
 * per task is the shape PLAN 16 rules out, and it is the shape a bulk completion
 * built out of forty two single completions would have.
 *
 * Everything is counted with a frequency map. A set would collapse forty two
 * runs of one statement into one and report a cost that was never paid.
 */
class GameCompletionQueryCountTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = driver).open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val progress get() = database.taskProgressDao()

    /** A game of [tasks] open 3D tasks, ready to be finished all at once. */
    private suspend fun aGameOf(tasks: Int): EntityId {
        val game = aGame(name = "Harmonies ${IdGenerator.Random.newId()}")
        val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        repeat(tasks) { at ->
            val task: TaskEntity =
                aTask(
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    name = "Token $at",
                    requiredQuantity = 20,
                )
            database.taskDao().addTaskToCell(task, cell.id, IdGenerator.Random.newId(), createdAt)
        }
        return game.id
    }

    /** A game of [tasks] open card tasks, each with a pipeline of its own. */
    private suspend fun aGameOfCards(tasks: Int): EntityId {
        val game = aGame(name = "Kartlar ${IdGenerator.Random.newId()}")
        val cell = aCell(gameId = game.id, columnType = CellColumnType.CARD)
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        repeat(tasks) { at ->
            val task =
                aTask(
                    poolType = PoolType.CARD,
                    trackingMode = TrackingMode.PIPELINE,
                    name = "Deste $at",
                    requiredQuantity = 20,
                )
            database.taskDao().addTaskToCell(task, cell.id, IdGenerator.Random.newId(), createdAt)
        }
        return game.id
    }

    /**
     * What was really run, by kind.
     *
     * Room's own change log is left out — it is the database's bookkeeping and
     * not a question the transaction asked — and so are the words that begin and
     * end a transaction, which say nothing about its shape.
     */
    private fun ran(recorded: List<String>): Map<String, Int> =
        recorded
            .filterNot { "room_table_modification" in it.lowercase() }
            .map { it.trimStart().uppercase() }
            .filterNot { it.startsWith("BEGIN") || it.startsWith("COMMIT") || it.startsWith("END") }
            .filterNot { it.startsWith("ROLLBACK") || it.startsWith("SAVEPOINT") || it.startsWith("RELEASE") }
            .filterNot { it.startsWith("PRAGMA") }
            .groupingBy { statement ->
                when {
                    statement.startsWith("SELECT") && "FROM GAMES" in statement -> "SELECT games"
                    statement.startsWith("SELECT") && "FROM TASK_STAGES" in statement -> "SELECT task_stages"
                    statement.startsWith("SELECT") && "FROM PROGRESS_EVENTS" in statement -> "SELECT progress_events"
                    statement.startsWith("SELECT") && "FROM TASKS" in statement -> "SELECT tasks"
                    // What a write hands back, not a question anybody asked:
                    // Room reads these to learn how many rows its own statement
                    // moved. They belong to the write and grow with it.
                    "CHANGES()" in statement || "LAST_INSERT_ROWID()" in statement -> "write result"
                    statement.startsWith("SELECT") -> "SELECT other"
                    statement.startsWith("UPDATE") && "TASK_STAGES" in statement -> "UPDATE task_stages"
                    statement.startsWith("UPDATE") && "GAMES" in statement -> "UPDATE games"
                    statement.startsWith("UPDATE") -> "UPDATE tasks"
                    statement.startsWith("INSERT") -> "INSERT"
                    else -> "other"
                }
            }.eachCount()

    /** Every question a transaction asked, whatever table it asked it of. */
    private fun decisions(counted: Map<String, Int>): Map<String, Int> = counted.filterKeys { it.startsWith("SELECT") }

    private suspend fun finish(gameId: EntityId): Map<String, Int> {
        driver.start()
        progress.completeGame(gameId, StoppedClock(updatedAt), IdGenerator.Random)
        return ran(driver.stop())
    }

    @Test
    fun `finishing a game asks the same questions of one task and of forty two`() =
        runBlocking<Unit> {
            val small = finish(aGameOf(1))
            val large = finish(aGameOf(42))

            assertEquals(
                decisions(small),
                decisions(large),
                "the cost of deciding grew with the game: $small then $large",
            )
        }

    @Test
    fun `deciding costs one read of the game, one of its tasks and one of their stages`() =
        runBlocking<Unit> {
            val counted = finish(aGameOf(42))

            // Twice each: once to decide, once to check what was written still
            // adds up. Both are whole-game reads, and neither is per task.
            assertEquals(2, counted["SELECT games"], "the game was read per task: $counted")
            assertEquals(2, counted["SELECT tasks"], "the tasks were read per task: $counted")
            assertEquals(2, counted["SELECT task_stages"], "the stages were read per task: $counted")
            assertEquals(1, counted["SELECT progress_events"], "the history was read per task: $counted")
        }

    @Test
    fun `the writes are the work, and grow with it`() =
        runBlocking<Unit> {
            val counted = finish(aGameOf(42))

            assertEquals(42, counted["UPDATE tasks"], "a task was left unfinished: $counted")
            assertEquals(1, counted["UPDATE games"], "the game was marked more than once: $counted")
            assertEquals(null, counted["INSERT"], "a game with nothing owed wrote an event")
        }

    @Test
    fun `a game of forty two owing tasks settles each debt once`() =
        runBlocking<Unit> {
            val gameId = aGameOf(42)
            progress.workableTasksOfGame(gameId).forEach {
                progress.reportFailure(IdGenerator.Random.newId(), it.id, quantity = 2, clock = StoppedClock(createdAt))
            }

            val counted = finish(gameId)

            assertEquals(42, counted["INSERT"], "the debts were not settled one event each: $counted")
            assertEquals(42, counted["UPDATE tasks"])
            assertTrue(decisions(counted).values.all { it <= 2 }, "a debt cost a question of its own: $counted")
        }

    @Test
    fun `asking about a game costs the same three reads whatever is in it`() =
        runBlocking<Unit> {
            val small = aGameOf(1)
            val large = aGameOf(42)

            driver.start()
            progress.gameCompletionSnapshot(small)
            val one = ran(driver.stop())
            driver.start()
            progress.gameCompletionSnapshot(large)
            val many = ran(driver.stop())

            assertEquals(one, many, "asking about a game grew with the game: $one then $many")
            assertEquals(1, many["SELECT games"], "the game was read more than once: $many")
            assertEquals(1, many["SELECT tasks"], "the tasks were read per task: $many")
            assertEquals(1, many["SELECT task_stages"], "the stages were read per task: $many")
            assertTrue(many.keys.none { it.startsWith("UPDATE") || it.startsWith("INSERT") }, "asking wrote something: $many")
        }

    @Test
    fun `asking about a game of pipelines costs no more than asking about one task`() =
        runBlocking<Unit> {
            // The stages are what was added to the picture, so this is the read
            // that could have become one per task and did not.
            val gameId = aGameOfCards(42)

            driver.start()
            val snapshot = progress.gameCompletionSnapshot(gameId)
            val counted = ran(driver.stop())

            assertEquals(42, snapshot?.tasks?.size)
            assertEquals(3 * 42, snapshot?.tasks?.sumOf { it.stages.size }, "the pipelines were not read")
            assertEquals(1, counted["SELECT task_stages"], "a pipeline cost a query of its own: $counted")
            assertEquals(1, counted["SELECT tasks"], "$counted")
            assertEquals(1, counted["SELECT games"], "$counted")
        }

    @Test
    fun `a game already finished costs one read and no writes at all`() =
        runBlocking<Unit> {
            val gameId = aGameOf(42)
            progress.completeGame(gameId, StoppedClock(updatedAt), IdGenerator.Random)

            val counted = finish(gameId)

            assertEquals(null, counted["UPDATE games"], "a no-op wrote the game: $counted")
            assertEquals(null, counted["UPDATE tasks"], "a no-op wrote a task: $counted")
            assertEquals(null, counted["INSERT"], "a no-op wrote an event: $counted")
        }

    @Test
    fun `reporting a shortage inside a finished game costs one more read and one more write`() =
        runBlocking<Unit> {
            val gameId = aGameOf(2)
            progress.completeGame(gameId, StoppedClock(updatedAt), IdGenerator.Random)
            val task = progress.workableTasksOfGame(gameId).first()

            driver.start()
            progress.reportFailure(IdGenerator.Random.newId(), task.id, quantity = 2, clock = StoppedClock(createdAt))
            val counted = ran(driver.stop())

            assertEquals(1, counted["UPDATE games"], "the game was not reopened with the task: $counted")
            assertEquals(1, counted["UPDATE tasks"], "more than the reported task was written: $counted")
            assertEquals(1, counted["INSERT"], "the report was not one event: $counted")
        }
}
