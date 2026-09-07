package dev.pnptracker.data.database

import androidx.room3.useWriterConnection
import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.domain.importrollback.ImportRollbackFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What is left behind when a rollback cannot finish.
 *
 * PLAN 11.4.4 allows exactly two outcomes: the import is wholly taken back, or
 * nothing at all is — no tombstone, no history line, no piece of a cell removed,
 * and the batch still `CONFIRMED`. A half taken back import would leave the user
 * with a cell missing words nobody can put back and tasks that vanished for no
 * recorded reason.
 *
 * Every failure below is made where the transaction really writes, against a
 * real SQLite file, so what is proved is what a real transaction leaves.
 */
class ImportRollbackFailureTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val failing = FailingSqliteDriver()
    private var realDatabaseExistedBefore = false

    private val importedAt = Instant.fromEpochMilliseconds(1_780_000_000_000)
    private val takenBackAt = Instant.fromEpochMilliseconds(1_780_900_000_000)

    /** Hands out names, and refuses after the [afterwards]-th one. */
    private class LimitedIdGenerator(
        private val afterwards: Int,
    ) : IdGenerator {
        private var handed = 0

        override fun newId(): EntityId {
            if (handed >= afterwards) throw IllegalStateException("no more names")
            handed++
            return IdGenerator.Random.newId()
        }
    }

    /** A clock that will not say what time it is. */
    private class BrokenClock : Clock {
        override fun now(): Instant = throw IllegalStateException("the clock refused to answer")
    }

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = failing).open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        failing.disarm()
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val importDao get() = database.importDao()

    private var gameId: EntityId = IdGenerator.Random.newId()
    private var cellId: EntityId = IdGenerator.Random.newId()
    private var batchId: EntityId = IdGenerator.Random.newId()

    /** Two games, two cells, one confirmed import that wrote into both. */
    private suspend fun givenAConfirmedImport() {
        val game = aGame(name = "Harmonies")
        database.gameDao().insert(game)
        gameId = game.id
        database.cellSegmentDao().saveDocumentText(
            gameId,
            CellColumnType.THREE_D,
            "",
            "Kullanıcının notu",
            StoppedClock(createdAt),
            IdGenerator.Random,
        )
        cellId = assertNotNull(database.gameCellDao().cellOfGame(gameId, CellColumnType.THREE_D)).id
        val other = aGame(name = "Wingspan")
        database.gameDao().insert(other)
        val otherCell = aCell(gameId = other.id, columnType = CellColumnType.THREE_D)
        database.gameCellDao().insert(otherCell)

        val batch = anImportBatch(rawBlockCount = 3)
        importDao.insertBatch(batch)
        batchId = batch.id
        listOf(cellId to "Kırmızı ev", cellId to "Mavi ev", otherCell.id to "Sarı kuş")
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

    /** Everything a rollback could disturb, read as whole rows. */
    private data class Everything(
        val tasks: List<Any?>,
        val segments: List<Any?>,
        val batches: List<Any?>,
        val history: List<Any?>,
        val progress: List<Any?>,
        val snapshots: List<Any?>,
        val cells: List<Any?>,
    )

    private suspend fun everything(): Everything =
        Everything(
            tasks = database.taskDao().allTasksIncludingDeleted(),
            segments =
                rowsOf(
                    "SELECT id, cell_id, order_index, kind, text, task_id FROM cell_segments " +
                        "ORDER BY cell_id, order_index",
                ),
            batches = importDao.allBatches(),
            history = database.historyDao().allEvents(),
            progress = rowsOf("SELECT id, task_id, kind, quantity FROM progress_events ORDER BY id"),
            snapshots = importDao.cellSnapshotsOfBatch(batchId),
            cells = rowsOf("SELECT id, game_id, column_type, updated_at FROM game_cells ORDER BY id"),
        )

    private suspend fun rowsOf(sql: String): List<String> =
        database.useWriterConnection { transactor ->
            transactor.usePrepared(sql) { statement ->
                buildList {
                    while (statement.step()) {
                        add((0..<statement.getColumnCount()).joinToString("|") { statement.getText(it) })
                    }
                }
            }
        }

    private suspend fun rollBack(
        idGenerator: IdGenerator = IdGenerator.Random,
        clock: Clock = StoppedClock(takenBackAt),
    ) = ImportRollbackStore(importDao, idGenerator, clock).rollBack(batchId)

    /** Arms [trap], tries to take the import back, and proves nothing moved. */
    private suspend fun refusedLeavesEverything(
        trap: () -> Unit,
        idGenerator: IdGenerator = IdGenerator.Random,
        clock: Clock = StoppedClock(takenBackAt),
    ) {
        val before = everything()
        trap()

        assertFailsWith<Throwable> { rollBack(idGenerator, clock) }
        failing.disarm()

        assertEquals(before, everything(), "a refused rollback left something behind")
        assertEquals(ImportBatchStatus.CONFIRMED, assertNotNull(importDao.batchById(batchId)).status)
    }

    private fun trapOn(
        occurrence: Int,
        verb: String,
        table: String,
    ): () -> Unit =
        {
            failing.failOn(occurrence) { sql ->
                val text = sql.uppercase()
                text.trimStart().startsWith(verb) && table.uppercase() in text
            }
        }

    // ------------------------------------------------------------- the injections

    @Test
    fun `the first tombstone refuses to go in`() =
        runBlocking {
            givenAConfirmedImport()
            refusedLeavesEverything(trapOn(1, "UPDATE", "DELETED_AT"))
        }

    @Test
    fun `the last tombstone refuses to go in`() =
        runBlocking {
            givenAConfirmedImport()
            refusedLeavesEverything(trapOn(3, "UPDATE", "DELETED_AT"))
        }

    @Test
    fun `the first history line refuses to go in`() =
        runBlocking {
            givenAConfirmedImport()
            refusedLeavesEverything(trapOn(1, "INSERT", "`HISTORY_EVENTS`"))
        }

    @Test
    fun `the last history line refuses to go in`() =
        runBlocking {
            givenAConfirmedImport()
            // Three tasks and two games: the fifth line is the last of them, and
            // by then three tombstones are already written.
            refusedLeavesEverything(trapOn(5, "INSERT", "`HISTORY_EVENTS`"))
        }

    @Test
    fun `the first piece of a cell refuses to go`() =
        runBlocking {
            givenAConfirmedImport()
            refusedLeavesEverything(trapOn(1, "DELETE", "CELL_SEGMENTS"))
        }

    @Test
    fun `a later piece of a cell refuses to go`() =
        runBlocking {
            givenAConfirmedImport()
            refusedLeavesEverything(trapOn(3, "DELETE", "CELL_SEGMENTS"))
        }

    @Test
    fun `touching the cell refuses`() =
        runBlocking {
            givenAConfirmedImport()
            refusedLeavesEverything(trapOn(1, "UPDATE", "GAME_CELLS"))
        }

    @Test
    fun `marking the batch taken back refuses`() =
        runBlocking {
            givenAConfirmedImport()
            // The very last write, with every tombstone, every history line and
            // every removed piece already in. All of it goes back.
            refusedLeavesEverything(trapOn(1, "UPDATE", "IMPORT_BATCHES"))
        }

    // ------------------------------------------------- names and clocks running out

    @Test
    fun `a name runs out before the first write and nothing is written`() =
        runBlocking {
            givenAConfirmedImport()
            // Three tasks and two games need five names. Four is one short, and
            // all five are asked for before the first row is touched.
            refusedLeavesEverything(trap = {}, idGenerator = LimitedIdGenerator(4))
        }

    @Test
    fun `a clock that will not answer writes nothing`() =
        runBlocking {
            givenAConfirmedImport()
            refusedLeavesEverything(trap = {}, clock = BrokenClock())
        }

    // ------------------------------------------- what the preview cannot promise

    @Test
    fun `an edit made after the preview is caught by the transaction`() =
        runBlocking {
            givenAConfirmedImport()
            val store = ImportRollbackStore(importDao, IdGenerator.Random, StoppedClock(takenBackAt))
            val preview = store.previewRollback(batchId)
            assertEquals(true, preview.canRollBack, "the fixture is not what this test is about")

            // Between reading the preview and acting on it, the user edits the
            // cell. The preview still says yes; the transaction reads again and
            // says no, which is why it does not trust the preview.
            database.cellSegmentDao().saveDocumentText(
                gameId,
                CellColumnType.THREE_D,
                "Kullanıcının notu Kırmızı ev Mavi ev",
                "Kullanıcının notu Kırmızı ev Mavi ev!",
                StoppedClock(takenBackAt),
                IdGenerator.Random,
            )
            val before = everything()

            val refusal =
                assertFailsWith<dev.pnptracker.domain.importrollback.ImportRollbackException> { store.rollBack(batchId) }

            assertEquals(ImportRollbackFailure.CELLS_WERE_EDITED, refusal.failure)
            assertEquals(before, everything(), "a refusal from inside the transaction still changed something")
        }

    @Test
    fun `two rollbacks of one batch cannot both succeed`() =
        runBlocking {
            givenAConfirmedImport()
            rollBack()
            val settled = everything()

            assertFailsWith<Throwable> { rollBack() }

            assertEquals(settled, everything(), "the second attempt changed something")
        }
}
