package dev.pnptracker.data.database

import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.domain.importrollback.ImportRollbackException
import dev.pnptracker.domain.importrollback.ImportRollbackFailure
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * An import confirmed before there was anywhere to record what a cell said.
 *
 * The database this works on is a real version 7 one, built from the schema that
 * actually shipped and walked up by the real `Migration7To8`. That matters: the
 * refusal is not something the engine can be told about, it is something it has
 * to notice, and the only honest way to produce the shape is to produce it.
 *
 * PLAN 11.4.4 will not have the missing record guessed at. Today's cell text
 * minus the names the import wrote would give a document that existed on no day
 * at all, because the user has been editing that cell ever since — so the
 * rollback is refused and the reason is told to the user. Both halves of that
 * are asked here: the refusal, and that the refusal costs the database nothing.
 */
class ImportRollbackLegacyBatchTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private var realDatabaseExistedBefore = false

    private val gameId = IdGenerator.Random.newId()
    private val cellId = IdGenerator.Random.newId()
    private val confirmedBatchId = IdGenerator.Random.newId()
    private val importedTaskId = IdGenerator.Random.newId()
    private val handwrittenTaskId = IdGenerator.Random.newId()

    @BeforeTest
    fun createTemporaryDirectory() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    /**
     * A version 7 database with one import already confirmed into a cell the
     * user had written in themselves.
     */
    private fun createUsedVersion7Database() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 7) { connection ->
            insertSeedColors(connection)
            insertVersion4Game(connection, gameId)
            insertVersion4GameCell(connection, cellId, gameId, "CARD")

            insertVersion6Task(connection, handwrittenTaskId, "CARD", "PIPELINE", "Elle yazılan deste", 30)
            insertVersion6Task(connection, importedTaskId, "CARD", "PIPELINE", "İçe aktarılan deste", 15)
            insertVersion4PlainTextSegment(connection, IdGenerator.Random.newId(), cellId, 0, "Kullanıcının notu ")
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), cellId, 1, handwrittenTaskId)
            insertVersion4PlainTextSegment(connection, IdGenerator.Random.newId(), cellId, 2, " ")
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), cellId, 3, importedTaskId)

            insertVersion5ImportBatch(connection, confirmedBatchId, status = "CONFIRMED", createdTaskCount = 1)
            val block = IdGenerator.Random.newId()
            insertVersion6RawImportBlock(connection, block, confirmedBatchId)
            insertVersion4DraftTask(
                connection,
                IdGenerator.Random.newId(),
                block,
                cellId,
                materializedTaskId = importedTaskId,
            )
        }
    }

    /** Opens the database — which walks it up on the first call — and closes it after. */
    private fun <T> withDatabase(body: suspend (AppDatabase) -> T): T {
        val database = DatabaseFactory().open(directory.databaseFile)
        return try {
            runBlocking { body(database) }
        } finally {
            database.close()
        }
    }

    private fun rowsOfEveryTable() = TABLES.associateWith { CommittedSchema.countRowsOf(directory.databaseFile, it) }

    private fun rollbackOf(database: AppDatabase) = ImportRollbackStore(database.importDao())

    private suspend fun piecesOf(database: AppDatabase): List<Triple<EntityId, Int, String?>> =
        database.cellSegmentDao().segmentsOfCell(cellId).map { Triple(it.id, it.orderIndex, it.text) }

    // ----------------------------------------------------------- what it answers

    @Test
    fun `an import confirmed before version 8 cannot be taken back`() {
        createUsedVersion7Database()

        val refusal =
            withDatabase { database ->
                assertFailsWith<ImportRollbackException> { rollbackOf(database).rollBack(confirmedBatchId) }
            }

        assertEquals(ImportRollbackFailure.NO_CELL_SNAPSHOT, refusal.failure)
    }

    @Test
    fun `the preview says the same thing, so the button never offers what cannot be done`() {
        createUsedVersion7Database()

        val preview = withDatabase { database -> rollbackOf(database).previewRollback(confirmedBatchId) }

        assertEquals(ImportRollbackFailure.NO_CELL_SNAPSHOT, preview.blockingFailure)
        assertFalse(preview.canRollBack)
        // A blocked plan holds nothing to do, so a screen reading these counts
        // cannot show the user a number of tasks that would go.
        assertEquals(0, preview.taskCount)
        assertEquals(0, preview.cellCount)
        // And there is nothing to list: the batch has no recorded cell at all,
        // which is a different shape from a recorded cell that has since
        // changed. The sentence about the refusal is the whole of what the
        // screen can say, which is why it says which kind of import this is.
        assertEquals(emptyList(), preview.blockedCells)
        assertEquals(emptyList(), preview.blockedTasks)
    }

    // --------------------------------------------------------- and what it costs

    @Test
    fun `the refusal changes nothing anywhere in the database`() {
        createUsedVersion7Database()
        // The first open walks the database up, so everything measured below is
        // measured on a version 8 database rather than on a version 7 one.
        val piecesBefore = withDatabase { piecesOf(it) }
        val before = rowsOfEveryTable()

        withDatabase { database ->
            assertFailsWith<ImportRollbackException> { rollbackOf(database).rollBack(confirmedBatchId) }
        }

        // Asked again on a connection opened afterwards, so nothing is being
        // read out of one connection's own memory of a transaction.
        withDatabase { database ->
            assertEquals(piecesBefore, piecesOf(database), "a refused rollback rewrote the cell")
            assertEquals(
                ImportBatchStatus.CONFIRMED,
                assertNotNull(database.importDao().batchById(confirmedBatchId)).status,
                "a refused rollback moved the batch",
            )
            assertEquals(
                emptyList(),
                database.importDao().cellSnapshotsOfBatch(confirmedBatchId),
                "the refusal invented the very record it had refused for want of",
            )
            assertTrue(
                database.importDao().tasksOfConfirmedBatch(confirmedBatchId).all { it.deletedAt == null },
                "a refused rollback took a task out of view",
            )
            assertEquals(emptyList(), database.historyDao().allEvents(), "a refused rollback wrote to the history")
        }

        assertEquals(before, rowsOfEveryTable(), "a refused rollback changed how many rows a table holds")
    }

    @Test
    fun `an import confirmed after the walk can still be taken back`() {
        // The refusal is about this batch's own missing record and not about the
        // database having once been version 7: an import confirmed on the walked
        // up database records its cells and behaves like any other.
        createUsedVersion7Database()

        val stillWorks =
            withDatabase { database ->
                val importDao = database.importDao()
                val batch = anImportBatch(rawBlockCount = 1, sha256 = "2".repeat(64))
                importDao.insertBatch(batch)
                val block = aRawImportBlock(batch.id, rowIndex = 7, columnIndex = 2, sourceColumnType = SourceColumnType.CARD)
                importDao.insertRawBlock(block)
                importDao.setRawBlockProcessed(block.id, true, updatedAt)
                val draft = aDraftTask(block.id, name = "Yeni deste").copy(requiredQuantity = 4)
                importDao.addDraftTask(draft)
                importDao.setDraftTargetUnderReview(draft.id, cellId, PoolType.CARD, TrackingMode.PIPELINE, updatedAt)
                importDao.confirmDraftBatch(batch.id, true, StoppedClock(confirmedAt), IdGenerator.Random)

                rollbackOf(database).rollBack(batch.id)
            }

        assertEquals(1, stillWorks.removedTaskCount)
        assertEquals(1, stillWorks.restoredCellCount)
    }

    private companion object {
        val confirmedAt: Instant = Instant.fromEpochMilliseconds(1_781_000_000_000)

        /** Every table version 8 has, so a row lost anywhere shows up. */
        val TABLES =
            listOf(
                "games",
                "game_cells",
                "cell_segments",
                "colors",
                "color_aliases",
                "tasks",
                "task_colors",
                "task_stages",
                "progress_events",
                "history_events",
                "import_batches",
                "raw_import_blocks",
                "import_batch_cells",
                "draft_tasks",
                "draft_task_colors",
            )
    }
}
