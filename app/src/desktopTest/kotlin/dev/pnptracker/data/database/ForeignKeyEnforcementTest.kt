package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What SQLite actually does about the foreign keys this schema declares.
 *
 * Measured rather than assumed, and the measurement corrected a written down
 * suspicion. Nothing in the Room runtime issues `PRAGMA foreign_keys = ON` and
 * SQLite's own compiled default is off, from which it had been inferred that a
 * dangling reference would be accepted and only found later by
 * `foreign_key_check`. That inference was wrong: the bundled driver this
 * application ships turns enforcement on, so the pragma reads `1` and a dangling
 * reference is refused where it is written.
 *
 * The database here is opened by [DatabaseFactory], the very class `Main.kt`
 * uses, so what is measured is the production setup rather than a connection a
 * test helper configured for itself. Only the file location differs, and it is a
 * temporary one.
 *
 * Both tests exist to notice a change. Enforcement going away would not announce
 * itself — every existing write would keep passing and only the meaning of the
 * data would quietly weaken — so it is pinned here, in the terms it matters in:
 * the pragma's value, and what happens to a row that points at nothing.
 */
class ForeignKeyEnforcementTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private var realDatabaseExisted = false

    @BeforeTest
    fun createDirectory() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
    }

    @AfterTest
    fun deleteDirectory() {
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExisted)
        directory.delete()
    }

    @Test
    fun `the production connection enforces foreign keys`() {
        val database = DatabaseFactory().open(directory.databaseFile)
        try {
            val pragma =
                runBlocking {
                    database.useReaderConnection { transactor ->
                        transactor.usePrepared("PRAGMA foreign_keys") { statement ->
                            check(statement.step()) { "PRAGMA foreign_keys returned no row" }
                            statement.getLong(0)
                        }
                    }
                }
            assertEquals(
                1L,
                pragma,
                "Foreign key enforcement changed. It is not the application that turns this on — the bundled " +
                    "driver does — so a change here comes from a dependency and must be a decision rather than " +
                    "a side effect: the backup restore in PLAN 14.4.3 orders its writes for an enforcing " +
                    "database.",
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `a row pointing at a game that is not there is refused where it is written`() {
        val database = DatabaseFactory().open(directory.databaseFile)
        try {
            runBlocking {
                try {
                    database.useWriterConnection { transactor ->
                        transactor.usePrepared(
                            "INSERT INTO game_cells (id, game_id, column_type, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?)",
                        ) { statement ->
                            statement.bindText(1, IdGenerator.Random.newId().toString())
                            statement.bindText(2, IdGenerator.Random.newId().toString())
                            statement.bindText(3, "THREE_D")
                            statement.bindLong(4, EPOCH_MILLISECONDS_CREATED)
                            statement.bindLong(5, EPOCH_MILLISECONDS_CREATED)
                            statement.step()
                        }
                    }
                    fail("storage accepted a cell belonging to a game that does not exist")
                } catch (refused: SQLiteException) {
                    assertTrue(
                        refused.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true),
                        "refused for some other reason: ${refused.message}",
                    )
                }

                // Refused means refused: nothing of the attempt is left behind.
                val cells =
                    database.useReaderConnection { transactor ->
                        transactor.usePrepared("SELECT COUNT(*) FROM game_cells") { statement ->
                            check(statement.step())
                            statement.getLong(0)
                        }
                    }
                assertEquals(0L, cells)
            }
        } finally {
            database.close()
        }
    }
}
