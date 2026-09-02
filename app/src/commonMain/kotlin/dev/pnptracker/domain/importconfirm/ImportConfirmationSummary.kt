package dev.pnptracker.domain.importconfirm

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ImportBatchStatus

/**
 * One cell the user can send a task to, named the way the screen lists it.
 *
 * [sharedNameOrdinal] is set only when another game carries the same name, and
 * is that game's place among the ones that share it. PLAN 17 does not let a
 * choice be ambiguous, and two chips reading `Wingspan › 3D Baskı` with nothing
 * to tell them apart is exactly that; the number comes from the game list's own
 * fixed reading order, so it does not move between reads.
 */
data class TargetCellChoice(
    val cellId: EntityId,
    val gameId: EntityId,
    val gameName: String,
    val columnType: CellColumnType,
    val sharedNameOrdinal: Int? = null,
)

/**
 * One draft that is not ready, and what is missing from it.
 *
 * [rawImportBlockId] travels with it so the screen can take the user to the cell
 * the draft came from: PLAN 17 asks that a problem be reachable, and a list of
 * names with nothing to press is a list of things to go and find by hand.
 */
data class DraftTaskProblem(
    val draftTaskId: EntityId,
    val rawImportBlockId: EntityId,
    val draftTaskName: String,
    val failure: ImportConfirmationFailure,
)

/**
 * One source cell that is not ready, and what is unanswered about it.
 *
 * Kept apart from [DraftTaskProblem] because it is about the cell rather than
 * about anything made from it: a green game cell nobody has answered stops the
 * import even when every draft in it is complete.
 */
data class RawBlockProblem(
    val rawImportBlockId: EntityId,
    /** One based, because the user reads this against their spreadsheet. */
    val rowIndex: Int,
    val columnIndex: Int,
    val failure: ImportConfirmationFailure,
)

/**
 * What confirming this import would do, worked out before anything is written.
 *
 * This is what the screen shows the user so they can decide. It is not what the
 * decision is made on: every check here is made again inside the confirming
 * transaction, because the database can change between reading this and acting
 * on it.
 */
data class ImportConfirmationSummary(
    val batchId: EntityId,
    val status: ImportBatchStatus,
    val draftTaskCount: Int,
    val readyTaskCount: Int,
    val unprocessedBlockCount: Int,
    val problems: List<DraftTaskProblem>,
    val blockProblems: List<RawBlockProblem> = emptyList(),
    val hasAnyCell: Boolean,
) {
    val isStillADraft: Boolean get() = status == ImportBatchStatus.DRAFT

    val isConfirmed: Boolean get() = status == ImportBatchStatus.CONFIRMED

    /** True when unreviewed cells mean the user has to say "go ahead anyway". */
    val needsUnprocessedAcknowledgement: Boolean get() = unprocessedBlockCount > 0

    /**
     * Why confirming is not possible yet, or null when it is.
     *
     * One draft being wrong stops the whole batch: PLAN says no draft is ever
     * quietly skipped, so a batch is either wholly ready or not ready at all.
     */
    val blockingFailure: ImportConfirmationFailure?
        get() =
            when {
                status == ImportBatchStatus.CONFIRMED -> ImportConfirmationFailure.ALREADY_CONFIRMED
                status != ImportBatchStatus.DRAFT -> ImportConfirmationFailure.BATCH_NOT_A_DRAFT
                draftTaskCount == 0 -> ImportConfirmationFailure.NO_DRAFTS_TO_CONFIRM
                !hasAnyCell -> ImportConfirmationFailure.NO_CELLS_AVAILABLE
                problems.isNotEmpty() -> problems.first().failure
                blockProblems.isNotEmpty() -> blockProblems.first().failure
                else -> null
            }

    val canConfirm: Boolean get() = blockingFailure == null
}

/**
 * What a confirmation actually did.
 *
 * [createdGameCount] is here to be asserted rather than reported: this version
 * never creates a game from an import, so anything but zero is a defect.
 */
data class ImportConfirmationResult(
    val batchId: EntityId,
    val createdTaskCount: Int,
    val createdGameCount: Int,
)
