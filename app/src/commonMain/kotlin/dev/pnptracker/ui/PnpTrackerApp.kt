package dev.pnptracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.pnptracker.AppInfo
import dev.pnptracker.ui.feature.colors.ColorCatalogueController
import dev.pnptracker.ui.feature.export.ExportController
import dev.pnptracker.ui.feature.games.GameTableController
import dev.pnptracker.ui.feature.history.HistoryController
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationController
import dev.pnptracker.ui.feature.importworkspace.ImportReviewController
import dev.pnptracker.ui.feature.importworkspace.ImportRollbackController
import dev.pnptracker.ui.feature.importworkspace.UnfinishedImportsController
import dev.pnptracker.ui.feature.pools.PoolControllers
import dev.pnptracker.ui.feature.settings.AppearanceController
import dev.pnptracker.ui.feature.settings.BackupController
import dev.pnptracker.ui.feature.settings.RestoreController
import dev.pnptracker.ui.feature.settings.RetentionController
import dev.pnptracker.ui.navigation.AppNavigationState
import dev.pnptracker.ui.navigation.AppScaffold
import dev.pnptracker.ui.theme.PnpTrackerTheme

/**
 * The whole user interface below the platform window.
 *
 * It owns the one piece of state the shell has — which section is open — and
 * knows nothing about files, windows or the database. How the application looks
 * is state as well, but it is state with a file behind it, so it arrives in a
 * controller the platform layer built and already read (PLAN 12.16). Anything else
 * that touches a file, such as the import controller, arrives the same way.
 */
@Composable
fun PnpTrackerApp(
    appInfo: AppInfo,
    appearanceController: AppearanceController,
    importController: ImportController,
    reviewController: ImportReviewController,
    confirmationController: ImportConfirmationController,
    rollbackController: ImportRollbackController,
    unfinishedController: UnfinishedImportsController,
    gameTableController: GameTableController,
    exportController: ExportController,
    backupController: BackupController,
    restoreController: RestoreController,
    retentionController: RetentionController,
    colorCatalogueController: ColorCatalogueController,
    poolControllers: PoolControllers,
    historyController: HistoryController,
) {
    val navigation = remember { AppNavigationState() }

    // A restore replaces every row in the database. The screens follow, because
    // they all read through a database flow, but a panel somebody left open is
    // anchored to a row that has gone — so each holder of one is asked to let go
    // (PLAN 14.4.3). Nothing navigates: the user pressed a button in the
    // settings and that is where they stay.
    val staleSurfaces: List<StaleSurfaces> =
        remember(gameTableController, colorCatalogueController, poolControllers, reviewController) {
            listOf(
                gameTableController,
                colorCatalogueController,
                reviewController,
                confirmationController,
                rollbackController,
                unfinishedController,
            ) +
                poolControllers.all
        }
    CloseStaleSurfacesAfterRestore(restoreController.restoredTick, staleSurfaces)

    val appearance = appearanceController.appearance
    PnpTrackerTheme(themeMode = appearance.themeMode, accentColor = appearance.accentColor) {
        AppScaffold(
            appInfo = appInfo,
            navigation = navigation,
            appearanceController = appearanceController,
            importController = importController,
            reviewController = reviewController,
            confirmationController = confirmationController,
            rollbackController = rollbackController,
            unfinishedController = unfinishedController,
            gameTableController = gameTableController,
            exportController = exportController,
            backupController = backupController,
            restoreController = restoreController,
            retentionController = retentionController,
            colorCatalogueController = colorCatalogueController,
            poolControllers = poolControllers,
            historyController = historyController,
        )
    }
}
