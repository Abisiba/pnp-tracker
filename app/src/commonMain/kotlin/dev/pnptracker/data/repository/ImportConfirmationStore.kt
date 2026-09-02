package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.GameCellDao
import dev.pnptracker.data.database.dao.GameDao
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.data.database.entity.DraftTaskEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.domain.importconfirm.DraftTaskProblem
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importconfirm.ImportConfirmationResult
import dev.pnptracker.domain.importconfirm.ImportConfirmationSummary
import dev.pnptracker.domain.importconfirm.RawBlockProblem
import dev.pnptracker.domain.importconfirm.TargetCellChoice
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.text.graphemeBoundariesOf
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
            // Two games really can share a name, and a row of chips saying the
            // same words twice asks the user to guess which is which. The game
            // list's reading order is fixed by the query, so counting through it
            // gives each of them the same number every time.
            val sharedNames =
                games
                    .groupingBy { it.name }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            val seen = mutableMapOf<String, Int>()
            val ordinals =
                games.associate { game ->
                    val at = seen.merge(game.name, 1, Int::plus)
                    game.id to at.takeIf { game.name in sharedNames }
                }
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
                    sharedNameOrdinal = ordinals[cell.gameId],
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
        val blocks = importDao.rawBlocksOfBatch(batchId).associateBy { it.id }
        // The colours the whole import chose, and the whole catalogue, in two
        // reads rather than two per draft. The catalogue is not read at all when
        // nothing was given a colour.
        val chosenColors = importDao.draftColorsOfBatch(batchId).groupBy { it.draftTaskId }
        val catalogue = if (chosenColors.isEmpty()) emptySet() else importDao.allColorIds().toSet()
        // Where each cell's characters really begin, worked out once per cell
        // rather than once per draft cut out of it.
        val boundaries = mutableMapOf<EntityId, Set<Int>>()

        val problems =
            drafts.mapNotNull { draft ->
                val target = draft.targetCellId?.let { liveCells[it] }
                val chosen = chosenColors[draft.id].orEmpty().map { it.colorId }
                val block = blocks[draft.rawImportBlockId]
                val failure =
                    when {
                        draft.targetCellId == null -> ImportConfirmationFailure.TARGET_CELL_MISSING
                        target == null -> ImportConfirmationFailure.TARGET_CELL_NOT_AVAILABLE
                        !target.columnType.holdsTasks -> ImportConfirmationFailure.TARGET_CELL_NOT_TASK_CAPABLE
                        draft.selectedPoolType == null -> ImportConfirmationFailure.POOL_TYPE_MISSING
                        target.columnType.poolType != draft.selectedPoolType ->
                            ImportConfirmationFailure.TARGET_CELL_WRONG_COLUMN

                        draft.selectedTrackingMode == null -> ImportConfirmationFailure.TRACKING_MODE_MISSING
                        draft.completionHint == HintDecision.PENDING ->
                            ImportConfirmationFailure.COMPLETION_HINT_UNDECIDED

                        chosen.any { it !in catalogue } -> ImportConfirmationFailure.COLOR_NO_LONGER_AVAILABLE
                        !selectionStillFits(draft, block, boundaries) ->
                            ImportConfirmationFailure.SELECTION_NO_LONGER_FITS

                        else -> null
                    }
                failure?.let { DraftTaskProblem(draft.id, draft.rawImportBlockId, draft.name, it) }
            }

        // Which games the accepted green cells can still reach, in one read.
        val reachable =
            if (blocks.values.none { it.gameCompletionHint == HintDecision.ACCEPTED }) {
                emptySet()
            } else {
                importDao.acceptedCompletionTargetsOfBatch(batchId).mapTo(mutableSetOf()) { it.id }
            }
        val blockProblems =
            blocks.values
                .mapNotNull { block ->
                    val failure =
                        when {
                            block.gameCompletionHint == HintDecision.PENDING ->
                                ImportConfirmationFailure.GAME_COMPLETION_HINT_UNDECIDED

                            block.gameCompletionHint != HintDecision.ACCEPTED -> null
                            // The one shape a version 5 database can hand over: the
                            // answer was recorded with nowhere to put the game.
                            block.completionTargetGameId == null ->
                                ImportConfirmationFailure.COMPLETION_TARGET_GAME_REQUIRED

                            block.completionTargetGameId !in reachable ->
                                ImportConfirmationFailure.COMPLETION_TARGET_GAME_NOT_AVAILABLE

                            else -> null
                        }
                    failure?.let { RawBlockProblem(block.id, block.rowIndex, block.columnIndex, it) }
                }.sortedWith(compareBy({ it.rowIndex }, { it.columnIndex }))

        return ImportConfirmationSummary(
            batchId = batch.id,
            status = batch.status,
            draftTaskCount = drafts.size,
            readyTaskCount = drafts.size - problems.size,
            unprocessedBlockCount = importDao.unprocessedRawBlockCount(batchId),
            problems = problems,
            blockProblems = blockProblems,
            hasAnyCell = importDao.activeCellCount() > 0,
        )
    }

    /**
     * Whether a draft's selection still describes whole characters of its cell.
     *
     * The same question the confirming transaction asks, asked early so the user
     * is told rather than refused. A draft the user typed has no selection and
     * nothing to check.
     */
    private fun selectionStillFits(
        draft: DraftTaskEntity,
        block: RawImportBlockEntity?,
        boundaries: MutableMap<EntityId, Set<Int>>,
    ): Boolean {
        val start = draft.selectionStartIndex ?: return true
        val end = draft.selectionEndIndex ?: return true
        if (block == null) return false
        val edges = boundaries.getOrPut(block.id) { graphemeBoundariesOf(block.rawText).toSet() }
        return end <= block.rawText.length && start in edges && end in edges
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
