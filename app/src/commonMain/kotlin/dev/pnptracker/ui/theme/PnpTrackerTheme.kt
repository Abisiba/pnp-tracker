package dev.pnptracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Material 3 with the baseline light and dark schemes, whose text and background
 * pairs already meet the contrast the plan asks for.
 *
 * These are interface colours and have nothing to do with the production colour
 * catalogue: a paint colour named "Kırmızı" is user data, this is chrome.
 *
 * Sizes stay in `dp` and `sp` throughout, so the window follows the display
 * scaling instead of assuming a pixel density.
 */
@Composable
fun PnpTrackerTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme =
            when (themeMode) {
                ThemeMode.LIGHT -> lightColorScheme()
                ThemeMode.DARK -> darkColorScheme()
            },
        content = content,
    )
}
