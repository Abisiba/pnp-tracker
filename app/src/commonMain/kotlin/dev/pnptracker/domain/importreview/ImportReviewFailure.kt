package dev.pnptracker.domain.importreview

/**
 * What can go wrong while reviewing, in words the screen can show.
 *
 * A missing import is not listed here: that is a state the workspace reports by
 * being absent, not an error the user did something to cause. Everything else in
 * the list is a change that did not happen, and each says what to do instead.
 */
enum class ImportReviewFailure {
    /** The database refused the change, so the previous value still stands. */
    COULD_NOT_SAVE,

    /** The draft this change was about is not there any more. */
    DRAFT_TASK_NOT_FOUND,

    /** The cell this change was about is not there any more. */
    RAW_BLOCK_NOT_FOUND,

    /**
     * The import has been confirmed or undone, so it is a record now.
     *
     * PLAN 11.4.3 makes a confirmed import read only; nothing is written.
     */
    BATCH_NOT_A_DRAFT,

    /** The same colour was chosen twice for one draft. */
    DUPLICATE_COLOR,

    /** A chosen colour is not in the catalogue any more. */
    COLOR_NOT_AVAILABLE,

    /** This cell is not one a game completion hint can be about. */
    BLOCK_CANNOT_CARRY_GAME_COMPLETION,

    /** Accepting a game completion hint means saying which game; none was given. */
    COMPLETION_TARGET_REQUIRED,

    /** A game was named for a hint that was not accepted, which says nothing. */
    COMPLETION_TARGET_NOT_ALLOWED,

    /** The game a hint was pointed at is gone, or has been deleted. */
    COMPLETION_TARGET_GAME_NOT_AVAILABLE,
}

/**
 * Thrown when a review change did not reach the database.
 *
 * Nothing has been written when this comes out: every path that raises it either
 * refuses before writing or does its writing inside one transaction.
 *
 * A broken invariant or any other unexpected error travels out untouched rather
 * than being dressed up as something the user can fix, and neither the name of a
 * draft nor the text of a cell is carried here: both are the user's own words
 * out of their own file, and a message ends up in logs.
 */
class ImportReviewException(
    val failure: ImportReviewFailure,
    cause: Throwable? = null,
) : Exception("The review change could not be saved: $failure", cause)
