package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.BACKUP_FORMAT
import dev.pnptracker.domain.backup.BACKUP_FORMAT_VERSION
import dev.pnptracker.domain.backup.BackupEnvelopeV1
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import dev.pnptracker.domain.backup.sha256Of
import kotlin.time.Instant

/**
 * The database schema whose rows this build can read out of a backup.
 *
 * Kept beside the reader rather than taken from the database class, because the
 * two are different statements: the database says what it is now, and this says
 * what a file may claim to have come from. They are the same number today and a
 * test holds them together, which is the point at which somebody bumping the
 * schema is asked what an older backup should do.
 */
const val SUPPORTED_SOURCE_SCHEMA_VERSION: Int = 8

/** The largest moment a row may carry: the last millisecond of the year 9999. */
internal const val LATEST_TIMESTAMP: Long = 253_402_300_799_999L

internal val LOWER_CASE_SHA_256 = Regex("[0-9a-f]{64}")

/**
 * What the file says about itself, checked in the order the answers matter.
 *
 * Format first, because a file that is not one of ours has nothing else worth
 * asking about. Then the two versions, which are separate questions with
 * separate answers (PLAN 14.4.1): the format version and the schema version move
 * independently, and being told the wrong one of them would send somebody
 * looking for the wrong fix. Too new means update the application; too old means
 * there is no upgrade path, and PLAN 14.4.1 is explicit that the first version
 * invents none.
 *
 * `appVersion` is not a gate. PLAN 14.4.1 says it alone refuses nothing, so a
 * backup from any version — including one that has not been written yet, and
 * including one that recorded an empty string — passes here. Whether the shape
 * of it was a string at all was settled by [checkDocumentShape].
 */
internal fun checkEnvelope(envelope: BackupEnvelopeV1): BackupRejection? {
    if (envelope.format != BACKUP_FORMAT) return refusal(BackupProblem.WRONG_FORMAT, "format")

    if (envelope.formatVersion > BACKUP_FORMAT_VERSION) return refusal(BackupProblem.FORMAT_TOO_NEW, "formatVersion")
    if (envelope.formatVersion < BACKUP_FORMAT_VERSION) return refusal(BackupProblem.FORMAT_TOO_OLD, "formatVersion")

    if (envelope.sourceSchemaVersion > SUPPORTED_SOURCE_SCHEMA_VERSION) {
        return refusal(BackupProblem.SCHEMA_TOO_NEW, "sourceSchemaVersion")
    }
    if (envelope.sourceSchemaVersion < SUPPORTED_SOURCE_SCHEMA_VERSION) {
        return refusal(BackupProblem.SCHEMA_TOO_OLD, "sourceSchemaVersion")
    }

    if (!isCanonicalMoment(envelope.createdAt)) return refusal(BackupProblem.INVALID_CREATED_AT, "createdAt")
    if (!LOWER_CASE_SHA_256.matches(envelope.dataSha256)) return refusal(BackupProblem.MALFORMED_CHECKSUM, "dataSha256")
    return null
}

/**
 * The checksum, worked out again from the data the file actually carries.
 *
 * The same canonical writer the backup was made with, and the same digest — not
 * a second implementation of either. PLAN 14.4.1 has the checksum cover the
 * canonical bytes of `data`, so the way to check it is to write those bytes
 * again and hash them; anything else would be a second opinion able to disagree
 * with the writer, and the disagreement would look like a corrupt backup.
 *
 * Because the comparison goes through the parsed records, a file whose fields
 * were written in a different order but which says the same thing still matches:
 * the canonical writer puts them back in the format's order before hashing. What
 * cannot slip past is a changed value, however small, and that is what the
 * checksum is for.
 *
 * This runs before any database is opened. A file that fails here has cost
 * nothing but the reading of it.
 */
internal fun checkChecksum(envelope: BackupEnvelopeV1): BackupRejection? {
    val recomputed = sha256Of(canonicalBackupDataJson(envelope.data).encodeToByteArray())
    if (recomputed != envelope.dataSha256) return refusal(BackupProblem.CHECKSUM_MISMATCH, "dataSha256")
    return null
}

/**
 * Whether [text] is a moment written the way the format writes one.
 *
 * Parsing is not enough on its own: the same instant can be written many ways,
 * and a backup is required to be canonical (PLAN 14.4.1). So the text is parsed
 * and then written back out, and it has to be the same text. That accepts what
 * the writer produces — UTC, `Z`, no offset — and refuses the rest without
 * anybody having to enumerate the rest.
 */
private fun isCanonicalMoment(text: String): Boolean {
    val moment =
        try {
            Instant.parse(text)
        } catch (notAMoment: IllegalArgumentException) {
            return false
        }
    return moment.toString() == text
}

private fun refusal(
    problem: BackupProblem,
    field: String,
) = BackupRejection(problem, BackupPlace.Envelope.copy(field = field))
