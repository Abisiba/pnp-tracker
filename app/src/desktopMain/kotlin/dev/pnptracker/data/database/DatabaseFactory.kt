package dev.pnptracker.data.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.pnptracker.data.database.migration.Migration1To2
import dev.pnptracker.data.database.migration.Migration2To3
import dev.pnptracker.data.database.migration.Migration3To4
import dev.pnptracker.data.database.migration.Migration4To5
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
) {
    fun open(databaseFile: Path): AppDatabase =
        Room
            .databaseBuilder<AppDatabase>(name = databaseFile.toAbsolutePath().toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(queryCoroutineContext)
            .addMigrations(Migration1To2, Migration2To3, Migration3To4, Migration4To5)
            .addCallback(SeedColorsCallback)
            .build()
}
