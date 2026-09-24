package dev.pnptracker.data.database

import androidx.room3.useWriterConnection
import dev.pnptracker.data.repository.confirmationStore
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

    /** Hands out names, but repeats the [reuse]-th one when the [at]-th is asked for. */
    private class RepeatingIdGenerator(
        private val reuse: Int,
        private val at: Int,
    ) : IdGenerator {
        private var handed = 0
        private var kept: EntityId? = null

        override fun newId(): EntityId {
            handed++
            if (handed == at) return assertNotNull(kept)
            return IdGenerator.Random.newId().also { if (handed == reuse) kept = it }
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
        val snapshots: List<Any?>,
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
            snapshots =
                rowsOf(
                    "SELECT import_batch_id, cell_id, length(document_before) FROM import_batch_cells " +
                        "ORDER BY import_batch_id, cell_id",
                ),
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
    private var otherCell: EntityId? = null
    private var printed: EntityId? = null

    private val batchId get() = assertNotNull(batch)
    private val gameId get() = assertNotNull(game)
    private val otherGameId get() = assertNotNull(otherGame)
    private val cellId get() = assertNotNull(cell)
    private val otherCellId get() = assertNotNull(otherCell)

    /**
     * Two games, two cells, four card drafts and one printing draft in colours,
     * an accepted marker and two accepted green cells — so a single confirmation
     * has something of every kind to write and every trap below has somewhere to
     * land.
     *
     * The colours are on the printing draft and nowhere else: PLAN 5.10 gives
     * them to that pool alone, so a card draft in colours is not a state to build
     * a fixture out of. The cards keep the pipelines, which the printing draft
     * has none of, and between them every kind of row a confirmation writes is
     * covered.
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
        val elsewhere = aCell(gameId = other.id, columnType = CellColumnType.CARD)
        database.gameCellDao().insert(elsewhere)
        this.otherCell = elsewhere.id
        this.game = game.id
        this.otherGame = other.id
        this.cell = cell.id

        // One raw cell per draft and the two green game cells below. A count
        // that left those two out would be a draft whose records contradict
        // each other (PLAN 11.4.5, D3), and the store refuses to confirm one.
        val batch = anImportBatch(rawBlockCount = drafts + 3)
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
                draft.id
            }
        // The one draft that is printed, and therefore the one with colours.
        val printedCell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
        database.gameCellDao().insert(printedCell)
        this.printed = printedCell.id
        val printedBlock =
            aRawImportBlock(
                batch.id,
                rowIndex = 400,
                columnIndex = 1,
                rawText = "15 GRİ",
                sourceColumnType = SourceColumnType.THREE_D,
            )
        importDao.insertRawBlock(printedBlock)
        importDao.setRawBlockProcessed(printedBlock.id, true, updatedAt)
        // Made after the cards, so the confirmation always reaches it last: the
        // drafts are read in the order they were created, and a fixture that
        // shuffled them would move every identity the traps below count on.
        val printedDraft =
            aDraftTask(printedBlock.id, name = "Gri figür").copy(
                requiredQuantity = 15,
                createdAt = updatedAt,
                updatedAt = updatedAt,
            )
        importDao.addDraftTask(printedDraft)
        importDao.setDraftTargetUnderReview(
            printedDraft.id,
            printedCell.id,
            PoolType.THREE_D,
            TrackingMode.THREE_D_BATCH,
            updatedAt,
        )
        importDao.setDraftColorsUnderReview(printedDraft.id, palette, StoppedClock(updatedAt))
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
        importDao.confirmDraftBatch(batchId, acknowledgeUnprocessedBlocks = true, clock = StoppedClock(moment), idGenerator = idGenerator)

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
    fun `keeping what the cell said refuses to go in`() =
        runBlocking {
            given()
            // The record is written before the first task, so a refusal here is
            // the earliest point the transaction can fail with something already
            // attempted — and it must still leave nothing behind.
            refusedLeavesEverything(trapOn(1, "INSERT", "IMPORT_BATCH_CELLS"))
        }

    @Test
    fun `keeping the second cell refuses, and the first cell is not kept either`() =
        runBlocking {
            val drafts = given()
            // Two cells, so the record is written twice and can fail between
            // them. What must not survive is a batch that remembers one of the
            // cells it wrote into: PLAN 11.4.4 reads the absence of a record as
            // "nothing is known", and a half kept batch would read as a fully
            // known one.
            importDao.setDraftTargetUnderReview(
                drafts.last(),
                otherCellId,
                PoolType.CARD,
                TrackingMode.PIPELINE,
                updatedAt,
            )
            refusedLeavesEverything(trapOn(2, "INSERT", "IMPORT_BATCH_CELLS"))
        }

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
            // The second of the two the printed draft is made in; a card draft
            // has none at all (PLAN 5.10).
            given()
            refusedLeavesEverything(trapOn(2, "INSERT", "TASK_COLORS"))
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
            // A task, its piece of the cell and the space in front of it are all
            // named before the first insert — so running out costs no rows.
            refusedLeavesEverything({ }, LimitedIdGenerator(afterwards = 3))
        }

    @Test
    fun `a name runs out while naming the last piece of a cell`() =
        runBlocking {
            given()
            refusedLeavesEverything({ }, LimitedIdGenerator(afterwards = 7))
        }

    @Test
    fun `a name runs out on the space between two tasks and nothing is written`() =
        runBlocking {
            given()
            // Two names for the first draft, which needs no space in front of it,
            // then a task and a piece for the second — and the separator's name
            // is the one that cannot be had. The space is named with everything
            // else, before the first insert, so this costs no rows either.
            refusedLeavesEverything({ }, LimitedIdGenerator(afterwards = 4))
        }

    @Test
    fun `the space in front of a task refuses to go in`() =
        runBlocking {
            given()
            // The first separator is the second piece of the cell to be written:
            // the first draft's task piece goes in before it.
            refusedLeavesEverything(trapOn(2, "INSERT", "`CELL_SEGMENTS`"))
        }

    @Test
    fun `a separator that collides with a piece already there takes everything back`() =
        runBlocking {
            val drafts = given()
            val before = everything()
            // A real constraint rather than an injected fault. Names are handed
            // out task, piece, space — so the second draft's space is the fifth,
            // and it is given the identity the first draft's piece already went
            // in under. SQLite refuses it, and everything written before it goes
            // with it.
            assertFailsWith<Throwable> { confirm(RepeatingIdGenerator(reuse = 2, at = 5)) }

            assertEquals(before, everything(), "a collided separator left something behind")
            assertEquals(ImportBatchStatus.DRAFT, assertNotNull(importDao.batchById(batchId)).status)
            assertTrue(importDao.draftTasksOfBatch(batchId).all { it.materializedTaskId == null })
            assertEquals(drafts.size + 1, importDao.draftTasksOfBatch(batchId).size)
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
            val store = confirmationStore(database, importDao, clock = BrokenClock())

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

            // The card drafts and the printed one.
            assertEquals(drafts.size + 1, confirm())

            assertEquals(drafts.size + 1, database.taskDao().activeTasks().size)
            // Four tasks and the three spaces between them, written once each.
            assertEquals(drafts.size * 2 - 1, database.cellSegmentDao().segmentCountOfCell(cellId))
            assertEquals(drafts.size - 1, database.cellSegmentDao().segmentsOfCell(cellId).count { it.text == " " })
            // Two colours, both on the one task that is printed (PLAN 5.10).
            assertEquals(2, CommittedSchema.countRowsOf(directory.databaseFile, "task_colors"))
            assertEquals(drafts.size * 3, CommittedSchema.countRowsOf(directory.databaseFile, "task_stages"))
            assertEquals(ImportBatchStatus.CONFIRMED, assertNotNull(importDao.batchById(batchId)).status)
            assertTrue(database.gameDao().allGamesIncludingDeleted().all { it.isManuallyCompleted })
            assertTrue(importDao.draftTasksOfBatch(batchId).all { it.materializedTaskId != null })
        }
}
