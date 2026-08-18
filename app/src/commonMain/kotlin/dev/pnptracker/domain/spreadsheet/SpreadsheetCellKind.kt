package dev.pnptracker.domain.spreadsheet

/** What the source cell was, before it was turned into display text. */
enum class SpreadsheetCellKind {
    TEXT,
    NUMBER,
    BOOLEAN,
    FORMULA,
    ERROR,
}
