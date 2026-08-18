package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.domain.model.ColorRelation
import dev.pnptracker.domain.model.EntityId

/**
 * Reads and writes the colors of a task.
 *
 * Two rules live here because they span several rows and only the database can
 * see all of them: a task uses either required colors or alternative ones, never
 * both, and at most one alternative is selected at a time.
 */
@Dao
interface TaskColorDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(taskColor: TaskColorEntity)

    @Query("SELECT * FROM task_colors WHERE task_id = :taskId ORDER BY color_id")
    suspend fun colorsOfTask(taskId: EntityId): List<TaskColorEntity>

    @Query("SELECT * FROM task_colors WHERE task_id = :taskId AND relation = :relation ORDER BY color_id")
    suspend fun colorsOfTaskByRelation(
        taskId: EntityId,
        relation: ColorRelation,
    ): List<TaskColorEntity>

    @Query("SELECT * FROM task_colors WHERE task_id = :taskId AND is_selected = 1")
    suspend fun selectedColorsOfTask(taskId: EntityId): List<TaskColorEntity>

    @Query("SELECT * FROM task_colors WHERE task_id = :taskId AND relation = 'ALTERNATIVE' AND is_selected = 1")
    suspend fun selectedAlternativeOfTask(taskId: EntityId): TaskColorEntity?

    @Query("UPDATE task_colors SET is_selected = 0 WHERE task_id = :taskId AND relation = 'ALTERNATIVE'")
    suspend fun clearAlternativeSelection(taskId: EntityId): Int

    @Query(
        "UPDATE task_colors SET is_selected = 1 " +
            "WHERE task_id = :taskId AND color_id = :colorId AND relation = 'ALTERNATIVE'",
    )
    suspend fun markAlternativeSelected(
        taskId: EntityId,
        colorId: EntityId,
    ): Int

    /**
     * Adds a color to a task after checking that it does not mix the two kinds of
     * relation.
     *
     * @throws IllegalArgumentException if the task already uses the other kind.
     */
    @Transaction
    suspend fun addRelation(taskColor: TaskColorEntity) {
        val existing = colorsOfTask(taskColor.taskId)
        val conflicting = existing.firstOrNull { it.relation != taskColor.relation }
        require(conflicting == null) {
            "Task ${taskColor.taskId} already uses ${conflicting?.relation} colors, " +
                "so a ${taskColor.relation} color cannot be added to it."
        }
        insert(taskColor)
    }

    /**
     * Picks one of a task's alternative colors, clearing any earlier pick first so
     * that never more than one is selected.
     *
     * Runs in one transaction: if [colorId] is not an alternative of this task the
     * whole change is rolled back and the earlier selection survives.
     *
     * @throws IllegalArgumentException if [colorId] is not an alternative of the task.
     */
    @Transaction
    suspend fun selectAlternativeColor(
        taskId: EntityId,
        colorId: EntityId,
    ) {
        clearAlternativeSelection(taskId)
        val updatedRows = markAlternativeSelected(taskId = taskId, colorId = colorId)
        require(updatedRows == 1) {
            "Color $colorId is not an alternative color of task $taskId."
        }
    }
}
