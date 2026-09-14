package dev.pnptracker.data.database

import dev.pnptracker.data.database.projection.DraftHealthFacts
import dev.pnptracker.data.database.projection.SelectionCandidateRow
import dev.pnptracker.data.database.projection.draftHealthOf
import dev.pnptracker.domain.importhealth.DraftContradiction
import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.platform.recovery.aReadyDraftImport
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * The classifier of PLAN 11.4.5, on everything that is *not* one of its nine
 * contradictions, and on the edges of the ones that are.
 *
 * Whether each contradiction can reach a live database at all is measured through
 * the real restore in [DraftContradictionReachTest]. What is asked here is the
 * other half of "exact": that the list of things PLAN says are not damage stays
 * sound, that a draft is judged by its own rows alone, that D8 is measured in
 * UTF-16 units rather than whatever SQLite counts, and that asking writes nothing.
 * Rows are shaped with SQL where no application path makes them — that is fine
 * for a question about what the classifier *answers*, and says nothing about
 * what can be reached.
 */
class DraftHealthTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false
    private var fingerprints = 100

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

    private suspend fun aDraft(
        blocks: Int,
        draftsPerBlock: Int = 1,
    ) = aDraftImport(database, blocks, "%064x".format(fingerprints++), draftsPerBlock)

    /** Asks twice, and proves the asking itself changed nothing. */
    private suspend fun healthOf(batchId: EntityId): DraftHealth {
        val before = wholeDatabase(database)
        val first = importDao.draftHealthOf(batchId)
        assertEquals(first, importDao.draftHealthOf(batchId), "the same database gave two answers")
        assertEquals(before, wholeDatabase(database), "classifying a draft wrote something")
        return first
    }

    private suspend fun sql(
        statement: String,
        vararg arguments: String,
    ) = executeRawSql(database, statement, *arguments)

    @Test
    fun `a draft built by the application is sound`() =
        runBlocking<Unit> {
            val draft = aDraft(blocks = 3, draftsPerBlock = 2)
            assertEquals(DraftHealth.Sound(draft.batchId), healthOf(draft.batchId))
        }

    @Test
    fun `a ready draft cut out of its cell's text is sound`() =
        runBlocking<Unit> {
            val batchId = aReadyDraftImport(database)
            assertEquals(DraftHealth.Sound(batchId), healthOf(batchId))
        }

    @Test
    fun `a draft with no raw cells at all is sound`() =
        runBlocking<Unit> {
            val draft = aDraft(blocks = 0)
            assertEquals(DraftHealth.Sound(draft.batchId), healthOf(draft.batchId))
        }

    @Test
    fun `a draft with raw cells and no task drafts, none of them processed, is sound`() =
        runBlocking<Unit> {
            val draft = aDraft(blocks = 4, draftsPerBlock = 0)
            assertEquals(DraftHealth.Sound(draft.batchId), healthOf(draft.batchId))
        }

    @Test
    fun `an import that is not there is not found`() =
        runBlocking<Unit> {
            val missing = IdGenerator.Random.newId()
            assertEquals(DraftHealth.NotFound(missing), healthOf(missing))
        }

    @Test
    fun `a confirmed or rolled back import is not a draft, however its rows read`() =
        runBlocking<Unit> {
            listOf(ImportBatchStatus.CONFIRMED, ImportBatchStatus.ROLLED_BACK).forEach { status ->
                // Counts that would be a contradiction on a draft, and are the
                // ordinary shape of a confirmed import.
                val batch =
                    anImportBatch(
                        status = status,
                        sha256 = "%064x".format(fingerprints++),
                        rawBlockCount = 7,
                    ).copy(createdTaskCount = 3)
                importDao.insertBatch(batch)
                assertEquals(DraftHealth.NotADraft(batch.id, status), healthOf(batch.id))
            }
        }

    @Test
    fun `a draft aimed at a cell whose game was deleted is sound`() =
        runBlocking<Unit> {
            val batchId = aReadyDraftImport(database)
            sql("UPDATE games SET deleted_at = updated_at")
            assertEquals(DraftHealth.Sound(batchId), healthOf(batchId))
        }

    @Test
    fun `a draft whose target and task were cleared the way the version 4 migration clears them is sound`() =
        runBlocking<Unit> {
            val batchId = aReadyDraftImport(database)
            sql(
                "UPDATE draft_tasks SET target_cell_id = NULL, selected_pool_type = NULL, selected_tracking_mode = NULL, materialized_task_id = NULL",
            )
            assertEquals(DraftHealth.Sound(batchId), healthOf(batchId))
        }

    @Test
    fun `timestamps that run backwards are not a contradiction`() =
        runBlocking<Unit> {
            val draft = aDraft(blocks = 2)
            sql("UPDATE import_batches SET updated_at = imported_at - 5000")
            sql("UPDATE raw_import_blocks SET updated_at = created_at - 5000")
            sql("UPDATE draft_tasks SET updated_at = created_at - 5000")
            assertEquals(DraftHealth.Sound(draft.batchId), healthOf(draft.batchId))
        }

    @Test
    fun `completion hints in the game column, answered or not, with or without a game, are sound`() =
        runBlocking<Unit> {
            val game = aGame(name = "Harmonies")
            database.gameDao().insert(game)
            val batch = anImportBatch(sha256 = "%064x".format(fingerprints++), rawBlockCount = 4)
            val cells =
                (1..4).map { row ->
                    aRawImportBlock(
                        batch.id,
                        rawText = "Harmonies",
                        rowIndex = row,
                        columnIndex = 0,
                        sourceColumnType = SourceColumnType.GAME,
                    )
                }
            importDao.saveDraftBatch(batch, cells)
            sql(
                "UPDATE raw_import_blocks SET game_completion_hint = 'ACCEPTED', completion_target_game_id = ? WHERE id = ?",
                game.id.toString(),
                cells[3].id.toString(),
            )
            sql("UPDATE raw_import_blocks SET game_completion_hint = 'PENDING' WHERE id = ?", cells[0].id.toString())
            // Accepted with no game named: the shape a version 5 database hands over.
            sql("UPDATE raw_import_blocks SET game_completion_hint = 'ACCEPTED' WHERE id = ?", cells[1].id.toString())
            sql("UPDATE raw_import_blocks SET game_completion_hint = 'REJECTED' WHERE id = ?", cells[2].id.toString())

            assertEquals(DraftHealth.Sound(batch.id), healthOf(batch.id))
        }

    @Test
    fun `a selection is measured in UTF-16 units, not in what SQLite counts`() =
        runBlocking<Unit> {
            // Two dice: two code points, which is what SQLite's length() says,
            // and four UTF-16 units, which is how a selection is indexed.
            val text = "🎲🎲"
            check(text.length == 4)
            val batch = anImportBatch(sha256 = "%064x".format(fingerprints++), rawBlockCount = 1)
            val cell = aRawImportBlock(batch.id, rawText = text, rowIndex = 1, columnIndex = 1)
            importDao.saveDraftBatch(batch, listOf(cell))
            val draft = aDraftTask(cell.id, selectionStartIndex = 0, selectionEndIndex = 4)
            importDao.addDraftTaskUnderReview(draft)

            suspend fun endAt(end: Int) = sql("UPDATE draft_tasks SET selection_end_index = $end WHERE id = ?", draft.id.toString())

            // Past SQLite's count and inside the text: sound.
            assertEquals(DraftHealth.Sound(batch.id), healthOf(batch.id))
            // In the middle of a character: not this predicate's business.
            endAt(3)
            assertEquals(DraftHealth.Sound(batch.id), healthOf(batch.id))
            // One unit past the end: the contradiction.
            endAt(5)
            assertEquals(DraftHealth.Contradicting(batch.id, setOf(DraftContradiction.SELECTION_BEYOND_ITS_TEXT)), healthOf(batch.id))
        }

    @Test
    fun `a selection that ends exactly at the end of its text is sound`() =
        runBlocking<Unit> {
            val draft = aDraft(blocks = 1)
            val text = wholeDatabase(database).rawImportBlocks.single().rawText
            sql("UPDATE draft_tasks SET selection_start_index = 0, selection_end_index = ${text.length}")
            assertEquals(DraftHealth.Sound(draft.batchId), healthOf(draft.batchId))

            sql("UPDATE draft_tasks SET selection_end_index = ${text.length + 1}")
            assertEquals(
                DraftHealth.Contradicting(draft.batchId, setOf(DraftContradiction.SELECTION_BEYOND_ITS_TEXT)),
                healthOf(draft.batchId),
            )
        }

    @Test
    fun `a draft is judged by its own rows, never by a neighbour's`() =
        runBlocking<Unit> {
            insertGameCellAndTask(database)
            val damaged = aDraft(blocks = 2)
            val neighbour = aDraft(blocks = 2)
            val block = damaged.blockIds.first().toString()
            sql(
                "UPDATE import_batches SET created_task_count = 4, created_game_count = 1, raw_block_count = 9 WHERE id = ?",
                damaged.batchId.toString(),
            )
            sql("UPDATE tasks SET source_raw_import_block_id = ?", block)
            sql("UPDATE games SET source_import_batch_id = ?", damaged.batchId.toString())
            sql(
                "UPDATE draft_tasks SET materialized_task_id = (SELECT id FROM tasks), selection_start_index = 0, selection_end_index = 999 WHERE raw_import_block_id = ?",
                block,
            )
            sql("UPDATE raw_import_blocks SET game_completion_hint = 'PENDING' WHERE import_batch_id = ?", damaged.batchId.toString())
            sql(
                "INSERT INTO import_batch_cells (import_batch_id, cell_id, document_before) VALUES (?, (SELECT id FROM game_cells), '')",
                damaged.batchId.toString(),
            )

            assertEquals(DraftHealth.Contradicting(damaged.batchId, DraftContradiction.entries.toSet()), healthOf(damaged.batchId))
            assertEquals(DraftHealth.Sound(neighbour.batchId), healthOf(neighbour.batchId))
            assertEquals(emptyList(), soundnessProblemsOf(database))
        }

    @Test
    fun `the classification itself names each contradiction from its own fact alone`() {
        val batch = anImportBatch(rawBlockCount = 2).copy(updatedAt = createdAt + 1.seconds)
        val sound = DraftHealthFacts(2, 0, 0, 0, 0, 0)
        val cases =
            mapOf(
                DraftContradiction.TASKS_COUNTED_BEFORE_CONFIRMATION to Triple(batch.copy(createdTaskCount = 1), sound, emptyList()),
                DraftContradiction.GAMES_COUNTED_BEFORE_CONFIRMATION to Triple(batch.copy(createdGameCount = 1), sound, emptyList()),
                DraftContradiction.RAW_CELL_COUNT_DISAGREES to Triple(batch, sound.copy(rawBlockRows = 3), emptyList()),
                DraftContradiction.DRAFT_ALREADY_MATERIALIZED to Triple(batch, sound.copy(materializedDraftCount = 1), emptyList()),
                DraftContradiction.CELL_RECORDED_BEFORE_CONFIRMATION to Triple(batch, sound.copy(cellSnapshotCount = 1), emptyList()),
                DraftContradiction.TASK_SOURCED_FROM_DRAFT to Triple(batch, sound.copy(sourcedTaskCount = 1), emptyList()),
                DraftContradiction.GAME_SOURCED_FROM_DRAFT to Triple(batch, sound.copy(sourcedGameCount = 1), emptyList()),
                DraftContradiction.SELECTION_BEYOND_ITS_TEXT to Triple(batch, sound, listOf(SelectionCandidateRow("ab", 3))),
                DraftContradiction.COMPLETION_HINT_OUTSIDE_GAME_COLUMN to Triple(batch, sound.copy(hintOutsideGameCount = 1), emptyList()),
            )

        assertEquals(DraftContradiction.entries.toSet(), cases.keys, "a contradiction has no case of its own")
        assertEquals(DraftHealth.Sound(batch.id), draftHealthOf(batch, sound, listOf(SelectionCandidateRow("🎲", 2))))
        cases.forEach { (expected, case) ->
            val (row, facts, selections) = case
            assertEquals(DraftHealth.Contradicting(batch.id, setOf(expected)), draftHealthOf(row, facts, selections), "$expected")
        }
    }

    @Test
    fun `a contradicting answer always names a contradiction, and a draft is never not a draft`() {
        val id = IdGenerator.Random.newId()
        assertFailsWith<IllegalArgumentException> { DraftHealth.Contradicting(id, emptySet()) }
        assertFailsWith<IllegalArgumentException> { DraftHealth.NotADraft(id, ImportBatchStatus.DRAFT) }
    }
}
