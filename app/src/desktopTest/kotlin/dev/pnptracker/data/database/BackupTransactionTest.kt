package dev.pnptracker.data.database

import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MOMENT = 1_700_000_000_000L

/**
 * Whether a backup is one reading of the database or several.
 *
 * Fifteen reads run one after another, and between any two of them somebody can
 * write. If they were fifteen separate readings, a game created half way through
 * could arrive in the file with its cell and without its task — a graph that
 * never existed, and one that would restore into a database missing a row it
 * points at.
 *
 * So the write is really attempted, from another thread, while the snapshot is
 * between two of its tables, and what is asked afterwards is not which way it
 * went but that it went one way whole: all four rows or none of them.
 */
class BackupTransactionTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = PausingSqliteDriver()
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

    @Test
    fun `a write made while the backup is running cannot leave half of itself in it`() {
        val gameId = IdGenerator.Random.newId().toString()
        val cellId = IdGenerator.Random.newId().toString()
        val taskId = IdGenerator.Random.newId().toString()
        val segmentId = IdGenerator.Random.newId().toString()

        runBlocking { fillWithEverything(database) }

        val begun = CountDownLatch(1)
        val writer =
            Thread {
                runBlocking {
                    begun.countDown()
                    writeWholeGame(gameId, cellId, taskId, segmentId)
                }
            }

        // The colours are the first table a snapshot reads and the tasks are the
        // seventh, so the write is attempted with the transaction well underway
        // and with more tables still to come.
        driver.interruptOnce(matches = { "FROM tasks" in it }) {
            writer.start()
            begun.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        val data = runBlocking { BackupStore(database).snapshot().data }
        writer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        assertTrue(!writer.isAlive, "the concurrent write never finished")

        val present =
            listOf(
                data.games.any { it.id == gameId },
                data.gameCells.any { it.id == cellId },
                data.tasks.any { it.id == taskId },
                data.cellSegments.any { it.id == segmentId },
            )
        assertTrue(
            present.all { it } || present.none { it },
            "the backup caught a write half way through: game/cell/task/segment present = $present",
        )

        // And the write really did happen, so the test is not passing because
        // nothing was ever attempted.
        val afterwards = runBlocking { BackupStore(database).snapshot().data }
        assertTrue(afterwards.games.any { it.id == gameId }, "the concurrent write never landed")
        assertTrue(afterwards.cellSegments.any { it.id == segmentId })
    }

    @Test
    fun `the snapshot taken afterwards has everything the one during did, and the write besides`() {
        runBlocking { fillWithEverything(database) }
        val before = runBlocking { BackupStore(database).snapshot().data }

        val gameId = IdGenerator.Random.newId().toString()
        runBlocking {
            writeWholeGame(
                gameId,
                IdGenerator.Random.newId().toString(),
                IdGenerator.Random.newId().toString(),
                IdGenerator.Random.newId().toString(),
            )
        }
        val after = runBlocking { BackupStore(database).snapshot().data }

        assertEquals(before.games.size + 1, after.games.size)
        assertTrue(before.games.none { it.id == gameId })
        assertTrue(after.games.any { it.id == gameId })
    }

    private suspend fun writeWholeGame(
        gameId: String,
        cellId: String,
        taskId: String,
        segmentId: String,
    ) {
        writeRow(
            database,
            "games",
            listOf(
                "id" to gameId,
                "name" to "Araya giren oyun",
                "is_manually_completed" to false,
                "completed_at" to null,
                "created_at" to MOMENT,
                "updated_at" to MOMENT,
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
                "created_at" to MOMENT,
                "updated_at" to MOMENT,
            ),
        )
        writeRow(
            database,
            "tasks",
            listOf(
                "id" to taskId,
                "pool_type" to "THREE_D",
                "tracking_mode" to "THREE_D_BATCH",
                "name" to "Araya giren görev",
                "required_quantity" to 4,
                "notes" to null,
                "is_completed" to false,
                "completed_at" to null,
                "primary_batch_completed" to false,
                "current_missing_quantity" to 0,
                "created_at" to MOMENT,
                "updated_at" to MOMENT,
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
                "id" to segmentId,
                "cell_id" to cellId,
                "order_index" to 0,
                "kind" to "TASK",
                "text" to null,
                "task_id" to taskId,
                "created_at" to MOMENT,
                "updated_at" to MOMENT,
            ),
        )
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}
