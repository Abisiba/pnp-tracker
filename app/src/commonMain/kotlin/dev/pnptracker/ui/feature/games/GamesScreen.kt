package dev.pnptracker.ui.feature.games

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.pnptracker.ui.feature.ScreenBody
import dev.pnptracker.ui.navigation.Screen
import dev.pnptracker.ui.textsOf

/**
 * Where the game list and manual completion will live. Reading games from the
 * database is the work of a later step.
 */
@Composable
fun GamesScreen(modifier: Modifier = Modifier) {
    ScreenBody(texts = textsOf(Screen.Games), modifier = modifier)
}
