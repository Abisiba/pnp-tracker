package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.insertGameAndCell
import dev.pnptracker.data.database.updatedAt
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
import kotlin.test.assertTrue

/**
 * What keeping a history costs at the driver.
 *
 * Writing one line per thing that happened is the work, and the work is allowed
 * to grow: finishing forty-two tasks writes forty-two lines. What is not allowed
 * to grow is the number of *questions* asked to decide any of it. PLAN 16 rules
 * out a read per row, and PLAN 18 (Faz 3 testleri) will ask the application to
 * hold up at a thousand tasks; a lookup of "which game is this task in" done
 * once per task inside a bulk finish is exactly the shape that fails both.
 *
 * Every count here is a frequency map. A set would collapse forty-two runs of
 * one statement into one and report a cost nobody paid.
 */
class HistoryQueryCountTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false
    private val ids = IdGenerator.Random
    private val clock = StoppedClock(updatedAt)

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

    /** Statements the application really ran, named by verb and table. */
    private fun tally(recorded: List<String>): Map<String, Int> =
        recorded
            .filterNot { "room_table_modification" in it.lowercase() }
            .filterNot { statement -> statement.trimStart().uppercase().substringBefore(' ') in TRANSACTION_CONTROL }
            .filterNot { statement ->
                val head = statement.trimStart().uppercase()
                head.startsWith("PRAGMA") || "CHANGES()" in head || "LAST_INSERT_ROWID()" in head
            }.groupingBy { statement ->
                val head = statement.trimStart().uppercase()
                val verb = head.substringBefore(' ')
                val table =
                    when {
                        "HISTORY_EVENTS" in head -> "history_events"
                        "PROGRESS_EVENTS" in head -> "progress_events"
                        "TASK_STAGES" in head -> "task_stages"
                        "TASK_COLORS" in head -> "task_colors"
                        "TASKS" in head -> "tasks"
                        "GAMES" in head -> "games"
                        "CELL_SEGMENTS" in head -> "cell_segments"
                        else -> "other"
                    }
                "$verb $table"
            }.eachCount()

    private inline fun <T> counting(block: () -> T): Pair<T, Map<String, Int>> {
        driver.start()
        val outcome = block()
        return outcome to tally(driver.stop())
    }

    private suspend fun aGameOf(
        tasks: Int,
        name: String,
        poolType: PoolType = PoolType.THREE_D,
        quantity: Int? = 40,
    ): Pair<EntityId, List<EntityId>> {
        val cell = insertGameAndCell(database, gameName = name, columnType = CellColumnType.of(poolType))
        val made =
            (0 until tasks).map { index ->
                val task =
                    aTask(
                        name = "Görev $index",
                        poolType = poolType,
                        trackingMode =
                            when (poolType) {
                                PoolType.THREE_D -> TrackingMode.THREE_D_BATCH
                                PoolType.CARD, PoolType.BOARD -> TrackingMode.PIPELINE
                                PoolType.SPECIAL -> TrackingMode.COUNTED
                            },
                        requiredQuantity = quantity,
                    )
                database.taskDao().addTaskToCell(task, cell.id, ids.newId(), createdAt)
                task.id
            }
        return cell.gameId to made
    }

    private fun readsIn(counted: Map<String, Int>) = counted.filterKeys { it.startsWith("SELECT") }

    // ------------------------------------------------------------- one at a time

    @Test
    fun `finishing one task asks the same questions whatever else is in the library`() =
        runBlocking<Unit> {
            val (_, alone) = aGameOf(1, "Yalnız", PoolType.SPECIAL, quantity = null)
            val (_, small) = counting { runBlocking { progress.completeTask(alone.single(), clock, ids) } }

            repeat(42) { index -> aGameOf(1, "Kalabalık $index", PoolType.SPECIAL, quantity = null) }
            val (_, crowded) = aGameOf(1, "Sonuncu", PoolType.SPECIAL, quantity = null)
            val (_, large) = counting { runBlocking { progress.completeTask(crowded.single(), clock, ids) } }

            assertEquals(readsIn(small), readsIn(large), "finishing one task cost more in a bigger library")
            assertEquals(1, small["INSERT history_events"], "one finish was not one line")
            assertEquals(1, small["SELECT cell_segments"], "the game was looked up more than once")
        }

    @Test
    fun `moving a pipeline step asks nothing per task around it`() =
        runBlocking<Unit> {
            val (_, alone) = aGameOf(1, "Yalnız", PoolType.CARD, quantity = 60)
            val (_, small) =
                counting { runBlocking { progress.setStageQuantity(alone.single(), ProductionStage.PRINT, 10, clock) } }

            repeat(42) { index -> aGameOf(1, "Kalabalık $index", PoolType.CARD, quantity = 60) }
            val (_, crowded) = aGameOf(1, "Sonuncu", PoolType.CARD, quantity = 60)
            val (_, large) =
                counting { runBlocking { progress.setStageQuantity(crowded.single(), ProductionStage.PRINT, 10, clock) } }

            assertEquals(readsIn(small), readsIn(large))
            assertEquals(1, small["INSERT history_events"])
        }

    @Test
    fun `deleting one task costs one look and one line`() =
        runBlocking<Unit> {
            val (_, tasks) = aGameOf(1, "Yalnız")

            val (_, counted) = counting { runBlocking { database.taskDao().softDelete(tasks.single(), updatedAt) } }

            assertEquals(1, counted["SELECT cell_segments"], "the game was looked up more than once")
            assertEquals(1, counted["UPDATE tasks"])
            assertEquals(1, counted["INSERT history_events"])
        }

    @Test
    fun `deleting one game costs no look at all`() =
        runBlocking<Unit> {
            val (gameId, _) = aGameOf(1, "Yalnız")

            val (_, counted) = counting { runBlocking { database.gameDao().softDelete(gameId, updatedAt) } }

            // The game is the subject, so there is nothing to look up.
            assertEquals(emptyMap(), readsIn(counted), "removing a game asked a question it already knew: $counted")
            assertEquals(1, counted["INSERT history_events"])
        }

    // ----------------------------------------------------------------- in bulk

    @Test
    fun `finishing a game of forty two asks what a game of one asks`() =
        runBlocking<Unit> {
            val (small, oneTask) = aGameOf(1, "Bir", PoolType.SPECIAL, quantity = null)
            val (_, smallCost) = counting { runBlocking { progress.completeGame(small, clock, ids) } }
            assertEquals(1, oneTask.size)

            val (large, _) = aGameOf(42, "Kırk iki", PoolType.SPECIAL, quantity = null)
            val (_, largeCost) = counting { runBlocking { progress.completeGame(large, clock, ids) } }

            assertEquals(readsIn(smallCost), readsIn(largeCost), "the questions grew with the game")
            // No lookup at all: the game being finished is the game every line is
            // filed under, so nothing asks which game a task is in.
            assertEquals(null, largeCost["SELECT cell_segments"], "a game lookup was made per task: $largeCost")
            assertEquals(42, largeCost["INSERT history_events"], "a finished task went unrecorded")
            assertEquals(42, largeCost["UPDATE tasks"])
        }

    @Test
    fun `a library of a thousand finishes a game no dearer than a library of one`() =
        runBlocking<Unit> {
            val (first, _) = aGameOf(42, "İlk", PoolType.SPECIAL, quantity = null)
            val (_, fortyTwo) = counting { runBlocking { progress.completeGame(first, clock, ids) } }

            repeat(24) { index -> aGameOf(40, "Kütüphane $index", PoolType.SPECIAL, quantity = null) }
            val (last, _) = aGameOf(42, "Son", PoolType.SPECIAL, quantity = null)
            val (_, thousandStrong) = counting { runBlocking { progress.completeGame(last, clock, ids) } }

            assertEquals(fortyTwo, thousandStrong, "the cost changed with the size of the library")
            assertTrue(database.taskDao().allTasksIncludingDeleted().size > 1_000)
        }

    // -------------------------------------------------------- what is not touched

    @Test
    fun `nothing reads the history while a task is being worked on`() =
        runBlocking<Unit> {
            val (gameId, tasks) = aGameOf(3, "Harmonies", PoolType.CARD, quantity = 4)

            val (_, counted) =
                counting {
                    runBlocking {
                        progress.setStageQuantity(tasks.first(), ProductionStage.PRINT, 2, clock)
                        progress.reportFailure(ids.newId(), tasks.first(), 1, clock)
                        progress.resolveShortage(ids.newId(), tasks.first(), 1, clock)
                        progress.completeGame(gameId, clock, ids)
                    }
                }

            assertEquals(
                null,
                counted["SELECT history_events"],
                "the history was read to decide something: $counted",
            )
        }

    @Test
    fun `the only thing ever done to the history is appending to it`() =
        runBlocking<Unit> {
            val (gameId, tasks) = aGameOf(2, "Harmonies", PoolType.CARD, quantity = 4)

            val (_, counted) =
                counting {
                    runBlocking {
                        progress.setStageQuantity(tasks.first(), ProductionStage.PRINT, 2, clock)
                        progress.completeGame(gameId, clock, ids)
                        database.taskDao().softDelete(tasks.first(), updatedAt)
                        database.gameDao().softDelete(gameId, updatedAt)
                    }
                }

            assertEquals(
                listOf("INSERT history_events"),
                counted.keys.filter { "history_events" in it },
                "something other than an append reached the history: $counted",
            )
        }

    @Test
    fun `converting a task to text stays one look and one line`() =
        runBlocking<Unit> {
            val (_, tasks) = aGameOf(1, "Harmonies")
            val taskId = tasks.single()

            val (_, counted) =
                counting {
                    runBlocking {
                        database.taskEditDao().convertTaskToText(taskId, clock, ids)
                    }
                }

            assertEquals(1, counted["INSERT history_events"])
            assertEquals(1, counted["UPDATE tasks"], "more than the converted task was written: $counted")
            assertEquals(null, counted["DELETE progress_events"], "the shortages were deleted after all: $counted")
            assertEquals(null, counted["DELETE task_stages"], "the pipeline was deleted after all: $counted")
            assertEquals(null, counted["DELETE task_colors"], "the colours were deleted after all: $counted")
        }

    private companion object {
        val TRANSACTION_CONTROL = setOf("BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE")
    }
}
