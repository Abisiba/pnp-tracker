package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.EntityId

/**
 * One stretch of a cell's document, and where it sits in it.
 *
 * The document is the pieces of a cell laid end to end (PLAN 5.5), so an offset
 * into it means nothing without the runs it falls between. A run carries its own
 * bounds rather than leaving every caller to count them again.
 */
data class DocumentRun(
    /**
     * The stored row this run is, or null when there is no row for it yet.
     *
     * A gap the user has just typed into has text but no piece of its own until
     * the change is saved. Nothing can be anchored to such a run — a selection in
     * it has no segment to name — and null is how it says so.
     */
    val segmentId: EntityId?,
    /** The task this run stands for, or null when it is plain text. */
    val taskId: EntityId?,
    /** This run's part of the document: its own text, or the task's name. */
    val text: String,
    /** Where the run starts in the document. */
    val start: Int,
) {
    val end: Int get() = start + text.length
    val isTask: Boolean get() = taskId != null
}

/**
 * Why a change the user made to a cell's document could not be taken.
 *
 * Both cases are about a task, because a task is the only thing in a cell that
 * is not the user's to type over. PLAN 5.5 makes a task piece atomic: it cannot
 * be split like text, deleted through by the caret, or typed into.
 */
enum class DocumentEditRefusal {
    /**
     * The change reached into or across a task.
     *
     * A backspace at the edge of a task, a selection that swallowed one, a paste
     * over the top of one: all the same answer. A task keeps its colours, its
     * pipeline and its history by keeping its identity, and it can only keep
     * that if nothing may quietly rewrite it as characters.
     *
     * The only one there is, and deliberately so. The gaps cover every offset a
     * task does not, so a change that no gap can hold is a change that ran into
     * one — there is no third thing for it to have been.
     */
    CROSSES_A_TASK,
}

/**
 * What a cell's document is to be made of once a change is taken.
 *
 * Expressed as the text of each **gap** — the stretch before the first task,
 * between each pair of tasks, and after the last — rather than as an edit to
 * apply somewhere. A cell with `n` tasks has exactly `n + 1` gaps, always,
 * however many of them are empty.
 *
 * Saying it this way makes the rules of PLAN 5.5 and 16 fall out rather than
 * having to be enforced: neighbouring stretches of text cannot exist separately
 * because a gap *is* one stretch, an empty gap simply writes no piece, and the
 * reading order is the order of the list. The tasks are not in here at all,
 * which is the point — nothing about this can move or change one.
 */
data class DocumentPlan(
    val gapTexts: List<String>,
)

/** What planning a change came to: a new shape for the document, or why not. */
sealed interface DocumentChange {
    data class Planned(
        val plan: DocumentPlan,
    ) : DocumentChange

    data class Refused(
        val reason: DocumentEditRefusal,
    ) : DocumentChange
}

/**
 * Works out what a cell's document is to be made of after its text changed.
 *
 * The change is found rather than trusted: the caller hands over the text before
 * and the text after, and the region that actually differs is derived from them.
 * That region has to lie inside a single gap. A change that reaches into a task,
 * spans one, or lands between two tasks where there is no text is refused, and
 * nothing is planned.
 *
 * A change at the very edge of a task is taken when there is a gap on that side
 * to hold it: typing after a trailing task appends to the gap that follows it,
 * even though that gap is empty and has no piece of its own yet. The gap is
 * where the text belongs; whether a row exists for it is the storage's business.
 */
fun planDocumentChange(
    runs: List<DocumentRun>,
    before: String,
    after: String,
): DocumentChange {
    var prefix = 0
    while (prefix < before.length && prefix < after.length && before[prefix] == after[prefix]) prefix++
    var suffix = 0
    while (
        suffix < before.length - prefix &&
        suffix < after.length - prefix &&
        before[before.length - 1 - suffix] == after[after.length - 1 - suffix]
    ) {
        suffix++
    }
    val changedStart = prefix
    val changedEnd = before.length - suffix
    val replacement = after.substring(prefix, after.length - suffix)

    val gaps = gapsOf(runs, before.length)
    // Gaps are separated by tasks, so at most one of them can hold a given
    // region: there is nothing here to choose between.
    val gap =
        gaps.firstOrNull { changedStart >= it.start && changedEnd <= it.end }
            ?: return DocumentChange.Refused(DocumentEditRefusal.CROSSES_A_TASK)

    val texts =
        gaps.map { candidate ->
            if (candidate !== gap) {
                candidate.text
            } else {
                candidate.text.substring(0, changedStart - candidate.start) +
                    replacement +
                    candidate.text.substring(changedEnd - candidate.start)
            }
        }
    return DocumentChange.Planned(DocumentPlan(texts))
}

/** One stretch of the document that is the user's to type in. */
private data class Gap(
    val start: Int,
    val text: String,
) {
    val end: Int get() = start + text.length
}

/**
 * The stretches of the document that are text, in order, empty ones included.
 *
 * There is one before the first task, one between each pair, and one after the
 * last, so how many there are depends only on how many tasks there are and never
 * on what has been typed. An empty gap is still a gap: it is where text goes.
 */
private fun gapsOf(
    runs: List<DocumentRun>,
    length: Int,
): List<Gap> {
    val gaps = mutableListOf<Gap>()
    var at = 0
    runs.filter { it.isTask }.forEach { task ->
        gaps += Gap(start = at, text = textBetween(runs, at, task.start))
        at = task.end
    }
    gaps += Gap(start = at, text = textBetween(runs, at, length))
    return gaps
}

private fun textBetween(
    runs: List<DocumentRun>,
    from: Int,
    to: Int,
): String {
    val inside = runs.filter { !it.isTask && it.start >= from && it.end <= to }
    return inside.joinToString(separator = "") { it.text }
}

/**
 * The document laid out again from the planned text of each gap.
 *
 * The tasks come through untouched, in order, with their identities: a change to
 * the text around them cannot move or rename one. A gap keeps the row it had
 * when it has one, and has none when its text is new, which is exactly what
 * [DocumentRun.segmentId] being null means.
 *
 * This is what lets an editor go on knowing where the tasks are while the user
 * types. Searching the text for a task's name would find the wrong one the
 * moment somebody typed that word themselves.
 */
fun runsFrom(
    previous: List<DocumentRun>,
    gapTexts: List<String>,
): List<DocumentRun> {
    val tasks = previous.filter { it.isTask }
    val plainsByGap =
        previous
            .filter { !it.isTask }
            .groupBy { plain -> tasks.count { it.start < plain.start } }
    val laid = mutableListOf<DocumentRun>()
    var at = 0
    gapTexts.forEachIndexed { gap, text ->
        if (text.isNotEmpty()) {
            laid +=
                DocumentRun(
                    segmentId = plainsByGap[gap]?.firstOrNull()?.segmentId,
                    taskId = null,
                    text = text,
                    start = at,
                )
            at += text.length
        }
        tasks.getOrNull(gap)?.let { task ->
            laid += task.copy(start = at)
            at += task.text.length
        }
    }
    return laid
}

/** The document these runs spell out, end to end and nothing between. */
fun List<DocumentRun>.documentText(): String = joinToString(separator = "") { it.text }
