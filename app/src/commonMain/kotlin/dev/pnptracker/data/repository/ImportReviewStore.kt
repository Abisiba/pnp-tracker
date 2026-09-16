package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.ColorDao
import dev.pnptracker.data.database.dao.GameDao
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.data.database.entity.DraftTaskEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.diagnostics.storageWriteFailed
import dev.pnptracker.domain.importhint.ColorVocabulary
import dev.pnptracker.domain.importhint.ColorVocabularyEntry
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
     * Every game the user still has, newest reading first, kept fresh.
     *
     * For the one question the review screen asks about a game: which one an
     * accepted green cell was about. Nothing here creates a game — PLAN 11.4.1
     * leaves that entirely to the user in the game table — and there is one
     * stream for the whole list rather than one per game.
     */
    fun observeActiveGames(): Flow<List<GameChoice>>

    /**
     * The colour names the hint detectors are allowed to recognise, kept fresh.
     *
     * Handed to the detectors rather than reached for by them: PLAN 11.6 has the
     * application read `Mavi/Açık Mavi` as a choice the user still has to make,
     * and it can only see that a choice was offered if it knows which words name
     * colours. One stream for the whole vocabulary, never one per colour.
     */
    fun observeColorVocabulary(): Flow<ColorVocabulary>

    /** The catalogue the colour list offers, in the user's own order. */
    fun observeColors(): Flow<List<ColorSummary>>

    /**
     * Cuts a draft out of one cell's own text, between two offsets.
     *
     * The offsets are read against the text as the database holds it, so a
     * selection made against a cell that has since gone is refused rather than
     * cut blindly. The name, the pool the column implies, the count the line
     * opens with and the `**` kept as a question are all written with it.
     *
     * @throws ImportReviewException if the cell is gone, its import is no longer
     *   a draft, or the selection is not one a name can be cut from.
     */
    suspend fun createDraftFromSelection(
        blockId: EntityId,
        startIndex: Int,
        endIndex: Int,
    ): EntityId

    /**
     * Stores a draft the user typed, with no selection at all.
     *
     * @throws ImportReviewException if the cell is gone, its import is no longer
     *   a draft, or the name says nothing.
     */
    suspend fun createDraftByHand(
        blockId: EntityId,
        name: String,
    ): EntityId

    /**
     * Saves everything one draft says, in one transaction.
     *
     * The whole answer at once, because a half applied panel is a state the user
     * never asked for. Saving the same answer changes nothing and moves no
     * timestamp.
     *
     * @return true when this call changed something.
     * @throws ImportReviewException with the case that stopped it; nothing written.
     */
    suspend fun saveDraft(edit: DraftEdit): Boolean

    /**
     * Answers the `**` of one draft, on its own.
     *
     * @return true when this call changed something.
     * @throws ImportReviewException if the draft is gone, its import is no longer
     *   a draft, or the file left no marker to answer.
     */
    suspend fun setCompletionDecision(
        draftTaskId: EntityId,
        decision: HintDecision,
    ): Boolean

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
    private val gameDao: GameDao,
    private val colorDao: ColorDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
    private val diagnostics: Diagnostics = Diagnostics.None,
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
            diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.IMPORT_REVIEW, ImportReviewFailure.COULD_NOT_SAVE, cause) }
            throw ImportReviewException(ImportReviewFailure.COULD_NOT_SAVE, cause)
        }
    }

    override fun observeActiveGames(): Flow<List<GameChoice>> =
        gameDao.observeActiveGames().map { games ->
            // Two games really can share a name (PLAN 5.3 puts no rule on it), and
            // a picker offering the same word twice asks the user to guess. The
            // reading order is fixed by the query, so counting through it gives
            // each of them the same label every time.
            val sharedNames =
                games
                    .groupingBy { it.name }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            val seen = mutableMapOf<String, Int>()
            games.map { game ->
                val ordinal = seen.merge(game.name, 1, Int::plus)
                GameChoice(
                    id = game.id,
                    name = game.name,
                    isCompleted = game.isManuallyCompleted,
                    // Null for a name nobody else has: a bare `1` beside a unique
                    // name is noise that says nothing.
                    sharedNameOrdinal = ordinal.takeIf { game.name in sharedNames },
                )
            }
        }

    override fun observeColors(): Flow<List<ColorSummary>> =
        colorDao.observeColors().map { colors ->
            colors.map { ColorSummary(it.id, it.canonicalName, it.hex, it.sortOrder) }
        }

    override fun observeColorVocabulary(): Flow<ColorVocabulary> =
        combine(colorDao.observeColors(), colorDao.observeAliases()) { colors, aliases ->
            val byColor = aliases.groupBy { it.colorId }
            ColorVocabulary.of(
                colors.map { color ->
                    ColorVocabularyEntry(
                        canonicalName = color.canonicalName,
                        aliases = byColor[color.id].orEmpty().map { it.alias },
                    )
                },
            )
        }

    override suspend fun createDraftFromSelection(
        blockId: EntityId,
        startIndex: Int,
        endIndex: Int,
    ): EntityId {
        val draftId = idGenerator.newId()
        // A refusal the transaction already named travels out as it is; only a
        // storage failure has to be turned into something to say here.
        try {
            importDao.createDraftFromSelectionUnderReview(draftId, blockId, startIndex, endIndex, clock)
        } catch (cause: SQLiteException) {
            diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.IMPORT_REVIEW, ImportReviewFailure.COULD_NOT_SAVE, cause) }
            throw ImportReviewException(ImportReviewFailure.COULD_NOT_SAVE, cause)
        }
        return draftId
    }

    override suspend fun createDraftByHand(
        blockId: EntityId,
        name: String,
    ): EntityId {
        val draftId = idGenerator.newId()
        try {
            importDao.createDraftByHandUnderReview(draftId, blockId, name, clock)
        } catch (cause: SQLiteException) {
            diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.IMPORT_REVIEW, ImportReviewFailure.COULD_NOT_SAVE, cause) }
            throw ImportReviewException(ImportReviewFailure.COULD_NOT_SAVE, cause)
        }
        return draftId
    }

    override suspend fun saveDraft(edit: DraftEdit): Boolean =
        try {
            importDao.editDraftUnderReview(
                draftTaskId = edit.draftTaskId,
                name = edit.name,
                targetCellId = edit.targetCellId,
                poolType = edit.poolType,
                trackingMode = edit.trackingMode,
                requiredQuantity = edit.requiredQuantity,
                notes = edit.notes,
                isMissing = edit.isMissing,
                isBorrowed = edit.isBorrowed,
                needsInfo = edit.needsInfo,
                needsClassification = edit.needsClassification,
                completionHint = edit.completionHint,
                colorIds = edit.colorIds,
                clock = clock,
            )
        } catch (cause: SQLiteException) {
            diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.IMPORT_REVIEW, ImportReviewFailure.COULD_NOT_SAVE, cause) }
            throw ImportReviewException(ImportReviewFailure.COULD_NOT_SAVE, cause)
        }

    override suspend fun setCompletionDecision(
        draftTaskId: EntityId,
        decision: HintDecision,
    ): Boolean =
        try {
            importDao.setDraftCompletionDecisionUnderReview(draftTaskId, decision, clock)
        } catch (cause: SQLiteException) {
            diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.IMPORT_REVIEW, ImportReviewFailure.COULD_NOT_SAVE, cause) }
            throw ImportReviewException(ImportReviewFailure.COULD_NOT_SAVE, cause)
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
            diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.IMPORT_REVIEW, ImportReviewFailure.COULD_NOT_SAVE, cause) }
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
            diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.IMPORT_REVIEW, ImportReviewFailure.COULD_NOT_SAVE, cause) }
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
        requiredQuantity = requiredQuantity,
        notes = notes,
        selectionStartIndex = selectionStartIndex,
        selectionEndIndex = selectionEndIndex,
        isMissing = isMissing,
        isBorrowed = isBorrowed,
        needsInfo = needsInfo,
        needsClassification = needsClassification,
        colorIds = colorIds,
        materializedTaskId = materializedTaskId,
    )
