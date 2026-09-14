package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.DraftTaskColorEntity
import dev.pnptracker.data.database.entity.DraftTaskEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.entity.HistoryEventEntity
import dev.pnptracker.data.database.entity.ImportBatchCellEntity
import dev.pnptracker.data.database.entity.ImportBatchEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.entity.TaskStageEntity
import dev.pnptracker.data.database.entity.stageRowsFor
import dev.pnptracker.data.database.projection.CellColumnRow
import dev.pnptracker.data.database.projection.CellDocumentRow
import dev.pnptracker.data.database.projection.CellGameRow
import dev.pnptracker.data.database.projection.DraftRemovalFacts
import dev.pnptracker.data.database.projection.DraftTargetRow
import dev.pnptracker.data.database.projection.RollbackCellRow
import dev.pnptracker.data.database.projection.RollbackSegmentRow
import dev.pnptracker.data.database.projection.TableCounts
import dev.pnptracker.domain.games.TASK_SEPARATOR
import dev.pnptracker.domain.games.taskNeedsSeparatorAfter
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.importreview.DraftInitialValues
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.importreview.initialDraftByHand
import dev.pnptracker.domain.importreview.initialDraftFromSelection
import dev.pnptracker.domain.importreview.selectTaskNameIn
import dev.pnptracker.domain.importrollback.ImportRollbackException
import dev.pnptracker.domain.importrollback.ImportRollbackFacts
import dev.pnptracker.domain.importrollback.ImportRollbackPlan
import dev.pnptracker.domain.importrollback.ImportRollbackPreview
import dev.pnptracker.domain.importrollback.ImportRollbackResult
import dev.pnptracker.domain.importrollback.RollbackCellFacts
import dev.pnptracker.domain.importrollback.RollbackSegmentFacts
import dev.pnptracker.domain.importrollback.RollbackTaskFacts
import dev.pnptracker.domain.importrollback.planImportRollback
import dev.pnptracker.domain.importrollback.previewOf
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.model.stagesOf
import dev.pnptracker.domain.rules.isTrackingModeAllowed
import dev.pnptracker.domain.rules.requireAllowedTrackingMode
import dev.pnptracker.domain.tasks.CompletionRules
import dev.pnptracker.domain.text.graphemeBoundariesOf
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
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

    /**
     * Every import that has been confirmed, whether or not it was taken back.
     *
     * One statement over one table, and no join: the row already says which file
     * it came from, which sheet, how many tasks it made and where it stands, so
     * a batch cannot be multiplied by anything and asking about forty-two costs
     * what asking about one costs (PLAN 16).
     *
     * Newest first, with identity settling a tie, so two imports saved inside
     * the same millisecond keep a stable order rather than swapping places
     * between readings.
     */
    @Query(
        "SELECT * FROM import_batches WHERE status IN ('CONFIRMED', 'ROLLED_BACK') " +
            "ORDER BY imported_at DESC, id",
    )
    abstract fun observeSettledBatches(): Flow<List<ImportBatchEntity>>

    @Query("UPDATE raw_import_blocks SET is_processed = :isProcessed, updated_at = :updatedAt WHERE id = :id")
    abstract suspend fun markRawBlockProcessed(
        id: EntityId,
        isProcessed: Boolean,
        updatedAt: Instant,
    ): Int

    /**
     * Writes a hint answer and the game it names, with nothing checked.
     *
     * Protected, because on its own it can write a combination that is not a
     * decision: an acceptance with no game, or a game attached to a rejection.
     * [setGameCompletionDecisionUnderReview] is the way in, and it checks both
     * before this runs.
     */
    @Query(
        """
        UPDATE raw_import_blocks
        SET game_completion_hint = :decision, completion_target_game_id = :targetGameId, updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    protected abstract suspend fun updateGameCompletionDecision(
        id: EntityId,
        decision: HintDecision,
        targetGameId: EntityId?,
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
     * Removes an import the user never confirmed, or changes nothing at all.
     *
     * PLAN 11.4.5 lets this take exactly five kinds of row: the batch, its raw
     * cells, their drafts, those drafts' colours, and any cell snapshot a
     * damaged draft could be carrying. Games, cells, pieces, tasks, their
     * colours and stages, progress, history, the colour catalogue and every
     * other batch stay exactly as they are.
     *
     * Every decision is made inside this transaction, from rows read here, so a
     * confirmation or an edit cannot slip between deciding and deleting: Room
     * keeps one writer connection, and whichever of them holds it first finishes
     * before the other reads. The three refusals are returned rather than thrown
     * — nothing has been written when they are reached, and a second removal of
     * the same draft is an ordinary thing to ask, not a failure.
     *
     * The cost does not grow with the draft: two reads to decide, one to count
     * the tables, one delete (the rest follows by the schema's cascades, inside
     * the same statement) and two reads to prove it. A draft of one raw cell and
     * one of forty-two run the same six statements.
     *
     * @return what was removed, or why nothing was.
     * @throws IllegalStateException if the removal did not do exactly what it
     *   counted on doing. It is a defect, not an outcome, and it takes the whole
     *   transaction down with it.
     */
    @Transaction
    open suspend fun removeDraftBatch(batchId: EntityId): DraftRemovalOutcome {
        val batch = batchById(batchId) ?: return DraftRemovalOutcome.Refused(batchId, DraftRemovalRefusal.ALREADY_REMOVED)
        if (batch.status != ImportBatchStatus.DRAFT) {
            return DraftRemovalOutcome.Refused(batchId, DraftRemovalRefusal.NOT_A_DRAFT)
        }
        val own = draftRemovalFactsOf(batchId)
        if (own.holdingTaskCount > 0 || own.holdingGameCount > 0) {
            return DraftRemovalOutcome.Refused(batchId, DraftRemovalRefusal.HELD_BY_RECORDS)
        }

        val before = tableCounts()
        val removed = deleteDraftBatchRow(batchId)
        check(removed == 1) { "The draft import $batchId was no longer a draft when it was about to be removed." }
        requireRemovalHeld(batchId, own, before)

        return DraftRemovalOutcome.Removed(
            batchId = batchId,
            rawBlockCount = own.rawBlockCount,
            draftTaskCount = own.draftTaskCount,
            draftColorCount = own.draftColorCount,
            cellSnapshotCount = own.cellSnapshotCount,
        )
    }

    /**
     * Reads back what the removal has just done and refuses to let it stand
     * unless it is exactly what was counted. Still inside the transaction, so a
     * broken postcondition takes the whole removal down.
     *
     * Nothing of the batch may be left, and every table must have lost exactly
     * the rows counted as the batch's own — which for ten of the fifteen tables
     * is none. A cascade that reached further than PLAN 11.4.5 allows, or a
     * delete that did less than it should, both stop here.
     */
    private suspend fun requireRemovalHeld(
        batchId: EntityId,
        own: DraftRemovalFacts,
        before: TableCounts,
    ) {
        val left = draftRemovalFactsOf(batchId)
        check(left.isEmpty) { "The draft import $batchId left rows of its own behind: $left." }
        val expected =
            before.copy(
                importBatches = before.importBatches - 1,
                rawImportBlocks = before.rawImportBlocks - own.rawBlockCount,
                draftTasks = before.draftTasks - own.draftTaskCount,
                draftTaskColors = before.draftTaskColors - own.draftColorCount,
                importBatchCells = before.importBatchCells - own.cellSnapshotCount,
            )
        val after = tableCounts()
        check(after == expected) { "Removing the draft import $batchId left $after where $expected was expected." }
    }

    // ------------------------------------------- the colours of a draft task

    /** One draft's chosen colours, in the order the user picked them. */
    @Query("SELECT * FROM draft_task_colors WHERE draft_task_id = :draftTaskId ORDER BY slot_index")
    abstract suspend fun draftColorsOf(draftTaskId: EntityId): List<DraftTaskColorEntity>

    /**
     * Every chosen colour of a whole import, in one query.
     *
     * The workspace shows all of a batch's drafts at once, so asking each draft
     * for its own colours would put a query behind every row of the screen and a
     * flow behind every row of the workspace. Ordered by draft and then by slot,
     * so the caller groups rather than sorts: the order the colours are drawn in
     * is the user's own and must not be left to however SQLite returns them.
     */
    @Query(
        """
        SELECT draft_task_colors.* FROM draft_task_colors
        JOIN draft_tasks ON draft_tasks.id = draft_task_colors.draft_task_id
        JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        WHERE raw_import_blocks.import_batch_id = :batchId
        ORDER BY draft_task_colors.draft_task_id, draft_task_colors.slot_index
        """,
    )
    abstract fun observeDraftColorsOfBatch(batchId: EntityId): Flow<List<DraftTaskColorEntity>>

    /** The same, read once rather than watched. */
    @Query(
        """
        SELECT draft_task_colors.* FROM draft_task_colors
        JOIN draft_tasks ON draft_tasks.id = draft_task_colors.draft_task_id
        JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        WHERE raw_import_blocks.import_batch_id = :batchId
        ORDER BY draft_task_colors.draft_task_id, draft_task_colors.slot_index
        """,
    )
    abstract suspend fun draftColorsOfBatch(batchId: EntityId): List<DraftTaskColorEntity>

    /**
     * The whole colour catalogue, as identities.
     *
     * PLAN 5.2 makes colour the one exception to the tombstone rule, so every
     * row here is a colour that still exists and there is no filter to apply.
     * Read once for a whole list of chosen colours rather than asked about each
     * of them: the answer is the same either way, and a query per colour would
     * make choosing five colours cost five round trips.
     */
    @Query("SELECT id FROM colors")
    abstract suspend fun allColorIds(): List<EntityId>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertDraftColor(row: DraftTaskColorEntity)

    @Delete
    protected abstract suspend fun deleteDraftColor(row: DraftTaskColorEntity)

    /**
     * Replaces the colours of one draft with exactly [colorIds], in that order.
     *
     * The whole list at once rather than one colour at a time, because what has
     * to stay true is a property of the list: the slots are `0..N-1` with no
     * gaps, and no colour appears twice. Adding and removing separately would
     * leave a moment between two writes when neither held.
     *
     * Everything is checked before anything is written, and everything written
     * is written in one transaction, so a colour that turns out to have been
     * deleted leaves the draft with the list it had rather than with half a new
     * one. An empty list is a real answer — PLAN 5.10 makes no colour a valid
     * state — and so is a list with the same colours in a different order.
     *
     * Giving the list the draft already has is nothing at all: no write, no new
     * timestamp, and [clock] is not read, so saving twice cannot move a moment.
     *
     * @return true when this call changed something.
     * @throws ImportReviewException if the draft is gone, its import is no longer
     *   a draft, a colour was named twice, or a colour is not in the catalogue;
     *   nothing is written in any of those cases.
     */
    @Transaction
    open suspend fun setDraftColorsUnderReview(
        draftTaskId: EntityId,
        colorIds: List<EntityId>,
        clock: Clock,
    ): Boolean {
        val draft = draftTaskById(draftTaskId) ?: refuseReview(ImportReviewFailure.DRAFT_TASK_NOT_FOUND)
        requireDraftBatch(draft)

        // Which colour was named twice, found before anything is read: a
        // duplicate costs no query and certainly no write.
        if (colorIds.toSet().size != colorIds.size) refuseReview(ImportReviewFailure.DUPLICATE_COLOR)

        val existing = draftColorsOf(draftTaskId)
        // The same colours in the same places is the same answer. Compared
        // against the stored slots rather than against the list's own order, so
        // a stored list that had somehow drifted is still put right.
        if (existing.map { it.colorId } == colorIds && existing.withIndex().all { (at, row) -> row.slotIndex == at }) {
            return false
        }

        if (colorIds.isNotEmpty()) {
            val catalogue = allColorIds().toSet()
            if (colorIds.any { it !in catalogue }) refuseReview(ImportReviewFailure.COLOR_NOT_AVAILABLE)
        }

        // Out of the way first: renumbering in place would collide with the
        // unique slot index the moment one colour moved onto a place another
        // still held. Deleting the whole old list is simpler and, inside one
        // transaction, indistinguishable from moving the rows.
        existing.forEach { deleteDraftColor(it) }
        colorIds.forEachIndexed { slot, colorId ->
            insertDraftColor(DraftTaskColorEntity(draftTaskId = draftTaskId, colorId = colorId, slotIndex = slot))
        }
        touchDraftTask(draftTaskId, clock.now())
        return true
    }

    @Query("UPDATE draft_tasks SET updated_at = :updatedAt WHERE id = :id")
    protected abstract suspend fun touchDraftTask(
        id: EntityId,
        updatedAt: Instant,
    ): Int

    // --------------------------------------- the game an accepted hint names

    /** 1 while the game is there and has not been deleted. */
    @Query("SELECT COUNT(*) FROM games WHERE id = :gameId AND deleted_at IS NULL")
    abstract suspend fun activeGameCount(gameId: EntityId): Int

    /**
     * Records what the user answered to a green cell, and which game they meant.
     *
     * The answer and the game are one decision, so they are written together: an
     * acceptance without a game would be a yes to a question with no subject, and
     * a game left behind on a rejection would be an answer nobody gave. Taking
     * the acceptance back therefore clears the game in the same statement.
     *
     * This does **not** finish the game. PLAN 5.3 has an accepted import hint
     * change a game's completion, but that is a write to the game and belongs to
     * confirming the import; all that is stored here is the decision, so it
     * survives the review being closed and reopened (PLAN 11.4.3).
     *
     * Answering what has already been answered, about the same game, is nothing
     * at all: no write, no new timestamp, and [clock] is not read.
     *
     * @return true when this call changed something.
     * @throws ImportReviewException if the cell is gone, its import is no longer
     *   a draft, the cell is not one such a hint can be about, an acceptance
     *   named no game, a game was named for something other than an acceptance,
     *   or the game is gone; nothing is written in any of those cases.
     */
    @Transaction
    open suspend fun setGameCompletionDecisionUnderReview(
        blockId: EntityId,
        decision: HintDecision,
        targetGameId: EntityId?,
        clock: Clock,
    ): Boolean {
        val block = rawBlockById(blockId) ?: refuseReview(ImportReviewFailure.RAW_BLOCK_NOT_FOUND)
        val batch = batchById(block.importBatchId) ?: refuseReview(ImportReviewFailure.RAW_BLOCK_NOT_FOUND)
        if (batch.status != ImportBatchStatus.DRAFT) refuseReview(ImportReviewFailure.BATCH_NOT_A_DRAFT)
        // PLAN 11.5 puts the green cell in the game name column, so no other
        // cell can carry this answer whatever it was coloured.
        if (block.sourceColumnType != SourceColumnType.GAME) {
            refuseReview(ImportReviewFailure.BLOCK_CANNOT_CARRY_GAME_COMPLETION)
        }
        if (decision == HintDecision.ACCEPTED) {
            if (targetGameId == null) refuseReview(ImportReviewFailure.COMPLETION_TARGET_REQUIRED)
            if (activeGameCount(targetGameId) != 1) {
                refuseReview(ImportReviewFailure.COMPLETION_TARGET_GAME_NOT_AVAILABLE)
            }
        } else if (targetGameId != null) {
            refuseReview(ImportReviewFailure.COMPLETION_TARGET_NOT_ALLOWED)
        }

        if (block.gameCompletionHint == decision && block.completionTargetGameId == targetGameId) return false

        val changed = updateGameCompletionDecision(blockId, decision, targetGameId, clock.now())
        check(changed == 1) { "The raw cell $blockId disappeared while its hint was being answered." }
        return true
    }

    // --------------------------------------------- making a draft out of a cell

    /**
     * Cuts a draft out of the cell's own text, at the boundaries the user drew.
     *
     * The selection is read against the text **as the database holds it**, inside
     * this transaction, rather than against whatever the screen was showing: a
     * cell that has gone, or offsets that no longer describe whole characters,
     * are caught here and cost nothing. The name and both offsets therefore
     * always belong together and always belong to the stored cell.
     *
     * Everything else the new draft starts out saying — the pool its column
     * implies, the count the line opens with, the `**` kept as a question, the
     * missing and borrowed marks — is worked out from that same stored cell and
     * written with it, so a suggestion the user accepted by making the draft is
     * still there when the review is closed and opened again (PLAN 11.4.3).
     *
     * The cell itself is not touched. Its text is unchanged, and its review mark
     * stays whatever it was: having made something out of a cell is a different
     * judgement from having finished reading it.
     *
     * @return what the new draft holds, so the caller need not read it back.
     * @throws ImportReviewException if the cell is gone, its import is no longer
     *   a draft, or the selection is not one a name can be cut from; nothing is
     *   written in any of those cases.
     */
    @Transaction
    open suspend fun createDraftFromSelectionUnderReview(
        draftId: EntityId,
        blockId: EntityId,
        startIndex: Int,
        endIndex: Int,
        clock: Clock,
    ): DraftTaskEntity {
        val block = rawBlockById(blockId) ?: refuseReview(ImportReviewFailure.RAW_BLOCK_NOT_FOUND)
        requireDraftBatchOf(block)
        val selection = selectTaskNameIn(block.rawText, startIndex, endIndex)
        return storeNewDraft(draftId, block, initialDraftFromSelection(block.columnIndex, selection), clock)
    }

    /**
     * Stores a draft the user typed rather than cut out of the text.
     *
     * It carries no selection at all, which is the difference the shape itself
     * records: PLAN 11.4 offers making an empty task by hand as its own action,
     * and a draft with invented offsets would claim to come from words nobody
     * pointed at. The column's own marks still apply, because the cell is in the
     * missing or borrowed list whether the words came out of it or not.
     *
     * @throws ImportReviewException if the cell is gone, its import is no longer
     *   a draft, or the name says nothing; nothing is written in those cases.
     */
    @Transaction
    open suspend fun createDraftByHandUnderReview(
        draftId: EntityId,
        blockId: EntityId,
        name: String,
        clock: Clock,
    ): DraftTaskEntity {
        val block = rawBlockById(blockId) ?: refuseReview(ImportReviewFailure.RAW_BLOCK_NOT_FOUND)
        requireDraftBatchOf(block)
        return storeNewDraft(draftId, block, initialDraftByHand(block.columnIndex, name), clock)
    }

    private suspend fun storeNewDraft(
        draftId: EntityId,
        block: RawImportBlockEntity,
        initial: DraftInitialValues,
        clock: Clock,
    ): DraftTaskEntity {
        val moment = clock.now()
        val draft =
            DraftTaskEntity(
                id = draftId,
                rawImportBlockId = block.id,
                name = initial.name,
                suggestedPoolType = initial.suggestedPoolType,
                requiredQuantity = initial.requiredQuantity,
                selectionStartIndex = initial.selectionStartIndex,
                selectionEndIndex = initial.selectionEndIndex,
                completionHint = initial.completionHint,
                isMissing = initial.isMissing,
                isBorrowed = initial.isBorrowed,
                needsInfo = initial.needsInfo,
                needsClassification = initial.needsClassification,
                createdAt = moment,
                updatedAt = moment,
            )
        insertDraftTaskRow(draft)
        return draft
    }

    // ------------------------------------------------- editing a whole draft

    /**
     * Saves everything the user changed about one draft, together.
     *
     * A name, a target, a pool, a total, a note, four marks and an ordered list
     * of colours are one answer to what the task is going to be, so they are
     * written in one transaction or not at all. A refusal anywhere in it leaves
     * the draft exactly as it was, colours included, and the panel still holding
     * what the user typed.
     *
     * The suggestions the draft was born with are not consulted here and are not
     * rewritten: `suggestedPoolType` stays what the column said, and everything
     * the user answers is stored beside it. That is what keeps a value somebody
     * edited from being pushed back by the next reading of the cell.
     *
     * A `**` that was never there cannot be answered and one that was there
     * cannot be made to disappear: [completionHint] may move between pending,
     * accepted and rejected, and may not cross to or from
     * [HintDecision.NONE]. PLAN 11.5 makes the marker a fact about the file.
     *
     * Saving the same answer again is nothing at all: no write, no new timestamp,
     * and [clock] is not read.
     *
     * @return true when this call changed something.
     * @throws ImportReviewException with the case that stopped it; nothing written.
     */
    @Transaction
    open suspend fun editDraftUnderReview(
        draftTaskId: EntityId,
        name: String,
        targetCellId: EntityId?,
        poolType: PoolType?,
        trackingMode: TrackingMode?,
        requiredQuantity: Int?,
        notes: String?,
        isMissing: Boolean,
        isBorrowed: Boolean,
        needsInfo: Boolean,
        needsClassification: Boolean,
        completionHint: HintDecision,
        colorIds: List<EntityId>,
        clock: Clock,
    ): Boolean {
        val draft = draftTaskById(draftTaskId) ?: refuseReview(ImportReviewFailure.DRAFT_TASK_NOT_FOUND)
        requireDraftBatch(draft)

        val cleanName = name.trim()
        if (cleanName.isEmpty()) refuseReview(ImportReviewFailure.TASK_NAME_EMPTY)
        if (cleanName.any { it == '\n' || it == '\r' }) refuseReview(ImportReviewFailure.SELECTION_CONTAINS_LINE_BREAK)
        if (requiredQuantity != null && requiredQuantity <= 0) {
            refuseReview(ImportReviewFailure.INVALID_REQUIRED_QUANTITY)
        }
        if (isMissing && isBorrowed) refuseReview(ImportReviewFailure.MISSING_AND_BORROWED)
        if ((draft.completionHint == HintDecision.NONE) != (completionHint == HintDecision.NONE)) {
            refuseReview(ImportReviewFailure.COMPLETION_HINT_NOT_ANSWERABLE)
        }
        if (poolType != null && trackingMode != null && !isTrackingModeAllowed(poolType, trackingMode)) {
            refuseReview(ImportReviewFailure.TRACKING_MODE_NOT_ALLOWED)
        }
        if (targetCellId != null) requireUsableTarget(targetCellId, poolType)

        // Named twice is found before anything is read: a duplicate costs no
        // query and certainly no write.
        if (colorIds.toSet().size != colorIds.size) refuseReview(ImportReviewFailure.DUPLICATE_COLOR)
        val existing = draftColorsOf(draftTaskId)
        val colorsHold =
            existing.map { it.colorId } == colorIds &&
                existing.withIndex().all { (at, row) -> row.slotIndex == at }
        if (!colorsHold && colorIds.isNotEmpty()) {
            val catalogue = allColorIds().toSet()
            if (colorIds.any { it !in catalogue }) refuseReview(ImportReviewFailure.COLOR_NOT_AVAILABLE)
        }

        val fieldsHold =
            draft.name == cleanName &&
                draft.targetCellId == targetCellId &&
                draft.selectedPoolType == poolType &&
                draft.selectedTrackingMode == trackingMode &&
                draft.requiredQuantity == requiredQuantity &&
                draft.notes == notes &&
                draft.isMissing == isMissing &&
                draft.isBorrowed == isBorrowed &&
                draft.needsInfo == needsInfo &&
                draft.needsClassification == needsClassification &&
                draft.completionHint == completionHint
        if (fieldsHold && colorsHold) return false

        val moment = clock.now()
        if (!fieldsHold) {
            val written =
                updateDraftRow(
                    draftId = draftTaskId,
                    name = cleanName,
                    targetCellId = targetCellId,
                    poolType = poolType,
                    trackingMode = trackingMode,
                    requiredQuantity = requiredQuantity,
                    notes = notes,
                    isMissing = isMissing,
                    isBorrowed = isBorrowed,
                    needsInfo = needsInfo,
                    needsClassification = needsClassification,
                    completionHint = completionHint,
                    updatedAt = moment,
                )
            check(written == 1) { "The draft $draftTaskId disappeared while it was being saved." }
        }
        if (!colorsHold) {
            // Out of the way first: renumbering in place would collide with the
            // unique slot index the moment one colour moved onto a place another
            // still held.
            existing.forEach { deleteDraftColor(it) }
            colorIds.forEachIndexed { slot, colorId ->
                insertDraftColor(DraftTaskColorEntity(draftTaskId = draftTaskId, colorId = colorId, slotIndex = slot))
            }
            if (fieldsHold) touchDraftTask(draftTaskId, moment)
        }
        return true
    }

    @Query(
        """
        UPDATE draft_tasks
        SET name = :name,
            target_cell_id = :targetCellId,
            selected_pool_type = :poolType,
            selected_tracking_mode = :trackingMode,
            required_quantity = :requiredQuantity,
            notes = :notes,
            is_missing = :isMissing,
            is_borrowed = :isBorrowed,
            needs_info = :needsInfo,
            needs_classification = :needsClassification,
            completion_hint = :completionHint,
            updated_at = :updatedAt
        WHERE id = :draftId
        """,
    )
    protected abstract suspend fun updateDraftRow(
        draftId: EntityId,
        name: String,
        targetCellId: EntityId?,
        poolType: PoolType?,
        trackingMode: TrackingMode?,
        requiredQuantity: Int?,
        notes: String?,
        isMissing: Boolean,
        isBorrowed: Boolean,
        needsInfo: Boolean,
        needsClassification: Boolean,
        completionHint: HintDecision,
        updatedAt: Instant,
    ): Int

    /**
     * Answers the `**` of one draft, and nothing else about it.
     *
     * Its own transaction because it is its own decision: PLAN 11.4 lists
     * accepting or rejecting the marker beside the other actions, and a user who
     * only wants to say "this one is done" should not have to save a form to do
     * it. A draft the file never marked has no question to answer, so it is
     * refused rather than given one.
     *
     * Nothing is completed here. The answer is stored, and the task it produces
     * is born finished — or not — when the import is confirmed.
     *
     * @return true when this call changed something.
     * @throws ImportReviewException if the draft is gone, its import is no longer
     *   a draft, or the file left no marker to answer; nothing is written.
     */
    @Transaction
    open suspend fun setDraftCompletionDecisionUnderReview(
        draftTaskId: EntityId,
        decision: HintDecision,
        clock: Clock,
    ): Boolean {
        val draft = draftTaskById(draftTaskId) ?: refuseReview(ImportReviewFailure.DRAFT_TASK_NOT_FOUND)
        requireDraftBatch(draft)
        if (draft.completionHint == HintDecision.NONE || decision == HintDecision.NONE) {
            refuseReview(ImportReviewFailure.COMPLETION_HINT_NOT_ANSWERABLE)
        }
        if (draft.completionHint == decision) return false
        val changed = updateDraftCompletionHint(draftTaskId, decision, clock.now())
        check(changed == 1) { "The draft $draftTaskId disappeared while its marker was being answered." }
        return true
    }

    /** The cell a draft is aimed at has to be there, hold tasks and match its pool. */
    private suspend fun requireUsableTarget(
        targetCellId: EntityId,
        poolType: PoolType?,
    ) {
        if (activeCellCount(targetCellId) != 1) refuseReview(ImportReviewFailure.TARGET_CELL_NOT_AVAILABLE)
        val columnType =
            columnTypeOfCell(targetCellId) ?: refuseReview(ImportReviewFailure.TARGET_CELL_NOT_AVAILABLE)
        if (!columnType.holdsTasks) refuseReview(ImportReviewFailure.TARGET_CELL_NOT_TASK_CAPABLE)
        if (poolType != null && columnType.poolType != poolType) {
            refuseReview(ImportReviewFailure.TARGET_CELL_WRONG_COLUMN)
        }
    }

    /** The import one raw cell belongs to, refusing unless it is still being reviewed. */
    private suspend fun requireDraftBatchOf(block: RawImportBlockEntity) {
        val batch = batchById(block.importBatchId) ?: refuseReview(ImportReviewFailure.RAW_BLOCK_NOT_FOUND)
        if (batch.status != ImportBatchStatus.DRAFT) refuseReview(ImportReviewFailure.BATCH_NOT_A_DRAFT)
    }

    /** The import a draft belongs to, refusing unless it is still being reviewed. */
    private suspend fun requireDraftBatch(draft: DraftTaskEntity) {
        val block = rawBlockById(draft.rawImportBlockId) ?: refuseReview(ImportReviewFailure.RAW_BLOCK_NOT_FOUND)
        val batch = batchById(block.importBatchId) ?: refuseReview(ImportReviewFailure.RAW_BLOCK_NOT_FOUND)
        if (batch.status != ImportBatchStatus.DRAFT) refuseReview(ImportReviewFailure.BATCH_NOT_A_DRAFT)
    }

    private fun refuseReview(failure: ImportReviewFailure): Nothing = throw ImportReviewException(failure)

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

    /**
     * Guarded by [removeDraftBatch]; the status check is repeated in SQL.
     *
     * The raw cells, their drafts, the drafts' colours and any cell snapshot go
     * with the row by the schema's own `ON DELETE CASCADE`, inside this one
     * statement. A game or a task pointing at the batch would stop it with
     * `RESTRICT`, but [removeDraftBatch] has already looked for both.
     */
    @Query("DELETE FROM import_batches WHERE id = :id AND status = 'DRAFT'")
    protected abstract suspend fun deleteDraftBatchRow(id: EntityId): Int

    /** See [DraftRemovalFacts]. */
    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM import_batches WHERE id = :batchId) AS batch_count,
          (SELECT COUNT(*) FROM raw_import_blocks WHERE import_batch_id = :batchId) AS raw_block_count,
          (SELECT COUNT(*) FROM draft_tasks
             JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
             WHERE raw_import_blocks.import_batch_id = :batchId) AS draft_task_count,
          (SELECT COUNT(*) FROM draft_task_colors
             JOIN draft_tasks ON draft_tasks.id = draft_task_colors.draft_task_id
             JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
             WHERE raw_import_blocks.import_batch_id = :batchId) AS draft_color_count,
          (SELECT COUNT(*) FROM import_batch_cells WHERE import_batch_id = :batchId) AS cell_snapshot_count,
          (SELECT COUNT(*) FROM tasks
             JOIN raw_import_blocks ON raw_import_blocks.id = tasks.source_raw_import_block_id
             WHERE raw_import_blocks.import_batch_id = :batchId) AS holding_task_count,
          (SELECT COUNT(*) FROM games WHERE source_import_batch_id = :batchId) AS holding_game_count
        """,
    )
    protected abstract suspend fun draftRemovalFactsOf(batchId: EntityId): DraftRemovalFacts

    /** See [TableCounts]. */
    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM colors) AS colors,
          (SELECT COUNT(*) FROM color_aliases) AS color_aliases,
          (SELECT COUNT(*) FROM games) AS games,
          (SELECT COUNT(*) FROM game_cells) AS game_cells,
          (SELECT COUNT(*) FROM tasks) AS tasks,
          (SELECT COUNT(*) FROM cell_segments) AS cell_segments,
          (SELECT COUNT(*) FROM task_colors) AS task_colors,
          (SELECT COUNT(*) FROM task_stages) AS task_stages,
          (SELECT COUNT(*) FROM progress_events) AS progress_events,
          (SELECT COUNT(*) FROM history_events) AS history_events,
          (SELECT COUNT(*) FROM import_batches) AS import_batches,
          (SELECT COUNT(*) FROM raw_import_blocks) AS raw_import_blocks,
          (SELECT COUNT(*) FROM draft_tasks) AS draft_tasks,
          (SELECT COUNT(*) FROM draft_task_colors) AS draft_task_colors,
          (SELECT COUNT(*) FROM import_batch_cells) AS import_batch_cells
        """,
    )
    protected abstract suspend fun tableCounts(): TableCounts

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

    /**
     * The cells among [cellIds] that are still there, with the column each one
     * belongs to.
     *
     * One query for a whole batch of drafts rather than one per draft: the review
     * screen re-reads its summary after every change the user makes, and asking
     * the same question separately for each draft turned a single decision into a
     * pile of round trips. A cell that is gone, or whose game has been deleted,
     * simply does not come back — which is the same answer the per-cell count
     * gave, arrived at once.
     */
    @Query(
        """
        SELECT game_cells.id AS cell_id, game_cells.column_type AS column_type
        FROM game_cells
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE game_cells.id IN (:cellIds) AND games.deleted_at IS NULL
        """,
    )
    abstract suspend fun activeCellColumns(cellIds: Collection<EntityId>): List<CellColumnRow>

    @Query("SELECT COALESCE(MAX(order_index), -1) + 1 FROM cell_segments WHERE cell_id = :cellId")
    abstract suspend fun nextSegmentIndex(cellId: EntityId): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSegment(segment: CellSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertStage(stage: TaskStageEntity)

    /**
     * Records the user's decisions about one draft: where the task will go, which
     * pool it belongs to and how it is tracked.
     *
     * Only possible while the import is a draft, which is what PLAN 11.4.3 means
     * by a confirmed batch being read only. The target cell is checked in the same
     * transaction, so a draft can never be pointed at a cell that has just gone.
     *
     * The three ways a target can be wrong come back as an
     * [ImportConfirmationException] carrying which one it was, because all three
     * are things a user can reach and each needs its own sentence on screen. A
     * draft or an import that is not there is a different matter and stays an
     * [IllegalArgumentException]: the screen aims drafts it is showing, so being
     * handed one that does not exist is a defect and travels out as one.
     *
     * @throws ImportConfirmationException with
     *   [ImportConfirmationFailure.TARGET_CELL_NOT_AVAILABLE],
     *   [ImportConfirmationFailure.TARGET_CELL_NOT_TASK_CAPABLE] or
     *   [ImportConfirmationFailure.TARGET_CELL_WRONG_COLUMN]; nothing is written.
     * @throws IllegalArgumentException if the draft is unknown or its import is no
     *   longer a draft; nothing is written in that case either.
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
            if (activeCellCount(targetCellId) != 1) {
                refuse(ImportConfirmationFailure.TARGET_CELL_NOT_AVAILABLE, draftId)
            }
            val columnType =
                columnTypeOfCell(targetCellId)
                    ?: refuse(ImportConfirmationFailure.TARGET_CELL_NOT_AVAILABLE, draftId)
            if (!columnType.holdsTasks) {
                refuse(ImportConfirmationFailure.TARGET_CELL_NOT_TASK_CAPABLE, draftId)
            }
            if (poolType != null && columnType.poolType != poolType) {
                refuse(ImportConfirmationFailure.TARGET_CELL_WRONG_COLUMN, draftId)
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

    /**
     * Every cell this import is aiming at, with its column, its game and where
     * its document ends — all of it in one query, for the whole batch.
     *
     * Joined from the batch rather than from a list of cell identifiers, so the
     * statement has one shape however many drafts there are: a generated
     * `IN (?, ?, ...)` would be a new statement for every different number of
     * targets and would meet SQLite's parameter ceiling on a large import.
     *
     * The `LEFT JOIN` is what lets an empty cell answer at all; without it a cell
     * with no pieces yet would simply not come back and would look deleted.
     *
     * `COUNT(DISTINCT …)` rather than `COUNT(…)`, because two drafts aiming at
     * one cell meet every piece of that cell twice: a plain count would report a
     * cell of three pieces as holding six and call a sound document damaged.
     */
    @Query(
        """
        SELECT game_cells.id AS cell_id,
               game_cells.column_type AS column_type,
               game_cells.game_id AS game_id,
               COALESCE(MAX(cell_segments.order_index), -1) + 1 AS next_order_index,
               COUNT(DISTINCT cell_segments.id) AS segment_count
        FROM draft_tasks
        INNER JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        INNER JOIN game_cells ON game_cells.id = draft_tasks.target_cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        LEFT JOIN cell_segments ON cell_segments.cell_id = game_cells.id
        WHERE raw_import_blocks.import_batch_id = :batchId AND games.deleted_at IS NULL
        GROUP BY game_cells.id
        """,
    )
    abstract suspend fun draftTargetsOfBatch(batchId: EntityId): List<DraftTargetRow>

    /**
     * The whole document of every cell this import is aiming at, in reading
     * order, with each piece resolved to the words it contributes.
     *
     * One statement for the batch, joined from the batch the same way the target
     * lookup is, so reading forty-two cells costs what reading one does. Two
     * drafts aiming at the same cell meet each of its pieces twice, which is what
     * the `GROUP BY` is for: without it a cell would be reported as holding its
     * document twice over.
     *
     * The import needs this to know where a cell's writing ends before it appends
     * to it — whether the last thing in the cell is a task, a piece of text, or
     * nothing at all — and to read the finished document back and check it says
     * what was promised.
     */
    @Query(
        """
        SELECT cell_segments.cell_id AS cell_id,
               cell_segments.order_index AS order_index,
               COALESCE(cell_segments.text, tasks.name) AS text
        FROM draft_tasks
        INNER JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        INNER JOIN cell_segments ON cell_segments.cell_id = draft_tasks.target_cell_id
        LEFT JOIN tasks ON tasks.id = cell_segments.task_id
        WHERE raw_import_blocks.import_batch_id = :batchId
        GROUP BY cell_segments.id
        ORDER BY cell_segments.cell_id, cell_segments.order_index
        """,
    )
    abstract suspend fun targetCellDocumentsOfBatch(batchId: EntityId): List<CellDocumentRow>

    /**
     * The games an import's accepted green cells point at, still active.
     *
     * One row per game however many cells name it, so answering the same thing
     * about one game three times cannot cost three reads or three writes.
     */
    @Query(
        """
        SELECT games.* FROM games
        INNER JOIN raw_import_blocks ON raw_import_blocks.completion_target_game_id = games.id
        WHERE raw_import_blocks.import_batch_id = :batchId
          AND raw_import_blocks.game_completion_hint = 'ACCEPTED'
          AND games.deleted_at IS NULL
        GROUP BY games.id
        """,
    )
    abstract suspend fun acceptedCompletionTargetsOfBatch(batchId: EntityId): List<GameEntity>

    /** Records the user's own judgement that a game is finished, without touching its work. */
    @Query(
        """
        UPDATE games
        SET is_manually_completed = 1, completed_at = :moment, updated_at = :moment
        WHERE id = :gameId AND deleted_at IS NULL AND is_manually_completed = 0
        """,
    )
    protected abstract suspend fun markGameManuallyCompleted(
        gameId: EntityId,
        moment: Instant,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertTaskColor(taskColor: TaskColorEntity)

    // ----------------------------------------------- reading back what was written

    /** Every task this import produced, reached through the drafts that made them. */
    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN draft_tasks ON draft_tasks.materialized_task_id = tasks.id
        INNER JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        WHERE raw_import_blocks.import_batch_id = :batchId
        ORDER BY tasks.id
        """,
    )
    abstract suspend fun tasksOfConfirmedBatch(batchId: EntityId): List<TaskEntity>

    /** Every colour of every task this import produced, in slot order. */
    @Query(
        """
        SELECT task_colors.* FROM task_colors
        INNER JOIN draft_tasks ON draft_tasks.materialized_task_id = task_colors.task_id
        INNER JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        WHERE raw_import_blocks.import_batch_id = :batchId
        ORDER BY task_colors.task_id, task_colors.slot_index
        """,
    )
    abstract suspend fun taskColorsOfConfirmedBatch(batchId: EntityId): List<TaskColorEntity>

    /** Every stage of every task this import produced, in pipeline order. */
    @Query(
        """
        SELECT task_stages.* FROM task_stages
        INNER JOIN draft_tasks ON draft_tasks.materialized_task_id = task_stages.task_id
        INNER JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        WHERE raw_import_blocks.import_batch_id = :batchId
        ORDER BY task_stages.task_id, task_stages.order_index
        """,
    )
    abstract suspend fun taskStagesOfConfirmedBatch(batchId: EntityId): List<TaskStageEntity>

    /** Every piece of a cell this import wrote, reached the same way. */
    @Query(
        """
        SELECT cell_segments.* FROM cell_segments
        INNER JOIN draft_tasks ON draft_tasks.materialized_task_id = cell_segments.task_id
        INNER JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        WHERE raw_import_blocks.import_batch_id = :batchId
        ORDER BY cell_segments.cell_id, cell_segments.order_index
        """,
    )
    abstract suspend fun segmentsOfConfirmedBatch(batchId: EntityId): List<CellSegmentEntity>

    /**
     * What every cell this import wrote into said before it did.
     *
     * One row per cell, in cell order, whatever the batch aimed at each of them.
     * A confirmed batch with no rows here was confirmed before there was
     * anywhere to keep this, which PLAN 11.4.4 makes a refusal rather than
     * something to work out afterwards.
     */
    @Query("SELECT * FROM import_batch_cells WHERE import_batch_id = :batchId ORDER BY cell_id")
    abstract suspend fun cellSnapshotsOfBatch(batchId: EntityId): List<ImportBatchCellEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertBatchCell(snapshot: ImportBatchCellEntity)

    // -------------------------------------------------- reading a rollback's facts

    /**
     * Which of this import's tasks a shortage was ever reported against.
     *
     * One statement for the batch rather than one per task: PLAN 16 rules out a
     * read per record, and this is asked about every task the import made.
     */
    @Query(
        """
        SELECT DISTINCT progress_events.task_id FROM progress_events
        INNER JOIN draft_tasks ON draft_tasks.materialized_task_id = progress_events.task_id
        INNER JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        WHERE raw_import_blocks.import_batch_id = :batchId
        """,
    )
    abstract suspend fun taskIdsWithProgressOfBatch(batchId: EntityId): List<EntityId>

    /** Which of this import's tasks the history has already recorded something about. */
    @Query(
        """
        SELECT DISTINCT history_events.task_id FROM history_events
        INNER JOIN draft_tasks ON draft_tasks.materialized_task_id = history_events.task_id
        INNER JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        WHERE raw_import_blocks.import_batch_id = :batchId
        """,
    )
    abstract suspend fun taskIdsWithHistoryOfBatch(batchId: EntityId): List<EntityId>

    /**
     * Every cell this import recorded, with its game and column named.
     *
     * Driven from `import_batch_cells` on purpose. A batch confirmed before
     * schema version 8 returns nothing here, and PLAN 11.4.4 turns that absence
     * into a refusal rather than into a guess.
     */
    @Query(
        """
        SELECT import_batch_cells.cell_id AS cell_id,
               import_batch_cells.document_before AS document_before,
               game_cells.game_id AS game_id,
               games.name AS game_name,
               game_cells.column_type AS column_type
        FROM import_batch_cells
        LEFT JOIN game_cells ON game_cells.id = import_batch_cells.cell_id
        LEFT JOIN games ON games.id = game_cells.game_id
        WHERE import_batch_cells.import_batch_id = :batchId
        ORDER BY import_batch_cells.cell_id
        """,
    )
    abstract suspend fun rollbackCellsOfBatch(batchId: EntityId): List<RollbackCellRow>

    /**
     * Every piece of those cells as they stand now, in reading order.
     *
     * One statement for the whole batch, so a rollback touching forty-two cells
     * asks what one asks. The join to `tasks` resolves a task piece to the words
     * it contributes; it cannot multiply a row, because `tasks.id` is the
     * primary key and a piece names at most one.
     */
    @Query(
        """
        SELECT cell_segments.cell_id AS cell_id,
               cell_segments.id AS segment_id,
               cell_segments.order_index AS order_index,
               cell_segments.kind AS kind,
               cell_segments.text AS text,
               cell_segments.task_id AS task_id,
               COALESCE(cell_segments.text, tasks.name) AS document_text
        FROM import_batch_cells
        INNER JOIN cell_segments ON cell_segments.cell_id = import_batch_cells.cell_id
        LEFT JOIN tasks ON tasks.id = cell_segments.task_id
        WHERE import_batch_cells.import_batch_id = :batchId
        ORDER BY cell_segments.cell_id, cell_segments.order_index
        """,
    )
    abstract suspend fun rollbackSegmentsOfBatch(batchId: EntityId): List<RollbackSegmentRow>

    /** The games of the cells this import is about to write into, one row each. */
    @Query(
        """
        SELECT game_cells.id AS cell_id, game_cells.game_id AS game_id
        FROM game_cells
        INNER JOIN draft_tasks ON draft_tasks.target_cell_id = game_cells.id
        INNER JOIN raw_import_blocks ON raw_import_blocks.id = draft_tasks.raw_import_block_id
        WHERE raw_import_blocks.import_batch_id = :batchId
        GROUP BY game_cells.id
        ORDER BY game_cells.id
        """,
    )
    abstract suspend fun targetCellGamesOfBatch(batchId: EntityId): List<CellGameRow>

    // ------------------------------------------------------- counting, for proofs

    @Query("SELECT COUNT(*) FROM tasks")
    protected abstract suspend fun countTasks(): Int

    @Query("SELECT COUNT(*) FROM cell_segments")
    protected abstract suspend fun countSegments(): Int

    @Query("SELECT COUNT(*) FROM progress_events")
    protected abstract suspend fun countProgressEvents(): Int

    @Query("SELECT COUNT(*) FROM history_events")
    protected abstract suspend fun countHistoryEvents(): Int

    /**
     * How many of the lines this transaction meant to write are really there,
     * carrying the kind and the moment they were written with.
     *
     * By identity rather than by kind and time alone. Two imports confirmed
     * inside the same millisecond — which a test with a stopped clock does on
     * purpose, and two quick saves could do for real — would otherwise count
     * each other's lines and let a transaction that wrote nothing look correct.
     */
    @Query(
        "SELECT COUNT(*) FROM history_events " +
            "WHERE id IN (:eventIds) AND kind = :kind AND occurred_at = :moment",
    )
    protected abstract suspend fun countHistoryEventsWritten(
        eventIds: Collection<EntityId>,
        kind: HistoryEventKind,
        moment: Instant,
    ): Int

    // ------------------------------------------------------ writing a rollback

    /**
     * Appends one line to the history.
     *
     * Public only because Room offers no other visibility on an abstract member
     * it generates. There is no update and no delete for these rows anywhere in
     * the application: appending is the whole of what the table supports
     * (PLAN 5.12).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun appendHistoryEvent(event: HistoryEventEntity)

    /** Takes a task out of view without destroying it (PLAN 5.2). */
    @Query(
        "UPDATE tasks SET deleted_at = :moment, updated_at = :moment WHERE id = :taskId AND deleted_at IS NULL",
    )
    protected abstract suspend fun writeTaskTombstone(
        taskId: EntityId,
        moment: Instant,
    ): Int

    @Query("DELETE FROM cell_segments WHERE id = :segmentId")
    protected abstract suspend fun deleteSegmentRow(segmentId: EntityId): Int

    @Query("UPDATE game_cells SET updated_at = :moment WHERE id = :cellId")
    protected abstract suspend fun touchCell(
        cellId: EntityId,
        moment: Instant,
    ): Int

    /**
     * Moves the batch to `ROLLED_BACK`, and only from `CONFIRMED`.
     *
     * The status is in the `WHERE` clause rather than checked beforehand, so two
     * callers racing at the same batch cannot both succeed: the second one
     * changes no row and the check on the count below takes its whole
     * transaction down.
     */
    @Query(
        "UPDATE import_batches SET status = 'ROLLED_BACK', updated_at = :moment " +
            "WHERE id = :batchId AND status = 'CONFIRMED'",
    )
    protected abstract suspend fun markBatchRolledBack(
        batchId: EntityId,
        moment: Instant,
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
     * advice given beforehand but the conditions the writes happen under, so a
     * cell cannot be deleted, and the batch cannot be confirmed by a second
     * caller, in the gap between checking and writing. Anything thrown from here
     * — a refused guard, a rejected row, a broken postcondition — takes every
     * write with it, which is what PLAN 11.4.2 means by no task remaining and no
     * counter moving after a failure.
     *
     * Confirming an import creates no game and no cell. It only writes tasks into
     * cells the user opened by hand, each one as a piece of that cell's document,
     * with a single space between it and whatever it follows so that two tasks
     * written into one cell do not read as one word (PLAN 5.5).
     *
     * A card or board task arrives with its whole pipeline, exactly as one typed
     * by hand does. There is no second kind of task with stages missing.
     *
     * @return how many tasks were created.
     * @throws ImportConfirmationException if the import cannot be confirmed.
     */
    @Transaction
    open suspend fun confirmDraftBatch(
        batchId: EntityId,
        acknowledgeUnprocessedBlocks: Boolean,
        clock: Clock,
        idGenerator: IdGenerator,
    ): Int {
        val plan = plannedConfirmationOf(batchId, acknowledgeUnprocessedBlocks)

        // Nothing above this line has written, read a clock or made a name. Every
        // identity is made now, before the first insert and the separators
        // included: an identifier that ran out half way would otherwise leave a
        // batch of rows behind it and a task with no space in front of it.
        val named =
            plan.drafts.map { piece ->
                NamedTask(
                    piece = piece,
                    taskId = idGenerator.newId(),
                    segmentId = idGenerator.newId(),
                    separatorId = piece.separatorOrderIndex?.let { idGenerator.newId() },
                )
            }
        // The history lines are named here too, for the same reason: a generator
        // that ran out after the tasks were written would leave an import that
        // happened and a history that never heard of it.
        val confirmationEventIds = plan.gamesWrittenInto.associateWith { idGenerator.newId() }
        // One reading of the clock for the whole act, taken once everything that
        // could still refuse has been decided.
        val moment = clock.now()

        // The cells as the user left them, kept before a single character of
        // this import goes into any of them. Written here rather than worked out
        // later because after the first insert below the evidence is gone: PLAN
        // 11.4.4 has a rollback restore what was there, and what was there is
        // only knowable now. One row per cell however many drafts aim at it —
        // they all share the one document that preceded all of them.
        plan.drafts.map { it.targetCellId }.distinct().forEach { cellId ->
            insertBatchCell(
                ImportBatchCellEntity(
                    importBatchId = batchId,
                    cellId = cellId,
                    // A cell nobody had written in yet is stored as the empty
                    // string. Leaving the row out would say something else —
                    // that this import never touched the cell.
                    documentBefore = plan.documentTextsBefore[cellId].orEmpty(),
                ),
            )
        }

        named.forEach { made ->
            val (piece, taskId, segmentId, separatorId) = made
            insertTask(piece.taskRow(taskId, moment))
            piece.colorIds.forEachIndexed { slot, colorId ->
                insertTaskColor(TaskColorEntity(taskId = taskId, colorId = colorId, slotIndex = slot))
            }
            stageRowsFor(taskId, piece.poolType, moment, piece.stageCount).forEach { insertStage(it) }
            // The space goes in as a piece of the document in its own right, so
            // the boundary survives being read back, copied out, spoken aloud and
            // edited again. Written before the task it separates, in the place
            // planning kept for it.
            if (separatorId != null && piece.separatorOrderIndex != null) {
                insertSegment(
                    CellSegmentEntity.plainText(
                        id = separatorId,
                        cellId = piece.targetCellId,
                        orderIndex = piece.separatorOrderIndex,
                        text = TASK_SEPARATOR,
                        moment = moment,
                    ),
                )
            }
            insertSegment(
                CellSegmentEntity.task(
                    id = segmentId,
                    cellId = piece.targetCellId,
                    orderIndex = piece.orderIndex,
                    taskId = taskId,
                    moment = moment,
                ),
            )
            val aimed = setDraftMaterializedTask(piece.draftId, taskId, moment)
            check(aimed == 1) { "The draft ${piece.draftId} could not be linked to the task it produced." }
        }

        // One write per game however many cells named it, and none at all for a
        // game the user had already finished: PLAN 5.3 makes `completedAt` the
        // moment they decided, and rewriting it would move a date they set.
        plan.gamesToFinish.forEach { gameId ->
            val finished = markGameManuallyCompleted(gameId, moment)
            check(finished == 1) { "The game $gameId could not be finished by the import that named it." }
        }

        val confirmed = markBatchConfirmed(batchId, plan.drafts.size, moment)
        check(confirmed == 1) { "The import $batchId was no longer a draft when it was about to be confirmed." }

        // One line per game the import wrote into (PLAN 12.15), inside the very
        // transaction that wrote it: a confirmation that failed leaves no record
        // of having happened, because it did not.
        plan.gamesWrittenInto.forEach { gameId ->
            appendHistoryEvent(
                HistoryEventEntity(
                    id = checkNotNull(confirmationEventIds[gameId]),
                    kind = HistoryEventKind.IMPORT_CONFIRMED,
                    occurredAt = moment,
                    gameId = gameId,
                ),
            )
        }

        requireConfirmationHeld(batchId, plan, moment, confirmationEventIds.values)
        return plan.drafts.size
    }

    // ------------------------------------------------------ taking an import back

    /**
     * Everything one rollback decision is made from, read in bulk.
     *
     * Seven statements for the whole batch, whether it made one task or
     * forty-two: the batch, its drafts, its tasks, the shortages against them,
     * the history about them, its pieces of cells, the cells it recorded and
     * those cells' pieces as they stand. Asking any of it per task is the shape
     * PLAN 16 rules out.
     */
    private suspend fun rollbackFactsOf(batchId: EntityId): ImportRollbackFacts {
        val batch = batchById(batchId)
        val drafts = draftTasksOfBatch(batchId)
        val tasks = tasksOfConfirmedBatch(batchId)
        val withProgress = taskIdsWithProgressOfBatch(batchId).toSet()
        val withHistory = taskIdsWithHistoryOfBatch(batchId).toSet()
        // Where each of this batch's tasks is written. Read now, because the
        // rollback is about to take those pieces away.
        val anchors = segmentsOfConfirmedBatch(batchId).mapNotNull { it.taskId?.let { id -> id to it.cellId } }.toMap()
        val cells = rollbackCellsOfBatch(batchId)
        val pieces = rollbackSegmentsOfBatch(batchId).groupBy { it.cellId }

        return ImportRollbackFacts(
            batchId = batchId,
            status = batch?.status,
            draftCount = drafts.size,
            materializedDraftCount = drafts.count { it.materializedTaskId != null },
            tasks =
                tasks.map { task ->
                    RollbackTaskFacts(
                        taskId = task.id,
                        name = task.name,
                        createdAt = task.createdAt,
                        updatedAt = task.updatedAt,
                        deletedAt = task.deletedAt,
                        hasProgressEvent = task.id in withProgress,
                        hasHistoryEvent = task.id in withHistory,
                    )
                },
            anchors = anchors,
            cells =
                cells.map { cell ->
                    RollbackCellFacts(
                        cellId = cell.cellId,
                        gameId = cell.gameId,
                        gameName = cell.gameName,
                        columnType = cell.columnType,
                        documentBefore = cell.documentBefore,
                        segments =
                            pieces[cell.cellId].orEmpty().map { piece ->
                                RollbackSegmentFacts(
                                    segmentId = piece.segmentId,
                                    orderIndex = piece.orderIndex,
                                    isTask = piece.kind == SegmentKind.TASK,
                                    text = piece.text,
                                    taskId = piece.taskId,
                                    documentText = piece.documentText,
                                )
                            },
                    )
                },
        )
    }

    /**
     * What taking this import back would do right now, without doing any of it.
     *
     * Advisory only, exactly as `summarize` is for a confirmation. Every check in
     * it is made again inside [rollBackConfirmedBatch], from rows read there —
     * the database can change between showing this and acting on it, and a
     * rollback that trusted a stale answer would be deciding on a cell somebody
     * has since edited.
     */
    @Transaction
    open suspend fun previewRollback(batchId: EntityId): ImportRollbackPreview {
        val facts = rollbackFactsOf(batchId)
        return previewOf(planImportRollback(facts), facts.status)
    }

    /**
     * Takes one confirmed import back, or changes nothing at all.
     *
     * PLAN 11.4.4 allows no third outcome. Every guard below is inside this
     * transaction rather than advice given beforehand, so a task cannot be
     * edited and a cell cannot be written into between the checking and the
     * writing; anything thrown from here — a refusal, a rejected row, a broken
     * postcondition — takes every write with it and leaves the batch
     * `CONFIRMED`.
     *
     * Nothing is destroyed. The tasks are tombstoned (PLAN 5.2), their colours,
     * pipelines, shortages and past history rows all stay, and the only rows
     * that really go are the pieces of a cell this import added. The games'
     * completion marks are left exactly as they are: PLAN 5.3 makes that the
     * user's own statement, and a rollback is not entitled to take it back.
     *
     * @return what was taken back.
     * @throws ImportRollbackException if the import could not be taken back;
     *   nothing is written in that case.
     */
    @Transaction
    open suspend fun rollBackConfirmedBatch(
        batchId: EntityId,
        clock: Clock,
        idGenerator: IdGenerator,
    ): ImportRollbackResult {
        val plan = planImportRollback(rollbackFactsOf(batchId))
        plan.failure?.let { throw ImportRollbackException(it, plan.blockedTasks, plan.blockedCells) }

        // What the rest of the database holds, so the checks at the end can say
        // that nothing outside this batch moved. Four counts, taken once.
        val tasksBefore = countTasks()
        val segmentsBefore = countSegments()
        val progressBefore = countProgressEvents()
        val historyBefore = countHistoryEvents()

        // Nothing above this line has written, read a clock or made a name. Both
        // sets of identities are made now, before the first write: a generator
        // that ran out half way would otherwise leave tombstones behind it and a
        // history that stopped in the middle of a sentence.
        val taskEventIds = plan.taskIds.associateWith { idGenerator.newId() }
        val gameEventIds = plan.gameIds.associateWith { idGenerator.newId() }
        // One reading of the clock for the whole act, so every line this writes
        // carries the one moment it happened at.
        val moment = clock.now()

        plan.taskIds.forEach { taskId ->
            val removed = writeTaskTombstone(taskId, moment)
            check(removed == 1) { "The task $taskId was already out of view when the import was taken back." }
            // The game was settled before any of this, because the piece of the
            // cell that answers it is about to go.
            val gameId = checkNotNull(plan.gameOfTask[taskId]) { "The task $taskId is in no game." }
            appendHistoryEvent(
                HistoryEventEntity(
                    id = checkNotNull(taskEventIds[taskId]),
                    kind = HistoryEventKind.TASK_ROLLED_BACK,
                    occurredAt = moment,
                    gameId = gameId,
                    taskId = taskId,
                ),
            )
        }

        // Only the pieces this import added, which the planning proved are a
        // suffix of each cell. What was there first keeps its rows, its
        // identities, its order and its links to its own tasks, and the reading
        // order stays `0..N-1` because a suffix was removed rather than a hole
        // punched in the middle.
        plan.cells.forEach { cell ->
            cell.segmentIdsToRemove.forEach { segmentId ->
                val removed = deleteSegmentRow(segmentId)
                check(removed == 1) { "A piece of the cell ${cell.cellId} was already gone." }
            }
            touchCell(cell.cellId, moment)
        }

        plan.gameIds.forEach { gameId ->
            appendHistoryEvent(
                HistoryEventEntity(
                    id = checkNotNull(gameEventIds[gameId]),
                    kind = HistoryEventKind.IMPORT_ROLLED_BACK,
                    occurredAt = moment,
                    gameId = gameId,
                ),
            )
        }

        val moved = markBatchRolledBack(batchId, moment)
        check(moved == 1) { "The import $batchId was no longer confirmed when it was about to be taken back." }

        requireRollbackHeld(
            batchId = batchId,
            plan = plan,
            moment = moment,
            taskEventIds = taskEventIds.values,
            gameEventIds = gameEventIds.values,
            tasksBefore = tasksBefore,
            segmentsBefore = segmentsBefore,
            progressBefore = progressBefore,
            historyBefore = historyBefore,
        )
        return ImportRollbackResult(
            batchId = batchId,
            removedTaskCount = plan.taskIds.size,
            restoredCellCount = plan.cells.size,
            affectedGameCount = plan.gameIds.size,
        )
    }

    /**
     * Reads back what the rollback has just done and refuses to let it stand
     * unless it is exactly what was promised. Still inside the transaction, so a
     * broken postcondition takes the whole rollback down.
     *
     * Every check is one statement for the batch or a count of a whole table.
     * The four table counts are what prove the negative PLAN 11.4.4 cares most
     * about: no task and no shortage was destroyed, no history line was removed,
     * and nothing outside this import's own pieces left the cells.
     */
    @Suppress("LongParameterList")
    private suspend fun requireRollbackHeld(
        batchId: EntityId,
        plan: ImportRollbackPlan,
        moment: Instant,
        taskEventIds: Collection<EntityId>,
        gameEventIds: Collection<EntityId>,
        tasksBefore: Int,
        segmentsBefore: Int,
        progressBefore: Int,
        historyBefore: Int,
    ) {
        val batch = checkNotNull(batchById(batchId)) { "The import $batchId disappeared while being taken back." }
        check(batch.status == ImportBatchStatus.ROLLED_BACK) {
            "The import $batchId was left as ${batch.status} after being taken back."
        }

        val tasks = tasksOfConfirmedBatch(batchId)
        check(tasks.size == plan.taskIds.size) { "The import $batchId lost or gained a task while being taken back." }
        // What is checked is the invariant PLAN 5.2 gives — the task is out of
        // view — and not that the moment reads back as the same object. The
        // column holds milliseconds, so an instant carrying anything finer comes
        // back rounded, and a comparison on it would fail for a reason that is
        // not about anything the application means.
        check(tasks.all { it.deletedAt != null && it.updatedAt == it.deletedAt }) {
            "${tasks.count { it.deletedAt == null }} of the import's tasks were left in view."
        }
        check(segmentsOfConfirmedBatch(batchId).isEmpty()) {
            "The import $batchId still has pieces of a cell after being taken back."
        }

        // Every cell reads what it read before the import, character for
        // character, and holds exactly the pieces it held then.
        val pieces = rollbackSegmentsOfBatch(batchId).groupBy { it.cellId }
        plan.cells.forEach { cell ->
            val now = pieces[cell.cellId].orEmpty()
            check(now.map { it.orderIndex } == now.indices.toList()) {
                "The cell ${cell.cellId} is numbered ${now.map { it.orderIndex }} after being put back."
            }
            check(now.map { it.segmentId } == cell.keptSegmentIds) {
                "The cell ${cell.cellId} holds ${now.size} pieces where ${cell.keptSegmentIds.size} were kept."
            }
            check(now.none { it.taskId in plan.taskIds }) {
                "A task the import created is still written in the cell ${cell.cellId}."
            }
            val document = now.joinToString(separator = "") { it.documentText }
            // Lengths, not the writing itself: this message can reach a log, and
            // the user's own words are not something to put there.
            check(document == cell.documentBefore) {
                "The cell ${cell.cellId} reads ${document.length} characters where it held " +
                    "${cell.documentBefore.length}."
            }
        }

        val removedSegments = plan.cells.sumOf { it.segmentIdsToRemove.size }
        check(countTasks() == tasksBefore) { "Taking the import back destroyed a task row." }
        check(countSegments() == segmentsBefore - removedSegments) {
            "Taking the import back removed pieces of a cell it had no business touching."
        }
        check(countProgressEvents() == progressBefore) { "Taking the import back destroyed a shortage record." }
        check(countHistoryEvents() == historyBefore + plan.taskIds.size + plan.gameIds.size) {
            "Taking the import back left the history the wrong length."
        }
        check(countHistoryEventsWritten(taskEventIds, HistoryEventKind.TASK_ROLLED_BACK, moment) == plan.taskIds.size) {
            "The history did not get one line per task taken back."
        }
        check(countHistoryEventsWritten(gameEventIds, HistoryEventKind.IMPORT_ROLLED_BACK, moment) == plan.gameIds.size) {
            "The history did not get one line per game the import touched."
        }
    }

    /**
     * Everything a confirmation is about to do, worked out before it does any of
     * it.
     *
     * Every guard lives here, and every guard is answered from a fixed number of
     * batch-wide reads: the batch, its drafts, its raw cells, the cells they aim
     * at, the colours they were given, the catalogue, and the games any accepted
     * green cell names. Seven questions, whether the import holds one draft or
     * forty-two. Asking per draft is the shape PLAN 16 rules out, and it is what
     * this used to do.
     *
     * Nothing is written, no clock is read and no identity is made while this
     * runs, so a refusal from anywhere in it costs nothing and leaves nothing.
     */
    private suspend fun plannedConfirmationOf(
        batchId: EntityId,
        acknowledgeUnprocessedBlocks: Boolean,
    ): PlannedConfirmation {
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

        val blocks = rawBlocksOfBatch(batchId).associateBy { it.id }
        if (!acknowledgeUnprocessedBlocks && blocks.values.any { !it.isProcessed }) {
            refuse(ImportConfirmationFailure.UNPROCESSED_BLOCKS_NOT_ACKNOWLEDGED)
        }

        val targets = draftTargetsOfBatch(batchId).associateBy { it.cellId }
        val chosenColors = draftColorsOfBatch(batchId).groupBy { it.draftTaskId }
        val catalogue = if (chosenColors.isEmpty()) emptySet() else allColorIds().toSet()
        // What each target cell reads before this import touches it, kept so the
        // postcondition can insist the user's own writing came through unchanged.
        val documentsBefore = targetCellDocumentsOfBatch(batchId).groupBy { it.cellId }
        // The same reading as the words each cell holds. Worked out once and
        // used three times — to decide where a separator is needed, to keep the
        // snapshot, and to check the user's writing survived — so those three
        // can never disagree about what a cell said.
        val documentTextsBefore =
            documentsBefore.mapValues { (_, rows) -> rows.joinToString(separator = "") { it.text } }
        // Which game each target cell belongs to, in one statement. The history
        // line PLAN 12.15 asks for is per game rather than per task, and working
        // that out per draft is the shape PLAN 16 rules out.
        val gameOfTargetCell = targetCellGamesOfBatch(batchId).associate { it.cellId to it.gameId }

        // Where the next piece goes in each cell, carried in memory: several
        // drafts aiming at one cell take consecutive places, and drafts aiming at
        // different cells do not disturb each other's numbering.
        val nextIndex = mutableMapOf<EntityId, Int>()
        // What each cell reads as the import fills it, so the second task written
        // into a cell is separated from the first one this import put there and
        // not just from whatever was in the cell to begin with.
        val readsSoFar = documentTextsBefore.toMutableMap()
        val pieces =
            drafts.map { draft ->
                // One unready draft stops the whole batch; none is ever skipped.
                val targetCellId =
                    draft.targetCellId ?: refuse(ImportConfirmationFailure.TARGET_CELL_MISSING, draft.id)
                val poolType =
                    draft.selectedPoolType ?: refuse(ImportConfirmationFailure.POOL_TYPE_MISSING, draft.id)
                val trackingMode =
                    draft.selectedTrackingMode ?: refuse(ImportConfirmationFailure.TRACKING_MODE_MISSING, draft.id)
                // PLAN 11.5 has the user answer the marker. Confirming with the
                // question still open would answer it for them, one way or the
                // other, and write the answer into a real task.
                if (draft.completionHint == HintDecision.PENDING) {
                    refuse(ImportConfirmationFailure.COMPLETION_HINT_UNDECIDED, draft.id)
                }
                val target =
                    targets[targetCellId] ?: refuse(ImportConfirmationFailure.TARGET_CELL_NOT_AVAILABLE, draft.id)
                if (!target.columnType.holdsTasks) {
                    refuse(ImportConfirmationFailure.TARGET_CELL_NOT_TASK_CAPABLE, draft.id)
                }
                if (target.columnType.poolType != poolType) {
                    refuse(ImportConfirmationFailure.TARGET_CELL_WRONG_COLUMN, draft.id)
                }
                // A document numbered with a gap in it is damaged, and writing
                // into one would either collide or widen the gap. Renumbering it
                // silently would be rewriting the user's own document, so this
                // stops instead.
                check(target.isSound) {
                    "The cell $targetCellId holds ${target.segmentCount} pieces numbered up to " +
                        "${target.nextOrderIndex - 1}."
                }
                // A stored draft cannot hold a quantity of zero or a pool the mode
                // forbids, so either would be a defect rather than a user mistake.
                require(draft.requiredQuantity == null || draft.requiredQuantity > 0) {
                    "The draft ${draft.id} holds a required quantity of ${draft.requiredQuantity}."
                }
                requireAllowedTrackingMode(poolType, trackingMode)
                requireSelectionStillFits(draft, blocks[draft.rawImportBlockId])

                val colorIds = chosenColors[draft.id].orEmpty().sortedBy { it.slotIndex }
                check(colorIds.map { it.slotIndex } == colorIds.indices.toList()) {
                    "The draft ${draft.id} has colour slots ${colorIds.map { it.slotIndex }}."
                }
                val ids = colorIds.map { it.colorId }
                check(ids.toSet().size == ids.size) { "The draft ${draft.id} names one colour twice." }
                if (ids.any { it !in catalogue }) {
                    refuse(ImportConfirmationFailure.COLOR_NO_LONGER_AVAILABLE, draft.id)
                }

                // A separator goes in only where a boundary would otherwise be
                // invisible: never before the first task of an empty cell, and
                // never after writing that already ends in a space of its own.
                val soFar = readsSoFar[targetCellId].orEmpty()
                val separator = taskNeedsSeparatorAfter(soFar)
                var at = nextIndex.getOrPut(targetCellId) { target.nextOrderIndex }
                val separatorOrderIndex = if (separator) at++ else null
                val orderIndex = at++
                nextIndex[targetCellId] = at
                readsSoFar[targetCellId] = soFar + (if (separator) TASK_SEPARATOR else "") + draft.name

                PlannedTask(
                    draftId = draft.id,
                    rawImportBlockId = draft.rawImportBlockId,
                    name = draft.name,
                    poolType = poolType,
                    trackingMode = trackingMode,
                    requiredQuantity = draft.requiredQuantity,
                    notes = draft.notes,
                    isMissing = draft.isMissing,
                    isBorrowed = draft.isBorrowed,
                    needsInfo = draft.needsInfo,
                    needsClassification = draft.needsClassification,
                    // PLAN 11.5 makes `**` a hint and nothing more until the user
                    // agrees to it. An unanswered one never gets this far, and a
                    // rejected one leaves the task open.
                    isFinished = draft.completionHint == HintDecision.ACCEPTED,
                    targetCellId = targetCellId,
                    separatorOrderIndex = separatorOrderIndex,
                    orderIndex = orderIndex,
                    colorIds = ids,
                )
            }

        return PlannedConfirmation(
            drafts = pieces,
            gamesToFinish = gamesToFinishFor(batchId, blocks.values),
            documentsBefore = documentsBefore,
            documentTextsBefore = documentTextsBefore,
            gameOfTargetCell = gameOfTargetCell,
        )
    }

    /**
     * The games this import will finish, and nothing more.
     *
     * PLAN 5.3 lets an accepted green cell change whether a game is finished, and
     * says in the same breath that a game's state is the user's own statement
     * rather than a summary of its work — so this finishes games and never
     * touches a task. A game the user had already finished is left entirely
     * alone.
     */
    private suspend fun gamesToFinishFor(
        batchId: EntityId,
        blocks: Collection<RawImportBlockEntity>,
    ): List<EntityId> {
        if (blocks.any { it.gameCompletionHint == HintDecision.PENDING }) {
            refuse(ImportConfirmationFailure.GAME_COMPLETION_HINT_UNDECIDED)
        }
        val accepted = blocks.filter { it.gameCompletionHint == HintDecision.ACCEPTED }
        if (accepted.isEmpty()) return emptyList()
        // A version 5 database could record the answer with nowhere to say which
        // game it was about. That answer is kept and the user is asked, rather
        // than the import guessing or the answer being thrown away.
        if (accepted.any { it.completionTargetGameId == null }) {
            refuse(ImportConfirmationFailure.COMPLETION_TARGET_GAME_REQUIRED)
        }
        val wanted = accepted.mapNotNull { it.completionTargetGameId }.toSet()
        val reachable = acceptedCompletionTargetsOfBatch(batchId)
        if (reachable.size != wanted.size) refuse(ImportConfirmationFailure.COMPLETION_TARGET_GAME_NOT_AVAILABLE)
        // Named once each, and only the ones that are not finished already.
        return reachable.filterNot { it.isManuallyCompleted }.map { it.id }
    }

    /**
     * Checks a draft's selection against the cell it really came from.
     *
     * The raw text is never rewritten, so what can change is the cell going away.
     * The boundaries are checked against the user's own characters rather than
     * against code units: offsets that fall inside one — half of an emoji, a
     * letter without its accent — would name text nobody selected.
     */
    private fun requireSelectionStillFits(
        draft: DraftTaskEntity,
        block: RawImportBlockEntity?,
    ) {
        val start = draft.selectionStartIndex ?: return
        val end = draft.selectionEndIndex ?: return
        if (block == null) refuse(ImportConfirmationFailure.SELECTION_NO_LONGER_FITS, draft.id)
        val boundaries = graphemeBoundariesOf(block.rawText)
        if (end > block.rawText.length || start !in boundaries || end !in boundaries) {
            refuse(ImportConfirmationFailure.SELECTION_NO_LONGER_FITS, draft.id)
        }
    }

    /** What one draft is about to become, decided before anything is written. */
    private data class PlannedTask(
        val draftId: EntityId,
        val rawImportBlockId: EntityId,
        val name: String,
        val poolType: PoolType,
        val trackingMode: TrackingMode,
        val requiredQuantity: Int?,
        val notes: String?,
        val isMissing: Boolean,
        val isBorrowed: Boolean,
        val needsInfo: Boolean,
        val needsClassification: Boolean,
        val isFinished: Boolean,
        val targetCellId: EntityId,
        /** Where the space in front of this task goes, or null when it needs none. */
        val separatorOrderIndex: Int?,
        val orderIndex: Int,
        val colorIds: List<EntityId>,
    ) {
        /** What each stage of the pipeline reads; the whole total when born finished. */
        val stageCount: Int
            get() = if (isFinished) CompletionRules.stageCountWhenFinished(requiredQuantity, 0) else 0

        fun taskRow(
            taskId: EntityId,
            moment: Instant,
        ): TaskEntity =
            TaskEntity(
                id = taskId,
                poolType = poolType,
                trackingMode = trackingMode,
                name = name,
                requiredQuantity = requiredQuantity,
                notes = notes,
                // A task born finished is finished the way any other is: PLAN 6.4
                // will not have the mark contradict the counters, so the print run
                // and the pipeline say so too. It owes nothing, having never been
                // worked on, so no event explains a debt that was never there.
                isCompleted = isFinished,
                completedAt = moment.takeIf { isFinished },
                primaryBatchCompleted =
                    isFinished && CompletionRules.primaryBatchCompletedWhenFinished(poolType, false),
                createdAt = moment,
                updatedAt = moment,
                sourceRawImportBlockId = rawImportBlockId,
                isMissing = isMissing,
                isBorrowed = isBorrowed,
                needsInfo = needsInfo,
                needsClassification = needsClassification,
            )
    }

    /** One planned task with the identities its rows will be written under. */
    private data class NamedTask(
        val piece: PlannedTask,
        val taskId: EntityId,
        val segmentId: EntityId,
        val separatorId: EntityId?,
    )

    /** Everything one confirmation will write, and nothing it will not. */
    private data class PlannedConfirmation(
        val drafts: List<PlannedTask>,
        val gamesToFinish: List<EntityId>,
        /** What each target cell held before any of this was written. */
        val documentsBefore: Map<EntityId, List<CellDocumentRow>>,
        /** The same documents as the words they read, one string per cell. */
        val documentTextsBefore: Map<EntityId, String>,
        /** Which game each target cell belongs to, for the history line per game. */
        val gameOfTargetCell: Map<EntityId, EntityId>,
    ) {
        /**
         * The games this import writes into, each once, in the drafts' own order.
         *
         * PLAN 12.15 asks for one `IMPORT_CONFIRMED` line per game touched, not
         * one per task: a file that fills six cells of one game is one thing the
         * user did, and six identical lines would bury the day it happened in.
         */
        val gamesWrittenInto: List<EntityId>
            get() = drafts.mapNotNull { gameOfTargetCell[it.targetCellId] }.distinct()
    }

    /**
     * Reads back what the transaction has just written and refuses to let it
     * stand unless it is exactly what was promised. Still inside the transaction,
     * so a broken postcondition rolls the whole confirmation back.
     *
     * Six reads for the whole batch, none of them per task: a check that cost a
     * query a row would put back the very shape the planning above exists to
     * remove.
     */
    private suspend fun requireConfirmationHeld(
        batchId: EntityId,
        plan: PlannedConfirmation,
        moment: Instant,
        confirmationEventIds: Collection<EntityId>,
    ) {
        val batch = batchById(batchId)
        checkNotNull(batch) { "The import $batchId disappeared while it was being confirmed." }
        check(batch.status == ImportBatchStatus.CONFIRMED) {
            "The import $batchId was left as ${batch.status} after being confirmed."
        }
        check(batch.createdTaskCount == plan.drafts.size) {
            "The import $batchId says it created ${batch.createdTaskCount} tasks, not ${plan.drafts.size}."
        }
        check(batch.createdGameCount == 0) {
            "Confirming an import created ${batch.createdGameCount} games; it must create none."
        }

        val stored = draftTasksOfBatch(batchId)
        check(stored.size == plan.drafts.size) { "The import $batchId lost or gained a draft while confirming." }
        check(stored.none { it.materializedTaskId == null }) {
            "${stored.count { it.materializedTaskId == null }} drafts were left without the task they produced."
        }

        val tasks = tasksOfConfirmedBatch(batchId).associateBy { it.id }
        check(tasks.size == plan.drafts.size) {
            "${plan.drafts.size} drafts should have produced ${plan.drafts.size} tasks, not ${tasks.size}."
        }
        val colors = taskColorsOfConfirmedBatch(batchId).groupBy { it.taskId }
        val stages = taskStagesOfConfirmedBatch(batchId).groupBy { it.taskId }
        val segments = segmentsOfConfirmedBatch(batchId)
        check(segments.size == plan.drafts.size) {
            "The import wrote ${segments.size} pieces of a cell for ${plan.drafts.size} tasks."
        }

        val byDraft = stored.associateBy { it.id }
        plan.drafts.forEach { piece ->
            val taskId =
                checkNotNull(byDraft[piece.draftId]?.materializedTaskId) {
                    "The draft ${piece.draftId} is not linked to a task."
                }
            val task = checkNotNull(tasks[taskId]) { "The task $taskId this import made is not there." }
            check(task.isMissing == piece.isMissing && task.isBorrowed == piece.isBorrowed) {
                "The task $taskId did not keep the missing and borrowed flags of its draft."
            }
            check(task.needsInfo == piece.needsInfo && task.needsClassification == piece.needsClassification) {
                "The task $taskId did not keep the information flags of its draft."
            }
            check(task.isCompleted == piece.isFinished) {
                "The task $taskId came out ${task.isCompleted} when the hint said ${piece.isFinished}."
            }
            check(task.isCompleted == (task.completedAt != null)) {
                "The task $taskId is ${task.isCompleted} but finished at ${task.completedAt}."
            }
            check(!task.isCompleted || task.poolType != PoolType.THREE_D || task.primaryBatchCompleted) {
                "The finished 3D task $taskId never had its print run made."
            }
            check(task.currentMissingQuantity == 0) { "A task this import made already owes something." }

            check(colors[taskId].orEmpty().map { it.colorId } == piece.colorIds) {
                "The task $taskId did not take the colours its draft was given, in order."
            }
            check(colors[taskId].orEmpty().map { it.slotIndex } == piece.colorIds.indices.toList()) {
                "The task $taskId has colour slots with a gap in them."
            }

            val pipeline = stages[taskId].orEmpty()
            check(pipeline.map { it.stage } == stagesOf(piece.poolType)) {
                "The task $taskId has the pipeline ${pipeline.map { it.stage }} for a ${piece.poolType} task."
            }
            check(pipeline.all { it.completedQuantity == piece.stageCount }) {
                "The task $taskId has a pipeline at ${pipeline.map { it.completedQuantity }}."
            }
        }

        // Every cell this import wrote in is still numbered 0..N-1. The same
        // batch-wide read the planning used, so checking forty-two cells costs
        // what checking one does.
        val written = segments.mapTo(mutableSetOf()) { it.cellId }
        draftTargetsOfBatch(batchId).filter { it.cellId in written }.forEach { cell ->
            check(cell.isSound) {
                "The cell ${cell.cellId} holds ${cell.segmentCount} pieces numbered up to " +
                    "${cell.nextOrderIndex - 1}."
            }
        }

        // What each cell reads now, against what it read before and against the
        // boundaries this import promised to leave in it. One batch-wide read,
        // the same statement the planning used.
        val documentsAfter = targetCellDocumentsOfBatch(batchId).groupBy { it.cellId }
        plan.drafts.groupBy { it.targetCellId }.forEach { (cellId, pieces) ->
            val after = documentsAfter[cellId].orEmpty()
            check(after.map { it.orderIndex } == after.indices.toList()) {
                "The cell $cellId is numbered ${after.map { it.orderIndex }} after being written into."
            }
            val before = plan.documentsBefore[cellId].orEmpty()
            val beforeText = plan.documentTextsBefore[cellId].orEmpty()
            val afterText = after.joinToString(separator = "") { it.text }
            // The user's own writing, character for character, still at the front
            // of the document: an import appends and never rewrites or trims.
            check(afterText.startsWith(beforeText)) {
                "The cell $cellId no longer begins with the ${beforeText.length} characters it held."
            }
            val separators = pieces.count { it.separatorOrderIndex != null }
            check(after.size == before.size + pieces.size + separators) {
                "The cell $cellId holds ${after.size} pieces for the ${before.size} it had, " +
                    "${pieces.size} tasks and $separators separators."
            }

            val byIndex = after.associateBy { it.orderIndex }
            pieces.forEach { piece ->
                check(byIndex[piece.orderIndex]?.text == piece.name) {
                    "The cell $cellId reads ${byIndex[piece.orderIndex]?.text} where ${piece.name} was written."
                }
                // Nothing runs straight on into a task: whatever precedes it ends
                // where a reader, a screen reader and a paste all hear it end.
                val preceding = byIndex[piece.orderIndex - 1]?.text
                check(preceding == null || !taskNeedsSeparatorAfter(preceding)) {
                    "The task ${piece.name} runs on from the writing before it in the cell $cellId."
                }
                piece.separatorOrderIndex?.let { at ->
                    check(byIndex[at]?.text == TASK_SEPARATOR) {
                        "The piece before ${piece.name} in the cell $cellId is not a single space."
                    }
                    // A space is only ever put where one was missing, so a
                    // separator can never sit against writing that already ended.
                    val earlier = byIndex[at - 1]?.text
                    check(earlier != null && taskNeedsSeparatorAfter(earlier)) {
                        "A separator was written before ${piece.name} in the cell $cellId with no need of one."
                    }
                }
            }
        }

        // The cells were really kept, one each, saying what they said. Read back
        // rather than assumed: a snapshot that did not land would leave PLAN
        // 11.4.4 with nothing to restore from and no way to know it, and by then
        // the cells would already have been written into. One read for the batch.
        val aimedAt = plan.drafts.map { it.targetCellId }.distinct()
        val snapshots = cellSnapshotsOfBatch(batchId).associateBy { it.cellId }
        check(snapshots.keys == aimedAt.toSet()) {
            "The import $batchId wrote into ${aimedAt.size} cells and kept ${snapshots.size} of them."
        }
        aimedAt.forEach { cellId ->
            val kept = snapshots[cellId]?.documentBefore
            val expected = plan.documentTextsBefore[cellId].orEmpty()
            // Lengths, not the writing itself: this message can reach a log, and
            // the user's own words are not something to put there.
            check(kept == expected) {
                "The cell $cellId was kept as ${kept?.length} characters where it held ${expected.length}."
            }
        }

        check(
            countHistoryEventsWritten(confirmationEventIds, HistoryEventKind.IMPORT_CONFIRMED, moment) ==
                plan.gamesWrittenInto.size,
        ) {
            "The history did not get one line per game this import wrote into."
        }

        if (plan.gamesToFinish.isNotEmpty()) {
            val finished = acceptedCompletionTargetsOfBatch(batchId).associateBy { it.id }
            plan.gamesToFinish.forEach { gameId ->
                val game = checkNotNull(finished[gameId]) { "The game $gameId disappeared while being finished." }
                // What is checked is the invariant PLAN 5.3 gives — a game is
                // finished exactly when it has a time it was finished at — and
                // not that the time reads back as the same object. The column
                // holds milliseconds, so an instant carrying anything finer
                // comes back rounded and would fail a comparison that is not
                // about anything the application means.
                check(game.isManuallyCompleted && game.completedAt != null) {
                    "The game $gameId was not finished by the import that named it."
                }
            }
        }
    }

    private fun refuse(
        failure: ImportConfirmationFailure,
        draftTaskId: EntityId? = null,
    ): Nothing = throw ImportConfirmationException(failure, draftTaskId)
}
