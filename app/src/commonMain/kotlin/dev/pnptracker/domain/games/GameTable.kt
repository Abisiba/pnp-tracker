package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId

/**
 * Which games the table is showing.
 *
 * PLAN 12.4 is explicit that these are three views of the one table rather than
 * three screens, three data sources or an archive: the user works and edits in
 * whichever one they are in. So this is a filter over rows that were read once,
 * and switching between them reads nothing and writes nothing.
 *
 * A deleted game is in none of them. Deletion is not a view.
 */
enum class GameTableView {
    /** What is still being made. PLAN 12.4 opens the table here. */
    ONGOING,

    /** What the user has said is finished; PLAN 5.3 keeps it visible and editable. */
    COMPLETED,

    /** Both together. */
    ALL,
    ;

    /** Whether a row belongs in this view. */
    fun includes(row: GameTableRow): Boolean =
        when (this) {
            ONGOING -> !row.isCompleted
            COMPLETED -> row.isCompleted
            ALL -> true
        }
}

/**
 * One piece of a cell as the table previews it.
 *
 * A piece of plain text shows its text and a task shows its name — PLAN 12.5 —
 * which is as far as this step goes. What the eventual cell draws around a task
 * (its colours, its count, its badge) is not here, because none of it is decided
 * by anything this reads.
 */
data class CellSegmentPreview(
    /** The task this stands for, or null when the piece is plain text. */
    val taskId: EntityId?,
    /** The text to show: the piece's own, or the task's name. */
    val text: String,
    /** True when a task the user has finished; PLAN 5.6 leaves it in its cell. */
    val isCompletedTask: Boolean = false,
) {
    val isTask: Boolean get() = taskId != null
}

/**
 * One column of one game row.
 *
 * [cellId] is null when the game has no cell in this column yet. That is an
 * ordinary state and not a gap to be filled: PLAN 5.4 gives a game *at most* one
 * cell per column, so a column nobody has written in has none, and the table
 * shows the slot regardless. Opening the cell is a write, and writes belong to
 * the editing step — reading the table must never quietly create rows.
 */
data class CellPreview(
    val columnType: CellColumnType,
    val cellId: EntityId? = null,
    val segments: List<CellSegmentPreview> = emptyList(),
) {
    val isEmpty: Boolean get() = segments.isEmpty()
}

/**
 * One game as one row of the table.
 *
 * PLAN 12.3: a game is a row and a task is never a row of its own. Every row
 * carries all five columns in the order [CellColumnType] declares them, so the
 * table is rectangular without any screen having to pad it.
 */
data class GameTableRow(
    val gameId: EntityId,
    val gameName: String,
    val isCompleted: Boolean,
    val cells: List<CellPreview>,
) {
    init {
        require(cells.map { it.columnType } == CellColumnType.entries) {
            "A game row carries every column once, in table order: ${cells.map { it.columnType }}"
        }
    }

    /** This row's cell in one column; always present, sometimes empty. */
    fun cell(columnType: CellColumnType): CellPreview = cells[columnType.ordinal]
}
