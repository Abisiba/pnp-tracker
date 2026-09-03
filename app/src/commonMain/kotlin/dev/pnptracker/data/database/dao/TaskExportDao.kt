package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.CELL_COLUMN_DISPLAY_ORDER
import dev.pnptracker.data.database.projection.ExportColorRow
import dev.pnptracker.data.database.projection.ExportTaskRow

/**
 * The two reads a whole export is built from.
 *
 * Two, whatever the library holds. A read per task, per game or per colour would
 * turn a thousand tasks into thousands of round trips, and PLAN 16 rules that
 * shape out; more to the point, a file assembled from many separate reads would
 * describe several different moments and belong to none of them.
 *
 * So both run inside one transaction: what comes back is one reading of the
 * database, and a task finished while the file is being written is either in it
 * or not, never half of each.
 *
 * Nothing here writes. Exporting is a question, and a question that changed the
 * answer would be a strange thing to ask.
 */
@Dao
abstract class TaskExportDao {
    /**
     * Every task the user still has, with its game and its cell beside it.
     *
     * Outer joins on purpose. A task with no segment, or one whose game has been
     * deleted, has to come back and be told apart: the first is a broken record
     * and stops the export, the second is data the user chose to hide and is
     * simply left out. An inner join would make the two look identical, and
     * identical to a task that does not exist.
     *
     * The order is the file's order and the game table's order at once — game
     * name, then the columns as the table draws them, then position in the cell —
     * with the task's own identity settling anything left over, so the same
     * database always writes the same file.
     */
    @Query(
        """
        SELECT tasks.id AS task_id,
               tasks.name AS task_name,
               tasks.pool_type AS pool_type,
               tasks.required_quantity AS required_quantity,
               tasks.notes AS notes,
               tasks.is_completed AS is_completed,
               tasks.needs_info AS needs_info,
               games.id AS game_id,
               games.name AS game_name,
               (games.deleted_at IS NOT NULL) AS game_is_deleted,
               game_cells.column_type AS column_type,
               cell_segments.id AS segment_id
        FROM tasks
        LEFT JOIN cell_segments ON cell_segments.task_id = tasks.id
        LEFT JOIN game_cells ON game_cells.id = cell_segments.cell_id
        LEFT JOIN games ON games.id = game_cells.game_id
        WHERE tasks.deleted_at IS NULL
        ORDER BY games.name, games.id, $CELL_COLUMN_DISPLAY_ORDER, cell_segments.order_index, tasks.id
        """,
    )
    abstract suspend fun exportRows(): List<ExportTaskRow>

    /**
     * Every colour of every task the user still has, in the slot they gave it.
     *
     * Joined and ordered rather than folded together in SQL: `GROUP_CONCAT` has
     * no order of its own worth relying on and no way to escape a colour named
     * with a bar in it, and both of those are decisions this file should not be
     * making.
     */
    @Query(
        """
        SELECT task_colors.task_id AS task_id,
               task_colors.color_id AS color_id,
               task_colors.slot_index AS slot_index,
               colors.canonical_name AS canonical_name
        FROM task_colors
        INNER JOIN colors ON colors.id = task_colors.color_id
        INNER JOIN tasks ON tasks.id = task_colors.task_id
        WHERE tasks.deleted_at IS NULL
        ORDER BY task_colors.task_id, task_colors.slot_index
        """,
    )
    abstract suspend fun exportColors(): List<ExportColorRow>

    /** Both reads, from one reading of the database. */
    @Transaction
    open suspend fun snapshot(): ExportSnapshotRows = ExportSnapshotRows(exportRows(), exportColors())
}

/** What one transaction handed back, before anything has judged it. */
data class ExportSnapshotRows(
    val tasks: List<ExportTaskRow>,
    val colors: List<ExportColorRow>,
)
