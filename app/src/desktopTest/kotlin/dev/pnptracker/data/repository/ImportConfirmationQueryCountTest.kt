package dev.pnptracker.data.repository

import androidx.room3.useWriterConnection
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aDraftTask
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aRawImportBlock
import dev.pnptracker.data.database.activePoolTasks
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
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
import kotlin.time.Instant

/**
 * What confirming an import costs, counted at the driver.
 *
 * The writes are allowed to grow: forty-two drafts are forty-two tasks, and that
 * is the work itself. What may not grow is the number of questions asked to
 * decide — PLAN 16 rules out the shape where a batch of forty-two costs
 * forty-two round trips, and it is exactly the shape this transaction used to
 * have: eighty-five reads of `game_cells` and forty-two of `cell_segments` for a
 * single confirmation.
 *
 * Everything is counted with a frequency map. A set would collapse forty-two
 * runs of one statement into one and report a cost that was never paid.
 */
class ImportConfirmationQueryCountTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_780_000_000_000)

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
                    // What a write hands back, not a question anybody asked.
                    "CHANGES()" in statement || "LAST_INSERT_ROWID()" in statement -> "write result"
                    statement.startsWith("SELECT") && "FROM DRAFT_TASK_COLORS" in statement -> "SELECT draft_task_colors"
                    statement.startsWith("SELECT") && "FROM DRAFT_TASKS" in statement -> "SELECT draft_tasks"
                    statement.startsWith("SELECT") && "FROM RAW_IMPORT_BLOCKS" in statement -> "SELECT raw_import_blocks"
                    statement.startsWith("SELECT") && "FROM IMPORT_BATCH_CELLS" in statement -> "SELECT import_batch_cells"
                    statement.startsWith("SELECT") && "FROM IMPORT_BATCHES" in statement -> "SELECT import_batches"
                    statement.startsWith("SELECT") && "FROM COLORS" in statement -> "SELECT colors"
                    statement.startsWith("SELECT") && "FROM GAMES" in statement -> "SELECT games"
                    statement.startsWith("SELECT") && "FROM GAME_CELLS" in statement -> "SELECT game_cells"
                    statement.startsWith("SELECT") && "FROM CELL_SEGMENTS" in statement -> "SELECT cell_segments"
                    statement.startsWith("SELECT") && "FROM TASK_COLORS" in statement -> "SELECT task_colors"
                    statement.startsWith("SELECT") && "FROM TASK_STAGES" in statement -> "SELECT task_stages"
                    statement.startsWith("SELECT") && "FROM TASKS" in statement -> "SELECT tasks"
                    statement.startsWith("SELECT") -> "SELECT other"
                    statement.startsWith("INSERT") && "`TASKS`" in statement -> "INSERT tasks"
                    statement.startsWith("INSERT") && "`TASK_COLORS`" in statement -> "INSERT task_colors"
                    statement.startsWith("INSERT") && "`TASK_STAGES`" in statement -> "INSERT task_stages"
                    statement.startsWith("INSERT") && "`CELL_SEGMENTS`" in statement -> "INSERT cell_segments"
                    statement.startsWith("INSERT") && "`IMPORT_BATCH_CELLS`" in statement -> "INSERT import_batch_cells"
                    statement.startsWith("INSERT") -> "INSERT other"
                    statement.startsWith("UPDATE") && "DRAFT_TASKS" in statement -> "UPDATE draft_tasks"
                    statement.startsWith("UPDATE") && "IMPORT_BATCHES" in statement -> "UPDATE import_batches"
                    statement.startsWith("UPDATE") && "GAMES" in statement -> "UPDATE games"
                    statement.startsWith("UPDATE") -> "UPDATE other"
                    else -> "other"
                }
            }.eachCount()

    /** Every question a transaction asked, whatever table it asked it of. */
    private fun decisions(counted: Map<String, Int>): Map<String, Int> = counted.filterKeys { it.startsWith("SELECT") }

    private var madeColors = 0

    private suspend fun extraColors(count: Int): List<EntityId> =
        (0..<count).map {
            val at = madeColors++
            val color =
                ColorEntity(IdGenerator.Random.newId(), "Ton $at", "ton $at", "#00" + "%04X".format(at), 100 + at)
            database.colorDao().insert(color)
            color.id
        }

    private var batches = 0

    /** One import, shaped by what the measurement is about. */
    private suspend fun aBatch(
        drafts: Int,
        oneCell: Boolean = true,
        poolType: PoolType = PoolType.THREE_D,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        completionHint: HintDecision = HintDecision.NONE,
        colorIds: List<EntityId> = emptyList(),
        greenHints: Int = 0,
        oneGame: Boolean = true,
    ): EntityId {
        val batch = anImportBatch(rawBlockCount = drafts, sha256 = "%064x".format(batches++))
        importDao.insertBatch(batch)
        val game = aGame(name = "Oyun ${IdGenerator.Random.newId()}")
        val cell = aCell(gameId = game.id, columnType = CellColumnType.of(poolType))
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        repeat(drafts) { at ->
            val targetCellId =
                if (oneCell) {
                    cell.id
                } else {
                    val other = aGame(name = "Oyun $at ${IdGenerator.Random.newId()}")
                    val otherCell = aCell(gameId = other.id, columnType = CellColumnType.of(poolType))
                    database.gameDao().insert(other)
                    database.gameCellDao().insert(otherCell)
                    otherCell.id
                }
            val block =
                aRawImportBlock(batch.id, rowIndex = at + 1, columnIndex = 1, sourceColumnType = SourceColumnType.THREE_D)
            importDao.insertRawBlock(block)
            importDao.setRawBlockProcessed(block.id, true, updatedAt)
            val draft =
                aDraftTask(block.id, name = "Token $at").copy(requiredQuantity = 20, completionHint = completionHint)
            importDao.addDraftTask(draft)
            importDao.setDraftTargetUnderReview(draft.id, targetCellId, poolType, trackingMode, updatedAt)
            if (colorIds.isNotEmpty()) importDao.setDraftColorsUnderReview(draft.id, colorIds, StoppedClock(updatedAt))
        }
        repeat(greenHints) { at ->
            val target =
                if (oneGame) {
                    game.id
                } else {
                    val other = aGame(name = "Hedef $at ${IdGenerator.Random.newId()}")
                    database.gameDao().insert(other)
                    other.id
                }
            val block =
                aRawImportBlock(batch.id, rowIndex = 500 + at, columnIndex = 0, sourceColumnType = SourceColumnType.GAME)
            importDao.insertRawBlock(block)
            importDao.setRawBlockProcessed(block.id, true, updatedAt)
            importDao.setGameCompletionDecisionUnderReview(
                block.id,
                HintDecision.ACCEPTED,
                target,
                StoppedClock(updatedAt),
            )
        }
        return batch.id
    }

    private suspend fun confirm(batchId: EntityId): Map<String, Int> {
        driver.start()
        importDao.confirmDraftBatch(
            batchId,
            acknowledgeUnprocessedBlocks = true,
            clock = StoppedClock(moment),
            idGenerator = IdGenerator.Random,
        )
        return ran(driver.stop())
    }

    @Test
    fun `a batch of forty-two asks exactly what a batch of one asks`() =
        runBlocking<Unit> {
            val one = confirm(aBatch(1))
            val many = confirm(aBatch(42))

            assertEquals(decisions(one), decisions(many), "confirming forty-two drafts asked more questions than one")
            // The shape the old transaction had, named so a regression is
            // recognisable rather than merely numerically different.
            assertEquals(1, many["SELECT game_cells"], "the per-draft cell lookup is back")
            assertEquals(1, many["SELECT cell_segments"], "the per-draft segment lookup is back")
            // The writing grows, because the writing is the work: forty-two tasks
            // and the forty-one spaces that keep their names apart.
            assertEquals(42, many["INSERT tasks"])
            assertEquals(83, many["INSERT cell_segments"])
            // What the cells said is kept once each, not once per draft: PLAN
            // 11.4.4 records a cell, and forty-two drafts aiming at one cell
            // are one cell.
            assertEquals(1, many["INSERT import_batch_cells"], "one cell was recorded once per draft")
            assertEquals(1, many["SELECT import_batch_cells"], "the records were read back per cell")
            assertEquals(42, many["UPDATE draft_tasks"])
            assertEquals(1, many["UPDATE import_batches"])
        }

    @Test
    fun `forty-two drafts aimed at forty-two different cells ask the same again`() =
        runBlocking<Unit> {
            val one = confirm(aBatch(1))
            val spread = confirm(aBatch(42, oneCell = false))

            assertEquals(decisions(one), decisions(spread), "aiming at many cells asked a question per cell")
            assertEquals(1, spread["SELECT game_cells"])
            assertEquals(1, spread["SELECT cell_segments"])
            // Forty-two cells really are forty-two records — that is the work,
            // not a question asked about each of them.
            assertEquals(42, spread["INSERT import_batch_cells"])
            assertEquals(1, spread["SELECT import_batch_cells"], "reading the records back grew with the cells")
        }

    @Test
    fun `colours cost one catalogue read however many there are`() =
        runBlocking<Unit> {
            val few = confirm(aBatch(1, colorIds = extraColors(2)))
            val many = confirm(aBatch(1, colorIds = extraColors(42)))

            assertEquals(decisions(few), decisions(many), "a longer colour list cost another question")
            assertEquals(1, many["SELECT colors"], "the catalogue was read more than once")
            assertEquals(42, many["INSERT task_colors"])
        }

    @Test
    fun `a batch of card tasks asks the same and writes its pipelines`() =
        runBlocking<Unit> {
            val one = confirm(aBatch(1))
            val cards = confirm(aBatch(42, poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE))

            assertEquals(decisions(one), decisions(cards))
            assertEquals(126, cards["INSERT task_stages"], "forty-two card tasks are forty-two pipelines")
        }

    @Test
    fun `forty-two accepted markers ask the same and write no history`() =
        runBlocking<Unit> {
            val plain = confirm(aBatch(1))
            val finished = confirm(aBatch(42, completionHint = HintDecision.ACCEPTED))

            assertEquals(decisions(plain), decisions(finished))
            assertEquals(null, finished["INSERT other"], "a task born finished wrote an event explaining a debt")
        }

    @Test
    fun `forty-two green cells naming one game write once`() =
        runBlocking<Unit> {
            val counted = confirm(aBatch(1, greenHints = 42))

            assertEquals(1, counted["UPDATE games"], "one game was finished more than once")
            assertEquals(2, counted["SELECT games"], "the games were asked about per hint")
        }

    @Test
    fun `forty-two green cells naming forty-two games ask the same and write each once`() =
        runBlocking<Unit> {
            val one = confirm(aBatch(1, greenHints = 1))
            val many = confirm(aBatch(1, greenHints = 42, oneGame = false))

            assertEquals(decisions(one), decisions(many), "the games were asked about per hint")
            assertEquals(42, many["UPDATE games"])
        }

    @Test
    fun `the game table still reads in four queries after an import`() =
        runBlocking<Unit> {
            val batchId = aBatch(6)
            importDao.confirmDraftBatch(batchId, true, StoppedClock(moment), IdGenerator.Random)
            val table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao())

            driver.start()
            assertNotNull(table.observeTable().first())
            val counted = ran(driver.stop())

            assertEquals(4, decisions(counted).values.sum(), "the game table stopped reading in four queries")
        }

    @Test
    fun `a pool still reads in the same few queries after an import`() =
        runBlocking<Unit> {
            val batchId = aBatch(6, colorIds = extraColors(2))
            importDao.confirmDraftBatch(batchId, true, StoppedClock(moment), IdGenerator.Random)
            val pools = PoolStore(database.poolDao())

            driver.start()
            assertNotNull(pools.observePool(PoolType.THREE_D).first())
            val counted = ran(driver.stop())

            assertEquals(
                mapOf("SELECT tasks" to 1, "SELECT task_colors" to 1, "SELECT progress_events" to 1),
                decisions(counted).mapKeys { (key, _) -> if (key == "SELECT other") "SELECT progress_events" else key },
                "a pool started asking more than one question per kind",
            )
        }

    @Test
    fun `a pool holding work that waits on information still reads the same`() =
        runBlocking<Unit> {
            val pools = PoolStore(database.poolDao())
            val batchId = aBatch(42)
            importDao.confirmDraftBatch(batchId, true, StoppedClock(moment), IdGenerator.Random)

            driver.start()
            assertNotNull(pools.observePool(PoolType.THREE_D).first())
            val before = decisions(ran(driver.stop()))

            markEverythingAsWaiting()
            // A raw write fires Room's invalidation triggers, and the refresh
            // that follows can re-run a query the moment after it was created.
            // One reading is taken to let that settle, so what is measured next
            // is the cost of showing the pool rather than the cost of the write
            // that happened to precede it.
            assertNotNull(pools.observePool(PoolType.THREE_D).first())

            driver.start()
            val snapshot = assertNotNull(pools.observePool(PoolType.THREE_D).first())
            val after = decisions(ran(driver.stop()))

            assertEquals(emptyList(), activePoolTasks(snapshot), "work waiting on information stayed on the active list")
            assertEquals(before, after, "the new filter cost another query")
        }

    private suspend fun markEverythingAsWaiting() {
        database.useWriterConnection { transactor ->
            transactor.usePrepared("UPDATE tasks SET needs_info = 1") { statement -> statement.step() }
        }
    }
}
