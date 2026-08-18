package dev.pnptracker.domain.importhint

/** The two stars the source file uses to mean "this part is finished". */
private const val MARKER = "**"

/**
 * What a scan of one cell found.
 *
 * [rawText] is the text exactly as it was read. [displayText] is the same text
 * with the markers in [markers] cut out and nothing else touched — no trimming,
 * no collapsing of runs of spaces, no rewriting of line breaks or of Turkish
 * letters. The two are kept side by side so the cell can be shown tidily while
 * the record of what the file said stays intact.
 */
data class CompletionMarkerScan(
    val rawText: String,
    val markers: List<ImportHint.CompletionMarker>,
) {
    val displayText: String = removeMarkers(rawText, markers)

    val hasMarkers: Boolean get() = markers.isNotEmpty()
}

/**
 * Finds every `**` in [rawText], left to right.
 *
 * Deliberately a scan and not `replace("**", "")`: the ranges are the point.
 * Once the user cuts a draft task out of part of the text, the marker that sits
 * inside that part is the one that belongs to it, and that question cannot be
 * answered from a string that has already lost the positions.
 *
 * Overlapping is not possible, because a match consumes both of its characters:
 * `****` is two markers, and `***` is one marker followed by a lone `*` that is
 * ordinary text. A single `*` is never a marker and is always kept.
 */
fun detectCompletionMarkers(rawText: String): CompletionMarkerScan {
    val markers = mutableListOf<ImportHint.CompletionMarker>()
    var index = 0
    while (index + 1 < rawText.length) {
        if (rawText[index] == '*' && rawText[index + 1] == '*') {
            markers += ImportHint.CompletionMarker(TextRange(index, index + MARKER.length))
            index += MARKER.length
        } else {
            index++
        }
    }
    return CompletionMarkerScan(rawText = rawText, markers = markers)
}

private fun removeMarkers(
    rawText: String,
    markers: List<ImportHint.CompletionMarker>,
): String {
    if (markers.isEmpty()) return rawText
    return buildString(rawText.length) {
        var copiedUpTo = 0
        markers.forEach { marker ->
            append(rawText, copiedUpTo, marker.evidence.startIndex)
            copiedUpTo = marker.evidence.endIndex
        }
        append(rawText, copiedUpTo, rawText.length)
    }
}
