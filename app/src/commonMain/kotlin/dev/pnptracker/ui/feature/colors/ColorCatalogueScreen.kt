package dev.pnptracker.ui.feature.colors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.ui.Strings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val MAX_CONTENT_WIDTH = 720.dp
private val SWATCH_SIZE = 28.dp
private val SWATCH_SHAPE = RoundedCornerShape(6.dp)

/**
 * The global colour catalogue.
 *
 * Every swatch is drawn beside the written name of its colour and never alone,
 * because PLAN 17 does not allow a colour to be the only thing carrying a
 * meaning: a reader who cannot tell two swatches apart still reads two names.
 */
@Composable
fun ColorCatalogueScreen(
    controller: ColorCatalogueController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = controller.state

    LaunchedEffect(Unit) { controller.observeColors() }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Strings.ScreenTitles.colors),
            style = MaterialTheme.typography.headlineSmall,
        )
        FailureLine(state.failure)

        when (val catalogue = state.catalogue) {
            ColorCatalogueState.Loading -> BusyRow(stringResource(Strings.Colors.loading))

            is ColorCatalogueState.Content ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().widthIn(max = MAX_CONTENT_WIDTH),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "composer") {
                        ComposerOrButton(
                            composer = state.composer,
                            isSaving = controller.isSaving,
                            onStart = { controller.startComposer() },
                            onEditName = { name -> controller.editName(name) },
                            onEditHex = { hex -> scope.launch { controller.editHex(hex) } },
                            onSave = { scope.launch { controller.save() } },
                            onDiscard = { controller.cancelComposer() },
                        )
                    }
                    item(key = "count") {
                        Text(
                            text = stringResource(Strings.Colors.count, catalogue.colors.size.toString()),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    items(catalogue.colors, key = { it.id.toString() }) { color -> ColorRow(color) }
                }
        }
    }
}

@Composable
private fun ColorRow(color: ColorSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(hex = color.hex, name = color.canonicalName)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = color.canonicalName, style = MaterialTheme.typography.bodyLarge)
                Text(text = color.hex, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * A square of one colour.
 *
 * It carries the colour's name as its description, so what it shows is also
 * available to a reader who is not looking at it.
 */
@Composable
private fun Swatch(
    hex: String,
    name: String,
) {
    val description = stringResource(Strings.Colors.swatch, name)
    Box(
        modifier =
            Modifier
                .size(SWATCH_SIZE)
                .clip(SWATCH_SHAPE)
                .background(opaqueColorOf(hex))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, SWATCH_SHAPE)
                .semantics { contentDescription = description },
    )
}

/**
 * `#RRGGBB` as an opaque colour.
 *
 * Only ever called with a value the form has already accepted, so there is
 * nothing here to fall back to.
 */
private fun opaqueColorOf(hex: String): Color {
    val digits = hex.removePrefix("#")
    return Color(
        red = digits.substring(0, 2).toInt(radix = 16),
        green = digits.substring(2, 4).toInt(radix = 16),
        blue = digits.substring(4, 6).toInt(radix = 16),
    )
}

@Composable
private fun ComposerOrButton(
    composer: ColorComposer?,
    isSaving: Boolean,
    onStart: () -> Unit,
    onEditName: (String) -> Unit,
    onEditHex: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    if (composer == null) {
        Button(onClick = onStart, enabled = !isSaving) {
            Text(stringResource(Strings.Colors.create))
        }
        return
    }

    Card(modifier = Modifier.fillMaxWidth().widthIn(max = MAX_CONTENT_WIDTH)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = composer.name,
                onValueChange = onEditName,
                label = { Text(stringResource(Strings.Colors.nameLabel)) },
                isError = !composer.isNameUsable,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!composer.isNameUsable) NoteLine(stringResource(Strings.Colors.nameRequired), isError = true)

            OutlinedTextField(
                value = composer.hex,
                onValueChange = onEditHex,
                label = { Text(stringResource(Strings.Colors.hexLabel)) },
                isError = composer.hex.isNotBlank() && !composer.isHexUsable,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (composer.hex.isNotBlank() && !composer.isHexUsable) {
                NoteLine(stringResource(Strings.Colors.hexInvalid), isError = true)
            } else {
                NoteLine(stringResource(Strings.Colors.hexHint), isError = false)
            }

            composer.previewHex?.let { hex ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Swatch(hex = hex, name = composer.name.ifBlank { hex })
                    Text(
                        text = "${stringResource(Strings.Colors.preview)} · $hex",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            if (composer.sharesHexWithAnotherColor) {
                NoteLine(
                    stringResource(
                        Strings.Colors.hexShared,
                        composer.colorsSharingHex.joinToString(", ") { it.canonicalName },
                    ),
                    isError = false,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = composer.canSave && !isSaving) {
                    Text(stringResource(Strings.Colors.save))
                }
                OutlinedButton(onClick = onDiscard, enabled = !isSaving) {
                    Text(stringResource(Strings.Colors.discard))
                }
            }
        }
    }
}

@Composable
private fun NoteLine(
    text: String,
    isError: Boolean,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FailureLine(failure: ColorSetupFailure?) {
    if (failure == null) return
    Text(
        text =
            stringResource(
                when (failure) {
                    ColorSetupFailure.COULD_NOT_SAVE -> Strings.Colors.errorCouldNotSave
                    ColorSetupFailure.NAME_ALREADY_USED -> Strings.Colors.errorNameUsed
                    ColorSetupFailure.NAME_IS_ANOTHER_COLORS_ALIAS -> Strings.Colors.errorNameIsAlias
                    ColorSetupFailure.COLOR_NO_LONGER_EXISTS -> Strings.Colors.errorColorGone
                },
            ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun BusyRow(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator()
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}
