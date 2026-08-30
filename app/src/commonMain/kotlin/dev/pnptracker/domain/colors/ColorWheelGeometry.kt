package dev.pnptracker.domain.colors

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Degrees one arrow key press moves around the wheel. */
const val HUE_STEP: Float = 5f

/** How much of the way from the centre to the rim one arrow key press covers. */
const val SATURATION_STEP: Float = 0.05f

private const val DEGREES_PER_RADIAN = 180f / kotlin.math.PI.toFloat()

/**
 * A place inside the wheel, measured from its centre in the drawing's own
 * directions: [x] to the right, [y] downwards.
 *
 * Its own small type rather than a Compose offset, so the arithmetic that
 * decides which colour a click landed on can be read and tested without a
 * screen. Nothing here knows how large the wheel is drawn — the radius travels
 * with the point.
 */
data class WheelPoint(
    val x: Float,
    val y: Float,
)

/**
 * The place on the wheel a point stands at: which way round it is, and how far
 * out.
 *
 * Round rather than square, because that is what PLAN 5.7 asks for and because
 * an angle is what a hue is. The rim is fully saturated and the centre is not
 * saturated at all, so the whole of one plane of colour is reachable by moving
 * one finger. A point outside the rim is not a refusal: it is read as the rim,
 * which is what a user who dragged off the edge meant.
 *
 * The exact centre has no direction to point in, so it keeps whatever hue was
 * already chosen rather than jumping to red. Dragging through the middle then
 * comes out the other side instead of losing the colour on the way.
 *
 * [radius] is the radius the wheel is drawn at, in the same units as the point.
 */
fun HsbColor.atWheelPoint(
    point: WheelPoint,
    radius: Float,
): HsbColor {
    if (radius <= 0f) return this
    val distance = hypot(point.x, point.y)
    val saturation = (distance / radius).coerceIn(0f, 1f)
    // Screen coordinates grow downwards; the wheel is read the way an angle is,
    // anticlockwise from the right, so the vertical axis is turned over here.
    val hue = if (distance == 0f) hue else (atan2(-point.y, point.x) * DEGREES_PER_RADIAN).mod(FULL_TURN)
    return withWheel(hue = hue, saturation = saturation)
}

/**
 * Where on the wheel this colour's mark belongs, measured from the centre.
 *
 * The exact inverse of [atWheelPoint], so the mark sits under the pointer that
 * put it there and the two cannot drift apart.
 */
fun HsbColor.wheelPoint(radius: Float): WheelPoint {
    val angle = hue / DEGREES_PER_RADIAN
    val distance = saturation * radius
    return WheelPoint(x = cos(angle) * distance, y = -sin(angle) * distance)
}

/** Which way an arrow key moves the colour on the wheel. */
enum class WheelNudge {
    HUE_FORWARD,
    HUE_BACK,
    SATURATION_OUT,
    SATURATION_IN,
}

/**
 * The colour one arrow key press away.
 *
 * Its own function rather than something the key handler works out, because
 * PLAN 17 asks for every main control to be usable from the keyboard and a wheel
 * that only answers to a pointer would not be one. Hue wraps and saturation
 * stops, which is what [HsbColor.of] already promises: pressing right at the end
 * of the wheel comes round again, and pressing out at the rim stays at the rim.
 */
fun HsbColor.nudged(nudge: WheelNudge): HsbColor =
    when (nudge) {
        WheelNudge.HUE_FORWARD -> withWheel(hue = hue + HUE_STEP, saturation = saturation)
        WheelNudge.HUE_BACK -> withWheel(hue = hue - HUE_STEP, saturation = saturation)
        WheelNudge.SATURATION_OUT -> withWheel(hue = hue, saturation = saturation + SATURATION_STEP)
        WheelNudge.SATURATION_IN -> withWheel(hue = hue, saturation = saturation - SATURATION_STEP)
    }
