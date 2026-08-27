package dev.pnptracker.ui.feature.games

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Where a task's word is on screen, measured on text that was really laid out.
 *
 * A task is drawn and pressed through the boxes its name occupies, so the thing
 * worth testing is the geometry itself rather than a description of it. Compose
 * can lay text out without a window, which is enough: no test dependency is
 * added for this and nothing is drawn.
 *
 * The case that matters is a name that wrapped. One rectangle around both halves
 * would reach across the whole line ending above and the whole indent below, and
 * everything caught in it would be edged as the task and would open the task's
 * menu when pressed — words that are not the task's at all.
 */
class TaskBoxesTest {
    @OptIn(ExperimentalTextApi::class)
    private fun layoutOf(
        text: String,
        width: Int,
    ): TextLayoutResult =
        TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
            .measure(
                AnnotatedString(text),
                style = TextStyle(fontSize = 14.sp),
                constraints = Constraints(maxWidth = width),
            )

    /**
     * A width narrow enough that [name] is split across lines by the wrap.
     *
     * With other words beside it on both of those lines, which is the case worth
     * measuring: a name alone on its lines would sit inside a single rectangle
     * around it just as well, and prove nothing about what such a rectangle
     * swallows.
     */
    private fun wrapped(
        text: String,
        name: String,
    ): Triple<TextLayoutResult, Int, Int> {
        val start = text.indexOf(name)
        val end = start + name.length
        for (width in 40..600 step 2) {
            val layout = layoutOf(text, width)
            val firstLine = layout.getLineForOffset(start)
            val lastLine = layout.getLineForOffset(end - 1)
            if (firstLine != lastLine &&
                start > layout.getLineStart(firstLine) &&
                end < layout.getLineEnd(lastLine, visibleEnd = true)
            ) {
                return Triple(layout, start, end)
            }
        }
        error("no width wrapped the name with words beside it")
    }

    @Test
    fun `a name that fits on one line is one box`() {
        val text = "önce Gri token sonra"
        val layout = layoutOf(text, width = 4000)
        val start = text.indexOf("Gri token")

        val boxes = layout.boxesOfRange(start, start + "Gri token".length)

        assertEquals(1, boxes.size, "a name on one line was cut into pieces")
    }

    @Test
    fun `a name that wrapped is one box for each line it reaches`() {
        val text = "önce Gri token sonra"
        val (layout, start, end) = wrapped(text, "Gri token")

        val boxes = layout.boxesOfRange(start, end)

        assertEquals(
            layout.getLineForOffset(end - 1) - layout.getLineForOffset(start) + 1,
            boxes.size,
            "the boxes do not cover the lines the name is on",
        )
        boxes.forEach { box ->
            assertTrue(box.width > 0f && box.height > 0f, "a box has no size")
        }
    }

    @Test
    fun `no box of a wrapped name covers a character that is not the name's`() {
        // The whole point of one box per line. A single rectangle around both
        // halves would swallow the text at the end of the line above and the
        // start of the line below, and make it press the task's menu.
        val text = "önce Gri token sonra ve biraz daha yazı"
        val (layout, start, end) = wrapped(text, "Gri token")
        val boxes = layout.boxesOfRange(start, end)

        text.indices.filter { it < start || it >= end }.forEach { offset ->
            val box = layout.getBoundingBox(offset)
            val x = box.left + box.width / 2f
            val y = box.top + box.height / 2f
            assertTrue(
                boxes.none { it.left <= x && x <= it.right && it.top <= y && y <= it.bottom },
                "the character at $offset is not the task's but is inside its boxes",
            )
        }
    }

    @Test
    fun `every character of a wrapped name is inside one of its boxes`() {
        val text = "önce Gri token sonra"
        val (layout, start, end) = wrapped(text, "Gri token")
        val boxes = layout.boxesOfRange(start, end)

        (start until end).forEach { offset ->
            val box = layout.getBoundingBox(offset)
            val x = box.left + box.width / 2f
            val y = box.top + box.height / 2f
            assertTrue(
                boxes.any { it.left <= x && x <= it.right && it.top <= y && y <= it.bottom },
                "the character at $offset is the task's but is in none of its boxes",
            )
        }
    }

    @Test
    fun `the popover hangs from the line the name starts on`() {
        val text = "önce Gri token sonra"
        val (layout, start, end) = wrapped(text, "Gri token")

        val anchor = assertNotNull(layout.boxOfRange(start, end))

        val line = layout.getLineForOffset(start)
        assertEquals(layout.boxesOfRange(start, end).first(), anchor)
        assertTrue(
            anchor.top >= layout.getLineTop(line) && anchor.bottom <= layout.getLineBottom(line),
            "the anchor is not on the line the name starts on",
        )
    }

    @Test
    fun `a name below the last line a cell shows has no box`() {
        val text = "birinci satır\nikinci satır\nüçüncü satır\nGri token"
        val layout =
            TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
                .measure(
                    AnnotatedString(text),
                    style = TextStyle(fontSize = 14.sp),
                    maxLines = 3,
                    constraints = Constraints(maxWidth = 4000),
                )
        val start = text.indexOf("Gri token")

        assertEquals(emptyList(), layout.boxesOfRange(start, start + "Gri token".length))
    }

    // ------------------------- a task with swatches for its extra colours

    /**
     * The drawn string of a task made in more colours than its name is long.
     *
     * Written the way the cell writes it: the name, then a gap and a swatch for
     * each colour with no character of its own, then the count. What matters is
     * that the whole of it is one run, so the boxes a handle is built from cover
     * every swatch — a colour the user can see and cannot press would be a lie
     * about what is clickable.
     */
    private fun drawnTaskOf(
        name: String,
        markers: Int,
        before: String = "Basılacak: ",
        after: String = ", kutu ayrı.",
    ): Triple<String, Int, Int> {
        val swatches = (1..markers).joinToString(separator = "") { " \u00A0\u00A0" }
        val text = before + name + swatches + " ×10" + after
        return Triple(text, before.length, before.length + name.length + swatches.length)
    }

    @Test
    fun `the swatches of a task are inside the boxes its handle is built from`() {
        val (text, start, end) = drawnTaskOf(name = "Ok", markers = 2)
        val layout = layoutOf(text, width = 4000)

        val boxes = layout.boxesOfRange(start, end)

        assertEquals(1, boxes.size, "the run was cut up on one line")
        val box = boxes.single()
        // Every character of the run, swatches included, is under the handle.
        (start until end).forEach { offset ->
            val glyph = layout.getBoundingBox(offset)
            assertTrue(
                glyph.left >= box.left - 0.5f && glyph.right <= box.right + 0.5f,
                "the character at $offset falls outside the task's box",
            )
        }
        // And the count that follows is not.
        val count = layout.getBoundingBox(text.indexOf("×10"))
        assertTrue(count.left >= box.right - 0.5f, "the count was swallowed by the task's box")
    }

    @Test
    fun `a swatch is never taken apart by a line ending`() {
        val (text, _, _) = drawnTaskOf(name = "Ok", markers = 4)
        val swatch = text.indexOf("\u00A0")

        (40..600 step 2).forEach { width ->
            val layout = layoutOf(text, width)
            assertEquals(
                layout.getLineForOffset(swatch),
                layout.getLineForOffset(swatch + 1),
                "a swatch was split across two lines at width $width",
            )
        }
    }

    @Test
    fun `a task whose swatches wrapped is pressable on every line it reaches`() {
        val (text, start, end) = drawnTaskOf(name = "Ok", markers = 6)
        // Narrow enough that the swatches cannot all sit beside the name.
        val layout =
            (40..600 step 2)
                .map { layoutOf(text, it) }
                .first { layout ->
                    layout.getLineForOffset(start) != layout.getLineForOffset(end - 1)
                }

        val boxes = layout.boxesOfRange(start, end)

        assertTrue(boxes.size > 1, "a wrapped task gave a single box")
        // Every line the run reaches has a box of its own, and each is a real
        // shape rather than an empty one.
        val lines = (start until end).map { layout.getLineForOffset(it) }.distinct()
        assertEquals(lines.size, boxes.size, "a line the task reaches has no box")
        boxes.forEach { assertTrue(it.width > 0f && it.height > 0f, "an empty box was handed to the handle") }
    }

    @Test
    fun `a name split across colours covers exactly the name`() {
        val text = "Basılacak: Yarasa ×10"
        val name = "Yarasa"
        val start = text.indexOf(name)
        val layout = layoutOf(text, width = 4000)
        // The three pieces `ya | ra | sa` are drawn as three stretches of the
        // same word, so together they occupy what the whole name occupies.
        val pieces = listOf(start to start + 2, start + 2 to start + 4, start + 4 to start + 6)

        val whole = assertNotNull(layout.boxesOfRange(start, start + name.length).singleOrNull())
        val painted = pieces.map { (from, to) -> assertNotNull(layout.boxesOfRange(from, to).singleOrNull()) }

        assertEquals(whole.left, painted.first().left, "the first colour does not start where the name does")
        assertEquals(whole.right, painted.last().right, "the last colour does not end where the name does")
        painted.zipWithNext().forEach { (left, right) ->
            assertEquals(left.right, right.left, "the colours leave a gap or overlap between them")
        }
    }
}
