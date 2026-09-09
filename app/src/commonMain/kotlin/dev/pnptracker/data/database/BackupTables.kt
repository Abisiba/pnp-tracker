package dev.pnptracker.data.database

import androidx.room3.PooledConnection
import androidx.room3.executeSQL
import androidx.sqlite.SQLiteStatement
import dev.pnptracker.domain.backup.BackupData

/**
 * The tables in the order PLAN 14.4.2 restores them, and every column of each.
 *
 * Written out rather than generated. The order is a decision — each table after
 * the ones it points at — and the columns are the format's contract with schema
 * version 8; a coverage test holds both against the committed `8.json`, so a
 * column added to the database without a thought for backups fails there rather
 * than going missing from somebody's data.
 */
internal val RESTORE_ORDER =
    listOf(
        "colors" to "id, canonical_name, normalized_name, hex, sort_order",
        "color_aliases" to "color_id, alias, normalized_alias",
        "import_batches" to
            "id, file_name, sha256, source_format, sheet_name, start_row_index, end_row_index, " +
            "start_column_index, end_column_index, created_game_count, raw_block_count, created_task_count, " +
            "status, imported_at, updated_at",
        "games" to "id, name, is_manually_completed, completed_at, created_at, updated_at, deleted_at, source_import_batch_id",
        "game_cells" to "id, game_id, column_type, created_at, updated_at",
        "raw_import_blocks" to
            "id, import_batch_id, raw_text, sheet_name, row_index, column_index, source_column_type, " +
            "fill_color_argb, game_completion_hint, completion_target_game_id, is_processed, created_at, updated_at",
        "tasks" to
            "id, pool_type, tracking_mode, name, required_quantity, notes, is_completed, completed_at, " +
            "primary_batch_completed, current_missing_quantity, created_at, updated_at, deleted_at, " +
            "source_raw_import_block_id, is_missing, is_borrowed, needs_info, needs_classification",
        "cell_segments" to "id, cell_id, order_index, kind, text, task_id, created_at, updated_at",
        "task_colors" to "task_id, color_id, slot_index",
        "task_stages" to "task_id, stage, order_index, completed_quantity, created_at, updated_at",
        "progress_events" to "id, task_id, kind, quantity, note, card_reference, stage, recorded_at",
        "history_events" to "id, kind, occurred_at, game_id, task_id, stage, previous_quantity, new_quantity",
        "import_batch_cells" to "import_batch_id, cell_id, document_before",
        "draft_tasks" to
            "id, raw_import_block_id, name, suggested_pool_type, selected_pool_type, selected_tracking_mode, " +
            "required_quantity, notes, target_cell_id, selection_start_index, selection_end_index, completion_hint, " +
            "is_missing, is_borrowed, needs_info, needs_classification, materialized_task_id, created_at, updated_at",
        "draft_task_colors" to "draft_task_id, color_id, slot_index",
    )

/**
 * A graph the database itself would not accept.
 *
 * Raised from inside the transaction that found it, so the transaction ends with
 * it rather than committing rows that point at nothing. Both the throwaway
 * database and the live one turn it into their own answer: for one it is a
 * backup being refused, for the other it is a restore that will not go ahead.
 */
internal class BrokenBackupGraph : Exception("A reference in the backup points at a row that is not there")

/**
 * Empties the fifteen tables and fills them from [data], inside one transaction.
 *
 * The single place this application replaces a whole database from a backup, and
 * it is deliberately single: the throwaway database a backup is tried out in and
 * the live database it is finally put into run exactly these statements in
 * exactly this order, so what the trial proved is what the restore does. Two
 * copies of it would be two chances to empty the tables in one order and fill
 * them in another.
 *
 * Emptied against the direction of the foreign keys and filled along it, which
 * is the order the schema accepts and the order PLAN 14.4.2 names. The twelve
 * colours Room seeds a new database with go with everything else: a restore
 * replaces the data rather than joining it (PLAN 14.4.3), so a backup holding no
 * colours must leave none behind.
 *
 * `defer_foreign_keys` holds the checking to the end of the transaction. With
 * the writes already in dependency order it changes nothing today, and that is
 * why it is here: the guarantee is about the transaction as a whole rather than
 * about the order somebody happened to write it in. The check is then run
 * explicitly, before the transaction ends, because a commit that succeeds is not
 * on its own evidence that the graph is whole.
 *
 * The caller supplies the transaction. Nothing here begins or ends one, so
 * neither caller can accidentally commit half of this.
 *
 * @throws BrokenBackupGraph if `foreign_key_check` finds anything at all.
 */
internal suspend fun replaceEverythingWith(
    connection: PooledConnection,
    data: BackupData,
) {
    connection.executeSQL("PRAGMA defer_foreign_keys = TRUE")
    RESTORE_ORDER.asReversed().forEach { (table, _) -> connection.executeSQL("DELETE FROM $table") }
    insertEverything(connection, data)
    connection.usePrepared("PRAGMA foreign_key_check") { statement ->
        if (statement.step()) throw BrokenBackupGraph()
    }
}

/**
 * Writes all fifteen tables, one prepared statement each.
 *
 * A statement per table and not per row: the statement is prepared once and
 * stepped for every row, so a backup of a thousand tasks costs fifteen
 * preparations. Nothing is read back while writing — no `SELECT` per row, no
 * checking whether something is already there — because the tables were emptied
 * a moment ago and the file has already been shown to hold no two rows with the
 * same key.
 */
private suspend fun insertEverything(
    connection: PooledConnection,
    data: BackupData,
) {
    connection.insertRows(0, data.colors) { listOf(it.id, it.canonicalName, it.normalizedName, it.hex, it.sortOrder) }
    connection.insertRows(1, data.colorAliases) { listOf(it.colorId, it.alias, it.normalizedAlias) }
    connection.insertRows(2, data.importBatches) {
        listOf(
            it.id,
            it.fileName,
            it.sha256,
            it.sourceFormat,
            it.sheetName,
            it.startRowIndex,
            it.endRowIndex,
            it.startColumnIndex,
            it.endColumnIndex,
            it.createdGameCount,
            it.rawBlockCount,
            it.createdTaskCount,
            it.status,
            it.importedAt,
            it.updatedAt,
        )
    }
    connection.insertRows(3, data.games) {
        listOf(
            it.id,
            it.name,
            it.isManuallyCompleted,
            it.completedAt,
            it.createdAt,
            it.updatedAt,
            it.deletedAt,
            it.sourceImportBatchId,
        )
    }
    connection.insertRows(4, data.gameCells) { listOf(it.id, it.gameId, it.columnType, it.createdAt, it.updatedAt) }
    connection.insertRows(5, data.rawImportBlocks) {
        listOf(
            it.id,
            it.importBatchId,
            it.rawText,
            it.sheetName,
            it.rowIndex,
            it.columnIndex,
            it.sourceColumnType,
            it.fillColorArgb,
            it.gameCompletionHint,
            it.completionTargetGameId,
            it.isProcessed,
            it.createdAt,
            it.updatedAt,
        )
    }
    connection.insertRows(6, data.tasks) {
        listOf(
            it.id,
            it.poolType,
            it.trackingMode,
            it.name,
            it.requiredQuantity,
            it.notes,
            it.isCompleted,
            it.completedAt,
            it.primaryBatchCompleted,
            it.currentMissingQuantity,
            it.createdAt,
            it.updatedAt,
            it.deletedAt,
            it.sourceRawImportBlockId,
            it.isMissing,
            it.isBorrowed,
            it.needsInfo,
            it.needsClassification,
        )
    }
    connection.insertRows(7, data.cellSegments) {
        listOf(it.id, it.cellId, it.orderIndex, it.kind, it.text, it.taskId, it.createdAt, it.updatedAt)
    }
    connection.insertRows(8, data.taskColors) { listOf(it.taskId, it.colorId, it.slotIndex) }
    connection.insertRows(9, data.taskStages) {
        listOf(it.taskId, it.stage, it.orderIndex, it.completedQuantity, it.createdAt, it.updatedAt)
    }
    connection.insertRows(10, data.progressEvents) {
        listOf(it.id, it.taskId, it.kind, it.quantity, it.note, it.cardReference, it.stage, it.recordedAt)
    }
    connection.insertRows(11, data.historyEvents) {
        listOf(it.id, it.kind, it.occurredAt, it.gameId, it.taskId, it.stage, it.previousQuantity, it.newQuantity)
    }
    connection.insertRows(12, data.importBatchCells) { listOf(it.importBatchId, it.cellId, it.documentBefore) }
    connection.insertRows(13, data.draftTasks) {
        listOf(
            it.id,
            it.rawImportBlockId,
            it.name,
            it.suggestedPoolType,
            it.selectedPoolType,
            it.selectedTrackingMode,
            it.requiredQuantity,
            it.notes,
            it.targetCellId,
            it.selectionStartIndex,
            it.selectionEndIndex,
            it.completionHint,
            it.isMissing,
            it.isBorrowed,
            it.needsInfo,
            it.needsClassification,
            it.materializedTaskId,
            it.createdAt,
            it.updatedAt,
        )
    }
    connection.insertRows(14, data.draftTaskColors) { listOf(it.draftTaskId, it.colorId, it.slotIndex) }
}

private suspend fun <R> PooledConnection.insertRows(
    table: Int,
    rows: List<R>,
    valuesOf: (R) -> List<Any?>,
) {
    if (rows.isEmpty()) return
    val (name, columns) = RESTORE_ORDER[table]
    val placeholders = columns.split(", ").joinToString(", ") { "?" }
    usePrepared("INSERT INTO $name ($columns) VALUES ($placeholders)") { statement ->
        rows.forEach { row ->
            statement.reset()
            statement.clearBindings()
            valuesOf(row).forEachIndexed { index, value -> statement.bindValue(index + 1, value) }
            statement.step()
        }
    }
}

/**
 * Binds one value, in the storage type the column holds.
 *
 * The records already carry exactly what the columns hold — canonical UUID text,
 * enum names, epoch milliseconds — so nothing is converted here beyond the two
 * shapes SQLite has no separate type for. Anything else reaching this is a
 * record type and a column list that have parted company, which is a mistake in
 * this file rather than anything a backup could say.
 */
private fun SQLiteStatement.bindValue(
    at: Int,
    value: Any?,
) {
    when (value) {
        null -> bindNull(at)
        is String -> bindText(at, value)
        is Int -> bindLong(at, value.toLong())
        is Long -> bindLong(at, value)
        is Boolean -> bindLong(at, if (value) 1L else 0L)
        else -> error("Nothing binds a ${value::class.simpleName} into a backup column")
    }
}
