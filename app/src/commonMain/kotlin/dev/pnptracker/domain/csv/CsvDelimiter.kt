package dev.pnptracker.domain.csv

/**
 * The two separators a CSV file is allowed to use here.
 *
 * A comma is what RFC 4180 says. A semicolon is what a spreadsheet saves in a
 * locale whose decimal separator is the comma, which is every Turkish copy of
 * Excel, so a file exported from the machine this application runs on is far
 * more likely to use one than not.
 *
 * The user is never asked which it is. Guessing wrong is not a matter of taste
 * that a person should have to settle: the header row says the answer, and
 * [dev.pnptracker.domain.importprep.readCsvWorkbook] reads it.
 */
enum class CsvDelimiter(
    val character: Char,
) {
    COMMA(','),
    SEMICOLON(';'),
}
