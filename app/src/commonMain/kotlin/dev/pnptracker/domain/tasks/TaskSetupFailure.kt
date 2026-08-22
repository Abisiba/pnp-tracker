package dev.pnptracker.domain.tasks

/**
 * What can go wrong while creating a task by hand, in words a screen can show.
 *
 * A blank name, a quantity that is not a positive number and a tracking mode the
 * pool does not allow are all missing from this list on purpose: the form refuses
 * them before anything is written, so they are states of the form rather than
 * outcomes of an attempt.
 */
enum class TaskSetupFailure {
    /** The storage refused the change, so nothing was written. */
    COULD_NOT_SAVE,

    /** The item the task was going under is gone, or it or its game was deleted. */
    CELL_NOT_AVAILABLE,
}

/**
 * Thrown when a task the user typed did not reach the database.
 *
 * Deliberately narrow, in the same way [dev.pnptracker.domain.games.GameSetupException]
 * is: only a recognised storage refusal or a parent that is no longer there
 * becomes one of these. A broken invariant travels out untouched rather than
 * being shown to the user as a saving problem they could act on.
 */
class TaskSetupException(
    val failure: TaskSetupFailure,
    cause: Throwable? = null,
) : Exception("The task could not be saved: $failure", cause)
