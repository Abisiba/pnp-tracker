package dev.pnptracker.domain.backup

import dev.pnptracker.domain.time.LocalMoment

/** The one extension a backup is written under, and the one this will accept. */
const val BACKUP_EXTENSION: String = ".json"

/**
 * Why a backup could not be saved, in the terms a user can act on.
 *
 * Deliberately short and deliberately about the outcome rather than the cause. A
 * person looking at this message can choose another folder, free some space or
 * try again; nothing they can do depends on knowing which of the three steps of
 * an atomic write it was, so the ones that call for the same action share a
 * name and the ones that call for different actions do not.
 *
 * Cancelling the save dialog is not in here. Changing one's mind is an ordinary
 * outcome and not a failure.
 */
enum class BackupFailure {
    /** The save dialog came back with nothing that can be written to. */
    NO_DESTINATION,

    /** The chosen name is not a `.json`, and this writes nothing else. */
    UNSUPPORTED_FILE_TYPE,

    /** The chosen place cannot be written to. */
    NOT_WRITABLE,

    /** The half-written file beside the destination could not be made. */
    TEMPORARY_FILE_FAILED,

    /** The chosen place stopped being there — an unplugged disk, a removed folder. */
    TARGET_UNAVAILABLE,

    /** The file could not be written; whatever was there before is untouched. */
    WRITE_FAILED,

    /** The file system cannot replace the destination in one step. */
    NOT_ATOMIC,

    /** The database could not be read, so there is nothing to write. */
    COULD_NOT_READ_DATABASE,

    /** The backup document itself could not be produced from what was read. */
    COULD_NOT_BUILD_DOCUMENT,
}

/**
 * A backup that cannot go ahead.
 *
 * Carries the reason and, for a developer, whatever caused it. It carries no
 * file path, no value out of anybody's data and nothing that would be shown to
 * a user.
 */
class BackupException(
    val failure: BackupFailure,
    cause: Throwable? = null,
) : Exception("The backup cannot be saved: $failure", cause)

/**
 * A place the user chose to write to, seen from the side of the application that
 * must not know about files.
 *
 * Only the name is exposed. Where the file sits is the user's private business
 * and is of no use above this line, so no absolute path can reach the screen, a
 * message or a log through this route — there is simply nothing to leak.
 */
interface BackupFileHandle {
    /** The name to show, never a path. */
    val fileName: String

    /** Whether something is already there and would be replaced. */
    suspend fun exists(): Boolean

    /**
     * Writes [bytes] whole or not at all.
     *
     * Bytes rather than text: the backup's checksum is taken of exactly these,
     * and a re-encoding anywhere between the two would make every file look
     * corrupt to the reader that checked it.
     *
     * @throws BackupException if the file could not be written. Whatever was at
     *   the destination before is unchanged when this throws.
     */
    suspend fun write(bytes: ByteArray)
}

/** Asks the user where to save the backup. */
interface BackupFileGateway {
    /**
     * The chosen destination, or null when the user changed their mind — which
     * is an ordinary outcome and not a failure.
     *
     * @throws BackupException with [BackupFailure.UNSUPPORTED_FILE_TYPE] if the
     *   chosen name is not a `.json`, or [BackupFailure.NO_DESTINATION] if the
     *   dialog answered with nothing usable.
     */
    suspend fun chooseDestination(suggestedName: String): BackupFileHandle?
}

/**
 * The name offered before the user types anything: `pnp-yedek-2026-09-08.json`.
 *
 * The date is the user's own calendar day, taken from [LocalMoment], so somebody
 * saving at eleven at night gets the day they are living rather than tomorrow in
 * some other zone. It is built by padding numbers rather than by formatting
 * them, because a formatter follows the machine's language and this name may
 * not: the same day has to produce the same name in every locale.
 *
 * No time of day. A manual backup is something a person takes occasionally, and
 * two on the same day are told apart by the overwrite question rather than by a
 * name nobody can read. And nothing out of the data: not a game, not a task, not
 * the machine — a file name travels further than the file's contents do.
 */
fun suggestedBackupFileName(moment: LocalMoment): String {
    val year = moment.year.toString().padStart(4, '0')
    val month = moment.month.toString().padStart(2, '0')
    val day = moment.dayOfMonth.toString().padStart(2, '0')
    return "pnp-yedek-$year-$month-$day$BACKUP_EXTENSION"
}

/**
 * The name a backup will actually be written under.
 *
 * A name with no extension gets `.json`, because somebody typing `yedek` means
 * the only thing on offer. A name that already ends in `.json` is left exactly
 * as it is, whatever case it was typed in: turning `YEDEK.JSON` into
 * `YEDEK.JSON.json` would be the application being pedantic about something no
 * file system cares about.
 *
 * A name ending in anything else is refused rather than quietly given a second
 * extension or, worse, written as a backup under a name that promises something
 * different. This is the same rule the task export applies to `.csv`, for the
 * same reason.
 *
 * @throws BackupException with [BackupFailure.UNSUPPORTED_FILE_TYPE].
 */
fun jsonFileNameOf(chosenName: String): String {
    require(chosenName.isNotEmpty()) { "A file needs a name" }

    val lastDot = chosenName.lastIndexOf('.')
    // A leading dot is a hidden file rather than an extension, so `.yedek` has
    // no extension and becomes `.yedek.json`.
    if (lastDot <= 0) return chosenName + BACKUP_EXTENSION
    if (chosenName.regionMatches(lastDot, BACKUP_EXTENSION, 0, BACKUP_EXTENSION.length, ignoreCase = true) &&
        lastDot + BACKUP_EXTENSION.length == chosenName.length
    ) {
        return chosenName
    }
    throw BackupException(BackupFailure.UNSUPPORTED_FILE_TYPE)
}
