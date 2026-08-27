package dev.pnptracker.domain.tasks

import dev.pnptracker.domain.text.graphemeBoundariesOf

/**
 * One colour's share of a task's name.
 *
 * The bounds are offsets into the name itself, so the text is never copied or
 * rewritten to be painted: what is drawn is the name's own characters, in the
 * name's own order.
 */
data class TaskNameSlice(
    val slotIndex: Int,
    val start: Int,
    val end: Int,
)

/**
 * How one task's name is drawn across the colours it is made in.
 *
 * [slices] are the colours that got a share of the name, in slot order, and
 * [markerSlots] are the colours there was no character left for. A name shorter
 * than its colour list is the only way the second list is not empty, and then it
 * is always the last colours: the share is handed out from the front.
 *
 * Nothing here is stored. PLAN 12.7 derives the split from the name and the
 * colour list every time, so renaming the task or reordering its colours simply
 * produces a different split, and no row can fall out of step with the name.
 */
data class TaskColorLayout(
    val slices: List<TaskNameSlice>,
    val markerSlots: List<Int>,
) {
    /** Every colour is accounted for, whether it got characters or not. */
    val colorCount: Int get() = slices.size + markerSlots.size
}

/**
 * Shares a task's name out among its colours, as PLAN 12.7 draws it.
 *
 * Over the user's own characters and never over code units: `Yarasa` in three
 * colours is `ya | ra | sa`, and a name of one emoji is one character however
 * many `Char`s it takes to write down.
 *
 * The share is `⌊G / K⌋` characters each, and the `G % K` left over go to the
 * **first** colours, one apiece — PLAN 12.7. That one rule also answers the
 * case where there are more colours than characters, because the arithmetic does
 * not change: with `G < K` the base share is zero and the remainder is `G`, so
 * the first `G` colours take one character each and the rest take none. The
 * colours that took none are not lost — they are [markerSlots], and the cell
 * draws each of them beside the name as its own swatch, so a colour the user
 * chose is never invisible.
 *
 * @param colorCount how many colours the task carries; none gives an empty
 *   layout, which is a task drawn in no colour at all (PLAN 5.10).
 */
fun taskColorLayoutOf(
    name: String,
    colorCount: Int,
): TaskColorLayout = taskColorLayoutOf(graphemeBoundariesOf(name), colorCount)

/**
 * The same rule over boundaries that have already been found.
 *
 * Pure, and separate so the sharing rule can be checked on its own: what a
 * character is belongs to [graphemeBoundariesOf], and how many each colour gets
 * belongs here.
 */
fun taskColorLayoutOf(
    boundaries: List<Int>,
    colorCount: Int,
): TaskColorLayout {
    if (colorCount <= 0) return TaskColorLayout(slices = emptyList(), markerSlots = emptyList())
    val characters = (boundaries.size - 1).coerceAtLeast(0)
    val share = characters / colorCount
    val extra = characters % colorCount
    val slices = mutableListOf<TaskNameSlice>()
    val markers = mutableListOf<Int>()
    var taken = 0
    repeat(colorCount) { slot ->
        val take = share + if (slot < extra) 1 else 0
        if (take == 0) {
            markers += slot
        } else {
            slices += TaskNameSlice(slotIndex = slot, start = boundaries[taken], end = boundaries[taken + take])
            taken += take
        }
    }
    return TaskColorLayout(slices = slices, markerSlots = markers)
}
