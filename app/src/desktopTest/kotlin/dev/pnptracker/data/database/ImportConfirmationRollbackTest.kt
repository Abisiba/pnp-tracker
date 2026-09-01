package dev.pnptracker.data.database

import androidx.room3.useWriterConnection
import dev.pnptracker.data.repository.ImportConfirmationStore
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
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
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What is left behind when a confirmation cannot finish.
 *
 * PLAN 11.4.2 allows exactly two outcomes: the whole import becomes real work,
 * or nothing does — no task, no colour, no pipeline, no piece of a cell, no
 * game finished, no counter moved, and the batch still a draft. A half written
 * import would leave the user with rows they never finished describing and no
 * way to tell which ones.
 *
 * Every failure below is made where the transaction really writes, against a
 * real SQLite file. A fake that threw before the DAO was reached would prove
 * only that a test can throw.
 */
class ImportConfirmationRollbackTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val counting = CountingSqliteDriver()
    private val failing = FailingSqliteDriver(counting)
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_780_000_000_000)

    /** Hands out names, and refuses after the [afterwards]-th one. */
    private class LimitedIdGenerator(
        private val afterwards: Int,
    ) : IdGenerator {
        var handed: Int = 0
            private set

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

    /** Everything a confirmation could disturb, read as whole rows. */
    private data class Everything(
        val tasks: List<Any?>,
        val colors: List<Any?>,
        val stages: List<Any?>,
        val segments: List<Any?>,
        val games: List<Any?>,
        val batches: List<Any?>,
        val drafts: List<Any?>,
        val draftColors: List<Any?>,
        val events: List<Any?>,
    )

    private suspend fun everything(): Everything =
        Everything(
            tasks = database.taskDao().allTasksIncludingDeleted(),
            colors = rowsOf("SELECT task_id, color_id, slot_index FROM task_colors ORDER BY task_id, slot_index"),
            stages = rowsOf("SELECT task_id, stage, completed_quantity FROM task_stages ORDER BY task_id, order_index"),
            segments = rowsOf("SELECT id, cell_id, order_index, kind, text, task_id FROM cell_segments ORDER BY cell_id, order_index"),
            games = database.gameDao().allGamesIncludingDeleted(),
            batches = importDao.allBatches(),
            drafts = importDao.draftTasksOfBatch(batchId),
            draftColors = importDao.draftColorsOfBatch(batchId),
            events = rowsOf("SELECT id, task_id, kind, quantity FROM progress_events ORDER BY id"),
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

    private var batch: EntityId? = null
    private var game: EntityId? = null
    private var otherGame: EntityId? = null
    private var cell: EntityId? = null

    private val batchId get() = assertNotNull(batch)
    private val gameId get() = assertNotNull(game)
    private val otherGameId get() = assertNotNull(otherGame)
    private val cellId get() = assertNotNull(cell)

    /**
     * Two games, one cell, four card drafts with colours, an accepted marker and
     * two accepted green cells — so a single confirmation has something of every
     * kind to write and every trap below has somewhere to land.
     */
    private suspend fun given(drafts: Int = 4): List<EntityId> {
        val game = aGame(name = "Harmonies")
        val other = aGame(name = "Wingspan")
        val cell = aCell(gameId = game.id, columnType = CellColumnType.CARD)
        database.gameDao().insert(game)
        database.gameDao().insert(other)
        database.gameCellDao().insert(cell)
        // A cell somewhere else too, so deleting this game leaves the
        // application with somewhere a task could have gone: the refusal then
        // really is about this target rather than about there being no cells.
        database.gameCellDao().insert(aCell(gameId = other.id, columnType = CellColumnType.CARD))
        this.game = game.id
        this.otherGame = other.id
        this.cell = cell.id

        val batch = anImportBatch(rawBlockCount = drafts)
        importDao.insertBatch(batch)
        this.batch = batch.id
        val palette = listOf("Gri", "Mavi").map { assertNotNull(database.colorDao().resolve(it)).id }
        val ids =
            (0..<drafts).map { at ->
                val block =
                    aRawImportBlock(
                        batch.id,
                        rowIndex = at + 1,
                        columnIndex = 2,
                        rawText = "15 KIRMIZI $at",
                        sourceColumnType = SourceColumnType.CARD,
                    )
                importDao.insertRawBlock(block)
                importDao.setRawBlockProcessed(block.id, true, updatedAt)
                val draft =
                    aDraftTask(block.id, name = "Deste $at").copy(
                        requiredQuantity = 20,
                        completionHint = if (at == 0) HintDecision.ACCEPTED else HintDecision.NONE,
                    )
                importDao.addDraftTask(draft)
                importDao.setDraftTargetUnderReview(draft.id, cell.id, PoolType.CARD, TrackingMode.PIPELINE, updatedAt)
                importDao.setDraftColorsUnderReview(draft.id, palette, StoppedClock(updatedAt))
                draft.id
            }
        listOf(game.id, other.id).forEachIndexed { at, target ->
            val block =
                aRawImportBlock(
                    batch.id,
                    rowIndex = 500 + at,
                    columnIndex = 0,
                    rawText = "Oyun $at",
                    sourceColumnType = SourceColumnType.GAME,
                )
            importDao.insertRawBlock(block)
            importDao.setRawBlockProcessed(block.id, true, updatedAt)
            importDao.setGameCompletionDecisionUnderReview(block.id, HintDecision.ACCEPTED, target, StoppedClock(updatedAt))
        }
        return ids
    }

    private suspend fun confirm(idGenerator: IdGenerator = IdGenerator.Random): Int =
        importDao.confirmDraftBatch(batchId, acknowledgeUnprocessedBlocks = true, moment = moment, idGenerator = idGenerator)

    /** Arms [trap], confirms, and proves the database is exactly as it was. */
    private suspend fun refusedLeavesEverything(
        trap: () -> Unit,
        idGenerator: IdGenerator = IdGenerator.Random,
    ) {
        val before = everything()
        trap()

        assertFailsWith<Throwable> { confirm(idGenerator) }
        failing.disarm()

        assertEquals(before, everything(), "a refused confirmation left something behind")
        val batch = assertNotNull(importDao.batchById(batchId))
        assertEquals(ImportBatchStatus.DRAFT, batch.status)
        assertEquals(0, batch.createdTaskCount)
        assertEquals(0, batch.createdGameCount)
        assertTrue(importDao.draftTasksOfBatch(batchId).all { it.materializedTaskId == null })
        assertFalse(assertNotNull(database.gameDao().activeGameById(gameId)).isManuallyCompleted)
        assertFalse(assertNotNull(database.gameDao().activeGameById(otherGameId)).isManuallyCompleted)
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

    // ------------------------------------------------------- the injections

    @Test
    fun `the second task refuses to go in`() =
        runBlocking {
            given()
            refusedLeavesEverything(trapOn(2, "INSERT", "`TASKS`"))
        }

    @Test
    fun `the last task refuses to go in`() =
        runBlocking {
            given()
            refusedLeavesEverything(trapOn(4, "INSERT", "`TASKS`"))
        }

    @Test
    fun `the first colour refuses to go in`() =
        runBlocking {
            given()
            refusedLeavesEverything(trapOn(1, "INSERT", "TASK_COLORS"))
        }

    @Test
    fun `the last colour refuses to go in`() =
        runBlocking {
            given()
            refusedLeavesEverything(trapOn(8, "INSERT", "TASK_COLORS"))
        }

    @Test
    fun `a stage refuses to go in`() =
        runBlocking {
            given()
            refusedLeavesEverything(trapOn(5, "INSERT", "TASK_STAGES"))
        }

    @Test
    fun `a piece of the cell refuses to go in`() =
        runBlocking {
            given()
            refusedLeavesEverything(trapOn(3, "INSERT", "CELL_SEGMENTS"))
        }

    @Test
    fun `linking the second draft to its task refuses`() =
        runBlocking {
            given()
            refusedLeavesEverything(trapOn(2, "UPDATE", "MATERIALIZED_TASK_ID"))
        }

    @Test
    fun `linking the last draft to its task refuses`() =
        runBlocking {
            given()
            refusedLeavesEverything(trapOn(4, "UPDATE", "MATERIALIZED_TASK_ID"))
        }

    @Test
    fun `finishing the first game refuses`() =
        runBlocking {
            given()
            refusedLeavesEverything(trapOn(1, "UPDATE", "IS_MANUALLY_COMPLETED"))
        }

    @Test
    fun `finishing the second game refuses`() =
        runBlocking {
            given()
            refusedLeavesEverything(trapOn(2, "UPDATE", "IS_MANUALLY_COMPLETED"))
        }

    @Test
    fun `marking the batch confirmed refuses`() =
        runBlocking {
            given()
            refusedLeavesEverything(trapOn(1, "UPDATE", "IMPORT_BATCHES"))
        }

    @Test
    fun `a name runs out before the second task and nothing is written`() =
        runBlocking {
            given()
            // Names are made in pairs, a task and its piece of the cell, and all
            // of them before the first insert — so running out costs no rows.
            refusedLeavesEverything({ }, LimitedIdGenerator(afterwards = 3))
        }

    @Test
    fun `a name runs out while naming the last piece of a cell`() =
        runBlocking {
            given()
            refusedLeavesEverything({ }, LimitedIdGenerator(afterwards = 7))
        }

    @Test
    fun `a target game deleted after the review takes everything back`() =
        runBlocking {
            given()
            val before = everything()
            database.gameDao().softDelete(otherGameId, deletedAt)

            val failure = assertFailsWith<ImportConfirmationException> { confirm() }

            assertEquals(ImportConfirmationFailure.COMPLETION_TARGET_GAME_NOT_AVAILABLE, failure.failure)
            assertEquals(before.tasks, everything().tasks)
            assertEquals(before.segments, everything().segments)
            assertEquals(ImportBatchStatus.DRAFT, assertNotNull(importDao.batchById(batchId)).status)
        }

    @Test
    fun `a target cell whose game is deleted takes everything back`() =
        runBlocking {
            given()
            val before = everything()
            database.gameDao().softDelete(gameId, deletedAt)

            val failure = assertFailsWith<ImportConfirmationException> { confirm() }

            assertEquals(ImportConfirmationFailure.TARGET_CELL_NOT_AVAILABLE, failure.failure)
            assertEquals(before.tasks, everything().tasks)
            assertEquals(before.segments, everything().segments)
        }

    @Test
    fun `a document already numbered with a gap is refused rather than renumbered`() =
        runBlocking {
            given()
            // A cell whose pieces jump from nothing to two: writing into it would
            // either collide or widen the gap, and renumbering it would be
            // rewriting somebody's document behind their back.
            insertSegmentDirectly(
                database,
                dev.pnptracker.data.database.entity.CellSegmentEntity.plainText(
                    id = IdGenerator.Random.newId(),
                    cellId = cellId,
                    orderIndex = 2,
                    text = "Elle yazılmış",
                    moment = updatedAt,
                ),
            )
            val before = everything()

            assertFailsWith<IllegalStateException> { confirm() }

            assertEquals(before.tasks, everything().tasks)
            assertEquals(before.segments, everything().segments, "the document was renumbered")
            assertEquals(ImportBatchStatus.DRAFT, assertNotNull(importDao.batchById(batchId)).status)
        }

    @Test
    fun `a clock that will not answer leaves the import untouched`() =
        runBlocking {
            given()
            val before = everything()
            val store =
                ImportConfirmationStore(
                    importDao = importDao,
                    gameCellDao = database.gameCellDao(),
                    gameDao = database.gameDao(),
                    idGenerator = IdGenerator.Random,
                    clock = BrokenClock(),
                )

            assertFailsWith<IllegalStateException> { store.confirm(batchId, acknowledgeUnprocessedBlocks = true) }

            assertEquals(before, everything())
        }

    @Test
    fun `a clean retry after a refusal writes everything exactly once`() =
        runBlocking {
            val drafts = given()
            failing.failOn(2) { it.uppercase().trimStart().startsWith("INSERT") && "`TASKS`" in it.uppercase() }
            assertFailsWith<Throwable> { confirm() }
            failing.disarm()

            assertEquals(drafts.size, confirm())

            assertEquals(drafts.size, database.taskDao().activeTasks().size)
            assertEquals(drafts.size, database.cellSegmentDao().segmentCountOfCell(cellId))
            assertEquals(drafts.size * 2, CommittedSchema.countRowsOf(directory.databaseFile, "task_colors"))
            assertEquals(drafts.size * 3, CommittedSchema.countRowsOf(directory.databaseFile, "task_stages"))
            assertEquals(ImportBatchStatus.CONFIRMED, assertNotNull(importDao.batchById(batchId)).status)
            assertTrue(database.gameDao().allGamesIncludingDeleted().all { it.isManuallyCompleted })
            assertTrue(importDao.draftTasksOfBatch(batchId).all { it.materializedTaskId != null })
        }
}
