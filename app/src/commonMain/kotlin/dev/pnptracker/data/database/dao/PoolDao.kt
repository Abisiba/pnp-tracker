package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import dev.pnptracker.data.database.projection.PoolColorRow
import dev.pnptracker.data.database.projection.PoolCountRow
import dev.pnptracker.data.database.projection.PoolFailureRow
import dev.pnptracker.data.database.projection.PoolStageRow
import dev.pnptracker.data.database.projection.PoolTaskRow
import dev.pnptracker.domain.model.PoolType
import kotlinx.coroutines.flow.Flow

/**
 * The reads a pool screen is drawn from.
 *
 * A pool is a reflection and never a record of its own (PLAN 12.10): nothing
 * here writes, and every query reads the same `tasks` rows the game table reads.
 * What a pool holds is decided by one column — `tasks.pool_type` — and by
 * nothing else. Tracking mode does not decide it, and cannot: PLAN gives cards
 * and board pieces the same `PIPELINE` mode, so a pool told apart by mode would
 * be two pools in one.
 *
 * The reads are whole-pool rather than per task, and kept as separate streams
 * rather than folded into one join. A single join over colours, stages and
 * events would multiply them by one another — a task in three colours with
 * three stages and two reports would come back eighteen times, and every total
 * over it would be eighteen times too large. Separate reads make that arithmetic
 * impossible rather than merely avoided.
 */
@Dao
interface PoolDao {
    /**
     * The work still to be done in one pool.
     *
     * Active means four things at once, and PLAN 5.6 and 12.10 give each of them:
     * the task is not finished, it has not been deleted, its game has not been
     * deleted, and it is still written somewhere — a task with no piece in any
     * cell is anchorless and has no place in the document the pool reflects.
     *
     * A game the user has marked finished is deliberately *not* one of them.
     * PLAN's scenario at the end keeps an unfinished task in the 3D pool while
     * its game stays completed, because finishing a game is a statement about
     * the game and not about the work left in it.
     *
     * The order here is only a starting point that never leaves two equal rows
     * to chance; the order the user sees is decided later, in one place, where
     * shortages come first.
     */
    @Query(
        """
        SELECT tasks.id AS task_id,
               cell_segments.id AS segment_id,
               game_cells.id AS cell_id,
               games.id AS game_id,
               games.name AS game_name,
               tasks.name AS task_name,
               tasks.required_quantity AS required_quantity,
               tasks.notes AS notes,
               tasks.tracking_mode AS tracking_mode,
               tasks.primary_batch_completed AS primary_batch_completed,
               tasks.current_missing_quantity AS current_missing_quantity
        FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.pool_type = :poolType
          AND tasks.is_completed = 0
          AND tasks.deleted_at IS NULL
          AND games.deleted_at IS NULL
        ORDER BY games.name, games.id, tasks.name, tasks.id
        """,
    )
    fun observeTasksOfPool(poolType: PoolType): Flow<List<PoolTaskRow>>

    /**
     * Every colour of every active task of one pool, read once.
     *
     * In slot order, because that order is what the task is: PLAN 5.10 numbers a
     * multi-colour task's colours from the user's own arrangement, and a card
     * that listed them in another order would be describing a different task.
     *
     * The colour is joined by identity. Two colours that happen to be written in
     * the same hex are two colours and group apart, and a colour the user has
     * renamed arrives under its new name without the pool knowing anything
     * changed.
     */
    @Query(
        """
        SELECT task_colors.task_id AS task_id,
               colors.id AS color_id,
               colors.canonical_name AS canonical_name,
               colors.hex AS hex,
               colors.sort_order AS sort_order,
               task_colors.slot_index AS slot_index
        FROM task_colors
        INNER JOIN colors ON colors.id = task_colors.color_id
        INNER JOIN tasks ON tasks.id = task_colors.task_id
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.pool_type = :poolType
          AND tasks.is_completed = 0
          AND tasks.deleted_at IS NULL
          AND games.deleted_at IS NULL
        ORDER BY task_colors.task_id, task_colors.slot_index
        """,
    )
    fun observeColorsOfPool(poolType: PoolType): Flow<List<PoolColorRow>>

    /**
     * Every stage of every active task of one pipeline pool, read once.
     *
     * In pipeline order, so the first unfinished one is simply the first that
     * has not been fully done — which is what PLAN 12.11 puts on the badge.
     */
    @Query(
        """
        SELECT task_stages.task_id AS task_id,
               task_stages.stage AS stage,
               task_stages.order_index AS order_index,
               task_stages.completed_quantity AS completed_quantity
        FROM task_stages
        INNER JOIN tasks ON tasks.id = task_stages.task_id
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.pool_type = :poolType
          AND tasks.is_completed = 0
          AND tasks.deleted_at IS NULL
          AND games.deleted_at IS NULL
        ORDER BY task_stages.task_id, task_stages.order_index
        """,
    )
    fun observeStagesOfPool(poolType: PoolType): Flow<List<PoolStageRow>>

    /**
     * What has been reported wrong against each active task of one pool.
     *
     * Grouped in the database, so one row comes back per task however long its
     * history is. Only what was reported counts towards this total: PLAN 12.10
     * asks for the total number of failures on the card, which is a record of
     * what went wrong and not a running balance — what is still owed is carried
     * on the task itself.
     */
    @Query(
        """
        SELECT progress_events.task_id AS task_id,
               SUM(progress_events.quantity) AS failure_total
        FROM progress_events
        INNER JOIN tasks ON tasks.id = progress_events.task_id
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.pool_type = :poolType
          AND tasks.is_completed = 0
          AND tasks.deleted_at IS NULL
          AND games.deleted_at IS NULL
          AND progress_events.kind = 'FAILURE_REPORTED'
        GROUP BY progress_events.task_id
        """,
    )
    fun observeFailureTotalsOfPool(poolType: PoolType): Flow<List<PoolFailureRow>>

    /**
     * How much each pool is holding, in one read for all four.
     *
     * The sidebar needs two different numbers and must not open a pool to get
     * them. `active_count` is the work still to do, which is what a pool's label
     * shows. `task_count` also counts the finished ones, which is what decides
     * whether the Special pool is there at all: PLAN 9 keeps it visible showing
     * `0 aktif` once its last task is done, and hides it again only when there
     * are none left at all.
     *
     * Grouped over the pools that have anything, so a pool with nothing in it
     * simply does not come back — the caller reads a missing row as zero rather
     * than as an absence to ask about.
     */
    @Query(
        """
        SELECT tasks.pool_type AS pool_type,
               SUM(CASE WHEN tasks.is_completed = 0 THEN 1 ELSE 0 END) AS active_count,
               COUNT(*) AS task_count
        FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.deleted_at IS NULL AND games.deleted_at IS NULL
        GROUP BY tasks.pool_type
        """,
    )
    fun observePoolCounts(): Flow<List<PoolCountRow>>
}
