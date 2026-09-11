package dev.pnptracker.platform.files

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.LiveBackupRestorer
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.data.database.rowCount
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.SAFETY_BACKUP_PREFIX
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.backup.sha256Of
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.platform.backupfiles.BackupSourcePicker
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.backupfiles.DesktopBackupSourceGateway
import dev.pnptracker.platform.backupfiles.DesktopSafetyBackupWriter
import dev.pnptracker.platform.backupfiles.PathBackupInput
import dev.pnptracker.platform.settings.DesktopSettingsStore
import dev.pnptracker.ui.feature.settings.RestoreController
import dev.pnptracker.ui.feature.settings.RestoreScreenState
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private val MOMENT = Instant.fromEpochMilliseconds(1_757_320_364_031)

private val TABLES =
    listOf(
        "colors",
        "color_aliases",
        "import_batches",
        "games",
        "game_cells",
        "raw_import_blocks",
        "tasks",
        "cell_segments",
        "task_colors",
        "task_stages",
        "progress_events",
        "history_events",
        "import_batch_cells",
        "draft_tasks",
        "draft_task_colors",
    )

/** The open dialog, standing in for the one a person would click through. */
private class ChosenFile(
    private val file: Path,
) : BackupSourcePicker {
    var asked = 0
        private set

    override suspend fun chooseSource(): Path {
        asked++
        return file
    }
}

/**
 * The whole restore, in a home of its own, with nothing pretended but the click.
 *
 * The companion of [BackupSmokeTest] and [BackupRestoreSmokeTest], and the last
 * of the three. Where those stop at a file that has been read, this carries on to
 * the end: the file goes back into the live database, through the controller a
 * button press drives, with a safety backup written first and the screens read
 * afterwards.
 *
 * One thing is substituted and only one: the system file dialog, which cannot be
 * clicked through by a test. Everything on either side of it is production — the
 * paths from [XdgAppPathsResolver], the directories from [AppDirectoryInitializer],
 * the database from [DatabaseFactory], the reader with its throwaway database, the
 * safety writer with its atomic write, and the transaction itself. What the
 * dialog would have answered with is a real file this application really wrote.
 *
 * The data is the anonymous fixture, and the home is a directory this test made
 * and deletes.
 */
class RestoreSmokeTest {
    private lateinit var home: Path
    private var realDatabaseExisted = false
    private var database: AppDatabase? = null
    private val probeRoots = mutableListOf<Path>()

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-restore-live-smoke")
    }

    @AfterTest
    fun deleteHome() {
        database?.close()
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the smoke changed whether the real application database exists",
        )
        probeRoots.forEach { deleteTree(it) }
        deleteTree(home)
    }

    /** Deletes one directory, after proving it is one this test made. */
    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        val absolute = root.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
        Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `a temporary home, a state A, a state B, and A put back over it`() =
        runBlocking<Unit> {
            // 1. A home of its own, and the application working out where its data goes.
            val data = home.resolve("data")
            val config = home.resolve("config")
            val paths =
                XdgAppPathsResolver(
                    environment = { name ->
                        when (name) {
                            "XDG_DATA_HOME" -> data.toString()
                            "XDG_CONFIG_HOME" -> config.toString()
                            else -> null
                        }
                    },
                ).resolve()
            AppDirectoryInitializer().ensureDirectories(paths)

            // 2. State A: a database of the current schema with something of every kind in it.
            val opened = DatabaseFactory().open(paths.databaseFile)
            database = opened
            opened.gameDao().activeCount()
            fillWithEverything(opened)
            val stateA = BackupStore(opened).snapshot().data
            val countsA = TABLES.associateWith { rowCount(opened, it) }
            assertTrue(countsA.values.all { it > 0 }, "a table stayed empty: $countsA")

            // 3. The manual backup of A, written where the user would have put it.
            val document = DatabaseBackupExporter(BackupStore(opened), AppInfo.Current, StoppedClock(MOMENT)).backupDocument()
            val fileA = paths.backupsDirectory.resolve("pnp-yedek-2026-09-08.json")
            AtomicFileWriter(temporarySuffix = ".json.part").write(fileA, document.json.encodeToByteArray())

            // 4. State B: the application is used some more.
            val game = aGame(name = "Sonradan eklenen oyun")
            opened.gameDao().insert(game)
            val cell = aCell(game.id, columnType = CellColumnType.THREE_D)
            opened.gameCellDao().insert(cell)
            opened.taskDao().addTaskToCell(aTask(name = "Sonradan eklenen görev"), cell.id, IdGenerator.Random.newId(), MOMENT)
            val stateB = BackupStore(opened).snapshot().data
            assertNotEquals(stateA, stateB)

            // 5. The controller a button press drives, with every collaborator the
            // application itself hands it.
            val controller = controllerFor(fileA, paths, opened)

            // 6. Choosing the file checks it from end to end and asks, having
            // written nothing.
            controller.chooseBackup()
            val asking = controller.state as RestoreScreenState.Confirming
            assertEquals("pnp-yedek-2026-09-08.json", asking.summary.fileName)
            assertEquals(stateA.games.size, asking.summary.gameCount)
            assertEquals(stateA.tasks.size, asking.summary.taskCount)
            assertEquals(stateB, BackupStore(opened).snapshot().data, "the database changed before anybody agreed")
            assertEquals(listOf(fileA.fileName.toString()), namesIn(paths.backupsDirectory))

            // 7. Backing out costs nothing at all.
            controller.cancelRestore()
            assertEquals(RestoreScreenState.Idle, controller.state)
            assertEquals(stateB, BackupStore(opened).snapshot().data)
            assertEquals(listOf(fileA.fileName.toString()), namesIn(paths.backupsDirectory))

            // 8. Agreeing writes the safety backup and then replaces everything.
            controller.chooseBackup()
            controller.confirmRestore()
            val restored = controller.state as RestoreScreenState.Restored
            assertEquals("pnp-yedek-2026-09-08.json", restored.fileName)
            assertTrue(restored.safetyFileName.startsWith(SAFETY_BACKUP_PREFIX), restored.safetyFileName)

            // 9. The database is A, down to the checksum, and the tables count what A counted.
            val after = BackupStore(opened).snapshot()
            assertEquals(stateA, after.data)
            assertEquals(8, after.sourceSchemaVersion)
            assertEquals(checksumOf(stateA), checksumOf(after.data))
            assertEquals(countsA, TABLES.associateWith { rowCount(opened, it) })

            // 10. And the safety backup on disk is B — the state that was replaced.
            val safety = paths.backupsDirectory.resolve(restored.safetyFileName)
            assertTrue(Files.exists(safety))
            assertTrue(Files.size(safety) > 0)
            assertEquals(stateB, dataInside(safety))

            // 11. The same backup again changes nothing and duplicates nothing.
            controller.startOver()
            controller.chooseBackup()
            controller.confirmRestore()
            assertTrue(controller.state is RestoreScreenState.Restored)
            assertEquals(stateA, BackupStore(opened).snapshot().data)

            // 12. And the way back really is one: restoring the safety backup
            // returns the database to B.
            val backToB = controllerFor(safety, paths, opened)
            backToB.chooseBackup()
            backToB.confirmRestore()
            assertTrue(backToB.state is RestoreScreenState.Restored)
            assertEquals(stateB, BackupStore(opened).snapshot().data)

            // 13. A file that has been tampered with is refused, and nothing moves.
            val tampered = paths.backupsDirectory.resolve("bozuk.json")
            Files.writeString(tampered, Files.readString(fileA).replaceFirst("\"sortOrder\":0", "\"sortOrder\":9"))
            val onTampered = controllerFor(tampered, paths, opened)
            val filesBefore = namesIn(paths.backupsDirectory)
            backToB.startOver()
            onTampered.chooseBackup()
            assertTrue(controller.state !is RestoreScreenState.Confirming)
            assertTrue(onTampered.state is RestoreScreenState.Rejected, "a tampered file was offered for restoring")
            assertEquals(stateB, BackupStore(opened).snapshot().data, "a refused file changed the database")
            assertEquals(filesBefore, namesIn(paths.backupsDirectory), "a refused file changed the backups folder")

            // 14. Nothing is left on disk but backups: the manual one, the
            // tampered copy this test made, and one safety backup per restore.
            val names = namesIn(paths.backupsDirectory)
            assertEquals(
                3,
                names.count { it.startsWith(SAFETY_BACKUP_PREFIX) },
                "one safety backup per restore, no more and no fewer: $names",
            )
            assertTrue(names.none { it.endsWith(".part") }, "a half-written file was left behind: $names")
            probeRoots.forEach { assertTrue(Files.notExists(it), "a throwaway database outlived the smoke") }
            // Three restores have now run through the real housekeeping, which
            // reads the retention number every time. Reading it creates nothing:
            // the settings file appears when somebody presses save and at no
            // other moment (PLAN 14.4.12).
            assertTrue(Files.notExists(paths.settingsFile), "a restore created the settings file by itself")
        }

    /**
     * The controller the settings screen would drive, pointed at one file.
     *
     * The housekeeping is the real one, settings file and all: the retention
     * number comes from [DesktopSettingsStore] and the clearing from
     * [AutomaticBackupRotation] over the real folder. That is what lets this
     * smoke say something worth saying about the settings file — a whole restore
     * runs through the thing that reads it, and the file still does not exist
     * afterwards (PLAN 14.4.12).
     */
    private fun controllerFor(
        file: Path,
        paths: XdgAppPaths,
        database: AppDatabase,
    ) = RestoreController(
        sources =
            DesktopBackupSourceGateway(ChosenFile(file)),
        reader = UntrustedBackupReader(probe()),
        exporter = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System),
        safety = DesktopSafetyBackupWriter(paths.backupsDirectory),
        restorer = LiveBackupRestorer(database),
        housekeeping =
            SettingsDrivenHousekeeping(
                settings = DesktopSettingsStore(paths.settingsFile),
                rotation = AutomaticBackupRotation(DesktopBackupDirectory(paths.backupsDirectory)),
            ),
        clock = Clock.System,
    )

    private fun probe() =
        TemporaryBackupProbe(
            temporaryDirectory = {
                Files.createTempDirectory("pnp-tracker-restore-live-smoke-probe").also { probeRoots.add(it) }
            },
        )

    private suspend fun dataInside(file: Path): BackupData =
        (UntrustedBackupReader(probe()).read(PathBackupInput(file)) as BackupReadResult.Valid).backup.data

    private fun checksumOf(data: BackupData): String = sha256Of(canonicalBackupDataJson(data).encodeToByteArray())

    private fun namesIn(folder: Path): List<String> =
        Files.list(folder).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }
}
