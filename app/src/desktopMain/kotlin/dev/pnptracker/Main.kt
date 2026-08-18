package dev.pnptracker

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.XdgAppPathsResolver
import dev.pnptracker.resources.Res
import dev.pnptracker.resources.app_window_title
import dev.pnptracker.ui.AppRoot
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.stringResource

fun main() {
    val paths = XdgAppPathsResolver().resolve()
    AppDirectoryInitializer().ensureDirectories(paths)
    val database = DatabaseFactory().open(paths.databaseFile)
    // A harmless read opens the connection and creates schema v1, so a database
    // that cannot be opened is reported before the window appears.
    runBlocking { database.gameDao().activeCount() }

    application {
        Window(
            onCloseRequest = {
                database.close()
                exitApplication()
            },
            title = stringResource(Res.string.app_window_title),
        ) {
            AppRoot(AppInfo.Current)
        }
    }
}
