package dev.pnptracker.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeModeTest {
    @Test
    fun `toggling light gives dark`() {
        assertEquals(ThemeMode.DARK, ThemeMode.LIGHT.toggled())
    }

    @Test
    fun `toggling dark gives light`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.DARK.toggled())
    }

    @Test
    fun `toggling twice comes back to where it started`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, mode.toggled().toggled())
        }
    }

    @Test
    fun `there are only the light and dark appearances`() {
        assertEquals(listOf(ThemeMode.LIGHT, ThemeMode.DARK), ThemeMode.entries)
    }
}
