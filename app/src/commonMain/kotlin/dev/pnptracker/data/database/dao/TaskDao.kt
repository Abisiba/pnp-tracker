package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.projection.GameTaskRow
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import kotlinx.coroutines.flow.Flow
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

    /** 1 while the item exists, is not deleted and its game is not deleted either, 0 otherwise. */
    @Query(
        """
        SELECT COUNT(*) FROM items
        INNER JOIN games ON games.id = items.game_id
        WHERE items.id = :itemId AND items.deleted_at IS NULL AND games.deleted_at IS NULL
        """,
    )
    suspend fun activeItemCount(itemId: EntityId): Int

    /**
     * Adds a task under an item that is really there.
     *
     * The foreign key already refuses an unknown item, but not a soft deleted
     * one, and it knows nothing at all about the game above it: both of those
     * rows still exist. Checking in the same transaction as the insert closes
     * that gap, so a task can never end up below something the user has thrown
     * away, and no check can go stale between being made and being acted on.
     *
     * @throws IllegalArgumentException if the item is missing or deleted, or its
     *   game is deleted; nothing is written in that case.
     */
    @Transaction
    suspend fun addTaskToActiveItem(task: TaskEntity) {
        require(activeItemCount(task.itemId) == 1) {
            "There is no item ${task.itemId} to add a task to."
        }
        insert(task)
    }

    /**
     * One game's tasks, with the name of the item each one hangs from.
     *
     * The `game_id` bound and the two joins are what keep a game's tasks to
     * itself: no other game's row can come back from here, whatever id is passed
     * in, and a task whose item or game has been deleted stays out along with it.
     *
     * The ordering is a presentation choice and carries no meaning of its own;
     * `id` last keeps the list from reshuffling when two names tie. The pool
     * sections are formed above this, because the pools are stored under their
     * names and sorting those alphabetically would not be the order PLAN 3.4
     * lists them in.
     */
    @Query(
        """
        SELECT tasks.id AS task_id, items.id AS item_id, items.name AS item_name,
               tasks.pool_type AS pool_type, tasks.tracking_mode AS tracking_mode,
               tasks.name AS task_name, tasks.required_quantity AS required_quantity,
               tasks.notes AS notes, tasks.source_raw_import_block_id AS source_raw_import_block_id
        FROM tasks
        INNER JOIN items ON items.id = tasks.item_id
        INNER JOIN games ON games.id = items.game_id
        WHERE items.game_id = :gameId
          AND tasks.deleted_at IS NULL AND tasks.is_archived = 0
          AND items.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY items.name, tasks.name, tasks.id
        """,
    )
    fun observeActiveTasksOfGame(gameId: EntityId): Flow<List<GameTaskRow>>

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
