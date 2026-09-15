package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aDraftImport
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.data.database.executeRawSql
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.data.database.soundnessProblemsOf
import dev.pnptracker.data.database.wholeDatabase
import dev.pnptracker.data.database.withoutDraft
import dev.pnptracker.domain.importhealth.DraftContradiction
import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ImportBatchStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The list of unfinished imports against a real database.
 *
 * The one promise this store adds to the classifier and the removal engine
 * under it is that the whole list, read at once, says of every draft exactly
 * what asking about that draft alone would say — and keeps saying it as the
 * database changes underneath. Both are held here row for row: nine drafts,
 * each carrying one contradiction and nothing else, one carrying all nine, and
 * sound ones beside them.
 */
class UnfinishedImportsStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val failing = FailingSqliteDriver()
    private var realDatabaseExistedBefore = false
    private var fingerprints = 0

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

    private val store get() = UnfinishedImportsStore(database, database.importDao())

    private suspend fun aDraft(
        blocks: Int = 2,
        fileName: String = "taslak.csv",
    ) = aDraftImport(database, blocks, "%064x".format(fingerprints++), fileName = fileName)

    private suspend fun sql(
        statement: String,
        vararg arguments: String,
    ) = executeRawSql(database, statement, *arguments)

    /** The next reading of the list that satisfies [what]. */
    private suspend fun listWhere(what: (List<UnfinishedImport>) -> Boolean): List<UnfinishedImport> =
        withTimeout(10.seconds) { store.observeUnfinishedImports().first(what) }

    /**
     * One draft for each of the nine contradictions, written the way the
     * classifier's own tests write them, keyed by what it carries.
     */
    private suspend fun oneDraftPerContradiction(): Map<DraftContradiction, EntityId> {
        insertGameCellAndTask(database, gameName = "Görevli")
        insertGameCellAndTask(database, gameName = "Oyunlu")
        val tasks = wholeDatabase(database).tasks.map { it.id }
        val games = wholeDatabase(database).games.map { it.id }
        val cell = wholeDatabase(database).gameCells.first().id
        return DraftContradiction.entries.associateWith { contradiction ->
            val draft = aDraft(fileName = "${contradiction.name.lowercase()}.csv")
            val batch = draft.batchId.toString()
            val block = draft.blockIds.first().toString()
            when (contradiction) {
                DraftContradiction.TASKS_COUNTED_BEFORE_CONFIRMATION ->
                    sql("UPDATE import_batches SET created_task_count = 1 WHERE id = ?", batch)
                DraftContradiction.GAMES_COUNTED_BEFORE_CONFIRMATION ->
                    sql("UPDATE import_batches SET created_game_count = 1 WHERE id = ?", batch)
                DraftContradiction.RAW_CELL_COUNT_DISAGREES ->
                    sql("UPDATE import_batches SET raw_block_count = 7 WHERE id = ?", batch)
                DraftContradiction.DRAFT_ALREADY_MATERIALIZED ->
                    sql("UPDATE draft_tasks SET materialized_task_id = ? WHERE raw_import_block_id = ?", tasks[0], block)
                DraftContradiction.CELL_RECORDED_BEFORE_CONFIRMATION ->
                    sql("INSERT INTO import_batch_cells (import_batch_id, cell_id, document_before) VALUES (?, ?, '')", batch, cell)
                DraftContradiction.TASK_SOURCED_FROM_DRAFT ->
                    sql("UPDATE tasks SET source_raw_import_block_id = ? WHERE id = ?", block, tasks[1])
                DraftContradiction.GAME_SOURCED_FROM_DRAFT ->
                    sql("UPDATE games SET source_import_batch_id = ? WHERE id = ?", batch, games[1])
                DraftContradiction.SELECTION_BEYOND_ITS_TEXT ->
                    sql("UPDATE draft_tasks SET selection_start_index = 0, selection_end_index = 900 WHERE raw_import_block_id = ?", block)
                DraftContradiction.COMPLETION_HINT_OUTSIDE_GAME_COLUMN ->
                    sql("UPDATE raw_import_blocks SET game_completion_hint = 'PENDING' WHERE id = ?", block)
            }
            draft.batchId
        }
    }

    @Test
    fun `the whole list says of every draft what asking about it alone says`() =
        runBlocking<Unit> {
            val sound = aDraft(fileName = "sağlam.csv").batchId
            val carrying = oneDraftPerContradiction()
            val soundToo = aDraft(blocks = 0, fileName = "boş.csv").batchId
            val importDao = database.importDao()

            val whole = importDao.healthOfDraftBatches()

            assertEquals(11, whole.size)
            whole.forEach { (batch, health) ->
                assertEquals(importDao.draftHealthOf(batch.id), health, "the list and the single reading disagree on ${batch.fileName}")
            }
            val byBatch = whole.associate { it.batch.id to it.health }
            assertEquals(DraftHealth.Sound(sound), byBatch[sound])
            assertEquals(DraftHealth.Sound(soundToo), byBatch[soundToo])
            carrying.forEach { (contradiction, batchId) ->
                assertEquals(DraftHealth.Contradicting(batchId, setOf(contradiction)), byBatch[batchId], "$contradiction")
            }
            assertEquals(emptyList(), soundnessProblemsOf(database))
        }

    @Test
    fun `a draft carrying all nine is named with all nine by both readings`() =
        runBlocking<Unit> {
            val carrying = oneDraftPerContradiction()
            // Move every contradiction onto one draft by pointing each row at it.
            val target = aDraft(fileName = "hepsi.csv")
            val batch = target.batchId.toString()
            val block = target.blockIds.first().toString()
            carrying.values.forEach { other ->
                sql("UPDATE import_batch_cells SET import_batch_id = ? WHERE import_batch_id = ?", batch, other.toString())
                sql("UPDATE games SET source_import_batch_id = ? WHERE source_import_batch_id = ?", batch, other.toString())
            }
            sql("UPDATE tasks SET source_raw_import_block_id = ? WHERE source_raw_import_block_id IS NOT NULL", block)
            sql("UPDATE draft_tasks SET materialized_task_id = NULL")
            sql(
                "UPDATE draft_tasks SET materialized_task_id = (SELECT id FROM tasks WHERE source_raw_import_block_id IS NULL), " +
                    "selection_start_index = 0, selection_end_index = 900 WHERE raw_import_block_id = ?",
                block,
            )
            sql("UPDATE raw_import_blocks SET game_completion_hint = 'PENDING' WHERE id = ?", block)
            sql("UPDATE import_batches SET created_task_count = 3, created_game_count = 1, raw_block_count = 9 WHERE id = ?", batch)

            val all = DraftHealth.Contradicting(target.batchId, DraftContradiction.entries.toSet())
            assertEquals(all, database.importDao().draftHealthOf(target.batchId))
            assertEquals(
                all,
                database
                    .importDao()
                    .healthOfDraftBatches()
                    .single { it.batch.id == target.batchId }
                    .health,
            )
        }

    @Test
    fun `the list is newest first, drafts only, and marks what a removal would be refused`() =
        runBlocking<Unit> {
            val carrying = oneDraftPerContradiction()
            val confirmed = anImportBatch(status = ImportBatchStatus.CONFIRMED, sha256 = "%064x".format(fingerprints++))
            database.importDao().insertBatch(confirmed)

            val rows = listWhere { it.isNotEmpty() }

            assertEquals(database.importDao().draftBatches().map { it.id }, rows.map { it.batchId }, "not the drafts' own order")
            assertEquals(false, rows.any { it.batchId == confirmed.id }, "a confirmed import was listed as unfinished")
            rows.forEach { row -> assertEquals(true, row.isContradicting, row.fileName) }
            val held = rows.filter { it.mayBeHeldByRecords }.map { it.batchId }.toSet()
            assertEquals(
                setOf(
                    carrying.getValue(DraftContradiction.TASK_SOURCED_FROM_DRAFT),
                    carrying.getValue(DraftContradiction.GAME_SOURCED_FROM_DRAFT),
                ),
                held,
            )
        }

    @Test
    fun `the list follows the database into every table a contradiction is about`() =
        runBlocking<Unit> {
            insertGameCellAndTask(database)
            val draft = aDraft()
            assertEquals(false, listWhere { it.isNotEmpty() }.single().isContradicting)

            // A change to tasks alone — no draft row moves — still reaches the list.
            sql("UPDATE tasks SET source_raw_import_block_id = ?", draft.blockIds.first().toString())
            val held = listWhere { rows -> rows.singleOrNull()?.isContradicting == true }.single()
            assertEquals(true, held.mayBeHeldByRecords)

            sql("UPDATE tasks SET source_raw_import_block_id = NULL")
            assertEquals(false, listWhere { rows -> rows.singleOrNull()?.isContradicting == false }.single().mayBeHeldByRecords)

            assertIs<DraftRemovalOutcome.Removed>(store.remove(draft.batchId))
            assertEquals(emptyList(), listWhere { it.isEmpty() })
        }

    @Test
    fun `removing goes through the removal engine and nothing else`() =
        runBlocking<Unit> {
            insertGameCellAndTask(database)
            val sound = aDraft()
            val held = aDraft()
            sql("UPDATE tasks SET source_raw_import_block_id = ?", held.blockIds.first().toString())
            val before = wholeDatabase(database)

            assertEquals(DraftRemovalOutcome.Refused(held.batchId, DraftRemovalRefusal.HELD_BY_RECORDS), store.remove(held.batchId))
            assertEquals(before, wholeDatabase(database))

            assertIs<DraftRemovalOutcome.Removed>(store.remove(sound.batchId))
            assertEquals(before.withoutDraft(sound.batchId), wholeDatabase(database))
            assertEquals(DraftRemovalOutcome.Refused(sound.batchId, DraftRemovalRefusal.ALREADY_REMOVED), store.remove(sound.batchId))
            // PLAN 11.4.5: no history line, and nothing here can take a backup.
            assertEquals(emptyList(), wholeDatabase(database).historyEvents)
        }

    @Test
    fun `asking afresh answers for the database as it is now`() =
        runBlocking<Unit> {
            val draft = aDraft()
            assertEquals(DraftHealth.Sound(draft.batchId), store.healthOf(draft.batchId))

            sql("UPDATE import_batches SET created_task_count = 1 WHERE id = ?", draft.batchId.toString())
            assertEquals(
                DraftHealth.Contradicting(draft.batchId, setOf(DraftContradiction.TASKS_COUNTED_BEFORE_CONFIRMATION)),
                store.healthOf(draft.batchId),
            )

            store.remove(draft.batchId)
            assertEquals(DraftHealth.NotFound(draft.batchId), store.healthOf(draft.batchId))
        }

    @Test
    fun `storage refusing is a typed answer and a defect is not`() =
        runBlocking<Unit> {
            val draft = aDraft()
            failing.failOn(1) { "AS HINT_OUTSIDE_GAME_COUNT" in it.uppercase() }
            assertFailsWith<UnfinishedImportsUnreadable> { store.healthOf(draft.batchId) }
            failing.disarm()

            failing.failOn(1) { "AS HINT_OUTSIDE_GAME_COUNT" in it.uppercase() }
            assertFailsWith<UnfinishedImportsUnreadable> { store.observeUnfinishedImports().first() }
            failing.disarm()

            assertEquals(1, store.observeUnfinishedImports().first().size)
        }
}
