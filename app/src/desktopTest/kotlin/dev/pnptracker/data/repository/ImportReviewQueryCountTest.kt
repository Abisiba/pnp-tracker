package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aRawImportBlock
import dev.pnptracker.data.database.anImportBatch
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
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the review screen costs to show and to summarise, counted at the driver.
 *
 * The screen re-reads both after every change the user makes, so a query per
 * draft would turn one decision into a pile of round trips — the shape PLAN 16
 * rules out. Writes are allowed to grow, because N drafts really are N rows;
 * what may not grow is the number of questions asked.
 *
 * Counted with a frequency map. A set would collapse forty-two runs of one
 * statement into one and report a cost that was never paid.
 */
class ImportReviewQueryCountTest {
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
            .map { it.trimStart().uppercase() }
            .filterNot { it.startsWith("BEGIN") || it.startsWith("COMMIT") || it.startsWith("END") }
            .filterNot { it.startsWith("ROLLBACK") || it.startsWith("SAVEPOINT") || it.startsWith("RELEASE") }
            .filterNot { it.startsWith("PRAGMA") }
            .groupingBy { statement ->
                when {
                    "CHANGES()" in statement || "LAST_INSERT_ROWID()" in statement -> "write result"
                    statement.startsWith("SELECT") && "FROM DRAFT_TASK_COLORS" in statement ->
                        "SELECT draft_task_colors"

                    statement.startsWith("SELECT") && "FROM DRAFT_TASKS" in statement -> "SELECT draft_tasks"
                    statement.startsWith("SELECT") && "FROM RAW_IMPORT_BLOCKS" in statement ->
                        "SELECT raw_import_blocks"

                    statement.startsWith("SELECT") && "FROM IMPORT_BATCHES" in statement -> "SELECT import_batches"
                    statement.startsWith("SELECT") && "FROM COLOR_ALIASES" in statement -> "SELECT color_aliases"
                    statement.startsWith("SELECT") && "FROM COLORS" in statement -> "SELECT colors"
                    statement.startsWith("SELECT") && "FROM GAMES" in statement -> "SELECT games"
                    statement.startsWith("SELECT") && "FROM GAME_CELLS" in statement -> "SELECT game_cells"
                    statement.startsWith("SELECT") && "FROM TASKS" in statement -> "SELECT tasks"
                    statement.startsWith("SELECT") -> "SELECT other"
                    statement.startsWith("INSERT") -> "INSERT other"
                    statement.startsWith("DELETE") -> "DELETE other"
                    statement.startsWith("UPDATE") -> "UPDATE other"
                    else -> "other"
                }
            }.eachCount()

    private fun decisions(counted: Map<String, Int>): Map<String, Int> = counted.filterKeys { it.startsWith("SELECT") }

    private fun reviewStore() =
        ImportReviewStore(
            importDao,
            database.gameDao(),
            database.colorDao(),
            IdGenerator.Random,
            StoppedClock(moment),
        )

    private fun confirmationStore() =
        ImportConfirmationStore(
            importDao,
            database.gameCellDao(),
            database.gameDao(),
            IdGenerator.Random,
            StoppedClock(moment),
        )

    /**
     * One import of [drafts] cells, each cut into one draft, each aimed at a cell
     * of its own game, each given a colour, and each carrying an answered marker.
     */
    private suspend fun aBatchOf(drafts: Int): Pair<EntityId, List<EntityId>> {
        val palette =
            database
                .colorDao()
                .allColors()
                .map { it.id }
        val batch = anImportBatch(rawBlockCount = drafts)
        importDao.insertBatch(batch)
        val ids =
            (0..<drafts).map { at ->
                val game = aGame(name = "Oyun $at")
                val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
                database.gameDao().insert(game)
                database.gameCellDao().insert(cell)

                val block =
                    aRawImportBlock(
                        batch.id,
                        rowIndex = at + 1,
                        columnIndex = 1,
                        rawText = "15 KIRMIZI**",
                        sourceColumnType = SourceColumnType.THREE_D,
                    )
                importDao.insertRawBlock(block)
                val draft =
                    importDao.createDraftFromSelectionUnderReview(
                        IdGenerator.Random.newId(),
                        block.id,
                        0,
                        12,
                        StoppedClock(moment),
                    )
                importDao.editDraftUnderReview(
                    draftTaskId = draft.id,
                    name = draft.name,
                    targetCellId = cell.id,
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = 15,
                    notes = null,
                    isMissing = false,
                    isBorrowed = false,
                    needsInfo = false,
                    needsClassification = false,
                    completionHint = HintDecision.REJECTED,
                    colorIds = listOf(palette[at % palette.size]),
                    clock = StoppedClock(moment),
                )
                draft.id
            }
        return batch.id to ids
    }

    private suspend fun workspaceReads(batchId: EntityId): Map<String, Int> {
        val store = reviewStore()
        driver.start()
        assertNotNull(store.observeWorkspace(batchId).first())
        return ran(driver.stop())
    }

    private suspend fun summaryReads(batchId: EntityId): Map<String, Int> {
        val store = confirmationStore()
        driver.start()
        assertNotNull(store.summarize(batchId))
        return ran(driver.stop())
    }

    // -------------------------------------------------------- the workspace

    @Test
    fun `showing an import of forty-two asks exactly what an import of one asks`() =
        runBlocking<Unit> {
            val (one, _) = aBatchOf(1)
            val (many, _) = aBatchOf(42)

            val forOne = workspaceReads(one)
            val forMany = workspaceReads(many)

            assertEquals(forOne, forMany, "the workspace asked more of a larger import")
            assertEquals(1, forOne["SELECT draft_task_colors"], "the colours of a whole import cost one read")
            assertEquals(1, forOne["SELECT draft_tasks"])
            assertEquals(1, forOne["SELECT raw_import_blocks"])
            assertEquals(1, forOne["SELECT import_batches"])
        }

    @Test
    fun `the catalogue and the words the detectors know cost one read each`() =
        runBlocking<Unit> {
            val store = reviewStore()
            aBatchOf(42)

            driver.start()
            assertNotNull(store.observeColorVocabulary().first())
            assertNotNull(store.observeColors().first())
            val counted = decisions(ran(driver.stop()))

            assertEquals(2, counted["SELECT colors"], "one for the vocabulary and one for the list")
            assertEquals(1, counted["SELECT color_aliases"])
            assertTrue(
                counted.keys.none { it == "SELECT other" },
                "the vocabulary asked something nobody accounted for: $counted",
            )
        }

    @Test
    fun `the games a green cell can name cost one read however many there are`() =
        runBlocking<Unit> {
            val store = reviewStore()
            aBatchOf(42)

            driver.start()
            assertEquals(42, store.observeActiveGames().first().size)
            val counted = decisions(ran(driver.stop()))

            assertEquals(mapOf("SELECT games" to 1), counted)
        }

    // ------------------------------------------------------- the summary

    @Test
    fun `summarising forty-two asks exactly what summarising one asks`() =
        runBlocking<Unit> {
            val (one, _) = aBatchOf(1)
            val (many, _) = aBatchOf(42)

            val forOne = decisions(summaryReads(one))
            val forMany = decisions(summaryReads(many))

            assertEquals(forOne, forMany, "the summary asked more of a larger import")
            assertEquals(1, forOne["SELECT draft_task_colors"])
            assertEquals(1, forOne["SELECT colors"], "the catalogue is read once, for the whole import")
        }

    @Test
    fun `summarising an import that names no game does not read the games at all`() =
        runBlocking<Unit> {
            val (batchId, _) = aBatchOf(3)

            val counted = decisions(summaryReads(batchId))

            assertEquals(null, counted["SELECT games"], "nothing was accepted, so nothing had to be looked up")
        }

    // -------------------------------------------------------- saving a draft

    @Test
    fun `saving one draft asks the same whether the import holds one or forty-two`() =
        runBlocking<Unit> {
            val (_, few) = aBatchOf(1)
            val (_, many) = aBatchOf(42)
            val palette =
                database
                    .colorDao()
                    .allColors()
                    .map { it.id }

            suspend fun saveOne(draftId: EntityId): Map<String, Int> {
                driver.start()
                importDao.editDraftUnderReview(
                    draftTaskId = draftId,
                    name = "Değişmiş ad",
                    targetCellId = assertNotNull(importDao.draftTaskById(draftId)).targetCellId,
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = 20,
                    notes = "not",
                    isMissing = false,
                    isBorrowed = false,
                    needsInfo = false,
                    needsClassification = false,
                    completionHint = HintDecision.REJECTED,
                    colorIds = listOf(palette[1], palette[2]),
                    clock = StoppedClock(moment),
                )
                return decisions(ran(driver.stop()))
            }

            assertEquals(saveOne(few.single()), saveOne(many.first()))
        }
}
