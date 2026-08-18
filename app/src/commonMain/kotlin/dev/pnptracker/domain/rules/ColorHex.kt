package dev.pnptracker.domain.rules

private val COLOR_HEX = Regex("#[0-9A-Fa-f]{6}")

/** True when [hex] is exactly `#RRGGBB`. */
fun isValidColorHex(hex: String): Boolean = COLOR_HEX.matches(hex)

/**
 * @throws IllegalArgumentException if [hex] is not exactly `#RRGGBB`.
 */
fun requireValidColorHex(hex: String): String {
    require(isValidColorHex(hex)) { "A color must be written as #RRGGBB, was: '$hex'" }
    return hex
}
