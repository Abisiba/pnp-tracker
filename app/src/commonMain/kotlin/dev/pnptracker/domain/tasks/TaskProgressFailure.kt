package dev.pnptracker.domain.tasks

/**
 * Why a change to a task's progress did not happen, in words a screen can show.
 *
 * Separate from [TaskSetupFailure] because these are things that go wrong while
 * working a task that already exists, not while making one.
 */
enum class TaskProgressFailure {
    /** There is no such task, or it has been deleted. */
    TASK_NOT_AVAILABLE,

    /** There is no such game, or it has been deleted. */
    GAME_NOT_AVAILABLE,

    /**
     * The game has moved since the question about finishing it was asked.
     *
     * PLAN 12.9 asks `Tüm görevler tamamlandı mı?` against the work standing at
     * that moment, so an answer given to that question may only be applied to
     * it. A task finished, reported short, added or deleted in between makes the
     * answer one to a different question, and applying it would finish work the
     * user was never shown.
     */
    STALE_GAME_COMPLETION,

    /**
     * The amount was not a number of pieces this could be about.
     *
     * PLAN 5.12 will not have a movement of nothing or of less than nothing, and
     * PLAN 6.4 caps what a task can owe at what it needs in total. An amount so
     * large that adding it up would wrap around is refused here for the same
     * reason: what came back would not be the number the user typed.
     *
     * A typed outcome rather than a thrown argument error, because the screen
     * that collects the amount has to be able to say what was wrong with it.
     */
    INVALID_QUANTITY,

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
     * A stage was asked to go further than the stage before it.
     *
     * PLAN 7.2 writes the rule as `0 <= cut <= laminated <= printed <= total`;
     * a piece cannot be cut before it is printed. Kept apart from
     * [STAGE_QUANTITY_EXCEEDS_REQUIRED] and [INVALID_QUANTITY] because what the
     * user has to do about each is different: reorder the steps, lower the
     * amount, or type a number at all.
     */
    STAGE_ORDER_VIOLATED,

    /** A stage was asked to count further than the task needs in total. */
    STAGE_QUANTITY_EXCEEDS_REQUIRED,

    /**
     * The task's pipeline is not the one its pool describes.
     *
     * A row missing, doubled, or belonging to some other pool's pipeline. Not
     * something a user can have caused, but refusing is still better than
     * writing a counter into a shape nothing else in the application expects.
     */
    STAGE_PIPELINE_BROKEN,

    /**
     * The stages have moved since the panel showing them was opened.
     *
     * The panel carries the counts it was opened on, and a save is refused
     * unless the database still says the same. Without it a panel left open
     * while the work moved elsewhere would put back the numbers it was opened
     * with, quietly undoing whatever happened in between.
     */
    STALE_STAGE_PROGRESS,

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
