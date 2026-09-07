package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aDraftTask
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aRawImportBlock
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.history.HistoryChange
import dev.pnptracker.domain.history.HistoryEntry
import dev.pnptracker.domain.history.HistoryFilter
import dev.pnptracker.domain.history.HistoryPeriod
import dev.pnptracker.domain.history.filterHistory
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What the history screen is handed once an import has been confirmed and taken
 * back.
 *
 * PLAN 12.15 asks the same screen for imports and rollbacks alongside everything
 * else, so this reads them through the very store the screen reads: the lines
 * have to arrive as things that happened, in the one order, carrying the names
 * a user recognises. Whether they *read* well in Turkish is asked next door in
 * `HistoryScreenLayoutTest`; what is asked here is that they arrive at all, say
 * what they are, and filter like everything else.
 */
class ImportHistoryReadingTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private val importedAt = Instant.fromEpochMilliseconds(1_780_000_000_000)
    private val takenBackAt = Instant.fromEpochMilliseconds(1_780_900_000_000)

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

    private val importDao get() = database.importDao()

    private var gameId: EntityId = IdGenerator.Random.newId()
    private var otherGameId: EntityId = IdGenerator.Random.newId()
    private var batchId: EntityId = IdGenerator.Random.newId()

    /** One import that wrote two tasks into Harmonies and one into Wingspan. */
    private suspend fun givenAConfirmedImport() {
        val game = aGame(name = "Harmonies")
        val other = aGame(name = "Wingspan")
        database.gameDao().insert(game)
        database.gameDao().insert(other)
        gameId = game.id
        otherGameId = other.id
        val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
        val otherCell = aCell(gameId = other.id, columnType = CellColumnType.THREE_D)
        database.gameCellDao().insert(cell)
        database.gameCellDao().insert(otherCell)

        val batch = anImportBatch(rawBlockCount = 3)
        importDao.insertBatch(batch)
        batchId = batch.id
        listOf(cell.id to "Kırmızı ev", cell.id to "Mavi ev", otherCell.id to "Sarı kuş")
            .forEachIndexed { at, (target, name) ->
                val block =
                    aRawImportBlock(
                        batch.id,
                        rowIndex = at + 1,
                        columnIndex = 1,
                        sourceColumnType = SourceColumnType.THREE_D,
                    )
                importDao.insertRawBlock(block)
                importDao.setRawBlockProcessed(block.id, true, updatedAt)
                val draft =
                    aDraftTask(block.id, name = name).copy(requiredQuantity = 20, createdAt = createdAt + (at + 1).seconds)
                importDao.addDraftTask(draft)
                importDao.setDraftTargetUnderReview(draft.id, target, PoolType.THREE_D, TrackingMode.THREE_D_BATCH, updatedAt)
            }
        importDao.confirmDraftBatch(batchId, true, StoppedClock(importedAt), IdGenerator.Random)
    }

    private suspend fun history(): List<HistoryEntry> = HistoryStore(database.historyDao()).observeHistory().first().entries

    private suspend fun takeItBack() = ImportRollbackStore(importDao, IdGenerator.Random, StoppedClock(takenBackAt)).rollBack(batchId)

    @Test
    fun `confirming an import reaches the screen as one line per game`() =
        runBlocking<Unit> {
            givenAConfirmedImport()

            val confirmations = history().filter { it.change == HistoryChange.ImportConfirmed }

            // Three tasks, two games, two lines: the import is what happened, not
            // each task in it.
            assertEquals(2, confirmations.size)
            assertEquals(setOf("Harmonies", "Wingspan"), confirmations.mapNotNull { it.gameName }.toSet())
            assertTrue(confirmations.all { it.taskId == null }, "a game's own line named a task")
            assertTrue(confirmations.all { it.occurredAt == importedAt })
        }

    @Test
    fun `taking it back reaches the screen as a line per game and a line per task`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            val names = importDao.tasksOfConfirmedBatch(batchId).associate { it.id to it.name }

            takeItBack()

            val entries = history()
            val perGame = entries.filter { it.change == HistoryChange.ImportRolledBack }
            val perTask = entries.filter { it.change == HistoryChange.TaskRolledBack }

            assertEquals(setOf("Harmonies", "Wingspan"), perGame.mapNotNull { it.gameName }.toSet())
            assertEquals(3, perTask.size)
            // Each task's line arrives with the name the user reads it by, even
            // though the task is now out of view: PLAN 5.2 tombstones rather than
            // erases, which is exactly what keeps this readable.
            assertEquals(names.values.toSet(), perTask.mapNotNull { it.taskName }.toSet())
            assertTrue(perTask.all { it.gameName != null }, "a task's rollback line lost its game")
        }

    @Test
    fun `the newest thing that happened is at the top`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            takeItBack()

            val entries = history()

            // Everything the rollback wrote is newer than everything the
            // confirmation wrote, and the screen is read from the top.
            val firstConfirmation = entries.indexOfFirst { it.change == HistoryChange.ImportConfirmed }
            val lastRollback = entries.indexOfLast { it.change == HistoryChange.ImportRolledBack }
            assertTrue(lastRollback < firstConfirmation, "the older lines came out above the newer ones")
            assertEquals(entries.sortedByDescending { it.occurredAt }.map { it.id }, entries.map { it.id })
        }

    @Test
    fun `the game filter still narrows to one game`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            takeItBack()
            val entries = history()

            val harmonies = filterHistory(entries, HistoryFilter(gameId = gameId), takenBackAt)

            assertTrue(harmonies.isNotEmpty())
            assertTrue(harmonies.all { it.gameId == gameId }, "another game's lines came through the filter")
            // Harmonies got two tasks; Wingspan one. Both games got a line each
            // for the import and for the rollback.
            assertEquals(2, harmonies.count { it.change == HistoryChange.TaskRolledBack })
            assertEquals(1, harmonies.count { it.change == HistoryChange.ImportConfirmed })
            assertEquals(1, harmonies.count { it.change == HistoryChange.ImportRolledBack })
        }

    @Test
    fun `the date filter still admits and excludes them`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            takeItBack()
            val entries = history()

            // Read a week after the rollback: the rollback is inside the window
            // and the import, ten days older, is not.
            val aWeekLater = takenBackAt + kotlin.time.Duration.parse("6d")
            val recent = filterHistory(entries, HistoryFilter(period = HistoryPeriod.LAST_WEEK), aWeekLater)

            assertTrue(recent.none { it.change == HistoryChange.ImportConfirmed }, "an older line was let through")
            assertEquals(2, recent.count { it.change == HistoryChange.ImportRolledBack })
            assertEquals(3, recent.count { it.change == HistoryChange.TaskRolledBack })
        }

    @Test
    fun `both games are offered by the filter once each`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            takeItBack()

            val log = HistoryStore(database.historyDao()).observeHistory().first()

            assertEquals(listOf("Harmonies", "Wingspan"), log.games.map { it.name })
        }

    @Test
    fun `a blocked rollback leaves the history exactly as it was`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            val before = history()
            val touched = importDao.tasksOfConfirmedBatch(batchId).first()
            database.taskDao().softDelete(touched.id, takenBackAt)
            val afterTheDeletion = history()

            runCatching { takeItBack() }

            // The deletion wrote its own line, as it always did. The refused
            // rollback wrote nothing at all: PLAN 12.15 keeps an operation that
            // did not happen out of the history.
            assertEquals(before.size + 1, afterTheDeletion.size)
            assertEquals(afterTheDeletion, history(), "a refused rollback wrote to the history")
            assertTrue(history().none { it.change == HistoryChange.ImportRolledBack })
            assertTrue(history().none { it.change == HistoryChange.TaskRolledBack })
        }

    @Test
    fun `a rolled back task keeps every earlier line it had`() =
        runBlocking<Unit> {
            givenAConfirmedImport()
            // Named rather than taken by position: the batch's tasks come back
            // in identity order, which says nothing about which game they are in.
            val task = importDao.tasksOfConfirmedBatch(batchId).single { it.name == "Kırmızı ev" }

            takeItBack()

            val itsLines = history().filter { it.taskId == task.id }
            assertEquals(listOf(HistoryChange.TaskRolledBack), itsLines.map { it.change })
            // And the task is still nameable, which is what a tombstone is for:
            // a hard delete would have left this line pointing at nothing.
            assertEquals(task.name, assertNotNull(itsLines.single().taskName))
            assertEquals(gameId, itsLines.single().gameId)
        }
}
