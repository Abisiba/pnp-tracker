package dev.pnptracker.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The colour a stored `#RRGGBB` value stands for, fully opaque.
 *
 * Every value that reaches this has already been through the catalogue, which
 * refuses anything not written this way, so there is nothing here to fall back
 * to. It is shared rather than written twice because the colour catalogue and
 * the game table have to paint the same colour the same way.
 */
fun opaqueColorOf(hex: String): Color {
    val digits = hex.removePrefix("#")
    return Color(
        red = digits.substring(0, 2).toInt(radix = 16),
        green = digits.substring(2, 4).toInt(radix = 16),
        blue = digits.substring(4, 6).toInt(radix = 16),
    )
}
