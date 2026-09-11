package dev.pnptracker.platform.settings

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.LiveBackupRestorer
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.data.repository.ImportConfirmationStore
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.backupDocumentOf
import dev.pnptracker.domain.backup.restore.SAFETY_BACKUP_PREFIX
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.restore.anEmptyBackup
import dev.pnptracker.domain.backup.restore.safetyBackupFileName
import dev.pnptracker.domain.backup.retention.AutomaticBackupHousekeeping
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.BackupDirectory
import dev.pnptracker.domain.backup.retention.IMPORT_SNAPSHOT_PREFIX
import dev.pnptracker.domain.backup.retention.InspectedBackupFile
import dev.pnptracker.domain.backup.retention.MIGRATION_SNAPSHOT_PREFIX
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.backup.retention.importSnapshotFileName
import dev.pnptracker.domain.backup.retention.migrationSnapshotSetName
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.backupfiles.BackupSourcePicker
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.backupfiles.DesktopBackupSourceGateway
import dev.pnptracker.platform.backupfiles.DesktopSafetyBackupWriter
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.XdgAppPaths
import dev.pnptracker.platform.files.XdgAppPathsResolver
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationController
import dev.pnptracker.ui.feature.settings.RestoreController
import dev.pnptracker.ui.feature.settings.RestoreScreenState
import dev.pnptracker.ui.feature.settings.RetentionController
import dev.pnptracker.ui.feature.settings.RetentionScreenState
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private val WRITTEN_AT = Instant.fromEpochMilliseconds(1_757_320_364_031)

/** The open dialog, standing in for the one a person would click through. */
private class ChosenFile(
    private val file: Path,
) : BackupSourcePicker {
    override suspend fun chooseSource(): Path = file
}

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
 * The setting, the restore and the housekeeping, joined up on a real disk.
 *
 * Each of the three has its own tests. What only this can show is the join:
 * that a number somebody saves is the number a later restore actually clears to,
 * that saving it removes nothing at the time, and that a folder which refuses to
 * give up its old files costs the restore nothing at all.
 *
 * A temporary home throughout, and a database made for the test. The real one is
 * never opened; the assertion in [checkNothingReal] is the same one every
 * database test carries.
 */
class RetentionAfterRestoreTest {
    private lateinit var home: Path
    private lateinit var paths: XdgAppPaths
    private var realDatabaseExisted = false
    private var database: AppDatabase? = null
    private val probeRoots = mutableListOf<Path>()

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-retention-restore")
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
    fun checkNothingReal() {
        database?.close()
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the test changed whether the real application database exists",
        )
        // Listed separately rather than as `probeRoots + home`: a Path is
        // itself an Iterable of its own name elements, so that expression
        // appends `tmp` and the folder's name as two relative paths, neither of
        // which exists — and the real directory is quietly never deleted.
        probeRoots.forEach(::deleteTree)
        deleteTree(home)
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        val absolute = root.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
        Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `saving a smaller number removes nothing, and the next restore applies it`() =
        runBlocking<Unit> {
            // 1. A folder with more of every kind than the number about to be
            // chosen, plus files that are nobody's business but the user's.
            val standing = fillTheFolder()

            // 2. Somebody chooses two. PLAN 14.4.12: this is not a destructive
            // act, and nothing on disk moves because of it.
            val settings = DesktopSettingsStore(paths.settingsFile)
            val retention = RetentionController(settings)
            retention.load()
            assertEquals(7, retention.state.countInUse, "the default stands until somebody chooses")
            retention.type("2")
            retention.save()

            assertEquals(RetentionScreenState.Ready(2, problem = null, justSaved = true), retention.state)
            assertEquals("""{"formatVersion":1,"automaticBackupCount":2}""", Files.readString(paths.settingsFile))
            assertEquals(standing, namesIn(paths.backupsDirectory), "saving a number deleted files there and then")

            // 3. A real restore. Its safety backup is the automatic backup that
            // retention then applies to.
            val database = openDatabase()
            val backupFile = paths.backupsDirectory.resolve("pnp-yedek-2026-09-08.json")
            AtomicFileWriter(temporarySuffix = ".json.part").write(backupFile, aDocumentOf(database))
            val restore = restoreController(backupFile, database, DesktopBackupDirectory(paths.backupsDirectory))

            restore.chooseBackup()
            restore.confirmRestore()

            val restored = restore.state as RestoreScreenState.Restored
            assertTrue(restored.safetyFileName.startsWith(SAFETY_BACKUP_PREFIX), restored.safetyFileName)

            // 4. Two of each kind left — the number that was saved, applied to
            // each kind on its own.
            val left = namesIn(paths.backupsDirectory)
            assertEquals(2, left.count { it.startsWith(SAFETY_BACKUP_PREFIX) }, left.toString())
            assertEquals(2, left.count { it.startsWith(IMPORT_SNAPSHOT_PREFIX) }, left.toString())
            // Two sets, which is four files.
            assertEquals(4, left.count { it.startsWith(MIGRATION_SNAPSHOT_PREFIX) }, left.toString())
            // And the safety backup the restore just wrote is one of the two
            // kept, because it is the newest of its kind.
            assertTrue(restored.safetyFileName in left, "the restore cleared away its own way back")

            // 5. Nothing of the user's was touched.
            assertTrue("pnp-yedek-2026-09-08.json" in left, "the manual backup was removed")
            assertTrue("pnp-yedek-2026-09-01.json" in left, "the manual backup was removed")
            assertTrue("okubeni.txt" in left, "somebody's own file was removed")
        }

    @Test
    fun `a folder that will not give up its old files costs the restore nothing`() =
        runBlocking<Unit> {
            // PLAN 14.4.13: clearing up is fail open. The restore has already
            // happened and the safety backup is already on disk, so a deletion
            // that will not go through changes neither.
            fillTheFolder()
            val database = openDatabase()
            val backupFile = paths.backupsDirectory.resolve("pnp-yedek-2026-09-08.json")
            AtomicFileWriter(temporarySuffix = ".json.part").write(backupFile, aDocumentOf(database))
            val stubborn = NeverDeletes(DesktopBackupDirectory(paths.backupsDirectory))
            val restore = restoreController(backupFile, database, stubborn)
            val before = namesIn(paths.backupsDirectory)

            restore.chooseBackup()
            restore.confirmRestore()

            val restored = restore.state as RestoreScreenState.Restored
            assertTrue(stubborn.refusals > 0, "nothing was even tried, so this proves nothing")
            assertTrue(Files.exists(paths.backupsDirectory.resolve(restored.safetyFileName)), "the way back was lost")
            // Everything that was there is still there, and one more beside it.
            assertTrue(namesIn(paths.backupsDirectory).containsAll(before))
            assertEquals(before.size + 1, namesIn(paths.backupsDirectory).size)
        }

    @Test
    fun `a restore on a machine that has chosen nothing keeps the default number`() =
        runBlocking<Unit> {
            fillTheFolder()
            val database = openDatabase()
            val backupFile = paths.backupsDirectory.resolve("pnp-yedek-2026-09-08.json")
            AtomicFileWriter(temporarySuffix = ".json.part").write(backupFile, aDocumentOf(database))
            val restore = restoreController(backupFile, database, DesktopBackupDirectory(paths.backupsDirectory))

            restore.chooseBackup()
            restore.confirmRestore()

            assertTrue(restore.state is RestoreScreenState.Restored)
            // Nine safety backups were there and one more was written; the
            // default keeps seven.
            assertEquals(7, namesIn(paths.backupsDirectory).count { it.startsWith(SAFETY_BACKUP_PREFIX) })
            assertTrue(Files.notExists(paths.settingsFile), "a restore created the settings file by itself")
        }

    @Test
    fun `opening a database still writes no migration snapshot`() =
        runBlocking<Unit> {
            // PLAN 14.4.9 and 14.4.10 are Dilim 4's work, and this is what says
            // so in a way that cannot quietly stop being true: a real database
            // is created and opened through the real factory, migrations and
            // seed and all, and the backups folder is exactly as it was.
            val before = namesIn(paths.backupsDirectory)

            openDatabase()

            assertEquals(before, namesIn(paths.backupsDirectory), "opening a database wrote a migration snapshot")
            assertTrue(namesIn(paths.backupsDirectory).none { it.startsWith(MIGRATION_SNAPSHOT_PREFIX) })
            assertTrue(Files.notExists(paths.settingsFile))
        }

    @Test
    fun `nothing but a restore has been given housekeeping to do`() {
        // The other half of the same claim, and the half a folder cannot show:
        // that the import confirmation and the database factory have not been
        // handed the collaborator at all. PLAN 14.4.8 and 14.4.9 are the next
        // two slices, and when either lands this is the test that has to be
        // changed on purpose rather than discovered to have gone quiet.
        // Plain JVM reflection rather than Kotlin's: kotlin-reflect is not a
        // dependency of this project and PLAN 14.1 does not add one for a test.
        val housekeeping = AutomaticBackupHousekeeping::class.java

        listOf(ImportConfirmationStore::class.java, ImportConfirmationController::class.java, DatabaseFactory::class.java)
            .forEach { owner ->
                val takesIt = owner.constructors.any { made -> made.parameterTypes.any { it == housekeeping } }
                assertFalse(takesIt, "${'$'}{owner.simpleName} has been given housekeeping before its slice")
            }

        // And the one that has been.
        assertTrue(
            RestoreController::class.java.constructors.any { made -> made.parameterTypes.any { it == housekeeping } },
            "the restore lost its housekeeping",
        )
    }

    /**
     * Nine of each kind, plus files the application must never touch.
     *
     * @return every name in the folder afterwards.
     */
    private fun fillTheFolder(): List<String> {
        val writer = AtomicFileWriter(temporarySuffix = ".json.part")
        val document =
            backupDocumentOf(anEmptyBackup(), AppInfo.Current.version, sourceSchemaVersion = 8, createdAt = WRITTEN_AT)
                .json
                .encodeToByteArray()
        (1..9).forEach { minute ->
            val moment = LocalMoment(2026, 9, 9, 10, minute, 0)
            writer.write(paths.backupsDirectory.resolve(safetyBackupFileName(moment)), document)
            writer.write(paths.backupsDirectory.resolve(importSnapshotFileName(moment)), document)
            val setName = migrationSnapshotSetName(fromSchemaVersion = 3, toSchemaVersion = 8, moment = moment)
            writer.write(paths.backupsDirectory.resolve("$setName.json"), document)
            writer.write(paths.backupsDirectory.resolve("$setName.db"), aDatabaseOfVersion(3))
        }
        writer.write(paths.backupsDirectory.resolve("pnp-yedek-2026-09-01.json"), document)
        Files.write(paths.backupsDirectory.resolve("okubeni.txt"), "bunlar benim".encodeToByteArray())
        return namesIn(paths.backupsDirectory)
    }

    private fun openDatabase(): AppDatabase =
        database ?: DatabaseFactory().open(paths.databaseFile).also {
            database = it
            runBlocking {
                it.gameDao().activeCount()
                fillWithEverything(it)
            }
        }

    private suspend fun aDocumentOf(database: AppDatabase): ByteArray =
        DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System).backupDocument().json.encodeToByteArray()

    private fun restoreController(
        file: Path,
        database: AppDatabase,
        directory: BackupDirectory,
    ) = RestoreController(
        sources = DesktopBackupSourceGateway(ChosenFile(file)),
        reader =
            UntrustedBackupReader(
                TemporaryBackupProbe(
                    temporaryDirectory = {
                        Files.createTempDirectory("pnp-tracker-retention-restore-probe").also { probeRoots.add(it) }
                    },
                ),
            ),
        exporter = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System),
        safety = DesktopSafetyBackupWriter(paths.backupsDirectory),
        restorer = LiveBackupRestorer(database),
        housekeeping =
            SettingsDrivenHousekeeping(
                settings = DesktopSettingsStore(paths.settingsFile),
                rotation = AutomaticBackupRotation(directory),
            ),
        clock = Clock.System,
    )

    private fun namesIn(folder: Path): List<String> =
        Files.list(folder).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }

    /** A plausible first page of a SQLite database still on [userVersion]. */
    private fun aDatabaseOfVersion(userVersion: Int): ByteArray {
        val page = ByteArray(4096)
        "SQLite format 3".encodeToByteArray().copyInto(page)
        page[16] = 0x10
        page[63] = userVersion.toByte()
        return page
    }
}
