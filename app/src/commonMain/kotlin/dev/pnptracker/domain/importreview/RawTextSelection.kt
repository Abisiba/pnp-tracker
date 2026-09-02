package dev.pnptracker.domain.importreview

import dev.pnptracker.domain.importhint.detectCompletionMarkers
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import dev.pnptracker.domain.tasks.splitForTaskName
import dev.pnptracker.domain.text.graphemeBoundariesOf

/**
 * A stretch of one source cell, cut down to the name of a task.
 *
 * [startIndex] and [endIndex] are UTF-16 indexes into the **untouched** cell
 * text, start included and end excluded: the same indexes Compose text selection
 * reports and the same ones `draft_tasks` stores, so nothing is converted
 * anywhere and no conversion can be got wrong. They are the boundaries after
 * trimming, not the ones the user's drag happened to land on, because the spaces
 * either side of a word are not part of what the thing is called.
 *
 * [selectedText] is exactly what those boundaries cover. [name] is the same text
 * with the `**` markers taken out, which is what PLAN 11.5 means by the marker
 * being cleared from the shown name — the cell itself keeps every character it
 * had, here and in the database.
 */
data class RawTextSelection(
    val startIndex: Int,
    val endIndex: Int,
    val selectedText: String,
    val name: String,
    /** True when the selected words carried a `**`, which PLAN 11.5 reads as a hint. */
    val hasCompletionMarker: Boolean,
) {
    init {
        require(endIndex > startIndex) { "A selection covers at least one character." }
        require(name.isNotBlank()) { "A task cut out of a cell has to have a name." }
    }
}

/**
 * Reads the user's selection of one cell as the name of a task, or refuses it.
 *
 * Every boundary is a real character boundary. `á` written as a letter and a
 * combining accent, `👍🏽`, `👨‍👩‍👧‍👦`, `🇹🇷` and a `\r\n` line ending are each one
 * character to the person who selected them, and each is several code units; a
 * cut inside one would store a name that is half of something, which no later
 * step could repair. So both ends are checked against the same grapheme
 * boundaries PLAN 12.7 splits a name over, before and after trimming.
 *
 * The trimming is `splitForTaskName`'s own rule, called rather than copied:
 * whitespace at the edges moves the boundaries inward and stays in the cell, and
 * a name that would run across a line ending is refused instead of being
 * straightened out. Two lines of somebody's notes are not the name of one thing
 * to make.
 *
 * The cell text is never modified. Nothing here writes, reads a clock or makes
 * an identity, so the same text and the same offsets always give the same
 * answer.
 *
 * @throws ImportReviewException with the case that stopped it.
 */
fun selectTaskNameIn(
    rawText: String,
    startIndex: Int,
    endIndex: Int,
): RawTextSelection {
    if (startIndex < 0 || endIndex > rawText.length || startIndex >= endIndex) {
        refuse(ImportReviewFailure.INVALID_SELECTION)
    }
    val boundaries = graphemeBoundariesOf(rawText).toSet()
    if (startIndex !in boundaries || endIndex !in boundaries) {
        refuse(ImportReviewFailure.SELECTION_SPLITS_A_CHARACTER)
    }

    val split =
        try {
            splitForTaskName(rawText, startIndex, endIndex)
        } catch (refusal: TaskFromTextException) {
            refuse(
                when (refusal.failure) {
                    TaskFromTextFailure.TASK_NAME_EMPTY -> ImportReviewFailure.SELECTION_IS_EMPTY
                    TaskFromTextFailure.SELECTION_CONTAINS_LINE_BREAK ->
                        ImportReviewFailure.SELECTION_CONTAINS_LINE_BREAK

                    else -> ImportReviewFailure.INVALID_SELECTION
                },
            )
        }

    val start = split.prefix.length
    val end = start + split.name.length
    // Trimming moved the ends, so they are asked again: a space that belongs to
    // the character after it would otherwise be stepped over into the middle.
    if (start !in boundaries || end !in boundaries) refuse(ImportReviewFailure.SELECTION_SPLITS_A_CHARACTER)

    val scan = detectCompletionMarkers(split.name)
    val name = scan.displayText.trim()
    if (name.isEmpty()) refuse(ImportReviewFailure.SELECTION_IS_EMPTY)

    return RawTextSelection(
        startIndex = start,
        endIndex = end,
        selectedText = split.name,
        name = name,
        hasCompletionMarker = scan.hasMarkers,
    )
}

private fun refuse(failure: ImportReviewFailure): Nothing = throw ImportReviewException(failure)
