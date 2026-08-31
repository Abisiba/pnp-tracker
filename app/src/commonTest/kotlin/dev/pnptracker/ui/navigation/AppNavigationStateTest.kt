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
        assertFalse(navigation.isCurrent(Screen.Home))
    }

    @Test
    fun `exactly one screen is current at a time`() {
        val navigation = AppNavigationState()

        navigation.navigateTo(Screen.Import)

        assertEquals(listOf(Screen.Import), Screen.all.filter(navigation::isCurrent))
    }

    @Test
    fun `the sidebar offers only the sections that have been built`() {
        // PLAN 12.1's order, as far as it has been built: the pools sit between
        // the table and the import section, and history and settings are absent
        // rather than present and dead.
        assertEquals(
            listOf(
                Screen.Home,
                Screen.Games,
                Screen.Pool(PoolType.THREE_D),
                Screen.Pool(PoolType.CARD),
                Screen.Pool(PoolType.BOARD),
                Screen.Pool(PoolType.SPECIAL),
                Screen.Import,
                Screen.Colors,
            ),
            Screen.all,
        )
    }

    @Test
    fun `a window standing on the special pool is moved to the 3D one when it goes`() {
        val navigation = AppNavigationState(Screen.Pool(PoolType.SPECIAL))
        val gone = PoolNavigationSummary.EMPTY

        // What the window does when the last special task is deleted: the pool
        // stops being offered, so standing on it would leave a section the
        // sidebar can no longer name. PLAN 9 hides the pool and says nothing
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
    fun `a state can be started on a screen other than home`() {
        assertEquals(Screen.Import, AppNavigationState(Screen.Import).currentScreen)
    }
}
