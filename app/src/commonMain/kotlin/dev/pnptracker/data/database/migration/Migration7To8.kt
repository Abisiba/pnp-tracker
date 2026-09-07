package dev.pnptracker.data.database.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Gives an import somewhere to keep what the cells it wrote into said first.
 *
 * **What this migration does with existing production data: it keeps all of it,
 * and reads nothing out of it.**
 *
 * PLAN 18 (Faz 2, Adım 4 kabul ölçütü) requires each migration to say which of
 * the two allowed behaviours it takes. This one adds and converts nothing: no
 * column changes, no table is rebuilt, and every row of every version 7 table is
 * left exactly as it was. What is added is `import_batch_cells`, and it is added
 * empty.
 *
 * ## Why nothing is backfilled
 *
 * The obvious temptation is to fill the table in for imports already confirmed,
 * by taking each target cell as it reads today and subtracting the names of the
 * tasks that import created. That would be a fabrication. Between the
 * confirmation and now the user may have written in the cell, renamed a task,
 * deleted one or turned one back into text, and the subtraction cannot tell any
 * of that from the import's own writing — so the "document before" it produced
 * would be a document that never existed, and a rollback trusting it would
 * silently discard the user's later work.
 *
 * PLAN 11.4.4 chose the honest alternative and states it as a rule: a batch with
 * no `ImportBatchCell` record cannot be rolled back, and the user is told why.
 * So an older confirmed batch having no row here is not a gap this migration
 * failed to fill. It is the recorded fact that nobody knows, and it is what
 * makes the refusal correct rather than merely cautious.
 *
 * Drafts confirmed after this upgrade are unaffected: they take the ordinary
 * path and record their cells like any other.
 */
val Migration7To8: Migration =
    object : Migration(7, 8) {
        override suspend fun migrate(connection: SQLiteConnection) {
            STATEMENTS.forEach(connection::execSQL)
        }
    }

/**
 * Written out as Room generates it, so a database that walked here and one
 * created here are the same database. `Migration7To8Test` compares both against
 * the committed `8.json` rather than against each other, so a drift in either
 * direction fails.
 */
private val STATEMENTS =
    listOf(
        "CREATE TABLE IF NOT EXISTS `import_batch_cells` (`import_batch_id` TEXT NOT NULL, " +
            "`cell_id` TEXT NOT NULL, `document_before` TEXT NOT NULL, " +
            "PRIMARY KEY(`import_batch_id`, `cell_id`), " +
            "FOREIGN KEY(`import_batch_id`) REFERENCES `import_batches`(`id`) " +
            "ON UPDATE RESTRICT ON DELETE CASCADE , " +
            "FOREIGN KEY(`cell_id`) REFERENCES `game_cells`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `index_import_batch_cells_cell_id` ON `import_batch_cells` (`cell_id`)",
    )
