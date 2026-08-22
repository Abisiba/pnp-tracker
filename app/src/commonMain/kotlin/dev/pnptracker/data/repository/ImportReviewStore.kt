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
     * The draft is only a draft. No game, item or task comes of it here, the cell
     * it came from keeps its review mark, and the import stays a draft.
     *
     * @throws ImportReviewException if the draft did not reach the database.
     */
    suspend fun addDraftTask(
        blockId: EntityId,
        name: String,
    )
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
        ) { batch, blocks, drafts ->
            batch ?: return@combine null
            ImportReviewWorkspace(
                batchId = batch.id,
                fileName = batch.fileName,
                sheetName = batch.sheetName,
                status = batch.status,
                rawBlocks = blocks.map { it.toReviewBlock() },
                draftTasks = drafts.map { it.toReviewDraft() },
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
        isProcessed = isProcessed,
    )

private fun DraftTaskEntity.toReviewDraft(): ReviewDraftTask =
    ReviewDraftTask(
        id = id,
        rawImportBlockId = rawImportBlockId,
        name = name,
        suggestedPoolType = suggestedPoolType,
        completionHint = completionHint,
        targetCellId = targetCellId,
        selectedPoolType = selectedPoolType,
        selectedTrackingMode = selectedTrackingMode,
        materializedTaskId = materializedTaskId,
    )
