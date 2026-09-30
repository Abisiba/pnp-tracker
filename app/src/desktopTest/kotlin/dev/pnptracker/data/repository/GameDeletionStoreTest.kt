package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.activePoolTasks
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.domain.games.GameRestoreOutcome
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.history.HistoryChange
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
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
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Deleting a game and bringing it back, on a real database (PLAN 5.2, 12.15).
 *
 * What is checked is what the user relies on: a deleted game leaves the table
 * and the pools but nothing under it is erased, the history says so, and `Geri
 * al` brings back the very same row — identity, cells, tasks — with a line of its
 * own. Names are not unique, so a namesake is counted, never refused.
 */
class GameDeletionStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var setup: GameSetupStore
    private val clock = SteppingClock()
    private var realDatabaseExistedBefore = false

    /** A clock that moves on a minute each time it is read, so every act has its own moment. */
    private class SteppingClock : Clock {
        private var next = Instant.fromEpochMilliseconds(1_781_000_000_000)

        override fun now(): Instant = next.also { next = Instant.fromEpochMilliseconds(it.toEpochMilliseconds() + 60_000) }
    }

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        setup = GameSetupStore(database.gameDao(), database.gameCellDao(), IdGenerator.Random, clock)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun gameWithTask(name: String): Pair<EntityId, EntityId> {
        val task = insertGameCellAndTask(database, gameName = name) { aTask(poolType = PoolType.THREE_D, name = "Jeton") }
        val game = database.gameDao().activeGames().first { it.name == name }
        return game.id to task.id
    }

    private suspend fun inPool(): List<EntityId> =
        activePoolTasks(PoolStore(database.poolDao()).observePool(PoolType.THREE_D).first()).map { it.taskId }

    private suspend fun tableNames(): List<String> =
        GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao()).observeTable().first().map { it.gameName }

    private suspend fun history() = HistoryStore(database.historyDao()).observeHistory().first().entries

    /** Everything under a game: its cells, their pieces, and its tasks, deleted or not. */
    private suspend fun everythingUnder(gameId: EntityId) =
        database.gameCellDao().cellsOfGame(gameId).let { cells ->
            Triple(
                cells,
                cells.flatMap { database.cellSegmentDao().segmentsOfCell(it.id) },
                database.taskDao().allTasksIncludingDeleted(),
            )
        }

    @Test
    fun `deleting a game tombstones it, leaves everything under it, and says so in the history`() =
        runBlocking<Unit> {
            val (gameId, taskId) = gameWithTask("Harmonies")
            val (_, keptTask) = gameWithTask("Catan")
            val before = everythingUnder(gameId)
            val row = database.gameDao().activeGameById(gameId)

            setup.deleteGame(gameId)

            val stored = assertNotNull(database.gameDao().gameByIdIncludingDeleted(gameId))
            assertNotNull(stored.deletedAt, "the game was not tombstoned")
            assertEquals(row?.copy(deletedAt = stored.deletedAt, updatedAt = stored.updatedAt), stored, "more than the tombstone changed")
            assertEquals(before, everythingUnder(gameId), "something under the game was changed or erased")
            assertEquals(listOf("Catan"), tableNames())
            assertEquals(listOf(keptTask), inPool(), "the deleted game's task is still in the pool")
            assertTrue(taskId !in inPool())

            val line = history().single { it.change == HistoryChange.GameDeleted }
            assertEquals(gameId, line.gameId)
            assertEquals(stored.deletedAt, line.occurredAt)
            assertTrue(line.gameIsDeleted)
        }

    @Test
    fun `restoring brings back the same game with the same cells and tasks, and writes its own line`() =
        runBlocking<Unit> {
            val (gameId, taskId) = gameWithTask("Harmonies")
            val before = everythingUnder(gameId)
            val original = database.gameDao().activeGameById(gameId)
            setup.deleteGame(gameId)

            val outcome = setup.restoreGame(gameId)

            assertEquals(GameRestoreOutcome.Restored(name = "Harmonies", namesakes = 0), outcome)
            val back = assertNotNull(database.gameDao().activeGameById(gameId), "the same identity is not back")
            assertNull(back.deletedAt)
            assertEquals(original?.copy(updatedAt = back.updatedAt), back, "the game came back different")
            assertEquals(before, everythingUnder(gameId), "the cells or tasks came back different")
            assertEquals(listOf("Harmonies"), tableNames())
            assertEquals(listOf(taskId), inPool())

            val kinds = database.historyDao().eventsOfGame(gameId).map { it.kind }
            assertEquals(listOf(HistoryEventKind.GAME_DELETED, HistoryEventKind.GAME_RESTORED), kinds)
            assertTrue(history().none { it.gameIsDeleted }, "the history still reads the game as deleted")
        }

    @Test
    fun `restoring twice writes once, and deleting what is gone is refused`() =
        runBlocking<Unit> {
            val (gameId, _) = gameWithTask("Harmonies")
            setup.deleteGame(gameId)
            setup.restoreGame(gameId)

            assertEquals(GameRestoreOutcome.AlreadyThere, setup.restoreGame(gameId))
            assertEquals(2, database.historyDao().eventsOfGame(gameId).size, "a second restore wrote a line")

            setup.deleteGame(gameId)
            val again = assertFailsWith<GameSetupException> { setup.deleteGame(gameId) }
            assertEquals(GameSetupFailure.GAME_NOT_AVAILABLE, again.failure)
            assertEquals(3, database.historyDao().eventsOfGame(gameId).size, "a second delete wrote a line")

            val missing = assertFailsWith<GameSetupException> { setup.restoreGame(IdGenerator.Random.newId()) }
            assertEquals(GameSetupFailure.GAME_NOT_AVAILABLE, missing.failure)
        }

    @Test
    fun `a game restored beside another of the same name keeps its name and counts the namesake`() =
        runBlocking<Unit> {
            val (gameId, _) = gameWithTask("Catan")
            setup.deleteGame(gameId)
            // Made while the first was away. Names are not unique (PLAN 12.3), and
            // the `A–Z` layout reads `CATAN` and `Catan` as the same name.
            setup.createGame("CATAN")

            val outcome = setup.restoreGame(gameId)

            assertEquals(GameRestoreOutcome.Restored(name = "Catan", namesakes = 1), outcome)
            assertEquals(listOf("CATAN", "Catan"), tableNames().sorted())
            assertEquals("Catan", database.gameDao().activeGameById(gameId)?.name, "the restored game was renamed")
        }
}
