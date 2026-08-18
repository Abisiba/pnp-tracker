package dev.pnptracker.ui.theme

/**
 * Light or dark appearance, chosen by the user for the current session.
 *
 * The choice is not written to the settings file yet; persisting preferences is
 * a separate piece of work with its own file format.
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    ;

    fun toggled(): ThemeMode =
        when (this) {
            LIGHT -> DARK
            DARK -> LIGHT
        }
}
