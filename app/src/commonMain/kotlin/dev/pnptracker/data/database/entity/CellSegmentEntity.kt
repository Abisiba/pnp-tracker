package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.SegmentKind
import kotlin.time.Instant

/**
 * One piece of a cell document: either text, or a task standing in the text.
 *
 * The two kinds are exclusive and the [init] block says so rather than leaving it
 * to whoever writes the row: a piece of text has no task, and a task piece has no
 * text of its own — its words come from the task it names.
 *
 * [orderIndex] is unique within a cell, so the document has one reading order and
 * two pieces can never claim the same place. [taskId] is unique across the whole
 * table, which is how PLAN 16 gets its rule that a task belongs to exactly one
 * segment: a second segment naming the same task is refused by the index.
 */
@Entity(
    tableName = "cell_segments",
    foreignKeys = [
        ForeignKey(
            entity = GameCellEntity::class,
            parentColumns = ["id"],
            childColumns = ["cell_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["cell_id", "order_index"], unique = true),
        Index(value = ["task_id"], unique = true),
    ],
)
data class CellSegmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: EntityId,
    @ColumnInfo(name = "cell_id")
    val cellId: EntityId,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "kind")
    val kind: SegmentKind,
    /** The text of a [SegmentKind.PLAIN_TEXT] piece; null for a task piece. */
    @ColumnInfo(name = "text")
    val text: String? = null,
    /** The task a [SegmentKind.TASK] piece stands for; null for a text piece. */
    @ColumnInfo(name = "task_id")
    val taskId: EntityId? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
) {
    init {
        require(orderIndex >= 0) { "A segment cannot come before the start of its cell: $orderIndex" }
        when (kind) {
            SegmentKind.PLAIN_TEXT -> {
                require(text != null) { "A plain text segment has to carry text." }
                require(text.isNotEmpty()) { "An empty plain text segment is never written." }
                require(taskId == null) { "A plain text segment cannot name a task." }
            }

            SegmentKind.TASK -> {
                require(taskId != null) { "A task segment has to name the task it stands for." }
                require(text == null) { "A task segment reads its words from its task, not from text of its own." }
            }
        }
    }

    companion object {
        fun plainText(
            id: EntityId,
            cellId: EntityId,
            orderIndex: Int,
            text: String,
            moment: Instant,
        ): CellSegmentEntity =
            CellSegmentEntity(
                id = id,
                cellId = cellId,
                orderIndex = orderIndex,
                kind = SegmentKind.PLAIN_TEXT,
                text = text,
                createdAt = moment,
                updatedAt = moment,
            )

        fun task(
            id: EntityId,
            cellId: EntityId,
            orderIndex: Int,
            taskId: EntityId,
            moment: Instant,
        ): CellSegmentEntity =
            CellSegmentEntity(
                id = id,
                cellId = cellId,
                orderIndex = orderIndex,
                kind = SegmentKind.TASK,
                taskId = taskId,
                createdAt = moment,
                updatedAt = moment,
            )
    }
}
