package dev.pnptracker.ui.navigation

import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.pools.PoolCounts
import dev.pnptracker.domain.pools.PoolNavigationSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppNavigationStateTest {
    @Test
    fun `the window opens on the game table`() {
        assertEquals(Screen.Games, AppNavigationState().currentScreen)
    }

    @Test
    fun `choosing games opens the games screen`() {
        val navigation = AppNavigationState()

        navigation.navigateTo(Screen.Games)

        assertEquals(Screen.Games, navigation.currentScreen)
    }

    @Test
    fun `choosing import opens the import screen`() {
        val navigation = AppNavigationState()

        navigation.navigateTo(Screen.Import)

        assertEquals(Screen.Import, navigation.currentScreen)
    }

    @Test
    fun `settings is always offered, special is the only conditional section`() {
        assertTrue(Screen.Settings in Screen.offered(PoolNavigationSummary.EMPTY))
        assertEquals(
            listOf(Screen.Pool(PoolType.SPECIAL)),
            Screen.all - Screen.offered(PoolNavigationSummary.EMPTY).toSet(),
        )
    }

    @Test
    fun `every screen can be reached from every other screen`() {
        val navigation = AppNavigationState()

        Screen.all.forEach { from ->
            Screen.all.forEach { to ->
                navigation.navigateTo(from)
                navigation.navigateTo(to)
                assertEquals(to, navigation.currentScreen, "could not go from $from to $to")
            }
        }
    }

    @Test
    fun `choosing the screen that is already open changes nothing`() {
        val navigation = AppNavigationState()
        navigation.navigateTo(Screen.Games)

        repeat(3) { navigation.navigateTo(Screen.Games) }

        assertEquals(Screen.Games, navigation.currentScreen)
        assertTrue(navigation.isCurrent(Screen.Games))
        assertFalse(navigation.isCurrent(Screen.Settings))
    }

    @Test
    fun `exactly one screen is current at a time`() {
        val navigation = AppNavigationState()

        navigation.navigateTo(Screen.Import)

        assertEquals(listOf(Screen.Import), Screen.all.filter(navigation::isCurrent))
    }

    @Test
    fun `every section that has been built is still a section`() {
        // There is no home page any more (PLAN 12.2) and everything else is here.
        assertEquals(
            listOf(
                Screen.Games,
                Screen.Pool(PoolType.THREE_D),
                Screen.Pool(PoolType.CARD),
                Screen.Pool(PoolType.BOARD),
                Screen.Pool(PoolType.SPECIAL),
                Screen.Import,
                Screen.History,
                Screen.Colors,
                Screen.Settings,
            ),
            Screen.all,
        )
    }

    @Test
    fun `the navigation across the top is the five entries the plan names`() {
        // PLAN 12.1: the table, the three pools that are always there, and the
        // settings. Five is what keeps it to one short line.
        assertEquals(
            listOf(
                Screen.Games,
                Screen.Pool(PoolType.THREE_D),
                Screen.Pool(PoolType.CARD),
                Screen.Pool(PoolType.BOARD),
                Screen.Settings,
            ),
            Screen.topLevel,
        )
    }

    @Test
    fun `import, colours and the history are in the menu under the settings`() {
        // Moved there by PLAN 12.1 and otherwise untouched: one click further
        // away, the same screens.
        assertEquals(listOf(Screen.Settings, Screen.Import, Screen.Colors, Screen.History), Screen.underSettings)
        // And in neither list twice, so nothing is drawn in two places at once.
        assertEquals(emptyList(), Screen.topLevel.filter { it in Screen.underSettings && it != Screen.Settings })
    }

    @Test
    fun `the special pool is in no navigation list and is reached from the table`() {
        // PLAN 12.1 keeps it out of the top row; PLAN 9 shows it only while there
        // is special work, and the game table is where that work is.
        assertFalse(Screen.specialPool in Screen.topLevel)
        assertFalse(Screen.specialPool in Screen.underSettings)
        assertTrue(Screen.specialPool in Screen.all)
    }

    @Test
    fun `every section is either in the top row or in the settings menu, or is the special pool`() {
        val reachable = (Screen.topLevel + Screen.underSettings).toSet()
        assertEquals(setOf(Screen.specialPool), Screen.all.toSet() - reachable)
    }

    @Test
    fun `choosing the history opens the history screen`() {
        val navigation = AppNavigationState()

        navigation.navigateTo(Screen.History)

        assertEquals(Screen.History, navigation.currentScreen)
        assertEquals(listOf(Screen.History), Screen.all.filter(navigation::isCurrent))
    }

    @Test
    fun `the history is offered whether or not there is special work`() {
        // Unlike the Special pool, it is never hidden: PLAN 12.1 keeps it in the
        // settings menu unconditionally, and a history with nothing in it says so
        // on the screen rather than by not being there.
        assertTrue(Screen.History in Screen.offered(PoolNavigationSummary.EMPTY))
    }

    @Test
    fun `a window standing on the special pool is moved to the 3D one when it goes`() {
        val navigation = AppNavigationState(Screen.Pool(PoolType.SPECIAL))
        val gone = PoolNavigationSummary.EMPTY

        // What the window does when the last special task is deleted: the pool
        // stops being offered, so standing on it would leave a section nothing
        // can name any more. PLAN 9 hides the pool and says nothing
        // else about it, so the move is silent and lands on the first pool.
        assertFalse(Screen.Pool(PoolType.SPECIAL) in Screen.offered(gone))
        navigation.navigateTo(Screen.threeDPool)

        assertEquals(Screen.Pool(PoolType.THREE_D), navigation.currentScreen)
        assertTrue(Screen.threeDPool in Screen.offered(gone), "the pool it moved to is not offered either")
    }

    @Test
    fun `a pool full of work elsewhere does not offer the special one`() {
        val busy =
            PoolNavigationSummary(
                mapOf(
                    PoolType.THREE_D to PoolCounts(activeCount = 42, taskCount = 42),
                    PoolType.CARD to PoolCounts(activeCount = 9, taskCount = 20),
                ),
            )

        assertFalse(Screen.Pool(PoolType.SPECIAL) in Screen.offered(busy))
    }

    @Test
    fun `the special pool is offered only once there is special work`() {
        // PLAN 9 hides it until the first special task and keeps it afterwards,
        // so what decides this is how many exist rather than how many are left.
        assertEquals(Screen.all - Screen.Pool(PoolType.SPECIAL), Screen.offered(PoolNavigationSummary.EMPTY))
        assertEquals(
            Screen.all,
            Screen.offered(PoolNavigationSummary(mapOf(PoolType.SPECIAL to PoolCounts(activeCount = 0, taskCount = 1)))),
        )
    }

    @Test
    fun `a state can be started on a screen other than the table`() {
        assertEquals(Screen.Import, AppNavigationState(Screen.Import).currentScreen)
    }
}
