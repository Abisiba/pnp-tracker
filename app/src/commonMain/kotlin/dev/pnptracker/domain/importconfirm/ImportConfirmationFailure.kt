package dev.pnptracker.domain.importconfirm

import dev.pnptracker.domain.model.EntityId

/**
 * Why an import could not be turned into real tasks.
 *
 * The list is split in two by what the user has to do next. The batch-wide cases
 * are about the import as a whole; the per-draft cases name something missing on
 * one draft, and the screen points at that draft rather than at the file.
 *
 * A draft carrying a pool type that its tracking mode does not allow, or a
 * quantity of zero, is not in this list: `DraftTaskEntity` refuses to exist that
 * way, so such a row could only come from outside the application. That is a
 * defect rather than something a user can correct, and it is left to travel out
 * as one.
 */
enum class ImportConfirmationFailure {
    /** The import is not in the database any more. */
    BATCH_NOT_FOUND,

    /**
     * This import has already been confirmed.
     *
     * A guarded refusal, never a quiet success: the first confirmation's tasks
     * stand and no second set is written.
     */
    ALREADY_CONFIRMED,

    /** The import is neither a draft nor confirmed, so it cannot be confirmed now. */
    BATCH_NOT_A_DRAFT,

    /** There is nothing to turn into tasks yet. */
    NO_DRAFTS_TO_CONFIRM,

    /** There is no item anywhere to attach a task to; the user creates one first. */
    NO_ITEMS_AVAILABLE,

    /** Cells are still unreviewed and the user has not said to go ahead anyway. */
    UNPROCESSED_BLOCKS_NOT_ACKNOWLEDGED,

    /** A draft has no target item chosen. */
    TARGET_ITEM_MISSING,

    /** A draft points at an item that is gone, or whose game is gone. */
    TARGET_ITEM_NOT_AVAILABLE,

    /** A draft has no pool chosen. */
    POOL_TYPE_MISSING,

    /** A draft has no tracking mode chosen. */
    TRACKING_MODE_MISSING,

    /** The database refused the confirmation, so nothing at all was written. */
    COULD_NOT_SAVE,
}

/**
 * Thrown when an import was not confirmed. Nothing has been written when this
 * comes out: the whole confirmation is one transaction.
 *
 * [draftTaskId] names the draft at fault for the per-draft cases so the screen
 * can point at it. The draft's *name* is deliberately not carried here and not
 * put in the message: it is text out of the user's file, and a message ends up
 * in logs.
 */
class ImportConfirmationException(
    val failure: ImportConfirmationFailure,
    val draftTaskId: EntityId? = null,
    cause: Throwable? = null,
) : Exception("The import could not be confirmed: $failure", cause)
