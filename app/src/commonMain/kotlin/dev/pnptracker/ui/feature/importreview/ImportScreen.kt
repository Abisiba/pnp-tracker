package dev.pnptracker.ui.feature.importreview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.pnptracker.ui.feature.ScreenBody
import dev.pnptracker.ui.navigation.Screen
import dev.pnptracker.ui.textsOf

/**
 * Where the two pane import review will live. No file is read and no draft is
 * written here yet; the tables behind it exist, the reader does not.
 */
@Composable
fun ImportScreen(modifier: Modifier = Modifier) {
    ScreenBody(texts = textsOf(Screen.Import), modifier = modifier)
}
