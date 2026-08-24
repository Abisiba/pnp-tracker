package dev.pnptracker.data.database.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds the progress model: completion, what is still owed, pipelines and history.
 *
 * **What this migration does with existing production data: it keeps all of it.**
 *
 * PLAN 18 requires each migration to say which of the two allowed behaviours it
 * takes, and this one converts rather than refusing. It can, because nothing here
 * has to be invented. Every new column has an answer that is true of an existing
 * row whatever it holds: work nobody has marked finished is unfinished, a print
 * run nobody has recorded has not been made, and a task nobody has reported a
 * shortage on owes nothing. So the defaults are not a guess to be papered over —
 * they are what the old rows already meant.
 *
 * That is the difference from the version 4 migration, which refused a database
 * holding tasks. There the model itself had been withdrawn and a task's place in
 * a cell could not be worked out from anything stored. Here the tables are only
 * being added to.
 *
 * Two things are written rather than defaulted:
 *
 * * Card and board tasks get their pipeline rows — PRINT/LAMINATE/CUT and
 *   PRINT/GLUE/CUT — at nothing done. PLAN 7.2 and 8 make the stages a property
 *   of the pool, so a task that had none was only missing them because the schema
 *   had nowhere to put them, and leaving them out would give the application two
 *   kinds of card task to handle.
 * * Nothing else. Three dimensional and special tasks get no stage rows, because
 *   PLAN gives their pools no pipeline and a row saying "no stages" would be a
 *   fiction the queries would then have to work around.
 *
 * The history starts empty, which is the truth: version 4 recorded no progress
 * events, so there are none to carry across and none to make up.
 */
val Migration4To5: Migration =
    object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            STATEMENTS.forEach(connection::execSQL)
        }
    }

private val STATEMENTS =
    listOf(
        // Completion, the print run, and what is still owed. Every existing task
        // takes the defaults, which say exactly what was true of it before.
        "ALTER TABLE `tasks` ADD COLUMN `is_completed` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `tasks` ADD COLUMN `completed_at` INTEGER",
        "ALTER TABLE `tasks` ADD COLUMN `primary_batch_completed` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `tasks` ADD COLUMN `current_missing_quantity` INTEGER NOT NULL DEFAULT 0",
        "CREATE INDEX IF NOT EXISTS `index_tasks_is_completed` ON `tasks` (`is_completed`)",
        // The pipelines.
        "CREATE TABLE IF NOT EXISTS `task_stages` (`task_id` TEXT NOT NULL, `stage` TEXT NOT NULL, " +
            "`order_index` INTEGER NOT NULL, `completed_quantity` INTEGER NOT NULL DEFAULT 0, " +
            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`task_id`, `stage`), FOREIGN KEY(`task_id`) REFERENCES `tasks`(`id`) " +
            "ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_task_stages_task_id_order_index` " +
            "ON `task_stages` (`task_id`, `order_index`)",
        // The history. Append only, and kept even for a task the user deleted.
        "CREATE TABLE IF NOT EXISTS `progress_events` (`id` TEXT NOT NULL, `task_id` TEXT NOT NULL, " +
            "`kind` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `note` TEXT, `card_reference` TEXT, " +
            "`stage` TEXT, `recorded_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`task_id`) REFERENCES `tasks`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `index_progress_events_task_id` ON `progress_events` (`task_id`)",
        "CREATE INDEX IF NOT EXISTS `index_progress_events_task_id_kind` ON `progress_events` (`task_id`, `kind`)",
        // Card tasks: printed, laminated, cut.
        "INSERT INTO `task_stages` (`task_id`, `stage`, `order_index`, `completed_quantity`, " +
            "`created_at`, `updated_at`) SELECT `id`, 'PRINT', 0, 0, `created_at`, `updated_at` " +
            "FROM `tasks` WHERE `pool_type` = 'CARD'",
        "INSERT INTO `task_stages` (`task_id`, `stage`, `order_index`, `completed_quantity`, " +
            "`created_at`, `updated_at`) SELECT `id`, 'LAMINATE', 1, 0, `created_at`, `updated_at` " +
            "FROM `tasks` WHERE `pool_type` = 'CARD'",
        "INSERT INTO `task_stages` (`task_id`, `stage`, `order_index`, `completed_quantity`, " +
            "`created_at`, `updated_at`) SELECT `id`, 'CUT', 2, 0, `created_at`, `updated_at` " +
            "FROM `tasks` WHERE `pool_type` = 'CARD'",
        // Board tasks: printed, glued, cut.
        "INSERT INTO `task_stages` (`task_id`, `stage`, `order_index`, `completed_quantity`, " +
            "`created_at`, `updated_at`) SELECT `id`, 'PRINT', 0, 0, `created_at`, `updated_at` " +
            "FROM `tasks` WHERE `pool_type` = 'BOARD'",
        "INSERT INTO `task_stages` (`task_id`, `stage`, `order_index`, `completed_quantity`, " +
            "`created_at`, `updated_at`) SELECT `id`, 'GLUE', 1, 0, `created_at`, `updated_at` " +
            "FROM `tasks` WHERE `pool_type` = 'BOARD'",
        "INSERT INTO `task_stages` (`task_id`, `stage`, `order_index`, `completed_quantity`, " +
            "`created_at`, `updated_at`) SELECT `id`, 'CUT', 2, 0, `created_at`, `updated_at` " +
            "FROM `tasks` WHERE `pool_type` = 'BOARD'",
    )
