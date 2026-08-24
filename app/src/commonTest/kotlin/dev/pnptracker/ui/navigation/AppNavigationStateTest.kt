package dev.pnptracker.ui.navigation

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
        assertEquals(listOf(Screen.Home, Screen.Games, Screen.Import, Screen.Colors), Screen.all)
    }

    @Test
    fun `a state can be started on a screen other than home`() {
        assertEquals(Screen.Import, AppNavigationState(Screen.Import).currentScreen)
    }
}
