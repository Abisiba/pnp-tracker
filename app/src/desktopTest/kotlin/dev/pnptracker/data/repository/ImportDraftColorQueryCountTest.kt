package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aDraftTask
import dev.pnptracker.data.database.aRawImportBlock
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.SourceColumnType
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
 * What showing and changing a draft's colours costs, counted at the driver.
 *
 * The writes are allowed to grow: N colours are N rows and that is the work
 * itself. What may not grow is the number of questions asked — the review screen
 * re-reads its workspace after every change the user makes, so a query per draft
 * or per colour would turn one decision into a pile of round trips. PLAN 16
 * rules that shape out.
 *
 * Everything is counted with a frequency map. A set would collapse forty-two
 * runs of one statement into one and report a cost that was never paid.
 */
class ImportDraftColorQueryCountTest {
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
                    // What a write hands back rather than a question anybody
                    // asked: Room reads these to learn how many rows it moved.
                    "CHANGES()" in statement || "LAST_INSERT_ROWID()" in statement -> "write result"
                    statement.startsWith("SELECT") && "FROM DRAFT_TASK_COLORS" in statement -> "SELECT draft_task_colors"
                    statement.startsWith("SELECT") && "FROM DRAFT_TASKS" in statement -> "SELECT draft_tasks"
                    statement.startsWith("SELECT") && "FROM RAW_IMPORT_BLOCKS" in statement -> "SELECT raw_import_blocks"
                    statement.startsWith("SELECT") && "FROM IMPORT_BATCHES" in statement -> "SELECT import_batches"
                    statement.startsWith("SELECT") && "FROM COLORS" in statement -> "SELECT colors"
                    statement.startsWith("SELECT") && "FROM GAMES" in statement -> "SELECT games"
                    statement.startsWith("SELECT") -> "SELECT other"
                    statement.startsWith("INSERT") && "DRAFT_TASK_COLORS" in statement -> "INSERT draft_task_colors"
                    statement.startsWith("INSERT") -> "INSERT other"
                    statement.startsWith("DELETE") && "DRAFT_TASK_COLORS" in statement -> "DELETE draft_task_colors"
                    statement.startsWith("UPDATE") && "DRAFT_TASKS" in statement -> "UPDATE draft_tasks"
                    statement.startsWith("UPDATE") -> "UPDATE other"
                    else -> "other"
                }
            }.eachCount()

    private fun decisions(counted: Map<String, Int>): Map<String, Int> = counted.filterKeys { it.startsWith("SELECT") }

    private var madeColors = 0

    /** More colours than the twelve seeds, so a draft can be given forty-two. */
    private suspend fun extraColors(count: Int): List<EntityId> =
        (0..<count).map {
            val at = madeColors++
            val color =
                ColorEntity(
                    id = IdGenerator.Random.newId(),
                    canonicalName = "Ton $at",
                    normalizedName = "ton $at",
                    hex = "#00" + "%04X".format(at),
                    sortOrder = 100 + at,
                )
            database.colorDao().insert(color)
            color.id
        }

    /** One import with [drafts] drafts, each on a cell of its own. */
    private suspend fun aBatchOf(drafts: Int): Pair<EntityId, List<EntityId>> {
        val batch = anImportBatch(rawBlockCount = drafts)
        importDao.insertBatch(batch)
        val ids =
            (0..<drafts).map { at ->
                val block =
                    aRawImportBlock(
                        batch.id,
                        rowIndex = at + 1,
                        columnIndex = 1,
                        rawText = "15 KIRMIZI $at",
                        sourceColumnType = SourceColumnType.THREE_D,
                    )
                importDao.insertRawBlock(block)
                val draft = aDraftTask(block.id, name = "Token $at")
                importDao.addDraftTask(draft)
                draft.id
            }
        return batch.id to ids
    }

    private suspend fun workspaceReads(batchId: EntityId): Map<String, Int> {
        val store = ImportReviewStore(importDao, IdGenerator.Random, StoppedClock(moment))
        driver.start()
        assertNotNull(store.observeWorkspace(batchId).first())
        return ran(driver.stop())
    }

    @Test
    fun `showing an import asks about its colours once, however many drafts it has`() =
        runBlocking<Unit> {
            val (oneBatch, oneDraft) = aBatchOf(1)
            importDao.setDraftColorsUnderReview(oneDraft.single(), extraColors(2), StoppedClock(moment))
            val one = workspaceReads(oneBatch)

            val (manyBatch, manyDrafts) = aBatchOf(42)
            val palette = extraColors(3)
            manyDrafts.forEach { importDao.setDraftColorsUnderReview(it, palette, StoppedClock(moment)) }
            val many = workspaceReads(manyBatch)

            assertEquals(1, one["SELECT draft_task_colors"], "N=1 asked about colours more than once")
            assertEquals(
                one["SELECT draft_task_colors"],
                many["SELECT draft_task_colors"],
                "the colour question grew with the number of drafts",
            )
            assertEquals(
                decisions(one),
                decisions(many),
                "showing a batch of forty-two asks more questions than showing one",
            )
        }

    @Test
    fun `showing an import asks the same however many colours a draft has`() =
        runBlocking<Unit> {
            val (batchId, drafts) = aBatchOf(1)
            val few = workspaceReads(batchId)

            importDao.setDraftColorsUnderReview(drafts.single(), extraColors(42), StoppedClock(moment))
            val many = workspaceReads(batchId)

            assertEquals(decisions(few), decisions(many), "a longer colour list cost another question")
            assertEquals(1, many["SELECT draft_task_colors"])
        }

    @Test
    fun `choosing colours asks the catalogue once, not once per colour`() =
        runBlocking<Unit> {
            val (_, drafts) = aBatchOf(1)
            val palette = extraColors(42)

            driver.start()
            importDao.setDraftColorsUnderReview(drafts.single(), palette, StoppedClock(moment))
            val counted = ran(driver.stop())

            assertEquals(1, counted["SELECT colors"], "the catalogue was read more than once")
            assertEquals(1, counted["SELECT draft_task_colors"], "the old list was read more than once")
            // The writing is allowed to grow: forty-two colours are forty-two rows.
            assertEquals(42, counted["INSERT draft_task_colors"])
            assertEquals(1, counted["UPDATE draft_tasks"])
        }

    @Test
    fun `the reads to change a list are the same for one colour and for forty-two`() =
        runBlocking<Unit> {
            val (_, drafts) = aBatchOf(2)
            val palette = extraColors(42)

            driver.start()
            importDao.setDraftColorsUnderReview(drafts[0], listOf(palette.first()), StoppedClock(moment))
            val one = ran(driver.stop())

            driver.start()
            importDao.setDraftColorsUnderReview(drafts[1], palette, StoppedClock(moment))
            val many = ran(driver.stop())

            assertEquals(decisions(one), decisions(many), "a longer list asked more questions")
        }

    @Test
    fun `a list that changes nothing asks about the draft and reads no catalogue`() =
        runBlocking<Unit> {
            val (_, drafts) = aBatchOf(1)
            val palette = extraColors(3)
            importDao.setDraftColorsUnderReview(drafts.single(), palette, StoppedClock(moment))

            driver.start()
            importDao.setDraftColorsUnderReview(drafts.single(), palette, StoppedClock(moment))
            val counted = ran(driver.stop())

            assertEquals(null, counted["SELECT colors"], "a no-op read the catalogue")
            assertEquals(null, counted["INSERT draft_task_colors"], "a no-op wrote a row")
            assertEquals(null, counted["DELETE draft_task_colors"], "a no-op removed a row")
            assertEquals(null, counted["UPDATE draft_tasks"], "a no-op moved a timestamp")
        }

    @Test
    fun `answering a green hint asks about the cell, the import and the game, and nothing else`() =
        runBlocking<Unit> {
            val game =
                dev.pnptracker.data.database
                    .aGame(name = "Harmonies")
            database.gameDao().insert(game)
            val batch = anImportBatch(rawBlockCount = 1)
            importDao.insertBatch(batch)
            val block =
                aRawImportBlock(
                    batch.id,
                    rawText = "Harmonies",
                    rowIndex = 1,
                    columnIndex = 0,
                    sourceColumnType = SourceColumnType.GAME,
                )
            importDao.insertRawBlock(block)

            driver.start()
            importDao.setGameCompletionDecisionUnderReview(
                block.id,
                dev.pnptracker.domain.model.HintDecision.ACCEPTED,
                game.id,
                StoppedClock(moment),
            )
            val counted = ran(driver.stop())

            assertEquals(
                mapOf("SELECT raw_import_blocks" to 1, "SELECT import_batches" to 1, "SELECT games" to 1),
                decisions(counted),
            )
        }
}
