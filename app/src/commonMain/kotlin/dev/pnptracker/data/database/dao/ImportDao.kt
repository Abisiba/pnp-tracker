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
import dev.pnptracker.data.database.entity.ImportBatchEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.entity.TaskStageEntity
import dev.pnptracker.data.database.entity.stageRowsFor
import dev.pnptracker.data.database.projection.CellColumnRow
import dev.pnptracker.data.database.projection.DraftTargetRow
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importreview.DraftInitialValues
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.importreview.initialDraftByHand
import dev.pnptracker.domain.importreview.initialDraftFromSelection
import dev.pnptracker.domain.importreview.selectTaskNameIn
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
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
     * cells the user opened by hand, each one as a piece of that cell's document.
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
        moment: Instant,
        idGenerator: IdGenerator,
    ): Int {
        val plan = plannedConfirmationOf(batchId, acknowledgeUnprocessedBlocks)

        // Nothing above this line has written, read a clock or made a name. The
        // clock is the caller's single moment for the whole act, and every
        // identity is made now, before the first insert: an identifier that ran
        // out half way would otherwise leave a batch of rows behind it.
        val named = plan.drafts.map { it to (idGenerator.newId() to idGenerator.newId()) }

        named.forEach { (piece, ids) ->
            val (taskId, segmentId) = ids
            insertTask(piece.taskRow(taskId, moment))
            piece.colorIds.forEachIndexed { slot, colorId ->
                insertTaskColor(TaskColorEntity(taskId = taskId, colorId = colorId, slotIndex = slot))
            }
            stageRowsFor(taskId, piece.poolType, moment, piece.stageCount).forEach { insertStage(it) }
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

        requireConfirmationHeld(batchId, plan)
        return plan.drafts.size
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

        // Where the next piece goes in each cell, carried in memory: several
        // drafts aiming at one cell take consecutive places, and drafts aiming at
        // different cells do not disturb each other's numbering.
        val nextIndex = mutableMapOf<EntityId, Int>()
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

                val orderIndex = nextIndex.getOrPut(targetCellId) { target.nextOrderIndex }
                nextIndex[targetCellId] = orderIndex + 1

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
                    orderIndex = orderIndex,
                    colorIds = ids,
                )
            }

        return PlannedConfirmation(drafts = pieces, gamesToFinish = gamesToFinishFor(batchId, blocks.values))
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

    /** Everything one confirmation will write, and nothing it will not. */
    private data class PlannedConfirmation(
        val drafts: List<PlannedTask>,
        val gamesToFinish: List<EntityId>,
    )

    /**
     * Reads back what the transaction has just written and refuses to let it
     * stand unless it is exactly what was promised. Still inside the transaction,
     * so a broken postcondition rolls the whole confirmation back.
     *
     * Five reads for the whole batch, none of them per task: a check that cost a
     * query a row would put back the very shape the planning above exists to
     * remove.
     */
    private suspend fun requireConfirmationHeld(
        batchId: EntityId,
        plan: PlannedConfirmation,
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
