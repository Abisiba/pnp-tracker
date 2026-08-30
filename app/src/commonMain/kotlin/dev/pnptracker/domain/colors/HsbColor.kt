package dev.pnptracker.domain.colors

import dev.pnptracker.domain.rules.requireValidColorHex
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A full turn of the colour wheel, in degrees. */
const val FULL_TURN: Float = 360f

/** Where one of the six sectors of the wheel ends, in degrees. */
private const val SECTOR = 60f

private const val CHANNEL_MAX = 255f

/**
 * A colour as the picker holds it: a place on the wheel and how bright it is.
 *
 * This, and not the `#RRGGBB` text, is what the form works in. A wheel drag is a
 * run of small changes, and holding the colour as text would mean converting to
 * eight-bit channels and back on every one of them — each round trip losing a
 * little, so a colour would drift while the user held still. Here the drag
 * changes two numbers and nothing is converted until something has to be drawn
 * or written.
 *
 * There is no alpha. PLAN 5.7 has colours a thing is really made in, and a
 * half transparent one is not something anyone prints.
 *
 * [hue] is in degrees and wraps: dragging past 360 comes back to red rather than
 * stopping, because the wheel has no end. [saturation] and [brightness] are
 * fractions and are clamped, because they do have ends.
 */
@ConsistentCopyVisibility
data class HsbColor private constructor(
    val hue: Float,
    val saturation: Float,
    val brightness: Float,
) {
    /** The same colour a little brighter or darker; nothing else moves. */
    fun withBrightness(brightness: Float): HsbColor = of(hue, saturation, brightness)

    /** The same brightness at another place on the wheel. */
    fun withWheel(
        hue: Float,
        saturation: Float,
    ): HsbColor = of(hue, saturation, brightness)

    /**
     * This colour written the way the catalogue stores it: `#RRGGBB`, upper
     * case, fully opaque.
     */
    fun toHex(): String {
        val chroma = brightness * saturation
        val sector = hue / SECTOR
        val second = chroma * (1f - abs(sector.mod(2f) - 1f))
        val (red, green, blue) =
            when (sector.toInt()) {
                0 -> Triple(chroma, second, 0f)
                1 -> Triple(second, chroma, 0f)
                2 -> Triple(0f, chroma, second)
                3 -> Triple(0f, second, chroma)
                4 -> Triple(second, 0f, chroma)
                else -> Triple(chroma, 0f, second)
            }
        val floor = brightness - chroma
        return buildString(7) {
            append('#')
            appendChannel(red + floor)
            appendChannel(green + floor)
            appendChannel(blue + floor)
        }
    }

    private fun StringBuilder.appendChannel(value: Float) {
        val byte = (value * CHANNEL_MAX).roundToInt().coerceIn(0, 255)
        append(HEX_DIGITS[byte shr 4])
        append(HEX_DIGITS[byte and 0xF])
    }

    companion object {
        private const val HEX_DIGITS = "0123456789ABCDEF"

        /**
         * A colour at this place on the wheel, with the hue wrapped and the two
         * fractions clamped.
         *
         * Kept as the only way to build one so no caller can hold a colour at
         * 400 degrees or at a saturation of 1.4: a drag that runs off the edge
         * of the wheel or a keystroke past the end of the range is an ordinary
         * thing to happen, and the answer is the nearest colour there is rather
         * than a refusal.
         */
        fun of(
            hue: Float,
            saturation: Float,
            brightness: Float,
        ): HsbColor =
            HsbColor(
                hue = if (hue.isNaN()) 0f else hue.mod(FULL_TURN),
                saturation = saturation.clampToFraction(),
                brightness = brightness.clampToFraction(),
            )

        private fun Float.clampToFraction(): Float = if (isNaN()) 0f else coerceIn(0f, 1f)
    }
}

/**
 * The place on the wheel a stored colour stands at.
 *
 * Every value that reaches this has been through the catalogue, which refuses
 * anything not written as `#RRGGBB`, so there is nothing to fall back to.
 *
 * Grey has no hue and white has neither hue nor saturation: the arithmetic
 * cannot say which way to point, so both come back at zero degrees. That is a
 * real answer rather than a missing one — the colour is exactly what it was, and
 * writing it back out returns the same six digits.
 *
 * @throws IllegalArgumentException if [hex] is not written as `#RRGGBB`.
 */
fun hsbOfHex(hex: String): HsbColor {
    requireValidColorHex(hex)
    val digits = hex.removePrefix("#")
    val red = digits.substring(0, 2).toInt(radix = 16) / CHANNEL_MAX
    val green = digits.substring(2, 4).toInt(radix = 16) / CHANNEL_MAX
    val blue = digits.substring(4, 6).toInt(radix = 16) / CHANNEL_MAX
    val top = max(red, max(green, blue))
    val bottom = min(red, min(green, blue))
    val spread = top - bottom
    val hue =
        when {
            spread == 0f -> 0f
            top == red -> SECTOR * (((green - blue) / spread).mod(6f))
            top == green -> SECTOR * ((blue - red) / spread + 2f)
            else -> SECTOR * ((red - green) / spread + 4f)
        }
    return HsbColor.of(
        hue = hue,
        saturation = if (top == 0f) 0f else spread / top,
        brightness = top,
    )
}
