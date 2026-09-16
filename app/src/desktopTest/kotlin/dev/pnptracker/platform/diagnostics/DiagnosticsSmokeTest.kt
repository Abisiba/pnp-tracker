package dev.pnptracker.platform.diagnostics

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.LiveBackupRestorer
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.data.repository.CellTextStore
import dev.pnptracker.data.repository.DraftEdit
import dev.pnptracker.data.repository.GameSetupStore
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.data.repository.ImportReviewStore
import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.data.repository.TaskExportStore
import dev.pnptracker.data.repository.confirmationStore
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.automatic.VerifiedSnapshotTaker
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.export.taskCsvOf
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.backupfiles.DesktopBackupFileGateway
import dev.pnptracker.platform.backupfiles.DesktopImportSnapshotWriter
import dev.pnptracker.platform.exportfiles.DesktopExportFileGateway
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.XdgAppPaths
import dev.pnptracker.platform.files.XdgAppPathsResolver
import dev.pnptracker.platform.importfiles.DesktopImportFileGateway
import dev.pnptracker.platform.settings.DesktopSettingsStore
import dev.pnptracker.platform.startup.deleteTemporaryTree
import dev.pnptracker.ui.feature.export.exportNames
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreenState
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationController
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationState
import dev.pnptracker.ui.feature.settings.aSafetySnapshot
import dev.pnptracker.ui.feature.settings.aValidatedBackup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The real writer, the real boundaries, and a temporary home for all three XDG
 * folders.
 *
 * Two questions, and the first is the one PLAN 14.7.2 cares most about: a whole
 * working afternoon — a game, a cell, an import confirmed behind its automatic
 * backup, that import taken back, a backup saved, tasks exported, a setting
 * changed — writes **nothing at all**, so the state folder does not even exist
 * afterwards. The second is what a real failure looks like on disk: lines a JSON
 * reader takes on their own, holding fixed names and numbers, under the
 * temporary state folder and nowhere else.
 */
class DiagnosticsSmokeTest {
    private lateinit var home: Path
    private lateinit var paths: XdgAppPaths
    private lateinit var database: AppDatabase
    private var realDatabaseExisted = false
    private var realStateExisted = false
    private val probeRoots = mutableListOf<Path>()
    private val unwritable = mutableListOf<Path>()
    private val logs = mutableListOf<QueuedDiagnostics>()

    private val realStateDirectory: Path =
        Path.of(System.getProperty("user.home")).resolve(".local/state/pnp-tracker")

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        realStateExisted = Files.exists(realStateDirectory)
        home = Files.createTempDirectory("pnp-tracker-diagnostics-smoke")
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
        database = DatabaseFactory().open(paths.databaseFile)
        runBlocking { database.gameDao().activeCount() }
    }

    @AfterTest
    fun deleteHome() {
        logs.forEach { it.close() }
        database.close()
        unwritable.forEach { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the smoke changed whether the real application database exists",
        )
        assertEquals(realStateExisted, Files.exists(realStateDirectory), "the smoke touched the real state folder")
        probeRoots.forEach(::deleteTemporaryTree)
        deleteTemporaryTree(home)
    }

    private fun diagnostics() = QueuedDiagnostics.inDirectory(paths.logsDirectory, AppInfo.Current).also { logs.add(it) }

    private fun linesWritten(): List<String> =
        Files
            .readAllLines(paths.logsDirectory.resolve("pnp-tanilama.jsonl"), StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }

    @Test
    fun `a whole afternoon of work that went right leaves no log at all`() =
        runBlocking<Unit> {
            val log = diagnostics()

            val cellId = aGameWithACell(log)
            val batchId = anImportConfirmedBehindItsBackup(log, cellId)
            // Exported while the import's tasks are still there, and taken back
            // afterwards: an export of nothing is a refusal of its own.
            theTasksExported(log)
            ImportRollbackStore(database.importDao(), diagnostics = log).rollBack(batchId)
            aBackupSavedToAFile(log)
            DesktopSettingsStore(paths.settingsFile, diagnostics = log).write(5)

            log.close()

            assertFalse(Files.exists(paths.logsDirectory), "a run with nothing wrong made the log folder")
            assertFalse(Files.exists(paths.stateDirectory), "a run with nothing wrong made the state folder")
            assertTrue(Files.exists(paths.settingsFile), "the smoke did not get as far as saving the setting")
        }

    @Test
    fun `two real failures become two lines, under the temporary state folder and nowhere else`() =
        runBlocking<Unit> {
            val log = diagnostics()
            val locked = aFolderNobodyCanWriteIn()

            val export = DesktopExportFileGateway(FixedExportPicker(locked.resolve("gorevler.csv")), diagnostics = log)
            runCatching { assertNotNull(export.chooseDestination("gorevler.csv")).write("game,column\r\n") }
            runCatching { DesktopSettingsStore(locked.resolve("settings.json"), diagnostics = log).write(4) }

            log.close()

            val lines = linesWritten()
            assertEquals(2, lines.size, "expected one line for each failure: $lines")
            val events =
                lines.map {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject
                        .getValue("event")
                        .jsonPrimitive.content
                }
            assertEquals(listOf("export.write_failed", "settings.write_failed"), events)
            lines.forEach { line ->
                val fields = Json.parseToJsonElement(line).jsonObject
                assertEquals(
                    1,
                    fields
                        .getValue("v")
                        .jsonPrimitive.content
                        .toInt(),
                )
                assertEquals("WARN", fields.getValue("level").jsonPrimitive.content)
                assertEquals(AppInfo.Current.version, fields.getValue("app").jsonPrimitive.content)
                listOf(home.toString(), "gorevler", "kilitli", System.getProperty("user.name")).forEach {
                    assertFalse(it in line, "`$it` reached the log: $line")
                }
            }
            // The log is the only thing that was made, and only where it belongs.
            assertEquals(listOf("logs"), namesIn(paths.stateDirectory))
            assertEquals(listOf("pnp-tanilama.jsonl", "pnp-tanilama.lock"), namesIn(paths.logsDirectory))
            // The database and its own files, and not one thing the log put there.
            assertEquals(
                emptyList(),
                namesIn(paths.dataDirectory).filterNot { it in setOf("pnp.db", "pnp.db.lck", "pnp.db-wal", "pnp.db-shm", "backups") },
            )
        }

    @Test
    fun `a restore that went through says so in one line`() =
        runBlocking<Unit> {
            val log = diagnostics()
            val before = BackupStore(database).snapshot().data

            val problem = LiveBackupRestorer(database, log).restore(aValidatedBackup(), aSafetySnapshot(before))
            log.close()

            assertEquals(null, problem)
            val line = linesWritten().single()
            val fields = Json.parseToJsonElement(line).jsonObject
            assertEquals("restore.completed", fields.getValue("event").jsonPrimitive.content)
            assertEquals("INFO", fields.getValue("level").jsonPrimitive.content)
            assertEquals(setOf("v", "seq", "at", "level", "event", "app", "schema"), fields.keys)
        }

    // ------------------------------------------------------- what is wired up

    private fun namesIn(folder: Path): List<String> =
        Files.list(folder).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }

    private fun aFolderNobodyCanWriteIn(): Path {
        val folder = Files.createDirectory(home.resolve("kilitli"))
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"))
        unwritable.add(folder)
        return folder
    }

    private suspend fun aGameWithACell(log: QueuedDiagnostics): EntityId {
        val setup = GameSetupStore(database.gameDao(), database.gameCellDao(), diagnostics = log)
        val gameId = setup.createGame("Harmonies")
        val cellId = setup.openCell(gameId, CellColumnType.THREE_D)
        CellTextStore(database.cellSegmentDao(), diagnostics = log)
            .saveDocumentText(gameId, CellColumnType.THREE_D, "", "Kırmızı ev ve mavi çatı")
        return cellId
    }

    private suspend fun anImportConfirmedBehindItsBackup(
        log: QueuedDiagnostics,
        cellId: EntityId,
    ): EntityId {
        val file = home.resolve("liste.csv")
        Files.write(
            file,
            """
            game,source_type,raw_text
            Harmonies,3D,12 KIRMIZI
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
        val importing =
            ImportController(
                gateway = DesktopImportFileGateway(FixedImportPicker(file)),
                store = ImportDraftStore(database.importDao()),
                diagnostics = log,
            )
        importing.chooseFile()
        assertIs<ImportScreenState.PreviewReady>(importing.state)
        importing.saveDraft()
        val batchId = assertIs<ImportScreenState.Saved>(importing.state).summary.batchId

        val review = ImportReviewStore(database.importDao(), database.gameDao(), database.colorDao(), diagnostics = log)
        val workspace = assertNotNull(review.observeWorkspace(batchId).first())
        workspace.rawBlocks
            .filter { it.sourceColumnType == SourceColumnType.THREE_D && it.rawText.isNotBlank() }
            .forEach { block ->
                val draftId = review.createDraftFromSelection(block.id, 0, block.rawText.length)
                review.saveDraft(
                    DraftEdit(
                        draftTaskId = draftId,
                        name = block.rawText.trim(),
                        targetCellId = cellId,
                        poolType = PoolType.THREE_D,
                        trackingMode = TrackingMode.THREE_D_BATCH,
                        requiredQuantity = 12,
                        notes = null,
                        isMissing = false,
                        isBorrowed = false,
                        needsInfo = false,
                        needsClassification = false,
                        completionHint = HintDecision.NONE,
                        colorIds = emptyList<EntityId>(),
                    ),
                )
            }

        val confirming =
            ImportConfirmationController(
                confirmationStore(
                    database = database,
                    snapshots =
                        VerifiedSnapshotTaker(
                            exporter = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System),
                            writer = DesktopImportSnapshotWriter(paths.backupsDirectory),
                            reader =
                                UntrustedBackupReader(
                                    TemporaryBackupProbe(
                                        temporaryDirectory = {
                                            Files.createTempDirectory("pnp-tracker-diagnostics-probe").also(probeRoots::add)
                                        },
                                    ),
                                ),
                            clock = Clock.System,
                            diagnostics = log,
                        ),
                    housekeeping =
                        SettingsDrivenHousekeeping(
                            settings = DesktopSettingsStore(paths.settingsFile, diagnostics = log),
                            rotation = AutomaticBackupRotation(DesktopBackupDirectory(paths.backupsDirectory)),
                            diagnostics = log,
                        ),
                    diagnostics = log,
                ),
            )
        confirming.refresh(batchId)
        confirming.acknowledgeUnprocessed(true)
        confirming.confirm(batchId)
        assertIs<ImportConfirmationState.Confirmed>(confirming.state)
        return batchId
    }

    private suspend fun aBackupSavedToAFile(log: QueuedDiagnostics) {
        val gateway = DesktopBackupFileGateway(FixedBackupPicker(home.resolve("pnp-yedek-2026-09-16.json")), diagnostics = log)
        val handle = assertNotNull(gateway.chooseDestination("pnp-yedek-2026-09-16.json"))
        val document = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System).backupDocument()
        handle.write(document.json.encodeToByteArray())
    }

    private suspend fun theTasksExported(log: QueuedDiagnostics) {
        val gateway = DesktopExportFileGateway(FixedExportPicker(home.resolve("gorevler.csv")), diagnostics = log)
        val handle = assertNotNull(gateway.chooseDestination("gorevler.csv"))
        handle.write(taskCsvOf(TaskExportStore(database.taskExportDao(), log).exportedTasks(), exportNames()))
    }
}
