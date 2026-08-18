package dev.pnptracker.domain.spreadsheet

/**
 * One filled source cell, exactly as it was found.
 *
 * [rawText] is what the cell showed in the spreadsheet and is never touched: no
 * trimming, no collapsing of line breaks, no stripping of `**`. Everything a
 * later step wants to read out of a cell is read out of a copy of this text, not
 * by editing it here.
 *
 * [rowIndex] and [columnIndex] are zero based.
 */
data class CellSnapshot(
    val rowIndex: Int,
    val columnIndex: Int,
    val rawText: String,
    val kind: SpreadsheetCellKind,
    val formula: String? = null,
    val fillColorArgb: String? = null,
    val fontColorArgb: String? = null,
    val isBold: Boolean = false,
    val richTextRuns: List<RichTextRunSnapshot> = emptyList(),
) {
    init {
        require(rowIndex >= 0) { "Row index is zero based, was: $rowIndex" }
        require(columnIndex >= 0) { "Column index is zero based, was: $columnIndex" }
        require(formula == null || kind == SpreadsheetCellKind.FORMULA) {
            "Only a formula cell carries formula text, but a $kind cell did"
        }
        requireArgbHex(fillColorArgb, "Fill colour")
        requireArgbHex(fontColorArgb, "Font colour")
        requireOrderedRuns()
    }

    private fun requireOrderedRuns() {
        var previousEnd = 0
        richTextRuns.forEach { run ->
            require(run.startIndex >= previousEnd) {
                "Rich text runs must be ordered and must not overlap, but $run starts before $previousEnd"
            }
            require(run.endIndex <= rawText.length) {
                "Run ${run.startIndex}..${run.endIndex} runs past the ${rawText.length} characters of the cell"
            }
            require(run.text == rawText.substring(run.startIndex, run.endIndex)) {
                "Run ${run.startIndex}..${run.endIndex} does not hold the text it points at"
            }
            previousEnd = run.endIndex
        }
    }
}
