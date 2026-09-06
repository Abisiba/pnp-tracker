package dev.pnptracker.ui.navigation

import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.pools.PoolNavigationSummary

/**
 * A section the sidebar can reach.
 *
 * The settings section of the plan arrives with the work that implements it, so
 * it is deliberately absent rather than disabled. The order here is the order
 * PLAN 12.1 lists the sidebar in, history included.
 *
 * A screen carries no route string and no visible text: what it is called on
 * screen comes from the Turkish text catalogue, which keeps the closed set of
 * destinations independent of wording.
 */
sealed interface Screen {
    data object Home : Screen

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

    companion object {
        val threeDPool = Pool(PoolType.THREE_D)

        /** Every screen, in the order the sidebar lists them. */
        val all: List<Screen> =
            listOf(Home, Games) + PoolType.entries.map(::Pool) + listOf(Import, History, Colors)

        /**
         * The screens the sidebar is offering right now.
         *
         * All of them but the Special pool, which PLAN 9 hides until there is
         * special work and hides again only once there is none left. Everything
         * else is always there, so this is the one question the sidebar asks.
         */
        fun offered(summary: PoolNavigationSummary): List<Screen> = all.filter { it != Pool(PoolType.SPECIAL) || summary.showsSpecial }
    }
}
