package dev.pnptracker.data.repository

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.data.database.writeRow
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.TimeSource

private val MOMENT = Instant.fromEpochMilliseconds(1_757_320_364_031)
private const val TASKS = 1_200

/**
 * A backup of a library the size of a real one.
 *
 * PLAN 18 asks for a thousand tasks and more, and the question is not whether
 * some number is beaten — a threshold tuned on this machine would fail on
 * somebody else's and teach nobody anything — but whether the shape is right:
 * the work is proportional to the data and there is no hidden pass over it. What
 * the measurement is for is the record; it is printed rather than asserted, and
 * the only bound is loose enough that only a change of kind can cross it.
 */
class BackupLargeDataTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExisted = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
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

    private suspend fun addTasks(count: Int) {
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
                    "created_at" to MOMENT_MILLIS,
                    "updated_at" to MOMENT_MILLIS,
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
                    "created_at" to MOMENT_MILLIS,
                    "updated_at" to MOMENT_MILLIS,
                ),
            )
            writeRow(
                database,
                "tasks",
                listOf(
                    "id" to taskId,
                    "pool_type" to "THREE_D",
                    "tracking_mode" to "THREE_D_BATCH",
                    "name" to "Görev $index — Şükrü'nün işi",
                    "required_quantity" to 12,
                    "notes" to "not $index",
                    "is_completed" to false,
                    "completed_at" to null,
                    "primary_batch_completed" to false,
                    "current_missing_quantity" to 0,
                    "created_at" to MOMENT_MILLIS,
                    "updated_at" to MOMENT_MILLIS,
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
                    "created_at" to MOMENT_MILLIS,
                    "updated_at" to MOMENT_MILLIS,
                ),
            )
        }
    }

    @Test
    fun `a library of more than a thousand tasks is backed up in one pass`() =
        runBlocking<Unit> {
            fillWithEverything(database)
            addTasks(TASKS)

            val runtime = Runtime.getRuntime()
            runtime.gc()
            val beforeBytes = runtime.totalMemory() - runtime.freeMemory()
            val started = TimeSource.Monotonic.markNow()
            val document = exported()
            val elapsed = started.elapsedNow()
            val afterBytes = runtime.totalMemory() - runtime.freeMemory()

            val characters = document.json.length
            println(
                "backup of ${TASKS + 3} tasks: $characters characters, " +
                    "${elapsed.inWholeMilliseconds} ms, " +
                    "${(afterBytes - beforeBytes) / 1024} KiB more in use afterwards",
            )

            assertEquals(TASKS + 3, document.envelope.data.tasks.size)
            assertTrue(characters > 500_000, "the fixture did not grow as expected: $characters characters")
            // Loose on purpose: this is here to catch a pass over the data that
            // should not be there, not to score the machine it ran on.
            assertTrue(
                elapsed.inWholeSeconds < 60,
                "a backup of ${TASKS + 3} tasks took ${elapsed.inWholeMilliseconds} ms",
            )
        }

    @Test
    fun `the document does not change with the machine's language or line endings`() =
        runBlocking<Unit> {
            fillWithEverything(database)
            val default = Locale.getDefault()
            val separator = System.getProperty("line.separator")
            try {
                Locale.setDefault(Locale.of("tr", "TR"))
                System.setProperty("line.separator", "\r\n")
                val turkish = exported()

                Locale.setDefault(Locale.ROOT)
                System.setProperty("line.separator", "\n")
                val root = exported()

                assertEquals(root.json, turkish.json, "the document changed with the machine's settings")
                assertTrue('\r' !in root.json, "the document picked up the platform's line ending")
            } finally {
                Locale.setDefault(default)
                System.setProperty("line.separator", separator)
            }
        }

    private companion object {
        const val MOMENT_MILLIS = 1_700_000_000_000L
    }
}
