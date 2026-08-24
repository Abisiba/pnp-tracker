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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.games.CellPreview
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.columnNameOf
import dev.pnptracker.ui.theme.PnpStatus
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

private val ComposerShape = RoundedCornerShape(8.dp)

/**
 * The game table: the surface the whole application is worked from.
 *
 * PLAN 12.3 makes a game one row and never a task, and PLAN 12.4 puts three
 * views over the same table rather than three screens. Both are visible here:
 * one list of rows, and a filter above it that reads nothing new when it changes.
 *
 * Cells are previewed and not edited. Writing in them is the next step, so
 * nothing here is clickable that would not do anything — an inert cell that
 * looked interactive would promise an editor that is not there yet.
 */
@Composable
fun GameTableScreen(controller: GameTableController) {
    LaunchedEffect(controller) { controller.observeTable() }

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
            is GameTableRowsState.Content -> Table(rows.rows, controller, state.editor)
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
    editor: CellEditor?,
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
                    TableRow(row = row, controller = controller, editor = editor)
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
    editor: CellEditor?,
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
            val openHere = editor?.takeIf { it.isOn(row.gameId, columnType) }
            if (openHere == null) {
                CellSlot(
                    cell = cell,
                    onEdit = { controller.beginEditing(row.gameId, columnType) },
                )
            } else {
                CellEditorSlot(cell = cell, editor = openHere, controller = controller)
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
 * separator added here would be a character the user never typed. A task reads
 * as its name, which is what PLAN 12.5 shows of one.
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
    val content = cell.text
    val editable = cell.editableText != null
    val editLabel = stringResource(Strings.Cell.editAction, columnName)
    val description =
        if (cell.isEmpty) {
            stringResource(Strings.Table.cellEmptyDescription, columnName)
        } else {
            stringResource(Strings.Table.cellDescription, columnName, content)
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
            if (content.lineSequence().count() > CELL_PREVIEW_LINES) {
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
 * The cell being written in, in its own place in the table.
 *
 * Inside the cell's own bounds rather than in a dialog: PLAN 12.5 keeps editing
 * where the content is, and a panel over the middle of the window would hide the
 * row being worked on. Enter puts in a line break because the text is a note and
 * notes have lines; Ctrl+Enter saves and Escape gives up.
 *
 * A refused save leaves everything standing — the editor, the words, and a line
 * saying what happened — because the alternative is discarding writing the user
 * has not agreed to lose.
 */
@Composable
private fun CellEditorSlot(
    cell: CellPreview,
    editor: CellEditor,
    controller: GameTableController,
) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(editor.gameId, editor.columnType) { focus.requestFocus() }
    val columnName = stringResource(columnNameOf(cell.columnType))
    val save = { if (!editor.isSaving) scope.launch { controller.saveEditing() } }

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
            value = editor.draft,
            onValueChange = controller::editCellText,
            enabled = !editor.isSaving,
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
                                controller.cancelEditing()
                                true
                            }

                            event.isCtrlPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                                save()
                                true
                            }

                            else -> false
                        }
                    },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val saveLabel = stringResource(Strings.Cell.save)
            val discardLabel = stringResource(Strings.Cell.discard)
            Button(
                onClick = { save() },
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
        val note =
            when {
                editor.isSaving -> Strings.Cell.saving
                editor.failure != null -> messageOf(editor.failure)
                else -> Strings.Cell.editorHint
            }
        Text(
            text = stringResource(note),
            style = MaterialTheme.typography.labelSmall,
            color =
                if (editor.failure != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
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
