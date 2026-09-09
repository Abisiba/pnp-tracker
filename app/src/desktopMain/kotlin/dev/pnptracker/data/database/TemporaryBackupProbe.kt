package dev.pnptracker.data.database

import androidx.room3.PooledConnection
import androidx.room3.executeSQL
import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import dev.pnptracker.domain.backup.restore.BackupPlace
import dev.pnptracker.domain.backup.restore.BackupProbe
import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.BackupRejection
import dev.pnptracker.domain.backup.restore.SUPPORTED_SOURCE_SCHEMA_VERSION
import dev.pnptracker.domain.backup.sha256Of
import dev.pnptracker.platform.files.XdgAppPathsResolver
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The tables in the order PLAN 14.4.2 restores them, and every column of each.
 *
 * Written out rather than generated. The order is a decision — each table after
 * the ones it points at — and the columns are the format's contract with schema
 * version 8; a coverage test holds both against the committed `8.json`, so a
 * column added to the database without a thought for backups fails there rather
 * than going missing from somebody's data.
 */
internal val RESTORE_ORDER =
    listOf(
        "colors" to "id, canonical_name, normalized_name, hex, sort_order",
        "color_aliases" to "color_id, alias, normalized_alias",
        "import_batches" to
            "id, file_name, sha256, source_format, sheet_name, start_row_index, end_row_index, " +
            "start_column_index, end_column_index, created_game_count, raw_block_count, created_task_count, " +
            "status, imported_at, updated_at",
        "games" to "id, name, is_manually_completed, completed_at, created_at, updated_at, deleted_at, source_import_batch_id",
        "game_cells" to "id, game_id, column_type, created_at, updated_at",
        "raw_import_blocks" to
            "id, import_batch_id, raw_text, sheet_name, row_index, column_index, source_column_type, " +
            "fill_color_argb, game_completion_hint, completion_target_game_id, is_processed, created_at, updated_at",
        "tasks" to
            "id, pool_type, tracking_mode, name, required_quantity, notes, is_completed, completed_at, " +
            "primary_batch_completed, current_missing_quantity, created_at, updated_at, deleted_at, " +
            "source_raw_import_block_id, is_missing, is_borrowed, needs_info, needs_classification",
        "cell_segments" to "id, cell_id, order_index, kind, text, task_id, created_at, updated_at",
        "task_colors" to "task_id, color_id, slot_index",
        "task_stages" to "task_id, stage, order_index, completed_quantity, created_at, updated_at",
        "progress_events" to "id, task_id, kind, quantity, note, card_reference, stage, recorded_at",
        "history_events" to "id, kind, occurred_at, game_id, task_id, stage, previous_quantity, new_quantity",
        "import_batch_cells" to "import_batch_id, cell_id, document_before",
        "draft_tasks" to
            "id, raw_import_block_id, name, suggested_pool_type, selected_pool_type, selected_tracking_mode, " +
            "required_quantity, notes, target_cell_id, selection_start_index, selection_end_index, completion_hint, " +
            "is_missing, is_borrowed, needs_info, needs_classification, materialized_task_id, created_at, updated_at",
        "draft_task_colors" to "draft_task_id, color_id, slot_index",
    )

/**
 * Tries a backup out on a database that exists for the length of the question.
 *
 * Everything before this reasoned about the file. This asks storage: it makes a
 * database of the current schema in a temporary directory, empties it — the
 * twelve colours Room seeds a new database with included, because a restore
 * replaces the data rather than joining it (PLAN 14.4.3) — writes all fifteen
 * tables in the order PLAN 14.4.2 gives, runs `foreign_key_check` while the
 * transaction is still open, and then reads the whole thing back out through the
 * same reader a real backup is taken with. If what comes back is the same data
 * and the same checksum, the file describes a database that can exist.
 *
 * Three things about the boundary, and they are the point of the class.
 *
 * It takes no path. Not from the caller, not from configuration, not from a
 * default that could be changed elsewhere: it makes its own directory under the
 * system's temporary one and works only there. There is no argument anybody
 * could pass that would aim this at the user's database.
 *
 * It takes no database either. A production `AppDatabase` cannot be handed in,
 * so nothing here can be pointed at a connection that is already open on
 * somebody's data.
 *
 * And it offers no way to keep what it built. The temporary database is deleted
 * before this returns, on every path — the one where it worked as much as the
 * ones where it did not. Putting a validated backup into the live database is a
 * later slice's work and there is deliberately no road from here to there.
 */
class TemporaryBackupProbe(
    private val databases: DatabaseFactory = DatabaseFactory(),
    /**
     * Where the throwaway database goes.
     *
     * Named so a test can watch it, not so a caller can choose it: whatever it
     * answers is held against the two rules below before anything is created,
     * and a directory that is not a temporary one, or that is anywhere near the
     * application's own data, is refused rather than used.
     */
    private val temporaryDirectory: () -> Path = { Files.createTempDirectory("pnp-tracker-backup-probe") },
    private val applicationDataDirectory: () -> Path = { XdgAppPathsResolver().resolve().dataDirectory },
) : BackupProbe {
    override suspend fun probe(
        data: BackupData,
        dataSha256: String,
    ): BackupRejection? {
        val root = temporaryDirectory().toAbsolutePath().normalize()
        refuseAnythingButATemporaryDirectory(root)

        val file = root.resolve(PROBE_DATABASE_NAME)
        try {
            val database = databases.open(file)
            try {
                // Opens the database and runs the migrations and the seed, so
                // what the transaction below starts from is a real, current
                // schema rather than an empty file.
                database.gameDao().activeCount()
                load(database, data)
                return readBackAndCompare(database, data, dataSha256)
            } catch (refusedByStorage: SQLiteException) {
                return refused()
            } catch (refusedByARow: IllegalArgumentException) {
                // A row that storage accepted and the entities will not: an
                // invariant the checks above do not mirror. The backup is the
                // thing at fault, not this code, so it is refused rather than
                // allowed to travel as an exception.
                return refused()
            } catch (refusedByTheProbe: ProbeRefusal) {
                return refusedByTheProbe.rejection
            } finally {
                database.close()
            }
        } finally {
            deleteEverythingUnder(root)
        }
    }

    /**
     * Empties the database and fills it from the backup, once, or not at all.
     *
     * One transaction, as PLAN 14.4.3 requires of a restore, so a probe that
     * fails halfway leaves nothing behind to confuse the reading that follows.
     * Tables are emptied against the direction of the foreign keys and filled
     * along it, which is the order the schema will accept and the order PLAN
     * 14.4.2 names.
     *
     * `defer_foreign_keys` holds the checking to the end of the transaction.
     * With the writes already in dependency order it changes nothing today, and
     * that is why it is here: the guarantee this makes is about the transaction
     * as a whole rather than about the order somebody happened to write it in.
     * The check itself is then run explicitly, inside the transaction, because a
     * commit that succeeds is not on its own evidence that the graph is whole.
     */
    private suspend fun load(
        database: AppDatabase,
        data: BackupData,
    ) {
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                executeSQL("PRAGMA defer_foreign_keys = TRUE")
                RESTORE_ORDER.asReversed().forEach { (table, _) -> executeSQL("DELETE FROM $table") }
                insertEverything(this, data)
                usePrepared("PRAGMA foreign_key_check") { statement ->
                    if (statement.step()) throw ProbeRefusal(BackupProblem.BROKEN_REFERENCE, BackupPlace("data"))
                }
            }
        }
    }

    /**
     * Reads the whole database back and holds it against the file it came from.
     *
     * This is the strongest thing the probe says, and it is deliberately said
     * with the same reader a real backup is taken with. Every row goes back
     * through the entities on the way out, so every `init` invariant they state
     * is checked again by the database itself rather than by a list somebody
     * kept in step by hand; and the canonical writer and the digest are the ones
     * the format is defined by, so agreement here means the file, the storage
     * and the checksum all say the same thing.
     */
    private suspend fun readBackAndCompare(
        database: AppDatabase,
        data: BackupData,
        dataSha256: String,
    ): BackupRejection? {
        val userVersion =
            database.useReaderConnection { transactor ->
                transactor.usePrepared("PRAGMA user_version") { statement ->
                    check(statement.step()) { "PRAGMA user_version returned no row" }
                    statement.getInt(0)
                }
            }
        if (userVersion != SUPPORTED_SOURCE_SCHEMA_VERSION) return refused()

        val reread = BackupStore(database).snapshot()
        if (reread.sourceSchemaVersion != SUPPORTED_SOURCE_SCHEMA_VERSION) return refused()
        if (reread.data != data) return refused()
        if (sha256Of(canonicalBackupDataJson(reread.data).encodeToByteArray()) != dataSha256) return refused()
        return null
    }

    private fun refuseAnythingButATemporaryDirectory(root: Path) {
        val systemTemporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(root.startsWith(systemTemporary) && root != systemTemporary) {
            "A backup is only ever tried out in a temporary directory, and this one is not below $systemTemporary."
        }
        val applicationData = applicationDataDirectory().toAbsolutePath().normalize()
        check(!root.startsWith(applicationData)) {
            "Refusing to build a throwaway database inside the application's own data directory."
        }
    }

    private fun deleteEverythingUnder(root: Path) {
        if (!Files.exists(root)) return
        try {
            Files.walk(root).use { entries ->
                entries.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        } catch (couldNotDelete: IOException) {
            // The answer about the backup has already been worked out, and a
            // temporary file that outlived its directory does not change it.
        }
    }

    private companion object {
        const val PROBE_DATABASE_NAME = "backup-probe.db"
    }
}

/** A refusal raised from inside the transaction, so the transaction ends with it. */
private class ProbeRefusal(
    problem: BackupProblem,
    place: BackupPlace,
) : Exception("The backup did not survive being written to a temporary database") {
    val rejection = BackupRejection(problem, place)
}

private fun refused() = BackupRejection(BackupProblem.TEMP_VALIDATION_FAILED, BackupPlace("data"))

/**
 * Writes all fifteen tables, one prepared statement each.
 *
 * A statement per table and not per row: the statement is prepared once and
 * stepped for every row, so a backup of a thousand tasks costs fifteen
 * preparations. Nothing is read back while writing — no `SELECT` per row, no
 * checking whether something is already there — because the tables were emptied
 * a moment ago and the file has already been shown to hold no two rows with the
 * same key.
 */
private suspend fun insertEverything(
    connection: PooledConnection,
    data: BackupData,
) {
    connection.insertRows(0, data.colors) { listOf(it.id, it.canonicalName, it.normalizedName, it.hex, it.sortOrder) }
    connection.insertRows(1, data.colorAliases) { listOf(it.colorId, it.alias, it.normalizedAlias) }
    connection.insertRows(2, data.importBatches) {
        listOf(
            it.id,
            it.fileName,
            it.sha256,
            it.sourceFormat,
            it.sheetName,
            it.startRowIndex,
            it.endRowIndex,
            it.startColumnIndex,
            it.endColumnIndex,
            it.createdGameCount,
            it.rawBlockCount,
            it.createdTaskCount,
            it.status,
            it.importedAt,
            it.updatedAt,
        )
    }
    connection.insertRows(3, data.games) {
        listOf(
            it.id,
            it.name,
            it.isManuallyCompleted,
            it.completedAt,
            it.createdAt,
            it.updatedAt,
            it.deletedAt,
            it.sourceImportBatchId,
        )
    }
    connection.insertRows(4, data.gameCells) { listOf(it.id, it.gameId, it.columnType, it.createdAt, it.updatedAt) }
    connection.insertRows(5, data.rawImportBlocks) {
        listOf(
            it.id,
            it.importBatchId,
            it.rawText,
            it.sheetName,
            it.rowIndex,
            it.columnIndex,
            it.sourceColumnType,
            it.fillColorArgb,
            it.gameCompletionHint,
            it.completionTargetGameId,
            it.isProcessed,
            it.createdAt,
            it.updatedAt,
        )
    }
    connection.insertRows(6, data.tasks) {
        listOf(
            it.id,
            it.poolType,
            it.trackingMode,
            it.name,
            it.requiredQuantity,
            it.notes,
            it.isCompleted,
            it.completedAt,
            it.primaryBatchCompleted,
            it.currentMissingQuantity,
            it.createdAt,
            it.updatedAt,
            it.deletedAt,
            it.sourceRawImportBlockId,
            it.isMissing,
            it.isBorrowed,
            it.needsInfo,
            it.needsClassification,
        )
    }
    connection.insertRows(7, data.cellSegments) {
        listOf(it.id, it.cellId, it.orderIndex, it.kind, it.text, it.taskId, it.createdAt, it.updatedAt)
    }
    connection.insertRows(8, data.taskColors) { listOf(it.taskId, it.colorId, it.slotIndex) }
    connection.insertRows(9, data.taskStages) {
        listOf(it.taskId, it.stage, it.orderIndex, it.completedQuantity, it.createdAt, it.updatedAt)
    }
    connection.insertRows(10, data.progressEvents) {
        listOf(it.id, it.taskId, it.kind, it.quantity, it.note, it.cardReference, it.stage, it.recordedAt)
    }
    connection.insertRows(11, data.historyEvents) {
        listOf(it.id, it.kind, it.occurredAt, it.gameId, it.taskId, it.stage, it.previousQuantity, it.newQuantity)
    }
    connection.insertRows(12, data.importBatchCells) { listOf(it.importBatchId, it.cellId, it.documentBefore) }
    connection.insertRows(13, data.draftTasks) {
        listOf(
            it.id,
            it.rawImportBlockId,
            it.name,
            it.suggestedPoolType,
            it.selectedPoolType,
            it.selectedTrackingMode,
            it.requiredQuantity,
            it.notes,
            it.targetCellId,
            it.selectionStartIndex,
            it.selectionEndIndex,
            it.completionHint,
            it.isMissing,
            it.isBorrowed,
            it.needsInfo,
            it.needsClassification,
            it.materializedTaskId,
            it.createdAt,
            it.updatedAt,
        )
    }
    connection.insertRows(14, data.draftTaskColors) { listOf(it.draftTaskId, it.colorId, it.slotIndex) }
}

private suspend fun <R> PooledConnection.insertRows(
    table: Int,
    rows: List<R>,
    valuesOf: (R) -> List<Any?>,
) {
    if (rows.isEmpty()) return
    val (name, columns) = RESTORE_ORDER[table]
    val placeholders = columns.split(", ").joinToString(", ") { "?" }
    usePrepared("INSERT INTO $name ($columns) VALUES ($placeholders)") { statement ->
        rows.forEach { row ->
            statement.reset()
            statement.clearBindings()
            valuesOf(row).forEachIndexed { index, value -> statement.bindValue(index + 1, value) }
            statement.step()
        }
    }
}

/**
 * Binds one value, in the storage type the column holds.
 *
 * The records already carry exactly what the columns hold — canonical UUID text,
 * enum names, epoch milliseconds — so nothing is converted here beyond the two
 * shapes SQLite has no separate type for. Anything else reaching this is a
 * record type and a column list that have parted company, which is a mistake in
 * this file rather than anything a backup could say.
 */
private fun SQLiteStatement.bindValue(
    at: Int,
    value: Any?,
) {
    when (value) {
        null -> bindNull(at)
        is String -> bindText(at, value)
        is Int -> bindLong(at, value.toLong())
        is Long -> bindLong(at, value)
        is Boolean -> bindLong(at, if (value) 1L else 0L)
        else -> error("Nothing binds a ${value::class.simpleName} into a backup column")
    }
}
