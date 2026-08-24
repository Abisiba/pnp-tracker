package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.GameCellDao
import dev.pnptracker.data.database.dao.GameDao
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.domain.importconfirm.DraftTaskProblem
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importconfirm.ImportConfirmationResult
import dev.pnptracker.domain.importconfirm.ImportConfirmationSummary
import dev.pnptracker.domain.importconfirm.TargetCellChoice
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.time.Clock

/**
 * Turning a reviewed import into real work, and nothing else.
 *
 * Kept as an interface so the screen can be driven without a database, and so
 * nothing above this line has to know what a Room entity looks like.
 */
interface ImportConfirmation {
    /** Cells the user has already opened, across all games, kept fresh. */
    fun observeTargetCells(): Flow<List<TargetCellChoice>>

    /**
     * What confirming this import would do right now, or null once it is gone.
     *
     * Advisory only. Every check in it is made again inside [confirm], because
     * the database can change between showing this and acting on it.
     */
    suspend fun summarize(batchId: EntityId): ImportConfirmationSummary?

    /**
     * Records where one draft's task will go and how it will be tracked.
     *
     * @throws ImportConfirmationException if the change did not reach the database.
     */
    suspend fun aimDraft(
        draftId: EntityId,
        targetCellId: EntityId?,
        poolType: PoolType?,
        trackingMode: TrackingMode?,
    )

    /**
     * Turns every draft of one import into a real task, in one transaction.
     *
     * @throws ImportConfirmationException if nothing was written, which is the
     *   only other outcome there is.
     */
    suspend fun confirm(
        batchId: EntityId,
        acknowledgeUnprocessedBlocks: Boolean,
    ): ImportConfirmationResult
}

class ImportConfirmationStore(
    private val importDao: ImportDao,
    private val gameCellDao: GameCellDao,
    private val gameDao: GameDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
) : ImportConfirmation {
    override fun observeTargetCells(): Flow<List<TargetCellChoice>> =
        combine(gameDao.observeActiveGames(), gameCellDao.observeCellsOfActiveGames()) { games, cells ->
            val gameNames = games.associate { it.id to it.name }
            cells.mapNotNull { cell ->
                // A cell whose game is not active has already been filtered out of
                // the cell query; this only guards the join.
                val gameName = gameNames[cell.gameId] ?: return@mapNotNull null
                // Notes hold no tasks, so they are never a place to send one.
                if (!cell.columnType.holdsTasks) return@mapNotNull null
                TargetCellChoice(
                    cellId = cell.id,
                    gameId = cell.gameId,
                    gameName = gameName,
                    columnType = cell.columnType,
                )
            }
        }

    override suspend fun summarize(batchId: EntityId): ImportConfirmationSummary? {
        val batch = importDao.batchById(batchId) ?: return null
        val drafts = importDao.draftTasksOfBatch(batchId)
        // Every draft's target looked up at once. Asking per draft made the
        // screen's re-read after each change cost one query per draft, and the
        // answer is the same either way: a cell missing from here is a cell that
        // is gone or whose game has been deleted.
        val aimedAt = drafts.mapNotNull { it.targetCellId }.toSet()
        val liveCells =
            if (aimedAt.isEmpty()) emptyMap() else importDao.activeCellColumns(aimedAt).associateBy { it.cellId }
        val problems =
            drafts.mapNotNull { draft ->
                val failure =
                    when {
                        draft.targetCellId == null -> ImportConfirmationFailure.TARGET_CELL_MISSING
                        draft.targetCellId !in liveCells -> ImportConfirmationFailure.TARGET_CELL_NOT_AVAILABLE
                        draft.selectedPoolType == null -> ImportConfirmationFailure.POOL_TYPE_MISSING
                        draft.selectedTrackingMode == null -> ImportConfirmationFailure.TRACKING_MODE_MISSING
                        else -> null
                    }
                failure?.let { DraftTaskProblem(draft.id, draft.name, it) }
            }
        return ImportConfirmationSummary(
            batchId = batch.id,
            status = batch.status,
            draftTaskCount = drafts.size,
            readyTaskCount = drafts.size - problems.size,
            unprocessedBlockCount = importDao.unprocessedRawBlockCount(batchId),
            problems = problems,
            hasAnyCell = importDao.activeCellCount() > 0,
        )
    }

    override suspend fun aimDraft(
        draftId: EntityId,
        targetCellId: EntityId?,
        poolType: PoolType?,
        trackingMode: TrackingMode?,
    ) {
        // A target that cannot take this task already arrives as an
        // ImportConfirmationException naming which way it was wrong, and passes
        // straight through to the screen. Only a storage refusal is turned into
        // one here; a broken invariant travels out as it is, because it is a
        // defect and not a saved-or-not.
        try {
            importDao.setDraftTargetUnderReview(
                draftId = draftId,
                targetCellId = targetCellId,
                poolType = poolType,
                trackingMode = trackingMode,
                updatedAt = clock.now(),
            )
        } catch (cause: SQLiteException) {
            throw ImportConfirmationException(ImportConfirmationFailure.COULD_NOT_SAVE, cause = cause)
        }
    }

    override suspend fun confirm(
        batchId: EntityId,
        acknowledgeUnprocessedBlocks: Boolean,
    ): ImportConfirmationResult {
        val createdTaskCount =
            try {
                importDao.confirmDraftBatch(
                    batchId = batchId,
                    acknowledgeUnprocessedBlocks = acknowledgeUnprocessedBlocks,
                    moment = clock.now(),
                    idGenerator = idGenerator,
                )
            } catch (cause: SQLiteException) {
                // The transaction rolled back, so nothing at all was written.
                throw ImportConfirmationException(ImportConfirmationFailure.COULD_NOT_SAVE, cause = cause)
            }
        return ImportConfirmationResult(
            batchId = batchId,
            createdTaskCount = createdTaskCount,
            createdGameCount = 0,
        )
    }
}
