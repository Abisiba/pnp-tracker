package dev.pnptracker.ui.feature.games

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import dev.pnptracker.domain.model.EntityId

/**
 * Where one task's word sits on screen, and which task it is.
 *
 * The bounds come from the real text layout rather than from anything guessed:
 * a word moves when the column is scrolled, when the window is resized, when the
 * text around it changes, and a popover pinned to a remembered coordinate would
 * be pointing at whatever happened to be there afterwards.
 */
data class TaskAnchor(
    val taskId: EntityId,
    val bounds: Rect,
)

/**
 * The box a stretch of laid-out text occupies.
 *
 * A run that wrapped is anchored on the line it begins on, which is where the
 * word starts and where a reader's eye is. Taking the union across lines would
 * give a box covering text that is not the task's at all.
 */
fun TextLayoutResult.boxOfRange(
    start: Int,
    end: Int,
): Rect? {
    if (start >= end || start < 0 || end > layoutInput.text.length) return null
    val lastVisible = getLineEnd(lineCount - 1, visibleEnd = true)
    if (start >= lastVisible) return null
    val stop = minOf(end, lastVisible)
    if (stop <= start) return null
    val first = getBoundingBox(start)
    val firstLine = getLineForOffset(start)
    return if (firstLine == getLineForOffset(stop - 1)) {
        val last = getBoundingBox(stop - 1)
        Rect(first.left, first.top, last.right, last.bottom)
    } else {
        Rect(first.left, first.top, getLineRight(firstLine), first.bottom)
    }
}

/**
 * Puts a small panel over the word it belongs to.
 *
 * PLAN 12.5 opens it immediately above the word, and PLAN 17 will not have it
 * cover the screen or lose the focus. So it goes above when there is room and
 * below when there is not, and it is kept inside the window either way — a panel
 * half off the edge is a panel with actions nobody can reach.
 *
 * A plain class with one arithmetic method on purpose: where a popover lands is
 * a decision worth being able to check without a window on the screen.
 */
class AnchoredAboveWord(
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val widest = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = anchorBounds.left.coerceIn(0, widest)
        val above = anchorBounds.top - popupContentSize.height - gap
        val below = anchorBounds.bottom + gap
        val tallest = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        // Above when it fits, below when it does not, and never past the edge.
        val y = if (above >= 0) above else below.coerceIn(0, tallest)
        return IntOffset(x, y)
    }
}
