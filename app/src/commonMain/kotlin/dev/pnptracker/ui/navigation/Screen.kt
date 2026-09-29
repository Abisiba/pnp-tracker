package dev.pnptracker.ui.navigation

import dev.pnptracker.domain.model.PoolType

/**
 * A section the window can show.
 *
 * The navigation across the top names the table, the four pools and `Ayarlar`;
 * `Ayarlar` opens a page of its own whose tabs reach the rest of the sections, so
 * a screen belongs to one of two lists here: [topLevel] or [underSettings].
 *
 * A screen carries no route string and no visible text: what it is called on
 * screen comes from the Turkish text catalogue, which keeps the closed set of
 * destinations independent of wording.
 */
sealed interface Screen {
    data object Games : Screen

    /**
     * One of the four production pools.
     *
     * One screen shape rather than four, because the pools differ in what they
     * show and not in what they are: PLAN 12.10 to 12.13 give each its own
     * layout, and every one of them is the same reflection of the same tasks.
     */
    data class Pool(
        val poolType: PoolType,
    ) : Screen

    data object Import : Screen

    /** What has happened, as a reading of what was recorded (PLAN 12.15). */
    data object History : Screen

    data object Colors : Screen

    /**
     * Where the application's own housekeeping lives (PLAN 12.16).
     *
     * It arrived with the work that gave it something to do, rather than as an
     * empty destination waiting to be filled: today it holds saving a backup,
     * and nothing else is drawn on it until there is something else that works.
     */
    data object Settings : Screen

    companion object {
        val threeDPool = Pool(PoolType.THREE_D)

        val specialPool = Pool(PoolType.SPECIAL)

        /** Every screen there is. */
        val all: List<Screen> =
            listOf(Games) + PoolType.entries.map(::Pool) + listOf(Import, History, Colors, Settings)

        /**
         * What the navigation across the top names, left to right.
         *
         * The table, the four pools and the settings. The Special pool is an entry
         * like the other three, always there, so its work is reached the same way
         * theirs is. The last entry opens the settings page, whose tabs lead to
         * [underSettings].
         */
        val topLevel: List<Screen> =
            listOf(Games) + PoolType.entries.map(::Pool) + listOf(Settings)

        /**
         * The tabs of the settings page, in that order.
         *
         * The settings themselves first, then the three sections that are about
         * the collection as a whole rather than about today's work.
         */
        val underSettings: List<Screen> = listOf(Settings, Import, Colors, History)
    }
}
