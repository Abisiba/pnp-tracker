package dev.pnptracker.platform.startup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.execSQL
import java.nio.file.Files
import java.nio.file.Path

/**
 * The schema a database that has never been written to reports.
 *
 * SQLite answers `0` for an empty file as readily as for a file that is not
 * there at all, and Room reads that as "create everything from scratch". So it
 * is not a version to snapshot; there is nothing in it yet.
 */
const val NO_SCHEMA_YET: Int = 0

/**
 * Makes a consistent copy of a SQLite database without changing it.
 *
 * `VACUUM INTO` on a **read-only** connection, and both halves of that are
 * measured rather than assumed (PLAN 14.4.9 says so in as many words).
 * `ConsistentDatabaseCloneTest` is the measurement, and what it found is the
 * reason this file looks the way it does:
 *
 * ```text
 * read-only + VACUUM INTO   works, with a hot WAL, with or without a -shm
 * rows still in the WAL     land in the clone; the WAL is read, not ignored
 * user_version              preserved, in the header and through the PRAGMA
 * integrity_check           ok on the clone
 * source .db and -wal       byte for byte unchanged afterwards
 * read-WRITE for comparison changes the source: closing checkpoints the WAL
 *                           away and rewrites the database file
 * ```
 *
 * That last line is why the connection is read-only and not merely "careful".
 * An ordinary open would leave the user's database rewritten before anything had
 * even been backed up, which is precisely what this exists to prevent.
 *
 * Copying the database, `-wal` and `-shm` files one after another is forbidden
 * and is not done here: there is no atomicity between the three, and a
 * checkpoint landing in the middle of the copying leaves an inconsistent set
 * (PLAN 14.4.9).
 *
 * `SQLITE_OPEN_NOFOLLOW` is deliberately not asked for. Room opens the same file
 * without it, so refusing a symbolic link here would refuse a setup the rest of
 * the application accepts — and the path is the application's own, not one that
 * arrived from outside.
 */
class ConsistentDatabaseClone(
    /**
     * The one statement that does the copying.
     *
     * A seam for the same reason [dev.pnptracker.platform.files.AtomicFileWriter]
     * has three: what a snapshot does when the copy fails halfway is exactly the
     * behaviour worth testing, and there is no way to make a real `VACUUM INTO`
     * fail on demand.
     */
    private val vacuumInto: (SQLiteConnection, String) -> Unit = { connection, target ->
        // The path is this application's own and is built from a name it
        // generated, so the only quoting needed is SQL's own doubling of
        // apostrophes — and a path holding one is a path a person could really
        // have.
        connection.execSQL("VACUUM INTO '${target.replace("'", "''")}'")
    },
) {
    /**
     * The schema version of [databaseFile], read without Room.
     *
     * Read through SQLite rather than out of byte 60 of the header, because a
     * database with a hot WAL can have a newer page one waiting in the log; the
     * header alone would answer for a state that no longer exists. This is still
     * not Room opening the database: no migration can run, no schema is
     * validated and nothing is written (PLAN 14.4.10 step 2).
     *
     * @return the version, or [NO_SCHEMA_YET] when the file is absent or empty.
     * @throws SQLiteException if the file is there and is not a database.
     */
    fun schemaVersionOf(databaseFile: Path): Int {
        if (!Files.exists(databaseFile) || Files.size(databaseFile) == 0L) return NO_SCHEMA_YET
        return readOnly(databaseFile) { connection ->
            connection.prepare("PRAGMA user_version").use { statement ->
                check(statement.step()) { "PRAGMA user_version returned no row" }
                statement.getInt(0)
            }
        }
    }

    /**
     * Clones [databaseFile] into [target], which must not already exist.
     *
     * @throws SQLiteException if the source cannot be read or the clone cannot
     *   be written.
     */
    fun cloneInto(
        databaseFile: Path,
        target: Path,
    ) {
        readOnly(databaseFile) { connection ->
            vacuumInto(connection, target.toAbsolutePath().normalize().toString())
        }
    }

    /**
     * Every table in [databaseFile] and how many rows it holds.
     *
     * The completeness check for a clone, and it is schema-agnostic on purpose:
     * this runs over databases of seven different shapes and must not carry a
     * list of what any of them contains. Internal tables are left out because
     * `VACUUM` rebuilds them — a rewritten `sqlite_sequence` is the vacuum
     * working, not data going missing.
     */
    fun tableRowCounts(databaseFile: Path): Map<String, Long> =
        readOnly(databaseFile) { connection ->
            val tables = mutableListOf<String>()
            connection
                .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
                .use { statement -> while (statement.step()) tables += statement.getText(0) }
            tables.associateWith { table ->
                connection.prepare("SELECT COUNT(*) FROM \"${table.replace("\"", "\"\"")}\"").use { statement ->
                    check(statement.step()) { "COUNT(*) returned no row" }
                    statement.getLong(0)
                }
            }
        }

    /** True when SQLite finds nothing wrong with the file and no broken reference. */
    fun isWholeAndConsistent(databaseFile: Path): Boolean =
        readOnly(databaseFile) { connection ->
            val integrity =
                connection.prepare("PRAGMA integrity_check").use { statement ->
                    if (statement.step()) statement.getText(0) else "bilinmiyor"
                }
            if (integrity != "ok") return@readOnly false
            connection.prepare("PRAGMA foreign_key_check").use { statement -> !statement.step() }
        }

    private fun <T> readOnly(
        databaseFile: Path,
        use: (SQLiteConnection) -> T,
    ): T {
        val connection =
            BundledSQLiteDriver().open(databaseFile.toAbsolutePath().normalize().toString(), SQLITE_OPEN_READONLY)
        return try {
            use(connection)
        } finally {
            connection.close()
        }
    }
}
