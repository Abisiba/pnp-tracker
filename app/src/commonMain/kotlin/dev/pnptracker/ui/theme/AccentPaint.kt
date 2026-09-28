package dev.pnptracker.ui.theme

import androidx.compose.ui.graphics.Color
import dev.pnptracker.domain.settings.AccentColor
import dev.pnptracker.domain.settings.ThemeMode

/**
 * What one accent is drawn with, in one theme.
 *
 * Two colours and not one, because an accent is a surface as well as a mark: a
 * button is filled with [colour] and its label is written in [ink], and the pair
 * is what has to be readable rather than either on its own (PLAN 17).
 */
data class AccentPaint(
    val colour: Color,
    val ink: Color,
)

/**
 * The accent palette.
 *
 * Each accent is given a mid tone for the light theme, written on in white, and a
 * pale tone for the dark theme, written on in a very dark one — which is the
 * arrangement Material's own baseline uses, and the reason the words stay legible
 * whichever theme is on. The purple is Material's baseline itself, so the default
 * look does not change at all.
 *
 * Nothing here touches the colour catalogue. These are interface colours; a paint
 * the user named and assigned to a 3D task is their data and is drawn from the
 * catalogue's own hex value.
 */
fun accentPaintOf(
    accent: AccentColor,
    themeMode: ThemeMode,
): AccentPaint =
    when (themeMode) {
        ThemeMode.LIGHT ->
            when (accent) {
                AccentColor.PURPLE -> AccentPaint(Color(0xFF6750A4), Color.White)
                AccentColor.BLUE -> AccentPaint(Color(0xFF0B57D0), Color.White)
                AccentColor.TEAL -> AccentPaint(Color(0xFF00696E), Color.White)
                AccentColor.GREEN -> AccentPaint(Color(0xFF2E6B34), Color.White)
                AccentColor.AMBER -> AccentPaint(Color(0xFF8A5000), Color.White)
                AccentColor.ROSE -> AccentPaint(Color(0xFFA1354F), Color.White)
            }

        ThemeMode.DARK ->
            when (accent) {
                AccentColor.PURPLE -> AccentPaint(Color(0xFFD0BCFF), Color(0xFF381E72))
                AccentColor.BLUE -> AccentPaint(Color(0xFFA8C7FA), Color(0xFF062E6F))
                AccentColor.TEAL -> AccentPaint(Color(0xFF4FD8E4), Color(0xFF00363A))
                AccentColor.GREEN -> AccentPaint(Color(0xFF9BD49B), Color(0xFF0A390F))
                AccentColor.AMBER -> AccentPaint(Color(0xFFFFB870), Color(0xFF4A2800))
                AccentColor.ROSE -> AccentPaint(Color(0xFFFFB1C4), Color(0xFF5E1133))
            }
    }
