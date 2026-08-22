package dev.pnptracker.ui.navigation

/**
 * A section the sidebar can reach.
 *
 * The pool, history and settings sections of the plan arrive with the work that
 * implements them, so they are deliberately absent rather than disabled. The
 * order here is the order PLAN 12.1 lists the sidebar in.
 *
 * A screen carries no route string and no visible text: what it is called on
 * screen comes from the Turkish text catalogue, which keeps the closed set of
 * destinations independent of wording.
 */
sealed interface Screen {
    data object Home : Screen

    data object Games : Screen

    data object Import : Screen

    data object Colors : Screen

    companion object {
        /** Every screen, in the order the sidebar lists them. */
        val all: List<Screen> = listOf(Home, Games, Import, Colors)
    }
}
