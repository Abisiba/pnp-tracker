package dev.pnptracker.data.database.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds the tables an import lives in and the two links from production records
 * back to where they came from.
 *
 * `games` and `tasks` gain their new column through `ALTER TABLE ADD COLUMN`, so
 * every existing row keeps the values it already had and simply reads `null` for
 * the new one. No table is rebuilt and no row is rewritten.
 */
val Migration2To3: Migration =
    object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            STATEMENTS.forEach(connection::execSQL)
        }
    }

private val STATEMENTS =
    listOf(
        "CREATE TABLE IF NOT EXISTS `import_batches` (`id` TEXT NOT NULL, `file_name` TEXT NOT NULL, " +
            "`sha256` TEXT NOT NULL, `source_format` TEXT NOT NULL, `sheet_name` TEXT NOT NULL, " +
            "`start_row_index` INTEGER, `end_row_index` INTEGER, `start_column_index` INTEGER, " +
            "`end_column_index` INTEGER, `created_game_count` INTEGER NOT NULL DEFAULT 0, " +
            "`raw_block_count` INTEGER NOT NULL DEFAULT 0, `created_task_count` INTEGER NOT NULL DEFAULT 0, " +
            "`status` TEXT NOT NULL, `imported_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_import_batches_sha256` ON `import_batches` (`sha256`)",
        "CREATE INDEX IF NOT EXISTS `index_import_batches_status` ON `import_batches` (`status`)",
        "CREATE TABLE IF NOT EXISTS `raw_import_blocks` (`id` TEXT NOT NULL, `import_batch_id` TEXT NOT NULL, " +
            "`raw_text` TEXT NOT NULL, `sheet_name` TEXT NOT NULL, `row_index` INTEGER NOT NULL, " +
            "`column_index` INTEGER NOT NULL, `source_column_type` TEXT NOT NULL, `fill_color_argb` INTEGER, " +
            "`game_completion_hint` TEXT NOT NULL DEFAULT 'NONE', `is_processed` INTEGER NOT NULL DEFAULT 0, " +
            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`import_batch_id`) REFERENCES `import_batches`(`id`) " +
            "ON UPDATE RESTRICT ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_raw_import_blocks_import_batch_id` " +
            "ON `raw_import_blocks` (`import_batch_id`)",
        "CREATE INDEX IF NOT EXISTS `index_raw_import_blocks_is_processed` ON `raw_import_blocks` (`is_processed`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "`index_raw_import_blocks_import_batch_id_sheet_name_row_index_column_index` " +
            "ON `raw_import_blocks` (`import_batch_id`, `sheet_name`, `row_index`, `column_index`)",
        "CREATE TABLE IF NOT EXISTS `draft_tasks` (`id` TEXT NOT NULL, `raw_import_block_id` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, `suggested_pool_type` TEXT, `selected_pool_type` TEXT, " +
            "`selected_tracking_mode` TEXT, `required_quantity` INTEGER, `notes` TEXT, `target_item_id` TEXT, " +
            "`selection_start_index` INTEGER, `selection_end_index` INTEGER, " +
            "`completion_hint` TEXT NOT NULL DEFAULT 'NONE', `is_missing` INTEGER NOT NULL DEFAULT 0, " +
            "`is_borrowed` INTEGER NOT NULL DEFAULT 0, `needs_info` INTEGER NOT NULL DEFAULT 0, " +
            "`needs_classification` INTEGER NOT NULL DEFAULT 0, `materialized_task_id` TEXT, " +
            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`raw_import_block_id`) REFERENCES `raw_import_blocks`(`id`) " +
            "ON UPDATE RESTRICT ON DELETE CASCADE , " +
            "FOREIGN KEY(`target_item_id`) REFERENCES `items`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT , " +
            "FOREIGN KEY(`materialized_task_id`) REFERENCES `tasks`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `index_draft_tasks_raw_import_block_id` ON `draft_tasks` (`raw_import_block_id`)",
        "CREATE INDEX IF NOT EXISTS `index_draft_tasks_target_item_id` ON `draft_tasks` (`target_item_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_draft_tasks_materialized_task_id` " +
            "ON `draft_tasks` (`materialized_task_id`)",
        "ALTER TABLE `games` ADD COLUMN `source_import_batch_id` TEXT " +
            "REFERENCES `import_batches`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT",
        "CREATE INDEX IF NOT EXISTS `index_games_source_import_batch_id` ON `games` (`source_import_batch_id`)",
        "ALTER TABLE `tasks` ADD COLUMN `source_raw_import_block_id` TEXT " +
            "REFERENCES `raw_import_blocks`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT",
        "CREATE INDEX IF NOT EXISTS `index_tasks_source_raw_import_block_id` " +
            "ON `tasks` (`source_raw_import_block_id`)",
    )
