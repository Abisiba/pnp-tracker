package dev.pnptracker.ui.feature.colors

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parts of the picker no unit can observe from outside it.
 *
 * What the picker *does* is settled by the controller tests and by the colour
 * arithmetic's own; what is left is what it is made of, and no Compose test
 * dependency may be added to look at that. So these read the source, pinned to
 * the things PLAN 5.7 either requires or forbids rather than to how they are
 * written.
 */
class ColorPickerLayoutTest {
    private fun read(path: String): String = Files.readString(Path.of(path))

    private val picker = read("src/commonMain/kotlin/dev/pnptracker/ui/feature/colors/CustomColorPicker.kt")
    private val colorScreen = read("src/commonMain/kotlin/dev/pnptracker/ui/feature/colors/ColorCatalogueScreen.kt")
    private val gameScreen = read("src/commonMain/kotlin/dev/pnptracker/ui/feature/games/GameTableScreen.kt")

    private val everySurface = listOf(picker, colorScreen, gameScreen)

    @Test
    fun `every place these tests read a screen at is really in it`() {
        val anchors =
            Regex("""substring(?:After|Before)\("((?:[^"\\]|\\.)*)"\)""")
                .findAll(read("src/desktopTest/kotlin/dev/pnptracker/ui/feature/colors/ColorPickerLayoutTest.kt"))
                .map { it.groupValues[1].replace("\\n", "\n") }
                .toSet()

        anchors.forEach { anchor ->
            assertTrue(
                everySurface.any { anchor in it },
                "these tests read a screen at a place it no longer has: $anchor",
            )
        }
    }

    @Test
    fun `the picker is the three things PLAN 5-7 asks for and nothing more`() {
        assertTrue(picker.contains("BaseColorSquares("), "the twelve squares are missing")
        assertTrue(picker.contains("ColorWheel("), "the wheel is missing")
        assertTrue(picker.contains("BrightnessControl("), "the one brightness control is missing")
        assertTrue(picker.contains("ColorPreview("), "there is nothing showing what is being made")
    }

    @Test
    fun `nothing anywhere offers a standing hex field or a channel box`() {
        // PLAN 5.7 names these as what the small picker is not. A field that
        // takes a value typed as text would also be a second place the colour
        // lives, and two places drift.
        everySurface.forEach { source ->
            assertTrue(
                "hexLabel" !in source && "hexHint" !in source && "hexInvalid" !in source,
                "a standing hex field is still offered",
            )
        }
        listOf("Strings.Colors.hexLabel", "RGB", "HSL", "HSV").forEach { forbidden ->
            assertTrue(forbidden !in picker, "the picker offers $forbidden, which PLAN 5.7 rules out")
        }
        // One adjustable control and no text field at all: the brightness is the
        // only extra PLAN 5.7 allows, and a channel box would be a second place
        // the colour lives.
        assertEquals(1, Regex("""Slider\(""").findAll(picker).count(), "the picker has more than one slider")
        assertEquals(0, Regex("""TextField\(""").findAll(picker).count(), "the picker takes a colour as typed text")
    }

    @Test
    fun `no colour the picker writes is anything but fully opaque`() {
        // Alpha is not offered anywhere, and the value written comes from a type
        // that has no alpha at all to write.
        assertTrue("Color.Transparent" !in picker || picker.contains("middle.copy(alpha = 0f)"))
        assertTrue(
            "copy(alpha" !in picker.substringAfter("private fun ColorPreview("),
            "the preview draws a colour that is not fully opaque",
        )
    }

    @Test
    fun `the wheel takes the keyboard as well as the pointer`() {
        val wheel = picker.substringAfter("private fun ColorWheel(").substringBefore("private fun Offset.asWheelPoint(")
        assertTrue(wheel.contains(".focusable("), "the wheel cannot be reached with the keyboard")
        assertTrue(wheel.contains("Key.DirectionRight"), "the wheel does not answer the arrow keys")
        assertTrue(wheel.contains("Key.DirectionLeft"))
        assertTrue(wheel.contains("Key.DirectionUp"))
        assertTrue(wheel.contains("Key.DirectionDown"))
        assertTrue(wheel.contains("detectDragGestures("), "the wheel cannot be dragged")
        assertTrue(wheel.contains("detectTapGestures"), "the wheel cannot be clicked")
    }

    @Test
    fun `the wheel says in words which colour it is standing on`() {
        val wheel = picker.substringAfter("private fun ColorWheel(").substringBefore("private fun Offset.asWheelPoint(")
        assertTrue(wheel.contains("contentDescription = label"), "the wheel has no name a reader could hear")
        assertTrue(wheel.contains("stateDescription = stateText"), "the wheel never says where it is")
        assertTrue(wheel.contains("Strings.Colors.wheelState"), "the hue and saturation are not read out")
    }

    @Test
    fun `the brightness is an ordinary control, so the keyboard already works on it`() {
        val brightness =
            picker.substringAfter("private fun BrightnessControl(").substringBefore("private fun ColorPreview(")
        assertTrue(brightness.contains("Slider("), "the brightness is not a standard adjustable control")
        assertTrue(brightness.contains("stateDescription = stateText"), "the brightness never says where it is")
        assertTrue(brightness.contains("color.brightness"), "the slider is not reading the brightness")
        assertTrue(
            "hue" !in brightness.substringAfter("Slider("),
            "the brightness control touches the place on the wheel",
        )
    }

    @Test
    fun `the squares carry their written names and say which one is chosen`() {
        val squares =
            picker.substringAfter("private fun BaseColorSquares(").substringBefore("private fun ColorWheel(")
        assertTrue(squares.contains("Strings.Colors.swatch"), "a square has no name a reader could hear")
        assertTrue(squares.contains("stateDescription = stateText"), "a chosen square is shown only by its border")
        assertTrue(squares.contains("selectableGroup()"), "the squares are not one group of choices")
        assertTrue(squares.contains("readableInkOn(fill)"), "the mark on a chosen square is a fixed colour")
        assertTrue(squares.contains("baseColorsIn(catalogue)"), "the squares are not the base colours of the catalogue")
    }

    @Test
    fun `the squares are large enough to click`() {
        val target = Regex("""private val SquareTarget = (\d+)\.dp""").find(picker)!!.groupValues[1].toInt()
        assertTrue(target >= 32, "a square is $target dp across, which is too small to hit")
    }

    @Test
    fun `the wheel wraps rather than overflowing the panel it sits in`() {
        assertTrue(picker.contains("FlowRow("), "the twelve squares cannot wrap, so they run out of a narrow panel")
        val size = Regex("""private val WheelSize = (\d+)\.dp""").find(picker)!!.groupValues[1].toInt()
        val cellWidth = Regex("""private val CellColumnWidth = (\d+)\.dp""").find(gameScreen)!!.groupValues[1].toInt()
        assertTrue(size <= cellWidth - 20, "the wheel is $size dp in a $cellWidth dp cell, so it would be clipped")
    }

    @Test
    fun `the sweep is built again only when the brightness changes`() {
        // A drag is hundreds of moves; rebuilding a run of colour conversions on
        // each of them would be work for no change at all.
        val wheel = picker.substringAfter("private fun ColorWheel(").substringBefore("private fun Offset.asWheelPoint(")
        assertTrue(wheel.contains("remember(color.brightness)"), "the wheel's colours are rebuilt on every move")
        assertEquals(2, Regex("""remember\(color\.brightness\)""").findAll(wheel).count())
    }

    @Test
    fun `every surface a colour is chosen on offers to make one`() {
        // PLAN 5.7 has one creation flow, and it is reachable from wherever a
        // colour is wanted rather than only from the colour section.
        // Four: the one definition, and the three places a colour is chosen.
        assertEquals(
            4,
            Regex("""NewColorButton\(""").findAll(gameScreen).count(),
            "a place where a colour is chosen has no way to make one",
        )
        listOf(
            "NewColorTarget.SingleDraft",
            "NewColorTarget.BatchRow(row)",
            "NewColorTarget.MulticolorList",
            "NewColorTarget.EditedTask",
        ).forEach { target ->
            assertTrue(target in gameScreen, "nothing ever aims a colour at $target")
        }
        assertTrue(colorScreen.contains("ColorPicker("), "the colour section does not use the same picker")
        assertTrue(gameScreen.contains("ColorPicker("), "the task surfaces do not use the same picker")
    }

    @Test
    fun `there is one wheel and every surface draws that one`() {
        // A second implementation is a second set of bugs and a second thing to
        // keep in step with the arithmetic.
        listOf(colorScreen, gameScreen).forEach { surface ->
            // The wheel, not drawing in general. A surface may well have a
            // canvas of its own — the tick on a task is one — and banning the
            // tool rather than the thing built with it would stop honest work
            // while a wheel assembled some other way still slipped through.
            assertTrue("sweepGradient" !in surface, "a surface builds its own colour wheel")
            assertTrue("ColorWheel(" !in surface, "a surface draws a wheel of its own")
        }
        assertEquals(1, Regex("""fun ColorWheel\(""").findAll(picker).count())
        assertEquals(1, Regex("""fun ColorPicker\(""").findAll(picker).count())
    }

    @Test
    fun `nothing here edits, deletes or restores a colour`() {
        // PLAN 12.14 gives all three to the next step, and a button that looks
        // like it works and does not is worse than one that is not there yet.
        everySurface.forEach { source ->
            listOf("deleteColor", "removeColor", "restoreBase", "editColorName").forEach { later ->
                assertTrue(later !in source, "$later is a later step's work and is already on a screen")
            }
        }
    }

    @Test
    fun `the picker answers the keyboard from inside itself`() {
        // The cell's own key handler sits on the text field, which is a sibling
        // of this panel rather than an ancestor of it, so an Escape typed with
        // the keyboard inside the picker never reached it. It closes itself.
        val panel =
            gameScreen.substringAfter("private fun NewColorPanel(").substringBefore("private fun NewColorButton(")
        assertTrue(panel.contains("onPreviewKeyEvent"), "the picker does not listen for keys of its own")
        assertTrue(
            panel.contains("controller.cancelColorCreation()"),
            "Escape typed inside the picker does not close it",
        )
        assertTrue(panel.contains("Key.Escape"), "the picker does not answer Escape")
        assertTrue(panel.contains("event.isCtrlPressed"), "Ctrl+Enter typed inside the picker does not save it")
    }

    @Test
    fun `the popover asks for the keyboard when a layer opens, not when a letter is typed`() {
        // Keyed on the whole of the open work, the popover's focus effect ran on
        // every keystroke and took the keyboard back off whatever field was
        // being typed in: a name kept only its first letter. It is keyed on
        // which surface is showing instead.
        val popover =
            gameScreen.substringAfter("private fun TaskPopover(").substringBefore("private fun TaskMenuActions(")
        assertTrue(
            "LaunchedEffect(state.work" !in popover,
            "the popover asks for the keyboard again on every change to what is being typed",
        )
        assertTrue(
            popover.contains("LaunchedEffect(openLayer, menu.taskId, state.focusRecall)"),
            "the popover no longer asks for the keyboard when a layer opens",
        )
    }

    @Test
    fun `the picker is the innermost surface and closes on its own`() {
        assertTrue(
            gameScreen.contains("creator != null -> controller.cancelColorCreation()"),
            "Escape does not close the picker before the panel under it",
        )
        assertTrue(
            gameScreen.contains("creator != null -> saveColor()"),
            "Ctrl+Enter in the cell does not reach the picker",
        )
        assertTrue(
            gameScreen.contains("readOnly = composer != null || creator != null"),
            "the cell's words can be typed over while a colour is being made above them",
        )
    }
}
