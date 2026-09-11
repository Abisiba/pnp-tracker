package dev.pnptracker.domain.backup.retention

import dev.pnptracker.domain.backup.BACKUP_EXTENSION
import dev.pnptracker.domain.backup.backupStampOf
import dev.pnptracker.domain.backup.isPlausibleStamp
import dev.pnptracker.domain.backup.restore.SAFETY_BACKUP_PREFIX
import dev.pnptracker.domain.time.LocalMoment

/** What an automatic backup is called before the date. */
const val IMPORT_SNAPSHOT_PREFIX: String = "pnp-otomatik-import-"

/** What a migration snapshot set is called before the two schema versions. */
const val MIGRATION_SNAPSHOT_PREFIX: String = "pnp-otomatik-migration-"

/** The extension the raw half of a migration set is written under. */
const val MIGRATION_DATABASE_EXTENSION: String = ".db"

/**
 * The three kinds of backup this application takes without being asked.
 *
 * They are separate because their quotas are separate (PLAN 14.4.11): each keeps
 * its own newest few, so a run of imports can never evict the file somebody
 * needs to undo a restore. A manual backup is not here at all — it is the user's
 * file, in the user's folder, and nothing automatic may delete it.
 */
enum class AutomaticBackupKind {
    /** Taken before an import is confirmed (PLAN 14.4.8). */
    IMPORT,

    /** Taken before a restore replaces everything (PLAN 14.4.4). */
    SAFETY,

    /** Taken before a migration, as a matched pair of files (PLAN 14.4.9). */
    MIGRATION,
}

/**
 * One automatic backup file, read out of its own name.
 *
 * Produced only by [automaticBackupNameOf], which accepts nothing but a name
 * this application would really have written. Everything rotation needs to order
 * and group files is here, and none of it comes from the file system: not the
 * modification time, not the size, not the order the directory happens to list
 * things in.
 */
data class AutomaticBackupName(
    val fileName: String,
    val kind: AutomaticBackupKind,
    /**
     * The name without its extension, which is what a set is known by.
     *
     * For an import or a safety backup this names one file. For a migration it
     * names two — the raw copy and the document — and it is how the pair is put
     * back together after being read out of a directory listing.
     */
    val setName: String,
    /** `2026-09-09-143355`; the first half of the sort key. */
    val stamp: String,
    /** 1 for the plain name, 2 upwards for the ones that followed it. */
    val attempt: Int,
    /** `.json` for every document, `.db` for the raw half of a migration set. */
    val extension: String,
    /** The schema the database was on before the migration; migration only. */
    val fromSchemaVersion: Int? = null,
    /** The schema it was going to; migration only. */
    val toSchemaVersion: Int? = null,
)

/** `pnp-otomatik-import-2026-09-09-143355.json`, and `-2`, `-3` after it. */
fun importSnapshotFileName(
    moment: LocalMoment,
    attempt: Int = 1,
): String = "$IMPORT_SNAPSHOT_PREFIX${stampWithAttempt(moment, attempt)}$BACKUP_EXTENSION"

/**
 * What both halves of one migration set are called, without the extension.
 *
 * The two files are one thing and are named as one thing: the same versions, the
 * same stamp and — when a second set lands in the same second — **the same
 * ordinal**. PLAN 14.4.9 keeps them together because each answers a question the
 * other cannot, and a pair that could not be recognised as a pair would be two
 * orphans the next rotation refuses to touch.
 */
fun migrationSnapshotSetName(
    fromSchemaVersion: Int,
    toSchemaVersion: Int,
    moment: LocalMoment,
    attempt: Int = 1,
): String {
    require(fromSchemaVersion >= 1) { "A schema version starts at one" }
    require(toSchemaVersion >= 1) { "A schema version starts at one" }
    return "${MIGRATION_SNAPSHOT_PREFIX}v$fromSchemaVersion-v$toSchemaVersion-${stampWithAttempt(moment, attempt)}"
}

private fun stampWithAttempt(
    moment: LocalMoment,
    attempt: Int,
): String {
    require(attempt >= 1) { "There is no attempt before the first" }
    val ordinal = if (attempt == 1) "" else "-$attempt"
    return "${backupStampOf(moment)}$ordinal"
}

/**
 * The ordinal a second name of the same second carries.
 *
 * `2` upwards, counted rather than padded, so `-10` is a name and `-1` and `-02`
 * are not: the first attempt has no ordinal at all and nothing here ever writes
 * a leading zero. Two alternatives rather than one, because a single `[2-9]\d*`
 * would refuse the tenth backup of a second — which is a name this application
 * really does write.
 */
private const val ORDINAL = """(?:-([2-9]|[1-9]\d+))?"""

private val IMPORT_NAME = Regex("""^$IMPORT_SNAPSHOT_PREFIX(\d{4}-\d{2}-\d{2}-\d{6})$ORDINAL\.json$""")

private val SAFETY_NAME = Regex("""^$SAFETY_BACKUP_PREFIX(\d{4}-\d{2}-\d{2}-\d{6})$ORDINAL\.json$""")

private val MIGRATION_NAME =
    Regex(
        """^${MIGRATION_SNAPSHOT_PREFIX}v([1-9]\d*)-v([1-9]\d*)-""" +
            """(\d{4}-\d{2}-\d{2}-\d{6})$ORDINAL\.(json|db)$""",
    )

/**
 * Reads a file name, and answers only for names this application writes.
 *
 * The first of the two things that stand between rotation and somebody's data,
 * and it is deliberately unforgiving. Not a prefix test: the whole name has to
 * be one [importSnapshotFileName], [migrationSnapshotSetName] or
 * `safetyBackupFileName` would really have produced, down to the padding of the
 * stamp and the absence of a `-1` that the first attempt never carries. PLAN
 * 14.4.11 says a prefix alone does not earn the right to delete a file, and this
 * is the half of that rule which costs no reading.
 *
 * Everything else — a manual backup, a half-written `.part`, a note the user
 * left in the folder, a name that is nearly right — comes back null and is
 * thereby out of rotation's reach for good.
 *
 * @return what the name says, or null when this application did not write it.
 */
fun automaticBackupNameOf(fileName: String): AutomaticBackupName? {
    IMPORT_NAME.matchEntire(fileName)?.let { match ->
        val (stamp, ordinal) = match.destructured
        return plainName(fileName, AutomaticBackupKind.IMPORT, stamp, ordinal, BACKUP_EXTENSION)
    }
    SAFETY_NAME.matchEntire(fileName)?.let { match ->
        val (stamp, ordinal) = match.destructured
        return plainName(fileName, AutomaticBackupKind.SAFETY, stamp, ordinal, BACKUP_EXTENSION)
    }
    MIGRATION_NAME.matchEntire(fileName)?.let { match ->
        val (from, to, stamp, ordinal, extension) = match.destructured
        if (!isPlausibleStamp(stamp)) return null
        return AutomaticBackupName(
            fileName = fileName,
            kind = AutomaticBackupKind.MIGRATION,
            setName = fileName.removeSuffix(".$extension"),
            stamp = stamp,
            attempt = ordinal.toIntOrNull() ?: 1,
            extension = ".$extension",
            fromSchemaVersion = from.toIntOrNull() ?: return null,
            toSchemaVersion = to.toIntOrNull() ?: return null,
        )
    }
    return null
}

private fun plainName(
    fileName: String,
    kind: AutomaticBackupKind,
    stamp: String,
    ordinal: String,
    extension: String,
): AutomaticBackupName? {
    if (!isPlausibleStamp(stamp)) return null
    return AutomaticBackupName(
        fileName = fileName,
        kind = kind,
        setName = fileName.removeSuffix(extension),
        stamp = stamp,
        attempt = ordinal.toIntOrNull() ?: 1,
        extension = extension,
    )
}
