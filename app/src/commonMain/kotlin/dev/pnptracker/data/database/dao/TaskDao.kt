package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.CELL_COLUMN_DISPLAY_ORDER
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.entity.TaskStageEntity
import dev.pnptracker.data.database.projection.GameTaskRow
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.stagesOf
import dev.pnptracker.domain.tasks.TaskSetupException
import dev.pnptracker.domain.tasks.TaskSetupFailure
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStage(stage: TaskStageEntity)

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
     * drawn. Every guard is made in the same transaction as the writes, so a
     * cell cannot be taken away between the check and the insert.
     *
     * The task's pool has to match the column it is written in. PLAN 5.4 keeps
     * tasks out of the notes column entirely, and a card task in the cardboard
     * column would show up in a pool its cell says nothing about.
     *
     * The three refusals are told apart rather than lumped into one, because
     * they are different things to be told: the cell is gone, the column was
     * never a place for tasks, or the pool and the column disagree. A screen can
     * only say the right sentence if it is handed the right one, and a single
     * general refusal would also have swallowed programming mistakes that
     * happened to arrive as the same kind of exception.
     *
     * A card or board task gets its whole pipeline here too. PLAN 7.2 and 8 fix
     * the stages by pool, so they are not a later decision, and writing them with
     * the task is what keeps a pipeline from ever existing half described.
     *
     * @throws TaskSetupException with [TaskSetupFailure.CELL_NOT_AVAILABLE],
     *   [TaskSetupFailure.CELL_DOES_NOT_HOLD_TASKS] or
     *   [TaskSetupFailure.CELL_POOL_MISMATCH]; nothing is written in those cases.
     */
    @Transaction
    suspend fun addTaskToCell(
        task: TaskEntity,
        cellId: EntityId,
        segmentId: EntityId,
        moment: Instant,
    ) {
        if (activeCellCount(cellId) != 1) throw TaskSetupException(TaskSetupFailure.CELL_NOT_AVAILABLE)
        val columnType = columnTypeOfCell(cellId) ?: throw TaskSetupException(TaskSetupFailure.CELL_NOT_AVAILABLE)
        if (!columnType.holdsTasks) throw TaskSetupException(TaskSetupFailure.CELL_DOES_NOT_HOLD_TASKS)
        if (columnType.poolType != task.poolType) throw TaskSetupException(TaskSetupFailure.CELL_POOL_MISMATCH)
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
        stagesOf(task.poolType).forEachIndexed { index, stage ->
            insertStage(
                TaskStageEntity(
                    taskId = task.id,
                    stage = stage,
                    orderIndex = index,
                    createdAt = moment,
                    updatedAt = moment,
                ),
            )
        }
    }

    /**
     * One game's tasks, with the column each one is written in.
     *
     * The `game_id` bound and the joins are what keep a game's tasks to itself:
     * no other game's row can come back from here, whatever id is passed in, and
     * a task whose game has been deleted stays out along with it.
     *
     * The rows come back column by column in the order the table is read in, and
     * within a column in the order the pieces sit in the cell; `id` last keeps
     * the list from reshuffling when two rows tie.
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
        ORDER BY """ + CELL_COLUMN_DISPLAY_ORDER + """, cell_segments.order_index, tasks.id
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
     *
     * PLAN 12.10 lists this as the first section of the *active* pool, so a
     * finished task leaves it along with everything else that is done.
     */
    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.deleted_at IS NULL AND games.deleted_at IS NULL
          AND tasks.is_completed = 0
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

    /**
     * The work still to be done in one pool.
     *
     * This is what a pool screen shows. PLAN 5.6 keeps a finished task in its
     * cell and takes it only out of the active pool, which is exactly the
     * difference between this and [tasksOfPoolIncludingCompleted].
     */
    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.pool_type = :poolType
          AND tasks.deleted_at IS NULL AND games.deleted_at IS NULL
          AND tasks.is_completed = 0
        ORDER BY tasks.name, tasks.id
        """,
    )
    suspend fun activeUnfinishedTasksInPool(poolType: PoolType): List<TaskEntity>

    /** What has been finished in one pool, for the history view. */
    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.pool_type = :poolType
          AND tasks.deleted_at IS NULL AND games.deleted_at IS NULL
          AND tasks.is_completed = 1
        ORDER BY tasks.completed_at DESC, tasks.name, tasks.id
        """,
    )
    suspend fun completedTasksInPool(poolType: PoolType): List<TaskEntity>

    /**
     * Everything in one pool, finished or not.
     *
     * Named for what it includes rather than left to be inferred, so a caller
     * choosing this over [activeUnfinishedTasksInPool] is choosing it knowingly.
     * Deleted work is still left out: a deleted task is gone from every view but
     * the ones that say they show deleted rows.
     */
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
    suspend fun tasksOfPoolIncludingCompleted(poolType: PoolType): List<TaskEntity>

    /**
     * Every task written in one game, finished ones included.
     *
     * The game table shows work that is done as well as work that is not — PLAN
     * 5.6 leaves a finished task in its cell, ticked and struck through — so this
     * is the query behind a game row rather than the pool ones above.
     */
    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE game_cells.game_id = :gameId
          AND tasks.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY """ + CELL_COLUMN_DISPLAY_ORDER + """, cell_segments.order_index, tasks.id
        """,
    )
    suspend fun tasksOfGameIncludingCompleted(gameId: EntityId): List<TaskEntity>

    /** Every task written in one cell, finished ones included. */
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
    suspend fun tasksOfCellIncludingCompleted(cellId: EntityId): List<TaskEntity>

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
