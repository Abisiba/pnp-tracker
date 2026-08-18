package dev.pnptracker.ui.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pnptracker.ui.ScreenTexts
import org.jetbrains.compose.resources.stringResource

/**
 * The heading and explanation every section shares.
 *
 * Scrolls rather than clipping, so a short window or a large text scale hides
 * nothing.
 */
@Composable
internal fun ScreenBody(
    texts: ScreenTexts,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(texts.title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(texts.description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 640.dp),
        )
    }
}
