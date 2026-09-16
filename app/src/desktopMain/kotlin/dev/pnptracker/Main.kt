package dev.pnptracker

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.LiveBackupRestorer
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.data.repository.CellTextStore
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.data.repository.GameSetupStore
import dev.pnptracker.data.repository.GameTableStore
import dev.pnptracker.data.repository.HistoryStore
import dev.pnptracker.data.repository.ImportConfirmationStore
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.data.repository.ImportReviewStore
import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.data.repository.PoolStore
import dev.pnptracker.data.repository.TaskEditStore
import dev.pnptracker.data.repository.TaskExportStore
import dev.pnptracker.data.repository.TaskFromTextStore
import dev.pnptracker.data.repository.TaskProgressStore
import dev.pnptracker.data.repository.UnfinishedImportsStore
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.domain.backup.automatic.StartupRefused
import dev.pnptracker.domain.backup.automatic.VerifiedSnapshotTaker
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.time.localMomentOf
import dev.pnptracker.platform.awt.applyLinuxFileDialogPolicy
import dev.pnptracker.platform.backupfiles.AwtBackupFilePicker
import dev.pnptracker.platform.backupfiles.AwtBackupSourcePicker
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.backupfiles.DesktopBackupFileGateway
import dev.pnptracker.platform.backupfiles.DesktopBackupSourceGateway
import dev.pnptracker.platform.backupfiles.DesktopImportSnapshotWriter
import dev.pnptracker.platform.backupfiles.DesktopSafetyBackupWriter
import dev.pnptracker.platform.diagnostics.QueuedDiagnostics
import dev.pnptracker.platform.diagnostics.RecordingWindowExceptionHandlerFactory
import dev.pnptracker.platform.diagnostics.startupRefusalRecord
import dev.pnptracker.platform.exportfiles.AwtExportFilePicker
import dev.pnptracker.platform.exportfiles.DesktopExportFileGateway
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.XdgAppPathsResolver
import dev.pnptracker.platform.importfiles.AwtImportFilePicker
import dev.pnptracker.platform.importfiles.DesktopImportFileGateway
import dev.pnptracker.platform.settings.DesktopSettingsStore
import dev.pnptracker.platform.startup.MigrationSnapshotSetWriter
import dev.pnptracker.platform.startup.StartupGate
import dev.pnptracker.ui.PnpTrackerApp
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.feature.colors.ColorCatalogueController
import dev.pnptracker.ui.feature.export.ExportController
import dev.pnptracker.ui.feature.export.exportNames
import dev.pnptracker.ui.feature.games.GameTableController
import dev.pnptracker.ui.feature.history.HistoryController
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationController
import dev.pnptracker.ui.feature.importworkspace.ImportReviewController
import dev.pnptracker.ui.feature.importworkspace.ImportRollbackController
import dev.pnptracker.ui.feature.importworkspace.UnfinishedImportsController
import dev.pnptracker.ui.feature.pools.PoolControllers
import dev.pnptracker.ui.feature.settings.BackupController
import dev.pnptracker.ui.feature.settings.RestoreController
import dev.pnptracker.ui.feature.settings.RetentionController
import dev.pnptracker.ui.feature.startup.StartupErrorScreen
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension
import kotlin.time.Clock

private const val MINIMUM_WINDOW_WIDTH = 640
private const val MINIMUM_WINDOW_HEIGHT = 460

/** Shown by the system file dialog, which is created before the resources are. */
private const val FILE_DIALOG_TITLE = "Excel veya CSV dosyası seç"

/** Shown by the system save dialog, which is created before the resources are. */
private const val EXPORT_DIALOG_TITLE = "Görevleri CSV olarak kaydet"

/** Shown by the system save dialog, which is created before the resources are. */
private const val BACKUP_DIALOG_TITLE = "Yedeği JSON olarak kaydet"

/** What the open dialog is called when a backup is being put back. */
private const val RESTORE_DIALOG_TITLE = "Geri yüklenecek yedeği seç"

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // First of all, and before anything can touch AWT: the file dialog choice
    // is read once while the toolkit is being created.
    applyLinuxFileDialogPolicy()

    val paths = XdgAppPathsResolver().resolve()
    AppDirectoryInitializer().ensureDirectories(paths)
    // The diagnostic log (PLAN 14.7.1). Until a first line is written it makes no
    // folder, no file and no lock, and closing it hands whatever is queued to the
    // disk for at most half a second. It is handed to each boundary that turns a
    // failure into a typed answer, and each failure is recorded by that boundary
    // alone (PLAN 14.7.2); nothing above a boundary records the same failure again.
    val diagnostics = QueuedDiagnostics.inDirectory(paths.logsDirectory, AppInfo.Current)
    // The one setting this application has, and the only thing that writes it is
    // the user pressing save. Reading it creates nothing (PLAN 14.4.12).
    val settingsStore = DesktopSettingsStore(paths.settingsFile, diagnostics = diagnostics)
    // Clearing old automatic backups away. It reads the number each time rather
    // than being told when it changes, which is what makes a lowered count take
    // effect at the next automatic backup and not before (PLAN 14.4.12).
    val housekeeping =
        SettingsDrivenHousekeeping(
            settings = settingsStore,
            rotation = AutomaticBackupRotation(DesktopBackupDirectory(paths.backupsDirectory)),
            diagnostics = diagnostics,
        )
    // Nothing in this application opens the user's database except this, and
    // nothing reaches a migration except through it. PLAN 14.4.10: the instance
    // lock, the version read without Room, and — for a database on an older
    // schema — a matched pair of snapshot artefacts that must both be written
    // and proved before the real migration is allowed to begin.
    val opened =
        try {
            StartupGate(
                paths = paths,
                databases = DatabaseFactory(),
                sets =
                    MigrationSnapshotSetWriter(
                        backupsDirectory = paths.backupsDirectory,
                        reader = UntrustedBackupReader(TemporaryBackupProbe()),
                        moment = { localMomentOf(Clock.System.now()) },
                    ),
                housekeeping = housekeeping,
                diagnostics = diagnostics,
            ).open()
        } catch (refused: StartupRefused) {
            // The refusal is decided inside the gate and becomes what the user sees
            // here, so this is its one record.
            diagnostics.recordSafely { startupRefusalRecord(refused) }
            showTheStartupProblem(refused.problem, diagnostics)
            return
        }
    val database = opened.database

    val importController =
        ImportController(
            // The picker owns the only Path in the import flow; everything above
            // it is handed a file name and nothing else.
            gateway = DesktopImportFileGateway(AwtImportFilePicker(title = FILE_DIALOG_TITLE)),
            store = ImportDraftStore(database.importDao()),
            diagnostics = diagnostics,
        )
    val reviewController =
        ImportReviewController(
            ImportReviewStore(database.importDao(), database.gameDao(), database.colorDao(), diagnostics = diagnostics),
        )
    // Confirming an import is the one thing in this application that writes a
    // great many rows at once, so PLAN 14.4.8 puts a backup in front of every
    // one of them. Every collaborator here is the real one: the same exporter a
    // manual backup uses, the same atomic writer, and the same untrusted reader
    // the user's own file goes through — because a snapshot nobody has read back
    // is not a backup and may not be treated as one.
    val confirmationController =
        ImportConfirmationController(
            ImportConfirmationStore(
                database = database,
                importDao = database.importDao(),
                gameCellDao = database.gameCellDao(),
                gameDao = database.gameDao(),
                snapshots =
                    VerifiedSnapshotTaker(
                        exporter = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System),
                        writer = DesktopImportSnapshotWriter(paths.backupsDirectory),
                        reader = UntrustedBackupReader(TemporaryBackupProbe()),
                        clock = Clock.System,
                        diagnostics = diagnostics,
                    ),
                housekeeping = housekeeping,
                diagnostics = diagnostics,
            ),
        )
    // Taking a confirmed import back reads and writes the very same rows the
    // confirmation above wrote, through the very same DAO: PLAN 11.4.4 has one
    // transaction undo the other, so there is one place both live.
    val rollbackController = ImportRollbackController(ImportRollbackStore(database.importDao(), diagnostics = diagnostics))
    // The unfinished imports are read by the same classifier the confirmation's
    // two gates use, and removed by the one removal engine there is: PLAN 11.4.5
    // has a draft be continued or removed, and nothing else done to it.
    val unfinishedController = UnfinishedImportsController(UnfinishedImportsStore(database, database.importDao(), diagnostics))
    // One catalogue behind both the colour section and the task panel, so a
    // colour the user adds is offered by the panel without a second read.
    val colorCatalogue = ColorCatalogueStore(database.colorDao(), diagnostics = diagnostics)
    // One progress store behind the table and the pools alike: PLAN 12.10 has a
    // write started from a pool happen on the very same task, through the very
    // same transaction, as one started from the table.
    val taskProgress = TaskProgressStore(database.taskProgressDao(), diagnostics = diagnostics)
    val gameTableController =
        GameTableController(
            table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao()),
            setup = GameSetupStore(database.gameDao(), database.gameCellDao(), diagnostics = diagnostics),
            cells = CellTextStore(database.cellSegmentDao(), diagnostics = diagnostics),
            colors = colorCatalogue,
            taskCreation = TaskFromTextStore(database.taskFromTextDao(), diagnostics = diagnostics),
            taskEditing = TaskEditStore(database.taskEditDao(), diagnostics = diagnostics),
            taskProgress = taskProgress,
        )
    // The exporter owns the only Path on its side of the application, exactly as
    // the import picker does; everything above it is handed a file name.
    val exportController =
        ExportController(
            gateway = DesktopExportFileGateway(AwtExportFilePicker(title = EXPORT_DIALOG_TITLE), diagnostics = diagnostics),
            tasks = TaskExportStore(database.taskExportDao(), diagnostics),
            names = ::exportNames,
        )
    // The backup reads every table there is and writes nothing at all; the only
    // thing it can do to the database is ask it a question (PLAN 14.4.2).
    val backupController =
        BackupController(
            gateway = DesktopBackupFileGateway(AwtBackupFilePicker(title = BACKUP_DIALOG_TITLE), diagnostics = diagnostics),
            exporter = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System),
            clock = Clock.System,
            diagnostics = diagnostics,
        )
    // The one route from a file to the user's data, and every step of it is a
    // real collaborator: the file comes through the same picker pattern as every
    // other, the checking is the untrusted reader with its throwaway database,
    // the safety backup is written by the same atomic writer a manual backup
    // uses, and the replacement is one transaction on the connection already
    // open (PLAN 14.4.3, 14.4.4).
    val restoreController =
        RestoreController(
            sources = DesktopBackupSourceGateway(AwtBackupSourcePicker(title = RESTORE_DIALOG_TITLE)),
            reader = UntrustedBackupReader(TemporaryBackupProbe()),
            exporter = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System),
            safety = DesktopSafetyBackupWriter(paths.backupsDirectory),
            restorer = LiveBackupRestorer(database, diagnostics),
            housekeeping = housekeeping,
            clock = Clock.System,
            diagnostics = diagnostics,
        )
    val retentionController = RetentionController(settingsStore)
    val colorCatalogueController = ColorCatalogueController(colorCatalogue)
    // The pools read the same tasks the table reads and write through the same
    // editing transaction, so they are given the very same store rather than one
    // of their own.
    val poolControllers =
        PoolControllers(
            pools = PoolStore(database.poolDao()),
            colors = colorCatalogue,
            taskEditing = TaskEditStore(database.taskEditDao(), diagnostics = diagnostics),
            taskProgress = taskProgress,
            diagnostics = diagnostics,
        )

    // The history reads the same rows every write above it appends to, and can
    // do nothing else: PLAN 5.12 keeps the record and the thing recorded in one
    // transaction, so the section is given a source with no way to write.
    val historyController = HistoryController(HistoryStore(database.historyDao()), diagnostics = diagnostics)

    application {
        // An error no typed boundary caught is recorded and then handled exactly as
        // Compose would have handled it (PLAN 14.7.2).
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides RecordingWindowExceptionHandlerFactory(diagnostics)) {
            Window(
                onCloseRequest = {
                    database.close()
                    diagnostics.close()
                    exitApplication()
                },
                state = rememberWindowState(size = DpSize(1100.dp, 720.dp)),
                title = stringResource(Strings.App.windowTitle),
            ) {
                // Below this the sidebar and the open section stop being usable
                // together, so the window manager is not allowed to go smaller.
                window.minimumSize = Dimension(MINIMUM_WINDOW_WIDTH, MINIMUM_WINDOW_HEIGHT)
                PnpTrackerApp(
                    AppInfo.Current,
                    importController,
                    reviewController,
                    confirmationController,
                    rollbackController,
                    unfinishedController,
                    gameTableController,
                    exportController,
                    backupController,
                    restoreController,
                    retentionController,
                    colorCatalogueController,
                    poolControllers,
                    historyController,
                )
            }
        }
    }
}

/**
 * Shows the refusal instead of the application, and nothing else.
 *
 * A window of its own rather than a dialog over the main one, because there is
 * no main one: PLAN 14.4.10 does not let the application reach its first screen
 * when the gate refuses, and a message the user can read and close is the whole
 * of what happens next.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun showTheStartupProblem(
    problem: StartupProblem,
    diagnostics: QueuedDiagnostics,
) {
    application {
        val exit = {
            diagnostics.close()
            exitApplication()
        }
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides RecordingWindowExceptionHandlerFactory(diagnostics)) {
            Window(
                onCloseRequest = exit,
                state = rememberWindowState(size = DpSize(560.dp, 360.dp)),
                title = stringResource(Strings.Startup.title),
            ) {
                PnpTrackerTheme(ThemeMode.LIGHT) {
                    StartupErrorScreen(problem = problem, onClose = exit)
                }
            }
        }
    }
}
