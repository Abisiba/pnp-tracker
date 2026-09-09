package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.BACKUP_EXTENSION
import dev.pnptracker.domain.time.LocalMoment

/** What every automatic pre-restore backup is called before the date. */
const val SAFETY_BACKUP_PREFIX: String = "pnp-oncesi-"

/**
 * How many names one moment may be asked for before this gives up.
 *
 * A bound rather than a loop that keeps going: a directory that answers "already
 * there" to every name it is offered is a directory something is wrong with, and
 * trying for ever would hang the restore instead of failing it. Two backups
 * within one second of each other is already an unusual thing for a person to
 * do; sixteen is not something to keep trying past.
 */
const val SAFETY_BACKUP_NAME_ATTEMPTS: Int = 16

/**
 * The name of the backup taken just before a restore replaces everything.
 *
 * `pnp-oncesi-2026-09-09-143355.json`, and `-2`, `-3` and so on when a name is
 * already taken. Down to the second, unlike the manual backup's name: a manual
 * backup is something a person takes occasionally and is told about when it
 * would replace one, while these are made without being asked for and must never
 * quietly replace each other — the whole reason one exists is that it is
 * somebody's way back.
 *
 * Built by padding numbers rather than by formatting them, for the same reason
 * the manual name is: a formatter follows the machine's language, and the same
 * moment has to produce the same name in every locale. Nothing from the data is
 * in it — not a game, not a count, not the machine — because a file name travels
 * further than a file's contents do.
 *
 * @param attempt 1 for the plain name, 2 upwards for the ones that follow it.
 */
fun safetyBackupFileName(
    moment: LocalMoment,
    attempt: Int = 1,
): String {
    require(attempt >= 1) { "There is no attempt before the first" }
    val year = moment.year.toString().padStart(4, '0')
    val month = moment.month.toString().padStart(2, '0')
    val day = moment.dayOfMonth.toString().padStart(2, '0')
    val hour = moment.hour.toString().padStart(2, '0')
    val minute = moment.minute.toString().padStart(2, '0')
    val second = moment.second.toString().padStart(2, '0')
    val ordinal = if (attempt == 1) "" else "-$attempt"
    return "$SAFETY_BACKUP_PREFIX$year-$month-$day-$hour$minute$second$ordinal$BACKUP_EXTENSION"
}

/**
 * Writes the backup that is the user's way back, into the application's own
 * folder.
 *
 * Nothing is chosen here by the user: PLAN 14.4.4 puts the file under the
 * application's data directory rather than wherever the last manual backup went,
 * so it is somewhere the application can be sure of and somewhere a restore does
 * not depend on a disk still being plugged in. The name is the only thing about
 * it that reaches the screen.
 *
 * It is an interface because a directory is a platform's business and because
 * this is the one step of the restore whose failure must stop everything: the
 * flow above it can be driven, and made to fail, without a disk.
 */
interface SafetyBackupWriter {
    /**
     * Writes [bytes] whole, under a name nothing else has.
     *
     * @return the name it was written under, never a path.
     * @throws dev.pnptracker.domain.backup.BackupException if it could not be
     *   written. Nothing is left behind when this throws, and the restore must
     *   not go ahead.
     */
    suspend fun writeSafetyBackup(
        bytes: ByteArray,
        moment: LocalMoment,
    ): String
}
