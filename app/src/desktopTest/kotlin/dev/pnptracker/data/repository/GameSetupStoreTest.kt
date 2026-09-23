package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.domain.games.GameRenameOutcome
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.model.CellColumnType
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
        store = GameSetupStore(database.gameDao(), database.gameCellDao(), clock = StoppedClock(moment))
    }

    @AfterTest
    fun closeAndDeleteTemporaryDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun games() = store.observeGames().first()

    private suspend fun cellsOf(gameId: EntityId) = store.observeCells(gameId).first()

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
    fun `a game can be given another name, and keeps everything else`() =
        runBlocking {
            val id = store.createGame("Harmoies")
            val cellId = store.openCell(id, CellColumnType.THREE_D)
            val before = assertNotNull(database.gameDao().activeGameById(id))

            val outcome = store.renameGame(id, "  Harmonies  ")

            assertEquals(GameRenameOutcome.RENAMED, outcome)
            val after = assertNotNull(database.gameDao().activeGameById(id))
            // Trimmed at the ends, as a name typed into the composer is.
            assertEquals("Harmonies", after.name)
            assertEquals(before.copy(name = "Harmonies", updatedAt = after.updatedAt), after, "renaming changed something else")
            assertEquals(listOf(cellId), cellsOf(id).map { it.id }, "renaming moved the game's cells")
        }

    @Test
    fun `the name a game already has is not written again`() =
        runBlocking {
            val id = store.createGame("Harmonies")
            val before = assertNotNull(database.gameDao().activeGameById(id))

            val outcome = store.renameGame(id, "  Harmonies  ")

            assertEquals(GameRenameOutcome.UNCHANGED, outcome)
            assertEquals(before, assertNotNull(database.gameDao().activeGameById(id)), "a name that did not change was written")
        }

    @Test
    fun `renaming a game that is gone says so rather than writing`() =
        runBlocking {
            val id = store.createGame("Harmonies")
            database.gameDao().softDelete(id, moment)

            val refusal = assertFailsWith<GameSetupException> { store.renameGame(id, "Root") }

            assertEquals(GameSetupFailure.GAME_NOT_AVAILABLE, refusal.failure)
            assertEquals(emptyList(), games())
        }

    @Test
    fun `a name that says nothing is refused before anything is written`() =
        runBlocking {
            val id = store.createGame("Harmonies")

            assertFailsWith<IllegalArgumentException> { store.renameGame(id, "   ") }

            assertEquals("Harmonies", games().single().name)
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
    fun `an opened cell goes under the game it was opened for`() =
        runBlocking {
            val gameId = store.createGame("Harmonies")

            val cellId = store.openCell(gameId, CellColumnType.THREE_D)

            val cell = cellsOf(gameId).single()
            assertEquals(cellId, cell.id)
            assertEquals(gameId, cell.gameId)
            assertEquals(CellColumnType.THREE_D, cell.columnType)
        }

    @Test
    fun `one game's cells never show up under another`() =
        runBlocking {
            val first = store.createGame("Harmonies")
            val second = store.createGame("Root")
            store.openCell(first, CellColumnType.THREE_D)
            store.openCell(first, CellColumnType.CARD)
            store.openCell(second, CellColumnType.NOTES)

            assertEquals(
                listOf(CellColumnType.THREE_D, CellColumnType.CARD),
                cellsOf(first).map { it.columnType }.sortedBy { it.ordinal },
            )
            assertEquals(listOf(CellColumnType.NOTES), cellsOf(second).map { it.columnType })
        }

    @Test
    fun `opening the same column twice hands back the one cell`() =
        runBlocking {
            val gameId = store.createGame("Harmonies")

            val first = store.openCell(gameId, CellColumnType.THREE_D)
            val second = store.openCell(gameId, CellColumnType.THREE_D)

            assertEquals(first, second, "the second call opened a second cell")
            assertEquals(1, cellsOf(gameId).size)
        }

    @Test
    fun `a cell cannot be opened in a game that is not there`() =
        runBlocking {
            val failure =
                assertFailsWith<GameSetupException> {
                    store.openCell(IdGenerator.Random.newId(), CellColumnType.THREE_D)
                }

            assertEquals(GameSetupFailure.GAME_NOT_AVAILABLE, failure.failure)
        }

    @Test
    fun `a cell cannot be opened in a deleted game`() =
        runBlocking {
            val gameId = store.createGame("Harmonies")
            database.gameDao().softDelete(gameId, moment)

            val failure = assertFailsWith<GameSetupException> { store.openCell(gameId, CellColumnType.THREE_D) }

            assertEquals(GameSetupFailure.GAME_NOT_AVAILABLE, failure.failure)
            assertEquals(emptyList(), cellsOf(gameId))
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
            store.openCell(gameId, CellColumnType.THREE_D)
            val before = database.gameCellDao().cellsOfGame(gameId)

            store.setGameCompleted(gameId, true)

            assertEquals(before, database.gameCellDao().cellsOfGame(gameId))
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
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
    fun `games cells and completion are still there after closing and reopening`() =
        runBlocking {
            val first = store.createGame("Harmonies")
            val second = store.createGame("Root")
            store.openCell(first, CellColumnType.THREE_D)
            store.openCell(first, CellColumnType.CARD)
            store.openCell(second, CellColumnType.NOTES)
            store.setGameCompleted(first, true)
            database.close()

            database = DatabaseFactory().open(directory.databaseFile)
            store = GameSetupStore(database.gameDao(), database.gameCellDao(), clock = StoppedClock(moment))

            assertEquals(listOf("Harmonies", "Root"), games().map { it.name })
            assertEquals(listOf(true, false), games().map { it.isManuallyCompleted })
            assertEquals(2, cellsOf(first).size)
            assertEquals(listOf(CellColumnType.NOTES), cellsOf(second).map { it.columnType })
        }

    @Test
    fun `setting up games leaves the import tables untouched`() =
        runBlocking {
            val gameId = store.createGame("Harmonies")
            store.openCell(gameId, CellColumnType.THREE_D)
            store.setGameCompleted(gameId, true)

            assertEquals(emptyList(), database.importDao().allBatches())
        }

    @Test
    fun `an existing game and cell written directly are read back the same way`() =
        runBlocking {
            // Proves the queries agree with rows this store did not write itself.
            val game = aGame(name = "Wingspan")
            database.gameDao().insert(game)
            database.gameCellDao().insert(aCell(gameId = game.id, columnType = CellColumnType.CARD))

            assertEquals(listOf("Wingspan"), games().map { it.name })
            assertEquals(listOf(CellColumnType.CARD), cellsOf(game.id).map { it.columnType })
        }
}
