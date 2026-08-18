package dev.pnptracker.domain.importhint

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * An `AARRGGBB` fill colour, taken apart.
 *
 * Parsing is done here rather than by trusting the string, so a value that is
 * not eight hex characters produces nothing instead of a wrong colour.
 */
internal data class ArgbColor(
    val alpha: Int,
    val red: Int,
    val green: Int,
    val blue: Int,
) {
    val isFullyTransparent: Boolean get() = alpha == 0
}

internal fun parseArgbHex(value: String): ArgbColor? {
    if (value.length != 8) return null
    val channels =
        (0 until 4).map { index ->
            value.substring(index * 2, index * 2 + 2).toIntOrNull(radix = 16) ?: return null
        }
    return ArgbColor(alpha = channels[0], red = channels[1], green = channels[2], blue = channels[3])
}

/**
 * How green a fill is, on the terms the green-cell hint cares about.
 *
 * The test is done in hue, saturation and value rather than by comparing the raw
 * channels, because "green" is a question about hue and `green > red && green >
 * blue` answers a different one: it calls olive, teal and nearly white cells
 * green, which would put a "did you finish this game?" question on rows that
 * were merely tinted.
 *
 * The bands below were chosen from the greens a spreadsheet actually offers:
 *
 * - The reference fill `4EA72E` sits at hue 104, the standard Excel green
 *   `00B050` at hue 147, its light green `92D050` at hue 89. A band of
 *   [MIN_GREEN_HUE]..[MAX_GREEN_HUE] covers all of them while staying clear of
 *   yellow, which is at hue 60, and of cyan, at hue 180.
 * - Below [MIN_SATURATION] a colour is a shade of grey; white, grey and black
 *   all sit at saturation zero and none of them is a statement about anything.
 * - Below [MIN_VALUE] a colour is effectively black, whatever its hue.
 *
 * A fill inside the narrower [CONFIDENT_MIN_HUE]..[CONFIDENT_MAX_HUE] band with
 * at least [CONFIDENT_MIN_SATURATION] is reported as [HintConfidence.HIGH]; the
 * rest of the band is [HintConfidence.MEDIUM], because a pale or yellowish green
 * is exactly where a person would want to look before agreeing.
 *
 * All arithmetic is on numbers, never on formatted text, so the result does not
 * depend on the machine's locale.
 */
internal const val MIN_GREEN_HUE = 80.0
internal const val MAX_GREEN_HUE = 160.0
internal const val MIN_SATURATION = 0.15
internal const val MIN_VALUE = 0.15
internal const val CONFIDENT_MIN_HUE = 90.0
internal const val CONFIDENT_MAX_HUE = 150.0
internal const val CONFIDENT_MIN_SATURATION = 0.35

/**
 * The confidence with which [fillColorArgb] reads as green, or null if it does
 * not read as green at all.
 *
 * A fully transparent fill is not a colour on the page, so it is never green.
 */
fun greenConfidenceOf(fillColorArgb: String): HintConfidence? {
    val color = parseArgbHex(fillColorArgb) ?: return null
    if (color.isFullyTransparent) return null

    val red = color.red / 255.0
    val green = color.green / 255.0
    val blue = color.blue / 255.0
    val value = max(red, max(green, blue))
    val lowest = min(red, min(green, blue))
    val chroma = value - lowest
    if (value < MIN_VALUE) return null
    val saturation = if (value == 0.0) 0.0 else chroma / value
    if (saturation < MIN_SATURATION) return null

    val hue = hueOf(red = red, green = green, blue = blue, value = value, chroma = chroma) ?: return null
    if (hue < MIN_GREEN_HUE || hue > MAX_GREEN_HUE) return null

    val isConfident =
        hue >= CONFIDENT_MIN_HUE && hue <= CONFIDENT_MAX_HUE && saturation >= CONFIDENT_MIN_SATURATION
    return if (isConfident) HintConfidence.HIGH else HintConfidence.MEDIUM
}

/** Null for a grey, which has no hue to speak of. */
private fun hueOf(
    red: Double,
    green: Double,
    blue: Double,
    value: Double,
    chroma: Double,
): Double? {
    if (chroma == 0.0) return null
    val degrees =
        when {
            // Comparing doubles that came from the same division is safe here, but
            // the tolerance keeps a rounded channel from picking the wrong branch.
            abs(value - red) < TOLERANCE -> 60.0 * (((green - blue) / chroma) % 6.0)
            abs(value - green) < TOLERANCE -> 60.0 * (((blue - red) / chroma) + 2.0)
            else -> 60.0 * (((red - green) / chroma) + 4.0)
        }
    return if (degrees < 0) degrees + 360.0 else degrees
}

private const val TOLERANCE = 1e-9
