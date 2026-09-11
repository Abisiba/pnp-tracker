package dev.pnptracker.platform.startup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CommittedSchema
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.insertVersion3ImportBatch
import dev.pnptracker.data.database.insertVersion3RawImportBlock
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.domain.backup.automatic.StartupRefused
import dev.pnptracker.domain.backup.backupDocumentOf
import dev.pnptracker.domain.backup.restore.SAFETY_BACKUP_PREFIX
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.restore.anEmptyBackup
import dev.pnptracker.domain.backup.restore.safetyBackupFileName
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.BackupDirectory
import dev.pnptracker.domain.backup.retention.InspectedBackupFile
import dev.pnptracker.domain.backup.retention.MIGRATION_SNAPSHOT_PREFIX
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.backup.retention.migrationSnapshotSetName
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.AtomicWriteException
import dev.pnptracker.platform.files.AtomicWriteFailure
import dev.pnptracker.platform.files.XdgAppPaths
import dev.pnptracker.platform.files.XdgAppPathsResolver
import dev.pnptracker.platform.settings.DesktopSettingsStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private val MOMENT = Instant.fromEpochMilliseconds(1_780_000_000_000)

/** A folder whose deletions all fail, to prove what that does and does not cost. */
private class NeverDeletes(
    private val real: BackupDirectory,
) : BackupDirectory {
    var refusals = 0
        private set

    override suspend fun inspect(): List<InspectedBackupFile> = real.inspect()

    override suspend fun remove(fileName: String): Boolean {
        refusals++
        return false
    }
}

/**
 * The gate PLAN 14.4.10 puts in front of every start.
 *
 * Almost everything here is about one sentence: a database on an older schema is
 * never migrated until a matched pair of artefacts has been written and proved.
 * The tests come at it from both sides — the path where the set succeeds and the
 * migration goes ahead, and the four ways the set can fail, each of which has to
 * leave the database on the version it was on.
 *
 * Everything is production: the real clone, the real set writer with its real
 * migration chain, the real reader with its throwaway database, the real
 * rotation, the real settings store, and the real `DatabaseFactory`. Only the
 * home is temporary, and the user's own database is never looked at.
 */
class StartupGateTest {
    private lateinit var home: Path
    private lateinit var paths: XdgAppPaths
    private var realDatabaseExisted = false
    private val open = mutableListOf<SQLiteConnection>()
    private val databases = mutableListOf<AppDatabase>()
    private val temporaryRoots = mutableListOf<Path>()

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-startup-gate")
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

    // ------------------------------------------------------- what is wired up

    private fun temporary(prefix: String): Path = Files.createTempDirectory(prefix).also(temporaryRoots::add)

    private fun setWriter(
        databases: DatabaseFactory = DatabaseFactory(),
        documents: AtomicFileWriter = AtomicFileWriter(temporarySuffix = ".part"),
        second: Int = 55,
    ) = MigrationSnapshotSetWriter(
        backupsDirectory = paths.backupsDirectory,
        databases = databases,
        reader = UntrustedBackupReader(TemporaryBackupProbe(temporaryDirectory = { temporary("pnp-tracker-gate-probe") })),
        documents = documents,
        workingDirectory = { temporary("pnp-tracker-gate-copy") },
        moment = { LocalMoment(2026, 9, 11, 14, 33, second) },
    )

    private fun gate(
        real: DatabaseFactory = DatabaseFactory(),
        sets: MigrationSnapshotSetWriter = setWriter(),
        directory: BackupDirectory = DesktopBackupDirectory(paths.backupsDirectory),
    ) = StartupGate(
        paths = paths,
        databases = real,
        sets = sets,
        housekeeping =
            SettingsDrivenHousekeeping(
                settings = DesktopSettingsStore(paths.settingsFile),
                rotation = AutomaticBackupRotation(directory),
            ),
    )

    private fun openThrough(gate: StartupGate): OpenedDatabase = gate.open().also { databases += it.database }

    /** A version 3 database with an import trail, all of it still in the log. */
    private fun aVersion3Database() {
        open +=
            openWithHotWal(paths.databaseFile, version = 3) { connection ->
                val batchId = IdGenerator.Random.newId()
                insertVersion3ImportBatch(connection, batchId = batchId, fileName = "liste.xlsx")
                insertVersion3RawImportBlock(connection, blockId = IdGenerator.Random.newId(), batchId = batchId)
            }
    }

    private fun namesIn(folder: Path): List<String> =
        Files.list(folder).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }

    private fun setsIn(): List<String> = namesIn(paths.backupsDirectory).filter { it.startsWith(MIGRATION_SNAPSHOT_PREFIX) }

    // ---------------------------------------------------- when nothing is due

    @Test
    fun `a machine with no database gets one, and no snapshot`() {
        val opened = openThrough(gate())

        assertNull(opened.set, "a new database was given a migration snapshot")
        assertEquals(emptyList(), namesIn(paths.backupsDirectory))
        assertEquals(8, runBlocking { BackupStore(opened.database).snapshot().sourceSchemaVersion })
        // A new database is seeded, which is the ordinary creation path PLAN
        // 14.4.10 step 3 sends it down.
        assertEquals(
            12,
            runBlocking {
                BackupStore(opened.database)
                    .snapshot()
                    .data.colors.size
            },
        )
    }

    @Test
    fun `a database already on this schema is opened with no snapshot`() {
        openThrough(gate()).database.close()

        val opened = openThrough(gate())

        assertNull(opened.set)
        assertEquals(emptyList(), namesIn(paths.backupsDirectory))
    }

    // --------------------------------------------------------- the migration

    @Test
    fun `an old database is migrated behind a matched pair`() {
        aVersion3Database()
        val before = rowCountsOf(paths.databaseFile)

        val opened = openThrough(gate())

        val set = assertNotNull(opened.set, "an old database was migrated with no snapshot")
        assertEquals(3, set.fromSchemaVersion)
        assertEquals(8, set.toSchemaVersion)
        assertEquals(listOf("${set.setName}.db", "${set.setName}.json").sorted(), setsIn())
        // The raw half is the database as it was; the live one has moved on.
        assertEquals(3, schemaVersionOf(paths.backupsDirectory.resolve("${set.setName}.db")))
        assertEquals(before, rowCountsOf(paths.backupsDirectory.resolve("${set.setName}.db")))
        assertEquals(8, schemaVersionOf(paths.databaseFile))
        // And the user's rows came through the chain rather than being lost.
        val live = runBlocking { BackupStore(opened.database).snapshot() }
        assertEquals(8, live.sourceSchemaVersion)
        assertEquals(1, live.data.importBatches.size)
        assertEquals(
            "liste.xlsx",
            live.data.importBatches
                .single()
                .fileName,
        )
    }

    @Test
    fun `starting again after a successful migration writes no second set`() {
        aVersion3Database()
        val first = openThrough(gate())
        val set = assertNotNull(first.set)
        first.database.close()
        open.forEach { it.close() }
        open.clear()

        val second = openThrough(gate(sets = setWriter(second = 56)))

        assertNull(second.set, "a database already migrated was snapshotted again")
        assertEquals(listOf("${set.setName}.db", "${set.setName}.json").sorted(), setsIn())
    }

    // ------------------------------------------------------------ fail closed

    @Test
    fun `a set that cannot be written leaves the database on its old version`() {
        aVersion3Database()
        val willNotWrite =
            AtomicFileWriter(
                temporarySuffix = ".part",
                writeBytes = { _, _ -> throw AtomicWriteException(AtomicWriteFailure.WRITE_FAILED) },
            )

        val refused = assertFailsWith<StartupRefused> { gate(sets = setWriter(documents = willNotWrite)).open() }

        assertEquals(StartupProblem.SNAPSHOT_NOT_WRITTEN, refused.problem)
        // The one thing that matters: nothing migrated it.
        assertEquals(3, schemaVersionOf(paths.databaseFile))
        assertTrue(setsIn().none { it.endsWith(".json") })
    }

    @Test
    fun `a working copy that will not migrate leaves the database on its old version`() {
        aVersion3Database()
        val driver = FailingSqliteDriver()
        driver.failOn { "CREATE TABLE" in it.uppercase() }

        val refused =
            assertFailsWith<StartupRefused> { gate(sets = setWriter(databases = DatabaseFactory(driver = driver))).open() }

        assertEquals(StartupProblem.SNAPSHOT_NOT_MIGRATED, refused.problem)
        assertEquals(3, schemaVersionOf(paths.databaseFile))
    }

    @Test
    fun `a database from a newer version is not opened at all`() {
        CommittedSchema.createDatabase(paths.databaseFile, version = 8)
        writeSchemaVersion(paths.databaseFile, 9)
        val before = digestOf(paths.databaseFile)

        val refused = assertFailsWith<StartupRefused> { gate().open() }

        assertEquals(StartupProblem.SCHEMA_TOO_NEW, refused.problem)
        assertEquals(before, digestOf(paths.databaseFile), "a database from the future was written to")
        assertEquals(emptyList(), namesIn(paths.backupsDirectory))
    }

    @Test
    fun `a file that is not a database is not opened at all`() {
        Files.write(paths.databaseFile, "bu benim dosyam, veritabanı değil".encodeToByteArray())

        val refused = assertFailsWith<StartupRefused> { gate().open() }

        assertEquals(StartupProblem.DATABASE_NOT_READABLE, refused.problem)
        assertEquals("bu benim dosyam, veritabanı değil", Files.readString(paths.databaseFile))
    }

    @Test
    fun `a real migration that fails afterwards keeps the set it was given`() {
        aVersion3Database()
        // The set is built by a healthy factory; the real open is given one that
        // refuses. This is PLAN 14.4.10's last paragraph exactly: the copy
        // migrated and the original did not.
        val driver = FailingSqliteDriver()
        driver.failOn { "CREATE TABLE" in it.uppercase() }

        val refused = assertFailsWith<StartupRefused> { gate(real = DatabaseFactory(driver = driver)).open() }

        assertEquals(StartupProblem.MIGRATION_FAILED, refused.problem)
        assertEquals(2, setsIn().size, "the set was cleared away by the failure it exists for")
        assertTrue(setsIn().any { it.endsWith(".db") } && setsIn().any { it.endsWith(".json") })
    }

    // --------------------------------------------------------------- rotation

    @Test
    fun `the number in the settings file is applied to migration sets`() {
        Files.write(paths.settingsFile, """{"formatVersion":1,"automaticBackupCount":2}""".encodeToByteArray())
        val standing = fillTheFolder()
        aVersion3Database()

        val opened = openThrough(gate())

        assertNotNull(opened.set)
        // Three sets were standing and one was written; two of each kind stay.
        assertEquals(4, setsIn().size, setsIn().toString())
        assertEquals(2, namesIn(paths.backupsDirectory).count { it.startsWith(SAFETY_BACKUP_PREFIX) })
        assertTrue("pnp-yedek-2026-09-08.json" in namesIn(paths.backupsDirectory), "a manual backup was removed")
        assertTrue("okubeni.txt" in namesIn(paths.backupsDirectory), "somebody's own file was removed")
        assertTrue(standing.isNotEmpty())
    }

    @Test
    fun `a folder that will not give up its old sets costs the startup nothing`() {
        Files.write(paths.settingsFile, """{"formatVersion":1,"automaticBackupCount":1}""".encodeToByteArray())
        fillTheFolder()
        aVersion3Database()
        val stubborn = NeverDeletes(DesktopBackupDirectory(paths.backupsDirectory))

        val opened = openThrough(gate(directory = stubborn))

        assertNotNull(opened.set, "a refused deletion took the migration with it")
        assertEquals(8, schemaVersionOf(paths.databaseFile))
        assertTrue(stubborn.refusals > 0, "nothing was even tried, so this proves nothing")
    }

    // ------------------------------------------------------- the instance lock

    @Test
    fun `a second copy of the application does not open the database`() {
        aVersion3Database()
        val holder = startALockHolder()
        try {
            val refused = assertFailsWith<StartupRefused> { gate().open() }

            assertEquals(StartupProblem.ANOTHER_COPY_IS_RUNNING, refused.problem)
            // And it did not get as far as looking at the database, let alone
            // migrating it.
            assertEquals(3, schemaVersionOf(paths.databaseFile))
            assertEquals(emptyList(), setsIn())
        } finally {
            holder.destroyForcibly().waitFor(20, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `a copy that died without releasing the lock leaves it free`() {
        // The whole of the stale-lock question. The holder is killed rather than
        // asked to stop, so nothing releases the lock in user code; the operating
        // system does, and the next start simply works.
        aVersion3Database()
        val holder = startALockHolder()
        assertFailsWith<StartupRefused> { gate().open() }
        holder.destroyForcibly().waitFor(20, TimeUnit.SECONDS)

        val opened = openThrough(gate())

        assertNotNull(opened.set)
        assertEquals(8, schemaVersionOf(paths.databaseFile))
    }

    @Test
    fun `the lock is given back after a startup that succeeded and one that failed`() {
        Files.write(paths.databaseFile, "veritabanı değil".encodeToByteArray())
        assertFailsWith<StartupRefused> { gate().open() }
        Files.delete(paths.databaseFile)

        // A second attempt in the same process would find the lock still held if
        // the first two had not given it back.
        val opened = openThrough(gate())
        opened.database.close()
        openThrough(gate()).database.close()

        assertTrue(Files.exists(paths.dataDirectory.resolve(INSTANCE_LOCK_NAME)), "the lock file was deleted")
    }

    // ---------------------------------------------------------------- helpers

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

    private fun startALockHolder(): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process =
            ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                "dev.pnptracker.platform.startup.LockHolderKt",
                paths.dataDirectory.resolve(INSTANCE_LOCK_NAME).toString(),
            ).redirectErrorStream(true).start()
        val said = process.inputStream.bufferedReader().readLine()
        check(said == "LOCKED") { "the second copy did not take the lock: $said" }
        return process
    }

    /** Three migration sets, three safety backups, a manual backup and a note. */
    private fun fillTheFolder(): List<String> {
        val writer = AtomicFileWriter(temporarySuffix = ".json.part")
        val document =
            backupDocumentOf(anEmptyBackup(), AppInfo.Current.version, sourceSchemaVersion = 8, createdAt = MOMENT)
                .json
                .encodeToByteArray()
        (1..3).forEach { minute ->
            val moment = LocalMoment(2026, 9, 9, 10, minute, 0)
            writer.write(paths.backupsDirectory.resolve(safetyBackupFileName(moment)), document)
            val setName = migrationSnapshotSetName(fromSchemaVersion = 3, toSchemaVersion = 8, moment = moment)
            writer.write(paths.backupsDirectory.resolve("$setName.json"), document)
            writer.write(paths.backupsDirectory.resolve("$setName.db"), aDatabaseOfVersion(3))
        }
        writer.write(paths.backupsDirectory.resolve("pnp-yedek-2026-09-08.json"), document)
        Files.write(paths.backupsDirectory.resolve("okubeni.txt"), "bunlar benim".encodeToByteArray())
        return namesIn(paths.backupsDirectory)
    }

    /** A plausible first page of a SQLite database still on [userVersion]. */
    private fun aDatabaseOfVersion(userVersion: Int): ByteArray {
        val page = ByteArray(4096)
        "SQLite format 3".encodeToByteArray().copyInto(page)
        page[16] = 0x10
        page[63] = userVersion.toByte()
        return page
    }
}
