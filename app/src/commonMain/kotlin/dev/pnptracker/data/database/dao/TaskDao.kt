package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.projection.GameTaskRow
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Reads and writes tasks.
 *
 * A task is active when it is not deleted and the game holding the cell it is
 * written in is not deleted either, so every active query walks the chain
 * `tasks → cell_segments → game_cells → games`.
 */
@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegment(segment: CellSegmentEntity)

    /** 1 while the cell exists and its game has not been deleted, 0 otherwise. */
    @Query(
        """
        SELECT COUNT(*) FROM game_cells
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE game_cells.id = :cellId AND games.deleted_at IS NULL
        """,
    )
    suspend fun activeCellCount(cellId: EntityId): Int

    @Query("SELECT column_type FROM game_cells WHERE id = :cellId")
    suspend fun columnTypeOfCell(cellId: EntityId): CellColumnType?

    @Query("SELECT COALESCE(MAX(order_index), -1) + 1 FROM cell_segments WHERE cell_id = :cellId")
    suspend fun nextSegmentIndex(cellId: EntityId): Int

    /**
     * Writes a task and the piece of the cell that stands for it.
     *
     * The two are written together because neither means anything alone: a task
     * nothing points at is unreachable, and a segment naming no task cannot be
     * drawn. Both guards are made in the same transaction as the writes, so a
     * cell cannot be taken away between the check and the insert.
     *
     * The task's pool has to match the column it is written in. PLAN 5.4 keeps
     * tasks out of the notes column entirely, and a card task in the cardboard
     * column would show up in a pool its cell says nothing about.
     *
     * @throws IllegalArgumentException if the cell is gone or belongs to a column
     *   that cannot hold this task; nothing is written in that case.
     */
    @Transaction
    suspend fun addTaskToCell(
        task: TaskEntity,
        cellId: EntityId,
        segmentId: EntityId,
        moment: Instant,
    ) {
        require(activeCellCount(cellId) == 1) { "There is no cell $cellId to write a task in." }
        val columnType = requireNotNull(columnTypeOfCell(cellId)) { "The cell $cellId has no column." }
        require(columnType.holdsTasks) { "The $columnType column holds no tasks." }
        require(columnType.poolType == task.poolType) {
            "A ${task.poolType} task does not belong in the $columnType column."
        }
        insert(task)
        insertSegment(
            CellSegmentEntity.task(
                id = segmentId,
                cellId = cellId,
                orderIndex = nextSegmentIndex(cellId),
                taskId = task.id,
                moment = moment,
            ),
        )
    }

    /**
     * One game's tasks, with the column each one is written in.
     *
     * The `game_id` bound and the joins are what keep a game's tasks to itself:
     * no other game's row can come back from here, whatever id is passed in, and
     * a task whose game has been deleted stays out along with it.
     *
     * The ordering is a presentation choice and carries no meaning of its own;
     * the segment order is the order the user sees inside a cell, and `id` last
     * keeps the list from reshuffling when two rows tie.
     */
    @Query(
        """
        SELECT tasks.id AS task_id, game_cells.id AS cell_id, game_cells.column_type AS column_type,
               tasks.pool_type AS pool_type, tasks.tracking_mode AS tracking_mode,
               tasks.name AS task_name, tasks.required_quantity AS required_quantity,
               tasks.notes AS notes, tasks.source_raw_import_block_id AS source_raw_import_block_id
        FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE game_cells.game_id = :gameId
          AND tasks.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY game_cells.column_type, cell_segments.order_index, tasks.id
        """,
    )
    fun observeActiveTasksOfGame(gameId: EntityId): Flow<List<GameTaskRow>>

    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY tasks.name, tasks.id
        """,
    )
    suspend fun activeTasks(): List<TaskEntity>

    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE cell_segments.cell_id = :cellId
          AND tasks.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY cell_segments.order_index
        """,
    )
    suspend fun activeTasksOfCell(cellId: EntityId): List<TaskEntity>

    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.pool_type = :poolType
          AND tasks.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY tasks.name, tasks.id
        """,
    )
    suspend fun activeTasksInPool(poolType: PoolType): List<TaskEntity>

    /**
     * The active tasks that have no colour yet, which is what the `Renk seçilecek`
     * section of the 3D pool lists.
     *
     * Having no colour is a state a task can be in from the start, and one it can
     * fall back into when the last colour it used is deleted. PLAN 5.10 keeps
     * such a task alive and out of the colour groups until someone picks one.
     */
    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.deleted_at IS NULL AND games.deleted_at IS NULL
          AND tasks.pool_type = :poolType
          AND NOT EXISTS (SELECT 1 FROM task_colors WHERE task_colors.task_id = tasks.id)
        ORDER BY tasks.name, tasks.id
        """,
    )
    suspend fun activeTasksAwaitingAColor(poolType: PoolType): List<TaskEntity>

    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.id = :id
          AND tasks.deleted_at IS NULL AND games.deleted_at IS NULL
        """,
    )
    suspend fun activeTaskById(id: EntityId): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun taskByIdIncludingDeleted(id: EntityId): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY name")
    suspend fun allTasksIncludingDeleted(): List<TaskEntity>

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
