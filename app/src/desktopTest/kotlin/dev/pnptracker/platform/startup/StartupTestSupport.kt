package dev.pnptracker.platform.startup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.execSQL
import dev.pnptracker.data.database.CommittedSchema
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertTrue

/** The three files a WAL database is, for the tests that watch all of them. */
fun sidecarsOf(databaseFile: Path): List<Path> = listOf(databaseFile, Path.of("$databaseFile-wal"), Path.of("$databaseFile-shm"))

/** A file's digest, or `-` when it is not there. */
fun digestOf(file: Path): String =
    if (!Files.exists(file)) {
        "-"
    } else {
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
    }

/**
 * Builds an old database and leaves rows sitting in an uncheckpointed WAL.
 *
 * The connection is handed back still open, because that is what keeps the WAL
 * hot: SQLite checkpoints and removes the log when the last connection closes,
 * so a test that closed it would be measuring the easy case.
 */
fun openWithHotWal(
    databaseFile: Path,
    version: Int,
    fill: (SQLiteConnection) -> Unit,
): SQLiteConnection {
    CommittedSchema.createDatabase(databaseFile, version)
    val connection = BundledSQLiteDriver().open(databaseFile.toAbsolutePath().toString())
    connection.execSQL("PRAGMA journal_mode=WAL")
    connection.execSQL("PRAGMA foreign_keys = ON")
    fill(connection)
    // Written again so that even a fixture with no rows of its own leaves a
    // frame in the log: what is being measured is a database whose newest page
    // is in the log rather than in the file, and an empty one has that too.
    connection.execSQL("PRAGMA user_version=$version")
    assertTrue(Files.exists(Path.of("$databaseFile-wal")), "the rows were written without a write-ahead log")
    return connection
}

/**
 * The three files as a crashed copy of themselves.
 *
 * Taken while the writer is idle, so what lands in [target] is a valid database
 * with a log nobody ever checkpointed — which is what a killed process leaves.
 * This is a way to *build a fixture*, not a way to take a snapshot: PLAN 14.4.9
 * forbids copying the three files as the snapshot mechanism precisely because
 * nothing guarantees they agree, and here the test is what guarantees it.
 */
fun crashedCopyOf(
    source: Path,
    target: Path,
    keepSharedMemory: Boolean = true,
) {
    Files.copy(source, target)
    Files.copy(Path.of("$source-wal"), Path.of("$target-wal"))
    val shm = Path.of("$source-shm")
    if (keepSharedMemory && Files.exists(shm)) Files.copy(shm, Path.of("$target-shm"))
}

/** Every table in the file and how many rows it holds, read without Room. */
fun rowCountsOf(databaseFile: Path): Map<String, Long> {
    val connection = BundledSQLiteDriver().open(databaseFile.toAbsolutePath().toString(), SQLITE_OPEN_READONLY)
    return try {
        val tables = mutableListOf<String>()
        connection
            .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .use { statement -> while (statement.step()) tables += statement.getText(0) }
        tables.associateWith { table ->
            connection.prepare("SELECT COUNT(*) FROM \"$table\"").use { statement ->
                statement.step()
                statement.getLong(0)
            }
        }
    } finally {
        connection.close()
    }
}

/** `PRAGMA user_version`, read without Room and without writing. */
fun schemaVersionOf(databaseFile: Path): Int {
    val connection = BundledSQLiteDriver().open(databaseFile.toAbsolutePath().toString(), SQLITE_OPEN_READONLY)
    return try {
        connection.prepare("PRAGMA user_version").use { statement ->
            statement.step()
            statement.getInt(0)
        }
    } finally {
        connection.close()
    }
}

/** Deletes one directory, after proving it is one a test made. */
fun deleteTemporaryTree(root: Path) {
    if (!Files.exists(root)) return
    val absolute = root.toAbsolutePath().normalize()
    val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
    check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
    Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}
