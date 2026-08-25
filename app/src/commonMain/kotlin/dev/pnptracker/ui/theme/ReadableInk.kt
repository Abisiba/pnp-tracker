package dev.pnptracker.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * How bright a colour is to the eye, on WCAG's scale from 0 to 1.
 *
 * Not the average of the channels: green carries most of what people see as
 * brightness and blue almost none, which is why a saturated yellow needs dark
 * text and a saturated blue of the same numeric value needs light text.
 */
fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val linear = value.toDouble()
        return if (linear <= 0.03928) linear / 12.92 else ((linear + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** How far apart two colours are, as WCAG's ratio from 1:1 to 21:1. */
fun contrastRatio(
    first: Color,
    second: Color,
): Double {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

/**
 * Ink that can be read on [background], whatever colour the user chose.
 *
 * A task is painted in the colour it will be made in — PLAN 12.5 — and the user
 * picks that colour for the filament, not for the label sitting on it. So the
 * writing is not a fixed colour at all: it is whichever of black and white
 * stands further from the ground beneath it.
 *
 * Black and white exactly, not a near-black and a near-white. The two extremes
 * are what make the guarantee hold: for any colour whatever, the better of them
 * is at least 4.58:1, which clears the 4.5:1 PLAN 17 asks of readable text.
 * Softening either end would open a band of middling colours where neither
 * choice is readable, and those are common colours rather than exotic ones.
 */
fun readableInkOn(background: Color): Color =
    if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) {
        Color.Black
    } else {
        Color.White
    }

/**
 * A line that shows where a task's colour ends, whatever colour it is.
 *
 * The problem this answers is the one colour that matches the theme's own
 * ground: white on a light surface, black on a dark one. Painted with no edge,
 * such a task shows no colour at all — the fill is there and invisible, so the
 * user is told nothing by the very thing meant to tell them.
 *
 * The edge is [readableInkOn] the fill, which makes the guarantee fall out of a
 * rule that is already proven: the ink stands at least 4.58:1 from the fill, so
 * the boundary is always visible from the inside; and when the fill matches the
 * surface, standing away from the fill is the same as standing away from the
 * surface, so it is visible from the outside too. One rule covers both, and no
 * colour has to be special-cased.
 *
 * PLAN 12.7 asks for exactly this where readability is not enough on its own —
 * an automatic contrast frame rather than a repainted colour, because repainting
 * would tell the user their filament is a colour it is not.
 */
fun visibleEdgeOn(fill: Color): Color = readableInkOn(fill)
