package dev.pnptracker

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.pnptracker.resources.Res
import dev.pnptracker.resources.app_window_title
import dev.pnptracker.ui.AppRoot
import org.jetbrains.compose.resources.stringResource

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = stringResource(Res.string.app_window_title),
        ) {
            AppRoot(AppInfo.Current)
        }
    }
