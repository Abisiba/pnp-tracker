package dev.pnptracker.ui.feature.games

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a popover lands relative to the word it belongs to.
 *
 * PLAN 12.5 opens it immediately above the word and PLAN 17 will not let it
 * cover the screen or put its actions out of reach. All of that is arithmetic,
 * so it is checked as arithmetic rather than by looking at a window.
 */
class AnchoredAboveWordTest {
    private val window = IntSize(width = 1000, height = 600)
    private val panel = IntSize(width = 200, height = 120)
    private val provider = AnchoredAboveWord(gap = 4)

    private fun positionOf(
        anchor: IntRect,
        windowSize: IntSize = window,
        popup: IntSize = panel,
    ) = provider.calculatePosition(anchor, windowSize, LayoutDirection.Ltr, popup)

    private fun wordAt(
        left: Int,
        top: Int,
        width: Int = 60,
        height: Int = 18,
    ) = IntRect(left = left, top = top, right = left + width, bottom = top + height)

    @Test
    fun `it opens immediately above the word when there is room`() {
        val word = wordAt(left = 300, top = 400)

        val at = positionOf(word)

        assertEquals(300, at.x, "the popover did not line up with the word")
        assertEquals(400 - 120 - 4, at.y, "the popover is not just above the word")
    }

    @Test
    fun `it flips below the word when there is no room above`() {
        // A task near the top of the window has nothing above it; opening there
        // would put half the panel off the screen.
        val word = wordAt(left = 300, top = 10)

        val at = positionOf(word)

        assertEquals(10 + 18 + 4, at.y, "the popover did not flip below the word")
    }

    @Test
    fun `it is kept inside the window on the right`() {
        val word = wordAt(left = 960, top = 400)

        val at = positionOf(word)

        assertEquals(800, at.x, "the popover ran off the right edge")
        assertTrue(at.x + panel.width <= window.width)
    }

    @Test
    fun `it is kept inside the window on the left`() {
        val word = wordAt(left = -40, top = 400)

        val at = positionOf(word)

        assertEquals(0, at.x, "the popover ran off the left edge")
    }

    @Test
    fun `a word with no room above is placed below and still fits`() {
        // A short window: there is no room above the word, and flipping below
        // still has to leave the whole panel inside.
        val word = wordAt(left = 300, top = 10)

        val at = positionOf(word, windowSize = IntSize(1000, 200))

        assertEquals(10 + 18 + 4, at.y)
        assertTrue(at.y >= 0, "the popover was placed above the window")
        assertTrue(at.y + panel.height <= 200, "the popover ran off the bottom edge")
    }

    @Test
    fun `a word low in a short window is pulled back inside it`() {
        val word = wordAt(left = 300, top = 150)

        val at = positionOf(word, windowSize = IntSize(1000, 200))

        assertTrue(at.y + panel.height <= 200, "the popover ran off the bottom edge")
    }

    @Test
    fun `a popover taller than the window is still placed at the top of it`() {
        val word = wordAt(left = 300, top = 20)

        val at = positionOf(word, windowSize = IntSize(1000, 100), popup = IntSize(200, 400))

        assertEquals(0, at.y, "a panel too tall for the window was placed off it")
    }

    @Test
    fun `it follows the word rather than remembering where it was`() {
        // Scrolling the table moves the word; the same anchor arithmetic run
        // against its new bounds is what keeps the popover on it.
        val before = positionOf(wordAt(left = 300, top = 400))
        val after = positionOf(wordAt(left = 300, top = 340))

        assertEquals(before.y - 60, after.y, "the popover did not move with the word")
    }
}
