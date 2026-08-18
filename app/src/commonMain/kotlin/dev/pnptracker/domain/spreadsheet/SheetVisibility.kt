package dev.pnptracker.domain.spreadsheet

/**
 * Whether the sheet was visible in the source file.
 *
 * Hidden sheets are read like any other; the flag is carried so a later step can
 * tell the user where a row came from instead of silently dropping it.
 */
enum class SheetVisibility {
    VISIBLE,
    HIDDEN,
    VERY_HIDDEN,
}
