package dev.pnptracker.domain.spreadsheet

/**
 * One worksheet, reduced to the cells that actually held something.
 *
 * The four range fields are derived from [cells] rather than from the range the
 * file declares, because a spreadsheet happily reports a range that covers rows
 * which only ever received formatting. They are all null together when the sheet
 * held nothing, and are inclusive on both ends otherwise.
 */
data class SheetSnapshot(
    val name: String,
    val visibility: SheetVisibility,
    val cells: List<CellSnapshot>,
) {
    val startRowIndex: Int? = cells.minOfOrNull { it.rowIndex }
    val endRowIndex: Int? = cells.maxOfOrNull { it.rowIndex }
    val startColumnIndex: Int? = cells.minOfOrNull { it.columnIndex }
    val endColumnIndex: Int? = cells.maxOfOrNull { it.columnIndex }

    val isEmpty: Boolean get() = cells.isEmpty()

    init {
        require(name.isNotEmpty()) { "A sheet has a name" }
        requireOrderedCells()
    }

    fun cellAt(
        rowIndex: Int,
        columnIndex: Int,
    ): CellSnapshot? = cells.firstOrNull { it.rowIndex == rowIndex && it.columnIndex == columnIndex }

    /** Cells are kept in reading order so a snapshot of the same file is always the same. */
    private fun requireOrderedCells() {
        cells.zipWithNext { previous, next ->
            val previousKey = previous.rowIndex to previous.columnIndex
            val nextKey = next.rowIndex to next.columnIndex
            require(
                next.rowIndex > previous.rowIndex ||
                    (next.rowIndex == previous.rowIndex && next.columnIndex > previous.columnIndex),
            ) { "Cells must run row by row, then column by column, but $previousKey came before $nextKey" }
        }
    }
}
