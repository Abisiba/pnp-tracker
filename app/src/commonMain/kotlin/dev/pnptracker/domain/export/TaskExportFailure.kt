package dev.pnptracker.domain.export

/**
 * Why tasks could not be written out, in the terms a user can act on.
 *
 * Deliberately short. Everything a developer would want to know about a broken
 * record is in [ExportInvariant], which never reaches the screen: a person
 * looking at this dialog can do nothing with the words `TASK_WITH_SEVERAL_SEGMENTS`,
 * and a message that names a table is a message that will one day name a value.
 */
enum class ExportFailure {
    /** There is no task to write, so no file was made (PLAN 14.4 exports tasks). */
    NOTHING_TO_EXPORT,

    /** Something stored is not what the rest of the application guarantees. */
    BROKEN_DATA,

    /**
     * Storage would not answer while the tasks were being read.
     *
     * Not [BROKEN_DATA]: nothing is known to be wrong with any record, only that
     * this reading of them did not happen. No file was made or changed.
     */
    COULD_NOT_READ,

    /** The chosen name is not a `.csv`, and this writes nothing else. */
    UNSUPPORTED_FILE_TYPE,

    /** The chosen place cannot be written to. */
    NOT_WRITABLE,

    /** The file could not be written; whatever was there before is untouched. */
    WRITE_FAILED,

    /**
     * The file system cannot replace the target in one step.
     *
     * Deleting the old file first and moving the new one into place afterwards
     * would turn a failed export into a lost file, so it is refused instead.
     */
    NOT_ATOMIC,
}

/**
 * Which guarantee turned out not to hold.
 *
 * These are all impossible through the application: a task is created inside a
 * cell, a colour is written with the next free slot, a segment is written with
 * its task. Finding one anyway means the file on disk has been edited by
 * something else, and the honest answer is to write nothing and say so.
 */
enum class ExportInvariant {
    /** PLAN 5.5: a task lives in a cell, through exactly one segment. */
    TASK_WITHOUT_SEGMENT,

    /** The same task is written in two places at once. */
    TASK_WITH_SEVERAL_SEGMENTS,

    /** The task's segment is in a column that does not feed the task's pool. */
    TASK_IN_THE_WRONG_CELL,

    /** The same colour is attached to one task twice. */
    DUPLICATE_TASK_COLOR,

    /** PLAN 5.10: slots run `0…N-1` with no gaps and no repeats. */
    BROKEN_COLOR_SLOTS,
}

/**
 * An export that cannot go ahead.
 *
 * The message names the reason and, for a broken record, the guarantee and the
 * identifier a developer would grep for. It carries no file path, no value out
 * of anybody's data and nothing that would be shown to a user.
 */
class TaskExportException(
    val failure: ExportFailure,
    val invariant: ExportInvariant? = null,
    val subjectId: String? = null,
) : Exception(
        "Tasks cannot be exported: $failure" +
            (invariant?.let { " ($it)" } ?: "") +
            (subjectId?.let { " for $it" } ?: ""),
    )
