package dev.pnptracker.domain.tasks

/**
 * What can go wrong while creating a task by hand, in words a screen can show.
 *
 * A blank name, a quantity that is not a positive number and a tracking mode the
 * pool does not allow are all missing from this list on purpose: the form refuses
 * them before anything is written, so they are states of the form rather than
 * outcomes of an attempt.
 *
 * The three cell cases are separate entries rather than one, because they are
 * different things to tell the user and lead to different next steps: wait for
 * the list to catch up, aim somewhere that takes tasks, or fix the disagreement
 * between the pool and the column.
 */
enum class TaskSetupFailure {
    /** The storage refused the change, so nothing was written. */
    COULD_NOT_SAVE,

    /** The cell the task was going in is gone, or its game has been deleted. */
    CELL_NOT_AVAILABLE,

    /** The cell belongs to the notes column, which PLAN 5.4 keeps tasks out of. */
    CELL_DOES_NOT_HOLD_TASKS,

    /** The task's pool and the cell's column disagree about what kind of work it is. */
    CELL_POOL_MISMATCH,
}

/**
 * Thrown when a task the user typed did not reach the database.
 *
 * Deliberately narrow, in the same way [dev.pnptracker.domain.games.GameSetupException]
 * is: only a recognised storage refusal or one of the cell cases above becomes
 * one of these. A broken invariant travels out untouched rather than being shown
 * to the user as a saving problem they could act on.
 */
class TaskSetupException(
    val failure: TaskSetupFailure,
    cause: Throwable? = null,
) : Exception("The task could not be saved: $failure", cause)
