package dev.pnptracker.data.database

import androidx.room3.Room
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.pnptracker.data.database.migration.Migration1To2
import dev.pnptracker.data.database.migration.Migration2To3
import dev.pnptracker.data.database.migration.Migration3To4
import dev.pnptracker.data.database.migration.Migration4To5
import dev.pnptracker.data.database.migration.Migration5To6
import dev.pnptracker.data.database.migration.Migration6To7
import dev.pnptracker.data.database.migration.Migration7To8
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

/**
 * Opens the application database on this machine.
 *
 * The location always comes from the caller — production passes
 * `XdgAppPaths.databaseFile`, tests pass a file inside their own temporary
 * directory — so no path is baked into the code.
 */
class DatabaseFactory(
    private val queryCoroutineContext: CoroutineContext = Dispatchers.IO,
    /**
     * What actually talks to SQLite.
     *
     * Named for the same reason [queryCoroutineContext] is: production takes the
     * default and a test that needs to watch what the database is asked to do
     * hands in its own. The alternative — a test building its own Room instance —
     * would keep a second copy of the migration list, which is the one thing here
     * that must never drift.
     */
    private val driver: SQLiteDriver = BundledSQLiteDriver(),
) {
    fun open(databaseFile: Path): AppDatabase =
        Room
            .databaseBuilder<AppDatabase>(name = databaseFile.toAbsolutePath().toString())
            .setDriver(driver)
            .setQueryCoroutineContext(queryCoroutineContext)
            .addMigrations(Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6, Migration6To7, Migration7To8)
            .addCallback(SeedColorsCallback)
            .build()
}
