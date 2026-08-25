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
 * The boxes a stretch of laid-out text occupies: one for each line it covers.
 *
 * A run that wrapped is two shapes on screen, not one. A single rectangle around
 * both would cover the whole width between them — the end of the line above and
 * the start of the line below — and make text that is not the task's part of it,
 * to look at and to press. So each line the run reaches gets its own box, and
 * the space between them belongs to whatever is really there.
 *
 * Empty when the run is not on screen at all: a cell shows a few lines and says
 * so, and a task past the cut has no box to give.
 */
fun TextLayoutResult.boxesOfRange(
    start: Int,
    end: Int,
): List<Rect> {
    if (start >= end || start < 0 || end > layoutInput.text.length) return emptyList()
    val lastVisible = getLineEnd(lineCount - 1, visibleEnd = true)
    val stop = minOf(end, lastVisible)
    if (stop <= start) return emptyList()
    val boxes = mutableListOf<Rect>()
    var at = start
    while (at < stop) {
        // Where the line really ends, trailing space and all: asked for the
        // visible end instead, a line that wrapped after a space would come back
        // short and the space would be measured as a second box on the same
        // line. One character on when a line ends where it began, so a line
        // break of its own cannot leave this going nowhere.
        val until = minOf(stop, maxOf(getLineEnd(getLineForOffset(at), visibleEnd = false), at + 1))
        val first = getBoundingBox(at)
        val last = getBoundingBox(until - 1)
        boxes += Rect(first.left, first.top, last.right, last.bottom)
        at = until
    }
    return boxes
}

/**
 * Where a stretch of laid-out text begins: the box on the line it starts on.
 *
 * What a popover hangs from. A wrapped run is anchored where the word starts and
 * where a reader's eye is, rather than at some point averaged across its lines.
 */
fun TextLayoutResult.boxOfRange(
    start: Int,
    end: Int,
): Rect? = boxesOfRange(start, end).firstOrNull()

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
