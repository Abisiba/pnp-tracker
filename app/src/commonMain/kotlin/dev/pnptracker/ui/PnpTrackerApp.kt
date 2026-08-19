package dev.pnptracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.pnptracker.AppInfo
import dev.pnptracker.ui.feature.games.GamesController
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importworkspace.ImportReviewController
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
    gamesController: GamesController,
) {
    val navigation = remember { AppNavigationState() }
    var themeMode by remember { mutableStateOf(ThemeMode.LIGHT) }

    PnpTrackerTheme(themeMode = themeMode) {
        AppScaffold(
            appInfo = appInfo,
            navigation = navigation,
            themeMode = themeMode,
            onToggleTheme = { themeMode = themeMode.toggled() },
            importController = importController,
            reviewController = reviewController,
            gamesController = gamesController,
        )
    }
}
