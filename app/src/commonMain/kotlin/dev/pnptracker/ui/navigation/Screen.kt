package dev.pnptracker.ui.navigation

import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.pools.PoolNavigationSummary

/**
 * A section the window can show.
 *
 * PLAN 12.1 names five places in the navigation across the top and puts the rest
 * of the sections in the menu under `Ayarlar`, so a screen belongs to one of two
 * lists here: [topLevel] or [underSettings]. The Special pool is in neither,
 * because it is reached from the game table that holds the work (PLAN 9).
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
         * What the navigation across the top names, left to right (PLAN 12.1).
         *
         * Five entries and no more, which is what keeps it to one short line: the
         * table, the three pools that are always there, and the settings. The
         * last one opens [underSettings] rather than only its own screen.
         */
        val topLevel: List<Screen> =
            listOf(Games, Pool(PoolType.THREE_D), Pool(PoolType.CARD), Pool(PoolType.BOARD), Settings)

        /**
         * What the menu under `Ayarlar` offers, in that order (PLAN 12.1).
         *
         * The settings themselves first, then the three sections that are about
         * the collection as a whole rather than about today's work. They are one
         * click further away than they were and they are otherwise untouched.
         */
        val underSettings: List<Screen> = listOf(Settings, Import, Colors, History)

        /**
         * The screens that can be reached right now.
         *
         * All of them but the Special pool, which PLAN 9 hides until there is
         * special work and hides again only once there is none left. Everything
         * else is always there, so this is the one question the navigation asks.
         */
        fun offered(summary: PoolNavigationSummary): List<Screen> = all.filter { it != specialPool || summary.showsSpecial }
    }
}
