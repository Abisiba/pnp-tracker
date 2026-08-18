package dev.pnptracker.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which section the window is showing.
 *
 * The whole model is one value, so it can be driven and asserted without a
 * composition. There is no back stack: the sidebar is always visible and every
 * destination is one click away, so a history would be state nothing in the user
 * interface could produce or consume.
 */
class AppNavigationState(
    initialScreen: Screen = Screen.Home,
) {
    var currentScreen: Screen by mutableStateOf(initialScreen)
        private set

    /** Shows [screen]. Asking for the screen that is already showing does nothing. */
    fun navigateTo(screen: Screen) {
        if (screen == currentScreen) return
        currentScreen = screen
    }

    fun isCurrent(screen: Screen): Boolean = screen == currentScreen
}
