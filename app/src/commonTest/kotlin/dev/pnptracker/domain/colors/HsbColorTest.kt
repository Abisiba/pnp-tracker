package dev.pnptracker.domain.colors

import dev.pnptracker.domain.rules.isValidColorHex
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The arithmetic the picker rests on.
 *
 * Checked against values worked out by hand rather than against the code's own
 * answers, because a conversion that is wrong in both directions is a conversion
 * that round trips perfectly and shows the user the wrong colour.
 */
class HsbColorTest {
    private fun assertClose(
        expected: Float,
        actual: Float,
        tolerance: Float,
        what: String,
    ) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$what: expected $expected but was $actual (tolerance $tolerance)",
        )
    }

    @Test
    fun `known places on the wheel become the colours they stand for`() {
        // Red, yellow, green, cyan, blue and magenta: one at each corner of the
        // six sectors, so a sector chosen wrongly shows up here rather than as a
        // faint shift somewhere in the middle.
        assertEquals("#FF0000", HsbColor.of(0f, 1f, 1f).toHex())
        assertEquals("#FFFF00", HsbColor.of(60f, 1f, 1f).toHex())
        assertEquals("#00FF00", HsbColor.of(120f, 1f, 1f).toHex())
        assertEquals("#00FFFF", HsbColor.of(180f, 1f, 1f).toHex())
        assertEquals("#0000FF", HsbColor.of(240f, 1f, 1f).toHex())
        assertEquals("#FF00FF", HsbColor.of(300f, 1f, 1f).toHex())
    }

    @Test
    fun `saturation and brightness move the colour the way they are supposed to`() {
        assertEquals("#FFFFFF", HsbColor.of(0f, 0f, 1f).toHex(), "no saturation at full brightness is white")
        assertEquals("#000000", HsbColor.of(210f, 1f, 0f).toHex(), "no brightness is black whatever the hue")
        assertEquals("#808080", HsbColor.of(0f, 0f, 0.5019608f).toHex(), "no saturation is a grey")
        assertEquals("#800000", HsbColor.of(0f, 1f, 0.5019608f).toHex(), "half bright red is a dark red")
    }

    @Test
    fun `known colours come back to the places they stand at`() {
        val red = hsbOfHex("#FF0000")
        assertClose(0f, red.hue, 0.01f, "red hue")
        assertEquals(1f, red.saturation)
        assertEquals(1f, red.brightness)

        val navy = hsbOfHex("#1A237E")
        assertClose(234.6f, navy.hue, 0.1f, "navy hue")
        assertClose(0.7937f, navy.saturation, 0.001f, "navy saturation")
        assertClose(0.4941f, navy.brightness, 0.001f, "navy brightness")
    }

    @Test
    fun `a grey has no direction to point in, and says so rather than guessing`() {
        listOf("#FFFFFF", "#000000", "#808080", "#111111").forEach { grey ->
            val at = hsbOfHex(grey)
            assertEquals(0f, at.hue, "$grey was given a hue it does not have")
            assertEquals(0f, at.saturation, "$grey was given a saturation it does not have")
            assertEquals(grey, at.toHex(), "$grey did not survive being read")
        }
    }

    @Test
    fun `every value survives the trip to the wheel and back exactly`() {
        // Round tripping is what a drag does over and over, so a value that
        // drifted by one would drift by a hundred over a long one. Sampled
        // rather than exhaustive, but across the whole of every channel.
        var checked = 0
        for (red in 0..255 step 17) {
            for (green in 0..255 step 17) {
                for (blue in 0..255 step 17) {
                    val hex = "#" + listOf(red, green, blue).joinToString("") { it.toHexPair() }
                    assertEquals(hex, hsbOfHex(hex).toHex(), "$hex did not come back")
                    checked += 1
                }
            }
        }
        assertEquals(16 * 16 * 16, checked)
    }

    @Test
    fun `a place on the wheel survives being written down and read back`() {
        // The other direction, which cannot be exact: eight bits per channel
        // cannot hold every place on the wheel. What it must be is close.
        listOf(
            HsbColor.of(12f, 0.8f, 0.9f),
            HsbColor.of(199f, 0.35f, 0.42f),
            HsbColor.of(287f, 1f, 1f),
            HsbColor.of(359f, 0.5f, 0.77f),
        ).forEach { colour ->
            val back = hsbOfHex(colour.toHex())
            // Compared the short way round the wheel: 359 and 0 are one degree
            // apart, and a straight subtraction would call them 359.
            val apart = abs(colour.hue - back.hue).let { min(it, FULL_TURN - it) }
            assertTrue(apart <= 1.5f, "hue of ${colour.toHex()}: ${colour.hue} became ${back.hue}")
            assertClose(colour.saturation, back.saturation, 0.01f, "saturation of ${colour.toHex()}")
            assertClose(colour.brightness, back.brightness, 0.005f, "brightness of ${colour.toHex()}")
        }
    }

    @Test
    fun `the wheel has no end, so a hue past the top comes round again`() {
        assertEquals(HsbColor.of(10f, 1f, 1f), HsbColor.of(370f, 1f, 1f))
        assertEquals(HsbColor.of(350f, 1f, 1f), HsbColor.of(-10f, 1f, 1f))
        assertEquals(HsbColor.of(0f, 1f, 1f), HsbColor.of(720f, 1f, 1f))
    }

    @Test
    fun `saturation and brightness do have ends, and stop at them`() {
        assertEquals(1f, HsbColor.of(0f, 4f, 0.5f).saturation)
        assertEquals(0f, HsbColor.of(0f, -4f, 0.5f).saturation)
        assertEquals(1f, HsbColor.of(0f, 0.5f, 9f).brightness)
        assertEquals(0f, HsbColor.of(0f, 0.5f, -9f).brightness)
    }

    @Test
    fun `a value the arithmetic could not produce is treated as none of it`() {
        val nowhere = HsbColor.of(Float.NaN, Float.NaN, Float.NaN)
        assertEquals(0f, nowhere.hue)
        assertEquals(0f, nowhere.saturation)
        assertEquals(0f, nowhere.brightness)
    }

    @Test
    fun `what is written down is always six upper case digits behind a hash`() {
        val written = HsbColor.of(23f, 0.44f, 0.61f).toHex()
        assertEquals(7, written.length)
        assertEquals('#', written.first())
        assertTrue(written.drop(1).all { it in "0123456789ABCDEF" }, "$written is not upper case hex")
        assertTrue(isValidColorHex(written))
    }

    @Test
    fun `every colour a new catalogue starts with survives the wheel untouched`() {
        baseColors.forEach { base ->
            assertEquals(base.hex, hsbOfHex(base.hex).toHex(), "${base.canonicalName} came back as something else")
        }
    }

    @Test
    fun `only brightness moves when only the brightness is asked to`() {
        val start = hsbOfHex("#1A237E")
        val darker = start.withBrightness(0.2f)
        assertEquals(start.hue, darker.hue)
        assertEquals(start.saturation, darker.saturation)
        assertEquals(0.2f, darker.brightness)
    }

    @Test
    fun `a value that is not written as RRGGBB is refused rather than guessed at`() {
        listOf("1A237E", "#1A237", "#1A237EE", "#GGGGGG", "").forEach { attempt ->
            assertFailsWith<IllegalArgumentException>("'$attempt' was read as a colour") { hsbOfHex(attempt) }
        }
    }
}

private fun Int.toHexPair(): String {
    val digits = "0123456789ABCDEF"
    return "${digits[this shr 4]}${digits[this and 0xF]}"
}
