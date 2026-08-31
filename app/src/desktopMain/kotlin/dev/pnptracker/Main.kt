package dev.pnptracker

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.repository.CellTextStore
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.data.repository.GameSetupStore
import dev.pnptracker.data.repository.GameTableStore
import dev.pnptracker.data.repository.ImportConfirmationStore
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.data.repository.ImportReviewStore
import dev.pnptracker.data.repository.PoolStore
import dev.pnptracker.data.repository.TaskEditStore
import dev.pnptracker.data.repository.TaskFromTextStore
import dev.pnptracker.platform.awt.applyLinuxFileDialogPolicy
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.XdgAppPathsResolver
import dev.pnptracker.platform.xlsx.AwtXlsxFilePicker
import dev.pnptracker.platform.xlsx.XlsxImportFileGateway
import dev.pnptracker.ui.PnpTrackerApp
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.feature.colors.ColorCatalogueController
import dev.pnptracker.ui.feature.games.GameTableController
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationController
import dev.pnptracker.ui.feature.importworkspace.ImportReviewController
import dev.pnptracker.ui.feature.pools.PoolControllers
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension

private const val MINIMUM_WINDOW_WIDTH = 640
private const val MINIMUM_WINDOW_HEIGHT = 460

/** Shown by the system file dialog, which is created before the resources are. */
private const val FILE_DIALOG_TITLE = "Excel dosyası seç"

fun main() {
    // First of all, and before anything can touch AWT: the file dialog choice
    // is read once while the toolkit is being created.
    applyLinuxFileDialogPolicy()

    val paths = XdgAppPathsResolver().resolve()
    AppDirectoryInitializer().ensureDirectories(paths)
    val database = DatabaseFactory().open(paths.databaseFile)
    // A harmless read opens the connection and runs any pending migration, so a
    // database that cannot be opened is reported before the window appears.
    runBlocking { database.gameDao().activeCount() }

    val importController =
        ImportController(
            // The picker owns the only Path in the import flow; everything above
            // it is handed a file name and nothing else.
            gateway = XlsxImportFileGateway(AwtXlsxFilePicker(title = FILE_DIALOG_TITLE)),
            store = ImportDraftStore(database.importDao()),
        )
    val reviewController = ImportReviewController(ImportReviewStore(database.importDao()))
    val confirmationController =
        ImportConfirmationController(
            ImportConfirmationStore(database.importDao(), database.gameCellDao(), database.gameDao()),
        )
    // One catalogue behind both the colour section and the task panel, so a
    // colour the user adds is offered by the panel without a second read.
    val colorCatalogue = ColorCatalogueStore(database.colorDao())
    val gameTableController =
        GameTableController(
            table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao()),
            setup = GameSetupStore(database.gameDao(), database.gameCellDao()),
            cells = CellTextStore(database.cellSegmentDao()),
            colors = colorCatalogue,
            taskCreation = TaskFromTextStore(database.taskFromTextDao()),
            taskEditing = TaskEditStore(database.taskEditDao()),
        )
    val colorCatalogueController = ColorCatalogueController(colorCatalogue)
    // The pools read the same tasks the table reads and write through the same
    // editing transaction, so they are given the very same store rather than one
    // of their own.
    val poolControllers =
        PoolControllers(
            pools = PoolStore(database.poolDao()),
            colors = colorCatalogue,
            taskEditing = TaskEditStore(database.taskEditDao()),
        )

    application {
        Window(
            onCloseRequest = {
                database.close()
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
                gameTableController,
                colorCatalogueController,
                poolControllers,
            )
        }
    }
}
