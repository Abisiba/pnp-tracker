package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.anItem
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
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
import kotlin.time.Instant

/**
 * Setting up games and items against a real database in a temporary directory.
 *
 * The two things worth proving here cannot be shown against a stand in: that a
 * game's items really are kept to that game by the query, and that what the user
 * built is still there after the application closes.
 */
class GameSetupStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: GameSetupStore
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_780_000_000_000)

    @BeforeTest
    fun openTemporaryDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = GameSetupStore(database.gameDao(), database.itemDao(), clock = StoppedClock(moment))
    }

    @AfterTest
    fun closeAndDeleteTemporaryDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun games() = store.observeGames().first()

    private suspend fun itemsOf(gameId: EntityId) = store.observeItems(gameId).first()

    @Test
    fun `a game the user typed is saved and comes back in the list`() =
        runBlocking {
            val id = store.createGame("Harmonies")

            val saved = games().single()
            assertEquals(id, saved.id)
            assertEquals("Harmonies", saved.name)
            assertTrue(!saved.isManuallyCompleted, "a new game is not finished")
        }

    @Test
    fun `a game the user typed carries no import behind it`() =
        runBlocking {
            val id = store.createGame("Harmonies")

            val row = assertNotNull(database.gameDao().activeGameById(id))
            assertNull(row.sourceImportBatchId, "a hand made game must not look like an imported one")
        }

    @Test
    fun `stray spaces around a name are dropped and the inside is kept`() =
        runBlocking {
            store.createGame("  Ticket to  Ride  ")

            assertEquals("Ticket to  Ride", games().single().name)
        }

    @Test
    fun `a blank game name is refused and nothing is written`() =
        runBlocking {
            assertFailsWith<IllegalArgumentException> { store.createGame("   ") }

            assertEquals(emptyList(), games())
            assertEquals(0, database.gameDao().activeCount())
        }

    @Test
    fun `games are listed in a fixed order`() =
        runBlocking {
            store.createGame("Splendor")
            store.createGame("Harmonies")
            store.createGame("Root")

            assertEquals(listOf("Harmonies", "Root", "Splendor"), games().map { it.name })
        }

    @Test
    fun `a deleted game is not in the list`() =
        runBlocking {
            val kept = store.createGame("Harmonies")
            val removed = store.createGame("Root")
            database.gameDao().softDelete(removed, moment)

            assertEquals(listOf(kept), games().map { it.id })
        }

    @Test
    fun `an item goes under the game it was created for`() =
        runBlocking {
            val gameId = store.createGame("Harmonies")

            val itemId = store.createItem(gameId, "Token")

            val item = itemsOf(gameId).single()
            assertEquals(itemId, item.id)
            assertEquals(gameId, item.gameId)
            assertEquals("Token", item.name)
        }

    @Test
    fun `one game's items never show up under another`() =
        runBlocking {
            val first = store.createGame("Harmonies")
            val second = store.createGame("Root")
            store.createItem(first, "Token")
            store.createItem(first, "Arazi")
            store.createItem(second, "Meeple")

            assertEquals(listOf("Arazi", "Token"), itemsOf(first).map { it.name })
            assertEquals(listOf("Meeple"), itemsOf(second).map { it.name })
        }

    @Test
    fun `a blank item name is refused and nothing is written`() =
        runBlocking {
            val gameId = store.createGame("Harmonies")

            assertFailsWith<IllegalArgumentException> { store.createItem(gameId, " \n ") }

            assertEquals(emptyList(), itemsOf(gameId))
        }

    @Test
    fun `an item cannot be created under a game that is not there`() =
        runBlocking {
            val failure =
                assertFailsWith<GameSetupException> {
                    store.createItem(IdGenerator.Random.newId(), "Token")
                }

            assertEquals(GameSetupFailure.GAME_NOT_AVAILABLE, failure.failure)
            assertEquals(emptyList(), database.itemDao().allItemsIncludingDeleted())
        }

    @Test
    fun `an item cannot be created under a deleted game`() =
        runBlocking {
            val gameId = store.createGame("Harmonies")
            database.gameDao().softDelete(gameId, moment)

            val failure = assertFailsWith<GameSetupException> { store.createItem(gameId, "Token") }

            assertEquals(GameSetupFailure.GAME_NOT_AVAILABLE, failure.failure)
            assertEquals(emptyList(), database.itemDao().allItemsIncludingDeleted())
        }

    @Test
    fun `finishing a game and reopening it both work`() =
        runBlocking {
            val gameId = store.createGame("Harmonies")

            store.setGameCompleted(gameId, true)
            assertTrue(games().single().isManuallyCompleted)
            assertEquals(moment, assertNotNull(database.gameDao().activeGameById(gameId)).completedAt)

            store.setGameCompleted(gameId, false)
            assertTrue(!games().single().isManuallyCompleted)
            assertNull(
                assertNotNull(database.gameDao().activeGameById(gameId)).completedAt,
                "reopening a game must clear the date it was finished on",
            )
        }

    @Test
    fun `finishing one game leaves every other game alone`() =
        runBlocking {
            val finished = store.createGame("Harmonies")
            store.createGame("Root")
            store.createGame("Splendor")

            store.setGameCompleted(finished, true)

            assertEquals(
                listOf(true, false, false),
                games().map { it.isManuallyCompleted },
                "only the game that was asked about may change",
            )
        }

    @Test
    fun `finishing a game changes nothing below it`() =
        runBlocking {
            val gameId = store.createGame("Harmonies")
            store.createItem(gameId, "Token")
            val before = database.itemDao().allItemsIncludingDeleted()

            store.setGameCompleted(gameId, true)

            assertEquals(before, database.itemDao().allItemsIncludingDeleted())
            assertEquals(emptyList(), database.taskDao().allTasksIncludingArchivedAndDeleted())
        }

    @Test
    fun `a game that is not there cannot be finished`() =
        runBlocking {
            val failure =
                assertFailsWith<GameSetupException> {
                    store.setGameCompleted(IdGenerator.Random.newId(), true)
                }

            assertEquals(GameSetupFailure.GAME_NOT_AVAILABLE, failure.failure)
        }

    @Test
    fun `games items and completion are still there after closing and reopening`() =
        runBlocking {
            val first = store.createGame("Harmonies")
            val second = store.createGame("Root")
            store.createItem(first, "Token")
            store.createItem(first, "Arazi")
            store.createItem(second, "Meeple")
            store.setGameCompleted(first, true)
            database.close()

            database = DatabaseFactory().open(directory.databaseFile)
            store = GameSetupStore(database.gameDao(), database.itemDao(), clock = StoppedClock(moment))

            assertEquals(listOf("Harmonies", "Root"), games().map { it.name })
            assertEquals(listOf(true, false), games().map { it.isManuallyCompleted })
            assertEquals(listOf("Arazi", "Token"), itemsOf(first).map { it.name })
            assertEquals(listOf("Meeple"), itemsOf(second).map { it.name })
        }

    @Test
    fun `setting up games leaves the import tables untouched`() =
        runBlocking {
            val gameId = store.createGame("Harmonies")
            store.createItem(gameId, "Token")
            store.setGameCompleted(gameId, true)

            assertEquals(emptyList(), database.importDao().allBatches())
        }

    @Test
    fun `an existing game and item written directly are read back the same way`() =
        runBlocking {
            // Proves the queries agree with rows this store did not write itself.
            val game = aGame(name = "Wingspan")
            database.gameDao().insert(game)
            database.itemDao().insert(anItem(gameId = game.id, name = "Bird Cards"))

            assertEquals(listOf("Wingspan"), games().map { it.name })
            assertEquals(listOf("Bird Cards"), itemsOf(game.id).map { it.name })
        }
}
