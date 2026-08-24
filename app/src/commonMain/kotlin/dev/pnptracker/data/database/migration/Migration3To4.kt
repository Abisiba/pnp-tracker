package dev.pnptracker.data.database.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Thrown when a version 3 database holds rows the table-first model has no
 * honest place for.
 *
 * Carries what was found rather than a bare failure, so the user is told which
 * kind of record stood in the way instead of being handed an error to guess at.
 */
class UnconvertibleLegacyDataException(
    val gameCount: Int,
    val itemCount: Int,
    val taskCount: Int,
    val taskColorCount: Int,
) : Exception(
        "This database holds records the table model cannot place: " +
            "$gameCount games, $itemCount items, $taskCount tasks, $taskColorCount task colours. " +
            "Nothing has been changed.",
    )

/**
 * Turns the item-and-task model into the table-first one.
 *
 * **What this migration does with existing production data: it refuses to run.**
 *
 * PLAN 18 requires each migration to say which of the two allowed behaviours it
 * takes, and this one is fail-fast rather than converting. The reason is that a
 * conversion would have to invent something. A version 3 task carries no record
 * of where in a cell's text it was written — no cell, no position, nothing — so
 * placing it would mean guessing an order the user never chose, and the plan
 * forbids inventing an anchor. Items are worse: the model they belong to has
 * been withdrawn, and folding an item's name into a cell would put words on the
 * user's screen that the user did not type.
 *
 * So a database holding games, items, tasks or task colours stops here with
 * [UnconvertibleLegacyDataException]. Every check runs **before** the first
 * destructive statement, and Room wraps the whole migration in a transaction, so
 * a refused database keeps its rows, its tables and its `user_version` exactly
 * as they were and can still be opened by the older build that wrote it.
 *
 * A database that only holds colours — which is what a fresh install is, seeded
 * and not yet used — migrates cleanly. Colours and their aliases are carried
 * across row by row, keeping their identifiers, names, values and order, so a
 * task written later still points at the same colour it would have before.
 *
 * Imports are a different case and are kept. Raw cells and drafts are the record
 * of what a spreadsheet said, and losing them would lose evidence; the drafts'
 * target changes from an item to a cell, and because an item can no longer be
 * meant, every draft's target is cleared. The user aims them again at a cell,
 * which is the only honest answer when the thing they pointed at is gone.
 *
 * A draft's `materialized_task_id` is cleared for the same reason and with
 * nothing lost: it names the task a draft was turned into, every task table row
 * is dropped here, and a foreign key stops a draft from naming a task that is
 * not there. So a draft that carried one could only exist in a database holding
 * tasks — which is a database this migration refuses before it writes anything.
 */
val Migration3To4: Migration =
    object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            refuseIfLegacyProductionDataExists(connection)
            STATEMENTS.forEach(connection::execSQL)
        }
    }

private suspend fun refuseIfLegacyProductionDataExists(connection: SQLiteConnection) {
    val games = countOf(connection, "games")
    val items = countOf(connection, "items")
    val tasks = countOf(connection, "tasks")
    val taskColors = countOf(connection, "task_colors")
    if (games == 0 && items == 0 && tasks == 0 && taskColors == 0) return
    throw UnconvertibleLegacyDataException(
        gameCount = games,
        itemCount = items,
        taskCount = tasks,
        taskColorCount = taskColors,
    )
}

private fun countOf(
    connection: SQLiteConnection,
    table: String,
): Int =
    connection.prepare("SELECT COUNT(*) FROM `$table`").use { statement ->
        statement.step()
        statement.getInt(0)
    }

private val STATEMENTS =
    listOf(
        // The cell and its pieces, which is where a task now lives.
        "CREATE TABLE IF NOT EXISTS `game_cells` (`id` TEXT NOT NULL, `game_id` TEXT NOT NULL, " +
            "`column_type` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`), FOREIGN KEY(`game_id`) REFERENCES `games`(`id`) " +
            "ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_game_cells_game_id_column_type` " +
            "ON `game_cells` (`game_id`, `column_type`)",
        "CREATE TABLE IF NOT EXISTS `cell_segments` (`id` TEXT NOT NULL, `cell_id` TEXT NOT NULL, " +
            "`order_index` INTEGER NOT NULL, `kind` TEXT NOT NULL, `text` TEXT, `task_id` TEXT, " +
            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`cell_id`) REFERENCES `game_cells`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT , " +
            "FOREIGN KEY(`task_id`) REFERENCES `tasks`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_cell_segments_cell_id_order_index` " +
            "ON `cell_segments` (`cell_id`, `order_index`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_cell_segments_task_id` ON `cell_segments` (`task_id`)",
        // games loses its notes column: notes are a cell of the table now.
        "CREATE TABLE `games_v4` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`is_manually_completed` INTEGER NOT NULL DEFAULT 0, `completed_at` INTEGER, " +
            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, " +
            "`source_import_batch_id` TEXT, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`source_import_batch_id`) REFERENCES `import_batches`(`id`) " +
            "ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "INSERT INTO `games_v4` (`id`, `name`, `is_manually_completed`, `completed_at`, `created_at`, " +
            "`updated_at`, `deleted_at`, `source_import_batch_id`) SELECT `id`, `name`, " +
            "`is_manually_completed`, `completed_at`, `created_at`, `updated_at`, `deleted_at`, " +
            "`source_import_batch_id` FROM `games`",
        "DROP TABLE `games`",
        "ALTER TABLE `games_v4` RENAME TO `games`",
        "CREATE INDEX IF NOT EXISTS `index_games_deleted_at` ON `games` (`deleted_at`)",
        "CREATE INDEX IF NOT EXISTS `index_games_source_import_batch_id` ON `games` (`source_import_batch_id`)",
        // colors loses the archive flag the product no longer has.
        "CREATE TABLE `colors_v4` (`id` TEXT NOT NULL, `canonical_name` TEXT NOT NULL, " +
            "`normalized_name` TEXT NOT NULL, `hex` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))",
        "INSERT INTO `colors_v4` (`id`, `canonical_name`, `normalized_name`, `hex`, `sort_order`) " +
            "SELECT `id`, `canonical_name`, `normalized_name`, `hex`, `sort_order` FROM `colors`",
        "DROP TABLE `colors`",
        "ALTER TABLE `colors_v4` RENAME TO `colors`",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_colors_normalized_name` ON `colors` (`normalized_name`)",
        // tasks loses its item and its archive flag, and gains nothing: where it
        // is written is a cell segment's business now.
        "DROP TABLE `tasks`",
        "CREATE TABLE `tasks` (`id` TEXT NOT NULL, `pool_type` TEXT NOT NULL, `tracking_mode` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, `required_quantity` INTEGER, `notes` TEXT, `created_at` INTEGER NOT NULL, " +
            "`updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `source_raw_import_block_id` TEXT, " +
            "PRIMARY KEY(`id`), FOREIGN KEY(`source_raw_import_block_id`) " +
            "REFERENCES `raw_import_blocks`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `index_tasks_pool_type` ON `tasks` (`pool_type`)",
        "CREATE INDEX IF NOT EXISTS `index_tasks_deleted_at` ON `tasks` (`deleted_at`)",
        "CREATE INDEX IF NOT EXISTS `index_tasks_source_raw_import_block_id` " +
            "ON `tasks` (`source_raw_import_block_id`)",
        // task_colors loses the relation kind and the selection, and gains the
        // order the user picked the colours in.
        "DROP TABLE `task_colors`",
        "CREATE TABLE `task_colors` (`task_id` TEXT NOT NULL, `color_id` TEXT NOT NULL, " +
            "`slot_index` INTEGER NOT NULL, PRIMARY KEY(`task_id`, `color_id`), " +
            "FOREIGN KEY(`task_id`) REFERENCES `tasks`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT , " +
            "FOREIGN KEY(`color_id`) REFERENCES `colors`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `index_task_colors_color_id` ON `task_colors` (`color_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_task_colors_task_id_slot_index` " +
            "ON `task_colors` (`task_id`, `slot_index`)",
        // The drafts' target moves from an item to a cell. Every existing target
        // is cleared, because an item can no longer be meant.
        "CREATE TABLE `draft_tasks_v4` (`id` TEXT NOT NULL, `raw_import_block_id` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, `suggested_pool_type` TEXT, `selected_pool_type` TEXT, " +
            "`selected_tracking_mode` TEXT, `required_quantity` INTEGER, `notes` TEXT, `target_cell_id` TEXT, " +
            "`selection_start_index` INTEGER, `selection_end_index` INTEGER, " +
            "`completion_hint` TEXT NOT NULL DEFAULT 'NONE', `is_missing` INTEGER NOT NULL DEFAULT 0, " +
            "`is_borrowed` INTEGER NOT NULL DEFAULT 0, `needs_info` INTEGER NOT NULL DEFAULT 0, " +
            "`needs_classification` INTEGER NOT NULL DEFAULT 0, `materialized_task_id` TEXT, " +
            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`raw_import_block_id`) REFERENCES `raw_import_blocks`(`id`) " +
            "ON UPDATE RESTRICT ON DELETE CASCADE , " +
            "FOREIGN KEY(`target_cell_id`) REFERENCES `game_cells`(`id`) " +
            "ON UPDATE RESTRICT ON DELETE RESTRICT , " +
            "FOREIGN KEY(`materialized_task_id`) REFERENCES `tasks`(`id`) " +
            "ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "INSERT INTO `draft_tasks_v4` (`id`, `raw_import_block_id`, `name`, `suggested_pool_type`, " +
            "`selected_pool_type`, `selected_tracking_mode`, `required_quantity`, `notes`, `target_cell_id`, " +
            "`selection_start_index`, `selection_end_index`, `completion_hint`, `is_missing`, `is_borrowed`, " +
            "`needs_info`, `needs_classification`, `materialized_task_id`, `created_at`, `updated_at`) " +
            "SELECT `id`, `raw_import_block_id`, `name`, `suggested_pool_type`, `selected_pool_type`, " +
            "`selected_tracking_mode`, `required_quantity`, `notes`, NULL, `selection_start_index`, " +
            "`selection_end_index`, `completion_hint`, `is_missing`, `is_borrowed`, `needs_info`, " +
            "`needs_classification`, NULL, `created_at`, `updated_at` FROM `draft_tasks`",
        "DROP TABLE `draft_tasks`",
        "ALTER TABLE `draft_tasks_v4` RENAME TO `draft_tasks`",
        "CREATE INDEX IF NOT EXISTS `index_draft_tasks_raw_import_block_id` " +
            "ON `draft_tasks` (`raw_import_block_id`)",
        "CREATE INDEX IF NOT EXISTS `index_draft_tasks_target_cell_id` ON `draft_tasks` (`target_cell_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_draft_tasks_materialized_task_id` " +
            "ON `draft_tasks` (`materialized_task_id`)",
        // The item model is withdrawn.
        "DROP TABLE `items`",
    )
