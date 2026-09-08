package dev.pnptracker.domain.backup

import kotlinx.serialization.Serializable

/*
 * One record type per table of the database, as the backup writes it.
 *
 * These are deliberately not the Room entities. PLAN 14.4.1 gives the backup a
 * `formatVersion` of its own, independent of the schema version, and a format
 * whose fields were an entity's fields would move whenever the entity did — a
 * column renamed for the database's convenience would silently be a different
 * file format, and a file written last year would stop being readable without
 * anyone deciding that it should. So the crossing between the two is written out
 * once, by hand, in `BackupStore`, and every field below is a decision.
 *
 * The shapes carry no domain types. An identifier is the canonical UUID text an
 * `EntityId` prints, an enum is the name it is stored under, and a moment is the
 * epoch milliseconds the column holds — the same values, in the same precision,
 * that SQLite has. Nothing is normalised, rounded, trimmed or recomputed on the
 * way out (PLAN 14.4.2): a backup that improved the data would be a backup of
 * data the user never had.
 *
 * Every field is written, including nulls and values that happen to equal a
 * default, because a reader cannot tell an absent field from a null one and PLAN
 * 14.4.1 will not have it guess.
 */

/** A colour of the catalogue, base or custom alike (PLAN 5.7). */
@Serializable
data class BackupColorRow(
    val id: String,
    val canonicalName: String,
    val normalizedName: String,
    val hex: String,
    val sortOrder: Int,
)

/** A spelling an import knows a colour by (PLAN 5.11). */
@Serializable
data class BackupColorAliasRow(
    val colorId: String,
    val alias: String,
    val normalizedAlias: String,
)

/** One import, whatever became of it (PLAN 11.2). */
@Serializable
data class BackupImportBatchRow(
    val id: String,
    val fileName: String,
    val sha256: String,
    val sourceFormat: String,
    val sheetName: String,
    val startRowIndex: Int?,
    val endRowIndex: Int?,
    val startColumnIndex: Int?,
    val endColumnIndex: Int?,
    val createdGameCount: Int,
    val rawBlockCount: Int,
    val createdTaskCount: Int,
    val status: String,
    val importedAt: Long,
    val updatedAt: Long,
)

/** A row of the game table, deleted ones included (PLAN 5.2, 5.3). */
@Serializable
data class BackupGameRow(
    val id: String,
    val name: String,
    val isManuallyCompleted: Boolean,
    val completedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val sourceImportBatchId: String?,
)

/** One column of one game (PLAN 5.4). */
@Serializable
data class BackupGameCellRow(
    val id: String,
    val gameId: String,
    val columnType: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A cell of the source file, with the hints read from it (PLAN 11.3). */
@Serializable
data class BackupRawImportBlockRow(
    val id: String,
    val importBatchId: String,
    val rawText: String,
    val sheetName: String,
    val rowIndex: Int,
    val columnIndex: Int,
    val sourceColumnType: String,
    val fillColorArgb: Int?,
    val gameCompletionHint: String,
    val completionTargetGameId: String?,
    val isProcessed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A piece of production work, deleted ones included (PLAN 5.6). */
@Serializable
data class BackupTaskRow(
    val id: String,
    val poolType: String,
    val trackingMode: String,
    val name: String,
    val requiredQuantity: Int?,
    val notes: String?,
    val isCompleted: Boolean,
    val completedAt: Long?,
    val primaryBatchCompleted: Boolean,
    val currentMissingQuantity: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val sourceRawImportBlockId: String?,
    val isMissing: Boolean,
    val isBorrowed: Boolean,
    val needsInfo: Boolean,
    val needsClassification: Boolean,
)

/**
 * One piece of a cell's document (PLAN 5.5).
 *
 * [text] being null and being empty are different things and both are written as
 * they are: a task piece carries no text at all, while plain text is never empty
 * — and a reader that turned one into the other would change what the cell says.
 */
@Serializable
data class BackupCellSegmentRow(
    val id: String,
    val cellId: String,
    val orderIndex: Int,
    val kind: String,
    val text: String?,
    val taskId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A colour a task needs, in the slot the user gave it (PLAN 5.10). */
@Serializable
data class BackupTaskColorRow(
    val taskId: String,
    val colorId: String,
    val slotIndex: Int,
)

/** How far one step of a pipeline has got (PLAN 5.12, 7.2). */
@Serializable
data class BackupTaskStageRow(
    val taskId: String,
    val stage: String,
    val orderIndex: Int,
    val completedQuantity: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A shortage reported or made good; never rewritten, never deleted (PLAN 5.12). */
@Serializable
data class BackupProgressEventRow(
    val id: String,
    val taskId: String,
    val kind: String,
    val quantity: Int,
    val note: String?,
    val cardReference: String?,
    val stage: String?,
    val recordedAt: Long,
)

/** Something that happened, as the history screen reads it (PLAN 12.15). */
@Serializable
data class BackupHistoryEventRow(
    val id: String,
    val kind: String,
    val occurredAt: Long,
    val gameId: String,
    val taskId: String?,
    val stage: String?,
    val previousQuantity: Int?,
    val newQuantity: Int?,
)

/**
 * What one cell said before an import wrote into it (PLAN 11.4.4).
 *
 * [documentBefore] is kept to the character, empty string included: taking an
 * import back compares against it exactly, so a backup that tidied its
 * whitespace would make the import unrollbackable after a restore.
 */
@Serializable
data class BackupImportBatchCellRow(
    val importBatchId: String,
    val cellId: String,
    val documentBefore: String,
)

/** A task an import proposed, kept as the audit trail after confirmation (PLAN 11.4.3). */
@Serializable
data class BackupDraftTaskRow(
    val id: String,
    val rawImportBlockId: String,
    val name: String,
    val suggestedPoolType: String?,
    val selectedPoolType: String?,
    val selectedTrackingMode: String?,
    val requiredQuantity: Int?,
    val notes: String?,
    val targetCellId: String?,
    val selectionStartIndex: Int?,
    val selectionEndIndex: Int?,
    val completionHint: String,
    val isMissing: Boolean,
    val isBorrowed: Boolean,
    val needsInfo: Boolean,
    val needsClassification: Boolean,
    val materializedTaskId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A colour a draft task asks for, in its slot. */
@Serializable
data class BackupDraftTaskColorRow(
    val draftTaskId: String,
    val colorId: String,
    val slotIndex: Int,
)
