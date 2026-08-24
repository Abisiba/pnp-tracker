package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import dev.pnptracker.data.database.projection.CellContentRow
import kotlinx.coroutines.flow.Flow

/**
 * The one read the game table is drawn from.
 *
 * The table needs three things: the games, their cells and what is written in
 * those cells. Each is a single query over every active game rather than one per
 * game, so the number of round trips does not grow with the library — the games
 * and the cells are read through [GameDao] and [GameCellDao], and everything
 * inside the cells is read here.
 *
 * They are kept as three streams rather than folded into one big join because
 * Room re-runs a query when a table it touches changes: renaming a game then
 * re-reads the games, and leaves the far larger content query alone.
 */
@Dao
interface GameTableDao {
    /**
     * Every piece of every cell of every game the user still has.
     *
     * A piece naming a task is given the task's name here rather than by a second
     * read, and a task the user deleted drops out of the join while its piece
     * stays in the result with nothing attached — which is how the fold knows to
     * leave it out of the preview without losing the pieces around it.
     *
     * The order is by cell and then by position in it, so the fold can group
     * without sorting and two reads never disagree about the order.
     */
    @Query(
        """
        SELECT cell_segments.cell_id AS cell_id,
               cell_segments.order_index AS order_index,
               cell_segments.kind AS kind,
               cell_segments.text AS text,
               tasks.id AS task_id,
               tasks.name AS task_name,
               tasks.is_completed AS task_is_completed
        FROM cell_segments
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        LEFT JOIN tasks ON tasks.id = cell_segments.task_id AND tasks.deleted_at IS NULL
        WHERE games.deleted_at IS NULL
        ORDER BY cell_segments.cell_id, cell_segments.order_index
        """,
    )
    fun observeCellContents(): Flow<List<CellContentRow>>
}
