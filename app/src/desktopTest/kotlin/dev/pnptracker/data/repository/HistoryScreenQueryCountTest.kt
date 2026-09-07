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
import dev.pnptracker.data.database.entity.HistoryEventEntity
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
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
import kotlin.time.Instant

/**
 * What opening the history costs, counted at the driver.
 *
 * The number that matters is not how large it is but whether it grows. A history
 * of a hundred lines across a dozen games must cost the same reads as a history
 * of one, because PLAN 16 rules out a read per record and PLAN 18 (Faz 3
 * testleri) asks the application to stay usable at a thousand tasks — each of
 * which leaves several lines behind it.
 *
 * The shape being ruled out is a real temptation on this screen. A progress
 * event carries no game, so "which game was this in?" has an obvious per-row
 * answer; asked per row it would be one query per shortage ever recorded. It is
 * answered inside the one statement instead, and this is what says so.
 *
 * Counted as a frequency map rather than a set: two runs of one query and one
 * run of it are the same set and different maps, and the difference between them
 * is exactly what an N+1 looks like.
 */
class HistoryScreenQueryCountTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false
    private val ids = IdGenerator.Random

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

    /** Which of the history's two reads one statement is. */
    private fun nameOf(sql: String): String {
        val text = sql.lowercase()
        return when {
            "from progress_events" in text -> "progress_history"
            "from history_events" in text -> "history_events"
            else -> "unexpected(" + sql.trim().take(48) + ")"
        }
    }

    /**
     * How many times each read really ran.
     *
     * Room keeps its own bookkeeping on the same connection — a trigger per
     * watched table when a Flow is first collected, and a poll of its change log
     * afterwards. Neither is the screen asking anything, and both would be
     * counted because they name the tables they watch.
     */
    private fun tally(recorded: List<String>): Map<String, Int> =
        recorded
            .filterNot { "room_table_modification" in it.lowercase() }
            .filter { it.trimStart().uppercase().startsWith("SELECT") }
            .groupingBy(::nameOf)
            .eachCount()

    private suspend fun aHistoryOf(
        games: Int,
        tasksPerGame: Int,
        shortagesPerTask: Int,
    ) {
        val progress = TaskProgressStore(database.taskProgressDao(), StoppedClock(updatedAt))
        repeat(games) { gameIndex ->
            val game = aGame(name = "Oyun $gameIndex")
            val cell = aCell(gameId = game.id, columnType = CellColumnType.of(PoolType.THREE_D))
            database.gameDao().insert(game)
            database.gameCellDao().insert(cell)
            repeat(tasksPerGame) { taskIndex ->
                val task =
                    aTask(
                        name = "Görev $gameIndex-$taskIndex",
                        poolType = PoolType.THREE_D,
                        trackingMode = TrackingMode.THREE_D_BATCH,
                        requiredQuantity = 40,
                    )
                database.taskDao().addTaskToCell(task, cell.id, ids.newId(), createdAt)
                repeat(shortagesPerTask) {
                    progress.reportFailure(eventId = ids.newId(), taskId = task.id, quantity = 1)
                }
                appendCompletion(game.id, task.id)
            }
        }
    }

    private suspend fun appendCompletion(
        gameId: EntityId,
        taskId: EntityId,
    ) {
        database.taskDao().appendHistoryEvent(
            HistoryEventEntity(
                id = ids.newId(),
                kind = HistoryEventKind.TASK_COMPLETED,
                occurredAt = Instant.fromEpochMilliseconds(9_000),
                gameId = gameId,
                taskId = taskId,
            ),
        )
    }

    private fun countingRead(): Map<String, Int> {
        driver.start()
        runBlocking { HistoryStore(database.historyDao()).observeHistory().first() }
        return tally(driver.stop())
    }

    @Test
    fun `opening the history is two reads`() =
        runBlocking<Unit> {
            aHistoryOf(games = 1, tasksPerGame = 1, shortagesPerTask = 1)

            assertEquals(mapOf("history_events" to 1, "progress_history" to 1), countingRead())
        }

    @Test
    fun `a much larger history is the same two reads`() =
        runBlocking<Unit> {
            aHistoryOf(games = 6, tasksPerGame = 7, shortagesPerTask = 3)
            val log = HistoryStore(database.historyDao()).observeHistory().first()

            val counted = countingRead()

            // 42 completions and 126 shortages: enough that a read per line, per
            // task or per game would be unmistakable in the count below.
            assertEquals(168, log.entries.size)
            assertEquals(6, log.games.size)
            assertEquals(mapOf("history_events" to 1, "progress_history" to 1), counted)
        }
}
