package dev.pnptracker

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.XdgAppPathsResolver
import dev.pnptracker.ui.PnpTrackerApp
import dev.pnptracker.ui.Strings
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension

private const val MINIMUM_WINDOW_WIDTH = 640
private const val MINIMUM_WINDOW_HEIGHT = 460

fun main() {
    val paths = XdgAppPathsResolver().resolve()
    AppDirectoryInitializer().ensureDirectories(paths)
    val database = DatabaseFactory().open(paths.databaseFile)
    // A harmless read opens the connection and runs any pending migration, so a
    // database that cannot be opened is reported before the window appears.
    runBlocking { database.gameDao().activeCount() }

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
            PnpTrackerApp(AppInfo.Current)
        }
    }
}
