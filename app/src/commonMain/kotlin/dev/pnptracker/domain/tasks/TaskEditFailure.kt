package dev.pnptracker.domain.tasks

/**
 * Why a change to an existing task did not happen, in words a screen can show.
 *
 * Separate from [TaskSetupFailure] and [TaskProgressFailure] because these are
 * things that go wrong while changing what a task *is*, rather than while making
 * one or while working it.
 *
 * Two of them are refusals to guess. Turning a task made in several colours into
 * a task made in one — or the other way about — and changing the total of a
 * finished task whose pipeline is counted to it both have answers that PLAN does
 * not give, so they are handed back to the user rather than resolved by
 * inventing a rule and writing it to their database.
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

    /** The same colour was given to the task twice. */
    DUPLICATE_COLOR,

    /**
     * The change would turn a task made in one colour into one made in several,
     * or the other way about.
     *
     * PLAN 5.10 has both kinds and PLAN 12.7 draws them differently, but nothing
     * in PLAN says what becomes of a task carried across between them: which
     * colour a several-colour task would keep, what the one it loses meant, what
     * a single-colour task's second colour would do to the counter it already
     * has. So the colours of a task may be changed, reordered and replaced, and
     * how many kinds of thing it is may not.
     */
    COLOR_COUNT_NOT_CHANGEABLE,

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
    /**
     * Which colour of the task the refusal is about, counting from zero.
     *
     * Null when it is about the task as a whole. A task made in several colours
     * is edited as an ordered list, so "a colour is gone" needs to say which
     * entry of that list the user has to deal with.
     */
    val row: Int? = null,
    cause: Throwable? = null,
) : Exception("The task could not be changed: $failure", cause)
