package dev.pnptracker.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours that carry a meaning rather than a role.
 *
 * Material's scheme has no green. `tertiaryContainer` in the baseline scheme is
 * a mauve, so a finished row painted with it would be the wrong colour under a
 * name that sounds right — which is exactly the sort of thing nobody notices
 * until they look at the screen. PLAN 12.3 asks for a finished game to be shown
 * green, so the green is stated here instead of borrowed.
 *
 * The colour is never the only signal. PLAN 17 requires a finished row to say so
 * in words as well, and it does; this only makes it easy to see at a glance.
 */
data class StatusColors(
    /** Behind a game the user has finished. */
    val completedContainer: Color,
    /** Text and marks on [completedContainer]. */
    val onCompletedContainer: Color,
)

/** Pale green ground, very dark green ink. */
internal val LightStatusColors =
    StatusColors(
        completedContainer = Color(0xFFD3EFD9),
        onCompletedContainer = Color(0xFF0B3D1B),
    )

/** Deep green ground, pale green ink. */
internal val DarkStatusColors =
    StatusColors(
        completedContainer = Color(0xFF1B3A25),
        onCompletedContainer = Color(0xFFB8E7C5),
    )

internal val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** The status colours of the theme in force. */
object PnpStatus {
    val colors: StatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStatusColors.current
}
