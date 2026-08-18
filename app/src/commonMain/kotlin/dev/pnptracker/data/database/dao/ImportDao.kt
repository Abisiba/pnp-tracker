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
