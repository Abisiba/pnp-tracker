package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupEnvelopeV1
import dev.pnptracker.domain.backup.backupJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * Tries the file out on a throwaway database of the current schema.
 *
 * The last check and the only one that needs storage. Everything before it is
 * arithmetic over the file; this is the file being asked to become rows in the
 * real schema, with the real foreign keys and the real unique indexes, in a
 * database that is created for the question and deleted after it.
 *
 * It is an interface for a reason that is not testability. Creating a database
 * is a platform's business, and the shared reader must not be able to name a
 * file — including the user's own. What implements this on the desktop makes its
 * own temporary directory and takes no path from anybody.
 */
fun interface BackupProbe {
    /**
     * @return the reason to refuse the backup, or null if it went in and came
     *   back out saying the same thing.
     */
    suspend fun probe(
        data: BackupData,
        dataSha256: String,
    ): BackupRejection?
}

/**
 * Reads a backup file without believing a word of it.
 *
 * The file is the one input this application takes that nobody checked: it may
 * have been edited, truncated, written by a newer version, or built on purpose
 * to see what this does. So the reading is a sequence of gates, each of which
 * runs only if every gate before it passed, and each of which is cheaper than
 * the one after it:
 *
 * ```text
 * size            → 64 MiB, checked before the file is opened and again as it is read
 * bytes           → strictly UTF-8, or nothing
 * text            → no repeated key, no runaway nesting, nothing after the document
 * JSON            → parsed by the one configuration the format is written with
 * shape           → every field the format declares, nothing it does not, of the right kind
 * envelope        → our format, our version, a real moment, a checksum shaped like one
 * checksum        → the data rewritten canonically and hashed again
 * values          → identifiers, enum names, ranges and the rules each row lives under
 * graph           → distinct keys, whole references, no holes in anything ordered
 * temporary store → the whole of it loaded into a database of the current schema
 * ```
 *
 * The order is the point. The expensive checks are behind the cheap ones, so a
 * file that is not a backup at all costs a read and a parse; and the database is
 * behind all of them, so a wrong checksum or a broken reference never causes so
 * much as a temporary file to be created.
 *
 * Nothing here can reach the user's data. There is no live database on this
 * path, no writer, and no way to ask for one: a [ValidatedBackup] is the end of
 * this slice's work, and what to do with one belongs to a later one.
 */
class UntrustedBackupReader(
    private val probe: BackupProbe,
    private val maximumBytes: Long = MAXIMUM_BACKUP_BYTES,
    private val maximumDepth: Int = MAXIMUM_JSON_DEPTH,
) {
    suspend fun read(input: BackupInput): BackupReadResult {
        val bytes =
            when (val read = readWithinLimit(input, maximumBytes)) {
                is Checked.Failed -> return BackupReadResult.Refused(read.rejection)
                is Checked.Passed -> read.value
            }

        val text =
            when (val decoded = decodeUtf8(bytes)) {
                is Checked.Failed -> return BackupReadResult.Refused(decoded.rejection)
                is Checked.Passed -> decoded.value
            }

        scanJsonText(text, maximumDepth)?.let { return BackupReadResult.Refused(it) }

        val document =
            parse(text) ?: return BackupReadResult.Refused(BackupRejection(BackupProblem.MALFORMED_JSON))

        checkDocumentShape(document)?.let { return BackupReadResult.Refused(it) }

        val envelope =
            decode(document)
                ?: return BackupReadResult.Refused(BackupRejection(BackupProblem.WRONG_TYPE, BackupPlace.Envelope))

        checkEnvelope(envelope)?.let { return BackupReadResult.Refused(it) }
        checkChecksum(envelope)?.let { return BackupReadResult.Refused(it) }
        checkValues(envelope.data)?.let { return BackupReadResult.Refused(it) }
        checkGraph(envelope.data)?.let { return BackupReadResult.Refused(it) }
        probe.probe(envelope.data, envelope.dataSha256)?.let { return BackupReadResult.Refused(it) }

        return BackupReadResult.Valid(
            ValidatedBackup(
                fileName = input.fileName,
                formatVersion = envelope.formatVersion,
                appVersion = envelope.appVersion,
                sourceSchemaVersion = envelope.sourceSchemaVersion,
                createdAt = envelope.createdAt,
                dataSha256 = envelope.dataSha256,
                data = envelope.data,
            ),
        )
    }

    /**
     * The document as JSON values, using the format's own configuration.
     *
     * The same [backupJson] the writer uses, so what is accepted here is exactly
     * what is written there: no comments, no trailing commas, nothing lenient.
     */
    private fun parse(text: String): JsonElement? =
        try {
            backupJson.parseToJsonElement(text)
        } catch (notJson: SerializationException) {
            null
        }

    /**
     * The document as records.
     *
     * [checkDocumentShape] has already said the shape is right, so this should
     * not be able to fail. It is guarded anyway, and the guard catches only
     * `SerializationException` — the library's way of saying a document does not
     * fit a format, which is a statement about the file. An
     * `IllegalStateException` or a `NullPointerException` from in here would be a
     * statement about this code and is left to travel as one (PLAN 14.4.5).
     */
    private fun decode(document: JsonElement): BackupEnvelopeV1? =
        try {
            backupJson.decodeFromJsonElement(serializer<BackupEnvelopeV1>(), document)
        } catch (doesNotFit: SerializationException) {
            null
        }
}
