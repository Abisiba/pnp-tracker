package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
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
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What taking an import back costs, counted at the driver.
 *
 * The writes are allowed to grow, because the writes are the work: forty-two
 * tasks are forty-two tombstones. What may not grow is the number of questions
 * asked to decide — PLAN 16 rules out the shape where a batch of forty-two costs
 * forty-two round trips, and a rollback is full of questions that invite it
 * ("has anything happened to this task?", "what does this cell say now?").
 *
 * Counted with a frequency map. A set would collapse forty-two runs of one
 * statement into one and report a cost nobody paid.
 */
class ImportRollbackQueryCountTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false

    private val importedAt = Instant.fromEpochMilliseconds(1_780_000_000_000)
    private val takenBackAt = Instant.fromEpochMilliseconds(1_780_900_000_000)

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

    private val importDao get() = database.importDao()

    private fun ran(recorded: List<String>): Map<String, Int> =
        recorded
            .filterNot { "room_table_modification" in it.lowercase() }
            .map { it.trimStart().uppercase().replace(Regex("\\s+"), " ") }
            .filterNot { it.startsWith("BEGIN") || it.startsWith("COMMIT") || it.startsWith("END") }
            .filterNot { it.startsWith("ROLLBACK") || it.startsWith("SAVEPOINT") || it.startsWith("RELEASE") }
            .filterNot { it.startsWith("PRAGMA") }
            .groupingBy { statement ->
                when {
                    "CHANGES()" in statement || "LAST_INSERT_ROWID()" in statement -> "write result"
                    statement.startsWith("SELECT") && "FROM IMPORT_BATCH_CELLS" in statement ->
                        "SELECT import_batch_cells"

                    statement.startsWith("SELECT") && "FROM IMPORT_BATCHES" in statement -> "SELECT import_batches"
                    statement.startsWith("SELECT") && "FROM DRAFT_TASKS" in statement -> "SELECT draft_tasks"
                    statement.startsWith("SELECT") && "FROM PROGRESS_EVENTS" in statement -> "SELECT progress_events"
                    statement.startsWith("SELECT") && "FROM HISTORY_EVENTS" in statement -> "SELECT history_events"
                    statement.startsWith("SELECT") && "FROM CELL_SEGMENTS" in statement -> "SELECT cell_segments"
                    statement.startsWith("SELECT") && "FROM TASKS" in statement -> "SELECT tasks"
                    statement.startsWith("SELECT") -> "SELECT other"
                    statement.startsWith("INSERT") && "`HISTORY_EVENTS`" in statement -> "INSERT history_events"
                    statement.startsWith("INSERT") -> "INSERT other"
                    statement.startsWith("DELETE") && "CELL_SEGMENTS" in statement -> "DELETE cell_segments"
                    statement.startsWith("UPDATE") && "DELETED_AT" in statement -> "UPDATE tombstone"
                    statement.startsWith("UPDATE") && "GAME_CELLS" in statement -> "UPDATE game_cells"
                    statement.startsWith("UPDATE") && "IMPORT_BATCHES" in statement -> "UPDATE import_batches"
                    statement.startsWith("UPDATE") -> "UPDATE other"
                    else -> "other"
                }
            }.eachCount()

    /** Every question a transaction asked, whatever table it asked it of. */
    private fun decisions(counted: Map<String, Int>): Map<String, Int> = counted.filterKeys { it.startsWith("SELECT") }

    private var batches = 0

    /** One confirmed import of [drafts] tasks, all in one cell or one cell each. */
    private suspend fun aConfirmedBatch(
        drafts: Int,
        oneCell: Boolean = true,
    ): EntityId {
        val batch = anImportBatch(rawBlockCount = drafts, sha256 = "%064x".format(batches++))
        importDao.insertBatch(batch)
        val game = aGame(name = "Oyun ${IdGenerator.Random.newId()}")
        val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        repeat(drafts) { at ->
            val target =
                if (oneCell) {
                    cell.id
                } else {
                    val other = aGame(name = "Oyun $at ${IdGenerator.Random.newId()}")
                    val otherCell = aCell(gameId = other.id, columnType = CellColumnType.THREE_D)
                    database.gameDao().insert(other)
                    database.gameCellDao().insert(otherCell)
                    otherCell.id
                }
            val block =
                aRawImportBlock(batch.id, rowIndex = at + 1, columnIndex = 1, sourceColumnType = SourceColumnType.THREE_D)
            importDao.insertRawBlock(block)
            importDao.setRawBlockProcessed(block.id, true, updatedAt)
            val draft =
                aDraftTask(block.id, name = "Token $at").copy(requiredQuantity = 20, createdAt = createdAt + (at + 1).seconds)
            importDao.addDraftTask(draft)
            importDao.setDraftTargetUnderReview(draft.id, target, PoolType.THREE_D, TrackingMode.THREE_D_BATCH, updatedAt)
        }
        importDao.confirmDraftBatch(batch.id, true, StoppedClock(importedAt), IdGenerator.Random)
        return batch.id
    }

    private suspend fun previewed(batchId: EntityId): Map<String, Int> {
        driver.start()
        importDao.previewRollback(batchId)
        return ran(driver.stop())
    }

    private suspend fun tookBack(batchId: EntityId): Map<String, Int> {
        driver.start()
        importDao.rollBackConfirmedBatch(batchId, StoppedClock(takenBackAt), IdGenerator.Random)
        return ran(driver.stop())
    }

    @Test
    fun `previewing forty-two asks exactly what previewing one asks`() =
        runBlocking<Unit> {
            val one = previewed(aConfirmedBatch(1))
            val many = previewed(aConfirmedBatch(42))

            assertEquals(one, many, "previewing forty-two tasks asked more than previewing one")
            // Named so a regression is recognisable rather than merely different.
            assertEquals(1, many["SELECT tasks"], "the tasks were asked about one at a time")
            assertEquals(1, many["SELECT cell_segments"], "the pieces of a cell were asked about one at a time")
            assertEquals(1, many["SELECT progress_events"], "shortages were asked about per task")
            assertEquals(1, many["SELECT history_events"], "the history was asked about per task")
            // Two: the cells this import recorded, and their pieces as they
            // stand. Both are one statement for the whole batch.
            assertEquals(2, many["SELECT import_batch_cells"])
        }

    @Test
    fun `taking forty-two back asks exactly what taking one back asks`() =
        runBlocking<Unit> {
            val one = tookBack(aConfirmedBatch(1))
            val many = tookBack(aConfirmedBatch(42))

            assertEquals(decisions(one), decisions(many), "taking forty-two back asked more questions than one")
            // The writing grows, because the writing is the work: forty-two
            // tombstones, forty-two task lines plus the one for the game, and the
            // forty-two names and forty-one spaces the import had added.
            assertEquals(42, many["UPDATE tombstone"])
            assertEquals(43, many["INSERT history_events"])
            assertEquals(83, many["DELETE cell_segments"])
            assertEquals(1, many["UPDATE game_cells"], "one cell was touched more than once")
            assertEquals(1, many["UPDATE import_batches"])
        }

    @Test
    fun `forty-two tasks spread over forty-two cells ask the same again`() =
        runBlocking<Unit> {
            val one = tookBack(aConfirmedBatch(1))
            val spread = tookBack(aConfirmedBatch(42, oneCell = false))

            assertEquals(decisions(one), decisions(spread), "reading forty-two cells cost a question each")
            assertEquals(42, spread["DELETE cell_segments"], "a cell of its own needs no separator")
            assertEquals(42, spread["UPDATE game_cells"])
            // Forty-two games really are forty-two lines, plus one per task.
            assertEquals(84, spread["INSERT history_events"])
        }

    @Test
    fun `a rollback asks the same questions however long the history already is`() =
        runBlocking<Unit> {
            val onAnEmptyHistory = tookBack(aConfirmedBatch(2))
            val small = aConfirmedBatch(2)
            val large = aConfirmedBatch(42)
            // Take the large one back first, so the history the small one is read
            // against already holds eighty-odd lines rather than a handful.
            tookBack(large)

            val onALongHistory = tookBack(small)

            assertEquals(
                decisions(onAnEmptyHistory),
                decisions(onALongHistory),
                "a longer history cost more questions",
            )
        }
}
