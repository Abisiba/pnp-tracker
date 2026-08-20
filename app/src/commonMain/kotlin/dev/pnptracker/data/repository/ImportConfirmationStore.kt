package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.GameDao
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.data.database.dao.ItemDao
import dev.pnptracker.domain.importconfirm.DraftTaskProblem
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importconfirm.ImportConfirmationResult
import dev.pnptracker.domain.importconfirm.ImportConfirmationSummary
import dev.pnptracker.domain.importconfirm.TargetItemChoice
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
    /** Items the user has already made, across all games, kept fresh. */
    fun observeTargetItems(): Flow<List<TargetItemChoice>>

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
        targetItemId: EntityId?,
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
    private val itemDao: ItemDao,
    private val gameDao: GameDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
) : ImportConfirmation {
    override fun observeTargetItems(): Flow<List<TargetItemChoice>> =
        combine(gameDao.observeActiveGames(), itemDao.observeActiveItems()) { games, items ->
            val gameNames = games.associate { it.id to it.name }
            items.mapNotNull { item ->
                // A game the item points at but that is not active has already
                // been filtered out of the item query; this only guards the join.
                val gameName = gameNames[item.gameId] ?: return@mapNotNull null
                TargetItemChoice(
                    itemId = item.id,
                    gameId = item.gameId,
                    gameName = gameName,
                    itemName = item.name,
                )
            }
        }

    override suspend fun summarize(batchId: EntityId): ImportConfirmationSummary? {
        val batch = importDao.batchById(batchId) ?: return null
        val drafts = importDao.draftTasksOfBatch(batchId)
        val problems =
            drafts.mapNotNull { draft ->
                val failure =
                    when {
                        draft.targetItemId == null -> ImportConfirmationFailure.TARGET_ITEM_MISSING
                        importDao.activeItemCount(draft.targetItemId) != 1 ->
                            ImportConfirmationFailure.TARGET_ITEM_NOT_AVAILABLE

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
            hasAnyItem = importDao.activeItemCount() > 0,
        )
    }

    override suspend fun aimDraft(
        draftId: EntityId,
        targetItemId: EntityId?,
        poolType: PoolType?,
        trackingMode: TrackingMode?,
    ) {
        // Only a storage failure becomes something the user can act on. A broken
        // invariant travels out as it is, because it is a defect and not a
        // saved-or-not.
        try {
            importDao.setDraftTargetUnderReview(
                draftId = draftId,
                targetItemId = targetItemId,
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
