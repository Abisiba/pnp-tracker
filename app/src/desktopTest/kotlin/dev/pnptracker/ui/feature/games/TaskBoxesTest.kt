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
}
