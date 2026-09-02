package dev.pnptracker.ui.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskEditFailure
import dev.pnptracker.domain.tasks.trackingModesOf
import dev.pnptracker.domain.text.graphemeBoundariesOf
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.feature.games.MulticolorDraft
import dev.pnptracker.ui.feature.games.TaskEditor
import dev.pnptracker.ui.feature.importworkspace.labelOf
import dev.pnptracker.ui.theme.opaqueColorOf
import dev.pnptracker.ui.theme.visibleEdgeOn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * What the task editor talks to, wherever it was opened from.
 *
 * A task is one record shown in two places: inside its cell in the game table,
 * and in the pool of the work it belongs to. PLAN 12.10 makes the second a
 * reflection of the first — "yazma bir havuz ekranından başlatılsa bile aynı
 * `Task` üzerinde gerçekleşir" — so both must offer the same form and end in the
 * same transaction. This is what makes that literal rather than a resemblance:
 * there is one panel, and each screen says how its own state answers it.
 *
 * Only the editing half is here. Everything a screen does that is its own — what
 * else can be open, where the panel is anchored, what closing goes back to —
 * stays with that screen, because those are the parts that genuinely differ.
 */
interface TaskEditingHost {
    fun editTaskName(name: String)

    fun editTaskEditColorQuery(query: String)

    fun chooseTaskEditColor(colorId: EntityId)

    fun moveTaskEditColorUp(slot: Int)

    fun moveTaskEditColorDown(slot: Int)

    fun editTaskEditQuantity(text: String)

    fun editTaskEditNotes(text: String)

    fun chooseTaskEditTracking(trackingMode: TrackingMode)

    /**
     * Turns one of the four import marks on or off.
     *
     * PLAN 11.7 makes `needs_info` the user's own judgement, so it is only ever
     * cleared from here: filling in a total does not quietly take it off.
     */
    fun setTaskEditMissing(isMissing: Boolean)

    fun setTaskEditBorrowed(isBorrowed: Boolean)

    fun setTaskEditNeedsInfo(needsInfo: Boolean)

    fun setTaskEditNeedsClassification(needsClassification: Boolean)

    /** Saves the whole answer at once, through the one editing transaction. */
    suspend fun saveTaskEdit()

    /** Closes the panel, going back to whatever it was opened over. */
    fun closeInnermost()
}

/** The shape the panels and their controls are cut to. */
private val ComposerShape = RoundedCornerShape(8.dp)

/** How tall the colour list may grow before it scrolls inside itself. */
private val ColorListHeight = 96.dp

/**
 * Changing what a task is, in the panel over its own word.
 *
 * Everything is saved together, because a name, its colours, a total and a note
 * are one answer to what the task is. A task made in one colour has that colour
 * replaced; a task made in several has the whole ordered list to work in — add,
 * take away, move — because PLAN 5.10 numbers those colours from the user's own
 * order and PLAN 12.7 draws the name split across them in it.
 *
 * What is not offered is turning one kind into the other. PLAN describes neither
 * crossing, so the panel does not put a control there that the transaction would
 * refuse.
 */
@Composable
internal fun TaskEditPanel(
    editor: TaskEditor,
    colors: List<ColorSummary>,
    catalogue: List<ColorSummary>,
    host: TaskEditingHost,
    newColorAction: @Composable (enabled: Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(editor.taskId) { focus.requestFocus() }
    val colorsAreThere = catalogue.stillHasEvery(editor.colorIds)
    val save = { if (editor.canSave && colorsAreThere) scope.launch { host.saveTaskEdit() } }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(text = stringResource(Strings.TaskEdit.title), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = editor.name,
            onValueChange = host::editTaskName,
            enabled = !editor.isSaving,
            singleLine = true,
            isError = editor.name.isNotEmpty() && !editor.isNameUsable,
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text(stringResource(Strings.TaskEdit.nameLabel)) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        if (editor.name.isNotEmpty() && !editor.isNameUsable) {
            NoteLine(text = stringResource(Strings.TaskEdit.nameInvalid), isProblem = true)
        }

        OutlinedTextField(
            value = editor.colorQuery,
            onValueChange = host::editTaskEditColorQuery,
            enabled = !editor.isSaving,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text(stringResource(Strings.CellTask.colorSearch)) },
            modifier = Modifier.fillMaxWidth(),
        )
        ColorList(
            colors = colors,
            chosen = editor.colorIds,
            enabled = !editor.isSaving,
            emptyQuery = editor.colorQuery.isBlank(),
            onChoose = host::chooseTaskEditColor,
        )
        newColorAction(!editor.isSaving)
        if (editor.holdsSeveralColors) {
            // The whole ordered list, editable: PLAN 5.10 numbers these from the
            // user's own order and PLAN 12.7 draws the name split across them in
            // it, so the order is part of what the task is. A task made in
            // several colours stays that way — it may not be emptied down to one
            // here, because PLAN says nothing about what such a task would
            // become.
            ChosenColorList(
                colorIds = editor.colorIds,
                catalogue = catalogue,
                name = editor.name,
                enabled = !editor.isSaving,
                leastColors = MulticolorDraft.LEAST_COLORS,
                floorText = stringResource(Strings.TaskEdit.colorFloor),
                failedSlot = editor.failureRow?.takeIf { it in editor.colorIds.indices },
                failureText = editor.failure?.let { sentenceOf(it, editor.failureRow, editor.failureConflictsWith) },
                onMoveUp = host::moveTaskEditColorUp,
                onMoveDown = host::moveTaskEditColorDown,
                onDrop = host::chooseTaskEditColor,
            )
        }

        OutlinedTextField(
            value = editor.quantityText,
            onValueChange = host::editTaskEditQuantity,
            enabled = !editor.isSaving,
            singleLine = true,
            isError = !editor.isQuantityUsable,
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text(stringResource(Strings.CellTask.quantityLabel)) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!editor.isQuantityUsable) {
            NoteLine(text = stringResource(Strings.CellTask.quantityInvalid), isProblem = true)
        }

        val trackingChoices = trackingChoicesFor(editor)
        if (trackingChoices.size > 1) {
            TrackingChoice(
                choices = trackingChoices,
                chosen = editor.trackingMode,
                onChoose = host::chooseTaskEditTracking,
            )
        }

        TaskFlagChoices(editor = editor, host = host)

        OutlinedTextField(
            value = editor.notes,
            onValueChange = host::editTaskEditNotes,
            enabled = !editor.isSaving,
            singleLine = false,
            minLines = 1,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text(stringResource(Strings.CellTask.notesLabel)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val saveLabel = stringResource(Strings.TaskEdit.save)
            val discardLabel = stringResource(Strings.CellTask.discard)
            Button(
                onClick = { save() },
                enabled = editor.canSave && colorsAreThere,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = saveLabel },
            ) {
                Text(text = saveLabel, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
                onClick = host::closeInnermost,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = discardLabel },
            ) {
                Text(text = discardLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
        val note =
            when {
                editor.isSaving -> stringResource(Strings.TaskEdit.saving)
                editor.failure != null -> sentenceOf(editor.failure, editor.failureRow, editor.failureConflictsWith)
                // Said in words, because a colour that is gone leaves nothing to
                // look at: the row is still chosen, it just names nothing now.
                !colorsAreThere -> stringResource(Strings.CellTask.colorGone)
                else -> stringResource(Strings.TaskEdit.hint)
            }
        NoteLine(text = note, isProblem = editor.failure != null || !colorsAreThere)
    }
}

/** The tracking modes this task's pool allows, in the order it lists them. */
@Composable
internal fun trackingChoicesFor(editor: TaskEditor): List<TrackingMode> =
    remember(editor.trackingMode) {
        PoolType.entries
            .firstOrNull { editor.trackingMode in trackingModesOf(it) }
            ?.let { trackingModesOf(it) }
            .orEmpty()
    }

@Composable
internal fun TrackingChoice(
    choices: List<TrackingMode>,
    chosen: TrackingMode?,
    onChoose: (TrackingMode) -> Unit,
) {
    Text(
        text = stringResource(Strings.Tasks.trackingLabel),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        choices.forEach { mode ->
            val isChosen = chosen == mode
            val stateText =
                stringResource(if (isChosen) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
            FilterChip(
                selected = isChosen,
                onClick = { onChoose(mode) },
                label = { Text(stringResource(labelOf(mode)), style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.focusOutline(ComposerShape).semantics { stateDescription = stateText },
            )
        }
    }
}

/**
 * The colours a task is made in, in the order they will be drawn.
 *
 * Numbered from one for the reader and moved with two buttons, so the order —
 * which is what PLAN 5.10 stores and PLAN 12.7 draws the name across — is both
 * visible and changeable without a gesture. A colour that has gone from the
 * catalogue while the panel was open is still listed and marked, because
 * dropping it silently would take away something the user chose.
 *
 * The line about a name shorter than the list is a statement of what will
 * happen, not a warning about a problem: those colours are drawn as swatches
 * beside the word (PLAN 12.7), and nothing is refused because of it.
 */
@Composable
internal fun ChosenColorList(
    colorIds: List<EntityId>,
    catalogue: List<ColorSummary>,
    name: String,
    enabled: Boolean,
    leastColors: Int,
    floorText: String,
    failedSlot: Int?,
    failureText: String?,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onDrop: (EntityId) -> Unit,
) {
    Text(
        text = stringResource(Strings.CellTask.colorOrderLabel),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (colorIds.isEmpty()) {
        NoteLine(text = stringResource(Strings.CellTask.colorNoneChosen), isProblem = false)
    }
    val unknown = stringResource(Strings.CellTask.colorUnknown)
    colorIds.forEachIndexed { slot, colorId ->
        val color = catalogue.firstOrNull { it.id == colorId }
        val colorName = color?.canonicalName ?: unknown
        val swatch = color?.let { opaqueColorOf(it.hex) }
        // Wrapped rather than one line: three actions and a name do not fit
        // across a table column, and given a weight the name was squeezed to
        // nothing — leaving a swatch as the only thing saying which colour it
        // was, which PLAN 17 does not allow. Here the actions drop to a line of
        // their own instead, and in a wider panel they stay beside the name.
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (swatch != null) {
                    Box(
                        modifier =
                            Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(swatch)
                                .border(1.dp, visibleEdgeOn(swatch), RoundedCornerShape(3.dp)),
                    )
                }
                Text(
                    text = stringResource(Strings.CellTask.colorSlot, slot + 1, colorName),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (color == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val upLabel = stringResource(Strings.CellTask.colorMoveUp, colorName)
            val downLabel = stringResource(Strings.CellTask.colorMoveDown, colorName)
            val dropLabel = stringResource(Strings.CellTask.colorDrop, colorName)
            // The three actions travel together, as one thing to wrap. Left
            // loose they broke apart mid-entry: one action beside the name and
            // two on the line below, which reads as two entries.
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onMoveUp(slot) },
                    enabled = enabled && slot > 0,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = upLabel },
                ) {
                    Text(
                        text = stringResource(Strings.CellTask.colorMoveUpShort),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(
                    onClick = { onMoveDown(slot) },
                    enabled = enabled && slot < colorIds.lastIndex,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = downLabel },
                ) {
                    Text(
                        text = stringResource(Strings.CellTask.colorMoveDownShort),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(
                    onClick = { onDrop(colorId) },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = dropLabel },
                ) {
                    Text(
                        text = stringResource(Strings.CellTask.colorDropShort),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        if (failedSlot == slot && failureText != null) {
            // Said on the entry it is about: a list of colours with one message
            // under all of them does not say which one went away.
            NoteLine(text = failureText, isProblem = true)
        }
    }
    if (colorIds.size < leastColors) {
        NoteLine(text = floorText, isProblem = false)
    }
    // Worked out once for the name and the count rather than on every frame.
    val characters = remember(name) { graphemeBoundariesOf(name).size - 1 }
    if (colorIds.size > characters) {
        NoteLine(text = stringResource(Strings.CellTask.colorOverflow), isProblem = false)
    }
}

/**
 * The catalogue, narrowed by what has been typed, one entry per colour.
 *
 * Every entry carries the colour's written name beside its swatch, because PLAN
 * 17 does not let a colour be the only thing carrying a meaning, and which one
 * is chosen is said in words as well as by the fill. The swatch is edged for the
 * same reason a task is: white on a light list, or black on a dark one, would
 * otherwise be a square nobody can see.
 */
@Composable
internal fun ColorList(
    colors: List<ColorSummary>,
    chosen: List<EntityId>,
    enabled: Boolean,
    emptyQuery: Boolean,
    onChoose: (EntityId) -> Unit,
) {
    if (colors.isEmpty()) {
        NoteLine(
            text = stringResource(if (emptyQuery) Strings.CellTask.colorEmpty else Strings.CellTask.colorNone),
            isProblem = false,
        )
        return
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = ColorListHeight)
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
    ) {
        colors.forEach { color ->
            val isChosen = color.id in chosen
            val stateText =
                stringResource(if (isChosen) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
            val swatch = opaqueColorOf(color.hex)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = isChosen, enabled = enabled, onClick = { onChoose(color.id) })
                        .background(if (isChosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .padding(horizontal = 4.dp, vertical = 3.dp)
                        .semantics { stateDescription = stateText },
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(swatch)
                            .border(1.dp, visibleEdgeOn(swatch), RoundedCornerShape(3.dp)),
                )
                Text(
                    text = color.canonicalName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One line of explanation under a field, red when it is a problem. */
@Composable
internal fun NoteLine(
    text: String,
    isProblem: Boolean,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun sentenceOf(
    failure: TaskEditFailure,
    row: Int?,
    conflictsWith: Int?,
): String =
    if (failure == TaskEditFailure.DUPLICATE_COLOR && row != null && conflictsWith != null) {
        stringResource(Strings.TaskEdit.errorDuplicateColor, conflictsWith + 1, row + 1)
    } else {
        stringResource(taskEditMessageOf(failure))
    }

/**
 * True when the catalogue still has every colour in [ids].
 *
 * A colour someone removed elsewhere leaves the draft exactly as the user built
 * it, so the panel has to ask this rather than assume: the choice is still
 * there, it just does not name anything any more.
 */
internal fun List<ColorSummary>.stillHasEvery(ids: List<EntityId>): Boolean = isEmpty() || ids.all { id -> any { it.id == id } }

/** What to tell the user about a task that did not change. */
internal fun taskEditMessageOf(failure: TaskEditFailure) =
    when (failure) {
        TaskEditFailure.TASK_NOT_AVAILABLE -> Strings.TaskEdit.errorTaskGone
        TaskEditFailure.TASK_NAME_EMPTY -> Strings.TaskEdit.errorNameEmpty
        TaskEditFailure.NAME_CONTAINS_LINE_BREAK -> Strings.TaskEdit.errorNameLineBreak
        TaskEditFailure.COLOR_NOT_AVAILABLE -> Strings.TaskEdit.errorColorGone
        TaskEditFailure.DUPLICATE_COLOR -> Strings.TaskEdit.errorDuplicateColor
        TaskEditFailure.COLOR_COUNT_NOT_CHANGEABLE -> Strings.TaskEdit.errorColorCount
        TaskEditFailure.INVALID_REQUIRED_QUANTITY -> Strings.TaskEdit.errorQuantity
        TaskEditFailure.QUANTITY_BELOW_PROGRESS -> Strings.TaskEdit.errorQuantityBelowProgress
        TaskEditFailure.QUANTITY_LOCKED_BY_COMPLETION -> Strings.TaskEdit.errorQuantityLocked
        TaskEditFailure.MISSING_AND_BORROWED -> Strings.Review.flagConflict
        TaskEditFailure.COULD_NOT_SAVE -> Strings.TaskEdit.errorCouldNotSave
    }

/**
 * The four marks a task carries, as things to tick.
 *
 * They are not pools and the line under them says so: PLAN 10 keeps `Eksik` and
 * `Ödünç Parçalar` as notes about the work rather than places it is done, and
 * PLAN 11.7 has `Bilgi eksik` keep an open task out of its active pool until the
 * user says the question is answered. Ticking missing unticks borrowed and the
 * other way about, because a cell came from one column.
 */
@Composable
internal fun TaskFlagChoices(
    editor: TaskEditor,
    host: TaskEditingHost,
) {
    Text(
        text = stringResource(Strings.TaskEdit.flagsTitle),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TaskFlagBox(stringResource(Strings.Review.flagMissing), editor.isMissing, !editor.isSaving) {
            host.setTaskEditMissing(it)
        }
        TaskFlagBox(stringResource(Strings.Review.flagBorrowed), editor.isBorrowed, !editor.isSaving) {
            host.setTaskEditBorrowed(it)
        }
        TaskFlagBox(stringResource(Strings.Review.flagNeedsInfo), editor.needsInfo, !editor.isSaving) {
            host.setTaskEditNeedsInfo(it)
        }
        TaskFlagBox(
            stringResource(Strings.Review.flagNeedsClassification),
            editor.needsClassification,
            !editor.isSaving,
        ) { host.setTaskEditNeedsClassification(it) }
    }
    NoteLine(text = stringResource(Strings.TaskEdit.flagsNote), isProblem = false)
    if (editor.flagsConflict) {
        NoteLine(text = stringResource(Strings.Review.flagConflict), isProblem = true)
    }
}

@Composable
private fun TaskFlagBox(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = label },
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Draws a ring around whatever holds keyboard focus, without moving anything. */
@Composable
internal fun Modifier.focusOutline(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusEvent { focused = it.hasFocus }
        .border(
            width = 2.dp,
            color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = shape,
        )
}
