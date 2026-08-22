package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import dev.pnptracker.domain.model.EntityId

/**
 * A colour a task is produced in.
 *
 * Every relation here is a required one. There is no second kind: the user picks
 * the real colour while creating the task, so a task either has the colours it
 * needs or has none yet.
 *
 * [slotIndex] is the user's own order, which is what a single-item multi-colour
 * task is drawn from — the name is split across the colours in this order. It
 * starts at zero and leaves no gaps, so removing a colour means closing the gap
 * rather than leaving a hole the drawing would have to guess about.
 */
@Entity(
    tableName = "task_colors",
    primaryKeys = ["task_id", "color_id"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.RESTRICT,
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
        Index(value = ["task_id", "slot_index"], unique = true),
    ],
)
data class TaskColorEntity(
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    @ColumnInfo(name = "color_id")
    val colorId: EntityId,
    @ColumnInfo(name = "slot_index")
    val slotIndex: Int,
) {
    init {
        require(slotIndex >= 0) { "A colour slot starts at zero, was: $slotIndex" }
    }
}
