package dev.pnptracker.domain.backup

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer

/**
 * What the backup format is supposed to contain, written out by hand.
 *
 * This is the test's own statement of the contract, not something derived from
 * the code it checks. Derived from the record types it would agree with them
 * whatever they said; derived from the schema it would agree with the database
 * whatever it held. Written out, it can disagree with either, and that is the
 * whole point: [BackupSchemaCoverageTest] holds it against the committed
 * `8.json` on one side and against the serializers on the other, so a column
 * added to an entity without a field to carry it fails here rather than going
 * missing from somebody's backup.
 *
 * Each [Column] names a database column and the JSON field that carries it. The
 * pairing is spelled out rather than computed from the column name, because the
 * format may not quietly follow the database's naming: PLAN 14.4.1 gives the
 * backup a version of its own, and a column renamed in a future schema must
 * force a decision here rather than silently becoming a different file format.
 */
data class Column(
    val column: String,
    val field: String,
)

data class TableContract(
    /** The table in the database. */
    val table: String,
    /** The array in `data` that carries it. */
    val arrayName: String,
    /** The columns PLAN 14.4.2 orders this table's rows by. */
    val orderedBy: List<String>,
    /** The record type that carries one row. */
    val descriptor: SerialDescriptor,
    val columns: List<Column>,
)

private infix fun String.to(field: String) = Column(this, field)

/**
 * The fifteen tables of schema version 8 and every column of each, in the order
 * PLAN 14.4.2 restores them.
 */
val backupManifest: List<TableContract> =
    listOf(
        TableContract(
            table = "colors",
            arrayName = "colors",
            orderedBy = listOf("id"),
            descriptor = serializer<BackupColorRow>().descriptor,
            columns =
                listOf(
                    "id" to "id",
                    "canonical_name" to "canonicalName",
                    "normalized_name" to "normalizedName",
                    "hex" to "hex",
                    "sort_order" to "sortOrder",
                ),
        ),
        TableContract(
            table = "color_aliases",
            arrayName = "colorAliases",
            orderedBy = listOf("color_id", "normalized_alias"),
            descriptor = serializer<BackupColorAliasRow>().descriptor,
            columns =
                listOf(
                    "color_id" to "colorId",
                    "alias" to "alias",
                    "normalized_alias" to "normalizedAlias",
                ),
        ),
        TableContract(
            table = "import_batches",
            arrayName = "importBatches",
            orderedBy = listOf("id"),
            descriptor = serializer<BackupImportBatchRow>().descriptor,
            columns =
                listOf(
                    "id" to "id",
                    "file_name" to "fileName",
                    "sha256" to "sha256",
                    "source_format" to "sourceFormat",
                    "sheet_name" to "sheetName",
                    "start_row_index" to "startRowIndex",
                    "end_row_index" to "endRowIndex",
                    "start_column_index" to "startColumnIndex",
                    "end_column_index" to "endColumnIndex",
                    "created_game_count" to "createdGameCount",
                    "raw_block_count" to "rawBlockCount",
                    "created_task_count" to "createdTaskCount",
                    "status" to "status",
                    "imported_at" to "importedAt",
                    "updated_at" to "updatedAt",
                ),
        ),
        TableContract(
            table = "games",
            arrayName = "games",
            orderedBy = listOf("id"),
            descriptor = serializer<BackupGameRow>().descriptor,
            columns =
                listOf(
                    "id" to "id",
                    "name" to "name",
                    "is_manually_completed" to "isManuallyCompleted",
                    "completed_at" to "completedAt",
                    "created_at" to "createdAt",
                    "updated_at" to "updatedAt",
                    "deleted_at" to "deletedAt",
                    "source_import_batch_id" to "sourceImportBatchId",
                ),
        ),
        TableContract(
            table = "game_cells",
            arrayName = "gameCells",
            orderedBy = listOf("id"),
            descriptor = serializer<BackupGameCellRow>().descriptor,
            columns =
                listOf(
                    "id" to "id",
                    "game_id" to "gameId",
                    "column_type" to "columnType",
                    "created_at" to "createdAt",
                    "updated_at" to "updatedAt",
                ),
        ),
        TableContract(
            table = "raw_import_blocks",
            arrayName = "rawImportBlocks",
            orderedBy = listOf("id"),
            descriptor = serializer<BackupRawImportBlockRow>().descriptor,
            columns =
                listOf(
                    "id" to "id",
                    "import_batch_id" to "importBatchId",
                    "raw_text" to "rawText",
                    "sheet_name" to "sheetName",
                    "row_index" to "rowIndex",
                    "column_index" to "columnIndex",
                    "source_column_type" to "sourceColumnType",
                    "fill_color_argb" to "fillColorArgb",
                    "game_completion_hint" to "gameCompletionHint",
                    "completion_target_game_id" to "completionTargetGameId",
                    "is_processed" to "isProcessed",
                    "created_at" to "createdAt",
                    "updated_at" to "updatedAt",
                ),
        ),
        TableContract(
            table = "tasks",
            arrayName = "tasks",
            orderedBy = listOf("id"),
            descriptor = serializer<BackupTaskRow>().descriptor,
            columns =
                listOf(
                    "id" to "id",
                    "pool_type" to "poolType",
                    "tracking_mode" to "trackingMode",
                    "name" to "name",
                    "required_quantity" to "requiredQuantity",
                    "notes" to "notes",
                    "is_completed" to "isCompleted",
                    "completed_at" to "completedAt",
                    "primary_batch_completed" to "primaryBatchCompleted",
                    "current_missing_quantity" to "currentMissingQuantity",
                    "created_at" to "createdAt",
                    "updated_at" to "updatedAt",
                    "deleted_at" to "deletedAt",
                    "source_raw_import_block_id" to "sourceRawImportBlockId",
                    "is_missing" to "isMissing",
                    "is_borrowed" to "isBorrowed",
                    "needs_info" to "needsInfo",
                    "needs_classification" to "needsClassification",
                ),
        ),
        TableContract(
            table = "cell_segments",
            arrayName = "cellSegments",
            orderedBy = listOf("cell_id", "order_index"),
            descriptor = serializer<BackupCellSegmentRow>().descriptor,
            columns =
                listOf(
                    "id" to "id",
                    "cell_id" to "cellId",
                    "order_index" to "orderIndex",
                    "kind" to "kind",
                    "text" to "text",
                    "task_id" to "taskId",
                    "created_at" to "createdAt",
                    "updated_at" to "updatedAt",
                ),
        ),
        TableContract(
            table = "task_colors",
            arrayName = "taskColors",
            orderedBy = listOf("task_id", "slot_index"),
            descriptor = serializer<BackupTaskColorRow>().descriptor,
            columns =
                listOf(
                    "task_id" to "taskId",
                    "color_id" to "colorId",
                    "slot_index" to "slotIndex",
                ),
        ),
        TableContract(
            table = "task_stages",
            arrayName = "taskStages",
            orderedBy = listOf("task_id", "order_index"),
            descriptor = serializer<BackupTaskStageRow>().descriptor,
            columns =
                listOf(
                    "task_id" to "taskId",
                    "stage" to "stage",
                    "order_index" to "orderIndex",
                    "completed_quantity" to "completedQuantity",
                    "created_at" to "createdAt",
                    "updated_at" to "updatedAt",
                ),
        ),
        TableContract(
            table = "progress_events",
            arrayName = "progressEvents",
            orderedBy = listOf("id"),
            descriptor = serializer<BackupProgressEventRow>().descriptor,
            columns =
                listOf(
                    "id" to "id",
                    "task_id" to "taskId",
                    "kind" to "kind",
                    "quantity" to "quantity",
                    "note" to "note",
                    "card_reference" to "cardReference",
                    "stage" to "stage",
                    "recorded_at" to "recordedAt",
                ),
        ),
        TableContract(
            table = "history_events",
            arrayName = "historyEvents",
            orderedBy = listOf("id"),
            descriptor = serializer<BackupHistoryEventRow>().descriptor,
            columns =
                listOf(
                    "id" to "id",
                    "kind" to "kind",
                    "occurred_at" to "occurredAt",
                    "game_id" to "gameId",
                    "task_id" to "taskId",
                    "stage" to "stage",
                    "previous_quantity" to "previousQuantity",
                    "new_quantity" to "newQuantity",
                ),
        ),
        TableContract(
            table = "import_batch_cells",
            arrayName = "importBatchCells",
            orderedBy = listOf("import_batch_id", "cell_id"),
            descriptor = serializer<BackupImportBatchCellRow>().descriptor,
            columns =
                listOf(
                    "import_batch_id" to "importBatchId",
                    "cell_id" to "cellId",
                    "document_before" to "documentBefore",
                ),
        ),
        TableContract(
            table = "draft_tasks",
            arrayName = "draftTasks",
            orderedBy = listOf("id"),
            descriptor = serializer<BackupDraftTaskRow>().descriptor,
            columns =
                listOf(
                    "id" to "id",
                    "raw_import_block_id" to "rawImportBlockId",
                    "name" to "name",
                    "suggested_pool_type" to "suggestedPoolType",
                    "selected_pool_type" to "selectedPoolType",
                    "selected_tracking_mode" to "selectedTrackingMode",
                    "required_quantity" to "requiredQuantity",
                    "notes" to "notes",
                    "target_cell_id" to "targetCellId",
                    "selection_start_index" to "selectionStartIndex",
                    "selection_end_index" to "selectionEndIndex",
                    "completion_hint" to "completionHint",
                    "is_missing" to "isMissing",
                    "is_borrowed" to "isBorrowed",
                    "needs_info" to "needsInfo",
                    "needs_classification" to "needsClassification",
                    "materialized_task_id" to "materializedTaskId",
                    "created_at" to "createdAt",
                    "updated_at" to "updatedAt",
                ),
        ),
        TableContract(
            table = "draft_task_colors",
            arrayName = "draftTaskColors",
            orderedBy = listOf("draft_task_id", "slot_index"),
            descriptor = serializer<BackupDraftTaskColorRow>().descriptor,
            columns =
                listOf(
                    "draft_task_id" to "draftTaskId",
                    "color_id" to "colorId",
                    "slot_index" to "slotIndex",
                ),
        ),
    )
