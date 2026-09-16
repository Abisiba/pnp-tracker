package dev.pnptracker.platform.diagnostics

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.data.repository.GameTableStore
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.XdgAppPaths
import dev.pnptracker.platform.recovery.aPreparedImport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The failures that still get past every typed boundary, measured rather than
 * guessed at (PLAN 14.7.2, "tipsiz kaçan sınırlar").
 *
 * Nothing here is fixed: each test says what leaves the application today, from
 * which layer, and whether a safe record could be made at that point. The list
 * these produce is the whole of what the next slice may turn into typed Turkish
 * answers — an untyped failure nobody measured is not in it.
 *
 * Each case below is written as it is found, so the day one of them is given a
 * typed answer this test fails and has to be turned deliberately.
 */
class UntypedFailureMeasurementTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val failing = FailingSqliteDriver()
    private val diagnostics = RecordingDiagnostics()
    private var realDatabaseExistedBefore = false
    private val unwritable = mutableListOf<Path>()

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = failing).open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        failing.disarm()
        database.close()
        unwritable.forEach { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private fun plain(sql: String) =
        sql
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase()

    @Test
    fun `saving a draft import has no typed answer for storage refusing`() =
        runBlocking {
            failing.failOn { plain(it).startsWith("INSERT") && "IMPORT_BATCHES" in plain(it) }

            // Leaves ImportDraftStore as the driver's own exception. The import
            // screen catches only ImportPreparationException, so it travels out
            // of the controller and the screen stays on "saving".
            val escaped = assertFailsWith<SQLiteException> { ImportDraftStore(database.importDao()).save(aPreparedImport()) }

            failing.disarm()
            assertEquals("androidx.sqlite.SQLiteException", escaped::class.qualifiedName)
            assertEquals(emptyList(), diagnostics.records.map { it.event.code }, "an untyped escape was recorded as something")
        }

    @Test
    fun `the game table and the colour catalogue have no typed answer for a read that fails`() =
        runBlocking {
            insertGameCellAndTask(database)
            failing.failOn { plain(it).startsWith("SELECT") && "FROM GAMES" in plain(it) }

            val table =
                assertFailsWith<SQLiteException> {
                    GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao()).observeTable().first()
                }

            failing.disarm()
            failing.failOn { plain(it).startsWith("SELECT") && "FROM COLORS" in plain(it) }

            val colors = assertFailsWith<SQLiteException> { ColorCatalogueStore(database.colorDao()).observeColors().first() }

            failing.disarm()
            assertEquals("androidx.sqlite.SQLiteException", table::class.qualifiedName)
            assertEquals("androidx.sqlite.SQLiteException", colors::class.qualifiedName)
            assertEquals(emptyList(), diagnostics.records.map { it.event.code })
        }

    @Test
    fun `a folder the application cannot make stops it before the writer exists`() {
        val locked = Files.createDirectory(directory.root.resolve("kilitli"))
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"))
        unwritable.add(locked)
        val paths =
            XdgAppPaths(
                dataDirectory = locked.resolve("data/pnp-tracker"),
                databaseFile = locked.resolve("data/pnp-tracker/pnp.db"),
                backupsDirectory = locked.resolve("data/pnp-tracker/backups"),
                configDirectory = locked.resolve("config/pnp-tracker"),
                settingsFile = locked.resolve("config/pnp-tracker/settings.json"),
                stateDirectory = locked.resolve("state/pnp-tracker"),
                logsDirectory = locked.resolve("state/pnp-tracker/logs"),
            )

        val escaped = assertFailsWith<IOException> { AppDirectoryInitializer().ensureDirectories(paths) }

        // Measured and left alone: this happens before the application has a
        // window or a log, and the message it carries is an absolute path — which
        // is exactly why no record is made of it here.
        assertEquals("java.io.IOException", escaped::class.qualifiedName)
        assertTrue(locked.toString() in escaped.message.orEmpty(), "the message stopped carrying the path this measured")
        assertEquals(emptyList(), diagnostics.records.map { it.event.code })
    }
}
