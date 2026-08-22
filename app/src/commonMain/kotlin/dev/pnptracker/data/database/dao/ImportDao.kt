package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.DraftTaskEntity
import dev.pnptracker.data.database.entity.ImportBatchEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.rules.requireAllowedTrackingMode
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

    /**
     * Stores a draft made while reviewing an import that is still a draft itself.
     *
     * The status is checked in the same transaction as the insert, so a draft can
     * never be attached to an import that has already been confirmed or undone.
     * Adding a draft says nothing about the cell it came from: the cell keeps
     * whatever review mark it had, because deciding a cell is dealt with is a
     * separate judgement from having made something out of it.
     *
     * @throws IllegalArgumentException if the cell is unknown or its import is no
     *   longer a draft; nothing is written in that case.
     */
    @Transaction
    open suspend fun addDraftTaskUnderReview(draft: DraftTaskEntity) {
        val block = rawBlockById(draft.rawImportBlockId)
        requireNotNull(block) { "There is no raw cell ${draft.rawImportBlockId} to draft from." }
        val batch = batchById(block.importBatchId)
        requireNotNull(batch) { "The raw cell ${block.id} belongs to no import batch." }
        require(batch.status == ImportBatchStatus.DRAFT) {
            "Drafts can only be added while the import is a draft, but ${batch.id} is ${batch.status}."
        }
        addDraftTask(draft)
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

    // ---------------------------------------------------------------------
    // Confirming an import: turning drafts into real tasks.
    // ---------------------------------------------------------------------

    /**
     * Every draft of a batch, oldest first, reached through the cells it came
     * from so no other import's draft can be swept into this one's confirmation.
     */
    @Query(
        """
        SELECT draft_tasks.* FROM draft_tasks
        JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        WHERE raw_import_blocks.import_batch_id = :batchId
        ORDER BY draft_tasks.created_at, draft_tasks.id
        """,
    )
    abstract suspend fun draftTasksOfBatch(batchId: EntityId): List<DraftTaskEntity>

    @Query(
        """
        SELECT COUNT(*) FROM raw_import_blocks
        WHERE import_batch_id = :batchId AND is_processed = 0
        """,
    )
    abstract suspend fun unprocessedRawBlockCount(batchId: EntityId): Int

    /** 1 while the cell and the game above it are both there and undeleted. */
    @Query(
        """
        SELECT COUNT(*) FROM game_cells
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE game_cells.id = :cellId AND games.deleted_at IS NULL
        """,
    )
    abstract suspend fun activeCellCount(cellId: EntityId): Int

    /** How many cells exist at all; zero means there is nowhere to put a task. */
    @Query(
        """
        SELECT COUNT(*) FROM game_cells
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE games.deleted_at IS NULL
        """,
    )
    abstract suspend fun activeCellCount(): Int

    @Query("SELECT column_type FROM game_cells WHERE id = :cellId")
    abstract suspend fun columnTypeOfCell(cellId: EntityId): CellColumnType?

    @Query("SELECT COALESCE(MAX(order_index), -1) + 1 FROM cell_segments WHERE cell_id = :cellId")
    abstract suspend fun nextSegmentIndex(cellId: EntityId): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSegment(segment: CellSegmentEntity)

    /**
     * Records the user's decisions about one draft: where the task will go, which
     * pool it belongs to and how it is tracked.
     *
     * Only possible while the import is a draft, which is what PLAN 11.4.3 means
     * by a confirmed batch being read only. The target cell is checked in the same
     * transaction, so a draft can never be pointed at a cell that has just gone.
     *
     * @throws IllegalArgumentException if the draft is unknown, its import is no
     *   longer a draft, or the target cell is not available; nothing is written.
     */
    @Transaction
    open suspend fun setDraftTargetUnderReview(
        draftId: EntityId,
        targetCellId: EntityId?,
        poolType: PoolType?,
        trackingMode: TrackingMode?,
        updatedAt: Instant,
    ) {
        val draft = draftTaskById(draftId)
        requireNotNull(draft) { "There is no draft $draftId to aim." }
        val block = rawBlockById(draft.rawImportBlockId)
        requireNotNull(block) { "The draft $draftId comes from no raw cell." }
        val batch = batchById(block.importBatchId)
        requireNotNull(batch) { "The raw cell ${block.id} belongs to no import batch." }
        require(batch.status == ImportBatchStatus.DRAFT) {
            "A draft can only be aimed while its import is a draft, but ${batch.id} is ${batch.status}."
        }
        if (targetCellId != null) {
            require(activeCellCount(targetCellId) == 1) {
                "There is no cell $targetCellId to send a task to."
            }
            val columnType = requireNotNull(columnTypeOfCell(targetCellId)) { "The cell $targetCellId has no column." }
            require(columnType.holdsTasks) { "The $columnType column holds no tasks." }
            if (poolType != null) {
                require(columnType.poolType == poolType) {
                    "A $poolType task does not belong in the $columnType column."
                }
            }
        }
        if (poolType != null && trackingMode != null) {
            requireAllowedTrackingMode(poolType, trackingMode)
        }
        updateDraftTargetRow(draftId, targetCellId, poolType, trackingMode, updatedAt)
    }

    @Query(
        """
        UPDATE draft_tasks
        SET target_cell_id = :targetCellId,
            selected_pool_type = :poolType,
            selected_tracking_mode = :trackingMode,
            updated_at = :updatedAt
        WHERE id = :draftId
        """,
    )
    protected abstract suspend fun updateDraftTargetRow(
        draftId: EntityId,
        targetCellId: EntityId?,
        poolType: PoolType?,
        trackingMode: TrackingMode?,
        updatedAt: Instant,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertTask(task: TaskEntity)

    @Query("UPDATE draft_tasks SET materialized_task_id = :taskId, updated_at = :updatedAt WHERE id = :draftId")
    protected abstract suspend fun setDraftMaterializedTask(
        draftId: EntityId,
        taskId: EntityId,
        updatedAt: Instant,
    ): Int

    @Query(
        """
        UPDATE import_batches
        SET status = 'CONFIRMED', created_task_count = :createdTaskCount, updated_at = :updatedAt
        WHERE id = :batchId AND status = 'DRAFT'
        """,
    )
    protected abstract suspend fun markBatchConfirmed(
        batchId: EntityId,
        createdTaskCount: Int,
        updatedAt: Instant,
    ): Int

    /**
     * Turns every draft of one import into a real task, or does nothing at all.
     *
     * All of it lives in one transaction on purpose: the guards below are not
     * advice given beforehand but the conditions the writes happen under, so an
     * item cannot be deleted, and the batch cannot be confirmed by a second
     * caller, in the gap between checking and writing. Anything thrown from here
     * — a refused guard, a rejected row, a broken postcondition — takes every
     * write with it, which is what PLAN 11.4.2 means by no task remaining and no
     * counter moving after a failure.
     *
     * Confirming an import creates no game and no cell. It only writes tasks into
     * cells the user opened by hand, each one as a piece of that cell's document.
     *
     * @return how many tasks were created.
     * @throws ImportConfirmationException if the import cannot be confirmed.
     */
    @Transaction
    open suspend fun confirmDraftBatch(
        batchId: EntityId,
        acknowledgeUnprocessedBlocks: Boolean,
        moment: Instant,
        idGenerator: IdGenerator,
    ): Int {
        val batch = batchById(batchId) ?: refuse(ImportConfirmationFailure.BATCH_NOT_FOUND)
        when (batch.status) {
            // A guarded refusal, not a repeat success: the first run's tasks stand.
            ImportBatchStatus.CONFIRMED -> refuse(ImportConfirmationFailure.ALREADY_CONFIRMED)
            ImportBatchStatus.ROLLED_BACK -> refuse(ImportConfirmationFailure.BATCH_NOT_A_DRAFT)
            ImportBatchStatus.DRAFT -> Unit
        }
        require(batch.createdGameCount == 0) {
            "An import that never created a game says it created ${batch.createdGameCount}."
        }

        val drafts = draftTasksOfBatch(batchId)
        if (drafts.isEmpty()) refuse(ImportConfirmationFailure.NO_DRAFTS_TO_CONFIRM)
        if (activeCellCount() == 0) refuse(ImportConfirmationFailure.NO_CELLS_AVAILABLE)
        if (!acknowledgeUnprocessedBlocks && unprocessedRawBlockCount(batchId) > 0) {
            refuse(ImportConfirmationFailure.UNPROCESSED_BLOCKS_NOT_ACKNOWLEDGED)
        }

        var createdTaskCount = 0
        drafts.forEach { draft ->
            // One unready draft stops the whole batch; none is ever skipped.
            val targetCellId =
                draft.targetCellId
                    ?: refuse(ImportConfirmationFailure.TARGET_CELL_MISSING, draft.id)
            val poolType =
                draft.selectedPoolType
                    ?: refuse(ImportConfirmationFailure.POOL_TYPE_MISSING, draft.id)
            val trackingMode =
                draft.selectedTrackingMode
                    ?: refuse(ImportConfirmationFailure.TRACKING_MODE_MISSING, draft.id)
            if (activeCellCount(targetCellId) != 1) {
                refuse(ImportConfirmationFailure.TARGET_CELL_NOT_AVAILABLE, draft.id)
            }
            val columnType = columnTypeOfCell(targetCellId)
            if (columnType == null || columnType.poolType != poolType) {
                refuse(ImportConfirmationFailure.TARGET_CELL_WRONG_COLUMN, draft.id)
            }
            // A stored draft cannot hold a quantity of zero or a pool the mode
            // forbids, so either would be a defect rather than a user mistake.
            require(draft.requiredQuantity == null || draft.requiredQuantity > 0) {
                "The draft ${draft.id} holds a required quantity of ${draft.requiredQuantity}."
            }
            val taskId = idGenerator.newId()
            insertTask(
                TaskEntity(
                    id = taskId,
                    poolType = poolType,
                    trackingMode = trackingMode,
                    name = draft.name,
                    requiredQuantity = draft.requiredQuantity,
                    notes = draft.notes,
                    createdAt = moment,
                    updatedAt = moment,
                    sourceRawImportBlockId = draft.rawImportBlockId,
                ),
            )
            insertSegment(
                CellSegmentEntity.task(
                    id = idGenerator.newId(),
                    cellId = targetCellId,
                    orderIndex = nextSegmentIndex(targetCellId),
                    taskId = taskId,
                    moment = moment,
                ),
            )
            val aimed = setDraftMaterializedTask(draft.id, taskId, moment)
            check(aimed == 1) { "The draft ${draft.id} could not be linked to the task it produced." }
            createdTaskCount++
        }

        val confirmed = markBatchConfirmed(batchId, createdTaskCount, moment)
        check(confirmed == 1) { "The import $batchId was no longer a draft when it was about to be confirmed." }

        requireConfirmationHeld(batchId, drafts.size, createdTaskCount)
        return createdTaskCount
    }

    /**
     * Reads back what the transaction has just written and refuses to let it
     * stand unless it is exactly what was promised. Still inside the transaction,
     * so a broken postcondition rolls the whole confirmation back.
     */
    private suspend fun requireConfirmationHeld(
        batchId: EntityId,
        draftCount: Int,
        createdTaskCount: Int,
    ) {
        check(createdTaskCount == draftCount) {
            "$draftCount drafts should have produced $draftCount tasks, not $createdTaskCount."
        }
        val batch = batchById(batchId)
        checkNotNull(batch) { "The import $batchId disappeared while it was being confirmed." }
        check(batch.status == ImportBatchStatus.CONFIRMED) {
            "The import $batchId was left as ${batch.status} after being confirmed."
        }
        check(batch.createdTaskCount == createdTaskCount) {
            "The import $batchId says it created ${batch.createdTaskCount} tasks, not $createdTaskCount."
        }
        check(batch.createdGameCount == 0) {
            "Confirming an import created ${batch.createdGameCount} games; it must create none."
        }
        val unlinked = draftTasksOfBatch(batchId).count { it.materializedTaskId == null }
        check(unlinked == 0) { "$unlinked drafts were left without the task they produced." }
    }

    private fun refuse(
        failure: ImportConfirmationFailure,
        draftTaskId: EntityId? = null,
    ): Nothing = throw ImportConfirmationException(failure, draftTaskId)
}
