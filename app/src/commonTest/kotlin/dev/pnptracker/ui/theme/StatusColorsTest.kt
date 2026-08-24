package dev.pnptracker.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The colour a finished row is actually painted.
 *
 * Asking the value rather than trusting the name. Material's baseline
 * `tertiaryContainer` sounds like it could be anything and is in fact a mauve,
 * so a row painted with it would have been the wrong colour under a name that
 * read correctly in the source.
 */
class StatusColorsTest {
    private fun channelLuminance(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * channelLuminance(color.red) +
            0.7152 * channelLuminance(color.green) +
            0.0722 * channelLuminance(color.blue)

    private fun contrastOf(
        first: Color,
        second: Color,
    ): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun assertIsGreen(
        color: Color,
        what: String,
    ) {
        assertTrue(color.green > color.red, "$what is not green: its red is at least its green")
        assertTrue(color.green > color.blue, "$what is not green: its blue is at least its green")
    }

    @Test
    fun `the finished row is painted a real green in both themes`() {
        assertIsGreen(LightStatusColors.completedContainer, "the light finished ground")
        assertIsGreen(DarkStatusColors.completedContainer, "the dark finished ground")
    }

    @Test
    fun `what is written on a finished row can be read in both themes`() {
        // PLAN 17 asks for readable contrast. 4.5:1 is what ordinary body text
        // needs; both pairs are chosen to clear it comfortably.
        listOf(
            "light" to LightStatusColors,
            "dark" to DarkStatusColors,
        ).forEach { (name, colors) ->
            val contrast = contrastOf(colors.onCompletedContainer, colors.completedContainer)
            assertTrue(contrast >= 4.5, "the $name finished row reads at only $contrast:1")
        }
    }

    @Test
    fun `the finished ground still contrasts with the ordinary one`() {
        // A green so pale it matched the surface would say nothing at a glance.
        val light = contrastOf(LightStatusColors.completedContainer, lightColorScheme().surface)
        val dark = contrastOf(DarkStatusColors.completedContainer, darkColorScheme().surface)
        assertTrue(light > 1.1, "the light finished row is indistinguishable from an ordinary one")
        assertTrue(dark > 1.1, "the dark finished row is indistinguishable from an ordinary one")
    }

    @Test
    fun `the finished colour is not borrowed from a Material role`() {
        // The point of stating it: nothing in the scheme is this colour, so it
        // cannot drift when the scheme changes.
        listOf(lightColorScheme().tertiaryContainer, darkColorScheme().tertiaryContainer).forEach { borrowed ->
            assertTrue(
                borrowed != LightStatusColors.completedContainer &&
                    borrowed != DarkStatusColors.completedContainer,
                "the finished colour is a Material role wearing a green name",
            )
        }
    }
}
