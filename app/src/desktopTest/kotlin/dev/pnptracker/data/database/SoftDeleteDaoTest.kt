package dev.pnptracker.data.database

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Covers what "deleted" means for games and items: the row stays, the active
 * queries stop returning it, and a game hides its items without changing them.
 */
class SoftDeleteDaoTest {
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

    @Test
    fun `a deleted game leaves the active list but stays in the full list`() =
        runBlocking {
            val game = aGame()
            database.gameDao().insert(game)

            assertEquals(1, database.gameDao().softDelete(game.id, deletedAt))

            assertEquals(emptyList(), database.gameDao().activeGames())
            assertNull(database.gameDao().activeGameById(game.id))
            assertEquals(0, database.gameDao().activeCount())
            assertEquals(1, database.gameDao().deletedCount())
            val stored = assertNotNull(database.gameDao().gameByIdIncludingDeleted(game.id))
            assertEquals(deletedAt, stored.deletedAt)
            assertEquals(deletedAt, stored.updatedAt)
            assertEquals(listOf(game.id), database.gameDao().allGamesIncludingDeleted().map { it.id })
        }

    @Test
    fun `deleting a game a second time changes no timestamp`() =
        runBlocking {
            val game = aGame()
            database.gameDao().insert(game)
            database.gameDao().softDelete(game.id, deletedAt)
            val later = Instant.fromEpochMilliseconds(EPOCH_MILLISECONDS_DELETED + 90_000)

            val changedRows = database.gameDao().softDelete(game.id, later)

            assertEquals(0, changedRows)
            val stored = assertNotNull(database.gameDao().gameByIdIncludingDeleted(game.id))
            assertEquals(deletedAt, stored.deletedAt)
            assertEquals(deletedAt, stored.updatedAt)
        }

    @Test
    fun `deleting a game keeps its cell rows on disk`() =
        runBlocking {
            val game = aGame()
            val cell = aCell(gameId = game.id)
            database.gameDao().insert(game)
            database.gameCellDao().insert(cell)

            database.gameDao().softDelete(game.id, deletedAt)

            // A cell has no tombstone of its own: deleting the game is what takes
            // it out of view, and the row is still there to come back with it.
            assertEquals(listOf(cell), database.gameCellDao().cellsOfGame(game.id))
            assertEquals(cell, assertNotNull(database.gameCellDao().cellById(cell.id)))
        }

    @Test
    fun `deleting a game hides its cells from the active queries`() =
        runBlocking {
            val game = aGame()
            val cell = aCell(gameId = game.id)
            database.gameDao().insert(game)
            database.gameCellDao().insert(cell)

            database.gameDao().softDelete(game.id, deletedAt)

            assertEquals(emptyList(), database.gameCellDao().observeCellsOfActiveGames().first())
            assertEquals(0, database.taskDao().activeCellCount(cell.id))
        }

    @Test
    fun `a cell of a live game is active while a cell of a deleted game is not`() =
        runBlocking {
            val liveGame = aGame(name = "Aktif oyun")
            val deletedGame = aGame(name = "Silinen oyun")
            val liveCell = aCell(gameId = liveGame.id)
            val hiddenCell = aCell(gameId = deletedGame.id)
            database.gameDao().insert(liveGame)
            database.gameDao().insert(deletedGame)
            database.gameCellDao().insert(liveCell)
            database.gameCellDao().insert(hiddenCell)

            database.gameDao().softDelete(deletedGame.id, deletedAt)

            assertEquals(listOf(liveCell), database.gameCellDao().observeCellsOfActiveGames().first())
            assertEquals(1, database.taskDao().activeCellCount(liveCell.id))
            assertEquals(0, database.taskDao().activeCellCount(hiddenCell.id))
        }
}
