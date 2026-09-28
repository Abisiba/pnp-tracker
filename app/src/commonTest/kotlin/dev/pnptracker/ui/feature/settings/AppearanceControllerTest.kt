package dev.pnptracker.ui.feature.settings

import dev.pnptracker.domain.settings.AccentColor
import dev.pnptracker.domain.settings.Appearance
import dev.pnptracker.domain.settings.AppearanceNotSaved
import dev.pnptracker.domain.settings.AppearanceProblem
import dev.pnptracker.domain.settings.AppearanceStore
import dev.pnptracker.domain.settings.SettingsWriteFailure
import dev.pnptracker.domain.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A store that remembers in memory, or refuses to (PLAN 12.16). */
private class SceneAppearance(
    var stored: Appearance = Appearance(),
    private val refuse: SettingsWriteFailure? = null,
) : AppearanceStore {
    var writes = 0
        private set

    override suspend fun read(): Appearance = stored

    override suspend fun write(appearance: Appearance) {
        writes += 1
        if (refuse != null) throw AppearanceNotSaved(refuse)
        stored = appearance
    }
}

class AppearanceControllerTest {
    @Test
    fun `it starts on what was read, so a machine that chose dark opens dark`() {
        val stored = Appearance(ThemeMode.DARK, AccentColor.GREEN)

        val controller = AppearanceController(SceneAppearance(stored), stored)

        assertEquals(stored, controller.appearance)
    }

    @Test
    fun `switching the theme keeps the accent, and choosing an accent keeps the theme`() =
        runBlocking<Unit> {
            val store = SceneAppearance()
            val controller = AppearanceController(store, Appearance())

            controller.useAccent(AccentColor.AMBER)
            controller.toggleTheme()

            assertEquals(Appearance(ThemeMode.DARK, AccentColor.AMBER), controller.appearance)
            assertEquals(Appearance(ThemeMode.DARK, AccentColor.AMBER), store.stored)
            assertEquals(2, store.writes)
        }

    @Test
    fun `what was chosen is written down, so the next start finds it`() =
        runBlocking<Unit> {
            val store = SceneAppearance()
            AppearanceController(store, Appearance()).useTheme(ThemeMode.DARK)

            // The next start is a fresh controller reading the same store.
            val next = AppearanceController(store, store.read())

            assertEquals(ThemeMode.DARK, next.appearance.themeMode)
        }

    @Test
    fun `a choice that could not be saved stays on screen and is said once`() =
        runBlocking<Unit> {
            val store = SceneAppearance(refuse = SettingsWriteFailure.NOT_WRITABLE)
            val controller = AppearanceController(store, Appearance())

            controller.useAccent(AccentColor.BLUE)

            // Applied, because they asked for it and the window can do it.
            assertEquals(AccentColor.BLUE, controller.appearance.accentColor)
            assertEquals(SettingsWriteFailure.NOT_WRITABLE, controller.notSaved)
        }

    @Test
    fun `a save that works clears a refusal that came before it`() =
        runBlocking<Unit> {
            val refusing = SceneAppearance(refuse = SettingsWriteFailure.COULD_NOT_WRITE)
            val controller = AppearanceController(refusing, Appearance())
            controller.useAccent(AccentColor.ROSE)
            assertEquals(SettingsWriteFailure.COULD_NOT_WRITE, controller.notSaved)

            val working = AppearanceController(SceneAppearance(), Appearance())
            working.useAccent(AccentColor.ROSE)

            assertNull(working.notSaved)
        }

    @Test
    fun `a file that could not be understood is reported and then answered by choosing`() =
        runBlocking<Unit> {
            val unreadable = Appearance(problem = AppearanceProblem.NOT_THE_EXPECTED_SHAPE)
            val store = SceneAppearance(unreadable)
            val controller = AppearanceController(store, unreadable)

            assertEquals(AppearanceProblem.NOT_THE_EXPECTED_SHAPE, controller.problem)

            controller.useTheme(ThemeMode.DARK)

            // What is written is a whole document, so the unreadable file is gone
            // and what is in use no longer carries a problem.
            assertNull(controller.appearance.problem)
            assertEquals(Appearance(ThemeMode.DARK, AccentColor.PURPLE), store.stored)
        }
}
