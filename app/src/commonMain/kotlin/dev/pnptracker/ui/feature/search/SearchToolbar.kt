package dev.pnptracker.ui.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.feature.tasks.focusOutline
import dev.pnptracker.ui.theme.opaqueColorOf
import dev.pnptracker.ui.theme.visibleEdgeOn
import org.jetbrains.compose.resources.stringResource

private val ChipShape =
    androidx.compose.foundation.shape
        .RoundedCornerShape(8.dp)
private val PanelMaxHeight = 460.dp

/**
 * The box the user types what they are looking for into.
 *
 * Its own row rather than a cell of the toolbar. At the widths this application
 * is used at — PLAN's own manual round goes down to 720 — the view chips already
 * fill a line, and a field squeezed in beside them would be too narrow to read a
 * game's name back in. A row of its own is the same shape at every width, which
 * is the one thing a toolbar must be.
 *
 * The clear button is only there when there is something to clear, and it says
 * in words what it does: PLAN 17 will not have an icon be the only carrier of a
 * meaning.
 */
@Composable
fun SearchField(
    text: String,
    onChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
) {
    val label = stringResource(Strings.Search.label)
    val clear = stringResource(Strings.Search.clear)
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = { Text(stringResource(Strings.Search.placeholder)) },
            singleLine = true,
            modifier =
                Modifier
                    .weight(1f)
                    .then(focus?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .semantics { contentDescription = label },
        )
        if (text.isNotEmpty()) {
            TextButton(
                onClick = onClear,
                modifier = Modifier.focusOutline(ChipShape).semantics { contentDescription = clear },
            ) {
                Text(text = clear, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * The button that opens the panel of filter choices.
 *
 * It carries the number of choices that are in force, so the state of the filter
 * is legible without opening it — and the number is in the button's own name, so
 * a reader hears it too rather than seeing a badge nobody reads out.
 */
@Composable
fun FilterButton(
    chosenCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
) {
    val label =
        if (chosenCount == 0) {
            stringResource(Strings.Search.openFilters)
        } else {
            stringResource(Strings.Search.openFiltersWithCount, chosenCount.toString())
        }
    OutlinedButton(
        onClick = onClick,
        modifier =
            modifier
                .focusOutline(ChipShape)
                .then(focus?.let { Modifier.focusRequester(it) } ?: Modifier)
                .semantics { contentDescription = label },
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * What is in force right now, written out.
 *
 * A sentence and not a row of icons. It is what tells somebody why the list in
 * front of them is short, and it is the same sentence a screen reader is given —
 * PLAN 17 asks for the meaning to exist outside the drawing of it.
 */
@Composable
fun FilterSummary(
    parts: List<String>,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary =
        if (parts.isEmpty()) {
            stringResource(Strings.Search.summaryNone)
        } else {
            stringResource(Strings.Search.summaryLabel, parts.joinToString(", "))
        }
    val clearAll = stringResource(Strings.Search.clearAll)
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = summary },
        )
        if (parts.isNotEmpty()) {
            TextButton(
                onClick = onClearAll,
                modifier = Modifier.focusOutline(ChipShape).semantics { contentDescription = clearAll },
            ) {
                Text(text = clearAll, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Closes the filter panel when Escape is pressed anywhere on the screen under it.
 *
 * The panel closes itself when the keyboard is inside it, but the keyboard is
 * often on the button that opened it instead — and a panel that could only be
 * dismissed from inside would be a trap for anybody working without a mouse.
 * Nothing else is intercepted, so every surface underneath keeps its own Escape.
 */
fun Modifier.closesFilterPanelOnEscape(
    isOpen: Boolean,
    onClose: () -> Unit,
): Modifier =
    onPreviewKeyEvent { event ->
        if (isOpen && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
            onClose()
            true
        } else {
            false
        }
    }

/**
 * The panel of filter choices, opened under the button that asked for it.
 *
 * Drawn in the ordinary composition rather than in a floating layer, and that is
 * a decision rather than a shortcut. A layer of its own would be one more focus
 * scope to step into and out of, one more thing that can be positioned off the
 * edge of an 880 pixel window, and one more place for Escape to mean something
 * different. Inline, the choices are simply the next Tab stops after the button,
 * Escape is the screen's own, and the panel cannot be anywhere but where the
 * page is.
 *
 * It is bounded and scrolls inside itself, so a catalogue of forty colours
 * lengthens the panel to a point and then stops rather than pushing the list of
 * work off the bottom.
 */
@Composable
fun FilterPanel(
    onClose: () -> Unit,
    onClearAll: () -> Unit,
    hasChoices: Boolean,
    content: @Composable () -> Unit,
) {
    val title = stringResource(Strings.Search.filtersTitle)
    val close = stringResource(Strings.Search.closeFilters)
    val clearAll = stringResource(Strings.Search.clearAll)
    Surface(
        shape = ChipShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            Modifier
                .fillMaxWidth()
                // Escape closes it from inside as well, for when the keyboard has
                // stepped into the choices.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
                },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier.weight(1f).semantics {
                            heading()
                            contentDescription = title
                        },
                )
                if (hasChoices) {
                    TextButton(
                        onClick = onClearAll,
                        modifier = Modifier.focusOutline(ChipShape).semantics { contentDescription = clearAll },
                    ) {
                        Text(text = clearAll, style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.focusOutline(ChipShape).semantics { contentDescription = close },
                ) {
                    Text(text = close, style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider()
            Column(
                modifier = Modifier.heightIn(max = PanelMaxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

/** One heading inside the panel, so a reader can move between the groups. */
@Composable
fun FilterSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
}

/** The choices of one section, wrapping rather than running off the edge. */
@Composable
fun FilterChoiceRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}

/**
 * One choice, which says in words whether it is chosen.
 *
 * The filled background is a second signal and never the only one: a reader is
 * told the state, and so is anybody who cannot tell the two fills apart.
 */
@Composable
fun FilterChoice(
    label: String,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateText = stringResource(if (selected) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        modifier =
            modifier.focusOutline(ChipShape).semantics {
                contentDescription = label
                stateDescription = stateText
            },
    )
}

/**
 * A colour choice: the swatch, and the colour's own name beside it.
 *
 * Never the swatch alone. PLAN 17 will not have colour carry a meaning by
 * itself, so the name is what the chip is called and what a reader is told; the
 * square is the second signal. White on white and black on black are handled the
 * way every other swatch in the application is — a visible edge, and ink chosen
 * to be readable on the fill.
 */
@Composable
fun ColorFilterChoice(
    colorName: String,
    hex: String,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val fill = opaqueColorOf(hex)
    val label = stringResource(Strings.Search.colorOption, colorName)
    val stateText = stringResource(if (selected) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
    FilterChip(
        selected = selected,
        onClick = onToggle,
        leadingIcon = {
            Surface(
                shape =
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(3.dp),
                color = fill,
                border = BorderStroke(1.dp, visibleEdgeOn(fill)),
                modifier = Modifier.padding(vertical = 2.dp),
                content = {
                    androidx.compose.foundation.layout
                        .Box(Modifier.padding(6.dp))
                },
            )
        },
        label = {
            Text(
                text = colorName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        modifier =
            Modifier.focusOutline(ChipShape).semantics {
                contentDescription = label
                stateDescription = stateText
            },
    )
}
