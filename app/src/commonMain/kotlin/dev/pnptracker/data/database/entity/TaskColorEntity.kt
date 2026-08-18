package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import dev.pnptracker.domain.model.ColorRelation
import dev.pnptracker.domain.model.EntityId

/**
 * Ties a color to a task.
 *
 * A required color is always in use, so it is selected by definition. Alternative
 * colors start unselected and the user picks exactly one of them before the task
 * joins a color group.
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
    indices = [Index(value = ["color_id"])],
)
data class TaskColorEntity(
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    @ColumnInfo(name = "color_id")
    val colorId: EntityId,
    @ColumnInfo(name = "relation")
    val relation: ColorRelation,
    @ColumnInfo(name = "is_selected", defaultValue = "0")
    val isSelected: Boolean,
) {
    init {
        require(relation != ColorRelation.REQUIRED || isSelected) {
            "A required color is always in use, so it cannot be unselected."
        }
    }

    companion object {
        fun required(
            taskId: EntityId,
            colorId: EntityId,
        ): TaskColorEntity =
            TaskColorEntity(
                taskId = taskId,
                colorId = colorId,
                relation = ColorRelation.REQUIRED,
                isSelected = true,
            )

        fun alternative(
            taskId: EntityId,
            colorId: EntityId,
            isSelected: Boolean = false,
        ): TaskColorEntity =
            TaskColorEntity(
                taskId = taskId,
                colorId = colorId,
                relation = ColorRelation.ALTERNATIVE,
                isSelected = isSelected,
            )
    }
}
