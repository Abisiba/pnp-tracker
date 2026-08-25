package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.TrackingMode

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
    @ColumnInfo(name = "task_notes")
    val taskNotes: String?,
    @ColumnInfo(name = "task_tracking_mode")
    val taskTrackingMode: TrackingMode?,
    /**
     * Whether any work has been recorded against the task.
     *
     * Carried with the row rather than asked for when a panel opens, because a
     * panel that had to ask would be a query per task. It is what decides whether
     * changing the total is safe, and what the user is warned about before
     * turning a task back into text.
     */
    @ColumnInfo(name = "task_has_progress")
    val taskHasProgress: Boolean?,
)
