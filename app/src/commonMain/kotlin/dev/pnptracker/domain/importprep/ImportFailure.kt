package dev.pnptracker.domain.importprep

/**
 * Why an import could not go ahead, in the terms a user can act on.
 *
 * This is deliberately not the reader's own failure list. That one describes
 * what a spreadsheet library complained about; this one describes what the
 * person in front of the screen has to do next, and it also covers the problems
 * the reader never sees — a file that changed under us, a sheet laid out
 * differently from the one this version understands.
 */
enum class ImportFailure {
    FILE_NOT_FOUND,
    NOT_READABLE,
    NOT_AN_XLSX_FILE,

    /** Neither an `.xlsx` nor a `.csv`; this version reads those two and no others. */
    UNSUPPORTED_FILE_TYPE,
    LEGACY_XLS_FILE,
    DAMAGED_FILE,
    ENCRYPTED,
    REJECTED_BY_SAFETY_LIMIT,

    /** The file was edited between being read and being saved. */
    FILE_CHANGED_WHILE_READING,

    /** The sheet has no header row this version recognises. */
    UNSUPPORTED_SHEET_LAYOUT,

    /** The sheet holds content outside the seven columns this version reads. */
    UNSUPPORTED_COLUMN,

    /** There is nothing on the chosen sheet to import. */
    EMPTY_SHEET,

    /** The file is not valid UTF-8; nothing is guessed at and nothing is replaced. */
    NOT_UTF8,

    /** A CSV field opened with a quote that the file never closed. */
    CSV_UNCLOSED_QUOTE,

    /** Something other than a separator or a line ending followed a closing quote. */
    CSV_TEXT_AFTER_QUOTE,

    /** A quote turned up inside a CSV field that never opened with one. */
    CSV_QUOTE_IN_PLAIN_FIELD,

    /** Both separators read the heading row, so the file says two different things. */
    CSV_AMBIGUOUS_DELIMITER,

    /** Neither separator read the heading row, so the file is not one this reads. */
    CSV_UNDETECTABLE_DELIMITER,

    /** The heading row is missing one of the three columns PLAN 11.8 names. */
    CSV_MISSING_HEADER_COLUMN,

    /** The heading row names one of the three required columns more than once. */
    CSV_DUPLICATE_HEADER_COLUMN,

    /** A row has a different number of fields from the heading row. */
    CSV_RAGGED_ROW,

    /** A row leaves one of the three required values empty. */
    CSV_BLANK_REQUIRED_VALUE,

    /** A row names a `source_type` this version does not recognise. */
    CSV_UNKNOWN_SOURCE_TYPE,

    /**
     * The file was read and storage would not take what it said.
     *
     * The one reason here that is about neither the file nor its layout: what
     * was read is still good, and the user has nothing to correct. It is written
     * in one transaction, so a save that failed left no batch and no block
     * behind and the very same draft can be offered again (PLAN 14.7.6).
     */
    COULD_NOT_SAVE,
}

/**
 * Where in a CSV file a problem is, in the file's own numbering.
 *
 * [lineNumber] counts physical lines from one, the way an editor does.
 * [columnName] is the heading of the column at fault, which is a word the file
 * itself contains and the user chose; it is never a value out of a cell.
 */
data class CsvErrorLocation(
    val lineNumber: Int,
    val columnName: String? = null,
) {
    init {
        require(lineNumber >= 1) { "Lines are numbered from one, was: $lineNumber" }
    }
}

/**
 * An import that cannot go ahead.
 *
 * The message names the reason and, where it helps, the column. It never
 * carries a path or anything the file contained.
 */
class ImportPreparationException(
    val failure: ImportFailure,
    val columnIndex: Int? = null,
    val csvLocation: CsvErrorLocation? = null,
) : Exception(
        "Import cannot go ahead: $failure" +
            (columnIndex?.let { " at column $it" } ?: "") +
            (csvLocation?.let { " at line ${it.lineNumber}" } ?: ""),
    )
