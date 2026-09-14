package dev.pnptracker.domain.importremoval

import dev.pnptracker.domain.model.EntityId

/**
 * Why an unconfirmed import was not removed.
 *
 * PLAN 11.4.5 allows two outcomes and no third: the draft and every row of its
 * own are gone, or not one row changed. So every value here describes a refusal
 * in which nothing was written, and there is deliberately no value meaning
 * "partly removed" — there is no such state for it to describe.
 *
 * A programming or invariant failure is not one of these. A broken
 * postcondition travels out as the exception it is, because it is a defect
 * rather than an answer, and calling it "could not be removed" would hide a bug
 * behind the user's data (PLAN 14.4.5).
 */
enum class DraftRemovalRefusal {
    /**
     * There is no import by that identity any more.
     *
     * What a second removal of the same draft finds, and what a removal of an
     * import that never existed finds: the two cannot be told apart once the row
     * is gone, and neither leaves anything to do. PLAN 11.4.5 makes repeating a
     * removal safe, so this is a typed answer and never an exception.
     */
    ALREADY_REMOVED,

    /**
     * The import has been confirmed, or confirmed and taken back.
     *
     * Its drafts became real tasks; clearing it away is not a removal of a draft
     * but the undoing of an import, and that is PLAN 11.4.4's operation.
     */
    NOT_A_DRAFT,

    /**
     * A real record still points at this draft: a game made from the batch, or a
     * task made from one of its raw cells, deleted or not.
     *
     * PLAN 11.4.5 will not delete real records so that a draft can be tidied
     * away. The references are looked for by an explicit query inside the
     * transaction, so this is known before anything is written and never
     * depends on reading a foreign key error.
     */
    HELD_BY_RECORDS,

    /** The database refused, so the transaction rolled back and nothing was written. */
    COULD_NOT_SAVE,
}

/** What asking to remove an unconfirmed import came to. */
sealed interface DraftRemovalOutcome {
    /**
     * The draft is gone, with exactly the rows PLAN 11.4.5 lets it take along.
     *
     * Only counts: which rows went is not something anything above the
     * database needs, and none of it is the user's own game or task data.
     */
    data class Removed(
        val batchId: EntityId,
        val rawBlockCount: Int,
        val draftTaskCount: Int,
        val draftColorCount: Int,
        val cellSnapshotCount: Int,
    ) : DraftRemovalOutcome

    /** Nothing was written. */
    data class Refused(
        val batchId: EntityId,
        val refusal: DraftRemovalRefusal,
    ) : DraftRemovalOutcome
}
