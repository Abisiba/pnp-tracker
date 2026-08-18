package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import kotlin.time.Instant

/**
 * Reads and writes tasks.
 *
 * A task is active only when it is neither archived nor deleted, and when the
 * item and the game above it are not deleted either, so every active query walks
 * the whole chain.
 */
@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity)

    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN items ON items.id = tasks.item_id
        INNER JOIN games ON games.id = items.game_id
        WHERE tasks.deleted_at IS NULL AND tasks.is_archived = 0
          AND items.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY tasks.name
        """,
    )
    suspend fun activeTasks(): List<TaskEntity>

    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN items ON items.id = tasks.item_id
        INNER JOIN games ON games.id = items.game_id
        WHERE tasks.item_id = :itemId
          AND tasks.deleted_at IS NULL AND tasks.is_archived = 0
          AND items.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY tasks.name
        """,
    )
    suspend fun activeTasksOfItem(itemId: EntityId): List<TaskEntity>

    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN items ON items.id = tasks.item_id
        INNER JOIN games ON games.id = items.game_id
        WHERE tasks.pool_type = :poolType
          AND tasks.deleted_at IS NULL AND tasks.is_archived = 0
          AND items.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY tasks.name
        """,
    )
    suspend fun activeTasksInPool(poolType: PoolType): List<TaskEntity>

    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN items ON items.id = tasks.item_id
        INNER JOIN games ON games.id = items.game_id
        WHERE tasks.id = :id
          AND tasks.deleted_at IS NULL AND tasks.is_archived = 0
          AND items.deleted_at IS NULL AND games.deleted_at IS NULL
        """,
    )
    suspend fun activeTaskById(id: EntityId): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun taskByIdIncludingArchivedAndDeleted(id: EntityId): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY name")
    suspend fun allTasksIncludingArchivedAndDeleted(): List<TaskEntity>

    @Query("UPDATE tasks SET is_archived = 1, updated_at = :updatedAt WHERE id = :id AND is_archived = 0")
    suspend fun archive(
        id: EntityId,
        updatedAt: Instant,
    ): Int

    /**
     * Marks a task as deleted; a second call changes nothing.
     *
     * @return how many rows changed: 1 on the first call, 0 afterwards.
     */
    @Query("UPDATE tasks SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :id AND deleted_at IS NULL")
    suspend fun softDelete(
        id: EntityId,
        deletedAt: Instant,
    ): Int
}
