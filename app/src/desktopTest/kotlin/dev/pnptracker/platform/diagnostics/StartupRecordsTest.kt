package dev.pnptracker.platform.diagnostics

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CommittedSchema
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.insertVersion3ImportBatch
import dev.pnptracker.data.database.insertVersion3RawImportBlock
import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.domain.backup.automatic.StartupRefused
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticLevel
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.XdgAppPaths
import dev.pnptracker.platform.files.XdgAppPathsResolver
import dev.pnptracker.platform.settings.DesktopSettingsStore
import dev.pnptracker.platform.startup.DatabaseDamage
import dev.pnptracker.platform.startup.MigrationSnapshotSetWriter
import dev.pnptracker.platform.startup.StartupGate
import dev.pnptracker.platform.startup.deleteTemporaryTree
import dev.pnptracker.platform.startup.openWithHotWal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * What the start of the application records (PLAN 14.7.2).
 *
 * A migration that really ran, because it is one of the two successes worth
 * knowing the moment of, and a refusal that stopped the application before its
 * first screen. Both are driven through the real gate on a real old database;
 * only the home is temporary and the user's own database is never looked at.
 */
class StartupRecordsTest {
    private lateinit var home: Path
    private lateinit var paths: XdgAppPaths
    private val diagnostics = RecordingDiagnostics()
    private var realDatabaseExisted = false
    private val open = mutableListOf<SQLiteConnection>()
    private val databases = mutableListOf<AppDatabase>()
    private val temporaryRoots = mutableListOf<Path>()

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-startup-records")
        paths =
            XdgAppPathsResolver(
                environment = { name ->
                    when (name) {
                        "XDG_DATA_HOME" -> home.resolve("data").toString()
                        "XDG_CONFIG_HOME" -> home.resolve("config").toString()
                        "XDG_STATE_HOME" -> home.resolve("state").toString()
                        else -> null
                    }
                },
            ).resolve()
        AppDirectoryInitializer().ensureDirectories(paths)
    }

    @AfterTest
    fun deleteHome() {
        open.forEach { runCatching { it.close() } }
        databases.forEach { runCatching { it.close() } }
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the test changed whether the real application database exists",
        )
        temporaryRoots.forEach(::deleteTemporaryTree)
        deleteTemporaryTree(home)
    }

    private fun temporary(prefix: String): Path = Files.createTempDirectory(prefix).also(temporaryRoots::add)

    private fun gate() =
        StartupGate(
            paths = paths,
            databases = DatabaseFactory(),
            sets =
                MigrationSnapshotSetWriter(
                    backupsDirectory = paths.backupsDirectory,
                    reader = UntrustedBackupReader(TemporaryBackupProbe(temporaryDirectory = { temporary("pnp-tracker-records-probe") })),
                    documents = AtomicFileWriter(temporarySuffix = ".part"),
                    workingDirectory = { temporary("pnp-tracker-records-copy") },
                    moment = { LocalMoment(2026, 9, 16, 14, 33, 55) },
                ),
            housekeeping =
                SettingsDrivenHousekeeping(
                    settings = DesktopSettingsStore(paths.settingsFile),
                    rotation = AutomaticBackupRotation(DesktopBackupDirectory(paths.backupsDirectory)),
                ),
            diagnostics = diagnostics,
        )

    private fun assertNothingLeaked() =
        assertLinesCarryNothingOfTheUsers(diagnostics, "liste.xlsx", home.toString(), System.getProperty("user.name"))

    @Test
    fun `a migration that really ran says which version it came from and reached`() {
        open +=
            openWithHotWal(paths.databaseFile, version = 3) { connection ->
                val batchId = IdGenerator.Random.newId()
                insertVersion3ImportBatch(connection, batchId = batchId, fileName = "liste.xlsx")
                insertVersion3RawImportBlock(connection, blockId = IdGenerator.Random.newId(), batchId = batchId)
            }

        val opened = gate().open().also { databases += it.database }

        assertNotNull(opened.set, "the migration ran without a snapshot set")
        val record = diagnostics.only()
        assertRecordedAsPlanned(
            ExpectedRecord(DiagnosticEvent.MIGRATION_COMPLETED, level = DiagnosticLevel.INFO),
            record,
        )
        assertEquals(3, record.fromSchema)
        assertEquals(8, record.toSchema)
        assertNothingLeaked()
    }

    @Test
    fun `an ordinary start records nothing at all`() {
        gate()
            .open()
            .also { databases += it.database }
            .database
            .close()

        gate().open().also { databases += it.database }

        assertEquals(emptyList(), diagnostics.records.map { it.event.code })
    }

    @Test
    fun `a database this build cannot open`() {
        CommittedSchema.createDatabase(paths.databaseFile, version = 8)
        writeSchemaVersion(paths.databaseFile, 9)

        val refused = assertFailsWith<StartupRefused> { gate().open() }
        // The two lines the application runs when the gate refuses.
        diagnostics.recordSafely { startupRefusalRecord(refused) }

        assertEquals(StartupProblem.SCHEMA_TOO_NEW, refused.problem)
        assertRecordedAsPlanned(
            ExpectedRecord(
                DiagnosticEvent.STARTUP_REFUSED,
                level = DiagnosticLevel.ERROR,
                reason = StartupProblem.SCHEMA_TOO_NEW,
            ),
            diagnostics.only(),
        )
        assertNothingLeaked()
    }

    @Test
    fun `a file that is not a database at all keeps the refusal's classes and nothing else`() {
        Files.write(paths.databaseFile, "bu benim dosyam, veritabanı değil".encodeToByteArray())

        val refused = assertFailsWith<StartupRefused> { gate().open() }
        diagnostics.recordSafely { startupRefusalRecord(refused) }

        assertRecordedAsPlanned(
            ExpectedRecord(
                DiagnosticEvent.STARTUP_REFUSED,
                level = DiagnosticLevel.ERROR,
                reason = StartupProblem.DATABASE_NOT_READABLE,
                // The refusal carries the driver's own exception, which has no
                // cause of its own; only the class travels either way.
                exception = "androidx.sqlite.SQLiteException",
            ),
            diagnostics.only(),
        )
        assertLinesCarryNothingOfTheUsers(diagnostics, "bu benim dosyam", home.toString())
    }

    @Test
    fun `a damaged database is one refusal, one line, and nothing of the file`() {
        CommittedSchema.createDatabase(paths.databaseFile, version = 8)
        DatabaseDamage.TABLE_PAGE.applyTo(paths.databaseFile)

        val refused = assertFailsWith<StartupRefused> { gate().open() }
        diagnostics.recordSafely { startupRefusalRecord(refused) }

        assertEquals(StartupProblem.DATABASE_DAMAGED, refused.problem)
        assertRecordedAsPlanned(
            ExpectedRecord(
                DiagnosticEvent.STARTUP_REFUSED,
                level = DiagnosticLevel.ERROR,
                reason = StartupProblem.DATABASE_DAMAGED,
                // A scrambled table page stops quick_check with SQLite's own
                // exception (measured); its class travels, its words do not.
                exception = "androidx.sqlite.SQLiteException",
            ),
            diagnostics.only(),
        )
        // SQLite's own words name the damage — "malformed", pages, tables — and
        // stay out of the line, as the path and the file name do.
        assertLinesCarryNothingOfTheUsers(diagnostics, "malformed", "pnp.db", "tasks", "quick_check")
        assertNothingLeaked()
    }

    @Test
    fun `another copy already running is a warning rather than an error`() {
        diagnostics.recordSafely { startupRefusalRecord(StartupRefused(StartupProblem.ANOTHER_COPY_IS_RUNNING)) }

        assertRecordedAsPlanned(
            ExpectedRecord(
                DiagnosticEvent.STARTUP_REFUSED,
                level = DiagnosticLevel.WARN,
                reason = StartupProblem.ANOTHER_COPY_IS_RUNNING,
            ),
            diagnostics.only(),
        )
    }

    /** Sets `user_version` directly, which is the only way to make a future database. */
    private fun writeSchemaVersion(
        file: Path,
        version: Int,
    ) {
        val connection = BundledSQLiteDriver().open(file.toAbsolutePath().toString())
        try {
            connection.execSQL("PRAGMA user_version=$version")
        } finally {
            connection.close()
        }
    }
}
