package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import dev.pnptracker.data.database.projection.CellContentRow
import dev.pnptracker.data.database.projection.TaskColorRow
import kotlinx.coroutines.flow.Flow

/**
 * The one read the game table is drawn from.
 *
 * The table needs four things: the games, their cells, what is written in those
 * cells, and the colours of the tasks written there. Each is a single query over
 * every active game rather than one per game, so the number of round trips does
 * not grow with the library — the games and the cells are read through [GameDao]
 * and [GameCellDao], and everything inside the cells is read here.
 *
 * They are kept as separate streams rather than folded into one big join because
 * Room re-runs a query when a table it touches changes: renaming a game then
 * re-reads the games, and leaves the far larger content query alone.
 */
@Dao
interface GameTableDao {
    /**
     * Every piece of every cell of every game the user still has.
     *
     * A piece naming a task is given the task's name here rather than by a second
     * read. The task is joined by identity alone: a piece that is in the cell is
     * part of what the cell says, so nothing about the task's state can take it
     * out of the document the user is shown (PLAN 5.5).
     *
     * The order is by cell and then by position in it, so the fold can group
     * without sorting and two reads never disagree about the order.
     */
    @Query(
        """
        SELECT cell_segments.id AS segment_id,
               cell_segments.cell_id AS cell_id,
               cell_segments.order_index AS order_index,
               cell_segments.kind AS kind,
               cell_segments.text AS text,
               tasks.id AS task_id,
               tasks.name AS task_name,
               tasks.is_completed AS task_is_completed,
               tasks.required_quantity AS task_required_quantity,
               tasks.notes AS task_notes,
               tasks.tracking_mode AS task_tracking_mode,
               tasks.pool_type AS task_pool_type,
               tasks.current_missing_quantity AS task_current_missing_quantity,
               tasks.is_missing AS task_is_missing,
               tasks.is_borrowed AS task_is_borrowed,
               tasks.needs_info AS task_needs_info,
               tasks.needs_classification AS task_needs_classification,
               (tasks.primary_batch_completed = 1
                OR tasks.current_missing_quantity > 0
                OR EXISTS (SELECT 1 FROM progress_events WHERE progress_events.task_id = tasks.id)
                OR EXISTS (SELECT 1 FROM task_stages
                           WHERE task_stages.task_id = tasks.id AND task_stages.completed_quantity > 0)
               ) AS task_has_progress
        FROM cell_segments
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        LEFT JOIN tasks ON tasks.id = cell_segments.task_id
        WHERE games.deleted_at IS NULL
        ORDER BY cell_segments.cell_id, cell_segments.order_index
        """,
    )
    fun observeCellContents(): Flow<List<CellContentRow>>

    /**
     * Every colour of every task written in every game the user still has.
     *
     * One read for the whole table, like the one above. A task's colours are
     * needed to draw its piece of a cell — PLAN 12.5 — and asking for them per
     * task would make the number of queries grow with the number of tasks, which
     * is the one thing the table's reads are shaped to avoid.
     *
     * Ordered by task and then by the place the user gave each colour, so the
     * fold can group without sorting and PLAN 5.10's slot order survives the
     * journey to the screen.
     */
    @Query(
        """
        SELECT task_colors.task_id AS task_id,
               colors.id AS color_id,
               colors.canonical_name AS canonical_name,
               colors.hex AS hex
        FROM task_colors
        INNER JOIN colors ON colors.id = task_colors.color_id
        INNER JOIN cell_segments ON cell_segments.task_id = task_colors.task_id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE games.deleted_at IS NULL
        ORDER BY task_colors.task_id, task_colors.slot_index
        """,
    )
    fun observeTaskColors(): Flow<List<TaskColorRow>>
}
