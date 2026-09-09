package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.BackupData

/**
 * A backup file that has been through every check there is.
 *
 * There is no public way to make one. It exists only at the end of
 * [UntrustedBackupReader.read], which means holding one is itself the proof that
 * the bytes were within the limit, the text was UTF-8, the JSON had no repeated
 * key, the envelope named this format and a version this build knows, the
 * checksum described the data, every identifier was a canonical UUID, every enum
 * was a name that exists, every reference pointed at something in the same file,
 * and the whole of it loaded into a throwaway database of the current schema and
 * came back out unchanged.
 *
 * It carries [data] as the format's own records rather than as storage rows. The
 * crossing into storage happens once, where the rows are written, and a type
 * that had already crossed would tie the file format to the entities — which is
 * exactly what PLAN 14.4.1 separates by giving the format a version of its own.
 *
 * This slice offers nothing that puts one of these into the live database. That
 * is deliberate and it is the whole boundary of the slice: reading a backup and
 * replacing somebody's data are two different pieces of work, and only the first
 * one has been built.
 */
class ValidatedBackup internal constructor(
    /** What the file was called. A name, never a path. */
    val fileName: String,
    val formatVersion: Int,
    /** Which version of the application wrote it. Information only (PLAN 14.4.1). */
    val appVersion: String,
    val sourceSchemaVersion: Int,
    /** The moment it was taken, in the canonical UTC text the format writes. */
    val createdAt: String,
    val dataSha256: String,
    val data: BackupData,
) {
    /**
     * The little that can safely be shown about a backup before it is used.
     *
     * Counts and a date. Nothing out of the data itself — not a game's name, not
     * a note, not an identifier — because a screen that says "this backup holds
     * Harmonies" has taken data out of an unverified file and put it in front of
     * somebody, and a file that lies about its contents would be lying on our
     * screen in our words.
     */
    val summary: BackupSummary
        get() =
            BackupSummary(
                fileName = fileName,
                createdAt = createdAt,
                appVersion = appVersion,
                formatVersion = formatVersion,
                sourceSchemaVersion = sourceSchemaVersion,
                colorCount = data.colors.size,
                gameCount = data.games.size,
                taskCount = data.tasks.size,
                importCount = data.importBatches.size,
                progressEventCount = data.progressEvents.size,
                historyEventCount = data.historyEvents.size,
            )
}

/** What a user may be told about a backup file: how big it is, and when it was taken. */
data class BackupSummary(
    val fileName: String,
    val createdAt: String,
    val appVersion: String,
    val formatVersion: Int,
    val sourceSchemaVersion: Int,
    val colorCount: Int,
    val gameCount: Int,
    val taskCount: Int,
    val importCount: Int,
    val progressEventCount: Int,
    val historyEventCount: Int,
)

/** What reading a backup file came to. */
sealed interface BackupReadResult {
    /** The file is a backup, and this is what it holds. */
    data class Valid(
        val backup: ValidatedBackup,
    ) : BackupReadResult

    /** The file is not one that can be used, and this is why. */
    data class Refused(
        val rejection: BackupRejection,
    ) : BackupReadResult
}
