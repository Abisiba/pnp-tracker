package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import dev.pnptracker.data.database.entity.DraftTaskEntity
import dev.pnptracker.data.database.entity.ImportBatchEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportBatchStatus
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Reads and writes the three tables an import lives in.
 *
 * Raw cells have no general update path on purpose: their text and coordinates
 * are the record of what the file contained, so only the parts a user really
 * decides — whether a cell is done with, and what they answered to a hint — can
 * be changed.
 */
@Dao
abstract class ImportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertBatch(batch: ImportBatchEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRawBlock(block: RawImportBlockEntity)

    /**
     * Writes a whole import at once: the batch and every raw cell it read.
     *
     * All of it or none of it. If any cell is refused — two cells claiming the
     * same coordinates, a batch id that is already taken — the batch goes back
     * with them, so a half written import can never be left behind for the user
     * to puzzle over.
     *
     * @throws IllegalArgumentException if the counts do not describe what is
     *   being written, or if the batch is not a draft.
     */
    @Transaction
    open suspend fun saveDraftBatch(
        batch: ImportBatchEntity,
        blocks: List<RawImportBlockEntity>,
    ) {
        require(batch.status == ImportBatchStatus.DRAFT) {
            "An import is saved as a draft first, but this one is ${batch.status}."
        }
        require(batch.rawBlockCount == blocks.size) {
            "The batch says it holds ${batch.rawBlockCount} cells but ${blocks.size} were given."
        }
        require(blocks.all { it.importBatchId == batch.id }) {
            "Every cell must belong to the batch being written."
        }
        insertBatch(batch)
        blocks.forEach { insertRawBlock(it) }
    }

    @Query("SELECT * FROM import_batches WHERE status = 'DRAFT' ORDER BY imported_at DESC")
    abstract suspend fun draftBatches(): List<ImportBatchEntity>

    @Query("SELECT * FROM import_batches WHERE id = :id")
    abstract suspend fun batchById(id: EntityId): ImportBatchEntity?

    @Query("SELECT * FROM import_batches ORDER BY imported_at DESC")
    abstract suspend fun allBatches(): List<ImportBatchEntity>

    /**
     * Every earlier import of a file with this fingerprint, newest first. An empty
     * list means this file has not been imported before.
     */
    @Query("SELECT * FROM import_batches WHERE sha256 = :sha256 ORDER BY imported_at DESC")
    abstract suspend fun batchesWithFingerprint(sha256: String): List<ImportBatchEntity>

    @Query(
        """
        SELECT * FROM raw_import_blocks
        WHERE import_batch_id = :batchId
        ORDER BY sheet_name, row_index, column_index
        """,
    )
    abstract suspend fun rawBlocksOfBatch(batchId: EntityId): List<RawImportBlockEntity>

    @Query(
        """
        SELECT * FROM raw_import_blocks
        WHERE import_batch_id = :batchId AND is_processed = 0
        ORDER BY sheet_name, row_index, column_index
        """,
    )
    abstract suspend fun unprocessedRawBlocksOfBatch(batchId: EntityId): List<RawImportBlockEntity>

    @Query("SELECT * FROM raw_import_blocks WHERE id = :id")
    abstract suspend fun rawBlockById(id: EntityId): RawImportBlockEntity?

    /**
     * The batch being reviewed, and null once it is gone.
     *
     * The three observing queries below are what the review workspace is built
     * from: Room re-runs them whenever the tables change, so marking a cell done
     * or adding a draft shows up without anything having to remember to reload.
     */
    @Query("SELECT * FROM import_batches WHERE id = :id")
    abstract fun observeBatch(id: EntityId): Flow<ImportBatchEntity?>

    /**
     * Every cell of a batch, in the order it appeared in the file.
     *
     * Sheet, row and column are already unique together for one batch, so the
     * order cannot tie; `id` is named last anyway so the order stays fixed even
     * if that ever stops being true.
     */
    @Query(
        """
        SELECT * FROM raw_import_blocks
        WHERE import_batch_id = :batchId
        ORDER BY sheet_name, row_index, column_index, id
        """,
    )
    abstract fun observeRawBlocksOfBatch(batchId: EntityId): Flow<List<RawImportBlockEntity>>

    /**
     * Every draft of a batch, oldest first, reached through the cells it came from
     * so a draft of another import can never appear in this one's workspace.
     *
     * Two drafts written in the same millisecond are separated by `id`, which
     * keeps the list from reshuffling between reads.
     */
    @Query(
        """
        SELECT draft_tasks.* FROM draft_tasks
        JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        WHERE raw_import_blocks.import_batch_id = :batchId
        ORDER BY draft_tasks.created_at, draft_tasks.id
        """,
    )
    abstract fun observeDraftTasksOfBatch(batchId: EntityId): Flow<List<DraftTaskEntity>>

    /** Imports the user can still come back to, newest first. */
    @Query("SELECT * FROM import_batches WHERE status = 'DRAFT' ORDER BY imported_at DESC, id")
    abstract fun observeDraftBatches(): Flow<List<ImportBatchEntity>>

    @Query("UPDATE raw_import_blocks SET is_processed = :isProcessed, updated_at = :updatedAt WHERE id = :id")
    abstract suspend fun markRawBlockProcessed(
        id: EntityId,
        isProcessed: Boolean,
        updatedAt: Instant,
    ): Int

    @Query("UPDATE raw_import_blocks SET game_completion_hint = :decision, updated_at = :updatedAt WHERE id = :id")
    abstract suspend fun updateGameCompletionHint(
        id: EntityId,
        decision: HintDecision,
        updatedAt: Instant,
    ): Int

    /**
     * Records how far the user has got with one cell.
     *
     * Reviewing progress is only meaningful while the import is still a draft, so
     * the cell has to exist and its batch has to be one; otherwise nothing is
     * written and the previous value stands.
     *
     * This says nothing about drafts. A cell can be marked done with no drafts on
     * it, and a cell with drafts stays unmarked until the user says otherwise:
     * the two are separate decisions and neither implies the other.
     *
     * @throws IllegalArgumentException if the cell is unknown or its import is no
     *   longer a draft; nothing is written in that case.
     */
    @Transaction
    open suspend fun setRawBlockProcessed(
        blockId: EntityId,
        isProcessed: Boolean,
        updatedAt: Instant,
    ) {
        val block = rawBlockById(blockId)
        requireNotNull(block) { "There is no raw cell $blockId to mark." }
        val batch = batchById(block.importBatchId)
        requireNotNull(batch) { "The raw cell $blockId belongs to no import batch." }
        require(batch.status == ImportBatchStatus.DRAFT) {
            "Only a draft import can be reviewed, but ${batch.id} is ${batch.status}."
        }
        markRawBlockProcessed(blockId, isProcessed, updatedAt)
    }

    @Query("SELECT * FROM draft_tasks WHERE raw_import_block_id = :blockId ORDER BY created_at, name")
    abstract suspend fun draftTasksOfBlock(blockId: EntityId): List<DraftTaskEntity>

    @Query("SELECT * FROM draft_tasks WHERE id = :id")
    abstract suspend fun draftTaskById(id: EntityId): DraftTaskEntity?

    @Query("UPDATE draft_tasks SET completion_hint = :decision, updated_at = :updatedAt WHERE id = :id")
    abstract suspend fun updateDraftCompletionHint(
        id: EntityId,
        decision: HintDecision,
        updatedAt: Instant,
    ): Int

    /**
     * Stores a draft after checking that any text selection really points inside
     * the cell it claims to come from.
     *
     * @throws IllegalArgumentException if the raw cell is unknown or the selection
     *   runs past the end of its text; nothing is written in that case.
     */
    @Transaction
    open suspend fun addDraftTask(draft: DraftTaskEntity) {
        requireSelectionFitsRawText(draft)
        insertDraftTaskRow(draft)
    }

    /** Replaces a draft with an edited version, under the same selection check. */
    @Transaction
    open suspend fun editDraftTask(draft: DraftTaskEntity) {
        requireSelectionFitsRawText(draft)
        updateDraftTaskRow(draft)
    }

    /**
     * Throws away an import the user never confirmed, together with the raw cells
     * and drafts it produced. Nothing else is touched.
     *
     * A confirmed or rolled back import cannot be discarded this way, and neither
     * can one whose cells already produced games or tasks: those foreign keys stop
     * the delete and the whole transaction is rolled back. Undoing a confirmed
     * import is a different operation and belongs to a later phase.
     *
     * @throws IllegalArgumentException if the batch is unknown or is not a draft.
     */
    @Transaction
    open suspend fun discardDraftBatch(id: EntityId) {
        val batch = batchById(id)
        requireNotNull(batch) { "There is no import batch $id to discard." }
        require(batch.status == ImportBatchStatus.DRAFT) {
            "Only a draft import can be discarded, but $id is ${batch.status}."
        }
        deleteDraftBatchRow(id)
    }

    private suspend fun requireSelectionFitsRawText(draft: DraftTaskEntity) {
        val start = draft.selectionStartIndex
        val end = draft.selectionEndIndex
        if (start == null || end == null) return
        val block = rawBlockById(draft.rawImportBlockId)
        requireNotNull(block) { "There is no raw cell ${draft.rawImportBlockId} for this draft." }
        require(end <= block.rawText.length) {
            "The selection $start..$end runs past the end of the cell text, which is ${block.rawText.length} " +
                "characters long."
        }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertDraftTaskRow(draft: DraftTaskEntity)

    /**
     * Replaces the whole draft row. Reachable only through [editDraftTask], which
     * re-checks the selection against whatever cell the edited draft points at.
     */
    @Update
    protected abstract suspend fun updateDraftTaskRow(draft: DraftTaskEntity): Int

    /** Guarded by [discardDraftBatch]; the status check is repeated in SQL. */
    @Query("DELETE FROM import_batches WHERE id = :id AND status = 'DRAFT'")
    protected abstract suspend fun deleteDraftBatchRow(id: EntityId): Int
}
