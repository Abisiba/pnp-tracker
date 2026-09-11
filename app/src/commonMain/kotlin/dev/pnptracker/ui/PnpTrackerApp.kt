package dev.pnptracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.pnptracker.AppInfo
import dev.pnptracker.ui.feature.colors.ColorCatalogueController
import dev.pnptracker.ui.feature.export.ExportController
import dev.pnptracker.ui.feature.games.GameTableController
import dev.pnptracker.ui.feature.history.HistoryController
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationController
import dev.pnptracker.ui.feature.importworkspace.ImportReviewController
import dev.pnptracker.ui.feature.importworkspace.ImportRollbackController
import dev.pnptracker.ui.feature.pools.PoolControllers
import dev.pnptracker.ui.feature.settings.BackupController
import dev.pnptracker.ui.feature.settings.RestoreController
import dev.pnptracker.ui.feature.settings.RetentionController
import dev.pnptracker.ui.navigation.AppNavigationState
import dev.pnptracker.ui.navigation.AppScaffold
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode

/**
 * The whole user interface below the platform window.
 *
 * It owns the two pieces of state the shell has — which section is open and
 * which theme is in use — and knows nothing about files, windows or the
 * database. Anything that does, such as the import controller, is handed in by
 * the platform layer that built it.
 */
@Composable
fun PnpTrackerApp(
    appInfo: AppInfo,
    importController: ImportController,
    reviewController: ImportReviewController,
    confirmationController: ImportConfirmationController,
    rollbackController: ImportRollbackController,
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
    var themeMode by remember { mutableStateOf(ThemeMode.LIGHT) }

    // A restore replaces every row in the database. The screens follow, because
    // they all read through a database flow, but a panel somebody left open is
    // anchored to a row that has gone — so each holder of one is asked to let go
    // (PLAN 14.4.3). Nothing navigates: the user pressed a button in the
    // settings and that is where they stay.
    val staleSurfaces: List<StaleSurfaces> =
        remember(gameTableController, colorCatalogueController, poolControllers, reviewController) {
            listOf(gameTableController, colorCatalogueController, reviewController, confirmationController, rollbackController) +
                poolControllers.all
        }
    CloseStaleSurfacesAfterRestore(restoreController.restoredTick, staleSurfaces)

    PnpTrackerTheme(themeMode = themeMode) {
        AppScaffold(
            appInfo = appInfo,
            navigation = navigation,
            themeMode = themeMode,
            onToggleTheme = { themeMode = themeMode.toggled() },
            importController = importController,
            reviewController = reviewController,
            confirmationController = confirmationController,
            rollbackController = rollbackController,
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
