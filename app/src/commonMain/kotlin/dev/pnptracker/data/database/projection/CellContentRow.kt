package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.SegmentKind

/**
 * One piece of one cell, with the task it names already resolved.
 *
 * The whole table's content arrives as a flat list of these and is folded into
 * rows in Kotlin. Reading it flat is what keeps the table to a fixed number of
 * queries however many games there are: a query per game, or per cell, or per
 * segment would each turn drawing the table into a storm of round trips.
 *
 * [taskId] is null on a piece of plain text, and also on a piece naming a task
 * the user has deleted — the join drops the deleted one rather than the segment,
 * so a cell keeps its other pieces instead of vanishing along with it.
 */
data class CellContentRow(
    @ColumnInfo(name = "segment_id")
    val segmentId: EntityId,
    @ColumnInfo(name = "cell_id")
    val cellId: EntityId,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "kind")
    val kind: SegmentKind,
    /** The piece's own text; null on a task piece. */
    @ColumnInfo(name = "text")
    val text: String?,
    @ColumnInfo(name = "task_id")
    val taskId: EntityId?,
    @ColumnInfo(name = "task_name")
    val taskName: String?,
    @ColumnInfo(name = "task_is_completed")
    val taskIsCompleted: Boolean?,
    /** How many the task needs, or null when the piece is text or the count is unknown. */
    @ColumnInfo(name = "task_required_quantity")
    val taskRequiredQuantity: Int?,
)
