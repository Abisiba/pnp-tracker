package dev.pnptracker.domain.backup.restore

/**
 * Why a backup file was refused.
 *
 * A backup file is the one piece of input this application takes that nobody
 * checked before it arrived: it may have been written by an older version, by a
 * newer one, by a text editor halfway through a repair, or by something that is
 * not this application at all. So every way it can be wrong gets a name here,
 * and the pipeline that reads it never has to fall back on "it did not work".
 *
 * The names are about the file, never about the user's data. Nothing in this
 * type or in [BackupRejection] carries a path, a value out of the file, an
 * identifier, a piece of SQL or the text of an exception (PLAN 14.4.5); a
 * failure travels as a reason and a place in the format, and the Turkish
 * sentence a person reads is chosen from that.
 *
 * Several of these will read the same to a user — a missing field and a field of
 * the wrong type are both "this file is not a backup this application can read".
 * They are kept apart anyway, because they say different things to whoever is
 * looking at why a particular file was refused, and joining them later is
 * possible while splitting them later is not.
 */
enum class BackupProblem {
    /** The file could not be found, opened or read to the end. */
    UNREADABLE,

    /** There was nothing in it. Not a JSON document, and worth saying so separately. */
    EMPTY_FILE,

    /** Bigger than a backup is allowed to be; refused before any of it was parsed. */
    SAFETY_LIMIT,

    /** The bytes are not UTF-8. */
    INVALID_UTF8,

    /** The text is not JSON. */
    MALFORMED_JSON,

    /** The JSON nests deeper than anything this format can mean. */
    TOO_DEEPLY_NESTED,

    /** One object names the same key twice, and there is no telling which was meant. */
    DUPLICATE_KEY,

    /** A field this format does not know. Ignoring it would lose it (PLAN 14.4.1). */
    UNKNOWN_FIELD,

    /** A field the format requires is not there. Absent and null are not the same. */
    MISSING_FIELD,

    /** A field is there but holds the wrong kind of value. */
    WRONG_TYPE,

    /** The document does not say it is one of ours. */
    WRONG_FORMAT,

    /** Written by a later version of the format than this one knows (PLAN 14.4.1). */
    FORMAT_TOO_NEW,

    /** A format version older than the first, which no file can honestly claim. */
    FORMAT_TOO_OLD,

    /** The rows came out of a later database schema than this version has. */
    SCHEMA_TOO_NEW,

    /** The rows came out of an older schema, and there is no upgrade path yet. */
    SCHEMA_TOO_OLD,

    /** The moment the backup was taken is not a moment. */
    INVALID_CREATED_AT,

    /** The checksum field is not sixty-four lower case hex characters. */
    MALFORMED_CHECKSUM,

    /** The checksum does not describe the data the file carries. */
    CHECKSUM_MISMATCH,

    /** An identifier is not a canonical UUID. */
    INVALID_ID,

    /** A value that has to be one of a fixed set of names is not one of them. */
    INVALID_ENUM,

    /** A value is outside what its column allows: a negative count, an impossible moment. */
    INVALID_VALUE,

    /** Two rows claim the same key, or the same place in something that holds one row. */
    DUPLICATE_RECORD,

    /** A row points at something the file does not contain. */
    BROKEN_REFERENCE,

    /** The rows are each valid and together say something the application cannot mean. */
    DOMAIN_INVARIANT,

    /** Loading it into a throwaway database of the current schema did not work out. */
    TEMP_VALIDATION_FAILED,

    /**
     * Every row stands up, but an import's own records contradict each other
     * (PLAN 14.7.5 decision 2): decided after the reader, only for the file a
     * person chose to restore — never by the reader, which also verifies
     * import snapshots and migration sets.
     */
    IMPORT_RECORDS_CONTRADICT,
}

/**
 * Where in the format something went wrong.
 *
 * Both fields come from a fixed vocabulary the format itself defines — the name
 * of one of the fifteen arrays, or `envelope`, and the name of a field in the
 * record type. Neither is ever a value read out of the file, so a rejection can
 * be shown, logged or counted without carrying anybody's data with it.
 */
data class BackupPlace(
    /** The array of `data` this happened in, or `envelope`, or `file`. */
    val part: String,
    /** The field of the record, when the trouble was with one field. */
    val field: String? = null,
) {
    companion object {
        val File = BackupPlace("file")
        val Envelope = BackupPlace("envelope")
    }
}

/**
 * A refusal: what was wrong, and where.
 *
 * There is no cause and no message. A `Throwable` would carry a stack trace and,
 * through it, file names and library internals into whatever showed this; the
 * whole point of the type is that there is nothing in it that must not be shown.
 */
data class BackupRejection(
    val problem: BackupProblem,
    val place: BackupPlace = BackupPlace.File,
)
