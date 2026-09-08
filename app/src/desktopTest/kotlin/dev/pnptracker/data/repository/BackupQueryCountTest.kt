package dev.pnptracker.data.repository

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.data.database.writeRow
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private val MOMENT = Instant.fromEpochMilliseconds(1_757_320_364_031)
private const val TABLES = 15

/**
 * What a backup costs the database, and whether the cost grows with the library.
 *
 * Fifteen reads and no more, whatever is in there. A read per row, or per game,
 * would turn a thousand tasks into thousands of round trips — PLAN 16 rules that
 * shape out — and would also mean the file described many different moments
 * instead of one.
 *
 * Counted at the driver rather than at the DAO, so what is measured is what
 * SQLite was really asked, including anything Room added on the way.
 */
class BackupQueryCountTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExisted = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = driver).open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExisted)
        directory.delete()
    }

    private fun exported() =
        runBlocking {
            DatabaseBackupExporter(BackupStore(database), AppInfo.Current, StoppedClock(MOMENT)).backupDocument()
        }

    private fun ran(recorded: List<String>): Map<String, Int> =
        recorded
            .filterNot { "room_table_modification" in it.lowercase() }
            .map { it.trimStart().uppercase().replace(Regex("\\s+"), " ") }
            .filterNot { it.startsWith("BEGIN") || it.startsWith("COMMIT") || it.startsWith("END") }
            .filterNot { it.startsWith("ROLLBACK") || it.startsWith("SAVEPOINT") || it.startsWith("RELEASE") }
            .filterNot { it.startsWith("PRAGMA") }
            .groupingBy { statement ->
                when {
                    statement.startsWith("SELECT") -> "SELECT"
                    statement.startsWith("INSERT") -> "INSERT"
                    statement.startsWith("UPDATE") -> "UPDATE"
                    statement.startsWith("DELETE") -> "DELETE"
                    else -> "other"
                }
            }.eachCount()

    /** Adds [count] more tasks, each in its own cell of its own game. */
    private suspend fun addLibrary(count: Int) {
        repeat(count) { index ->
            val gameId = IdGenerator.Random.newId().toString()
            val cellId = IdGenerator.Random.newId().toString()
            val taskId = IdGenerator.Random.newId().toString()
            writeRow(
                database,
                "games",
                listOf(
                    "id" to gameId,
                    "name" to "Oyun $index",
                    "is_manually_completed" to false,
                    "completed_at" to null,
                    "created_at" to 1_700_000_000_000L,
                    "updated_at" to 1_700_000_000_000L,
                    "deleted_at" to null,
                    "source_import_batch_id" to null,
                ),
            )
            writeRow(
                database,
                "game_cells",
                listOf(
                    "id" to cellId,
                    "game_id" to gameId,
                    "column_type" to "THREE_D",
                    "created_at" to 1_700_000_000_000L,
                    "updated_at" to 1_700_000_000_000L,
                ),
            )
            writeRow(
                database,
                "tasks",
                listOf(
                    "id" to taskId,
                    "pool_type" to "THREE_D",
                    "tracking_mode" to "THREE_D_BATCH",
                    "name" to "Görev $index",
                    "required_quantity" to 12,
                    "notes" to null,
                    "is_completed" to false,
                    "completed_at" to null,
                    "primary_batch_completed" to false,
                    "current_missing_quantity" to 0,
                    "created_at" to 1_700_000_000_000L,
                    "updated_at" to 1_700_000_000_000L,
                    "deleted_at" to null,
                    "source_raw_import_block_id" to null,
                    "is_missing" to false,
                    "is_borrowed" to false,
                    "needs_info" to false,
                    "needs_classification" to false,
                ),
            )
            writeRow(
                database,
                "cell_segments",
                listOf(
                    "id" to IdGenerator.Random.newId().toString(),
                    "cell_id" to cellId,
                    "order_index" to 0,
                    "kind" to "TASK",
                    "text" to null,
                    "task_id" to taskId,
                    "created_at" to 1_700_000_000_000L,
                    "updated_at" to 1_700_000_000_000L,
                ),
            )
        }
    }

    @Test
    fun `a backup reads each table once and no table twice`() =
        runBlocking<Unit> {
            fillWithEverything(database)
            // Warmed first, so the schema reads Room makes when a statement is
            // new are not counted as the cost of taking a backup.
            exported()

            driver.start()
            exported()
            val statements = ran(driver.stop())

            assertEquals(TABLES, statements["SELECT"], "a backup is one read per table: $statements")
        }

    @Test
    fun `a database of a thousand tasks costs the same as one of three`() =
        runBlocking<Unit> {
            fillWithEverything(database)
            exported()

            driver.start()
            exported()
            val small = ran(driver.stop())

            addLibrary(1_000)
            driver.start()
            val document = exported()
            val large = ran(driver.stop())

            assertTrue(
                document.json.length > 100_000,
                "the fixture did not actually grow: ${document.json.length} characters",
            )
            assertEquals(small, large, "a bigger library cost the backup more questions")
        }

    @Test
    fun `a backup writes nothing at all`() =
        runBlocking<Unit> {
            fillWithEverything(database)
            exported()

            driver.start()
            exported()
            val statements = ran(driver.stop())

            assertEquals(null, statements["INSERT"], "a backup wrote to the database: $statements")
            assertEquals(null, statements["UPDATE"], "a backup wrote to the database: $statements")
            assertEquals(null, statements["DELETE"], "a backup wrote to the database: $statements")
        }
}
