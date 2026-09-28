package dev.pnptracker.ui.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.domain.settings.AccentColor
import dev.pnptracker.domain.settings.Appearance
import dev.pnptracker.domain.settings.AppearanceNotSaved
import dev.pnptracker.domain.settings.AppearanceStore
import dev.pnptracker.domain.settings.SettingsWriteFailure
import dev.pnptracker.domain.settings.ThemeMode

/**
 * Drives how the application looks, and remembers it.
 *
 * Two things are worth knowing.
 *
 * **What is on screen is what was chosen, whatever the disk did.** The choice is
 * applied first and written afterwards; a write that fails leaves the application
 * looking the way the user asked and puts a line on the screen saying it will not
 * be there next time. The alternative — refusing to change the theme because a
 * file would not save — would be a worse answer to a smaller problem.
 *
 * **The value it starts with is the one that was read.** The window is built with
 * it, so a machine that chose dark opens dark rather than opening light and
 * blinking (PLAN 12.16).
 */
class AppearanceController(
    private val store: AppearanceStore,
    initial: Appearance = Appearance(),
) {
    var appearance: Appearance by mutableStateOf(initial)
        private set

    /**
     * Why the appearance in use is the default rather than the file's.
     *
     * Read once, when the application started, and kept: the screen shows it
     * whenever it is opened, and a file that could not be understood then cannot
     * become understandable without somebody replacing it.
     */
    val problem = initial.problem

    /** Set when the last choice could not be written down. Cleared by one that could. */
    var notSaved: SettingsWriteFailure? by mutableStateOf(null)
        private set

    suspend fun useTheme(themeMode: ThemeMode) = remember(appearance.copy(themeMode = themeMode))

    suspend fun useAccent(accentColor: AccentColor) = remember(appearance.copy(accentColor = accentColor))

    suspend fun toggleTheme() = useTheme(appearance.themeMode.toggled())

    private suspend fun remember(chosen: Appearance) {
        // On screen first. The problem a stored file had is not carried forward:
        // what is written now is a whole document, so whatever was unreadable
        // before has been answered by somebody choosing.
        appearance = chosen.copy(problem = null)
        notSaved =
            try {
                store.write(appearance)
                null
            } catch (couldNotSave: AppearanceNotSaved) {
                couldNotSave.failure
            }
    }
}
