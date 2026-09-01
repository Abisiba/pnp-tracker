package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import dev.pnptracker.domain.model.EntityId

/**
 * A colour the user has chosen for a task they are still drafting.
 *
 * Deliberately its own table rather than a reuse of [TaskColorEntity]. A draft
 * is not a task and does not become one until an import is confirmed: it can be
 * discarded with its whole batch, it may point at no cell yet, and its colours
 * are a decision in progress. Writing that decision into `task_colors` would
 * mean either inventing a task to hang it on or letting a production table hold
 * rows that stand for nothing the application produces.
 *
 * What it does share is the shape, because the two carry the same meaning and
 * one is copied into the other when an import is confirmed: [slotIndex] is the
 * user's own order, starting at zero and leaving no gaps, and the same colour
 * may not appear twice. PLAN 5.10 and 12.7 draw a single-item multi-colour task
 * by splitting its name across the colours in exactly this order, so an order
 * that had holes in it would leave the drawing guessing.
 *
 * The colour is held by `RESTRICT`: PLAN 5.9 deletes a colour only through the
 * user's own confirmed act, and a draft quietly losing a colour they picked
 * would be that act happening behind their back. The draft is held by `CASCADE`,
 * because a draft that is thrown away takes its unfinished decisions with it.
 */
@Entity(
    tableName = "draft_task_colors",
    primaryKeys = ["draft_task_id", "color_id"],
    foreignKeys = [
        ForeignKey(
            entity = DraftTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["draft_task_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ColorEntity::class,
            parentColumns = ["id"],
            childColumns = ["color_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["color_id"]),
        Index(value = ["draft_task_id", "slot_index"], unique = true),
    ],
)
data class DraftTaskColorEntity(
    @ColumnInfo(name = "draft_task_id")
    val draftTaskId: EntityId,
    @ColumnInfo(name = "color_id")
    val colorId: EntityId,
    @ColumnInfo(name = "slot_index")
    val slotIndex: Int,
) {
    init {
        require(slotIndex >= 0) { "A colour slot starts at zero, was: $slotIndex" }
    }
}
