package dev.pnptracker.data.repository

import androidx.room3.useReaderConnection
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.dao.DatabaseRows
import dev.pnptracker.domain.backup.BackupCellSegmentRow
import dev.pnptracker.domain.backup.BackupColorAliasRow
import dev.pnptracker.domain.backup.BackupColorRow
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupDraftTaskColorRow
import dev.pnptracker.domain.backup.BackupDraftTaskRow
import dev.pnptracker.domain.backup.BackupGameCellRow
import dev.pnptracker.domain.backup.BackupGameRow
import dev.pnptracker.domain.backup.BackupHistoryEventRow
import dev.pnptracker.domain.backup.BackupImportBatchCellRow
import dev.pnptracker.domain.backup.BackupImportBatchRow
import dev.pnptracker.domain.backup.BackupProgressEventRow
import dev.pnptracker.domain.backup.BackupRawImportBlockRow
import dev.pnptracker.domain.backup.BackupSnapshot
import dev.pnptracker.domain.backup.BackupSource
import dev.pnptracker.domain.backup.BackupTaskColorRow
import dev.pnptracker.domain.backup.BackupTaskRow
import dev.pnptracker.domain.backup.BackupTaskStageRow

/**
 * Reads the whole database and says what it found in the backup's own terms.
 *
 * This is the one crossing between the storage shape and the file shape (PLAN
 * 14.4.1), and it is written out by hand on purpose. Generating it from the
 * entities would tie the format to them, and then a column renamed for the
 * database's convenience would change the file format without anybody deciding
 * that it should.
 *
 * Nothing here judges, normalises or recomputes. An identifier becomes its
 * canonical text, an enum becomes the name it is stored under and a moment
 * becomes the epoch milliseconds the column holds — the same values, the same
 * precision. Everything else travels character for character (PLAN 14.4.2).
 */
class BackupStore(
    private val database: AppDatabase,
) : BackupSource {
    override suspend fun snapshot(): BackupSnapshot =
        BackupSnapshot(
            // Which schema wrote these rows is asked of the database itself
            // rather than of a constant in the source, so a backup records the
            // version it was really taken from. It can only change when a
            // migration runs, which happens while the database is being opened
            // and never while it is being read, so reading it beside the
            // transaction rather than inside one costs nothing.
            sourceSchemaVersion = schemaVersion(),
            data = backupDataOf(database.backupDao().snapshot()),
        )

    private suspend fun schemaVersion(): Int =
        database.useReaderConnection { transactor ->
            transactor.usePrepared("PRAGMA user_version") { statement ->
                check(statement.step()) { "PRAGMA user_version returned no row" }
                statement.getInt(0)
            }
        }
}

/**
 * The rows one transaction returned, as the document writes them.
 *
 * Order is preserved exactly: the reads already sorted by the key PLAN 14.4.2
 * names, and sorting again here would be a second opinion able to disagree with
 * the first.
 */
internal fun backupDataOf(rows: DatabaseRows): BackupData =
    BackupData(
        colors =
            rows.colors.map {
                BackupColorRow(
                    id = it.id.toString(),
                    canonicalName = it.canonicalName,
                    normalizedName = it.normalizedName,
                    hex = it.hex,
                    sortOrder = it.sortOrder,
                )
            },
        colorAliases =
            rows.colorAliases.map {
                BackupColorAliasRow(
                    colorId = it.colorId.toString(),
                    alias = it.alias,
                    normalizedAlias = it.normalizedAlias,
                )
            },
        importBatches =
            rows.importBatches.map {
                BackupImportBatchRow(
                    id = it.id.toString(),
                    fileName = it.fileName,
                    sha256 = it.sha256,
                    sourceFormat = it.sourceFormat.name,
                    sheetName = it.sheetName,
                    startRowIndex = it.startRowIndex,
                    endRowIndex = it.endRowIndex,
                    startColumnIndex = it.startColumnIndex,
                    endColumnIndex = it.endColumnIndex,
                    createdGameCount = it.createdGameCount,
                    rawBlockCount = it.rawBlockCount,
                    createdTaskCount = it.createdTaskCount,
                    status = it.status.name,
                    importedAt = it.importedAt.toEpochMilliseconds(),
                    updatedAt = it.updatedAt.toEpochMilliseconds(),
                )
            },
        games =
            rows.games.map {
                BackupGameRow(
                    id = it.id.toString(),
                    name = it.name,
                    isManuallyCompleted = it.isManuallyCompleted,
                    completedAt = it.completedAt?.toEpochMilliseconds(),
                    createdAt = it.createdAt.toEpochMilliseconds(),
                    updatedAt = it.updatedAt.toEpochMilliseconds(),
                    deletedAt = it.deletedAt?.toEpochMilliseconds(),
                    sourceImportBatchId = it.sourceImportBatchId?.toString(),
                )
            },
        gameCells =
            rows.gameCells.map {
                BackupGameCellRow(
                    id = it.id.toString(),
                    gameId = it.gameId.toString(),
                    columnType = it.columnType.name,
                    createdAt = it.createdAt.toEpochMilliseconds(),
                    updatedAt = it.updatedAt.toEpochMilliseconds(),
                )
            },
        rawImportBlocks =
            rows.rawImportBlocks.map {
                BackupRawImportBlockRow(
                    id = it.id.toString(),
                    importBatchId = it.importBatchId.toString(),
                    rawText = it.rawText,
                    sheetName = it.sheetName,
                    rowIndex = it.rowIndex,
                    columnIndex = it.columnIndex,
                    sourceColumnType = it.sourceColumnType.name,
                    fillColorArgb = it.fillColorArgb,
                    gameCompletionHint = it.gameCompletionHint.name,
                    completionTargetGameId = it.completionTargetGameId?.toString(),
                    isProcessed = it.isProcessed,
                    createdAt = it.createdAt.toEpochMilliseconds(),
                    updatedAt = it.updatedAt.toEpochMilliseconds(),
                )
            },
        tasks =
            rows.tasks.map {
                BackupTaskRow(
                    id = it.id.toString(),
                    poolType = it.poolType.name,
                    trackingMode = it.trackingMode.name,
                    name = it.name,
                    requiredQuantity = it.requiredQuantity,
                    notes = it.notes,
                    isCompleted = it.isCompleted,
                    completedAt = it.completedAt?.toEpochMilliseconds(),
                    primaryBatchCompleted = it.primaryBatchCompleted,
                    currentMissingQuantity = it.currentMissingQuantity,
                    createdAt = it.createdAt.toEpochMilliseconds(),
                    updatedAt = it.updatedAt.toEpochMilliseconds(),
                    deletedAt = it.deletedAt?.toEpochMilliseconds(),
                    sourceRawImportBlockId = it.sourceRawImportBlockId?.toString(),
                    isMissing = it.isMissing,
                    isBorrowed = it.isBorrowed,
                    needsInfo = it.needsInfo,
                    needsClassification = it.needsClassification,
                )
            },
        cellSegments =
            rows.cellSegments.map {
                BackupCellSegmentRow(
                    id = it.id.toString(),
                    cellId = it.cellId.toString(),
                    orderIndex = it.orderIndex,
                    kind = it.kind.name,
                    text = it.text,
                    taskId = it.taskId?.toString(),
                    createdAt = it.createdAt.toEpochMilliseconds(),
                    updatedAt = it.updatedAt.toEpochMilliseconds(),
                )
            },
        taskColors =
            rows.taskColors.map {
                BackupTaskColorRow(
                    taskId = it.taskId.toString(),
                    colorId = it.colorId.toString(),
                    slotIndex = it.slotIndex,
                )
            },
        taskStages =
            rows.taskStages.map {
                BackupTaskStageRow(
                    taskId = it.taskId.toString(),
                    stage = it.stage.name,
                    orderIndex = it.orderIndex,
                    completedQuantity = it.completedQuantity,
                    createdAt = it.createdAt.toEpochMilliseconds(),
                    updatedAt = it.updatedAt.toEpochMilliseconds(),
                )
            },
        progressEvents =
            rows.progressEvents.map {
                BackupProgressEventRow(
                    id = it.id.toString(),
                    taskId = it.taskId.toString(),
                    kind = it.kind.name,
                    quantity = it.quantity,
                    note = it.note,
                    cardReference = it.cardReference,
                    stage = it.stage?.name,
                    recordedAt = it.recordedAt.toEpochMilliseconds(),
                )
            },
        historyEvents =
            rows.historyEvents.map {
                BackupHistoryEventRow(
                    id = it.id.toString(),
                    kind = it.kind.name,
                    occurredAt = it.occurredAt.toEpochMilliseconds(),
                    gameId = it.gameId.toString(),
                    taskId = it.taskId?.toString(),
                    stage = it.stage?.name,
                    previousQuantity = it.previousQuantity,
                    newQuantity = it.newQuantity,
                )
            },
        importBatchCells =
            rows.importBatchCells.map {
                BackupImportBatchCellRow(
                    importBatchId = it.importBatchId.toString(),
                    cellId = it.cellId.toString(),
                    documentBefore = it.documentBefore,
                )
            },
        draftTasks =
            rows.draftTasks.map {
                BackupDraftTaskRow(
                    id = it.id.toString(),
                    rawImportBlockId = it.rawImportBlockId.toString(),
                    name = it.name,
                    suggestedPoolType = it.suggestedPoolType?.name,
                    selectedPoolType = it.selectedPoolType?.name,
                    selectedTrackingMode = it.selectedTrackingMode?.name,
                    requiredQuantity = it.requiredQuantity,
                    notes = it.notes,
                    targetCellId = it.targetCellId?.toString(),
                    selectionStartIndex = it.selectionStartIndex,
                    selectionEndIndex = it.selectionEndIndex,
                    completionHint = it.completionHint.name,
                    isMissing = it.isMissing,
                    isBorrowed = it.isBorrowed,
                    needsInfo = it.needsInfo,
                    needsClassification = it.needsClassification,
                    materializedTaskId = it.materializedTaskId?.toString(),
                    createdAt = it.createdAt.toEpochMilliseconds(),
                    updatedAt = it.updatedAt.toEpochMilliseconds(),
                )
            },
        draftTaskColors =
            rows.draftTaskColors.map {
                BackupDraftTaskColorRow(
                    draftTaskId = it.draftTaskId.toString(),
                    colorId = it.colorId.toString(),
                    slotIndex = it.slotIndex,
                )
            },
    )
