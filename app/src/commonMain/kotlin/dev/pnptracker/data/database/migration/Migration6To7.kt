package dev.pnptracker.data.database.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Gives the application somewhere to keep what happened, not only what is.
 *
 * **What this migration does with existing production data: it keeps all of it,
 * and reads three things out of it.**
 *
 * PLAN 18 requires each migration to say which of the two allowed behaviours it
 * takes, and this one converts rather than refusing. Nothing existing is
 * rewritten: no column changes, no table is rebuilt, and every row of every
 * version 6 table is left exactly as it was. What is added is `history_events`
 * and, in it, the past that version 6 already knew but had nowhere to say.
 *
 * ## What is recovered, and why only this much
 *
 * Three facts in a version 6 database are records of something that happened,
 * each with the time it happened at already stored:
 *
 * * `tasks.completed_at` — this task was finished, then.
 * * `tasks.deleted_at` — this task was removed from view, then.
 * * `games.deleted_at` — this game was removed from view, then.
 *
 * Those become one history event each, at their own timestamp. A task that was
 * both finished and removed gets both, because they are two different things
 * that happened to it.
 *
 * Everything else is **not** invented:
 *
 * * **Stage movements.** A version 6 pipeline stores where the work stands, not
 *   how it got there. A count of nine could be one movement or nine, on any day.
 * * **Reopenings.** A task that was finished and then made active again clears
 *   `completed_at`, so a version 6 database cannot tell a task that was reopened
 *   from one that was never finished.
 * * **Conversions back to text.** Until this version, converting a task to text
 *   deleted the task and everything hanging off it. Those rows are gone; there
 *   is nothing left to write an event from.
 * * **Imports taken back.** No import has ever been rolled back — nothing writes
 *   that status — so there is no such event to recover.
 *
 * PLAN 1404 forbids a migration from quietly losing rows; it does not ask one to
 * make up history it cannot know, and a fabricated date on the history screen
 * would be worse than an honest gap.
 *
 * ## The identifiers
 *
 * A backfilled event's identifier is **derived from the row it is about** rather
 * than generated, so this cannot write two events for the same fact — a second
 * run would collide with the primary key and take the whole transaction down
 * instead of doubling the history.
 *
 * The derivation replaces the UUID's version digit, which is the fifteenth
 * character of the canonical form. Every identifier this application has ever
 * written is a random (version 4) UUID, so that digit is always `4` and nothing
 * is lost by replacing it: the mapping is one to one, and a derived identifier
 * can never equal an identifier the application generates, because those always
 * carry `4` there and these never do. The digit also says which of the three
 * facts the event is about, so a task that was both finished and deleted gets
 * two identifiers that differ by construction rather than by luck.
 *
 * ## A task with no cell
 *
 * A history event names the game it happened in, and a task reaches its game
 * through the piece of a cell it is written in. Every task the application can
 * create has one; a task with none is a record no screen can show and no pool
 * can hold. Such a task is left without a backfilled event rather than given a
 * guessed game — PLAN forbids inventing a target — and it does not stop the
 * migration, because refusing to open a database over a row the user cannot even
 * see would cost them everything to protect nothing.
 */
val Migration6To7: Migration =
    object : Migration(6, 7) {
        override suspend fun migrate(connection: SQLiteConnection) {
            STATEMENTS.forEach(connection::execSQL)
        }
    }

/**
 * The canonical UUID text with its version digit replaced by [marker].
 *
 * Character fifteen of `xxxxxxxx-xxxx-Vxxx-xxxx-xxxxxxxxxxxx` is `V`, so the
 * first fourteen characters and everything from the sixteenth are kept as they
 * are and the shape stays canonical — which is what [dev.pnptracker.domain.model.EntityId]
 * will insist on when the row is read back.
 */
private fun derivedIdOf(
    column: String,
    marker: Char,
): String = "substr($column, 1, 14) || '$marker' || substr($column, 16)"

/** Marks an event this migration read out of `tasks.completed_at`. */
private const val COMPLETED_MARKER = '8'

/** Marks an event this migration read out of `tasks.deleted_at`. */
private const val TASK_DELETED_MARKER = '9'

/** Marks an event this migration read out of `games.deleted_at`. */
private const val GAME_DELETED_MARKER = 'a'

/**
 * One backfill insert: every task carrying [timestampColumn] becomes an event.
 *
 * The join to `games` is what guarantees the foreign key: a task is only given
 * an event when the cell it is written in really does belong to a game that is
 * there. Deleted games are included on purpose — PLAN 1123 asks the history
 * screen for records that have since been removed, so leaving them out would
 * empty exactly the part of it this migration exists to fill.
 */
private fun taskBackfill(
    kind: String,
    marker: Char,
    timestampColumn: String,
): String =
    """
    INSERT INTO `history_events`
        (`id`, `kind`, `occurred_at`, `game_id`, `task_id`, `stage`, `previous_quantity`, `new_quantity`)
    SELECT ${derivedIdOf("`tasks`.`id`", marker)}, '$kind', `tasks`.`$timestampColumn`,
           `game_cells`.`game_id`, `tasks`.`id`, NULL, NULL, NULL
    FROM `tasks`
    INNER JOIN `cell_segments` ON `cell_segments`.`task_id` = `tasks`.`id`
    INNER JOIN `game_cells` ON `game_cells`.`id` = `cell_segments`.`cell_id`
    INNER JOIN `games` ON `games`.`id` = `game_cells`.`game_id`
    WHERE `tasks`.`$timestampColumn` IS NOT NULL
    """

private val STATEMENTS =
    listOf(
        "CREATE TABLE IF NOT EXISTS `history_events` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
            "`occurred_at` INTEGER NOT NULL, `game_id` TEXT NOT NULL, `task_id` TEXT, `stage` TEXT, " +
            "`previous_quantity` INTEGER, `new_quantity` INTEGER, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`game_id`) REFERENCES `games`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT , " +
            "FOREIGN KEY(`task_id`) REFERENCES `tasks`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `index_history_events_occurred_at` ON `history_events` (`occurred_at`)",
        "CREATE INDEX IF NOT EXISTS `index_history_events_game_id_occurred_at` " +
            "ON `history_events` (`game_id`, `occurred_at`)",
        "CREATE INDEX IF NOT EXISTS `index_history_events_task_id_occurred_at` " +
            "ON `history_events` (`task_id`, `occurred_at`)",
        taskBackfill("TASK_COMPLETED", COMPLETED_MARKER, "completed_at"),
        taskBackfill("TASK_DELETED", TASK_DELETED_MARKER, "deleted_at"),
        """
        INSERT INTO `history_events`
            (`id`, `kind`, `occurred_at`, `game_id`, `task_id`, `stage`, `previous_quantity`, `new_quantity`)
        SELECT ${derivedIdOf("`games`.`id`", GAME_DELETED_MARKER)}, 'GAME_DELETED', `games`.`deleted_at`,
               `games`.`id`, NULL, NULL, NULL, NULL
        FROM `games`
        WHERE `games`.`deleted_at` IS NOT NULL
        """,
    )
