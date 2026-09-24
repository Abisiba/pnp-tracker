package dev.pnptracker.data.repository

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.dao.GameCellDao
import dev.pnptracker.data.database.dao.GameDao
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.data.database.entity.DraftTaskEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.domain.backup.automatic.AutomaticSnapshot
import dev.pnptracker.domain.backup.automatic.AutomaticSnapshotTaker
import dev.pnptracker.domain.backup.automatic.SnapshotNotTaken
import dev.pnptracker.domain.backup.automatic.SnapshotProblem
import dev.pnptracker.domain.backup.retention.AutomaticBackupHousekeeping
import dev.pnptracker.domain.backup.retention.automaticBackupNameOf
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticRecord
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.diagnostics.storageWriteFailed
import dev.pnptracker.domain.importconfirm.DraftTaskProblem
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importconfirm.ImportConfirmationResult
import dev.pnptracker.domain.importconfirm.ImportConfirmationSummary
import dev.pnptracker.domain.importconfirm.RawBlockProblem
import dev.pnptracker.domain.importconfirm.TargetCellChoice
import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.rules.poolHoldsColors
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
     * Backs the database up, then turns every draft of one import into a real
     * task, in one transaction.
     *
     * The backup is not an aside. PLAN 14.4.8 puts an automatic snapshot in
     * front of every confirmation — no threshold, no difference between a
     * spreadsheet and a CSV, no way to turn it off — and a snapshot that cannot
     * be taken stops the import rather than being skipped.
     *
     * @throws ImportConfirmationException if nothing was written, which is the
     *   only other outcome there is.
     */
    suspend fun confirm(
        batchId: EntityId,
        acknowledgeUnprocessedBlocks: Boolean,
    ): ImportConfirmationResult
}

/** Raised inside the transaction when the database is no longer what was backed up. */
private class ChangedUnderneath : Exception("The data changed after the automatic backup was taken")

/** Raised inside the transaction when the draft's records have come to contradict each other. */
private class ContradictsNow(
    val health: DraftHealth.Contradicting,
) : Exception("The draft's records contradict each other")

class ImportConfirmationStore(
    private val database: AppDatabase,
    private val importDao: ImportDao,
    private val gameCellDao: GameCellDao,
    private val gameDao: GameDao,
    private val snapshots: AutomaticSnapshotTaker,
    private val housekeeping: AutomaticBackupHousekeeping,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
    private val diagnostics: Diagnostics = Diagnostics.None,
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
                        chosen.isNotEmpty() && draft.selectedPoolType?.let { !poolHoldsColors(it) } == true ->
                            ImportConfirmationFailure.COLOR_NOT_ALLOWED_FOR_POOL

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
            throw couldNotSave(cause)
        }
    }

    /**
     * The whole of PLAN 14.4.8, in the order the plan gives it.
     *
     * ```text
     * 1  the fifteen tables are read and written to a file nobody chose
     * 2  the file is read back by the real reader and held against what went down
     * 3  older automatic backups are cleared to the number the user chose
     * 4  the transaction reads the fifteen tables again as its first act
     * 5  anything that moved in between stops it, with nothing written
     * 6  and only then does the import become tasks
     * ```
     *
     * Steps 1 and 2 are somebody else's work — the snapshot has to have been
     * *verified* before this can hold one at all, which is why the type cannot
     * be constructed here (PLAN 14.4.13).
     *
     * Step 3 sits where the plan puts it, before the transaction rather than
     * after it. A confirmation that then fails leaves the snapshot on disk, and
     * retention has already been applied to it, so a run of refused imports
     * cannot pile up untouched files. Nothing it does can stop the import: PLAN
     * 14.4.13 is fail open about tidying up, and the housekeeping's own contract
     * is that it does not throw for an outcome.
     *
     * Step 4 is where this differs from a plain confirmation, and it is the
     * restore's guarantee reused rather than a second design (PLAN 14.4.4). The
     * transaction is `BEGIN IMMEDIATE`, so the write lock is held before the
     * first read; Room keeps one writer connection, so from that moment nothing
     * else in this application can write; and the reading that decides whether
     * to go ahead therefore describes the same database the writing lands on. A
     * global mutation barrier was considered and not chosen — this is the
     * guarantee it would have given, at the cost of one reading.
     *
     * Two looks at whether the draft's records agree (PLAN 11.4.5) stand on
     * either side of all this. The first is before step 1, read only, so a
     * draft that can never be confirmed costs no backup and no rotation. The
     * second is inside the transaction, after step 5 and before the first
     * write, so records that came to contradict each other after the first look
     * — and were then backed up as they were — still write nothing. Only a
     * contradiction stops either one; every other answer is left to the
     * confirmation's own checks, exactly as before.
     */
    override suspend fun confirm(
        batchId: EntityId,
        acknowledgeUnprocessedBlocks: Boolean,
    ): ImportConfirmationResult {
        val health =
            try {
                importDao.draftHealthOf(batchId)
            } catch (cause: SQLiteException) {
                // Nothing has been written, and no backup taken, when this is reached.
                throw couldNotSave(cause)
            }
        if (health is DraftHealth.Contradicting) throw recordedContradiction(health)

        val snapshot =
            try {
                snapshots.takeBeforeImport()
            } catch (notTaken: SnapshotNotTaken) {
                // Recorded where the snapshot was refused, which is the one place
                // that still knows why (PLAN 14.7.2); not a second time here.
                throw ImportConfirmationException(failureOf(notTaken.problem), cause = notTaken)
            }
        automaticBackupNameOf(snapshot.fileName)?.let { housekeeping.afterWriting(it.setName) }

        val createdTaskCount =
            try {
                confirmUnlessChanged(batchId, acknowledgeUnprocessedBlocks, snapshot)
            } catch (changed: ChangedUnderneath) {
                // Every catch here is outside the transaction: it has already been
                // rolled back when a record is handed over, so recording can add
                // nothing to it (PLAN 14.7.2).
                diagnostics.recordSafely { DiagnosticRecord(DiagnosticEvent.IMPORT_CHANGED_MEANWHILE) }
                throw ImportConfirmationException(ImportConfirmationFailure.DATA_CHANGED_MEANWHILE, cause = changed)
            } catch (contradicts: ContradictsNow) {
                throw recordedContradiction(contradicts.health, cause = contradicts)
            } catch (cause: SQLiteException) {
                // The transaction rolled back, so nothing at all was written.
                throw couldNotSave(cause)
            }
        return ImportConfirmationResult(
            batchId = batchId,
            createdTaskCount = createdTaskCount,
            createdGameCount = 0,
        )
    }

    /** Storage refused; the one record of it, and the answer the user is given. */
    private fun couldNotSave(cause: SQLiteException): ImportConfirmationException {
        diagnostics.recordSafely {
            storageWriteFailed(DiagnosticArea.IMPORT_CONFIRMATION, ImportConfirmationFailure.COULD_NOT_SAVE, cause)
        }
        return ImportConfirmationException(ImportConfirmationFailure.COULD_NOT_SAVE, cause = cause)
    }

    /** Either look found records that disagree; recorded once, whichever look it was. */
    private fun recordedContradiction(
        health: DraftHealth.Contradicting,
        cause: Throwable? = null,
    ): ImportConfirmationException {
        diagnostics.recordSafely { DiagnosticRecord(DiagnosticEvent.IMPORT_RECORDS_CONTRADICT) }
        return contradicting(health, cause)
    }

    /**
     * The confirmation, behind one reading of everything the snapshot covers.
     *
     * The comparison is the whole of the data rather than a count or a checksum
     * of it. A count agrees with a database holding the right number of the
     * wrong rows, and the thing being promised is that what is about to be
     * written into is what was backed up. It costs fifteen queries for the whole
     * import, however many drafts it has, so PLAN 16's shape rule holds.
     */
    private suspend fun confirmUnlessChanged(
        batchId: EntityId,
        acknowledgeUnprocessedBlocks: Boolean,
        snapshot: AutomaticSnapshot,
    ): Int =
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val before = backupDataOf(database.backupDao().snapshot())
                if (before != snapshot.data) throw ChangedUnderneath()
                // The second look, before the first write. Same reading as the
                // first, and inside the write lock, so it cannot be overtaken.
                val health = importDao.draftHealthOf(batchId)
                if (health is DraftHealth.Contradicting) throw ContradictsNow(health)

                importDao.confirmDraftBatch(
                    batchId = batchId,
                    acknowledgeUnprocessedBlocks = acknowledgeUnprocessedBlocks,
                    clock = clock,
                    idGenerator = idGenerator,
                )
            }
        }
}

/** The refusal for a draft whose records disagree, carrying how they do. */
private fun contradicting(
    health: DraftHealth.Contradicting,
    cause: Throwable? = null,
) = ImportConfirmationException(
    failure = ImportConfirmationFailure.RECORDS_CONTRADICT_EACH_OTHER,
    cause = cause,
    contradictions = health.contradictions,
)

/** What a snapshot that did not happen is, in the words the import screen has. */
private fun failureOf(problem: SnapshotProblem): ImportConfirmationFailure =
    when (problem) {
        SnapshotProblem.DATABASE_NOT_READ -> ImportConfirmationFailure.SNAPSHOT_NOT_MADE
        SnapshotProblem.NOT_WRITTEN -> ImportConfirmationFailure.SNAPSHOT_NOT_WRITTEN
        SnapshotProblem.NOT_VERIFIED -> ImportConfirmationFailure.SNAPSHOT_NOT_VERIFIED
    }
