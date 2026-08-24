package dev.pnptracker.domain.tasks

import dev.pnptracker.domain.model.EntityId

/**
 * A stretch of one piece of a cell's text, with enough proof to cut it later.
 *
 * The offsets are counted the way the text field the user dragged in counts
 * them: UTF-16 code units, so nothing has to be converted between what Compose
 * reports and what is stored, and no conversion can be got wrong.
 *
 * They are offsets **into one piece**, never into the whole cell. A cell is a
 * document made of pieces (PLAN 5.5) and a cell-wide offset would point at a
 * different piece as soon as one before it changed; carrying [segmentId] and
 * [expectedText] together means the cut either lands exactly where the user
 * pointed or is refused.
 *
 * [expectedText] is the proof. The transaction compares it against what the
 * piece says now, so text edited in another window between the selection and the
 * save is caught rather than cut blindly at stale offsets.
 *
 * None of this is stored. A selection lives as long as the panel the user opened
 * from it, and the database never hears about one that was not acted on.
 */
data class CellTextSelection(
    val gameId: EntityId,
    val cellId: EntityId,
    val segmentId: EntityId,
    /** What the piece said when the selection was made. */
    val expectedText: String,
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * One piece of text cut into what comes before a task, its name, and what comes
 * after.
 *
 * The whole point of the shape is [documentText]: whatever the user selected,
 * the three parts put back together are the text that was there before, to the
 * character. Nothing is trimmed away into nowhere, no space is invented between
 * the parts, and no line ending is rewritten.
 */
data class SplitPlainText(
    val prefix: String,
    val name: String,
    val suffix: String,
) {
    init {
        require(name.isNotEmpty()) { "A task cut out of text has to have a name." }
    }

    /** The text this was cut from; the same string, always. */
    val documentText: String get() = prefix + name + suffix
}

/**
 * Cuts [text] at the user's selection, keeping every character.
 *
 * Whitespace at the edges of the selection is **not** part of the name and is
 * **not** thrown away: the boundaries move inward to the real words and the
 * spaces stay in the document, on whichever side they were. Selecting `" token "`
 * out of `"40 token ×14"` leaves `"40 "` before the task and `" ×14"` after it,
 * and the name is `token`.
 *
 * A selection whose words run across a line ending is refused rather than
 * quietly straightened: a task called `"gri token\n26 ağaç"` is two lines of
 * somebody's notes, not the name of one thing to make. A line ending at the very
 * edge of the selection is only whitespace, so it narrows away like any other.
 *
 * The offsets may not fall inside a surrogate pair. Half of a character is not a
 * character, and storing one would put a broken name in the database that no
 * later step could repair.
 *
 * @throws TaskFromTextException with [TaskFromTextFailure.INVALID_SELECTION],
 *   [TaskFromTextFailure.TASK_NAME_EMPTY] or
 *   [TaskFromTextFailure.SELECTION_CONTAINS_LINE_BREAK].
 */
fun splitForTaskName(
    text: String,
    startOffset: Int,
    endOffset: Int,
): SplitPlainText {
    if (startOffset < 0 || endOffset > text.length || startOffset >= endOffset) {
        refuse(TaskFromTextFailure.INVALID_SELECTION)
    }
    if (text.splitsASurrogatePairAt(startOffset) || text.splitsASurrogatePairAt(endOffset)) {
        refuse(TaskFromTextFailure.INVALID_SELECTION)
    }

    var start = startOffset
    var end = endOffset
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    if (start == end) refuse(TaskFromTextFailure.TASK_NAME_EMPTY)

    val name = text.substring(start, end)
    if (name.any { it == '\n' || it == '\r' }) refuse(TaskFromTextFailure.SELECTION_CONTAINS_LINE_BREAK)

    return SplitPlainText(
        prefix = text.substring(0, start),
        name = name,
        suffix = text.substring(end),
    )
}

/** True when cutting here would leave half of a character on each side. */
private fun String.splitsASurrogatePairAt(offset: Int): Boolean =
    offset in 1..<length && this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate()

private fun refuse(failure: TaskFromTextFailure): Nothing = throw TaskFromTextException(failure)
