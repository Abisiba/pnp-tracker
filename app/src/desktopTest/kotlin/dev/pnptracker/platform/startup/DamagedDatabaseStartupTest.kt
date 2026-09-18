package dev.pnptracker.platform.startup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CommittedSchema
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.data.database.rowCount
import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.domain.backup.automatic.StartupRefused
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.XdgAppPaths
import dev.pnptracker.platform.files.XdgAppPathsResolver
import dev.pnptracker.platform.settings.DesktopSettingsStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A driver that lets nothing through: Room reaching it means the gate let a damaged file by. */
private class NoRoomAllowed : SQLiteDriver {
    var opens = 0
        private set

    override fun open(fileName: String): SQLiteConnection {
        opens++
        throw AssertionError("Room opened the database")
    }
}

/**
 * PLAN 14.7.4 through the real gate: a damaged database is refused before Room,
 * and nothing about it or beside it changes.
 *
 * Every damaged file here is made by this test, in a temporary XDG home, from a
 * database this test wrote. The user's own database is never looked at.
 */
class DamagedDatabaseStartupTest {
    private lateinit var home: Path
    private lateinit var paths: XdgAppPaths
    private var realDatabaseExisted = false
    private val open = mutableListOf<SQLiteConnection>()
    private val databases = mutableListOf<AppDatabase>()
    private val temporaryRoots = mutableListOf<Path>()

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-damaged-startup")
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

    private fun gate(
        driver: SQLiteDriver = BundledSQLiteDriver(),
        clone: ConsistentDatabaseClone = ConsistentDatabaseClone(),
    ) = StartupGate(
        paths = paths,
        databases = DatabaseFactory(driver = driver),
        sets =
            MigrationSnapshotSetWriter(
                backupsDirectory = paths.backupsDirectory,
                reader = UntrustedBackupReader(TemporaryBackupProbe(temporaryDirectory = { temporary("pnp-tracker-damaged-probe") })),
                documents = AtomicFileWriter(temporarySuffix = ".part"),
                workingDirectory = { temporary("pnp-tracker-damaged-copy") },
                moment = { LocalMoment(2026, 9, 18, 10, 0, 0) },
            ),
        housekeeping =
            SettingsDrivenHousekeeping(
                settings = DesktopSettingsStore(paths.settingsFile),
                rotation = AutomaticBackupRotation(DesktopBackupDirectory(paths.backupsDirectory)),
            ),
        clone = clone,
    )

    /** A version 8 database with something of every kind in it, closed and whole. */
    private fun aFullDatabase(file: Path) {
        val database = DatabaseFactory().open(file)
        try {
            runBlocking { fillWithEverything(database) }
        } finally {
            database.close()
        }
        check(Files.notExists(Path.of("$file-wal"))) { "the fixture was left in a log" }
    }

    /**
     * Every file in the temporary home with its digest — but the instance lock,
     * which is the gate's own empty file and is made by the first start.
     */
    private fun everythingOnDisk(): Map<String, String> =
        Files.walk(home).use { entries ->
            entries
                .filter { Files.isRegularFile(it) && it.fileName.toString() != INSTANCE_LOCK_NAME }
                .toList()
                .associate { home.relativize(it).toString() to digestOf(it) }
        }

    // ------------------------------------------------------------ healthy

    @Test
    fun `a healthy database of this schema opens as it always did`() {
        aFullDatabase(paths.databaseFile)

        val opened = gate().open().also { databases += it.database }

        assertNull(opened.set)
        assertEquals(3, runBlocking { rowCount(opened.database, "tasks") })
    }

    @Test
    fun `a healthy database whose newest rows are still in the log keeps them`() {
        // The rows are committed and sit only in the WAL, as a killed copy leaves them.
        val source = temporary("pnp-tracker-damaged-hot").resolve("pnp.db")
        val writer =
            openWithHotWal(source, version = 8) { connection ->
                connection.execSQL(
                    "INSERT INTO games (id, name, is_manually_completed, completed_at, created_at, updated_at, deleted_at, " +
                        "source_import_batch_id) VALUES ('0a000000-0000-4000-8000-000000000001', 'Günlükteki oyun', 0, NULL, 1, 1, NULL, NULL)",
                )
            }
        open += writer
        crashedCopyOf(source, paths.databaseFile)
        assertTrue(Files.size(Path.of("${paths.databaseFile}-wal")) > 0)

        val opened = gate().open().also { databases += it.database }

        assertEquals(1, runBlocking { opened.database.gameDao().activeCount() })
    }

    // ------------------------------------------------------------ damaged

    @Test
    fun `every damaged database whose version reads is refused before Room, and nothing changes`() {
        DatabaseDamage.entries.filter { it != DatabaseDamage.TRUNCATED_FILE }.forEach { damage ->
            aFullDatabase(paths.databaseFile)
            damage.applyTo(paths.databaseFile)
            val before = everythingOnDisk()
            val driver = NoRoomAllowed()

            // Twice: a second attempt meets the same answer and leaves the same disk.
            repeat(2) {
                val refused = assertFailsWith<StartupRefused>("$damage") { gate(driver = driver).open() }
                assertEquals(StartupProblem.DATABASE_DAMAGED, refused.problem, "$damage")
            }

            assertEquals(0, driver.opens, "$damage reached Room")
            assertEquals(before, everythingOnDisk(), "$damage: a refused start changed a file")
            assertEquals(emptyList(), namesIn(paths.backupsDirectory), "$damage: a refused start wrote a backup")
            sidecarsOf(paths.databaseFile).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `a truncated file whose version cannot be read is refused as unreadable, first`() {
        aFullDatabase(paths.databaseFile)
        DatabaseDamage.TRUNCATED_FILE.applyTo(paths.databaseFile)
        val before = everythingOnDisk()
        val driver = NoRoomAllowed()

        val refused = assertFailsWith<StartupRefused> { gate(driver = driver).open() }

        assertEquals(StartupProblem.DATABASE_NOT_READABLE, refused.problem)
        assertEquals(0, driver.opens)
        assertEquals(before, everythingOnDisk())
    }

    @Test
    fun `a damaged database with a hot log keeps the database, the log and the shared memory byte for byte`() {
        // A crashed copy whose log holds a newer page of one table and whose
        // file holds a scrambled page of another.
        val source = temporary("pnp-tracker-damaged-hot").resolve("pnp.db")
        aFullDatabase(source)
        DatabaseDamage.TABLE_PAGE.applyTo(source)
        val writer = BundledSQLiteDriver().open(source.toAbsolutePath().toString())
        open += writer
        writer.execSQL("UPDATE games SET name = name || ' ' WHERE deleted_at IS NULL")
        crashedCopyOf(source, paths.databaseFile)
        val sidecars = sidecarsOf(paths.databaseFile)
        assertTrue(sidecars.all { Files.exists(it) }, "the fixture is not a database with a hot log")
        val before = everythingOnDisk()

        val refused = assertFailsWith<StartupRefused> { gate(driver = NoRoomAllowed()).open() }

        assertEquals(StartupProblem.DATABASE_DAMAGED, refused.problem)
        assertEquals(before, everythingOnDisk(), "a refused start changed the database or one beside it")
    }

    @Test
    fun `a damaged old database is neither snapshotted nor migrated`() {
        CommittedSchema.createDatabase(paths.databaseFile, version = 3)
        DatabaseDamage.TABLE_PAGE.applyTo(paths.databaseFile)
        val before = everythingOnDisk()

        val refused = assertFailsWith<StartupRefused> { gate(driver = NoRoomAllowed()).open() }

        assertEquals(StartupProblem.DATABASE_DAMAGED, refused.problem)
        assertEquals(emptyList(), namesIn(paths.backupsDirectory), "a snapshot set was made of a damaged database")
        assertEquals(before, everythingOnDisk())
        assertEquals(listOf("3"), pragmaAnswerOf(paths.databaseFile, "user_version"))
    }

    @Test
    fun `the lock is given back after a damaged database is refused`() {
        aFullDatabase(paths.databaseFile)
        DatabaseDamage.INDEX_PAGE.applyTo(paths.databaseFile)
        assertFailsWith<StartupRefused> { gate().open() }

        // Whoever comes next finds the lock free.
        assertEquals("free", InstanceLock(paths.dataDirectory.resolve(INSTANCE_LOCK_NAME)).withLock { "free" })
    }

    @Test
    fun `a closed database is asked its version and checked without a file appearing beside it`() {
        aFullDatabase(paths.databaseFile)
        DatabaseDamage.FREELIST.applyTo(paths.databaseFile)
        assertTrue(Files.notExists(Path.of("${paths.databaseFile}-wal")))

        assertFailsWith<StartupRefused> { gate(driver = NoRoomAllowed()).open() }

        // A plain read-only connection would have left an empty log and an index here.
        sidecarsOf(paths.databaseFile).drop(1).forEach { assertTrue(Files.notExists(it), "$it appeared") }
    }

    @Test
    fun `a home whose path holds what a SQLite URI reads as syntax is still the database that is checked`() {
        val odd = home.resolve("veri ?#% ğüşİ")
        paths =
            XdgAppPathsResolver(
                environment = { name ->
                    when (name) {
                        "XDG_DATA_HOME" -> odd.toString()
                        "XDG_CONFIG_HOME" -> home.resolve("config").toString()
                        "XDG_STATE_HOME" -> home.resolve("state").toString()
                        else -> null
                    }
                },
            ).resolve()
        AppDirectoryInitializer().ensureDirectories(paths)
        aFullDatabase(paths.databaseFile)
        gate()
            .open()
            .also { databases += it.database }
            .database
            .close()

        DatabaseDamage.INDEX_PAGE.applyTo(paths.databaseFile)
        val refused = assertFailsWith<StartupRefused> { gate(driver = NoRoomAllowed()).open() }

        assertEquals(StartupProblem.DATABASE_DAMAGED, refused.problem)
    }

    // ------------------------------------------------------------ what is not damage

    @Test
    fun `only what SQLite says is damage, and a defect goes up as it is`() {
        aFullDatabase(paths.databaseFile)

        // SQLite's own refusal, and an answer other than ok, are damage.
        listOf<(SQLiteConnection) -> List<String>>(
            { throw SQLiteException("stand-in for a malformed page") },
            { listOf("*** in database main ***", "Page 7: btreeInitPage() returns error code 11") },
            { emptyList() },
        ).forEach { answer ->
            val refused = assertFailsWith<StartupRefused> { gate(clone = ConsistentDatabaseClone(quickCheck = answer)).open() }
            assertEquals(StartupProblem.DATABASE_DAMAGED, refused.problem)
        }

        // Anything else is not damage, is not turned into a refusal, and still
        // gives the lock back.
        listOf(
            IllegalStateException("a defect"),
            IllegalArgumentException("a defect"),
            CancellationException("cancelled"),
            OutOfMemoryError("an error"),
        ).forEach { defect ->
            val thrown = assertFailsWith<Throwable> { gate(clone = ConsistentDatabaseClone(quickCheck = { throw defect })).open() }
            assertSame(defect, thrown, "${defect::class.simpleName} was masked")
        }
        gate().open().also { databases += it.database }
    }

    private fun namesIn(folder: Path): List<String> =
        Files.list(folder).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }
}
