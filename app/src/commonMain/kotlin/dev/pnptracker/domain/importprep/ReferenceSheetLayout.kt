package dev.pnptracker.domain.importprep

import dev.pnptracker.domain.spreadsheet.SheetSnapshot

/**
 * The seven column headings this version of the import understands.
 *
 * The reference file has been kept by hand for a long time, so a heading is
 * written more than one way. Every spelling that is unambiguous is accepted; a
 * heading that is not on this list is not guessed at, because putting cells into
 * the wrong pool is far worse than asking the user to check the file.
 *
 * The game column is the one that may have no heading at all: the reference file
 * leaves A1 empty.
 */
object ReferenceSheetLayout {
    const val GAME_COLUMN_INDEX = 0
    const val LAST_COLUMN_INDEX = 6

    private val ACCEPTED_HEADERS: Map<Int, Set<String>> =
        mapOf(
            // "Adı" in capitals is "ADI", and lower casing that gives a dotted
            // i. Both spellings are listed rather than folding the two Turkish
            // i letters together, which is a licence the colour catalogue needs
            // and a column heading does not.
            0 to setOf("", "oyun", "oyun adı", "oyun adi"),
            1 to setOf("3d", "3d print", "3d print (figür vb.)"),
            2 to setOf("kart", "laminasyon", "laminasyon (kart vb.)"),
            3 to setOf("mukavva", "mukavva (board, token vb.)"),
            4 to setOf("özel"),
            5 to setOf("eksik"),
            6 to setOf("ödünç parçalar"),
        )

    /**
     * Which row holds the headings, or null if no row does.
     *
     * Only the first row that holds anything is considered. A heading further
     * down would mean the sheet is not laid out the way this version reads, and
     * hunting for one risks mistaking a row of data for a heading.
     */
    fun headerRowIndexOf(sheet: SheetSnapshot): Int? {
        val firstRow = sheet.startRowIndex ?: return null
        val cells = sheet.cells.filter { it.rowIndex == firstRow }
        if (cells.isEmpty()) return null

        val everyCellIsAKnownHeading =
            cells.all { cell ->
                val accepted = ACCEPTED_HEADERS[cell.columnIndex] ?: return@all false
                normalizeHeader(cell.rawText) in accepted
            }
        // A row holding only the game heading would match trivially, and so would
        // a data row whose single filled cell happens to read "Kart". At least
        // one of the work columns has to be named for this to be a heading row.
        val namesAWorkColumn = cells.any { it.columnIndex in 1..LAST_COLUMN_INDEX }

        return if (everyCellIsAKnownHeading && namesAWorkColumn) firstRow else null
    }

    /** The leftmost content column outside the reference layout, or null if there is none. */
    fun unsupportedColumnOf(sheet: SheetSnapshot): Int? =
        sheet.cells
            .map { it.columnIndex }
            .filter { it > LAST_COLUMN_INDEX }
            .minOrNull()

    /**
     * The column a heading names, or null when no column accepts that spelling.
     *
     * The accepted spellings share no word between columns, so the answer does
     * not depend on the order they are looked at in; a test pins that down. The
     * game column's blank heading is not an answer here — an empty value names
     * nothing, and a CSV row that leaves `source_type` empty is a row to reject
     * rather than a row about a game name.
     */
    fun columnIndexOfHeader(text: String): Int? {
        val normalized = normalizeHeader(text)
        if (normalized.isEmpty()) return null
        return ACCEPTED_HEADERS.entries.firstOrNull { (_, accepted) -> normalized in accepted }?.key
    }

    fun isKnownHeader(
        columnIndex: Int,
        text: String,
    ): Boolean = normalizeHeader(text) in ACCEPTED_HEADERS.getOrElse(columnIndex) { emptySet() }
}
