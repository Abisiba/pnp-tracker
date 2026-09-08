package dev.pnptracker.domain.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** The string a backup names itself by, so a file that is not one is known at once. */
const val BACKUP_FORMAT: String = "pnp-tracker-backup"

/** The version of the backup format itself, which is not the database's (PLAN 14.4.1). */
const val BACKUP_FORMAT_VERSION: Int = 1

/**
 * Everything the database holds, table by table.
 *
 * The order of the fields is the order PLAN 14.4.2 restores in, and it is the
 * order they are written in: a reader going through the file top to bottom meets
 * every parent before the rows that point at it. Each list arrives already
 * sorted, by the key PLAN 14.4.2 names for its table.
 */
@Serializable
data class BackupData(
    val colors: List<BackupColorRow>,
    val colorAliases: List<BackupColorAliasRow>,
    val importBatches: List<BackupImportBatchRow>,
    val games: List<BackupGameRow>,
    val gameCells: List<BackupGameCellRow>,
    val rawImportBlocks: List<BackupRawImportBlockRow>,
    val tasks: List<BackupTaskRow>,
    val cellSegments: List<BackupCellSegmentRow>,
    val taskColors: List<BackupTaskColorRow>,
    val taskStages: List<BackupTaskStageRow>,
    val progressEvents: List<BackupProgressEventRow>,
    val historyEvents: List<BackupHistoryEventRow>,
    val importBatchCells: List<BackupImportBatchCellRow>,
    val draftTasks: List<BackupDraftTaskRow>,
    val draftTaskColors: List<BackupDraftTaskColorRow>,
)

/**
 * What a backup file says about itself, and then what it holds.
 *
 * The six fields before [data] are the ones a reader needs in order to decide
 * whether to go on, so they are written first (PLAN 14.4.1). None of them is
 * part of [dataSha256]: the checksum covers the data alone, so two backups of an
 * unchanged database have the same checksum however far apart they were taken.
 */
@Serializable
data class BackupEnvelopeV1(
    val format: String,
    val formatVersion: Int,
    val appVersion: String,
    val sourceSchemaVersion: Int,
    val createdAt: String,
    val dataSha256: String,
    val data: BackupData,
)

/**
 * A finished backup, held in memory.
 *
 * [json] is the whole file as it would be written; this slice does not write it
 * anywhere. [envelope] is the same document as values, so a caller can read what
 * was recorded without parsing what it just produced.
 */
data class BackupDocument(
    val envelope: BackupEnvelopeV1,
    val json: String,
)

/**
 * The one JSON configuration this application uses for backups.
 *
 * Every setting here is part of the format rather than a preference:
 *
 * - not pretty printed, because the checksum covers the very bytes the file
 *   embeds and only compact output writes a nested object exactly as it writes a
 *   standalone one. Indentation would make the two differ, and then either the
 *   checksum or the file would be a lie;
 * - defaults encoded and nulls explicit, so every field of every row is
 *   physically present and a reader never has to invent one;
 * - unknown keys refused, which is PLAN 14.4.1's policy stated where it takes
 *   effect. Reading an untrusted file is a later slice's work; the policy is
 *   declared here so that work inherits it rather than choosing again;
 * - nothing lenient, no comments, no trailing commas: what this writes is a JSON
 *   document and what it will accept is a JSON document.
 *
 * Field order comes from the declaration order of the serializable classes, and
 * row order from the lists, so no part of the format depends on how a map
 * happens to iterate.
 */
val backupJson: Json =
    Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        allowComments = false
        allowTrailingComma = false
        allowStructuredMapKeys = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
    }

/**
 * The canonical text of [data]: the bytes that are hashed, and the bytes the
 * envelope embeds.
 *
 * One function, used for both, because PLAN 14.4.1 has the checksum cover the
 * data the file actually carries. A second rendering of the same data — however
 * carefully written — would eventually differ from this one, and the difference
 * would show up as a corrupt backup rather than as the bug it was.
 */
fun canonicalBackupDataJson(data: BackupData): String = backupJson.encodeToString(data)

/**
 * Assembles the document: hashes the data, then writes it inside the envelope.
 *
 * [createdAt] is taken once by the caller rather than read here, so a document
 * describes one moment and a test can decide what that moment is.
 */
fun backupDocumentOf(
    data: BackupData,
    appVersion: String,
    sourceSchemaVersion: Int,
    createdAt: Instant,
): BackupDocument {
    val envelope =
        BackupEnvelopeV1(
            format = BACKUP_FORMAT,
            formatVersion = BACKUP_FORMAT_VERSION,
            appVersion = appVersion,
            sourceSchemaVersion = sourceSchemaVersion,
            // Instant prints ISO-8601 in UTC, which is the same text on every
            // machine in every locale; PLAN 14.4.1 wants a moment a person can
            // read, and this is the only place in the format where one appears.
            createdAt = createdAt.toString(),
            dataSha256 = sha256Of(canonicalBackupDataJson(data).encodeToByteArray()),
            data = data,
        )
    return BackupDocument(envelope = envelope, json = backupJson.encodeToString(envelope))
}
