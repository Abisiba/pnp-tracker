package dev.pnptracker.ui.feature.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.games.CellPreview
import dev.pnptracker.domain.games.CellSegmentPreview
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import dev.pnptracker.domain.tasks.trackingModesOf
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.columnNameOf
import dev.pnptracker.ui.feature.importworkspace.labelOf
import dev.pnptracker.ui.theme.PnpStatus
import dev.pnptracker.ui.theme.opaqueColorOf
import dev.pnptracker.ui.theme.readableInkOn
import dev.pnptracker.ui.viewNameOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The name column is wide enough for a real game name before anything wraps. */
private val GameColumnWidth = 240.dp

/** Each cell column. Fixed, so the table lines up and can be scrolled sideways. */
private val CellColumnWidth = 200.dp

private val TableWidth = GameColumnWidth + CellColumnWidth * CellColumnType.entries.size

/** How much of a cell is previewed before the rest is left for the editor. */
private const val CELL_PREVIEW_LINES = 3

/** How tall the editor grows before it scrolls inside itself. */
private const val EDITOR_LINES = 8

/**
 * How tall the colour list grows before it scrolls inside the panel.
 *
 * Kept short deliberately. The panel lives inside one cell of a dense table, and
 * every line it takes is a line the row it belongs to grows by.
 */
private val ColorListHeight = 96.dp

/**
 * The gap drawn between a task and its count.
 *
 * Drawn and never stored. The count is something the table says about a task —
 * PLAN 12.5 — so neither it nor the space before it is part of anybody's text,
 * and the cell reads back without either of them.
 */
private const val QUANTITY_GAP = " "

private val ComposerShape = RoundedCornerShape(8.dp)

/**
 * The game table: the surface the whole application is worked from.
 *
 * PLAN 12.3 makes a game one row and never a task, and PLAN 12.4 puts three
 * views over the same table rather than three screens. Both are visible here:
 * one list of rows, and a filter above it that reads nothing new when it changes.
 *
 * A cell is written in where it sits, and a task is made out of the words the
 * user picked out of what they wrote. Nothing here is clickable that would not
 * do anything: a task drawn in a cell is not a control yet, so it is not drawn
 * as one either.
 */
@Composable
fun GameTableScreen(controller: GameTableController) {
    LaunchedEffect(controller) { controller.observeTable() }
    LaunchedEffect(controller) { controller.observeColorCatalogue() }

    val state = controller.state
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = stringResource(Strings.ScreenTitles.games),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(Strings.ScreenDescriptions.games),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        TableControls(controller = controller, state = state)
        FailureLine(state.failure)
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        when (val rows = state.rows) {
            GameTableRowsState.Loading -> Message(stringResource(Strings.Table.loading))
            is GameTableRowsState.Empty -> EmptyTable(rows)
            is GameTableRowsState.Content -> Table(rows.rows, controller, state)
        }
    }
}

/** The view filter and the one action, above the table. */
@Composable
private fun TableControls(
    controller: GameTableController,
    state: GameTableScreenState,
) {
    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .selectableGroup()
                    .semantics { contentDescription = "" },
        ) {
            Text(
                text = stringResource(Strings.Table.viewLabel),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GameTableView.entries.forEach { view ->
                ViewChip(
                    view = view,
                    selected = state.view == view,
                    onSelect = { controller.showView(view) },
                )
            }
        }

        if (state.blockedByEditor) {
            // Nothing is saved and nothing is thrown away; the user is told the
            // open cell is waiting for them to finish with it.
            Text(
                text = stringResource(Strings.Cell.editorOpenElsewhere),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val composer = state.gameComposer
        if (composer == null) {
            Button(
                onClick = controller::startGameComposer,
                modifier = Modifier.focusOutline(ComposerShape),
            ) {
                Text(text = stringResource(Strings.Table.addGame))
            }
        } else {
            GameComposer(controller = controller, composer = composer, isSaving = controller.isSaving)
        }
    }
}

/**
 * One of the three views.
 *
 * The chip says in words whether it is the one showing, so the filled background
 * is a second signal rather than the only one.
 */
@Composable
private fun ViewChip(
    view: GameTableView,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val stateText =
        stringResource(if (selected) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
    FilterChip(
        selected = selected,
        onClick = onSelect,
        label = {
            Text(
                text = stringResource(viewNameOf(view)),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        modifier =
            Modifier
                .focusOutline(ComposerShape)
                .semantics { stateDescription = stateText },
    )
}

/**
 * The compact row for naming a new game.
 *
 * Enter saves and Escape gives up, which is what a desktop user reaches for
 * before they reach for a button. It is a row in place rather than a dialog: the
 * table stays visible behind what is being added to it.
 */
@Composable
private fun GameComposer(
    controller: GameTableController,
    composer: NameComposer,
    isSaving: Boolean,
) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val save = { if (composer.canSave && !isSaving) scope.launch { controller.saveGame() } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = composer.name,
                onValueChange = controller::editGameName,
                label = { Text(stringResource(Strings.Games.nameLabel)) },
                singleLine = true,
                isError = composer.name.isNotEmpty() && !composer.canSave,
                keyboardOptions = KeyboardOptions(),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier =
                    Modifier
                        .widthIn(min = 200.dp, max = GameColumnWidth)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    save()
                                    true
                                }

                                Key.Escape -> {
                                    controller.cancelGameComposer()
                                    true
                                }

                                else -> false
                            }
                        },
            )
            Button(
                onClick = { save() },
                enabled = composer.canSave && !isSaving,
                modifier = Modifier.focusOutline(ComposerShape),
            ) {
                Text(text = stringResource(Strings.Games.save))
            }
            TextButton(
                onClick = controller::cancelGameComposer,
                modifier = Modifier.focusOutline(ComposerShape),
            ) {
                Text(text = stringResource(Strings.Games.discard))
            }
        }
        Text(
            text =
                if (composer.name.isNotEmpty() && !composer.canSave) {
                    stringResource(Strings.Games.nameRequired)
                } else {
                    stringResource(Strings.Table.addGameHint)
                },
            style = MaterialTheme.typography.bodySmall,
            color =
                if (composer.name.isNotEmpty() && !composer.canSave) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/**
 * The table itself.
 *
 * The header and the rows scroll sideways together and the rows scroll down on
 * their own, so a narrow window loses neither the far columns nor the ability to
 * reach them.
 */
@Composable
private fun Table(
    rows: List<GameTableRow>,
    controller: GameTableController,
    state: GameTableScreenState,
) {
    val horizontal = rememberScrollState()
    val label = stringResource(Strings.Table.label)
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontal)
                    .semantics { contentDescription = label },
        ) {
            TableHeader()
            HorizontalDivider(modifier = Modifier.width(TableWidth))
            LazyColumn(modifier = Modifier.width(TableWidth).fillMaxHeight()) {
                items(rows, key = { it.gameId.value }) { row ->
                    TableRow(row = row, controller = controller, state = state)
                    HorizontalDivider(modifier = Modifier.width(TableWidth))
                }
            }
        }
    }
}

@Composable
private fun TableHeader() {
    Row(modifier = Modifier.width(TableWidth).padding(vertical = 8.dp)) {
        HeaderCell(text = stringResource(Strings.Columns.game), width = GameColumnWidth)
        CellColumnType.entries.forEach { columnType ->
            HeaderCell(text = stringResource(columnNameOf(columnType)), width = CellColumnWidth)
        }
    }
}

@Composable
private fun HeaderCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(horizontal = 12.dp),
    )
}

/**
 * One game.
 *
 * A finished game is drawn on a green ground, and carries a tick and the word
 * `Tamamlandı` beside its name: PLAN 17 will not have colour be the only thing
 * saying what a row is.
 */
@Composable
private fun TableRow(
    row: GameTableRow,
    controller: GameTableController,
    state: GameTableScreenState,
) {
    val stateText =
        stringResource(if (row.isCompleted) Strings.Table.rowCompleted else Strings.Table.rowOngoing)
    val description = stringResource(Strings.Table.rowDescription, row.gameName, stateText)
    val background =
        if (row.isCompleted) {
            PnpStatus.colors.completedContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    Row(
        modifier =
            Modifier
                .width(TableWidth)
                .background(background)
                .semantics {
                    contentDescription = description
                    stateDescription = stateText
                },
    ) {
        GameNameCell(row = row, stateText = stateText)
        CellColumnType.entries.forEach { columnType ->
            val cell = row.cell(columnType)
            val openHere = state.editor?.takeIf { it.isOn(row.gameId, columnType) }
            if (openHere == null) {
                CellSlot(
                    cell = cell,
                    onEdit = { controller.beginEditing(row.gameId, columnType) },
                )
            } else {
                CellEditorSlot(
                    cell = cell,
                    editor = openHere,
                    composer = state.taskComposer,
                    colors = controller.colorsOffered(),
                    focusRecall = state.focusRecall,
                    controller = controller,
                )
            }
        }
    }
}

@Composable
private fun GameNameCell(
    row: GameTableRow,
    stateText: String,
) {
    Column(
        modifier =
            Modifier
                .width(GameColumnWidth)
                .heightIn(min = 64.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            if (row.isCompleted) {
                Text(
                    text = stringResource(Strings.Table.completedMark),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = row.gameName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.isCompleted) {
            Text(
                text = stateText,
                style = MaterialTheme.typography.labelSmall,
                color = PnpStatus.colors.onCompletedContainer,
            )
        }
    }
}

/**
 * One cell of one row, as it reads when nobody is writing in it.
 *
 * The pieces are laid end to end in the order they sit in the cell, with
 * nothing between them: PLAN 5.5 makes the document the pieces themselves, so a
 * separator added here would be a character the user never typed. A task is
 * painted in the colour it will be made in and carries its count beside it,
 * which is what PLAN 12.5 shows of one.
 *
 * One click takes the keyboard, a double click opens the editor, and Enter or F2
 * open it from the keyboard. A cell holding a task opens nothing and says why —
 * flattening it would cost the task its colours, its stages and its history.
 */
@Composable
private fun CellSlot(
    cell: CellPreview,
    onEdit: () -> Unit,
) {
    val columnName = stringResource(columnNameOf(cell.columnType))
    val content = cellContentOf(cell)
    val editable = cell.editableText != null
    val editLabel = stringResource(Strings.Cell.editAction, columnName)
    val description =
        if (cell.isEmpty) {
            stringResource(Strings.Table.cellEmptyDescription, columnName)
        } else {
            stringResource(Strings.Table.cellDescription, columnName, spokenContentOf(cell))
        }
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .width(CellColumnWidth)
                .heightIn(min = 64.dp)
                .cellBorder(focused)
                .onFocusEvent { focused = it.isFocused }
                .focusable()
                .combinedClickable(
                    enabled = editable,
                    onClickLabel = editLabel,
                    // A single click only takes the focus; the double click is
                    // what opens the editor, so passing over a cell on the way
                    // to another never puts one into it.
                    onClick = {},
                    onDoubleClick = onEdit,
                ).onPreviewKeyEvent { event ->
                    if (!editable || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.F2 -> {
                            onEdit()
                            true
                        }

                        else -> false
                    }
                }.padding(horizontal = 10.dp, vertical = 8.dp)
                .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (cell.isEmpty) {
            Text(
                text = stringResource(Strings.Table.cellEmpty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Long content is cut with an ellipsis rather than allowed to make
            // one row as tall as a screen; the cut itself is what tells the user
            // there is more in the cell than the table is showing.
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = CELL_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            // A note broken into lines is cut at a line ending, where an
            // ellipsis has nowhere to appear, so the cut is said in words
            // instead. Without it a five line cell looks like a three line one.
            if (content.text.lineSequence().count() > CELL_PREVIEW_LINES) {
                Text(
                    text = stringResource(Strings.Table.cellMore),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!editable) {
            // Said in words, in the cell, and on a line of its own: drawn over
            // the content it would make both of them unreadable.
            Text(
                text = stringResource(Strings.Cell.lockedByTasks),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What a cell reads as, with its tasks painted.
 *
 * The text of the cell is exactly its pieces in order, with nothing added
 * between them. What *is* added is around a task and never inside the document:
 * the ground it is painted on comes from the colour the user chose for it, and
 * the count beside it comes from the task's own required quantity. Neither is a
 * character anybody typed, and neither is ever written back — PLAN 12.5 shows
 * `×15` beside a task, and PLAN 5.5 keeps the document to the pieces themselves.
 *
 * The ink is chosen against the ground rather than fixed, because the ground is
 * a colour the user picked for filament and not for legibility; PLAN 17 asks for
 * text that can still be read.
 *
 * A task with no colour is drawn on the theme's own ground rather than on
 * nothing. That is a state PLAN 5.10 allows and this step never creates, but a
 * later colour deletion can put a task into it.
 */
@Composable
private fun cellContentOf(cell: CellPreview): AnnotatedString {
    val metadata = MaterialTheme.colorScheme.onSurfaceVariant
    val colourlessGround = MaterialTheme.colorScheme.surfaceVariant
    val colourlessInk = MaterialTheme.colorScheme.onSurfaceVariant
    val marks =
        cell.segments.map { segment ->
            segment.requiredQuantity
                ?.takeIf { segment.isTask }
                ?.let { stringResource(Strings.CellTask.quantityMark, it) }
        }
    return buildAnnotatedString {
        cell.segments.forEachIndexed { index, segment ->
            if (!segment.isTask) {
                append(segment.text)
                return@forEachIndexed
            }
            // Two tasks with nothing written between them would otherwise run
            // into one another, the count of the first touching the name of the
            // second. This gap sits between two things that are drawn rather
            // than between two pieces of the document, so it says nothing about
            // the text and is never stored.
            if (cell.segments.getOrNull(index - 1)?.isTask == true) {
                withStyle(SpanStyle(color = metadata)) { append(QUANTITY_GAP) }
            }
            val ground = segment.colors.firstOrNull()?.let { opaqueColorOf(it.hex) }
            withStyle(
                SpanStyle(
                    background = ground ?: colourlessGround,
                    color = ground?.let(::readableInkOn) ?: colourlessInk,
                    fontWeight = FontWeight.Medium,
                    // PLAN 5.6 leaves a finished task in its cell, struck through.
                    textDecoration = if (segment.isCompletedTask) TextDecoration.LineThrough else null,
                ),
            ) {
                append(segment.text)
            }
            marks[index]?.let { mark ->
                withStyle(SpanStyle(color = metadata)) {
                    append(QUANTITY_GAP)
                    append(mark)
                }
            }
        }
    }
}

/**
 * What a cell reads as out loud.
 *
 * A colour is never the only thing carrying a meaning — PLAN 17 — so what the
 * eye gets from a painted word and a count, a screen reader gets in words: the
 * task's name, the colours it is made in, and how many are needed. The pieces
 * are joined with nothing between them, exactly as they are drawn, because plain
 * text carries its own punctuation and spacing.
 */
@Composable
private fun spokenContentOf(cell: CellPreview): String {
    // Resolved piece by piece first: `joinToString` takes an ordinary lambda,
    // which is no place to read a text from the catalogue.
    val spoken = cell.segments.map { segment -> if (segment.isTask) spokenTaskOf(segment) else segment.text }
    return spoken.joinToString(separator = "")
}

@Composable
private fun spokenTaskOf(segment: CellSegmentPreview): String {
    val colors =
        if (segment.colors.isEmpty()) {
            stringResource(Strings.CellTask.noColor)
        } else {
            segment.colors.joinToString(separator = ", ") { it.canonicalName }
        }
    val said =
        segment.requiredQuantity?.let { quantity ->
            stringResource(Strings.CellTask.description, segment.text, colors, quantity)
        } ?: stringResource(Strings.CellTask.descriptionUnknownQuantity, segment.text, colors)
    return if (segment.isCompletedTask) said + ", " + stringResource(Strings.CellTask.completed) else said
}

/**
 * The cell being written in, in its own place in the table.
 *
 * Inside the cell's own bounds rather than in a dialog: PLAN 12.5 keeps editing
 * where the content is, and a panel over the middle of the window would hide the
 * row being worked on. Enter puts in a line break because the text is a note and
 * notes have lines; Ctrl+Enter saves and Escape gives up.
 *
 * Selecting words in the field offers to turn them into a task. The offer is
 * only made against text that is already stored: the cut is made in the
 * database, at offsets counted over what is stored, so a draft that has moved
 * ahead of it would point somewhere else entirely. Saving on the user's behalf
 * to close that gap would be an automatic save PLAN does not describe, so they
 * are asked to save first instead.
 *
 * A refused save leaves everything standing — the editor, the words, and a line
 * saying what happened — because the alternative is discarding writing the user
 * has not agreed to lose.
 */
@Composable
private fun CellEditorSlot(
    cell: CellPreview,
    editor: CellEditor,
    composer: TaskComposer?,
    colors: List<ColorSummary>,
    focusRecall: Int,
    controller: GameTableController,
) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // The selection is the field's own, so double clicking a word and dragging
    // across several both work the way they do in any other text box.
    var field by
        remember(editor.gameId, editor.columnType) {
            mutableStateOf(TextFieldValue(editor.draft, TextRange(editor.draft.length)))
        }
    // The keyboard comes back here whenever the panel closes or an action was
    // refused, so Escape reaches this cell rather than whatever was clicked.
    LaunchedEffect(editor.gameId, editor.columnType, focusRecall, composer == null) {
        if (composer == null) focus.requestFocus()
    }
    val columnName = stringResource(columnNameOf(cell.columnType))
    val save = { if (!editor.isSaving) scope.launch { controller.saveEditing() } }
    val saveTask = { if (composer?.canSave == true) scope.launch { controller.saveTask() } }

    Column(
        modifier =
            Modifier
                .width(CellColumnWidth)
                .heightIn(min = 64.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .cellBorder(focused = true)
                .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = field,
            onValueChange = {
                field = it
                controller.editCellText(it.text)
            },
            enabled = !editor.isSaving,
            // Held still while the panel is open: the panel carries offsets into
            // the text as it stands, and a keystroke would move the words out
            // from under the selection the user made.
            readOnly = composer != null,
            // A note has lines, so Enter makes one. Nothing here parses what is
            // typed or pasted: the text is stored as the user left it.
            singleLine = false,
            minLines = 2,
            maxLines = EDITOR_LINES,
            textStyle = MaterialTheme.typography.bodyMedium,
            label = { Text(columnName) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Escape -> {
                                // With the panel open, giving up means giving up
                                // on the task; the cell and its words stay.
                                if (composer != null) controller.cancelTaskComposer() else controller.cancelEditing()
                                true
                            }

                            event.isCtrlPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                                if (composer != null) saveTask() else save()
                                true
                            }

                            else -> false
                        }
                    },
        )

        if (composer == null) {
            CellEditorActions(editor = editor, field = field, controller = controller, onSave = { save() })
        } else {
            TaskComposerPanel(
                composer = composer,
                colors = colors,
                focusRecall = focusRecall,
                controller = controller,
                onSave = { saveTask() },
            )
        }
    }
}

/**
 * The two things a cell editor can do, and the offer to make a task.
 *
 * The offer appears only once there is really something selected. PLAN 12.6 has
 * the user pick a word and then convert it, so an action standing there with
 * nothing chosen would be a button that cannot do what it says.
 */
@Composable
private fun CellEditorActions(
    editor: CellEditor,
    field: TextFieldValue,
    controller: GameTableController,
    onSave: () -> Unit,
) {
    val hasSelection = !field.selection.collapsed
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val saveLabel = stringResource(Strings.Cell.save)
        val discardLabel = stringResource(Strings.Cell.discard)
        Button(
            onClick = onSave,
            enabled = !editor.isSaving,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = saveLabel },
        ) {
            Text(text = saveLabel, style = MaterialTheme.typography.labelMedium)
        }
        TextButton(
            onClick = controller::cancelEditing,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = discardLabel },
        ) {
            Text(text = discardLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (hasSelection && !editor.isSaving) {
        val createLabel = stringResource(Strings.CellTask.create)
        TextButton(
            onClick = { controller.beginTaskComposer(field.selection.min, field.selection.max) },
            enabled = !editor.hasChanges,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = createLabel },
        ) {
            Text(text = createLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
    val note =
        when {
            editor.isSaving -> Strings.Cell.saving
            editor.failure != null -> messageOf(editor.failure)
            editor.selectionFailure != null -> messageOf(editor.selectionFailure)
            hasSelection && editor.hasChanges -> Strings.CellTask.saveTextFirst
            hasSelection -> Strings.CellTask.selectHint
            else -> Strings.Cell.editorHint
        }
    val isProblem = editor.failure != null || editor.selectionFailure != null || (hasSelection && editor.hasChanges)
    NoteLine(text = stringResource(note), isProblem = isProblem)
}

/**
 * Naming the colour, the count and the note for a task made out of words.
 *
 * Compact and inside the cell the words are in — PLAN 12.6 rules out a full
 * screen modal or a panel that covers the window, and the row being worked on
 * has to stay visible while it is worked on.
 *
 * The colour has to be chosen and cannot be typed as a value: PLAN 5.7 keeps
 * every colour a named record in the global catalogue, and this step creates
 * none. Two colours that happen to share a value are two entries, because they
 * are two names.
 */
@Composable
private fun TaskComposerPanel(
    composer: TaskComposer,
    colors: List<ColorSummary>,
    focusRecall: Int,
    controller: GameTableController,
    onSave: () -> Unit,
) {
    val panelFocus = remember { FocusRequester() }
    LaunchedEffect(focusRecall) { panelFocus.requestFocus() }
    val trackingChoices =
        composer.columnType.poolType
            ?.let { trackingModesOf(it) }
            .orEmpty()

    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        // Caught for the whole panel rather than for one field in it: the user
        // may be anywhere in here when they finish. A plain Enter still reaches
        // the field it was typed in, so the note keeps its lines.
        modifier =
            Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Escape -> {
                        controller.cancelTaskComposer()
                        true
                    }

                    event.isCtrlPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        onSave()
                        true
                    }

                    else -> false
                }
            },
    ) {
        // The title and the words being turned into a task share a line: the
        // panel is inside a table cell, and a line spent on a label of its own
        // is a line the row grows by.
        val nameLabel = stringResource(Strings.CellTask.nameLabel)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Strings.CellTask.panelTitle),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = composer.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = "${'$'}nameLabel: ${'$'}{composer.name}" },
            )
        }

        OutlinedTextField(
            value = composer.colorQuery,
            onValueChange = controller::editTaskColorQuery,
            enabled = !composer.isSaving,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text(stringResource(Strings.CellTask.colorSearch)) },
            modifier = Modifier.fillMaxWidth().focusRequester(panelFocus),
        )
        ColorChoices(composer = composer, colors = colors, controller = controller)

        OutlinedTextField(
            value = composer.quantityText,
            onValueChange = controller::editTaskQuantity,
            enabled = !composer.isSaving,
            singleLine = true,
            isError = !composer.isQuantityUsable,
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text(stringResource(Strings.CellTask.quantityLabel)) },
            modifier = Modifier.fillMaxWidth(),
        )
        NoteLine(
            text =
                if (composer.isQuantityUsable) {
                    stringResource(Strings.CellTask.quantityHint)
                } else {
                    stringResource(Strings.CellTask.quantityInvalid)
                },
            isProblem = !composer.isQuantityUsable,
        )

        if (trackingChoices.size > 1) {
            // Asked for only where the pool really leaves a choice. Everywhere
            // else the mode follows from the column and nothing is put to the
            // user that has only one answer.
            Text(
                text = stringResource(Strings.Tasks.trackingLabel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.selectableGroup(),
            ) {
                trackingChoices.forEach { mode ->
                    val chosen = composer.trackingMode == mode
                    val stateText =
                        stringResource(
                            if (chosen) Strings.Accessibility.selected else Strings.Accessibility.notSelected,
                        )
                    FilterChip(
                        selected = chosen,
                        onClick = { controller.chooseTaskTracking(mode) },
                        label = { Text(stringResource(labelOf(mode)), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.focusOutline(ComposerShape).semantics { stateDescription = stateText },
                    )
                }
            }
        }

        OutlinedTextField(
            value = composer.notes,
            onValueChange = controller::editTaskNotes,
            enabled = !composer.isSaving,
            // A note has lines like any other note, and Enter makes one here too.
            singleLine = false,
            minLines = 1,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text(stringResource(Strings.CellTask.notesLabel)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val saveLabel = stringResource(Strings.CellTask.save)
            val discardLabel = stringResource(Strings.CellTask.discard)
            Button(
                onClick = onSave,
                enabled = composer.canSave,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = saveLabel },
            ) {
                Text(text = saveLabel, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
                onClick = controller::cancelTaskComposer,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = discardLabel },
            ) {
                Text(text = discardLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
        val note =
            when {
                composer.isSaving -> stringResource(Strings.CellTask.saving)
                composer.failure != null -> stringResource(messageOf(composer.failure))
                composer.colorId == null -> stringResource(Strings.CellTask.colorRequired)
                else -> stringResource(Strings.CellTask.hint)
            }
        NoteLine(text = note, isProblem = composer.failure != null)
    }
}

/**
 * The catalogue, narrowed by what has been typed, one entry per colour.
 *
 * Every entry carries the colour's written name beside its swatch, because PLAN
 * 17 does not let a colour be the only thing carrying a meaning, and which one
 * is chosen is said in words as well as by the fill.
 */
@Composable
private fun ColorChoices(
    composer: TaskComposer,
    colors: List<ColorSummary>,
    controller: GameTableController,
) {
    if (colors.isEmpty()) {
        NoteLine(
            text =
                stringResource(
                    if (composer.colorQuery.isBlank()) Strings.CellTask.colorEmpty else Strings.CellTask.colorNone,
                ),
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
            val chosen = composer.colorId == color.id
            val stateText =
                stringResource(if (chosen) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = chosen,
                            enabled = !composer.isSaving,
                            onClick = { controller.chooseTaskColor(color.id) },
                        ).background(
                            if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        ).padding(horizontal = 4.dp, vertical = 3.dp)
                        .semantics { stateDescription = stateText },
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(opaqueColorOf(color.hex))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp)),
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
private fun NoteLine(
    text: String,
    isProblem: Boolean,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** What to tell the user about words that did not become a task. */
private fun messageOf(failure: TaskFromTextFailure) =
    when (failure) {
        TaskFromTextFailure.GAME_NOT_AVAILABLE -> Strings.CellTask.errorGameGone
        TaskFromTextFailure.CELL_NOT_AVAILABLE -> Strings.CellTask.errorCellGone
        TaskFromTextFailure.CELL_DOES_NOT_HOLD_TASKS -> Strings.CellTask.errorCellHoldsNoTasks
        TaskFromTextFailure.SEGMENT_NOT_AVAILABLE -> Strings.CellTask.errorSegmentGone
        TaskFromTextFailure.SEGMENT_IS_NOT_PLAIN_TEXT -> Strings.CellTask.errorSegmentNotText
        TaskFromTextFailure.STALE_TEXT_SELECTION -> Strings.CellTask.errorStaleSelection
        TaskFromTextFailure.INVALID_SELECTION -> Strings.CellTask.errorInvalidSelection
        TaskFromTextFailure.SELECTION_CONTAINS_LINE_BREAK -> Strings.CellTask.errorLineBreak
        TaskFromTextFailure.TASK_NAME_EMPTY -> Strings.CellTask.errorNameEmpty
        TaskFromTextFailure.COLOR_NOT_AVAILABLE -> Strings.CellTask.errorColorGone
        TaskFromTextFailure.INVALID_REQUIRED_QUANTITY -> Strings.CellTask.errorQuantity
        TaskFromTextFailure.COULD_NOT_SAVE -> Strings.CellTask.errorCouldNotSave
    }

/** What to tell the user about a cell that did not save. */
private fun messageOf(failure: CellTextFailure) =
    when (failure) {
        CellTextFailure.GAME_NOT_AVAILABLE -> Strings.Cell.errorGameGone
        CellTextFailure.CELL_CONTAINS_TASKS -> Strings.Cell.errorContainsTasks
        CellTextFailure.COULD_NOT_SAVE -> Strings.Cell.errorCouldNotSave
    }

/**
 * The line around a cell.
 *
 * Always drawn, so focusing one does not nudge the row beside it, and darker
 * when the cell holds the keyboard: PLAN 17 wants focus that can be seen, and a
 * dense table wants a grid that can be read.
 */
@Composable
private fun Modifier.cellBorder(focused: Boolean): Modifier =
    border(
        width = if (focused) 2.dp else 1.dp,
        color =
            if (focused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
    )

@Composable
private fun EmptyTable(state: GameTableRowsState.Empty) {
    val (title, hint) =
        when {
            !state.hasGamesInOtherViews -> Strings.Table.emptyLibrary to Strings.Table.emptyLibraryHint
            state.view == GameTableView.COMPLETED ->
                Strings.Table.emptyCompleted to Strings.Table.emptyCompletedHint

            else -> Strings.Table.emptyOngoing to Strings.Table.emptyOngoingHint
        }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FailureLine(failure: GameSetupFailure?) {
    if (failure == null) return
    val text =
        when (failure) {
            GameSetupFailure.COULD_NOT_SAVE -> Strings.Games.errorCouldNotSave
            GameSetupFailure.GAME_NOT_AVAILABLE -> Strings.Games.errorGameUnavailable
        }
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Draws a ring around whatever holds keyboard focus, without moving anything. */
@Composable
private fun Modifier.focusOutline(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusEvent { focused = it.hasFocus }
        .border(
            width = 2.dp,
            color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = shape,
        )
}
