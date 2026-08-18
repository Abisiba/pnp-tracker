package dev.pnptracker.data.database.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import dev.pnptracker.data.database.insertSeedColors

/**
 * Adds the color catalogue and the task tables.
 *
 * Nothing existing is touched: `games` and `items` keep every row and column they
 * had in version 1. The seed colors are written here so that a database upgraded
 * from version 1 ends up with exactly the same catalogue as a database created
 * fresh at version 2. Room runs this inside one transaction, so a failure part
 * way through leaves the database at version 1.
 */
val Migration1To2: Migration =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            CREATE_STATEMENTS.forEach(connection::execSQL)
            insertSeedColors(connection)
        }
    }

private val CREATE_STATEMENTS =
    listOf(
        "CREATE TABLE IF NOT EXISTS `colors` (`id` TEXT NOT NULL, `canonical_name` TEXT NOT NULL, " +
            "`normalized_name` TEXT NOT NULL, `hex` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, " +
            "`is_archived` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_colors_normalized_name` ON `colors` (`normalized_name`)",
        "CREATE TABLE IF NOT EXISTS `color_aliases` (`color_id` TEXT NOT NULL, `alias` TEXT NOT NULL, " +
            "`normalized_alias` TEXT NOT NULL, PRIMARY KEY(`color_id`, `normalized_alias`), " +
            "FOREIGN KEY(`color_id`) REFERENCES `colors`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_color_aliases_normalized_alias` " +
            "ON `color_aliases` (`normalized_alias`)",
        "CREATE TABLE IF NOT EXISTS `tasks` (`id` TEXT NOT NULL, `item_id` TEXT NOT NULL, " +
            "`pool_type` TEXT NOT NULL, `tracking_mode` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`required_quantity` INTEGER, `notes` TEXT, `is_archived` INTEGER NOT NULL DEFAULT 0, " +
            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, " +
            "PRIMARY KEY(`id`), FOREIGN KEY(`item_id`) REFERENCES `items`(`id`) " +
            "ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `index_tasks_item_id` ON `tasks` (`item_id`)",
        "CREATE INDEX IF NOT EXISTS `index_tasks_pool_type` ON `tasks` (`pool_type`)",
        "CREATE INDEX IF NOT EXISTS `index_tasks_deleted_at` ON `tasks` (`deleted_at`)",
        "CREATE TABLE IF NOT EXISTS `task_colors` (`task_id` TEXT NOT NULL, `color_id` TEXT NOT NULL, " +
            "`relation` TEXT NOT NULL, `is_selected` INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(`task_id`, `color_id`), " +
            "FOREIGN KEY(`task_id`) REFERENCES `tasks`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT , " +
            "FOREIGN KEY(`color_id`) REFERENCES `colors`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `index_task_colors_color_id` ON `task_colors` (`color_id`)",
    )
