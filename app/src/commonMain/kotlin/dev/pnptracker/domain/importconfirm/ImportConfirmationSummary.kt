package dev.pnptracker.domain.importconfirm

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ImportBatchStatus

/** One cell the user can send a task to, named the way the screen lists it. */
data class TargetCellChoice(
    val cellId: EntityId,
    val gameId: EntityId,
    val gameName: String,
    val columnType: CellColumnType,
)

/** One draft that is not ready, and what is missing from it. */
data class DraftTaskProblem(
    val draftTaskId: EntityId,
    val draftTaskName: String,
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
