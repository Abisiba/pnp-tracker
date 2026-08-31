package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection

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
 * One colour of a task, as a cell draws it.
 *
 * The name travels with the value and neither can be shown alone: PLAN 17 will
 * not have colour be the only carrier of a meaning, so the swatch a cell paints
 * and the word a screen reader says come from the same row.
 *
 * A list of these rather than a single colour, even though this step only ever
 * creates one. PLAN 5.10 has a single task carry several ordered colours, and a
 * shape that could hold only one would have to be replaced rather than drawn
 * differently when that step arrives.
 */
data class TaskColorPreview(
    val colorId: EntityId,
    val canonicalName: String,
    val hex: String,
)

/**
 * One piece of a cell as the table previews it.
 *
 * A piece of plain text shows its text and a task shows its name, the colours it
 * is made in and how many are needed — PLAN 12.5.
 *
 * [text] is the piece's part of the **document**: for a task that is its name
 * and nothing else. The count is not in it and must never be written into it —
 * `×14` is something the table says about a task, not something the user typed —
 * so a cell reads back exactly as it was written whatever is drawn around it.
 */
data class CellSegmentPreview(
    /** The row this piece is stored as; what a selection in it is anchored to. */
    val segmentId: EntityId,
    /** The task this stands for, or null when the piece is plain text. */
    val taskId: EntityId?,
    /** The piece's part of the document: its own text, or the task's name. */
    val text: String,
    /** True when a task the user has finished; PLAN 5.6 leaves it in its cell. */
    val isCompletedTask: Boolean = false,
    /** How many the task needs, or null on plain text and on an unknown count. */
    val requiredQuantity: Int? = null,
    /** The task's colours in the order they were chosen; empty on plain text. */
    val colors: List<TaskColorPreview> = emptyList(),
    /** The task's note, exactly as the user wrote it. */
    val notes: String? = null,
    val trackingMode: TrackingMode? = null,
    /** Which pool the task is worked in; null on plain text. */
    val poolType: PoolType? = null,
    /**
     * How much the task still owes and has to be made again (PLAN 6.2).
     *
     * Carried with the piece for the same reason [hasProgress] is: the menu over
     * a task decides from it whether there is anything to make good, and asking
     * the database when the menu opens would be a read per task.
     */
    val currentMissingQuantity: Int = 0,
    /**
     * True when work has been recorded against the task.
     *
     * Carried with the piece rather than asked for when a panel opens, so a menu
     * over a task costs no query of its own. It is what decides whether changing
     * the total is safe, and what the user is warned about before turning the
     * task back into text.
     */
    val hasProgress: Boolean = false,
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
     * The document laid out with each run's place in it.
     *
     * Worked out once and read by everything that has to reason about offsets:
     * where a selection really is, whether a change reached into a task, where a
     * task sits on screen. Counting it again in each of those places is how the
     * three would come to disagree.
     */
    val runs: List<DocumentRun>
        get() {
            var at = 0
            return segments.map { segment ->
                DocumentRun(
                    segmentId = segment.segmentId,
                    taskId = segment.taskId,
                    text = segment.text,
                    start = at,
                ).also { at = it.end }
            }
        }

    /** The tasks written in this cell, in reading order. */
    val tasks: List<CellSegmentPreview> get() = segments.filter { it.isTask }

    /**
     * The text an editor opens on.
     *
     * The whole document, tasks included. A task's characters are in here
     * because that is what the user sees and points at — the editor counts its
     * offsets over the same string — but they are not the editor's to change:
     * [planDocumentChange] refuses any change that reaches one, so a task is
     * present, visible, and untouchable.
     *
     * Null only when the cell has never been opened, which is not a document at
     * all yet.
     */
    val editableText: String get() = text

    /**
     * Where a stretch of the cell's text really lives.
     *
     * The offsets arrive counted across the whole cell, because that is what the
     * user dragged over, but a cut has to be made in one stored piece. This is
     * the one place the two are reconciled: the selection is handed to the piece
     * that wholly contains it, with the offsets moved into that piece's own
     * frame.
     *
     * A selection that spans two pieces, or that lands on a task, has no answer
     * here and gets none. Guessing which piece was meant would cut somewhere the
     * user never pointed at, and that is the whole failure this shape exists to
     * make impossible.
     *
     * @return null when nothing about the selection can be trusted.
     */
    fun locateSelection(
        gameId: EntityId,
        startOffset: Int,
        endOffset: Int,
    ): CellTextSelection? {
        val cellId = cellId ?: return null
        if (startOffset < 0 || startOffset >= endOffset) return null
        var pieceStart = 0
        segments.forEach { segment ->
            val pieceEnd = pieceStart + segment.text.length
            if (startOffset >= pieceStart && endOffset <= pieceEnd) {
                if (segment.isTask) return null
                return CellTextSelection(
                    gameId = gameId,
                    cellId = cellId,
                    segmentId = segment.segmentId,
                    expectedText = segment.text,
                    startOffset = startOffset - pieceStart,
                    endOffset = endOffset - pieceStart,
                )
            }
            pieceStart = pieceEnd
        }
        return null
    }
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
