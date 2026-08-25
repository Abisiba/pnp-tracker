package dev.pnptracker.domain.tasks

/**
 * Why a change to an existing task did not happen, in words a screen can show.
 *
 * Separate from [TaskSetupFailure] and [TaskProgressFailure] because these are
 * things that go wrong while changing what a task *is*, rather than while making
 * one or while working it.
 *
 * Two of them are refusals to guess. A task with more than one colour and a
 * finished task whose pipeline is counted to its old total both have answers
 * that PLAN does not give, so they are handed back to the user rather than
 * resolved by inventing a rule and writing it to their database.
 */
enum class TaskEditFailure {
    /** There is no such task, it has been deleted, or its game has. */
    TASK_NOT_AVAILABLE,

    /** The name is empty or nothing but whitespace. */
    TASK_NAME_EMPTY,

    /**
     * The name runs across a line ending.
     *
     * A task's name is one stretch of the cell's document; two lines of somebody's
     * notes are not the name of one thing to make.
     */
    NAME_CONTAINS_LINE_BREAK,

    /** The colour was deleted between choosing it and saving. */
    COLOR_NOT_AVAILABLE,

    /**
     * The task has more than one colour, which this cannot edit.
     *
     * PLAN 5.10 gives a single-item multi-colour task several ordered colours and
     * PLAN 12.7 draws its name split across them. Offering one colour box here
     * would quietly reduce it to a single colour and lose that order, so it is
     * refused until the step that can edit the whole list.
     */
    MULTICOLOR_EDIT_NOT_AVAILABLE,

    /** The quantity is not a whole number greater than zero. */
    INVALID_REQUIRED_QUANTITY,

    /**
     * The new total is below work already recorded against the task.
     *
     * PLAN 6.4 and 7.2 keep what is owed and what each stage has done within the
     * total, so lowering the total under them would leave counters the history
     * cannot account for.
     */
    QUANTITY_BELOW_PROGRESS,

    /**
     * The task is finished and its pipeline is counted to the total it had.
     *
     * PLAN 6.4 will not let the finished mark contradict the counters, and PLAN
     * describes no reopening on a change of total — so rather than inventing one,
     * or quietly moving the stage counts to match, the change is refused and the
     * user decides what they meant.
     */
    QUANTITY_LOCKED_BY_COMPLETION,

    /** The storage refused the change, so nothing was written. */
    COULD_NOT_SAVE,
}

/**
 * Thrown when a task could not be changed.
 *
 * Nothing has been written when this comes out: every change to a task is one
 * transaction and the guards are made inside it.
 */
class TaskEditException(
    val failure: TaskEditFailure,
    cause: Throwable? = null,
) : Exception("The task could not be changed: $failure", cause)
