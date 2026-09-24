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

    /**
     * A colour was named for a draft in a pool that has none.
     *
     * PLAN 5.10 gives colours to three dimensional printing alone. The review
     * screen offers none for the other three pools, so this guards the write
     * rather than asking the user a question — and it never takes a colour off a
     * draft that already carries one.
     */
    COLOR_NOT_ALLOWED_FOR_POOL,

    /** This cell is not one a game completion hint can be about. */
    BLOCK_CANNOT_CARRY_GAME_COMPLETION,

    /** Accepting a game completion hint means saying which game; none was given. */
    COMPLETION_TARGET_REQUIRED,

    /** A game was named for a hint that was not accepted, which says nothing. */
    COMPLETION_TARGET_NOT_ALLOWED,

    /** The game a hint was pointed at is gone, or has been deleted. */
    COMPLETION_TARGET_GAME_NOT_AVAILABLE,

    /** The selection runs backwards, is empty, or reaches past the cell text. */
    INVALID_SELECTION,

    /**
     * An end of the selection falls inside one of the user's own characters.
     *
     * Half of an emoji, a letter parted from its accent, a `\r` without its
     * `\n`. Storing one would put a broken name in the database that no later
     * step could repair, so the cut is refused instead.
     */
    SELECTION_SPLITS_A_CHARACTER,

    /** Nothing but whitespace, or nothing but `**`, was selected. */
    SELECTION_IS_EMPTY,

    /** The selected words run across a line ending, which is notes and not a name. */
    SELECTION_CONTAINS_LINE_BREAK,

    /** A draft was given a name that says nothing. */
    TASK_NAME_EMPTY,

    /** A total was given that is not a whole number above nothing. */
    INVALID_REQUIRED_QUANTITY,

    /**
     * A draft was marked both missing and borrowed.
     *
     * PLAN 10 gives each its own column and a cell is in one of them, so the two
     * together could not have come from a file.
     */
    MISSING_AND_BORROWED,

    /** The pool a draft was put in does not allow the way it is to be tracked. */
    TRACKING_MODE_NOT_ALLOWED,

    /** The cell a draft was aimed at is gone, or its game has been deleted. */
    TARGET_CELL_NOT_AVAILABLE,

    /** The target belongs to the notes column, which PLAN 5.4 keeps tasks out of. */
    TARGET_CELL_NOT_TASK_CAPABLE,

    /** The cell a draft was aimed at belongs to a different column than its pool. */
    TARGET_CELL_WRONG_COLUMN,

    /**
     * A `**` was answered on a draft the file never marked, or a real marker was
     * asked to become no marker at all.
     *
     * PLAN 11.5 makes the marker a fact about the source file. Whether it was
     * agreed to is the user's answer; whether it was there is not.
     */
    COMPLETION_HINT_NOT_ANSWERABLE,
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
