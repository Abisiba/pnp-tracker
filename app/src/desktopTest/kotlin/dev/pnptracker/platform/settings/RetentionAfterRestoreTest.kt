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
import dev.pnptracker.domain.backup.automatic.AutomaticSnapshotTaker
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
import dev.pnptracker.platform.startup.MigrationSnapshotSetWriter
import dev.pnptracker.platform.startup.StartupGate
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
    fun `the plain factory still writes no migration snapshot, which is what the gate depends on`() =
        runBlocking<Unit> {
            // This used to say that nothing anywhere wrote a migration snapshot,
            // because nothing did. PLAN 14.4.10's gate landed and it was changed
            // on purpose rather than left to go quiet — and what it says now is
            // narrower and more useful: `DatabaseFactory` on its own is still
            // inert. That is not a leftover. The gate migrates a *working copy*
            // through this very factory while building the set, and a factory
            // that took snapshots of its own would take one of the copy.
            //
            // What the gate does instead is `StartupGateTest`'s subject.
            val before = namesIn(paths.backupsDirectory)

            openDatabase()

            assertEquals(before, namesIn(paths.backupsDirectory), "the plain factory wrote a migration snapshot")
            assertTrue(namesIn(paths.backupsDirectory).none { it.startsWith(MIGRATION_SNAPSHOT_PREFIX) })
            assertTrue(Files.notExists(paths.settingsFile))
        }

    @Test
    fun `every automatic backup has an owner, and the database factory is not one of them`() {
        // Which pieces have been handed the collaborators at all, asked
        // structurally because a folder cannot show it. The claim has been
        // rewritten deliberately at each slice rather than left to go quiet:
        // first only the restore had housekeeping, then PLAN 14.4.8 gave the
        // import a snapshot taker too, and now PLAN 14.4.10's gate is the third
        // and last owner. With job 4 finished there is no next one.
        //
        // Plain JVM reflection rather than Kotlin's: kotlin-reflect is not a
        // dependency of this project and PLAN 14.1 does not add one for a test.
        val housekeeping = AutomaticBackupHousekeeping::class.java
        val snapshots = AutomaticSnapshotTaker::class.java

        fun takes(
            owner: Class<*>,
            collaborator: Class<*>,
        ) = owner.constructors.any { made -> made.parameterTypes.any { it == collaborator } }

        // The factory stays inert, and that is load-bearing rather than tidy:
        // the gate migrates a working copy through it, and a factory that took
        // snapshots would take one of the copy.
        assertFalse(takes(DatabaseFactory::class.java, housekeeping), "the database factory took on housekeeping")
        assertFalse(takes(DatabaseFactory::class.java, snapshots), "the database factory took on snapshots")
        // The screen above the import has neither. The guarantee belongs to the
        // store, so that every caller gets it and not merely this one.
        assertFalse(takes(ImportConfirmationController::class.java, housekeeping))
        assertFalse(takes(ImportConfirmationController::class.java, snapshots))

        // And the three that do.
        assertTrue(takes(RestoreController::class.java, housekeeping), "the restore lost its housekeeping")
        assertTrue(takes(ImportConfirmationStore::class.java, housekeeping), "the import lost its housekeeping")
        assertTrue(takes(ImportConfirmationStore::class.java, snapshots), "the import can be confirmed without a backup")
        assertTrue(takes(StartupGate::class.java, housekeeping), "the startup gate lost its housekeeping")
        // The gate's own snapshot is a matched pair rather than one document, so
        // what it holds is the writer of that pair — and it cannot open the
        // database without one.
        assertTrue(
            takes(StartupGate::class.java, MigrationSnapshotSetWriter::class.java),
            "a database can be migrated without a snapshot set",
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
