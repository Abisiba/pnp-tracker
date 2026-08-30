package dev.pnptracker.ui.feature.colors

import androidx.compose.ui.graphics.Color
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.HsbColor
import dev.pnptracker.domain.colors.baseColors
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.theme.contrastRatio
import dev.pnptracker.ui.theme.opaqueColorOf
import dev.pnptracker.ui.theme.readableInkOn
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the colour picker says, resolved through the real Turkish catalogue. */
class ColorPickerPresentationTest {
    private fun textOf(
        resource: StringResource,
        vararg arguments: Any,
    ): String = runBlocking { getString(resource, *arguments) }

    @Test
    fun `every word the picker says is really in the catalogue and reads as Turkish`() {
        val everything =
            listOf(
                Strings.Colors.create,
                Strings.Colors.newAction,
                Strings.Colors.newTitle,
                Strings.Colors.nameLabel,
                Strings.Colors.nameRequired,
                Strings.Colors.baseLabel,
                Strings.Colors.baseNone,
                Strings.Colors.wheelLabel,
                Strings.Colors.wheelHint,
                Strings.Colors.brightnessLabel,
                Strings.Colors.previewUnnamed,
                Strings.Colors.save,
                Strings.Colors.saving,
                Strings.Colors.discard,
                Strings.Colors.strandedDismiss,
                Strings.Colors.errorCouldNotSave,
                Strings.Colors.errorNameUsed,
                Strings.Colors.errorNameIsAlias,
                Strings.Colors.errorColorGone,
            )

        everything.forEach { resource ->
            val text = textOf(resource)
            assertTrue(text.isNotBlank(), "a word the picker says is missing")
            assertEquals(text, text.trim(), "'$text' has stray whitespace around it")
            assertTrue('\\' !in text, "an escape reached the user in: $text")
            assertTrue('%' !in text, "an unfilled placeholder reached the user in: $text")
        }
    }

    @Test
    fun `every line that takes a value fills it in`() {
        val filled =
            listOf(
                textOf(Strings.Colors.swatch, "Lacivert"),
                textOf(Strings.Colors.wheelState, "210", "64"),
                textOf(Strings.Colors.brightnessState, "48"),
                textOf(Strings.Colors.previewOf, "Lacivert", "#1A237E"),
                textOf(Strings.Colors.hexShared, "Gri, Duman"),
                textOf(Strings.Colors.stranded, "Lacivert"),
            )

        filled.forEach { text ->
            assertTrue('%' !in text, "an unfilled placeholder reached the user in: $text")
            assertTrue('\\' !in text, "an escape reached the user in: $text")
        }
    }

    @Test
    fun `the wheel says both of the numbers it is standing on`() {
        val said = textOf(Strings.Colors.wheelState, "210", "64")

        assertTrue("210" in said, "the hue was not read out: $said")
        assertTrue("64" in said, "the saturation was not read out: $said")
    }

    @Test
    fun `the preview names the colour and the value together`() {
        val said = textOf(Strings.Colors.previewOf, "Lacivert", "#1A237E")

        assertTrue("Lacivert" in said, "the name was left out of the preview: $said")
        assertTrue("#1A237E" in said, "the value was left out of the preview: $said")
    }

    @Test
    fun `a colour with no name yet is described as having none rather than left silent`() {
        val said = textOf(Strings.Colors.previewOf, textOf(Strings.Colors.previewUnnamed), "#1A237E")

        assertTrue(said.isNotBlank())
        assertTrue("#1A237E" in said)
    }

    @Test
    fun `the same-value remark names the colours that carry it`() {
        val said = textOf(Strings.Colors.hexShared, "Gri, Duman")

        assertTrue("Gri" in said && "Duman" in said, "the remark did not say who carries the value: $said")
    }

    @Test
    fun `a colour saved with nowhere to go is said to be saved and said not to be applied`() {
        val said = textOf(Strings.Colors.stranded, "Lacivert")

        assertTrue("Lacivert" in said, "the colour was not named: $said")
        assertTrue(said.length > "Lacivert".length + 10, "only half of what happened was said: $said")
    }

    @Test
    fun `a name that is another colour's alias is explained without showing aliases`() {
        val said = textOf(Strings.Colors.errorNameIsAlias)

        // PLAN 5.11 keeps aliases inside the system, so the user is told what to
        // do rather than shown a table they cannot manage.
        assertTrue(said.isNotBlank())
        assertTrue("alias" !in said.lowercase(), "the user was shown the word alias: $said")
    }

    @Test
    fun `the four ways a colour can be refused all read differently`() {
        val sentences =
            ColorSetupFailure.entries.map {
                textOf(
                    when (it) {
                        ColorSetupFailure.COULD_NOT_SAVE -> Strings.Colors.errorCouldNotSave
                        ColorSetupFailure.NAME_ALREADY_USED -> Strings.Colors.errorNameUsed
                        ColorSetupFailure.NAME_IS_ANOTHER_COLORS_ALIAS -> Strings.Colors.errorNameIsAlias
                        ColorSetupFailure.COLOR_NO_LONGER_EXISTS -> Strings.Colors.errorColorGone
                    },
                )
            }

        assertEquals(sentences.size, sentences.toSet().size, "two refusals read the same: $sentences")
    }

    @Test
    fun `the mark on a chosen square can be seen on every colour there is`() {
        // The two the user reaches for first are exactly the two a fixed mark
        // disappears on, so the mark is drawn in ink chosen for the colour under
        // it and has to clear PLAN 17's ratio on all of them.
        val everyColor =
            baseColors.map { it.hex } +
                listOf("#FFFFFF", "#000000", "#111111", "#FEFEFE", "#7F7F7F", "#808080", "#FDD835")
        everyColor.forEach { hex ->
            val fill = opaqueColorOf(hex)
            val ink = readableInkOn(fill)
            assertTrue(
                contrastRatio(ink, fill) >= 4.5,
                "the mark on $hex would be at ${contrastRatio(ink, fill)}:1, which cannot be seen",
            )
            assertTrue(ink == Color.Black || ink == Color.White, "the mark was drawn in something other than ink")
        }
    }

    @Test
    fun `the pointer's two rings mean one of them always shows`() {
        // The mark on the wheel is a white ring with a black one inside it, so
        // whichever the colour underneath swallows, the other is still there.
        listOf("#FFFFFF", "#000000", "#808080").forEach { hex ->
            val under = opaqueColorOf(hex)
            val best = maxOf(contrastRatio(Color.White, under), contrastRatio(Color.Black, under))
            assertTrue(best >= 4.5, "neither ring could be seen on $hex")
        }
    }

    @Test
    fun `every colour the wheel can produce is written as the catalogue wants it`() {
        // The catalogue refuses anything but `#RRGGBB`, so a value the wheel
        // produced and the catalogue would refuse is a colour the user can make
        // and cannot save.
        var checked = 0
        for (hue in 0 until 360 step 7) {
            for (saturation in 0..10) {
                for (brightness in 0..10) {
                    val hex = HsbColor.of(hue.toFloat(), saturation / 10f, brightness / 10f).toHex()
                    assertTrue(
                        Regex("#[0-9A-F]{6}").matches(hex),
                        "the wheel produced $hex, which the catalogue would refuse",
                    )
                    checked += 1
                }
            }
        }
        assertTrue(checked > 6000)
    }
}
