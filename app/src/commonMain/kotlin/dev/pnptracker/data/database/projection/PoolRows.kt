package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode

/**
 * One active task of one pool, with everything a pool card shows and everything
 * the shared task editor needs to open over it.
 *
 * The cell and segment are carried here rather than looked up when a card is
 * clicked. PLAN 12.10 makes a pool a reflection of the same task, so opening its
 * menu must reach the same record the game table would — and reaching it at
 * click time would be a read per task, which is the one thing the pool's reads
 * are shaped to avoid.
 */
data class PoolTaskRow(
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    @ColumnInfo(name = "segment_id")
    val segmentId: EntityId,
    @ColumnInfo(name = "cell_id")
    val cellId: EntityId,
    @ColumnInfo(name = "game_id")
    val gameId: EntityId,
    @ColumnInfo(name = "game_name")
    val gameName: String,
    @ColumnInfo(name = "task_name")
    val taskName: String,
    @ColumnInfo(name = "required_quantity")
    val requiredQuantity: Int?,
    @ColumnInfo(name = "notes")
    val notes: String?,
    @ColumnInfo(name = "tracking_mode")
    val trackingMode: TrackingMode,
    @ColumnInfo(name = "primary_batch_completed")
    val primaryBatchCompleted: Boolean,
    @ColumnInfo(name = "current_missing_quantity")
    val currentMissingQuantity: Int,
    /** PLAN 10 and 11.7: what the import said about the work, carried for the editor. */
    @ColumnInfo(name = "is_missing")
    val isMissing: Boolean = false,
    @ColumnInfo(name = "is_borrowed")
    val isBorrowed: Boolean = false,
    @ColumnInfo(name = "needs_info")
    val needsInfo: Boolean = false,
    @ColumnInfo(name = "needs_classification")
    val needsClassification: Boolean = false,
)

/** One colour of one task of a pool, in the user's own slot order. */
data class PoolColorRow(
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    @ColumnInfo(name = "color_id")
    val colorId: EntityId,
    @ColumnInfo(name = "canonical_name")
    val canonicalName: String,
    @ColumnInfo(name = "hex")
    val hex: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    @ColumnInfo(name = "slot_index")
    val slotIndex: Int,
)

/** One stage of one pipeline task, in pipeline order. */
data class PoolStageRow(
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    @ColumnInfo(name = "stage")
    val stage: ProductionStage,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "completed_quantity")
    val completedQuantity: Int,
)

/**
 * What one task has had reported against it, already added up.
 *
 * Added up by the database rather than by folding the events in memory, because
 * the events themselves are not shown here — only their total is (PLAN 12.10) —
 * and reading them all to add them would grow with the history rather than with
 * the pool.
 */
data class PoolFailureRow(
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    /**
     * Held as a `Long` because that is what the database added up.
     *
     * `SUM` over the events is a 64 bit number and the failure total is
     * deliberately uncapped — PLAN 6.4 lets a piece be spoiled more times than
     * the task needs pieces — so there is nothing here to bound it by. Narrowing
     * it would not report a number too large, it would report a different one:
     * the low half of it, which past two thousand million reads as a negative
     * count of things that went wrong.
     */
    @ColumnInfo(name = "failure_total")
    val failureTotal: Long,
)

/** How much work each pool is holding, for the sidebar. */
data class PoolCountRow(
    @ColumnInfo(name = "pool_type")
    val poolType: dev.pnptracker.domain.model.PoolType,
    @ColumnInfo(name = "active_count")
    val activeCount: Int,
    @ColumnInfo(name = "task_count")
    val taskCount: Int,
)
