package dev.pnptracker.data.database

import dev.pnptracker.data.repository.ImportDraftRemovalStore
import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.data.repository.confirmationStore
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * Removing an import the user never confirmed (PLAN 11.4.5).
 *
 * Every test starts from a database with a row in all fifteen tables — games,
 * cells, pieces, tasks with colours and stages, progress and history, a
 * confirmed and a rolled back import with raw cells, drafts, draft colours and
 * cell snapshots — and a neighbouring draft that must survive. So "nothing else
 * changed" is asked of a full database and answered value for value, not of an
 * empty one where it would be true for free.
 */
class DraftRemovalTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false
    private lateinit var neighbour: DraftImport

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        runBlocking {
            fillWithEverything(database)
            neighbour = aDraftImport(database, blocks = 3, fingerprint = "%064x".format(99), fileName = "komşu.xlsx")
        }
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val store get() = ImportDraftRemovalStore(database.importDao())

    private var fingerprints = 0

    private suspend fun aDraft(
        blocks: Int,
        draftsPerBlock: Int = 1,
        coloursPerDraft: Int = 2,
    ): DraftImport =
        aDraftImport(
            database,
            blocks = blocks,
            fingerprint = "%064x".format(fingerprints++),
            draftsPerBlock = draftsPerBlock,
            coloursPerDraft = coloursPerDraft,
        )

    /** Removes [draft] and proves the database is everything before it, less exactly the draft's own rows. */
    private suspend fun removedCompletely(draft: DraftImport): DraftRemovalOutcome.Removed {
        val before = wholeDatabase(database)
        val outcome = assertIs<DraftRemovalOutcome.Removed>(store.remove(draft.batchId))
        val after = wholeDatabase(database)

        assertEquals(before.withoutDraft(draft.batchId), after, "the removal changed a row that was not the draft's own")
        assertEquals(emptyList(), soundnessProblemsOf(database))
        // The neighbour is not merely still counted: every one of its rows reads as it did.
        assertEquals(
            before.rawImportBlocks.filter { it.importBatchId == neighbour.batchId.toString() },
            after.rawImportBlocks.filter { it.importBatchId == neighbour.batchId.toString() },
        )
        return outcome
    }

    @Test
    fun `a draft with one raw cell is removed with its draft and colours and nothing else`() =
        runBlocking<Unit> {
            val draft = aDraft(blocks = 1)

            val outcome = removedCompletely(draft)

            assertEquals(DraftRemovalOutcome.Removed(draft.batchId, 1, 1, 2, 0), outcome)
        }

    @Test
    fun `a draft with many raw cells, drafts and colours is removed all at once`() =
        runBlocking<Unit> {
            val draft = aDraft(blocks = 42, draftsPerBlock = 2, coloursPerDraft = 3)

            val outcome = removedCompletely(draft)

            assertEquals(DraftRemovalOutcome.Removed(draft.batchId, 42, 84, 252, 0), outcome)
        }

    @Test
    fun `a draft with no raw cells at all is removed the same way`() =
        runBlocking<Unit> {
            // PLAN 11.4.5 counts an import with no drafts, or nothing processed,
            // as an ordinary draft; one with no cells is the far end of that.
            val draft = aDraft(blocks = 0)

            val outcome = removedCompletely(draft)

            assertEquals(DraftRemovalOutcome.Removed(draft.batchId, 0, 0, 0, 0), outcome)
        }

    @Test
    fun `raw cells nobody drafted from and drafts with no colour are removed too`() =
        runBlocking<Unit> {
            val draft = aDraft(blocks = 5, draftsPerBlock = 0)
            val bare = aDraft(blocks = 2, coloursPerDraft = 0)

            assertEquals(DraftRemovalOutcome.Removed(draft.batchId, 5, 0, 0, 0), removedCompletely(draft))
            assertEquals(DraftRemovalOutcome.Removed(bare.batchId, 2, 2, 0, 0), removedCompletely(bare))
        }

    @Test
    fun `asking again gets a typed already removed and writes nothing`() =
        runBlocking<Unit> {
            val draft = aDraft(blocks = 4)
            assertIs<DraftRemovalOutcome.Removed>(store.remove(draft.batchId))
            val afterFirst = wholeDatabase(database)

            val second = store.remove(draft.batchId)
            val third = database.importDao().removeDraftBatch(draft.batchId)

            assertEquals(DraftRemovalOutcome.Refused(draft.batchId, DraftRemovalRefusal.ALREADY_REMOVED), second)
            assertEquals(DraftRemovalOutcome.Refused(draft.batchId, DraftRemovalRefusal.ALREADY_REMOVED), third)
            assertEquals(afterFirst, wholeDatabase(database))
        }

    @Test
    fun `an import that never existed is the same typed answer`() =
        runBlocking<Unit> {
            val before = wholeDatabase(database)
            val unknown = IdGenerator.Random.newId()

            assertEquals(DraftRemovalOutcome.Refused(unknown, DraftRemovalRefusal.ALREADY_REMOVED), store.remove(unknown))
            assertEquals(before, wholeDatabase(database))
        }

    @Test
    fun `a really confirmed import and a really rolled back one are refused with nothing changed`() =
        runBlocking<Unit> {
            val game = aGame(name = "Kaldırılamaz")
            database.gameDao().insert(game)
            val cell = aCell(game.id, columnType = CellColumnType.THREE_D)
            database.gameCellDao().insert(cell)
            val confirmed = aConfirmedImport(cell.id)
            val rolledBack = aConfirmedImport(cell.id, afterwards = { ImportRollbackStore(database.importDao()).rollBack(it) })
            assertEquals(ImportBatchStatus.CONFIRMED, database.importDao().batchById(confirmed)?.status)
            assertEquals(ImportBatchStatus.ROLLED_BACK, database.importDao().batchById(rolledBack)?.status)
            val before = wholeDatabase(database)

            assertEquals(DraftRemovalOutcome.Refused(confirmed, DraftRemovalRefusal.NOT_A_DRAFT), store.remove(confirmed))
            assertEquals(DraftRemovalOutcome.Refused(rolledBack, DraftRemovalRefusal.NOT_A_DRAFT), store.remove(rolledBack))

            assertEquals(before, wholeDatabase(database))
            assertEquals(emptyList(), soundnessProblemsOf(database))
        }

    @Test
    fun `a draft a task was made from is refused, even when the task is deleted`() =
        runBlocking<Unit> {
            // D6 of PLAN 11.4.5. The application never writes this shape for a
            // draft; a restored backup can. The refusal is decided by looking,
            // not by reading SQLite's foreign key error.
            val draft = aDraft(blocks = 3)
            val task = insertGameCellAndTask(database, gameName = "Kaynaklı")
            executeRawSql(
                database,
                "UPDATE tasks SET source_raw_import_block_id = ?, deleted_at = updated_at WHERE id = ?",
                draft.blockIds[1].toString(),
                task.id.toString(),
            )
            val before = wholeDatabase(database)

            assertEquals(DraftRemovalOutcome.Refused(draft.batchId, DraftRemovalRefusal.HELD_BY_RECORDS), store.remove(draft.batchId))

            assertEquals(before, wholeDatabase(database))
        }

    @Test
    fun `a draft a game was made from is refused, even when the game is deleted`() =
        runBlocking<Unit> {
            // D7 of PLAN 11.4.5.
            val draft = aDraft(blocks = 2)
            val game = aGame(name = "Kaynaklı oyun").copy(sourceImportBatchId = draft.batchId, deletedAt = updatedAt)
            database.gameDao().insert(game)
            val before = wholeDatabase(database)

            assertEquals(DraftRemovalOutcome.Refused(draft.batchId, DraftRemovalRefusal.HELD_BY_RECORDS), store.remove(draft.batchId))

            assertEquals(before, wholeDatabase(database))
        }

    @Test
    fun `a damaged draft carrying cell snapshots and a materialised task is removed without touching the task or the cell`() =
        runBlocking<Unit> {
            // D4 and D5 of PLAN 11.4.5 do not stop a removal: the snapshot rows
            // and the draft's link are the draft's own, and deleting them changes
            // no cell and no task. The task and the cell themselves stay.
            val draft = aDraft(blocks = 2)
            val task = insertGameCellAndTask(database, gameName = "Bağlı")
            val cellId = insertGameAndCell(database, gameName = "Anlık görüntülü").id
            executeRawSql(
                database,
                "UPDATE draft_tasks SET materialized_task_id = ? WHERE id = ?",
                task.id.toString(),
                draft.draftIds[0].toString(),
            )
            executeRawSql(
                database,
                "INSERT INTO import_batch_cells (import_batch_id, cell_id, document_before) VALUES (?, ?, 'önce')",
                draft.batchId.toString(),
                cellId.toString(),
            )
            val before = wholeDatabase(database)

            val outcome = removedCompletely(draft)

            assertEquals(DraftRemovalOutcome.Removed(draft.batchId, 2, 2, 4, 1), outcome)
            val after = wholeDatabase(database)
            assertEquals(before.tasks, after.tasks)
            assertEquals(before.gameCells, after.gameCells)
            assertEquals(before.cellSegments, after.cellSegments)
        }

    @Test
    fun `a removal writes no history and leaves the ten tables that are not an import's exactly as they were`() =
        runBlocking<Unit> {
            val draft = aDraft(blocks = 6)
            val before = wholeDatabase(database)
            assertNotEquals(0, before.historyEvents.size, "the fixture was meant to have a history to keep")

            store.remove(draft.batchId)

            val after = wholeDatabase(database)
            assertEquals(before.historyEvents, after.historyEvents)
            assertEquals(before.colors, after.colors)
            assertEquals(before.colorAliases, after.colorAliases)
            assertEquals(before.games, after.games)
            assertEquals(before.gameCells, after.gameCells)
            assertEquals(before.cellSegments, after.cellSegments)
            assertEquals(before.tasks, after.tasks)
            assertEquals(before.taskColors, after.taskColors)
            assertEquals(before.taskStages, after.taskStages)
            assertEquals(before.progressEvents, after.progressEvents)
            // And of the five import tables, every row of every other batch.
            listOf(BATCH_DRAFT, BATCH_CONFIRMED, BATCH_ROLLED_BACK, neighbour.batchId.toString()).forEach { other ->
                assertEquals(before.importBatches.filter { it.id == other }, after.importBatches.filter { it.id == other })
                assertEquals(
                    before.importBatchCells.filter { it.importBatchId == other },
                    after.importBatchCells.filter {
                        it.importBatchId ==
                            other
                    },
                )
            }
            assertEquals(before.draftTasks.size - 6, after.draftTasks.size)
        }

    @Test
    fun `a change outside the draft's own rows breaks the postcondition, is not masked, and leaves nothing behind`() =
        runBlocking<Unit> {
            // A trigger is the stand-in for a cascade that reaches further than
            // PLAN 11.4.5 allows. The transaction must notice, refuse to commit,
            // and say so as the defect it is — not as "could not be removed".
            val draft = aDraft(blocks = 3)
            executeRawSql(
                database,
                "CREATE TEMP TRIGGER reaches_too_far AFTER DELETE ON import_batches BEGIN DELETE FROM color_aliases; END",
            )
            val before = wholeDatabase(database)
            assertNotEquals(0, before.colorAliases.size)

            val defect = assertFailsWith<IllegalStateException> { store.remove(draft.batchId) }

            assertEquals(true, defect.message.orEmpty().startsWith("Removing the draft import"), defect.message)
            assertEquals(before, wholeDatabase(database))
        }

    @Test
    fun `a delete that leaves the draft's own row behind breaks the postcondition too`() =
        runBlocking<Unit> {
            // The other direction: the delete did less than it counted on. The
            // trigger puts the batch row straight back after the cascade has taken
            // its children.
            val draft = aDraft(blocks = 3)
            executeRawSql(
                database,
                """
                CREATE TEMP TRIGGER leaves_the_batch_behind AFTER DELETE ON import_batches BEGIN
                  INSERT INTO import_batches VALUES (OLD.id, OLD.file_name, OLD.sha256, OLD.source_format,
                    OLD.sheet_name, OLD.start_row_index, OLD.end_row_index, OLD.start_column_index,
                    OLD.end_column_index, OLD.created_game_count, OLD.raw_block_count, OLD.created_task_count,
                    OLD.status, OLD.imported_at, OLD.updated_at);
                END
                """.trimIndent(),
            )
            val before = wholeDatabase(database)

            val defect = assertFailsWith<IllegalStateException> { store.remove(draft.batchId) }

            assertEquals(true, defect.message.orEmpty().contains("left rows of its own behind"), defect.message)
            assertEquals(before, wholeDatabase(database))
        }

    // ----------------------------------------------------------------- helpers

    /** A real import, confirmed through the real store, of one draft into [cellId]. */
    private suspend fun aConfirmedImport(
        cellId: EntityId,
        afterwards: suspend (EntityId) -> Unit = {},
    ): EntityId {
        val draft = aDraft(blocks = 1, coloursPerDraft = 1)
        val importDao = database.importDao()
        importDao.setDraftTargetUnderReview(draft.draftIds.single(), cellId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH, updatedAt)
        importDao.draftTaskById(draft.draftIds.single())?.let { importDao.editDraftTask(it.copy(requiredQuantity = 4)) }
        confirmationStore(database).confirm(draft.batchId, acknowledgeUnprocessedBlocks = true)
        afterwards(draft.batchId)
        return draft.batchId
    }
}
