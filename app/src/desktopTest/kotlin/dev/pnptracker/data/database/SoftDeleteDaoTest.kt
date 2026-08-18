package dev.pnptracker.data.database

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
    fun `deleting a game keeps its item rows on disk`() =
        runBlocking {
            val game = aGame()
            val item = anItem(gameId = game.id)
            database.gameDao().insert(game)
            database.itemDao().insert(item)

            database.gameDao().softDelete(game.id, deletedAt)

            assertEquals(listOf(item), database.itemDao().allItemsIncludingDeleted())
            val stored = assertNotNull(database.itemDao().itemByIdIncludingDeleted(item.id))
            assertNull(stored.deletedAt)
            assertEquals(createdAt, stored.updatedAt)
        }

    @Test
    fun `deleting a game hides its items from the active queries`() =
        runBlocking {
            val game = aGame()
            val item = anItem(gameId = game.id)
            database.gameDao().insert(game)
            database.itemDao().insert(item)

            database.gameDao().softDelete(game.id, deletedAt)

            assertEquals(emptyList(), database.itemDao().activeItems())
            assertEquals(emptyList(), database.itemDao().activeItemsOfGame(game.id))
            assertNull(database.itemDao().activeItemById(item.id))
            assertEquals(0, database.itemDao().activeCountOfGame(game.id))
        }

    @Test
    fun `a deleted item leaves the active queries of a live game`() =
        runBlocking {
            val game = aGame()
            val deletedItem = anItem(gameId = game.id, name = "Eski token")
            val keptItem = anItem(gameId = game.id, name = "Kalan token")
            database.gameDao().insert(game)
            database.itemDao().insert(deletedItem)
            database.itemDao().insert(keptItem)

            assertEquals(1, database.itemDao().softDelete(deletedItem.id, deletedAt))

            assertEquals(listOf(keptItem), database.itemDao().activeItemsOfGame(game.id))
            assertNull(database.itemDao().activeItemById(deletedItem.id))
            assertEquals(1, database.itemDao().activeCountOfGame(game.id))
            assertEquals(2, database.itemDao().allItemsIncludingDeleted().size)
        }

    @Test
    fun `deleting an item a second time changes no timestamp`() =
        runBlocking {
            val game = aGame()
            val item = anItem(gameId = game.id)
            database.gameDao().insert(game)
            database.itemDao().insert(item)
            database.itemDao().softDelete(item.id, deletedAt)
            val later = Instant.fromEpochMilliseconds(EPOCH_MILLISECONDS_DELETED + 90_000)

            val changedRows = database.itemDao().softDelete(item.id, later)

            assertEquals(0, changedRows)
            val stored = assertNotNull(database.itemDao().itemByIdIncludingDeleted(item.id))
            assertEquals(deletedAt, stored.deletedAt)
            assertEquals(deletedAt, stored.updatedAt)
        }

    @Test
    fun `an item of a live game is active while an item of a deleted game is not`() =
        runBlocking {
            val liveGame = aGame(name = "Aktif oyun")
            val deletedGame = aGame(name = "Silinen oyun")
            val liveItem = anItem(gameId = liveGame.id, name = "Aktif token")
            val hiddenItem = anItem(gameId = deletedGame.id, name = "Gizli token")
            database.gameDao().insert(liveGame)
            database.gameDao().insert(deletedGame)
            database.itemDao().insert(liveItem)
            database.itemDao().insert(hiddenItem)

            database.gameDao().softDelete(deletedGame.id, deletedAt)

            assertEquals(listOf(liveItem), database.itemDao().activeItems())
            assertEquals(2, database.itemDao().allItemsIncludingDeleted().size)
        }
}
