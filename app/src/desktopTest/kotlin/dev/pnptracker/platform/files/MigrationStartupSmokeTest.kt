package dev.pnptracker.platform.files

import androidx.sqlite.SQLiteConnection
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.insertVersion3DraftTask
import dev.pnptracker.data.database.insertVersion3ImportBatch
import dev.pnptracker.data.database.insertVersion3RawImportBlock
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.automatic.MigrationSnapshotSet
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.retention.AutomaticBackupKind
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.backup.retention.automaticBackupNameOf
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.time.localMomentOf
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.backupfiles.PathBackupInput
import dev.pnptracker.platform.settings.DesktopSettingsStore
import dev.pnptracker.platform.startup.MigrationSnapshotSetWriter
import dev.pnptracker.platform.startup.StartupGate
import dev.pnptracker.platform.startup.deleteTemporaryTree
import dev.pnptracker.platform.startup.openWithHotWal
import dev.pnptracker.platform.startup.rowCountsOf
import dev.pnptracker.platform.startup.schemaVersionOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * A machine that has been using an old version, started once.
 *
 * The last of the smoke tests in this package and the one that joins the whole
 * of job 4 together. A home of its own, an old database with rows still in its
 * log — which is what a copy that was killed rather than closed leaves — and
 * then one start through the very code `Main` runs.
 *
 * Nothing is substituted. The paths come from [XdgAppPathsResolver], the folders
 * from [AppDirectoryInitializer], the version is read without Room, the clone is
 * the read-only `VACUUM INTO`, the working copy is migrated by the real chain,
 * the document is written by the real atomic writer and read back by the real
 * untrusted reader with its throwaway database, retention is the real rotation
 * reading the real settings store, and the database is opened by the real
 * `DatabaseFactory`.
 *
 * What it is really for is the sentence a user would say: *I updated the
 * application, it started, my data is there, and there is a backup of what it
 * was before.*
 */
class MigrationStartupSmokeTest {
    private lateinit var home: Path
    private lateinit var paths: XdgAppPaths
    private var realDatabaseExisted = false
    private var oldConnection: SQLiteConnection? = null
    private var database: AppDatabase? = null
    private val temporaryRoots = mutableListOf<Path>()

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-migration-smoke")
        paths =
            XdgAppPathsResolver(
                environment = { name ->
                    when (name) {
                        "XDG_DATA_HOME" -> home.resolve("data").toString()
                        "XDG_CONFIG_HOME" -> home.resolve("config").toString()
                        else -> null
                    }
                },
            ).resolve()
        AppDirectoryInitializer().ensureDirectories(paths)
    }

    @AfterTest
    fun deleteHome() {
        oldConnection?.let { runCatching { it.close() } }
        database?.let { runCatching { it.close() } }
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the smoke changed whether the real application database exists",
        )
        temporaryRoots.forEach(::deleteTemporaryTree)
        deleteTemporaryTree(home)
    }

    @Test
    fun `an old database with a hot log is started, backed up and brought forward`() {
        // 1. A machine that was using version 3 and did not shut down tidily:
        // the import it made is in the log rather than in the database file.
        val batchId = IdGenerator.Random.newId()
        val blockId = IdGenerator.Random.newId()
        oldConnection =
            openWithHotWal(paths.databaseFile, version = 3) { connection ->
                insertVersion3ImportBatch(connection, batchId = batchId, fileName = "Kitap.xlsx")
                insertVersion3RawImportBlock(connection, blockId = blockId, batchId = batchId)
                insertVersion3DraftTask(connection, draftId = IdGenerator.Random.newId(), blockId = blockId)
            }
        assertTrue(Files.exists(Path.of("${paths.databaseFile}-wal")), "the fixture left no log to read")
        val wasOnDisk = rowCountsOf(paths.databaseFile)
        assertEquals(3, schemaVersionOf(paths.databaseFile))
        // The application is not running any more; only its files are left.
        oldConnection?.close()
        oldConnection = null

        // 2. One start, through the gate the application starts through.
        val opened = gate().open()
        database = opened.database
        val set = assertNotNull(opened.set, "an old database was brought forward with no snapshot")

        // 3. The pair is there, matched, and named the way rotation reads names.
        val left = namesIn(paths.backupsDirectory)
        assertEquals(listOf("${set.setName}.db", "${set.setName}.json").sorted(), left)
        left.forEach { name ->
            val read = assertNotNull(automaticBackupNameOf(name), name)
            assertEquals(AutomaticBackupKind.MIGRATION, read.kind)
            assertEquals(set.setName, read.setName)
        }

        // 4. The raw half is the old database, log and all, never migrated.
        val raw = paths.backupsDirectory.resolve("${set.setName}.db")
        assertEquals(3, schemaVersionOf(raw))
        assertEquals(wasOnDisk, rowCountsOf(raw))
        assertTrue("items" in rowCountsOf(raw).keys, "the raw half is not a version 3 database")

        // 5. The document half is an ordinary backup of the migrated data, and
        // it opens with the reader a person's own file goes through.
        val read = runBlocking { reader().read(PathBackupInput(paths.backupsDirectory.resolve("${set.setName}.json"))) }
        val valid = read as? BackupReadResult.Valid ?: error("the document did not read back as a backup: $read")
        assertEquals(8, valid.backup.sourceSchemaVersion)
        assertEquals(1, valid.backup.data.importBatches.size)
        assertEquals(
            "Kitap.xlsx",
            valid.backup.data.importBatches
                .single()
                .fileName,
        )
        assertEquals(1, valid.backup.data.draftTasks.size)

        // 6. And the live database is on this build's schema with the same rows.
        assertEquals(8, schemaVersionOf(paths.databaseFile))
        val live = runBlocking { BackupStore(assertNotNull(database)).snapshot() }
        assertEquals(8, live.sourceSchemaVersion)
        assertEquals(valid.backup.data, live.data, "the backup and the database disagree about what was migrated")

        // 7. Nothing temporary anywhere, and no settings file: reading a number
        // never creates one (PLAN 14.4.12).
        assertTrue(left.none { it.endsWith(".part") })
        assertTrue(Files.notExists(paths.settingsFile), "the startup created the settings file by itself")
        temporaryRoots.forEach { assertTrue(Files.notExists(it), "a working directory was left at $it") }
    }

    @Test
    fun `starting the same machine again changes nothing`() {
        oldConnection =
            openWithHotWal(paths.databaseFile, version = 3) { connection ->
                insertVersion3ImportBatch(connection, batchId = IdGenerator.Random.newId(), fileName = "Kitap.xlsx")
            }
        oldConnection?.close()
        oldConnection = null

        val first = gate().open()
        val set: MigrationSnapshotSet = assertNotNull(first.set)
        first.database.close()
        val afterFirst = namesIn(paths.backupsDirectory)

        // A second start, a second later, finds a database already on this
        // schema — so PLAN 14.4.10 step 4 applies and no second set is made.
        val second = gate(second = 56).open()
        database = second.database

        assertNull(second.set, "a database already brought forward was snapshotted again")
        assertEquals(afterFirst, namesIn(paths.backupsDirectory))
        assertEquals(listOf("${set.setName}.db", "${set.setName}.json").sorted(), afterFirst)
        assertEquals(8, schemaVersionOf(paths.databaseFile))
    }

    // ---------------------------------------------------------------- helpers

    private fun temporary(prefix: String): Path = Files.createTempDirectory(prefix).also(temporaryRoots::add)

    private fun reader() =
        UntrustedBackupReader(TemporaryBackupProbe(temporaryDirectory = { temporary("pnp-tracker-migration-smoke-probe") }))

    private fun gate(second: Int = 55) =
        StartupGate(
            paths = paths,
            databases = DatabaseFactory(),
            sets =
                MigrationSnapshotSetWriter(
                    backupsDirectory = paths.backupsDirectory,
                    reader = reader(),
                    workingDirectory = { temporary("pnp-tracker-migration-smoke-copy") },
                    moment = { localMomentOf(Clock.System.now()).copy(second = second) },
                ),
            housekeeping =
                SettingsDrivenHousekeeping(
                    settings = DesktopSettingsStore(paths.settingsFile),
                    rotation = AutomaticBackupRotation(DesktopBackupDirectory(paths.backupsDirectory)),
                ),
        )

    private fun namesIn(folder: Path): List<String> =
        Files.list(folder).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }
}
