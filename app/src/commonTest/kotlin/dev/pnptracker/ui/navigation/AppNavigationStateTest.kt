package dev.pnptracker.ui.navigation

import dev.pnptracker.domain.model.PoolType
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
    fun `the navigation across the top is the table, the four pools and the settings`() {
        // `Özel` stands beside the other three pools, always there, rather than
        // being reached from a button on the table.
        assertEquals(
            listOf(
                Screen.Games,
                Screen.Pool(PoolType.THREE_D),
                Screen.Pool(PoolType.CARD),
                Screen.Pool(PoolType.BOARD),
                Screen.Pool(PoolType.SPECIAL),
                Screen.Settings,
            ),
            Screen.topLevel,
        )
    }

    @Test
    fun `import, colours and the history are tabs of the settings page`() {
        assertEquals(listOf(Screen.Settings, Screen.Import, Screen.Colors, Screen.History), Screen.underSettings)
        // And in neither list twice, so nothing is drawn in two places at once.
        assertEquals(emptyList(), Screen.topLevel.filter { it in Screen.underSettings && it != Screen.Settings })
    }

    @Test
    fun `every section is either in the top row or a tab of the settings page`() {
        assertEquals(Screen.all.toSet(), (Screen.topLevel + Screen.underSettings).toSet())
    }

    @Test
    fun `choosing the history opens the history screen`() {
        val navigation = AppNavigationState()

        navigation.navigateTo(Screen.History)

        assertEquals(Screen.History, navigation.currentScreen)
        assertEquals(listOf(Screen.History), Screen.all.filter(navigation::isCurrent))
    }

    @Test
    fun `a state can be started on a screen other than the table`() {
        assertEquals(Screen.Import, AppNavigationState(Screen.Import).currentScreen)
    }
}
