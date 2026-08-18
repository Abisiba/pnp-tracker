package dev.pnptracker.domain.spreadsheet

/**
 * A stretch of a cell's text that was formatted differently from its neighbours.
 *
 * [startIndex] and [endIndex] are UTF-16 indexes into [CellSnapshot.rawText] —
 * the same indexes Kotlin's `String` and Compose text selection use — so a run
 * can be highlighted without converting anything.
 *
 * The formatting here is a record of how the cell looked. It is never evidence
 * of a colour name or of a finished job.
 */
data class RichTextRunSnapshot(
    val startIndex: Int,
    val endIndex: Int,
    val text: String,
    val fontColorArgb: String?,
    val isBold: Boolean,
) {
    init {
        require(startIndex >= 0) { "A run cannot start before the text, was: $startIndex" }
        require(endIndex > startIndex) {
            "A run must cover at least one character, was: $startIndex..$endIndex"
        }
        require(text.length == endIndex - startIndex) {
            "A run of $startIndex..$endIndex must hold ${endIndex - startIndex} characters, held ${text.length}"
        }
        requireArgbHex(fontColorArgb, "Run font colour")
    }
}
