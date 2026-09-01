package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.data.database.entity.DraftTaskEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.importreview.ImportReviewWorkspace
import dev.pnptracker.domain.importreview.ReviewDraftTask
import dev.pnptracker.domain.importreview.ReviewRawBlock
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * What the review screen needs from storage, and nothing else.
 *
 * Kept as an interface so the screen can be driven in a test without a database,
 * and so the screen never learns what a Room entity looks like.
 */
interface ImportReview {
    /** Imports the user can still come back to, newest first. */
    fun observeDraftBatches(): Flow<List<EarlierImport>>

    /** One import's two panes, re-emitted whenever anything in it changes; null once it is gone. */
    fun observeWorkspace(batchId: EntityId): Flow<ImportReviewWorkspace?>

    /**
     * Records how far the user has got with one cell.
     *
     * This never creates or removes a draft, and never moves the import out of
     * being a draft.
     *
     * @throws ImportReviewException if the change did not reach the database.
     */
    suspend fun setProcessed(
        blockId: EntityId,
        isProcessed: Boolean,
    )

    /**
     * Stores one task draft made from a cell.
     *
     * [name] is written as the user left it. It is not trimmed, tidied or stripped
     * of markers: they were shown the cell text and then edited it, so what they
     * ended up with is the answer.
     *
     * The draft is only a draft. No game, cell or task comes of it here, the cell
     * it came from keeps its review mark, and the import stays a draft.
     *
     * @throws ImportReviewException if the draft did not reach the database.
     */
    suspend fun addDraftTask(
        blockId: EntityId,
        name: String,
    )

    /**
     * Replaces the colours of one draft with exactly [colorIds], in that order.
     *
     * The whole list, because the order is part of the answer and an empty list
     * is a valid one (PLAN 5.10). Giving the list the draft already has changes
     * nothing and moves no timestamp.
     *
     * @return true when this call changed something.
     * @throws ImportReviewException if the draft is gone, its import is no
     *   longer a draft, a colour was named twice, or a colour is not in the
     *   catalogue; nothing is written in any of those cases.
     */
    suspend fun setDraftColors(
        draftTaskId: EntityId,
        colorIds: List<EntityId>,
    ): Boolean

    /**
     * Records what the user answered to a green game cell, and which game it was
     * about. Storing the answer only; the game itself is not finished here.
     *
     * @return true when this call changed something.
     * @throws ImportReviewException if the cell is gone, its import is no longer
     *   a draft, the cell cannot carry such a hint, an acceptance named no game,
     *   a game was named for something other than an acceptance, or the game is
     *   gone; nothing is written in any of those cases.
     */
    suspend fun setGameCompletionDecision(
        blockId: EntityId,
        decision: HintDecision,
        targetGameId: EntityId?,
    ): Boolean
}

class ImportReviewStore(
    private val importDao: ImportDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
) : ImportReview {
    override fun observeDraftBatches(): Flow<List<EarlierImport>> =
        importDao.observeDraftBatches().map { batches ->
            batches.map { EarlierImport(it.id, it.fileName, it.sheetName, it.status) }
        }

    override fun observeWorkspace(batchId: EntityId): Flow<ImportReviewWorkspace?> =
        combine(
            importDao.observeBatch(batchId),
            importDao.observeRawBlocksOfBatch(batchId),
            importDao.observeDraftTasksOfBatch(batchId),
            // One stream for the whole import's colours, not one per draft: a
            // flow behind every row would make opening a batch of forty drafts
            // cost forty subscriptions to answer one question.
            importDao.observeDraftColorsOfBatch(batchId),
        ) { batch, blocks, drafts, colors ->
            batch ?: return@combine null
            val chosen = colors.groupBy { it.draftTaskId }
            ImportReviewWorkspace(
                batchId = batch.id,
                fileName = batch.fileName,
                sheetName = batch.sheetName,
                status = batch.status,
                rawBlocks = blocks.map { it.toReviewBlock() },
                // Already ordered by slot from the query; grouping keeps that
                // order, so nothing here re-sorts the user's own choice.
                draftTasks = drafts.map { draft -> draft.toReviewDraft(chosen[draft.id].orEmpty().map { it.colorId }) },
            )
        }

    override suspend fun setProcessed(
        blockId: EntityId,
        isProcessed: Boolean,
    ) {
        // Only a storage failure is turned into something the user can act on.
        // Anything else — a broken invariant, a cell that should have existed —
        // travels out as it is, because it is a defect and not a saved-or-not.
        try {
            importDao.setRawBlockProcessed(blockId, isProcessed, clock.now())
        } catch (cause: SQLiteException) {
            throw ImportReviewException(ImportReviewFailure.COULD_NOT_SAVE, cause)
        }
    }

    override suspend fun addDraftTask(
        blockId: EntityId,
        name: String,
    ) {
        val moment = clock.now()
        val draft =
            DraftTaskEntity(
                id = idGenerator.newId(),
                rawImportBlockId = blockId,
                name = name,
                createdAt = moment,
                updatedAt = moment,
            )
        try {
            importDao.addDraftTaskUnderReview(draft)
        } catch (cause: SQLiteException) {
            throw ImportReviewException(ImportReviewFailure.COULD_NOT_SAVE, cause)
        }
    }

    override suspend fun setDraftColors(
        draftTaskId: EntityId,
        colorIds: List<EntityId>,
    ): Boolean =
        // A refusal the DAO already named travels out as it is; only a storage
        // failure has to be turned into something to say here.
        try {
            importDao.setDraftColorsUnderReview(draftTaskId, colorIds, clock)
        } catch (cause: SQLiteException) {
            throw ImportReviewException(ImportReviewFailure.COULD_NOT_SAVE, cause)
        }

    override suspend fun setGameCompletionDecision(
        blockId: EntityId,
        decision: HintDecision,
        targetGameId: EntityId?,
    ): Boolean =
        try {
            importDao.setGameCompletionDecisionUnderReview(blockId, decision, targetGameId, clock)
        } catch (cause: SQLiteException) {
            throw ImportReviewException(ImportReviewFailure.COULD_NOT_SAVE, cause)
        }
}

private fun RawImportBlockEntity.toReviewBlock(): ReviewRawBlock =
    ReviewRawBlock(
        id = id,
        rawText = rawText,
        sheetName = sheetName,
        rowIndex = rowIndex,
        columnIndex = columnIndex,
        sourceColumnType = sourceColumnType,
        fillColorArgb = fillColorArgb,
        gameCompletionHint = gameCompletionHint,
        completionTargetGameId = completionTargetGameId,
        isProcessed = isProcessed,
    )

private fun DraftTaskEntity.toReviewDraft(colorIds: List<EntityId>): ReviewDraftTask =
    ReviewDraftTask(
        id = id,
        rawImportBlockId = rawImportBlockId,
        name = name,
        suggestedPoolType = suggestedPoolType,
        completionHint = completionHint,
        targetCellId = targetCellId,
        selectedPoolType = selectedPoolType,
        selectedTrackingMode = selectedTrackingMode,
        colorIds = colorIds,
        materializedTaskId = materializedTaskId,
    )
