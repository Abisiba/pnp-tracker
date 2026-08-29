package dev.pnptracker.ui.feature.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.games.CellPreview
import dev.pnptracker.domain.games.CellSegmentPreview
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskEditFailure
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import dev.pnptracker.domain.tasks.taskColorLayoutOf
import dev.pnptracker.domain.tasks.trackingModesOf
import dev.pnptracker.domain.text.graphemeBoundariesOf
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.columnNameOf
import dev.pnptracker.ui.feature.importworkspace.labelOf
import dev.pnptracker.ui.theme.PnpStatus
import dev.pnptracker.ui.theme.opaqueColorOf
import dev.pnptracker.ui.theme.readableInkOn
import dev.pnptracker.ui.theme.visibleEdgeOn
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

/**
 * The swatch drawn for a colour the task's name was too short to reach.
 *
 * Two hard spaces filled with the colour rather than a glyph: it has to be a
 * flat block of exactly that colour whatever font the machine has, and a
 * character that could be missing would leave a colour the user chose showing
 * nothing at all. Hard, so a swatch is never split across two lines.
 */
private const val MARKER_MARK = "\u00A0\u00A0"

/**
 * The gap before each swatch.
 *
 * An ordinary space, and breakable on purpose: a task in more colours than its
 * name is long has to be able to wrap inside a narrow cell, and PLAN 12.7 will
 * not have any of its colours hidden, folded into a `+3` or quietly cut off.
 */
private const val MARKER_GAP = " "

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
            val writing = state.writingIn(row.gameId, columnType)
            if (writing == null) {
                CellSlot(
                    cell = cell,
                    gameId = row.gameId,
                    state = state,
                    controller = controller,
                    onEdit = { controller.beginEditing(row.gameId, columnType) },
                )
            } else {
                CellEditorSlot(
                    cell = cell,
                    editor = writing,
                    composer = (state.work as? CellWork.MakingTask)?.composer,
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
 * How a task is drawn wherever it appears: its own colour, with an edge.
 *
 * The edge is what makes the colour visible at all when it happens to match the
 * theme's ground — white on a light surface, black on a dark one. Without it
 * such a task shows no colour, so the one thing meant to tell the user which
 * filament it is tells them nothing. PLAN 12.7 asks for an automatic contrast
 * frame in exactly this case; drawing one is honest where repainting the colour
 * would not be.
 */
private data class TaskPaint(
    val fill: Color,
    val ink: Color,
    val edge: Color,
)

/**
 * How each of a task's colours is painted, in the order the user chose them.
 *
 * One entry per colour, so a task made in several is several paints and never an
 * average of them — PLAN 12.7 forbids a gradient and wants each part drawn in
 * its own flat colour. A task with no colour at all gets the single fallback
 * paint, which is the theme's own ground: PLAN 5.10 allows that state, and it
 * still has to read as a task rather than as plain words.
 */
@Composable
private fun paintsOf(segment: CellSegmentPreview): List<TaskPaint> =
    if (segment.colors.isEmpty()) {
        listOf(
            TaskPaint(
                fill = MaterialTheme.colorScheme.surfaceVariant,
                ink = MaterialTheme.colorScheme.onSurfaceVariant,
                edge = MaterialTheme.colorScheme.outline,
            ),
        )
    } else {
        segment.colors.map { color ->
            val fill = opaqueColorOf(color.hex)
            TaskPaint(fill = fill, ink = readableInkOn(fill), edge = visibleEdgeOn(fill))
        }
    }

/** One stretch of the drawn string painted in one of a task's colours. */
private data class DrawnStripe(
    val start: Int,
    val end: Int,
    val edge: Color,
)

/**
 * Where one task sits in the string that is actually drawn.
 *
 * [start] and [end] cover the whole of it — the name and any swatches drawn for
 * colours the name was too short to reach — because all of that is one task and
 * one thing to press (PLAN 12.7). [stripes] are the coloured pieces inside it,
 * each needing its own contrast edge, which is what makes a white piece visible
 * on a light ground and a black one on a dark ground.
 */
private data class DrawnTask(
    val segment: CellSegmentPreview,
    val start: Int,
    val end: Int,
    val stripes: List<DrawnStripe>,
)

/** The document as it is drawn, and where each task's word ended up in it. */
private data class DrawnDocument(
    val text: AnnotatedString,
    val tasks: List<DrawnTask>,
)

/**
 * Lays out a cell's document for drawing.
 *
 * The characters are the document's own, in order, with nothing between them:
 * PLAN 5.5 makes the document the pieces themselves, so a separator invented
 * here would be a character the user never typed. What is added is only ever
 * around a task and never inside it — the count beside it, and the space before
 * that count — and [withCounts] is off wherever the drawn string has to line up
 * with the document character for character, as it does in an editor.
 */
@Composable
private fun drawnDocumentOf(
    cell: CellPreview,
    withCounts: Boolean,
): DrawnDocument {
    val metadata = MaterialTheme.colorScheme.onSurfaceVariant
    val paints = cell.segments.map { if (it.isTask) paintsOf(it) else null }
    val marks =
        cell.segments.map { segment ->
            segment.requiredQuantity
                ?.takeIf { segment.isTask && withCounts }
                ?.let { stringResource(Strings.CellTask.quantityMark, it) }
        }
    // Worked out once per document rather than on every recomposition: finding
    // where the user's own characters begin is real work, and the answer only
    // changes when a name or a colour list does.
    val layouts =
        remember(cell.segments) {
            cell.segments.map { segment ->
                if (segment.isTask) taskColorLayoutOf(segment.text, segment.colors.size) else null
            }
        }
    val tasks = mutableListOf<DrawnTask>()
    val text =
        buildAnnotatedString {
            cell.segments.forEachIndexed { index, segment ->
                if (!segment.isTask) {
                    append(segment.text)
                    return@forEachIndexed
                }
                // Two tasks with nothing written between them would otherwise
                // run into one another. This gap sits between two things that
                // are drawn, never between two pieces of the document, so it
                // says nothing about the text — and it is left out entirely
                // where the drawn string has to match the document exactly.
                if (withCounts && cell.segments.getOrNull(index - 1)?.isTask == true) {
                    withStyle(SpanStyle(color = metadata)) { append(QUANTITY_GAP) }
                }
                val taskPaints = requireNotNull(paints[index])
                val layout = requireNotNull(layouts[index])
                val stripes = mutableListOf<DrawnStripe>()
                val start = length
                // The name in the colours it is made in, in slot order. One
                // colour is one piece covering the whole name, which is every
                // task in the cell until somebody makes one of several.
                if (layout.slices.isEmpty()) {
                    stripes += paintedName(segment, segment.text, taskPaints.first())
                } else {
                    layout.slices.forEach { slice ->
                        val paint = taskPaints[slice.slotIndex]
                        stripes += paintedName(segment, segment.text.substring(slice.start, slice.end), paint)
                    }
                }
                // A colour the name was too short to reach is drawn as its own
                // swatch, right after the name and before the count. PLAN 12.7
                // will not have a colour the user chose go unseen, and the
                // swatch belongs to this task: it is inside the pressable run,
                // takes no focus of its own and says nothing of its own.
                //
                // Left out where the drawn string has to line up with the
                // document character for character, as it does in an editor —
                // there the name is still painted in every colour that reaches
                // it, and nothing is added around it.
                if (withCounts) {
                    layout.markerSlots.forEach { slot ->
                        // An ordinary space, so a long row of swatches can wrap
                        // inside the task rather than running off the cell.
                        append(MARKER_GAP)
                        val paint = taskPaints[slot]
                        val at = length
                        withStyle(SpanStyle(background = paint.fill, color = paint.ink)) { append(MARKER_MARK) }
                        stripes += DrawnStripe(start = at, end = length, edge = paint.edge)
                    }
                }
                tasks += DrawnTask(segment = segment, start = start, end = length, stripes = stripes)
                marks[index]?.let { mark ->
                    withStyle(SpanStyle(color = metadata)) {
                        append(QUANTITY_GAP)
                        append(mark)
                    }
                }
            }
        }
    return DrawnDocument(text = text, tasks = tasks)
}

/** Writes one piece of a task's name in one colour, and says where it landed. */
private fun AnnotatedString.Builder.paintedName(
    segment: CellSegmentPreview,
    part: String,
    paint: TaskPaint,
): DrawnStripe {
    val at = length
    withStyle(
        SpanStyle(
            background = paint.fill,
            color = paint.ink,
            fontWeight = FontWeight.Medium,
            // PLAN 5.6 leaves a finished task in its cell, struck through.
            textDecoration = if (segment.isCompletedTask) TextDecoration.LineThrough else null,
        ),
    ) {
        append(part)
    }
    return DrawnStripe(start = at, end = length, edge = paint.edge)
}

/**
 * Draws the contrast edge around every coloured piece the layout actually placed.
 *
 * Per piece rather than per task, because a task made in several colours is
 * several colours: each needs the edge that makes it visible on the ground it
 * happens to match, and one rectangle around the lot would put a single colour's
 * edge around all of them. A piece whose name wrapped is edged on each line it
 * reaches, for the same reason — the half on the second line needs one as much
 * as the first does. PLAN 12.7 and 17 both ask for exactly this frame.
 */
private fun DrawScope.drawTaskEdges(
    layout: TextLayoutResult,
    tasks: List<DrawnTask>,
    corner: Float,
    stroke: Float,
) {
    tasks.forEach { task ->
        task.stripes.forEach { stripe ->
            layout.boxesOfRange(stripe.start, stripe.end).forEach { box ->
                drawRoundRect(
                    color = stripe.edge,
                    topLeft = Offset(box.left, box.top),
                    size = Size(box.width, box.height),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}

/**
 * One cell of one row, as it reads when nobody is writing in it.
 *
 * The pieces are laid end to end in the order they sit in the cell, with nothing
 * between them. A task is painted in the colour it will be made in, edged so the
 * colour is visible whatever it is, and carries its count beside it — which is
 * what PLAN 12.5 shows of one.
 *
 * A task is a thing to press: it takes the pointer's hand cursor, it takes the
 * keyboard, and Enter or Space opens its menu over the word. The plain text
 * around it is not — a double click there opens the editor, and Enter or F2 open
 * it from the keyboard.
 */
@Composable
private fun CellSlot(
    cell: CellPreview,
    gameId: EntityId,
    state: GameTableScreenState,
    controller: GameTableController,
    onEdit: () -> Unit,
) {
    val columnName = stringResource(columnNameOf(cell.columnType))
    val drawn = drawnDocumentOf(cell, withCounts = true)
    val editLabel = stringResource(Strings.Cell.editAction, columnName)
    val description =
        if (cell.isEmpty) {
            stringResource(Strings.Table.cellEmptyDescription, columnName)
        } else {
            stringResource(Strings.Table.cellDescription, columnName, spokenContentOf(cell))
        }
    var focused by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val edgeCorner = with(LocalDensity.current) { 3.dp.toPx() }
    val edgeStroke = with(LocalDensity.current) { 1.dp.toPx() }

    Column(
        modifier =
            Modifier
                .width(CellColumnWidth)
                .heightIn(min = 64.dp)
                .cellBorder(focused)
                .onFocusEvent { focused = it.isFocused }
                .focusable()
                .combinedClickable(
                    onClickLabel = editLabel,
                    // A single click only takes the focus; the double click is
                    // what opens the editor, so passing over a cell on the way
                    // to another never puts one into it.
                    onClick = {},
                    onDoubleClick = onEdit,
                ).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
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
            Box {
                Text(
                    text = drawn.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = CELL_PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout = it },
                    modifier =
                        Modifier.drawBehind {
                            layout?.let { drawTaskEdges(it, drawn.tasks, edgeCorner, edgeStroke) }
                        },
                )
                // One handle per task, sitting exactly where its word was laid
                // out. The bounds are the layout's own, so a handle follows its
                // word when the column scrolls, the window resizes or the text
                // around it changes — nothing here remembers a coordinate.
                layout?.let { placed ->
                    drawn.tasks.forEach { task ->
                        val boxes = placed.boxesOfRange(task.start, task.end)
                        if (boxes.isNotEmpty()) {
                            TaskHandle(
                                task = task.segment,
                                boxes = boxes,
                                gameId = gameId,
                                columnType = cell.columnType,
                                state = state,
                                controller = controller,
                            )
                        }
                    }
                }
            }
            // A note broken into lines is cut at a line ending, where an
            // ellipsis has nowhere to appear, so the cut is said in words
            // instead. Without it a five line cell looks like a three line one.
            if (drawn.text.text
                    .lineSequence()
                    .count() > CELL_PREVIEW_LINES
            ) {
                Text(
                    text = stringResource(Strings.Table.cellMore),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The part of a cell that is one task: what it responds to, and what opens on it.
 *
 * Laid over the word rather than replacing it, so the text keeps flowing and
 * wrapping as text and the row does not grow. What it adds is everything a task
 * needs to be a control — the hand cursor, a focus ring, Enter and Space, an
 * accessible name and role — and the popover, which hangs off this and therefore
 * off the word's own position.
 */
@Composable
private fun TaskHandle(
    task: CellSegmentPreview,
    boxes: List<Rect>,
    gameId: EntityId,
    columnType: CellColumnType,
    state: GameTableScreenState,
    controller: GameTableController,
) {
    val taskId = task.taskId ?: return
    val density = LocalDensity.current
    val menu = state.menuIn(gameId, columnType)?.takeIf { it.taskId == taskId }
    val spoken = spokenTaskOf(task)
    val openLabel = stringResource(Strings.TaskMenu.open, task.text)
    var focused by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    // Both rings are the theme's own accent rather than something derived from
    // the task: it has to stand out from the colour underneath, and that colour
    // is anything at all — including the outline colour itself.
    val focusRing = MaterialTheme.colorScheme.primary
    val hoverRing = MaterialTheme.colorScheme.primary

    // A wrapped name is several boxes and one control. They share the hover and
    // the focus, so pressing either half does the same thing and lighting up is
    // about the task rather than about the piece of it under the pointer. Only
    // the first takes the keyboard and carries the name, so Tab stops at a task
    // once and a reader is told about it once; only the first hangs the popover,
    // because PLAN 12.5 opens it above the word and the word starts there.
    boxes.forEachIndexed { index, bounds ->
        val leading = index == 0
        Box(
            modifier =
                with(density) {
                    Modifier
                        .offset { IntOffset(bounds.left.toInt(), bounds.top.toInt()) }
                        .size(bounds.width.toDp(), bounds.height.toDp())
                }.hoverable(interactions)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .then(
                        if (leading) {
                            Modifier
                                .onFocusEvent { focused = it.isFocused }
                                .focusable()
                        } else {
                            Modifier
                        },
                    ).clickable(onClickLabel = openLabel) { controller.openTaskMenu(gameId, columnType, taskId) }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                                controller.openTaskMenu(gameId, columnType, taskId)
                                true
                            }

                            else -> false
                        }
                    }.drawBehind {
                        // A ring rather than a wash: the colour underneath is the
                        // information, and covering it to say "you are over this"
                        // would trade the answer for the pointer.
                        if (focused || hovered) {
                            drawRoundRect(
                                color = if (focused) focusRing else hoverRing,
                                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                style = Stroke(width = if (focused) 2.dp.toPx() else 1.5.dp.toPx()),
                            )
                        }
                    }.then(
                        if (leading) {
                            Modifier.semantics {
                                contentDescription = spoken
                                role = Role.Button
                            }
                        } else {
                            Modifier.clearAndSetSemantics { }
                        },
                    ),
        ) {
            if (leading && menu != null) {
                TaskPopover(menu = menu, state = state, controller = controller)
            }
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
 * The tasks are in the text and are not the user's to type over. Every change is
 * planned before it is taken: a backspace at the edge of a task, a selection
 * that swallowed one, a paste over the top of one are all refused, and the draft
 * is left exactly as it was — so a task never appears to be eaten and put back.
 * The refusal is said in words rather than by nothing happening.
 *
 * A refused save leaves everything standing — the editor, the words, and a line
 * saying what happened — because the alternative is discarding writing the user
 * has not agreed to lose.
 */
@Composable
private fun CellEditorSlot(
    cell: CellPreview,
    editor: CellWork.WritingText,
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
    // A refused keystroke never reaches the draft, so the field is put back to
    // what the draft still says rather than being left showing a change that
    // was not taken.
    if (field.text != editor.draft) {
        field = field.copy(text = editor.draft, selection = TextRange(editor.draft.length.coerceAtMost(field.selection.end)))
    }
    // The keyboard comes back here whenever a panel closes or an action was
    // refused, so Escape reaches this cell rather than whatever was clicked.
    LaunchedEffect(editor.gameId, editor.columnType, focusRecall, composer == null) {
        if (composer == null) focus.requestFocus()
    }
    val columnName = stringResource(columnNameOf(cell.columnType))
    val save = { if (!editor.isSaving) scope.launch { controller.saveEditing() } }
    val saveTask = { if (composer?.canSave == true) scope.launch { controller.saveTask() } }
    val drawn = drawnDocumentOf(cell, withCounts = false)
    val painted = remember(drawn.text) { TaskPainting(drawn.text) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val edgeCorner = with(LocalDensity.current) { 3.dp.toPx() }
    val edgeStroke = with(LocalDensity.current) { 1.dp.toPx() }

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
        Text(
            text = columnName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A basic field rather than an outlined one, because this is the only
        // kind that reports where it laid its text out — and that is what the
        // edges around the tasks, and everything anchored to a task, are drawn
        // from. The frame it would have brought is drawn here instead.
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                controller.editCellText(it.text)
            },
            enabled = !editor.isSaving,
            // Held still while a task panel is open: the panel carries offsets
            // into the text as it stands, and a keystroke would move the words
            // out from under the selection the user made.
            readOnly = composer != null,
            // A note has lines, so Enter makes one. Nothing here parses what is
            // typed or pasted: the text is stored as the user left it.
            singleLine = false,
            minLines = 2,
            maxLines = EDITOR_LINES,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            // The tasks keep their colours while the text around them is typed,
            // so the user can see what they may not touch. The transformation
            // adds no characters, so every offset still means what it meant.
            visualTransformation = painted,
            onTextLayout = { layout = it },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, ComposerShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, ComposerShape)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .focusRequester(focus)
                    .drawBehind {
                        layout?.let { drawTaskEdges(it, drawn.tasks, edgeCorner, edgeStroke) }
                    }.onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Escape -> {
                                // With a panel open, giving up means giving up on
                                // the task; the cell and its words stay.
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
                catalogue = colors,
                focusRecall = focusRecall,
                controller = controller,
                onSave = { saveTask() },
            )
        }
    }
}

/**
 * Keeps the tasks painted inside a text field without changing a character.
 *
 * The styles are the ones the document was drawn with and the offsets are left
 * alone, so the caret, the selection and every offset the editor reasons about
 * still mean exactly what they meant. A transformation that added or removed
 * characters would put all three quietly out of step.
 */
private class TaskPainting(
    private val painted: AnnotatedString,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        if (text.text == painted.text) {
            TransformedText(painted, OffsetMapping.Identity)
        } else {
            // Mid-keystroke the field can be a character ahead of the document
            // the styles were built from. Plain text for one frame is right;
            // painting at stale offsets would colour the wrong letters.
            TransformedText(text, OffsetMapping.Identity)
        }
}

/**
 * The two things a cell editor can do, and the offer to make a task.
 *
 * The offer appears only once there is really something selected, and only when
 * that selection is a stretch of plain text: PLAN 12.6 has the user pick a word
 * and convert it, and a selection that swallowed a task is not a name.
 */
@Composable
private fun CellEditorActions(
    editor: CellWork.WritingText,
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
            enabled = !editor.hasUnsavedChanges,
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
            editor.refusal != null -> messageOf(editor.refusal)
            editor.selectionFailure != null -> messageOf(editor.selectionFailure)
            hasSelection && editor.hasUnsavedChanges -> Strings.CellTask.saveTextFirst
            hasSelection -> Strings.CellTask.selectHint
            else -> Strings.Cell.editorHint
        }
    val isProblem =
        editor.failure != null ||
            editor.refusal != null ||
            editor.selectionFailure != null ||
            (hasSelection && editor.hasUnsavedChanges)
    NoteLine(text = stringResource(note), isProblem = isProblem)
}

/** How far a popover sits from the word it belongs to. */
private val PopoverGap = 4.dp

/** How wide a popover is allowed to be; narrow enough to stay a popover. */
private val PopoverWidth = 260.dp

/**
 * The small panel over one task's word.
 *
 * PLAN 12.5 opens it immediately above the word and PLAN 17 will not have it
 * cover the screen. It hangs off the handle laid over the word, so its anchor is
 * the word's real position: scrolling the table sideways or down moves both
 * together, and there is no remembered coordinate to go stale.
 *
 * What is inside it depends on how far in the user has gone — the menu, the
 * panel that changes the task, or the question that has to be answered before a
 * task becomes text again. Escape closes one of those at a time.
 */
@Composable
private fun TaskPopover(
    menu: CellWork.TaskMenu,
    state: GameTableScreenState,
    controller: GameTableController,
) {
    val gap = with(LocalDensity.current) { PopoverGap.roundToPx() }
    val provider = remember(gap) { AnchoredAboveWord(gap) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.work, state.focusRecall) { focus.requestFocus() }
    // Caught for the whole popover rather than for one field in it: the user may
    // be anywhere inside when they finish, and handling it deeper left Ctrl+Enter
    // dead in exactly the field they end up in.
    val confirm = {
        when (val work = state.work) {
            is CellWork.EditingTask -> if (work.editor.canSave) scope.launch { controller.saveTaskEdit() } else Unit
            is CellWork.ConfirmingConvert -> if (!work.isSaving) scope.launch { controller.confirmConvertToText() } else Unit
            else -> Unit
        }
    }

    Popup(
        popupPositionProvider = provider,
        onDismissRequest = {
            // Clicking away closes the menu, which holds nothing; it will not
            // close a panel with something typed in it.
            if (state.work?.hasUnsavedChanges != true) controller.closeInnermost()
        },
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = ComposerShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier =
                Modifier
                    .widthIn(max = PopoverWidth)
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Escape -> {
                                controller.closeInnermost()
                                true
                            }

                            event.isCtrlPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                                confirm()
                                true
                            }

                            else -> false
                        }
                    },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (val work = state.work) {
                    is CellWork.EditingTask ->
                        TaskEditPanel(
                            editor = work.editor,
                            colors = controller.colorsOffered(),
                            catalogue = state.colors,
                            controller = controller,
                        )
                    is CellWork.ConfirmingConvert -> ConvertConfirmation(work, controller)
                    else -> TaskMenuActions(menu, controller)
                }
            }
        }
    }
}

/**
 * What can be done to a task, in the menu over its own word.
 *
 * Only the actions this step really has. PLAN 12.5 also lists `Eksik parça`, and
 * PLAN 18 gives shortages to a later slice — so it is absent rather than present
 * and dead. A button that looks like it works and does not is worse than one
 * that is not there yet.
 */
@Composable
private fun TaskMenuActions(
    menu: CellWork.TaskMenu,
    controller: GameTableController,
) {
    Text(
        text = menu.name,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    val edit = stringResource(Strings.TaskMenu.edit)
    val convert = stringResource(Strings.TaskMenu.convertToText)
    TextButton(
        onClick = controller::beginTaskEdit,
        modifier = Modifier.fillMaxWidth().focusOutline(ComposerShape).semantics { contentDescription = edit },
    ) {
        Text(text = edit, style = MaterialTheme.typography.labelMedium)
    }
    TextButton(
        onClick = controller::beginConvertToText,
        modifier = Modifier.fillMaxWidth().focusOutline(ComposerShape).semantics { contentDescription = convert },
    ) {
        Text(text = convert, style = MaterialTheme.typography.labelMedium)
    }
    NoteLine(text = stringResource(Strings.TaskMenu.hint), isProblem = false)
}

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
private fun TaskEditPanel(
    editor: TaskEditor,
    colors: List<ColorSummary>,
    catalogue: List<ColorSummary>,
    controller: GameTableController,
) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(editor.taskId) { focus.requestFocus() }
    val save = { if (editor.canSave) scope.launch { controller.saveTaskEdit() } }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(text = stringResource(Strings.TaskEdit.title), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = editor.name,
            onValueChange = controller::editTaskName,
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
            onValueChange = controller::editTaskEditColorQuery,
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
            onChoose = controller::chooseTaskEditColor,
        )
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
                onMoveUp = controller::moveTaskEditColorUp,
                onMoveDown = controller::moveTaskEditColorDown,
                onDrop = controller::chooseTaskEditColor,
            )
        }

        OutlinedTextField(
            value = editor.quantityText,
            onValueChange = controller::editTaskEditQuantity,
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
                onChoose = controller::chooseTaskEditTracking,
            )
        }

        OutlinedTextField(
            value = editor.notes,
            onValueChange = controller::editTaskEditNotes,
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
                enabled = editor.canSave,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = saveLabel },
            ) {
                Text(text = saveLabel, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
                onClick = controller::closeInnermost,
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
                else -> stringResource(Strings.TaskEdit.hint)
            }
        NoteLine(text = note, isProblem = editor.failure != null)
    }
}

/**
 * Asking whether a task really should go back to being words.
 *
 * PLAN 12.8 keeps the word exactly where it is and takes everything else away,
 * and PLAN 17 asks for that to be confirmed. So the question says all three
 * things it costs — the colour and total stop being tracked, the history goes,
 * and none of it comes back — and says the history part only when there is a
 * history to lose.
 */
@Composable
private fun ConvertConfirmation(
    work: CellWork.ConfirmingConvert,
    controller: GameTableController,
) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(work.taskId) { focus.requestFocus() }

    Text(text = stringResource(Strings.TaskConvert.title), style = MaterialTheme.typography.labelLarge)
    Text(
        text = stringResource(Strings.TaskConvert.body, work.name),
        style = MaterialTheme.typography.bodySmall,
    )
    if (work.hasProgress) {
        NoteLine(text = stringResource(Strings.TaskConvert.historyWarning), isProblem = true)
    }
    NoteLine(text = stringResource(Strings.TaskConvert.irreversible), isProblem = false)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val acceptLabel = stringResource(Strings.TaskConvert.accept)
        val cancelLabel = stringResource(Strings.TaskConvert.cancel)
        Button(
            onClick = { if (!work.isSaving) scope.launch { controller.confirmConvertToText() } },
            enabled = !work.isSaving,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier =
                Modifier
                    .focusRequester(focus)
                    .focusOutline(ComposerShape)
                    .semantics { contentDescription = acceptLabel },
        ) {
            Text(text = acceptLabel, style = MaterialTheme.typography.labelMedium)
        }
        TextButton(
            onClick = controller::closeInnermost,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = cancelLabel },
        ) {
            Text(text = cancelLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
    work.failure?.let { NoteLine(text = stringResource(messageOf(it)), isProblem = true) }
}

/** The tracking modes this task's pool allows, in the order it lists them. */
@Composable
private fun trackingChoicesFor(editor: TaskEditor): List<TrackingMode> =
    remember(editor.trackingMode) {
        PoolType.entries
            .firstOrNull { editor.trackingMode in trackingModesOf(it) }
            ?.let { trackingModesOf(it) }
            .orEmpty()
    }

@Composable
private fun TrackingChoice(
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
 * What a cell reads as out loud.
 *
 * A colour is never the only thing carrying a meaning — PLAN 17 — so what the
 * eye gets from a painted word and a count, a screen reader gets in words: the
 * task's name, how many are needed, and every colour it is made in, in that
 * order and each said once. The pieces are joined with nothing between them,
 * exactly as they are drawn, because plain text carries its own punctuation and
 * spacing.
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
    // Every colour of the task, once each, in the order its slots put them —
    // the same order the name is drawn split across, so what is heard and what
    // is seen agree. A colour with no piece of the name is in here like any
    // other: PLAN 17 will not have the swatch beside the word be the only place
    // it exists.
    val colors =
        if (segment.colors.isEmpty()) {
            stringResource(Strings.CellTask.noColor)
        } else {
            segment.colors.joinToString(separator = ", ") { it.canonicalName }
        }
    val said =
        segment.requiredQuantity?.let { quantity ->
            stringResource(Strings.CellTask.description, segment.text, quantity, colors)
        } ?: stringResource(Strings.CellTask.descriptionUnknownQuantity, segment.text, colors)
    return if (segment.isCompletedTask) said + ", " + stringResource(Strings.CellTask.completed) else said
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
    catalogue: List<ColorSummary>,
    focusRecall: Int,
    controller: GameTableController,
    onSave: () -> Unit,
) {
    val panelFocus = remember { FocusRequester() }
    LaunchedEffect(focusRecall) { panelFocus.requestFocus() }
    // Where the keyboard goes when a save is refused: the first row that is not
    // ready, so the user is put in front of the thing to fix rather than at the
    // top of a panel they have to search.
    val landing = composer.firstUnusableRow ?: 0

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
        // The title and the words being turned into tasks share a line: the
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

        CreationModeChoice(composer = composer, controller = controller)

        if (composer.mode == TaskCreationMode.SINGLE_ITEM_MULTICOLOR) {
            MulticolorFields(
                composer = composer,
                colors = controller.colorsOffered(),
                catalogue = catalogue,
                focus = panelFocus,
                controller = controller,
            )
        } else if (composer.mode == TaskCreationMode.SINGLE_COLOR) {
            // One task: no numbering, no remove button, nothing about a list.
            // The mode is a form convenience, and a form for one thing should
            // not look like a form for several.
            TaskRowFields(
                row = 0,
                draft = composer.single,
                composer = composer,
                colors = controller.colorsOffered(0),
                isRepeatedColor = false,
                focus = panelFocus.takeIf { landing == 0 },
                controller = controller,
            )
        } else {
            NoteLine(text = stringResource(Strings.CellTask.modeManyHint), isProblem = false)
            composer.rows.forEachIndexed { index, draft ->
                BatchTaskRow(
                    row = index,
                    draft = draft,
                    composer = composer,
                    colors = controller.colorsOffered(index),
                    focus = panelFocus.takeIf { landing == index },
                    controller = controller,
                )
            }
            val addLabel = stringResource(Strings.CellTask.rowAdd)
            TextButton(
                onClick = controller::addTaskRow,
                enabled = !composer.isSaving,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = addLabel },
            ) {
                Text(text = addLabel, style = MaterialTheme.typography.labelMedium)
            }
            if (!composer.canRemoveRow) {
                // Said once, where the rows end, rather than under each of them:
                // it is a fact about the batch, and repeating it on every row
                // made a two row panel say it twice.
                NoteLine(text = stringResource(Strings.CellTask.rowFloor), isProblem = false)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val saveLabel =
                if (composer.taskCount == 1) {
                    stringResource(Strings.CellTask.save)
                } else {
                    stringResource(Strings.CellTask.saveMany, composer.taskCount)
                }
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
                composer.isSaving && composer.taskCount > 1 -> stringResource(Strings.CellTask.savingMany)
                composer.isSaving -> stringResource(Strings.CellTask.saving)
                composer.failure != null ->
                    sentenceOf(composer.failure, composer.failureRow, composer.failureConflictsWith)

                composer.repeatedColorRows.isNotEmpty() ->
                    composer.repeatedColorRows.entries.minByOrNull { it.key }!!.let { (later, earlier) ->
                        stringResource(Strings.CellTask.errorDuplicateColor, earlier + 1, later + 1)
                    }
                composer.usedRows.any { it.colorId == null } -> stringResource(Strings.CellTask.colorRequired)
                else -> stringResource(Strings.CellTask.hint)
            }
        NoteLine(
            text = note,
            isProblem = composer.failure != null || composer.repeatedColorRows.isNotEmpty(),
        )
    }
}

/**
 * Choosing what is being made: one task, several, or one made in several colours.
 *
 * All three of PLAN 12.7's modes, and all three really save. They are a
 * convenience of the form and nothing else (PLAN 12.6) — nothing about which one
 * was open is stored, and what tells the resulting tasks apart afterwards is
 * what they are, not how they were typed.
 *
 * Switching loses nothing in any direction. The rows and the several-colour
 * draft are held separately and left where they are, so a user who looks at
 * another mode and comes back finds their answers exactly as they left them,
 * and neither draft can overwrite the other. That is why this needs no warning
 * and asks no question.
 */
@Composable
private fun CreationModeChoice(
    composer: TaskComposer,
    controller: GameTableController,
) {
    Text(
        text = stringResource(Strings.CellTask.modeLabel),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Wrapped rather than laid out in one line: the panel is as wide as one
    // column of the table, and three names do not fit across it. In a plain row
    // the third chip was pushed off the edge — unreachable — and its label wrapped
    // to one word per line, which stretched the whole row's height.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        listOf(
            TaskCreationMode.SINGLE_COLOR to Strings.CellTask.modeSingle,
            TaskCreationMode.INDEPENDENT_TASKS to Strings.CellTask.modeMany,
            TaskCreationMode.SINGLE_ITEM_MULTICOLOR to Strings.CellTask.modeMulticolor,
        ).forEach { (mode, label) ->
            val chosen = composer.mode == mode
            val stateText =
                stringResource(if (chosen) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
            FilterChip(
                selected = chosen,
                enabled = !composer.isSaving,
                onClick = { controller.chooseCreationMode(mode) },
                label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.focusOutline(ComposerShape).semantics { stateDescription = stateText },
            )
        }
    }
}

/**
 * One task of a batch, with its place in the panel and a way to take it away.
 *
 * Numbered because the order is the order the tasks will sit in the cell, and
 * separated by a line rather than boxed in: the panel lives inside a table cell,
 * and a bordered card for every row would spend width the row does not have.
 */
@Composable
private fun BatchTaskRow(
    row: Int,
    draft: TaskDraftRow,
    composer: TaskComposer,
    colors: List<ColorSummary>,
    focus: FocusRequester?,
    controller: GameTableController,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Strings.CellTask.rowTitle, row + 1),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        val removeLabel = stringResource(Strings.CellTask.rowRemove, row + 1)
        TextButton(
            onClick = { controller.removeTaskRow(row) },
            enabled = composer.canRemoveRow && !composer.isSaving,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.focusOutline(ComposerShape).semantics { contentDescription = removeLabel },
        ) {
            Text(text = stringResource(Strings.CellTask.rowRemoveShort), style = MaterialTheme.typography.labelSmall)
        }
    }
    TaskRowFields(
        row = row,
        draft = draft,
        composer = composer,
        colors = colors,
        isRepeatedColor = row in composer.repeatedColorRows,
        focus = focus,
        controller = controller,
    )
}

/**
 * The colour, count, tracking and note of one task being described.
 *
 * The same fields in both modes, because they describe the same thing: a task
 * made alone and one made beside two others are the same record afterwards.
 */
@Composable
private fun TaskRowFields(
    row: Int,
    draft: TaskDraftRow,
    composer: TaskComposer,
    colors: List<ColorSummary>,
    isRepeatedColor: Boolean,
    focus: FocusRequester?,
    controller: GameTableController,
) {
    val trackingChoices =
        composer.columnType.poolType
            ?.let { trackingModesOf(it) }
            .orEmpty()

    OutlinedTextField(
        value = draft.colorQuery,
        onValueChange = { controller.editTaskColorQuery(row, it) },
        enabled = !composer.isSaving,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        label = { Text(stringResource(Strings.CellTask.colorSearch)) },
        modifier = Modifier.fillMaxWidth().then(focus?.let { Modifier.focusRequester(it) } ?: Modifier),
    )
    ColorList(
        colors = colors,
        chosen = listOfNotNull(draft.colorId),
        enabled = !composer.isSaving,
        emptyQuery = draft.colorQuery.isBlank(),
        onChoose = { controller.chooseTaskColor(row, it) },
    )
    composer.repeatedColorRows[row]?.let { earlier ->
        // Said on the row that repeats rather than only at the foot of the
        // panel, and naming the row it repeats: with several rows open, neither
        // "somewhere above" nor a message at the bottom says what to change.
        NoteLine(text = stringResource(Strings.CellTask.rowDuplicate, earlier + 1), isProblem = true)
    }
    if (composer.failureRow == row && composer.failure != null) {
        // The same reason. A batch is refused about one of its rows, and the
        // storage says which; showing it only at the foot would leave the user
        // to guess which colour went away.
        NoteLine(
            text = sentenceOf(composer.failure, composer.failureRow, composer.failureConflictsWith),
            isProblem = true,
        )
    }

    OutlinedTextField(
        value = draft.quantityText,
        onValueChange = { controller.editTaskQuantity(row, it) },
        enabled = !composer.isSaving,
        singleLine = true,
        isError = !draft.isQuantityUsable,
        textStyle = MaterialTheme.typography.bodySmall,
        label = { Text(stringResource(Strings.CellTask.quantityLabel)) },
        modifier = Modifier.fillMaxWidth(),
    )
    NoteLine(
        text =
            if (draft.isQuantityUsable) {
                stringResource(Strings.CellTask.quantityHint)
            } else {
                stringResource(Strings.CellTask.quantityInvalid)
            },
        isProblem = !draft.isQuantityUsable,
    )

    if (trackingChoices.size > 1) {
        // Asked for only where the pool really leaves a choice. Everywhere else
        // the mode follows from the column and nothing is put to the user that
        // has only one answer. Asked per task, because two tasks made together
        // are two tasks and may be tracked differently.
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
            trackingChoices.forEach { mode ->
                val chosen = draft.trackingMode == mode
                val stateText =
                    stringResource(
                        if (chosen) Strings.Accessibility.selected else Strings.Accessibility.notSelected,
                    )
                FilterChip(
                    selected = chosen,
                    enabled = !composer.isSaving,
                    onClick = { controller.chooseTaskTracking(row, mode) },
                    label = { Text(stringResource(labelOf(mode)), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.focusOutline(ComposerShape).semantics { stateDescription = stateText },
                )
            }
        }
    }

    OutlinedTextField(
        value = draft.notes,
        onValueChange = { controller.editTaskNotes(row, it) },
        enabled = !composer.isSaving,
        // A note has lines like any other note, and Enter makes one here too.
        singleLine = false,
        minLines = 1,
        maxLines = 3,
        textStyle = MaterialTheme.typography.bodySmall,
        label = { Text(stringResource(Strings.CellTask.notesLabel)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The one task being described in several colours.
 *
 * One quantity, one note and one tracking mode for the whole task — PLAN 12.7
 * gives it one counter, so a second of any of them would be describing something
 * the product does not have. What there are several of is colours, and they are
 * an ordered list because the order is what the cell draws the name across.
 *
 * Reordering is two plain buttons rather than dragging. PLAN 17 wants every main
 * action reachable from the keyboard, and `Yukarı`/`Aşağı` are that without a
 * gesture nobody can perform with one.
 */
@Composable
private fun MulticolorFields(
    composer: TaskComposer,
    colors: List<ColorSummary>,
    catalogue: List<ColorSummary>,
    focus: FocusRequester,
    controller: GameTableController,
) {
    val palette = composer.palette
    val trackingChoices =
        composer.columnType.poolType
            ?.let { trackingModesOf(it) }
            .orEmpty()

    NoteLine(text = stringResource(Strings.CellTask.modeMulticolorHint), isProblem = false)

    OutlinedTextField(
        value = palette.colorQuery,
        onValueChange = controller::editMulticolorColorQuery,
        enabled = !composer.isSaving,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        label = { Text(stringResource(Strings.CellTask.colorSearch)) },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    ColorList(
        colors = colors,
        chosen = palette.colorIds,
        enabled = !composer.isSaving,
        emptyQuery = palette.colorQuery.isBlank(),
        // Choosing one already in the list takes it back out, so the same colour
        // can never be in it twice — PLAN 5.10 numbers a task's colours uniquely.
        onChoose = controller::toggleMulticolorColor,
    )

    ChosenColorList(
        colorIds = palette.colorIds,
        catalogue = catalogue,
        name = composer.name,
        enabled = !composer.isSaving,
        leastColors = MulticolorDraft.LEAST_COLORS,
        floorText = stringResource(Strings.CellTask.colorFloor),
        failedSlot = composer.failedColorSlot,
        failureText = composer.failure?.let { sentenceOf(it, composer.failureRow, composer.failureConflictsWith) },
        onMoveUp = controller::moveMulticolorColorUp,
        onMoveDown = controller::moveMulticolorColorDown,
        onDrop = controller::toggleMulticolorColor,
    )

    OutlinedTextField(
        value = palette.quantityText,
        onValueChange = controller::editMulticolorQuantity,
        enabled = !composer.isSaving,
        singleLine = true,
        isError = !palette.isQuantityUsable,
        textStyle = MaterialTheme.typography.bodySmall,
        label = { Text(stringResource(Strings.CellTask.quantityLabel)) },
        modifier = Modifier.fillMaxWidth(),
    )
    NoteLine(
        text =
            if (palette.isQuantityUsable) {
                stringResource(Strings.CellTask.quantityHint)
            } else {
                stringResource(Strings.CellTask.quantityInvalid)
            },
        isProblem = !palette.isQuantityUsable,
    )

    if (trackingChoices.size > 1) {
        TrackingChoice(
            choices = trackingChoices,
            chosen = palette.trackingMode,
            onChoose = controller::chooseMulticolorTracking,
        )
    }

    OutlinedTextField(
        value = palette.notes,
        onValueChange = controller::editMulticolorNotes,
        enabled = !composer.isSaving,
        singleLine = false,
        minLines = 1,
        maxLines = 3,
        textStyle = MaterialTheme.typography.bodySmall,
        label = { Text(stringResource(Strings.CellTask.notesLabel)) },
        modifier = Modifier.fillMaxWidth(),
    )
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
private fun ChosenColorList(
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
private fun ColorList(
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

/**
 * What a refusal reads as, with the places it is about filled in.
 *
 * A duplicate colour is the one refusal that is about a pair, so it is the one
 * that cannot be a bare sentence: naming only the second of the two leaves the
 * user looking for the first. Counted from one, because that is how the panel
 * numbers what it shows.
 */
@Composable
private fun sentenceOf(
    failure: TaskFromTextFailure,
    row: Int?,
    conflictsWith: Int?,
): String =
    if (failure == TaskFromTextFailure.DUPLICATE_COLOR && row != null && conflictsWith != null) {
        stringResource(Strings.CellTask.errorDuplicateColor, conflictsWith + 1, row + 1)
    } else {
        stringResource(messageOf(failure))
    }

@Composable
private fun sentenceOf(
    failure: TaskEditFailure,
    row: Int?,
    conflictsWith: Int?,
): String =
    if (failure == TaskEditFailure.DUPLICATE_COLOR && row != null && conflictsWith != null) {
        stringResource(Strings.TaskEdit.errorDuplicateColor, conflictsWith + 1, row + 1)
    } else {
        stringResource(messageOf(failure))
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
        TaskFromTextFailure.DUPLICATE_COLOR -> Strings.CellTask.errorDuplicateColor
        TaskFromTextFailure.NO_TASK_DESCRIBED -> Strings.CellTask.errorNoTask
        TaskFromTextFailure.COULD_NOT_SAVE -> Strings.CellTask.errorCouldNotSave
    }

/** What to tell the user about a cell that did not save. */
private fun messageOf(failure: CellTextFailure) =
    when (failure) {
        CellTextFailure.GAME_NOT_AVAILABLE -> Strings.Cell.errorGameGone
        CellTextFailure.CELL_NOT_AVAILABLE -> Strings.Cell.errorCellGone
        CellTextFailure.CHANGE_CROSSES_A_TASK -> Strings.Cell.errorCrossesTask
        CellTextFailure.STALE_DOCUMENT -> Strings.Cell.errorStaleDocument
        CellTextFailure.COULD_NOT_SAVE -> Strings.Cell.errorCouldNotSave
    }

/** What to tell the user about a task that did not change. */
private fun messageOf(failure: TaskEditFailure) =
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
        TaskEditFailure.COULD_NOT_SAVE -> Strings.TaskEdit.errorCouldNotSave
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
