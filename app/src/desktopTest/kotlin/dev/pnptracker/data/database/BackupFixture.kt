package dev.pnptracker.data.database

import androidx.room3.useWriterConnection
import dev.pnptracker.domain.rules.normalizeColorTerm

/**
 * A database with something of every kind in it.
 *
 * Rows go in through raw SQL rather than through the application's own writes,
 * because a backup has to carry what storage holds and not only what today's
 * screens can produce: a task that was soft deleted, a history line of a kind
 * nothing writes yet, an import that was taken back. Reading still goes through
 * Room, so every row here is one the entities accept — a fixture the application
 * could not read would prove nothing.
 *
 * The identifiers are fixed rather than random so that the order rows come back
 * in is knowable, and so a failure names the same row twice running.
 */
suspend fun writeRow(
    database: AppDatabase,
    table: String,
    values: List<Pair<String, Any?>>,
) {
    val columns = values.joinToString(", ") { it.first }
    val placeholders = values.joinToString(", ") { "?" }
    database.useWriterConnection { transactor ->
        transactor.usePrepared("INSERT INTO $table ($columns) VALUES ($placeholders)") { statement ->
            values.forEachIndexed { index, (column, value) ->
                val position = index + 1
                when (value) {
                    null -> statement.bindNull(position)
                    is String -> statement.bindText(position, value)
                    is Int -> statement.bindLong(position, value.toLong())
                    is Long -> statement.bindLong(position, value)
                    is Boolean -> statement.bindLong(position, if (value) 1L else 0L)
                    else -> error("$table.$column: nothing binds a ${value::class.simpleName}")
                }
            }
            statement.step()
        }
    }
}

/** Counts the rows of one table without going through Room's readers. */
suspend fun rowCount(
    database: AppDatabase,
    table: String,
): Long =
    database.useWriterConnection { transactor ->
        transactor.usePrepared("SELECT COUNT(*) FROM $table") { statement ->
            check(statement.step())
            statement.getLong(0)
        }
    }

const val CUSTOM_COLOR_A = "aa000000-0000-4000-8000-000000000001"
const val CUSTOM_COLOR_B = "aa000000-0000-4000-8000-000000000002"
const val BATCH_DRAFT = "bb000000-0000-4000-8000-000000000001"
const val BATCH_CONFIRMED = "bb000000-0000-4000-8000-000000000002"
const val BATCH_ROLLED_BACK = "bb000000-0000-4000-8000-000000000003"
const val GAME_KEPT = "cc000000-0000-4000-8000-000000000001"
const val GAME_DELETED_ROW = "cc000000-0000-4000-8000-000000000002"
const val CELL_THREE_D = "dd000000-0000-4000-8000-000000000001"
const val CELL_NOTES = "dd000000-0000-4000-8000-000000000002"
const val CELL_OF_DELETED_GAME = "dd000000-0000-4000-8000-000000000003"
const val RAW_BLOCK_ACCEPTED = "ee000000-0000-4000-8000-000000000001"
const val RAW_BLOCK_PENDING = "ee000000-0000-4000-8000-000000000002"
const val RAW_BLOCK_PROCESSED = "ee000000-0000-4000-8000-000000000003"
const val TASK_MULTICOLOR = "ff000000-0000-4000-8000-000000000001"
const val TASK_FINISHED = "ff000000-0000-4000-8000-000000000002"
const val TASK_SOFT_DELETED = "ff000000-0000-4000-8000-000000000003"
const val DRAFT_FULL = "1a000000-0000-4000-8000-000000000001"
const val DRAFT_BARE = "1a000000-0000-4000-8000-000000000002"

/** Turkish, an emoji outside the basic plane, and the characters JSON has to escape. */
const val AWKWARD_TEXT = "Şükrü'nün \"özel\" işi\n\tikinci satır \\ ters bölü 👨‍👩‍👧‍👦 🇹🇷"

/** Whitespace that a backup may not tidy: PLAN 11.4.4 compares this to the character. */
const val DOCUMENT_BEFORE_WITH_SPACES = "  başta ve sonda boşluk  "

private const val MOMENT_CREATED = 1_700_000_000_000L
private const val MOMENT_UPDATED = 1_700_000_600_000L
private const val MOMENT_DELETED = 1_700_001_200_000L
private const val MOMENT_COMPLETED = 1_700_000_900_000L

private const val FINGERPRINT_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val FINGERPRINT_B = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
private const val FINGERPRINT_C = "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"

/** The green Excel fill the import reads as a completion hint (PLAN 11.5). */
private val GREEN_FILL = 0xFF92D050.toInt()

/**
 * Fills [database] with at least one row of every table and, within the tables,
 * with the awkward shapes: a soft deleted game and task, a task of two colours,
 * a pipeline part way through, every kind of history line, an import in each of
 * its three states, a draft with everything set and one with almost nothing.
 *
 * The steps are in foreign key order, which is also the order PLAN 14.4.2
 * restores in — the database refuses any other, which is itself worth knowing.
 */
suspend fun fillWithEverything(
    database: AppDatabase,
    reversed: Boolean = false,
) {
    val rows = Rows(database, reversed)
    writeCustomColors(rows)
    writeImports(rows)
    writeGamesAndCells(rows)
    writeRawBlocks(rows)
    writeTasks(rows)
    writeCellDocuments(rows)
    writeTaskDetails(rows)
    writeEvents(rows)
    writeDrafts(rows)
}

/**
 * Writes rows into a table, in the given order or against it.
 *
 * Two databases holding the same rows in a different physical order have to
 * produce the same backup, and the only way to build the second one is to write
 * the rows the other way round. The order between tables is not a choice —
 * storage refuses a child before its parent — so only the order within a table
 * is turned around.
 */
class Rows(
    private val database: AppDatabase,
    private val reversed: Boolean,
) {
    suspend fun into(
        table: String,
        rows: List<List<Pair<String, Any?>>>,
    ) {
        val ordered = if (reversed) rows.reversed() else rows
        ordered.forEach { writeRow(database, table, it) }
    }
}

private suspend fun writeCustomColors(rows: Rows) {
    val colors =
        listOf(
            Triple(CUSTOM_COLOR_A, "Fıstık Yeşili", "#92D050"),
            Triple(CUSTOM_COLOR_B, "İnci Beyazı", "#FAF9F6"),
        )
    rows.into(
        "colors",
        colors.mapIndexed { index, (id, name, hex) ->
            listOf(
                "id" to id,
                "canonical_name" to name,
                "normalized_name" to normalizeColorTerm(name),
                "hex" to hex,
                "sort_order" to 100 + index,
            )
        },
    )
    rows.into(
        "color_aliases",
        colors.map { (id, name, _) ->
            listOf(
                "color_id" to id,
                "alias" to "$name TAKMA",
                "normalized_alias" to normalizeColorTerm("$name TAKMA"),
            )
        },
    )
}

private suspend fun writeImports(rows: Rows) {
    fun batch(
        id: String,
        fileName: String,
        fingerprint: String,
        format: String,
        status: String,
        tasks: Int,
        bounded: Boolean,
    ) = listOf(
        "id" to id,
        "file_name" to fileName,
        "sha256" to fingerprint,
        "source_format" to format,
        "sheet_name" to "Sayfa1",
        "start_row_index" to if (bounded) 0 else null,
        "end_row_index" to if (bounded) 4 else null,
        "start_column_index" to if (bounded) 0 else null,
        "end_column_index" to if (bounded) 6 else null,
        "created_game_count" to 0,
        "raw_block_count" to 2,
        "created_task_count" to tasks,
        "status" to status,
        "imported_at" to MOMENT_CREATED,
        "updated_at" to MOMENT_UPDATED,
    )

    rows.into(
        "import_batches",
        listOf(
            batch(BATCH_DRAFT, "taslak.xlsx", FINGERPRINT_A, "XLSX", "DRAFT", 0, bounded = true),
            batch(BATCH_CONFIRMED, "onaylı.csv", FINGERPRINT_B, "CSV", "CONFIRMED", 2, bounded = true),
            // Every optional bound left out, which is the shape an import that
            // detected no range leaves behind.
            batch(BATCH_ROLLED_BACK, "geri alınmış.xlsx", FINGERPRINT_C, "XLSX", "ROLLED_BACK", 1, bounded = false),
        ),
    )
}

private suspend fun writeGamesAndCells(rows: Rows) {
    rows.into(
        "games",
        listOf(
            listOf(
                "id" to GAME_KEPT,
                "name" to "Örnek Oyun 🎲",
                "is_manually_completed" to false,
                "completed_at" to null,
                "created_at" to MOMENT_CREATED,
                "updated_at" to MOMENT_UPDATED,
                "deleted_at" to null,
                "source_import_batch_id" to BATCH_CONFIRMED,
            ),
            listOf(
                "id" to GAME_DELETED_ROW,
                "name" to "Silinmiş Oyun",
                "is_manually_completed" to true,
                "completed_at" to MOMENT_COMPLETED,
                "created_at" to MOMENT_CREATED,
                "updated_at" to MOMENT_UPDATED,
                "deleted_at" to MOMENT_DELETED,
                "source_import_batch_id" to null,
            ),
        ),
    )
    rows.into(
        "game_cells",
        listOf(
            Triple(CELL_THREE_D, GAME_KEPT, "THREE_D"),
            Triple(CELL_NOTES, GAME_KEPT, "NOTES"),
            Triple(CELL_OF_DELETED_GAME, GAME_DELETED_ROW, "CARD"),
        ).map { (id, gameId, column) ->
            listOf(
                "id" to id,
                "game_id" to gameId,
                "column_type" to column,
                "created_at" to MOMENT_CREATED,
                "updated_at" to MOMENT_UPDATED,
            )
        },
    )
}

private suspend fun writeRawBlocks(rows: Rows) {
    fun block(
        id: String,
        batchId: String,
        text: String,
        row: Int,
        hint: String,
        target: String?,
        fill: Int?,
        processed: Boolean,
    ) = listOf(
        "id" to id,
        "import_batch_id" to batchId,
        "raw_text" to text,
        "sheet_name" to "Sayfa1",
        "row_index" to row,
        "column_index" to 1,
        "source_column_type" to "THREE_D",
        "fill_color_argb" to fill,
        "game_completion_hint" to hint,
        "completion_target_game_id" to target,
        "is_processed" to processed,
        "created_at" to MOMENT_CREATED,
        "updated_at" to MOMENT_UPDATED,
    )

    rows.into(
        "raw_import_blocks",
        listOf(
            block(RAW_BLOCK_ACCEPTED, BATCH_CONFIRMED, AWKWARD_TEXT, 1, "ACCEPTED", GAME_KEPT, GREEN_FILL, true),
            block(RAW_BLOCK_PENDING, BATCH_CONFIRMED, "15 KIRMIZI** 19 YEŞİL**", 2, "PENDING", null, null, false),
            block(RAW_BLOCK_PROCESSED, BATCH_DRAFT, "Bird Cards 170", 3, "NONE", null, null, true),
        ),
    )
}

private suspend fun writeTasks(rows: Rows) {
    rows.into(
        "tasks",
        listOf(
            listOf(
                "id" to TASK_MULTICOLOR,
                "pool_type" to "THREE_D",
                "tracking_mode" to "THREE_D_BATCH",
                "name" to "Kılıç $AWKWARD_TEXT",
                "required_quantity" to 15,
                "notes" to AWKWARD_TEXT,
                "is_completed" to false,
                "completed_at" to null,
                "primary_batch_completed" to true,
                "current_missing_quantity" to 3,
                "created_at" to MOMENT_CREATED,
                "updated_at" to MOMENT_UPDATED,
                "deleted_at" to null,
                "source_raw_import_block_id" to RAW_BLOCK_ACCEPTED,
                "is_missing" to true,
                "is_borrowed" to false,
                "needs_info" to false,
                "needs_classification" to false,
            ),
            listOf(
                "id" to TASK_FINISHED,
                "pool_type" to "CARD",
                "tracking_mode" to "PIPELINE",
                "name" to "Bird Cards",
                "required_quantity" to 170,
                "notes" to null,
                "is_completed" to true,
                "completed_at" to MOMENT_COMPLETED,
                "primary_batch_completed" to false,
                "current_missing_quantity" to 0,
                "created_at" to MOMENT_CREATED,
                "updated_at" to MOMENT_UPDATED,
                "deleted_at" to null,
                "source_raw_import_block_id" to null,
                "is_missing" to false,
                "is_borrowed" to true,
                "needs_info" to false,
                "needs_classification" to false,
            ),
            listOf(
                "id" to TASK_SOFT_DELETED,
                "pool_type" to "SPECIAL",
                "tracking_mode" to "CHECKLIST",
                "name" to "Zar torbası",
                "required_quantity" to null,
                "notes" to "",
                "is_completed" to false,
                "completed_at" to null,
                "primary_batch_completed" to false,
                "current_missing_quantity" to 0,
                "created_at" to MOMENT_CREATED,
                "updated_at" to MOMENT_UPDATED,
                "deleted_at" to MOMENT_DELETED,
                "source_raw_import_block_id" to null,
                "is_missing" to false,
                "is_borrowed" to false,
                "needs_info" to true,
                "needs_classification" to true,
            ),
        ),
    )
}

private suspend fun writeCellDocuments(rows: Rows) {
    fun segment(
        id: String,
        cellId: String,
        order: Int,
        kind: String,
        text: String?,
        taskId: String?,
    ) = listOf(
        "id" to id,
        "cell_id" to cellId,
        "order_index" to order,
        "kind" to kind,
        "text" to text,
        "task_id" to taskId,
        "created_at" to MOMENT_CREATED,
        "updated_at" to MOMENT_UPDATED,
    )

    // Written out of order on purpose: what comes back is sorted by position in
    // the cell, not by the order somebody happened to write the rows.
    rows.into(
        "cell_segments",
        listOf(
            segment("2b000000-0000-4000-8000-000000000002", CELL_THREE_D, 1, "TASK", null, TASK_MULTICOLOR),
            segment("2b000000-0000-4000-8000-000000000001", CELL_THREE_D, 0, "PLAIN_TEXT", "Kullanıcının kendi notu ", null),
            segment("2b000000-0000-4000-8000-000000000003", CELL_THREE_D, 2, "PLAIN_TEXT", " ve $AWKWARD_TEXT", null),
            segment("2b000000-0000-4000-8000-000000000004", CELL_NOTES, 0, "PLAIN_TEXT", "Serbest not 📦", null),
            segment("2b000000-0000-4000-8000-000000000005", CELL_OF_DELETED_GAME, 0, "TASK", null, TASK_FINISHED),
            segment("2b000000-0000-4000-8000-000000000006", CELL_OF_DELETED_GAME, 1, "TASK", null, TASK_SOFT_DELETED),
        ),
    )
}

private suspend fun writeTaskDetails(rows: Rows) {
    // One task, two colours, in the slots the user chose: PLAN 5.10's single
    // item in several colours is one task and never several.
    rows.into(
        "task_colors",
        listOf(
            listOf("task_id" to TASK_MULTICOLOR, "color_id" to CUSTOM_COLOR_B, "slot_index" to 1),
            listOf("task_id" to TASK_MULTICOLOR, "color_id" to CUSTOM_COLOR_A, "slot_index" to 0),
            listOf("task_id" to TASK_FINISHED, "color_id" to CUSTOM_COLOR_A, "slot_index" to 0),
        ),
    )
    rows.into(
        "task_stages",
        listOf("PRINT" to 170, "LAMINATE" to 167, "CUT" to 150).mapIndexed { order, (stage, done) ->
            listOf(
                "task_id" to TASK_FINISHED,
                "stage" to stage,
                "order_index" to order,
                "completed_quantity" to done,
                "created_at" to MOMENT_CREATED,
                "updated_at" to MOMENT_UPDATED,
            )
        },
    )
}

private suspend fun writeEvents(rows: Rows) {
    fun progress(
        id: String,
        taskId: String,
        kind: String,
        quantity: Int,
        note: String?,
        card: String?,
        stage: String?,
    ) = listOf(
        "id" to id,
        "task_id" to taskId,
        "kind" to kind,
        "quantity" to quantity,
        "note" to note,
        "card_reference" to card,
        "stage" to stage,
        "recorded_at" to MOMENT_UPDATED,
    )

    rows.into(
        "progress_events",
        listOf(
            progress("3c000000-0000-4000-8000-000000000001", TASK_MULTICOLOR, "FAILURE_REPORTED", 3, AWKWARD_TEXT, null, null),
            progress("3c000000-0000-4000-8000-000000000002", TASK_MULTICOLOR, "SHORTAGE_RESOLVED", 1, null, null, null),
            progress("3c000000-0000-4000-8000-000000000003", TASK_FINISHED, "FAILURE_REPORTED", 2, null, "K-12", "LAMINATE"),
        ),
    )

    fun history(
        id: String,
        kind: String,
        gameId: String,
        taskId: String?,
        stage: String? = null,
        previous: Int? = null,
        next: Int? = null,
    ) = listOf(
        "id" to id,
        "kind" to kind,
        "occurred_at" to MOMENT_UPDATED,
        "game_id" to gameId,
        "task_id" to taskId,
        "stage" to stage,
        "previous_quantity" to previous,
        "new_quantity" to next,
    )

    // Every kind there is, so a kind added later without a thought for backups
    // shows up as a gap here.
    rows.into(
        "history_events",
        listOf(
            history("4d000000-0000-4000-8000-000000000001", "TASK_STAGE_QUANTITY_CHANGED", GAME_KEPT, TASK_FINISHED, "LAMINATE", 160, 167),
            history("4d000000-0000-4000-8000-000000000002", "TASK_COMPLETED", GAME_KEPT, TASK_FINISHED),
            history("4d000000-0000-4000-8000-000000000003", "TASK_REOPENED", GAME_KEPT, TASK_MULTICOLOR),
            history("4d000000-0000-4000-8000-000000000004", "TASK_DELETED", GAME_KEPT, TASK_SOFT_DELETED),
            history("4d000000-0000-4000-8000-000000000005", "TASK_RESTORED", GAME_KEPT, TASK_SOFT_DELETED),
            history("4d000000-0000-4000-8000-000000000006", "TASK_CONVERTED_TO_TEXT", GAME_KEPT, TASK_SOFT_DELETED),
            history("4d000000-0000-4000-8000-000000000007", "GAME_DELETED", GAME_DELETED_ROW, null),
            history("4d000000-0000-4000-8000-000000000008", "GAME_RESTORED", GAME_DELETED_ROW, null),
            history("4d000000-0000-4000-8000-000000000009", "IMPORT_CONFIRMED", GAME_KEPT, null),
            history("4d000000-0000-4000-8000-00000000000a", "IMPORT_ROLLED_BACK", GAME_KEPT, null),
            history("4d000000-0000-4000-8000-00000000000b", "TASK_ROLLED_BACK", GAME_KEPT, TASK_MULTICOLOR),
        ),
    )

    rows.into(
        "import_batch_cells",
        listOf(
            listOf(
                "import_batch_id" to BATCH_CONFIRMED,
                "cell_id" to CELL_THREE_D,
                "document_before" to DOCUMENT_BEFORE_WITH_SPACES,
            ),
            // An empty document is not the same as no record at all (PLAN
            // 11.4.4), so it is written and has to come back as the empty string.
            listOf(
                "import_batch_id" to BATCH_CONFIRMED,
                "cell_id" to CELL_NOTES,
                "document_before" to "",
            ),
        ),
    )
}

private suspend fun writeDrafts(rows: Rows) {
    rows.into(
        "draft_tasks",
        listOf(
            listOf(
                "id" to DRAFT_FULL,
                "raw_import_block_id" to RAW_BLOCK_ACCEPTED,
                "name" to "Kırmızı token",
                "suggested_pool_type" to "THREE_D",
                "selected_pool_type" to "THREE_D",
                "selected_tracking_mode" to "THREE_D_BATCH",
                "required_quantity" to 15,
                "notes" to AWKWARD_TEXT,
                "target_cell_id" to CELL_THREE_D,
                "selection_start_index" to 3,
                "selection_end_index" to 11,
                "completion_hint" to "ACCEPTED",
                "is_missing" to false,
                "is_borrowed" to true,
                "needs_info" to false,
                "needs_classification" to false,
                "materialized_task_id" to TASK_MULTICOLOR,
                "created_at" to MOMENT_CREATED,
                "updated_at" to MOMENT_UPDATED,
            ),
            listOf(
                "id" to DRAFT_BARE,
                "raw_import_block_id" to RAW_BLOCK_PENDING,
                "name" to "Adı var, başka bir şeyi yok",
                "suggested_pool_type" to null,
                "selected_pool_type" to null,
                "selected_tracking_mode" to null,
                "required_quantity" to null,
                "notes" to null,
                "target_cell_id" to null,
                "selection_start_index" to null,
                "selection_end_index" to null,
                "completion_hint" to "NONE",
                "is_missing" to false,
                "is_borrowed" to false,
                "needs_info" to false,
                "needs_classification" to false,
                "materialized_task_id" to null,
                "created_at" to MOMENT_CREATED,
                "updated_at" to MOMENT_UPDATED,
            ),
        ),
    )
    rows.into(
        "draft_task_colors",
        listOf(
            listOf("draft_task_id" to DRAFT_FULL, "color_id" to CUSTOM_COLOR_B, "slot_index" to 1),
            listOf("draft_task_id" to DRAFT_FULL, "color_id" to CUSTOM_COLOR_A, "slot_index" to 0),
        ),
    )
}

/**
 * [fillWithEverything] with import records that agree with each other, for the
 * tests that take a backup of the database back through the restore screen.
 *
 * The full fixture writes every shape a row can take, including import records
 * that contradict each other (D3; C1, C4, C5, U1; RB1, RB2, U3 — measured). No
 * path of this application can write those, and since İş 10 / Dilim 7 a backup
 * carrying them is refused before the restore question (PLAN 14.7.5 decisions 2
 * and 4). The format and live-replace tests keep the full fixture; the flow
 * tests get this one, where every table still has rows:
 *
 * - the pending raw cell and its bare draft move to the draft batch, whose
 *   count of two raw cells is then true (D3), without a completion hint on a
 *   column that is not the game column (D9);
 * - the confirmed batch keeps one raw cell, one draft, the task it made and the
 *   one cell that draft targets (C1, C2, C4, U3), no hint outside the game
 *   column (U1), and no game counted or sourced from it (C5);
 * - the rolled back batch, which carried no raw cells and no recorded cells
 *   (RB1, RB2, U3), is left out — rolling back is covered where it is made.
 */
suspend fun fillWithEverythingARestoreAccepts(database: AppDatabase) {
    fillWithEverything(database)
    executeRawSql(
        database,
        "UPDATE raw_import_blocks SET import_batch_id = '$BATCH_DRAFT', game_completion_hint = 'NONE' " +
            "WHERE id = '$RAW_BLOCK_PENDING'",
    )
    executeRawSql(
        database,
        "UPDATE raw_import_blocks SET game_completion_hint = 'NONE', completion_target_game_id = NULL " +
            "WHERE id = '$RAW_BLOCK_ACCEPTED'",
    )
    executeRawSql(
        database,
        "UPDATE import_batches SET raw_block_count = 1, created_task_count = 1 WHERE id = '$BATCH_CONFIRMED'",
    )
    executeRawSql(database, "DELETE FROM import_batch_cells WHERE cell_id = '$CELL_NOTES'")
    executeRawSql(database, "UPDATE games SET source_import_batch_id = NULL")
    executeRawSql(database, "DELETE FROM import_batches WHERE id = '$BATCH_ROLLED_BACK'")
}
