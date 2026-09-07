package dev.pnptracker.domain.importrollback

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ImportBatchStatus

/**
 * What taking this import back would do, worked out before anything is written.
 *
 * This is what a screen shows so the user can decide, and PLAN 11.4.4 wants it
 * to say two different things: how much would go, or else exactly what is in the
 * way. It carries no segment identities and no cell text — those belong to the
 * transaction, not to a screen.
 *
 * It is **not** what the decision is made on. Every check here is made again
 * inside the writing transaction, from rows read there, because the database can
 * change between showing this and acting on it. A preview that said "safe" and a
 * transaction that trusted it would be a rollback deciding on yesterday's facts.
 */
data class ImportRollbackPreview(
    val batchId: EntityId,
    val status: ImportBatchStatus?,
    /** How many tasks would be taken out of view. */
    val taskCount: Int,
    /** How many cells would be put back the way they were. */
    val cellCount: Int,
    /** How many games would get a line in the history. */
    val gameCount: Int,
    val blockedTasks: List<BlockedTask>,
    val blockedCells: List<BlockedCell>,
    val blockingFailure: ImportRollbackFailure?,
) {
    val canRollBack: Boolean get() = blockingFailure == null
}

/** The preview shape of a decision already reached. */
fun previewOf(
    plan: ImportRollbackPlan,
    status: ImportBatchStatus?,
): ImportRollbackPreview =
    ImportRollbackPreview(
        batchId = plan.batchId,
        status = status,
        // Counts describe what would happen, and a blocked plan holds nothing to
        // do — so these are zero without a guard here, because the plan itself
        // is empty (PLAN 11.4.4: one conflict stops all of it).
        taskCount = plan.taskIds.size,
        cellCount = plan.cells.size,
        gameCount = plan.gameIds.size,
        blockedTasks = plan.blockedTasks,
        blockedCells = plan.blockedCells,
        blockingFailure = plan.failure,
    )

/**
 * What a rollback actually did.
 *
 * Only counts. The batch is `ROLLED_BACK`, its tasks are tombstoned and its
 * cells read as they did before — there is no partial outcome to describe,
 * because PLAN 11.4.4 does not allow one to exist.
 */
data class ImportRollbackResult(
    val batchId: EntityId,
    val removedTaskCount: Int,
    val restoredCellCount: Int,
    val affectedGameCount: Int,
)
