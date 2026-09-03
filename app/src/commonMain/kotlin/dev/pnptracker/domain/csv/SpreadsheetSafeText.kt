package dev.pnptracker.domain.csv

/** What a spreadsheet reads as the start of a formula rather than as text. */
private val FORMULA_STARTS = charArrayOf('=', '+', '-', '@')

private const val TAB = '\t'
private const val CARRIAGE_RETURN = '\r'
private const val LINE_FEED = '\n'

/** Marks a cell as text; a spreadsheet strips it again when the file is opened. */
private const val TEXT_MARKER = '\''

/**
 * Stops a spreadsheet from treating an exported cell as a formula.
 *
 * A task somebody named `=1+1` is a name, not a sum, and a note beginning `-2`
 * is a note. Opened in Excel or LibreOffice, both would be evaluated, and a cell
 * beginning `=` can reach a great deal further than arithmetic. Quoting the field
 * does not help: quoting is how CSV marks where a field ends, and the spreadsheet
 * removes it before deciding what the value is.
 *
 * So the value is prefixed with an apostrophe, which every spreadsheet reads as
 * "the rest of this cell is text" and does not show. Leading spaces do not get
 * round it — the first character that is not whitespace is the one that decides —
 * and the apostrophe still goes at the real start of the cell, so the spaces the
 * user typed are kept.
 *
 * This changes what is written to the file and nothing else. The task, the note
 * and the colour keep their own names in the database; a value that comes back
 * through the CSV import is the value with its apostrophe, which is the honest
 * cost of not running somebody's spreadsheet for them.
 */
fun spreadsheetSafeText(value: String): String {
    if (value.isEmpty()) return value
    // A control character at the very front is dangerous in its own right: it is
    // not something a person typed as the start of a word, and what a reader
    // makes of it is its own business.
    val first = value.first()
    if (first == TAB || first == CARRIAGE_RETURN || first == LINE_FEED) return TEXT_MARKER + value

    val meaningful = value.firstOrNull { !it.isWhitespace() } ?: return value
    if (meaningful in FORMULA_STARTS) return TEXT_MARKER + value
    return value
}
