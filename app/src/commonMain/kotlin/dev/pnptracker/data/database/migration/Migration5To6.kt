package dev.pnptracker.data.database.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Makes room for the decisions an import review takes: the colours a draft is
 * given, the flags a task carries out of the source columns, and the game an
 * accepted green cell was about.
 *
 * **What this migration does with existing production data: it keeps all of it.**
 *
 * PLAN 18 requires each migration to say which of the two allowed behaviours it
 * takes, and this one converts rather than refusing — in fact it converts
 * nothing, because everything it adds is new. No existing column is rewritten,
 * no table is rebuilt, and no row is read except to be left alone. The three
 * defaults are what the old rows already meant: a task nobody marked as coming
 * from the missing or borrowed column did not; a task nobody said was waiting on
 * information was not; and a draft nobody gave colours to has none.
 *
 * The one row shape that deserves naming is a version 5 raw cell whose green
 * hint was already `ACCEPTED`. Version 5 had nowhere to record which game such
 * an acceptance meant, so those rows arrive here with a decision and no target.
 * **They are left exactly as they are.** Turning them back into `PENDING` would
 * throw away an answer the user gave, and inventing a target would put a game
 * into their decision that they never picked. The row stays accepted with a null
 * target, which the review workspace can show as a decision still missing its
 * game — a question to ask, not a record to quietly correct.
 */
val Migration5To6: Migration =
    object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            STATEMENTS.forEach(connection::execSQL)
        }
    }

private val STATEMENTS =
    listOf(
        // The colours of a draft. Its own table rather than a use of
        // `task_colors`: a draft is not a task, and may never become one.
        "CREATE TABLE IF NOT EXISTS `draft_task_colors` (`draft_task_id` TEXT NOT NULL, " +
            "`color_id` TEXT NOT NULL, `slot_index` INTEGER NOT NULL, " +
            "PRIMARY KEY(`draft_task_id`, `color_id`), " +
            "FOREIGN KEY(`draft_task_id`) REFERENCES `draft_tasks`(`id`) ON UPDATE RESTRICT ON DELETE CASCADE , " +
            "FOREIGN KEY(`color_id`) REFERENCES `colors`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `index_draft_task_colors_color_id` ON `draft_task_colors` (`color_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_draft_task_colors_draft_task_id_slot_index` " +
            "ON `draft_task_colors` (`draft_task_id`, `slot_index`)",
        // The four flags PLAN 10 and 11.7 keep on a task. Every existing task
        // takes the default, which says exactly what was true of it before:
        // version 5 had nowhere to record any of this, so no task carried it.
        "ALTER TABLE `tasks` ADD COLUMN `is_missing` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `tasks` ADD COLUMN `is_borrowed` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `tasks` ADD COLUMN `needs_info` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `tasks` ADD COLUMN `needs_classification` INTEGER NOT NULL DEFAULT 0",
        // Where an accepted green cell points. Nullable, and left null for every
        // existing row — including an already accepted one, whose decision is
        // kept rather than rewritten.
        "ALTER TABLE `raw_import_blocks` ADD COLUMN `completion_target_game_id` TEXT " +
            "REFERENCES `games`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT",
        "CREATE INDEX IF NOT EXISTS `index_raw_import_blocks_completion_target_game_id` " +
            "ON `raw_import_blocks` (`completion_target_game_id`)",
    )
