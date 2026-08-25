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

    /** There is no such cell any more. */
    CELL_NOT_AVAILABLE,

    /**
     * The change reached into or across a task.
     *
     * PLAN 5.5 makes a task piece atomic: it cannot be split like text, deleted
     * through by the caret, or typed over. It keeps its colours, its pipeline
     * and its history by keeping its identity, so a change that would rewrite it
     * as characters is refused rather than allowed to do the damage. Turning a
     * task back into text is a separate action the user asks for.
     */
    CHANGE_CROSSES_A_TASK,

    /**
     * The cell says something other than what the editor was opened on.
     *
     * Someone else changed it in between. Writing anyway would silently throw
     * away whatever they did, so the user is told instead.
     */
    STALE_DOCUMENT,

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
