package dev.pnptracker.domain.tasks

/**
 * What can stop a stretch of a cell's text from becoming a task.
 *
 * Every case is its own constant rather than one general apology, because each
 * leads somewhere different: wait for the table to catch up, select fewer words,
 * pick another colour, type a real number. A screen can only say the right
 * sentence if it is handed the right one.
 *
 * Nothing about a broken invariant belongs in here. A tracking mode a pool does
 * not allow, an identity handed out twice, a segment written at a place another
 * one holds — those are programming mistakes and travel out as what they are,
 * because filing them under a failure the user is asked to fix would hide them.
 */
enum class TaskFromTextFailure {
    /** The game holding the cell has been deleted. */
    GAME_NOT_AVAILABLE,

    /** The cell is gone, or it belongs to some other game than the one asked about. */
    CELL_NOT_AVAILABLE,

    /** The cell is the notes column, which PLAN 5.4 keeps tasks out of entirely. */
    CELL_DOES_NOT_HOLD_TASKS,

    /** The piece of text the selection was made in is no longer there. */
    SEGMENT_NOT_AVAILABLE,

    /** The piece is a task already, so there is no text in it to cut. */
    SEGMENT_IS_NOT_PLAIN_TEXT,

    /**
     * The text changed after the selection was made.
     *
     * The offsets would land somewhere the user never pointed at, so the cut is
     * refused rather than made in the wrong place.
     */
    STALE_TEXT_SELECTION,

    /** The offsets do not describe a stretch of this text that could be cut. */
    INVALID_SELECTION,

    /** The selection runs across a line ending, which no task name does. */
    SELECTION_CONTAINS_LINE_BREAK,

    /** Nothing but whitespace was selected, so there is no name in it. */
    TASK_NAME_EMPTY,

    /** The colour was deleted between choosing it and saving. */
    COLOR_NOT_AVAILABLE,

    /** The quantity is not a whole number greater than zero. */
    INVALID_REQUIRED_QUANTITY,

    /**
     * Two of the tasks being made together were given the same colour.
     *
     * Refused rather than quietly folded into one. PLAN 12.7 makes a batch N
     * independent tasks, so merging two rows would silently create fewer tasks
     * than the user described and lose one of the quantities they typed.
     */
    DUPLICATE_COLOR,

    /** No task was described at all, so there is nothing to create. */
    NO_TASK_DESCRIBED,

    /** The storage refused the change, so nothing was written. */
    COULD_NOT_SAVE,
}

/**
 * Thrown when text the user selected did not become a task.
 *
 * Deliberately narrow, like the other setup exceptions: only a recognised case
 * above becomes one of these, and nothing is written when one is thrown.
 */
class TaskFromTextException(
    val failure: TaskFromTextFailure,
    /**
     * Which task of a batch the refusal is about, counting from zero.
     *
     * Null when it is about the whole attempt rather than one of its rows. A
     * batch is several tasks described at once (PLAN 12.7), so "a colour is
     * gone" is only half an answer: the user has a form with rows in it and
     * needs to be told which row to change.
     */
    val row: Int? = null,
    cause: Throwable? = null,
) : Exception("The selected text could not become a task: $failure", cause)
