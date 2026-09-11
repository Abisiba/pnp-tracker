package dev.pnptracker.domain.backup

import dev.pnptracker.domain.time.LocalMoment

/** How many characters a stamp is: `2026-09-09-143355`. */
const val BACKUP_STAMP_LENGTH: Int = 17

/** A stamp and nothing else, down to the second, with every field padded. */
internal val BACKUP_STAMP = Regex("""\d{4}-\d{2}-\d{2}-\d{6}""")

/**
 * The date and time an automatically named backup carries: `2026-09-09-143355`.
 *
 * One builder for every automatic name there is, because the stamp is also the
 * sort key (PLAN 14.4.11) and two spellings of the same moment would order
 * against each other rather than with each other.
 *
 * Built by padding numbers rather than by formatting them, for the reason
 * [suggestedBackupFileName] gives: a formatter follows the machine's language,
 * and the same moment has to produce the same name in every locale.
 *
 * Every field is fixed width and the fields run from the largest to the
 * smallest, so comparing two stamps as text puts them in the order they
 * happened. That is what lets rotation sort by name and ignore `mtime`, which
 * copying and syncing change and the name does not.
 */
fun backupStampOf(moment: LocalMoment): String {
    val year = moment.year.toString().padStart(4, '0')
    val month = moment.month.toString().padStart(2, '0')
    val day = moment.dayOfMonth.toString().padStart(2, '0')
    val hour = moment.hour.toString().padStart(2, '0')
    val minute = moment.minute.toString().padStart(2, '0')
    val second = moment.second.toString().padStart(2, '0')
    return "$year-$month-$day-$hour$minute$second"
}

/**
 * Whether [stamp] is a moment this application would really have written.
 *
 * Shape alone is not enough. A file called `pnp-otomatik-import-9999-99-99-999999.json`
 * matches the pattern and was written by nobody here, and rotation is about to
 * decide whether it may delete things: a name that could not have come out of
 * [backupStampOf] is one more reason to leave a file alone.
 *
 * The year is deliberately not bounded. Which years are plausible is not this
 * function's business and a clock set oddly is still the user's own clock.
 */
internal fun isPlausibleStamp(stamp: String): Boolean {
    if (!BACKUP_STAMP.matches(stamp)) return false
    val month = stamp.substring(5, 7).toInt()
    val day = stamp.substring(8, 10).toInt()
    val hour = stamp.substring(11, 13).toInt()
    val minute = stamp.substring(13, 15).toInt()
    val second = stamp.substring(15, 17).toInt()
    return month in 1..12 && day in 1..31 && hour in 0..23 && minute in 0..59 && second in 0..59
}
