package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.domain.model.EntityId

/**
 * Reads and writes the colours of a task.
 *
 * The order the user picked the colours in is kept, because a single-item
 * multi-colour task is drawn by splitting its name across them in that order.
 * The order has to stay `0..N-1` with no gaps, which is a rule about the whole
 * set of a task's rows and so lives here rather than on any one row.
 */
@Dao
interface TaskColorDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(taskColor: TaskColorEntity)

    @Query("SELECT * FROM task_colors WHERE task_id = :taskId ORDER BY slot_index")
    suspend fun colorsOfTask(taskId: EntityId): List<TaskColorEntity>

    @Query("SELECT COUNT(*) FROM task_colors WHERE task_id = :taskId")
    suspend fun colorCountOfTask(taskId: EntityId): Int

    @Query("SELECT COUNT(*) FROM task_colors WHERE color_id = :colorId")
    suspend fun usageCountOfColor(colorId: EntityId): Int

    @Query("SELECT DISTINCT task_id FROM task_colors WHERE color_id = :colorId")
    suspend fun tasksUsingColor(colorId: EntityId): List<EntityId>

    @Query("DELETE FROM task_colors WHERE color_id = :colorId")
    suspend fun removeEveryUseOfColor(colorId: EntityId): Int

    @Query("UPDATE task_colors SET slot_index = :slotIndex WHERE task_id = :taskId AND color_id = :colorId")
    suspend fun setSlotIndex(
        taskId: EntityId,
        colorId: EntityId,
        slotIndex: Int,
    ): Int

    /**
     * Adds a colour to the end of a task's list.
     *
     * The place is worked out and used in the same transaction, so two colours
     * added at once cannot be given the same one.
     *
     * @throws IllegalArgumentException if the task already uses this colour.
     */
    @Transaction
    suspend fun addColorToTask(
        taskId: EntityId,
        colorId: EntityId,
    ) {
        val existing = colorsOfTask(taskId)
        require(existing.none { it.colorId == colorId }) {
            "The task $taskId already uses the colour $colorId."
        }
        insert(TaskColorEntity(taskId = taskId, colorId = colorId, slotIndex = existing.size))
    }

    /**
     * Closes the gaps in one task's colour order, leaving `0..N-1`.
     *
     * Renumbering in place would collide with the unique index the moment a
     * colour moved onto a place another one still holds, so the rows are lifted
     * out of the way first. The negative range is never a resting state: it only
     * exists between the two loops of one transaction.
     */
    @Transaction
    suspend fun compactSlotsOfTask(taskId: EntityId) {
        val ordered = colorsOfTask(taskId)
        if (ordered.withIndex().all { (index, row) -> row.slotIndex == index }) return
        ordered.forEachIndexed { index, row ->
            setSlotIndex(taskId = taskId, colorId = row.colorId, slotIndex = -(index + 1))
        }
        ordered.forEachIndexed { index, row ->
            setSlotIndex(taskId = taskId, colorId = row.colorId, slotIndex = index)
        }
    }
}
