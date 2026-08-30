package dev.pnptracker.domain.colors

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where a click on the wheel lands, and where the mark showing it belongs. */
class ColorWheelGeometryTest {
    private val start = HsbColor.of(hue = 200f, saturation = 0.5f, brightness = 0.75f)

    private fun assertClose(
        expected: Float,
        actual: Float,
        tolerance: Float,
        what: String,
    ) {
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected but was $actual")
    }

    @Test
    fun `the four sides of the wheel are the hues they look like`() {
        val radius = 100f
        // Screen coordinates grow downwards, so up the screen is a positive
        // angle: a wheel where these came out upside down would be a wheel that
        // does not match the colours drawn on it.
        assertClose(0f, start.atWheelPoint(WheelPoint(radius, 0f), radius).hue, 0.01f, "right")
        assertClose(90f, start.atWheelPoint(WheelPoint(0f, -radius), radius).hue, 0.01f, "up")
        assertClose(180f, start.atWheelPoint(WheelPoint(-radius, 0f), radius).hue, 0.01f, "left")
        assertClose(270f, start.atWheelPoint(WheelPoint(0f, radius), radius).hue, 0.01f, "down")
    }

    @Test
    fun `the rim is fully saturated and the middle is not saturated at all`() {
        val radius = 80f
        assertEquals(1f, start.atWheelPoint(WheelPoint(radius, 0f), radius).saturation)
        assertEquals(0f, start.atWheelPoint(WheelPoint(0f, 0f), radius).saturation)
        assertClose(0.5f, start.atWheelPoint(WheelPoint(40f, 0f), radius).saturation, 0.001f, "halfway out")
    }

    @Test
    fun `a drag that runs off the edge is read as the edge rather than refused`() {
        val radius = 60f
        val outside = start.atWheelPoint(WheelPoint(300f, 0f), radius)
        assertEquals(1f, outside.saturation)
        assertClose(0f, outside.hue, 0.01f, "hue at the edge")
    }

    @Test
    fun `the exact middle keeps the hue it was on rather than jumping to red`() {
        val radius = 60f
        val middle = start.atWheelPoint(WheelPoint(0f, 0f), radius)
        assertEquals(start.hue, middle.hue, "dragging through the middle lost the colour on the way")
    }

    @Test
    fun `the wheel never touches the brightness`() {
        val radius = 50f
        listOf(WheelPoint(0f, 0f), WheelPoint(50f, 0f), WheelPoint(-30f, 20f)).forEach { point ->
            assertEquals(start.brightness, start.atWheelPoint(point, radius).brightness, "$point moved the brightness")
        }
    }

    @Test
    fun `the mark sits exactly where the pointer that put it there was`() {
        val radius = 120f
        listOf(
            WheelPoint(60f, 0f),
            WheelPoint(0f, -90f),
            WheelPoint(-40f, 55f),
            WheelPoint(33f, 71f),
        ).forEach { point ->
            val at = start.atWheelPoint(point, radius)
            val mark = at.wheelPoint(radius)
            assertClose(point.x, mark.x, 0.01f, "x of $point")
            assertClose(point.y, mark.y, 0.01f, "y of $point")
        }
    }

    @Test
    fun `drawing the wheel at another size does not change the colour on it`() {
        // A window resize redraws the wheel, and the mark has to land in the same
        // place on it. The colour is state and the radius is only how it is drawn.
        val small = 40f
        val large = 260f
        val chosen = start.atWheelPoint(WheelPoint(20f, -12f), small)
        val markSmall = chosen.wheelPoint(small)
        val markLarge = chosen.wheelPoint(large)

        assertEquals(chosen, chosen.atWheelPoint(markLarge, large), "the same place at another size is another colour")
        assertClose(markSmall.x / small, markLarge.x / large, 0.0001f, "the mark moved relative to the rim")
        assertClose(markSmall.y / small, markLarge.y / large, 0.0001f, "the mark moved relative to the rim")
    }

    @Test
    fun `a wheel drawn at no size at all changes nothing`() {
        assertEquals(start, start.atWheelPoint(WheelPoint(4f, 4f), radius = 0f))
    }

    @Test
    fun `the arrow keys move the colour and nothing else`() {
        assertEquals(start.hue + HUE_STEP, start.nudged(WheelNudge.HUE_FORWARD).hue)
        assertEquals(start.hue - HUE_STEP, start.nudged(WheelNudge.HUE_BACK).hue)
        assertClose(
            start.saturation + SATURATION_STEP,
            start.nudged(WheelNudge.SATURATION_OUT).saturation,
            0.0001f,
            "saturation out",
        )
        assertClose(
            start.saturation - SATURATION_STEP,
            start.nudged(WheelNudge.SATURATION_IN).saturation,
            0.0001f,
            "saturation in",
        )
        WheelNudge.entries.forEach { nudge ->
            assertEquals(start.brightness, start.nudged(nudge).brightness, "$nudge moved the brightness")
        }
    }

    @Test
    fun `the keyboard runs off neither end of the wheel`() {
        val nearlyRound = HsbColor.of(FULL_TURN - 1f, 1f, 0.5f)
        assertClose(HUE_STEP - 1f, nearlyRound.nudged(WheelNudge.HUE_FORWARD).hue, 0.001f, "past the top")
        assertEquals(1f, nearlyRound.nudged(WheelNudge.SATURATION_OUT).saturation, "past the rim")

        val middle = HsbColor.of(0f, 0f, 0.5f)
        assertEquals(0f, middle.nudged(WheelNudge.SATURATION_IN).saturation, "past the middle")
        assertClose(FULL_TURN - HUE_STEP, middle.nudged(WheelNudge.HUE_BACK).hue, 0.001f, "back past zero")
    }
}
