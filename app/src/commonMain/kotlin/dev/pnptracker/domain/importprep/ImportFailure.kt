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
) : Exception("Import cannot go ahead: $failure${columnIndex?.let { " at column $it" } ?: ""}")
