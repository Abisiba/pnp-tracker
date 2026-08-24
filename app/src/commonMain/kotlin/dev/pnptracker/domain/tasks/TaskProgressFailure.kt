package dev.pnptracker.domain.tasks

/**
 * Why a change to a task's progress did not happen, in words a screen can show.
 *
 * Separate from [TaskSetupFailure] because these are things that go wrong while
 * working a task that already exists, not while making one. A quantity that is
 * zero or negative is missing from this list on purpose: PLAN 5.12 forbids it
 * outright, so it is refused before anything is attempted rather than being an
 * outcome the user is told about.
 */
enum class TaskProgressFailure {
    /** There is no such task, or it has been deleted. */
    TASK_NOT_AVAILABLE,

    /** The pool this task belongs to has no pipeline, so it has no stages. */
    TASK_HAS_NO_STAGES,

    /** The stage named is not one of the stages this task's pool runs through. */
    STAGE_NOT_IN_PIPELINE,

    /**
     * The task's total is unknown, so there is nothing for a stage to count up to.
     *
     * PLAN 7.2 asks the user for the total first rather than letting a pipeline
     * run against a bound nobody has given.
     */
    REQUIRED_QUANTITY_UNKNOWN,

    /**
     * A stage was asked to go further than the stage before it, or past the total.
     *
     * PLAN 7.2 writes the rule as `0 <= cut <= laminated <= printed <= total`;
     * a piece cannot be cut before it is printed.
     */
    STAGE_ORDER_VIOLATED,

    /**
     * More was reported made good than was still owed.
     *
     * PLAN 6.3 keeps the outstanding amount from going below zero.
     */
    MORE_RESOLVED_THAN_OUTSTANDING,

    /**
     * The print run was recorded on a task that is not counted by one.
     *
     * PLAN 6 is the 3D model throughout, and the one batch a task is printed in
     * is its idea alone. A card or board task is counted by its pipeline, and a
     * special task by whatever the user chose — neither has a run to finish, so
     * asking for one is a mistake rather than a thing to be quietly allowed.
     */
    PRIMARY_BATCH_ONLY_FOR_THREE_D,

    /**
     * A card was named on a shortage for a task that is not made of cards.
     *
     * PLAN 7.4 gives the naming to the card pipeline, where knowing *which* card
     * came out short is what makes the record useful. On any other pool it would
     * be a detail nothing can read back.
     */
    CARD_REFERENCE_ONLY_FOR_CARDS,

    /**
     * The identifier of this event has already been used for a different one.
     *
     * PLAN 5.12 has an event applied at most once, which is why the caller names
     * it. Handing the same name to a different movement is not a retry, and
     * treating it as one would drop the movement without telling anybody.
     */
    EVENT_ID_ALREADY_USED,
}

/**
 * Thrown when a task's progress could not be changed.
 *
 * Nothing has been written when this comes out: every progress change is one
 * transaction, and the guards are made inside it.
 */
class TaskProgressException(
    val failure: TaskProgressFailure,
    cause: Throwable? = null,
) : Exception("The task's progress could not be changed: $failure", cause)
