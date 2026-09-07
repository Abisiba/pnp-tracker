package dev.pnptracker.domain.importrollback

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId

/**
 * Why an import could not be taken back.
 *
 * PLAN 11.4.4 allows exactly two outcomes and no third: the whole import is
 * taken back, or nothing at all happens. So every value here describes a refusal
 * in which not one row changed, and there is deliberately no value meaning
 * "partly done" — a batch cannot be left half taken back, and a failure that
 * said so would be describing a state the application will not produce.
 */
enum class ImportRollbackFailure {
    /** The import is not in the database any more. */
    BATCH_NOT_FOUND,

    /**
     * The import is still a draft, so it has produced nothing to take back.
     *
     * PLAN 11.4.4 names this separately from a refusal about a confirmed batch:
     * the user has not finished reviewing it, and the thing to do is finish or
     * discard the review rather than to take anything back.
     */
    BATCH_NOT_CONFIRMED,

    /**
     * This import has already been taken back.
     *
     * A guarded refusal, never a quiet success — the same shape as the second
     * confirmation in PLAN 11.4.2. `ROLLED_BACK` is a final state: the user
     * imports the file again as a new batch rather than re-running this one.
     */
    ALREADY_ROLLED_BACK,

    /**
     * The import kept no record of what its cells said beforehand.
     *
     * A batch confirmed before schema version 8, which had nowhere to store it.
     * PLAN 11.4.4 refuses rather than working the text out backwards from what
     * the cell says today, because everything the user has written since would
     * be thrown away by the guess.
     */
    NO_CELL_SNAPSHOT,

    /**
     * At least one task this import created has been touched since.
     *
     * One is enough for all of them: PLAN 11.4.4 stops the whole operation
     * rather than removing the ones that still look safe.
     */
    TASKS_WERE_EDITED,

    /**
     * At least one cell this import wrote into no longer reads as it left it.
     *
     * Independent of the tasks. A user can edit the plain text around a task
     * without touching the task at all, and a later import into the same cell
     * has the same effect — either way the words to restore are no longer known.
     */
    CELLS_WERE_EDITED,

    /**
     * The trail from the batch to its tasks is missing or does not add up.
     *
     * A confirmed batch has one task per draft, each anchored in a cell the
     * batch recorded. Anything else is a defect rather than something the user
     * did, and it is refused rather than guessed around.
     */
    PROVENANCE_BROKEN,

    /** The database refused the rollback, so nothing at all was written. */
    COULD_NOT_SAVE,
}

/** What is wrong with one task the import created. */
enum class TaskObstacle {
    /** Its `updatedAt` has moved away from its `createdAt`: something was changed. */
    EDITED,

    /** A shortage was reported against it, or made good. */
    HAS_PROGRESS_EVENT,

    /** Something has already happened to it that the history recorded. */
    HAS_HISTORY_EVENT,

    /** The user removed it from view. */
    DELETED,

    /** It has no piece of a cell any more — turned back into text, or stranded. */
    NOT_ANCHORED,

    /** Its piece of a cell is somewhere this import never recorded writing. */
    PROVENANCE_MISSING,
}

/** What is wrong with one cell the import wrote into. */
enum class CellObstacle {
    /** This import kept no record of what the cell said before it wrote. */
    SNAPSHOT_MISSING,

    /** The words the cell reads are not the words this import left in it. */
    DOCUMENT_CHANGED,

    /**
     * The words match but the pieces do not.
     *
     * The cell reads correctly and yet is not built the way this import built
     * it — another import's task sits in the same run, a piece is numbered
     * wrongly, or a task of this import is no longer where it was written. The
     * document check alone would pass, and removing pieces on that basis could
     * take somebody else's away.
     */
    STRUCTURE_CHANGED,
}

/**
 * One task standing in the way, named so the user can go and look at it.
 *
 * The name travels with it because PLAN 11.4.4 says the user is shown *what*
 * blocked the rollback, and an identifier is not that. Turning this into a
 * sentence is the screen's business (PLAN 17); nothing here is a message.
 */
data class BlockedTask(
    val taskId: EntityId,
    val taskName: String,
    val obstacle: TaskObstacle,
)

/**
 * One cell standing in the way, named by its game and its column.
 *
 * PLAN 11.4.4 asks for exactly those two, because that is how the user finds a
 * cell: a game row and a column of the table. The cell's own text is not carried
 * — the user is being sent to look at it, not shown a copy of it.
 */
data class BlockedCell(
    val cellId: EntityId,
    val gameId: EntityId?,
    val gameName: String?,
    val columnType: CellColumnType?,
    val obstacle: CellObstacle,
)

/**
 * Thrown when an import was not taken back. Nothing has been written when this
 * comes out: the whole rollback is one transaction.
 *
 * The blocked lists carry what the screen will show. The message deliberately
 * carries none of it: task names are the user's own words out of their file, and
 * a message ends up in logs.
 */
class ImportRollbackException(
    val failure: ImportRollbackFailure,
    val blockedTasks: List<BlockedTask> = emptyList(),
    val blockedCells: List<BlockedCell> = emptyList(),
    cause: Throwable? = null,
) : Exception("The import could not be taken back: $failure", cause)
