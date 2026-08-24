package dev.pnptracker.domain.games

/**
 * Why writing a cell's text did not happen, in words a screen can show.
 *
 * Nothing about the text itself is here. Text is stored as it was typed —
 * leading spaces, doubled spaces, punctuation, line breaks and all — so there is
 * no such thing as text this refuses.
 */
enum class CellTextFailure {
    /** There is no such game, or it has been deleted. */
    GAME_NOT_AVAILABLE,

    /**
     * The cell holds a task, so it has no whole-text form to write back.
     *
     * Writing one would flatten the task into words and take its colours, its
     * stages and its history with it. PLAN 12.5 edits a task through its own
     * menu and PLAN 5.5 keeps a task piece from being split like text, so a
     * whole-cell write is refused here rather than allowed to do the damage.
     * Turning a task back into text is a separate action the user asks for.
     */
    CELL_CONTAINS_TASKS,

    /** The storage refused the change, so nothing was written. */
    COULD_NOT_SAVE,
}

/**
 * Thrown when a cell's text could not be written.
 *
 * Nothing has been written when this comes out: the whole change is one
 * transaction and every guard is made inside it.
 */
class CellTextException(
    val failure: CellTextFailure,
    cause: Throwable? = null,
) : Exception("The cell's text could not be saved: $failure", cause)
