package dev.pnptracker.domain.importreview

/**
 * What can go wrong while reviewing, in words the screen can show.
 *
 * A missing import is not listed here: that is a state the workspace reports by
 * being absent, not an error the user did something to cause. Only a write that
 * did not happen belongs in this list, and there is exactly one of those so far.
 */
enum class ImportReviewFailure {
    /** The database refused the change, so the previous value still stands. */
    COULD_NOT_SAVE,
}

/**
 * Thrown when a review change did not reach the database.
 *
 * Deliberately narrow: it is raised only for a storage failure that has been
 * recognised as such. A broken invariant or any other unexpected error travels
 * out untouched rather than being dressed up as something the user can fix.
 */
class ImportReviewException(
    val failure: ImportReviewFailure,
    cause: Throwable? = null,
) : Exception("The review change could not be saved: $failure", cause)
