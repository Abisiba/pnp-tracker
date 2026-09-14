package dev.pnptracker.domain.importconfirm

import dev.pnptracker.domain.importhealth.DraftContradiction
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

    /**
     * The draft's own records contradict each other (PLAN 11.4.5).
     *
     * Not something the review screen can put right: no edit reaches the rows
     * that disagree. Found before the automatic backup, so none is taken, or —
     * if the records changed after that look — inside the transaction before
     * its first write. Either way the batch stays a draft and nothing is written;
     * [ImportConfirmationException.contradictions] says how, for code and tests.
     */
    RECORDS_CONTRADICT_EACH_OTHER,

    /** There is nothing to turn into tasks yet. */
    NO_DRAFTS_TO_CONFIRM,

    /** There is no cell anywhere to write a task in; the user opens one first. */
    NO_CELLS_AVAILABLE,

    /** Cells are still unreviewed and the user has not said to go ahead anyway. */
    UNPROCESSED_BLOCKS_NOT_ACKNOWLEDGED,

    /** A draft has no target cell chosen. */
    TARGET_CELL_MISSING,

    /** A draft points at a cell that is gone, or whose game is gone. */
    TARGET_CELL_NOT_AVAILABLE,

    /** The target belongs to the notes column, which PLAN 5.4 keeps tasks out of. */
    TARGET_CELL_NOT_TASK_CAPABLE,

    /** The cell the draft points at belongs to a different column than its pool. */
    TARGET_CELL_WRONG_COLUMN,

    /** A draft has no pool chosen. */
    POOL_TYPE_MISSING,

    /** A draft has no tracking mode chosen. */
    TRACKING_MODE_MISSING,

    /**
     * A colour a draft was given is not in the catalogue any more.
     *
     * PLAN 5.9 deletes a colour physically and only on the user's own say-so, so
     * this is something that really happens between reviewing and confirming.
     */
    COLOR_NO_LONGER_AVAILABLE,

    /**
     * A draft's text selection no longer fits the cell it came from.
     *
     * The raw text is never rewritten, so this is a draft whose cell has gone —
     * or offsets that would cut a character in half.
     */
    SELECTION_NO_LONGER_FITS,

    /**
     * A draft still carries an unanswered `**`.
     *
     * PLAN 11.5 makes the marker a hint the user accepts or rejects, and PLAN
     * 11.4.2 lets no draft be quietly skipped — so an unanswered one stops the
     * whole import rather than producing a task that silently guessed.
     */
    COMPLETION_HINT_UNDECIDED,

    /**
     * A green game cell still carries an unanswered completion hint.
     *
     * The same rule one step out: the file being kept by hand means a green fill
     * is a question, and confirming would have to answer it one way or the other
     * on the user's behalf.
     */
    GAME_COMPLETION_HINT_UNDECIDED,

    /**
     * A green cell was accepted without saying which game it was about.
     *
     * The shape a version 5 database can hold: it could record the answer but had
     * nowhere to put the game. The user is asked rather than the answer thrown
     * away.
     */
    COMPLETION_TARGET_GAME_REQUIRED,

    /** The game an accepted green cell names is gone, or has been deleted. */
    COMPLETION_TARGET_GAME_NOT_AVAILABLE,

    /** The database refused the confirmation, so nothing at all was written. */
    COULD_NOT_SAVE,

    /**
     * The database could not be read for the automatic backup that comes first.
     *
     * PLAN 14.4.13 is fail closed here: an import that cannot be backed up does
     * not happen. The four below are the same rule seen from four sides, and
     * every one of them leaves the batch a draft with nothing written.
     */
    SNAPSHOT_NOT_MADE,

    /** The automatic backup could not be put in the backups folder. */
    SNAPSHOT_NOT_WRITTEN,

    /**
     * The automatic backup was written and could not be read back as one.
     *
     * PLAN 14.4.7 does not let a file be called a backup until the real reader
     * has opened it, so a file that will not come back up is a file the import
     * may not go ahead behind.
     */
    SNAPSHOT_NOT_VERIFIED,

    /**
     * The data changed between the automatic backup and the transaction.
     *
     * The backup on disk no longer describes the database it was taken from, so
     * going ahead would write into something the backup does not cover. Nothing
     * was written and the backup itself stands; the remedy is to confirm again
     * (PLAN 14.4.8).
     */
    DATA_CHANGED_MEANWHILE,
}

/**
 * Thrown when an import was not confirmed. Nothing has been written when this
 * comes out: the whole confirmation is one transaction.
 *
 * [draftTaskId] names the draft at fault for the per-draft cases so the screen
 * can point at it. The draft's *name* is deliberately not carried here and not
 * put in the message: it is text out of the user's file, and a message ends up
 * in logs.
 *
 * [contradictions] is filled by the store for
 * [ImportConfirmationFailure.RECORDS_CONTRADICT_EACH_OTHER] alone, and is never
 * shown to the user.
 */
class ImportConfirmationException(
    val failure: ImportConfirmationFailure,
    val draftTaskId: EntityId? = null,
    cause: Throwable? = null,
    val contradictions: Set<DraftContradiction> = emptySet(),
) : Exception("The import could not be confirmed: $failure", cause)
