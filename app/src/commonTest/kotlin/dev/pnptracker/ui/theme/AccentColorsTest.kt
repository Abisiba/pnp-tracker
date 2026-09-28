package dev.pnptracker.ui.theme

import androidx.compose.ui.graphics.Color
import dev.pnptracker.domain.settings.AccentColor
import dev.pnptracker.domain.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The accent palette: readable in both themes, and nothing to do with the paints
 * the user assigns to tasks (PLAN 12.16, 17).
 */
class AccentColorsTest {
    /** What PLAN 17 asks of readable text. */
    private val readable = 4.5

    @Test
    fun `every accent can be written on in both themes`() {
        ThemeMode.entries.forEach { theme ->
            AccentColor.entries.forEach { accent ->
                val paint = accentPaintOf(accent, theme)

                val ratio = contrastRatio(paint.ink, paint.colour)
                assertTrue(ratio >= readable, "$accent in $theme writes at ${"%.2f".format(ratio)}:1")
            }
        }
    }

    @Test
    fun `the default accent is the one the application has always had`() {
        // Material's own baseline primary pair. Changing this would change how
        // every existing installation looks without anybody asking for it.
        assertEquals(Color(0xFF6750A4), accentPaintOf(AccentColor.PURPLE, ThemeMode.LIGHT).colour)
        assertEquals(Color.White, accentPaintOf(AccentColor.PURPLE, ThemeMode.LIGHT).ink)
        assertEquals(Color(0xFFD0BCFF), accentPaintOf(AccentColor.PURPLE, ThemeMode.DARK).colour)
    }

    @Test
    fun `no two accents look the same in either theme`() {
        ThemeMode.entries.forEach { theme ->
            val colours = AccentColor.entries.map { accentPaintOf(it, theme).colour }

            assertEquals(colours.size, colours.toSet().size, "two accents are the same colour in $theme")
        }
    }

    @Test
    fun `an accent is a different colour in the dark than in the light`() {
        // The light theme takes a mid tone written on in white and the dark theme a
        // pale one written on in near-black; an accent that ignored the theme would
        // be unreadable in one of them.
        AccentColor.entries.forEach { accent ->
            assertNotEquals(
                accentPaintOf(accent, ThemeMode.LIGHT).colour,
                accentPaintOf(accent, ThemeMode.DARK).colour,
                "$accent is drawn the same either way",
            )
        }
    }

    @Test
    fun `changing the accent changes no colour a task could be painted in`() {
        // The one fear a colour control in this application should answer: the
        // paints in the catalogue are the user's data and are drawn from their own
        // hex values, which no accent is an input to. Asked here of the very
        // functions the task surfaces use.
        val paints = listOf("#FF0000", "#6750A4", "#FFFFFF", "#123456").map { opaqueColorOf(it) }

        val before = paints.map { readableInkOn(it) to visibleEdgeOn(it) }
        AccentColor.entries.forEach { accent ->
            ThemeMode.entries.forEach { theme ->
                accentPaintOf(accent, theme)
                assertEquals(before, paints.map { readableInkOn(it) to visibleEdgeOn(it) }, "$accent in $theme moved a task's colour")
            }
        }
        // And a catalogue colour is still exactly the hex it was given.
        assertEquals(opaqueColorOf("#6750A4"), opaqueColorOf("#6750A4"))
    }
}
