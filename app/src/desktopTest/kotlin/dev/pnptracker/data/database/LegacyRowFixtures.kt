package dev.pnptracker.data.database

import androidx.sqlite.SQLiteConnection
import dev.pnptracker.domain.model.EntityId

/**
 * Rows written straight through a raw connection, the way an older version of the
 * application would have written them, so migration tests start from real data
 * rather than from rows Room created with today's entities.
 */
fun insertVersion1Game(
    connection: SQLiteConnection,
    gameId: EntityId,
    name: String = "Harmonies",
    notes: String? = "eski not",
) {
    connection
        .prepare(
            "INSERT INTO games (id, name, notes, is_manually_completed, completed_at, " +
                "created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, gameId.toString())
            statement.bindText(2, name)
            if (notes == null) statement.bindNull(3) else statement.bindText(3, notes)
            statement.bindInt(4, 1)
            statement.bindLong(5, EPOCH_MILLISECONDS_UPDATED)
            statement.bindLong(6, EPOCH_MILLISECONDS_CREATED)
            statement.bindLong(7, EPOCH_MILLISECONDS_UPDATED)
            statement.bindNull(8)
            statement.step()
        }
}

fun insertVersion1Item(
    connection: SQLiteConnection,
    itemId: EntityId,
    gameId: EntityId,
    name: String = "Token",
) {
    connection
        .prepare(
            "INSERT INTO items (id, game_id, name, notes, created_at, updated_at, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, itemId.toString())
            statement.bindText(2, gameId.toString())
            statement.bindText(3, name)
            statement.bindNull(4)
            statement.bindLong(5, EPOCH_MILLISECONDS_CREATED)
            statement.bindLong(6, EPOCH_MILLISECONDS_CREATED)
            statement.bindNull(7)
            statement.step()
        }
}

fun insertVersion2Task(
    connection: SQLiteConnection,
    taskId: EntityId,
    itemId: EntityId,
    name: String = "Gri token",
    requiredQuantity: Int = 14,
) {
    connection
        .prepare(
            "INSERT INTO tasks (id, item_id, pool_type, tracking_mode, name, required_quantity, notes, " +
                "is_archived, created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, taskId.toString())
            statement.bindText(2, itemId.toString())
            statement.bindText(3, "THREE_D")
            statement.bindText(4, "THREE_D_BATCH")
            statement.bindText(5, name)
            statement.bindInt(6, requiredQuantity)
            statement.bindNull(7)
            statement.bindInt(8, 0)
            statement.bindLong(9, EPOCH_MILLISECONDS_CREATED)
            statement.bindLong(10, EPOCH_MILLISECONDS_CREATED)
            statement.bindNull(11)
            statement.step()
        }
}

fun insertVersion2TaskColor(
    connection: SQLiteConnection,
    taskId: EntityId,
    colorId: EntityId,
) {
    connection
        .prepare("INSERT INTO task_colors (task_id, color_id, relation, is_selected) VALUES (?, ?, ?, ?)")
        .use { statement ->
            statement.bindText(1, taskId.toString())
            statement.bindText(2, colorId.toString())
            statement.bindText(3, "REQUIRED")
            statement.bindInt(4, 1)
            statement.step()
        }
}

fun insertVersion2ColorAlias(
    connection: SQLiteConnection,
    colorId: EntityId,
    alias: String,
    normalizedAlias: String,
) {
    connection
        .prepare("INSERT INTO color_aliases (color_id, alias, normalized_alias) VALUES (?, ?, ?)")
        .use { statement ->
            statement.bindText(1, colorId.toString())
            statement.bindText(2, alias)
            statement.bindText(3, normalizedAlias)
            statement.step()
        }
}

/**
 * An import batch as version 3 held one. Imports create no game and no task, so
 * a database can hold these and nothing else — which is the one shape the v4
 * migration both accepts and has rows to carry across.
 */
fun insertVersion3ImportBatch(
    connection: SQLiteConnection,
    batchId: EntityId,
    fileName: String = "Kitap1(1).xlsx",
) {
    connection
        .prepare(
            "INSERT INTO import_batches (id, file_name, sha256, source_format, sheet_name, " +
                "start_row_index, end_row_index, start_column_index, end_column_index, " +
                "created_game_count, raw_block_count, created_task_count, status, imported_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, batchId.toString())
            statement.bindText(2, fileName)
            statement.bindText(3, "0".repeat(64))
            statement.bindText(4, "XLSX")
            statement.bindText(5, "Sayfa1")
            statement.bindInt(6, 0)
            statement.bindInt(7, 9)
            statement.bindInt(8, 0)
            statement.bindInt(9, 6)
            statement.bindInt(10, 0)
            statement.bindInt(11, 1)
            statement.bindInt(12, 0)
            statement.bindText(13, "DRAFT")
            statement.bindLong(14, EPOCH_MILLISECONDS_CREATED)
            statement.bindLong(15, EPOCH_MILLISECONDS_UPDATED)
            statement.step()
        }
}

fun insertVersion3RawImportBlock(
    connection: SQLiteConnection,
    blockId: EntityId,
    batchId: EntityId,
    rawText: String = "15 KIRMIZI**\nBıçak ve kabza ayrı",
) {
    connection
        .prepare(
            "INSERT INTO raw_import_blocks (id, import_batch_id, raw_text, sheet_name, row_index, " +
                "column_index, source_column_type, fill_color_argb, game_completion_hint, is_processed, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, blockId.toString())
            statement.bindText(2, batchId.toString())
            statement.bindText(3, rawText)
            statement.bindText(4, "Sayfa1")
            statement.bindInt(5, 3)
            statement.bindInt(6, 2)
            statement.bindText(7, "CARD")
            statement.bindNull(8)
            statement.bindText(9, "NONE")
            statement.bindInt(10, 0)
            statement.bindLong(11, EPOCH_MILLISECONDS_CREATED)
            statement.bindLong(12, EPOCH_MILLISECONDS_UPDATED)
            statement.step()
        }
}

/**
 * A draft with every optional field filled in, so the v4 copy has something to
 * lose if it drops or transposes a column.
 *
 * `target_item_id` is left null on purpose: a draft that named an item could only
 * exist alongside an items row, and the migration refuses such a database before
 * it writes anything.
 */
fun insertVersion3DraftTask(
    connection: SQLiteConnection,
    draftId: EntityId,
    blockId: EntityId,
    name: String = "Kırmızı token",
) {
    connection
        .prepare(
            "INSERT INTO draft_tasks (id, raw_import_block_id, name, suggested_pool_type, " +
                "selected_pool_type, selected_tracking_mode, required_quantity, notes, target_item_id, " +
                "selection_start_index, selection_end_index, completion_hint, is_missing, is_borrowed, " +
                "needs_info, needs_classification, materialized_task_id, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, draftId.toString())
            statement.bindText(2, blockId.toString())
            statement.bindText(3, name)
            statement.bindText(4, "CARD")
            statement.bindText(5, "CARD")
            statement.bindText(6, "PIPELINE")
            statement.bindInt(7, 15)
            statement.bindText(8, "Sayısına bakılacak")
            statement.bindNull(9)
            statement.bindInt(10, 3)
            statement.bindInt(11, 10)
            statement.bindText(12, "ACCEPTED")
            statement.bindInt(13, 1)
            statement.bindInt(14, 0)
            statement.bindInt(15, 1)
            statement.bindInt(16, 0)
            statement.bindNull(17)
            statement.bindLong(18, EPOCH_MILLISECONDS_CREATED)
            statement.bindLong(19, EPOCH_MILLISECONDS_UPDATED)
            statement.step()
        }
}
