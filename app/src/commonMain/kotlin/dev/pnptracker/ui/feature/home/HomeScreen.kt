package dev.pnptracker.ui.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.pnptracker.ui.feature.ScreenBody
import dev.pnptracker.ui.navigation.Screen
import dev.pnptracker.ui.textsOf

/**
 * Where the game and pool summaries of the plan will live. It reads nothing from
 * the database yet, so it shows no counters it cannot stand behind.
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    ScreenBody(texts = textsOf(Screen.Home), modifier = modifier)
}
