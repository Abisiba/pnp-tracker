package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.SegmentKind

/**
 * One piece of one cell with the task it names already resolved, for writing.
 *
 * The read the table is drawn from answers a different question and leaves out
 * what a write has to know, so this is its own shape rather than a borrowed one.
 * [taskName] is null on a piece of plain text and also on a piece naming a task
 * that has been deleted — the join drops the deleted one, and a name nobody can
 * see is not part of the document.
 */
data class CellRunRow(
    @ColumnInfo(name = "segment_id")
    val segmentId: EntityId,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "kind")
    val kind: SegmentKind,
    @ColumnInfo(name = "text")
    val text: String?,
    @ColumnInfo(name = "task_id")
    val taskId: EntityId?,
    @ColumnInfo(name = "task_name")
    val taskName: String?,
)
