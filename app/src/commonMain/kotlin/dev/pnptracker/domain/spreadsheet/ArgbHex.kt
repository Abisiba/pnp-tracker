package dev.pnptracker.domain.spreadsheet

/**
 * Colours read out of a spreadsheet are kept as `AARRGGBB`: eight upper case hex
 * characters, alpha first.
 *
 * This is not the `#RRGGBB` of the production colour catalogue. A spreadsheet
 * colour is only what a cell looked like in the source file; it never names a
 * paint colour.
 */
private val ARGB_PATTERN = Regex("[0-9A-F]{8}")

internal fun requireArgbHex(
    value: String?,
    field: String,
): String? {
    if (value == null) return null
    require(ARGB_PATTERN.matches(value)) {
        "$field must be eight upper case hex characters in AARRGGBB form, was: $value"
    }
    return value
}
