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
 * shows the slot regardless. Opening the cell is a write, and a read of the
 * table must never quietly perform one.
 *
 * [holdsTasks] is read from what is stored rather than from what is previewed. A
 * piece naming a task the user deleted shows nothing, but the piece is still
 * there, and a cell holding one is still a cell whose text cannot be rewritten
 * wholesale.
 */
data class CellPreview(
    val columnType: CellColumnType,
    val cellId: EntityId? = null,
    val segments: List<CellSegmentPreview> = emptyList(),
    val holdsTasks: Boolean = false,
) {
    val isEmpty: Boolean get() = segments.isEmpty()

    /**
     * What the cell reads as, end to end.
     *
     * Straight concatenation and nothing else. The pieces of a cell carry their
     * own spacing — PLAN 5.5 makes the document the pieces in order, not a list
     * of words to be joined — so a separator invented here would be a character
     * the user never typed, appearing in the table and read out by a screen
     * reader as though it were theirs.
     */
    val text: String get() = segments.joinToString(separator = "") { it.text }

    /**
     * The text an editor may replace, or null when it may not.
     *
     * A cell holding a task has no whole-text form that could be written back:
     * flattening it would turn tasks into words and take their colours, stages
     * and history with them. Editing those belongs to the segment editor of a
     * later step, so until then the answer here is honestly nothing.
     */
    val editableText: String? get() = if (holdsTasks) null else text
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
