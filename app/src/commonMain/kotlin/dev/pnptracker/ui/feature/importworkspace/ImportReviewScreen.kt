package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pnptracker.data.repository.GameChoice
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.importconfirm.ImportConfirmationSummary
import dev.pnptracker.domain.importconfirm.TargetCellChoice
import dev.pnptracker.domain.importhint.CellHintAnalysis
import dev.pnptracker.domain.importreview.ReviewDraftTask
import dev.pnptracker.domain.importreview.ReviewRawBlock
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.isUnusableQuantity
import dev.pnptracker.domain.tasks.suitsPool
import dev.pnptracker.domain.tasks.trackingModesOf
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.columnNameOf
import dev.pnptracker.ui.feature.importreview.nameOf
import dev.pnptracker.ui.feature.tasks.NoteLine
import dev.pnptracker.ui.feature.tasks.focusOutline
import dev.pnptracker.ui.theme.opaqueColorOf
import dev.pnptracker.ui.theme.visibleEdgeOn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Below this the two panes stop being two panes and become one column of tabs. */
private val TwoPaneWidth = 880.dp

private val PanelShape = RoundedCornerShape(8.dp)

/** How tall a list inside a panel may grow before it scrolls inside itself. */
private val InnerListHeight = 150.dp

/**
 * Reviewing one saved import: the cells the file held on the left, the task
 * drafts made from them on the right.
 *
 * The left pane shows the cell text exactly as the file had it — line breaks,
 * `**` markers and spacing included — which is why it is set in a monospaced
 * face and never trimmed for display. It is also the surface the user cuts tasks
 * out of, so it is a real selectable field rather than a label: the offsets it
 * reports are the ones stored on the draft.
 */
@Composable
fun ImportReviewScreen(
    controller: ImportReviewController,
    confirmation: ImportConfirmationController,
    batchId: EntityId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(batchId) { controller.observe(batchId) }
    LaunchedEffect(batchId) { confirmation.refresh(batchId) }
    LaunchedEffect(Unit) { confirmation.observeTargetCells() }
    LaunchedEffect(Unit) { controller.observeGames() }
    LaunchedEffect(Unit) { controller.observeColorCatalogue() }
    LaunchedEffect(Unit) { controller.observeColors() }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 20.dp)
                // Escape steps out of the innermost panel and no further, wherever
                // the keyboard happens to be inside the screen.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        controller.closeInnermost()
                        true
                    } else {
                        false
                    }
                },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Strings.Review.title),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBack) { Text(stringResource(Strings.Review.back)) }
        }

        when (val state = controller.state) {
            ImportReviewState.Loading -> BusyRow(stringResource(Strings.Review.loading))

            ImportReviewState.Unavailable ->
                MessageCard(
                    title = stringResource(Strings.Review.unavailableTitle),
                    body = stringResource(Strings.Review.unavailable),
                )

            is ImportReviewState.Empty ->
                MessageCard(
                    title = stringResource(Strings.Review.emptyTitle),
                    body = stringResource(Strings.Review.empty, state.fileName, state.sheetName),
                )

            is ImportReviewState.Content ->
                ReviewContent(
                    state = state,
                    controller = controller,
                    confirmation = confirmation,
                    batchId = batchId,
                )
        }
    }
}

/**
 * The whole workspace, laid out to fit.
 *
 * Wide enough and the two panes stand side by side, which is the layout PLAN
 * 11.4 sketches. Narrower, they become one column with a control to move between
 * them: two panes squeezed into a phone-width window leave a colour list and a
 * game picker that reach off the edge, which PLAN 17 does not allow. The context
 * travels either way — the drafts pane says which cell it is showing.
 */
@Composable
private fun ReviewContent(
    state: ImportReviewState.Content,
    controller: ImportReviewController,
    confirmation: ImportConfirmationController,
    batchId: EntityId,
) {
    val workspace = state.workspace

    // What confirming would do is a snapshot, and everything the user does to a
    // draft changes it. The workspace arriving is exactly when it has changed,
    // so the summary and the list of blockers are re-read from there rather than
    // by every action remembering to ask.
    LaunchedEffect(workspace) { confirmation.refresh(batchId) }

    Text(
        text = stringResource(Strings.Import.fileLabel, workspace.fileName),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = stringResource(Strings.Review.progress, workspace.processedBlockCount, workspace.rawBlockCount),
        style = MaterialTheme.typography.labelMedium,
    )
    if (state.failure != null) {
        NoteLine(text = stringResource(reviewMessageOf(state.failure)), isProblem = true)
    }

    ConfirmationSection(
        confirmation = confirmation,
        controller = controller,
        state = state,
        batchId = batchId,
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= TwoPaneWidth) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RawBlockPane(
                    state = state,
                    controller = controller,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                DraftPane(
                    state = state,
                    controller = controller,
                    confirmation = confirmation,
                    batchId = batchId,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            NarrowPanes(state = state, controller = controller, confirmation = confirmation, batchId = batchId)
        }
    }
}

/** One column, with a control saying which pane is showing. */
@Composable
private fun NarrowPanes(
    state: ImportReviewState.Content,
    controller: ImportReviewController,
    confirmation: ImportConfirmationController,
    batchId: EntityId,
) {
    var showingDrafts by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            PaneTab(
                label = stringResource(Strings.Review.paneCells),
                selected = !showingDrafts,
                onClick = { showingDrafts = false },
            )
            PaneTab(
                label = stringResource(Strings.Review.paneDrafts),
                selected = showingDrafts,
                onClick = { showingDrafts = true },
            )
        }
        // The cell the drafts belong to is named on both tabs, so moving between
        // them never loses which cell is being read.
        state.selectedBlock?.let { block ->
            Text(
                text = stringResource(Strings.Review.cellLocation, block.rowIndex + 1, block.columnIndex + 1),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (showingDrafts) {
            DraftPane(
                state = state,
                controller = controller,
                confirmation = confirmation,
                batchId = batchId,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            RawBlockPane(state = state, controller = controller, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PaneTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val stateText = stringResource(if (selected) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier =
            Modifier.focusOutline(PanelShape).semantics {
                contentDescription = label
                stateDescription = stateText
            },
    )
}

// ------------------------------------------------------------- the cells

@Composable
private fun RawBlockPane(
    state: ImportReviewState.Content,
    controller: ImportReviewController,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Strings.Review.rawBlocksTitle),
            style = MaterialTheme.typography.titleMedium,
        )
        // Being sent to a cell has to actually show it: PLAN 17 asks that a
        // problem be reachable, and selecting a row forty rows down the list
        // without moving the list is not reaching it.
        val listState = rememberLazyListState()
        LaunchedEffect(state.selectedBlockId) {
            val at = state.workspace.rawBlocks.indexOfFirst { it.id == state.selectedBlockId }
            if (at >= 0) listState.animateScrollToItem(at)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.workspace.rawBlocks, key = { it.id.toString() }) { block ->
                RawBlockRow(
                    block = block,
                    draftCount = state.workspace.draftsOf(block.id).size,
                    isSelected = block.id == state.selectedBlockId,
                    isEditable = state.canEdit,
                    controller = controller,
                )
            }
        }
    }
}

@Composable
private fun RawBlockRow(
    block: ReviewRawBlock,
    draftCount: Int,
    isSelected: Boolean,
    isEditable: Boolean,
    controller: ImportReviewController,
) {
    val scope = rememberCoroutineScope()
    val selectCell = stringResource(Strings.Review.selectCellAccessibility)
    val requester = remember(block.id) { FocusRequester() }
    val wanted = controller.focus
    LaunchedEffect(wanted) {
        if (wanted is ReviewFocus.Block && wanted.blockId == block.id) {
            requester.requestFocus()
            controller.focusHonoured()
        }
    }

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // What selects the cell, and nothing else. The open cell's own text
            // field lives outside it: a `selectable` wrapped around the whole
            // card takes the keyboard back on every release, so the field it
            // contains could be clicked and never kept — which is the whole
            // point of the left pane.
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(requester)
                        .focusOutline(RoundedCornerShape(8.dp))
                        .selectable(selected = isSelected, onClick = { controller.select(block.id) })
                        .semantics { contentDescription = selectCell },
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(nameOf(block.sourceColumnType)),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        // One based, because the pane is read against the spreadsheet.
                        text = stringResource(Strings.Review.cellLocation, block.rowIndex + 1, block.columnIndex + 1),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (block.isProcessed) {
                        val processed = stringResource(Strings.Review.processedAccessibility)
                        Text(
                            text = stringResource(Strings.Review.processedBadge),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { contentDescription = processed },
                        )
                    }
                    if (draftCount > 0) {
                        Text(
                            text = stringResource(Strings.Review.draftCount, draftCount),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                if (!isSelected) {
                    // Closed, the cell still shows what it says, word for word.
                    Text(
                        text = block.rawText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (block.gameCompletionHint == HintDecision.PENDING) {
                        NoteLine(text = stringResource(Strings.Review.greenHintPending), isProblem = false)
                    }
                }
            }

            if (isSelected) {
                OpenCell(block = block, isEditable = isEditable, controller = controller)
            }

            // A confirmed import is evidence, not a workspace: PLAN 11.4.3 makes
            // it read only, so the actions that write are not offered at all.
            if (isEditable) {
                OutlinedButton(
                    onClick = { scope.launch { controller.setProcessed(block.id, !block.isProcessed) } },
                    enabled = !controller.isSaving,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.focusOutline(PanelShape),
                ) {
                    Text(
                        text =
                            stringResource(
                                if (block.isProcessed) Strings.Review.markUnprocessed else Strings.Review.markProcessed,
                            ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/**
 * The cell the user is reading: its text to select from, what the detectors
 * noticed in it, and the two ways of making a task out of it.
 */
@Composable
private fun OpenCell(
    block: ReviewRawBlock,
    isEditable: Boolean,
    controller: ImportReviewController,
) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        SelectableRawText(block = block, controller = controller)
        NoteLine(text = stringResource(Strings.Review.rawTextHint), isProblem = false)

        val pointed = controller.selection?.takeIf { it.blockId == block.id }
        NoteLine(
            text =
                if (pointed == null) {
                    stringResource(Strings.Review.selectionNone)
                } else {
                    stringResource(
                        Strings.Review.selectionShown,
                        block.rawText.substring(pointed.startIndex, pointed.endIndex),
                    )
                },
            isProblem = false,
        )

        CellHints(controller.hintsOfSelectedBlock())

        if (isEditable) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val createLabel = stringResource(Strings.Review.createFromSelection)
                val byHandLabel = stringResource(Strings.Review.createByHand)
                Button(
                    onClick = { scope.launch { controller.createFromSelection() } },
                    enabled = pointed != null && !controller.isSaving,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = createLabel },
                ) {
                    Text(text = createLabel, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = { controller.beginManualDraft(block.id) },
                    enabled = !controller.isSaving,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = byHandLabel },
                ) {
                    Text(text = byHandLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        GreenHintDecision(block = block, isEditable = isEditable, controller = controller)
    }
}

/**
 * The cell's own text, selectable and copyable, reporting where the user pointed.
 *
 * A real text field rather than a label with a selection wrapper around it: the
 * point of the left pane is that the boundaries the user drags to become the
 * offsets stored on the draft, and a wrapper that only paints a highlight has no
 * offsets to give.
 *
 * It is read only **by construction** rather than by the `readOnly` flag: on the
 * desktop that flag turns off the mouse selection detector along with the
 * editing, which leaves a field nobody can drag across and nothing to report.
 * So the field stays editable as far as Compose is concerned and this only ever
 * takes the selection out of what comes back — the text it is given is the text
 * it keeps, whatever is typed at it.
 */
@Composable
private fun SelectableRawText(
    block: ReviewRawBlock,
    controller: ImportReviewController,
) {
    var field by remember(block.id, block.rawText) {
        mutableStateOf(
            androidx.compose.ui.text.input
                .TextFieldValue(block.rawText),
        )
    }
    val spoken =
        stringResource(
            Strings.Review.rawTextAccessibility,
            block.rowIndex + 1,
            block.columnIndex + 1,
            stringResource(nameOf(block.sourceColumnType)),
            block.rawText,
        )
    OutlinedTextField(
        value = field,
        onValueChange = { updated ->
            // Only where the caret and the selection are is taken; the text is
            // the one this was given and is never replaced, so a keystroke at the
            // record of what the file said changes nothing at all. Offsets are
            // kept as the field reports them, in the UTF-16 units the draft is
            // stored with.
            // A field losing the keyboard reports its selection as collapsed,
            // and pressing the button that acts on the selection is exactly what
            // takes the keyboard away. So a collapse says nothing: the words stay
            // chosen until something really unchooses them.
            if (!updated.selection.collapsed) {
                field = field.copy(selection = updated.selection)
                controller.pointAt(block.id, updated.selection.min, updated.selection.max)
            }
        },
        label = { Text(stringResource(Strings.Review.rawTextTitle)) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        minLines = 2,
        maxLines = 8,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = spoken },
    )
}

/** What the detectors noticed, said as suggestions and never applied. */
@Composable
private fun CellHints(analysis: CellHintAnalysis?) {
    if (analysis == null) return
    val quantity = analysis.quantity
    val markers = analysis.completionMarkers
    val alternatives = analysis.alternativeColors
    val column = analysis.columnSuggestion?.suggestedPoolType
    if (quantity == null && markers.isEmpty() && alternatives.isEmpty() && column == null) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = stringResource(Strings.Review.hintsTitle), style = MaterialTheme.typography.labelMedium)
        if (column != null) {
            NoteLine(
                text = stringResource(Strings.Review.hintColumn, stringResource(labelOf(column))),
                isProblem = false,
            )
        }
        if (quantity != null) {
            NoteLine(text = stringResource(Strings.Review.hintQuantity, quantity.value), isProblem = false)
        }
        if (markers.isNotEmpty()) {
            NoteLine(text = stringResource(Strings.Review.hintMarker, markers.size), isProblem = false)
        }
        alternatives.forEach { alternative ->
            // PLAN 11.6: written as a choice, so it is shown as one. Nothing here
            // turns it into a colour relation.
            NoteLine(
                text =
                    stringResource(
                        Strings.Review.hintAlternative,
                        alternative.options.joinToString(" / ") { it.canonicalName },
                    ),
                isProblem = false,
            )
        }
        NoteLine(text = stringResource(Strings.Review.hintOnly), isProblem = false)
    }
}

/**
 * What the user answers to a green game cell, and which game they meant.
 *
 * The answer is stored here and the game is not finished: PLAN 5.3 has an
 * accepted hint change a game's completion, and that write belongs to confirming
 * the import. A version 5 database can hold an acceptance with no game at all,
 * which is shown as the open question it is rather than quietly turned back into
 * a no.
 */
@Composable
private fun GreenHintDecision(
    block: ReviewRawBlock,
    isEditable: Boolean,
    controller: ImportReviewController,
) {
    if (block.gameCompletionHint == HintDecision.NONE) return
    val scope = rememberCoroutineScope()
    val chosen = controller.games.firstOrNull { it.id == block.completionTargetGameId }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Text(text = stringResource(Strings.Review.greenTitle), style = MaterialTheme.typography.labelLarge)
        val summary =
            when {
                block.needsCompletionTarget -> stringResource(Strings.Review.greenNeedsTarget)
                block.gameCompletionHint == HintDecision.ACCEPTED ->
                    stringResource(Strings.Review.greenAccepted)

                block.gameCompletionHint == HintDecision.REJECTED -> stringResource(Strings.Review.greenRejected)
                else -> stringResource(Strings.Review.greenHintPending)
            }
        val decisionRequester = remember(block.id) { FocusRequester() }
        val wanted = controller.focus
        LaunchedEffect(wanted) {
            if (wanted is ReviewFocus.GameDecision) {
                decisionRequester.requestFocus()
                controller.focusHonoured()
            }
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (block.gameCompletionHint == HintDecision.PENDING || block.needsCompletionTarget) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            modifier =
                Modifier
                    .focusRequester(decisionRequester)
                    .focusable()
                    .focusOutline(PanelShape)
                    .semantics { contentDescription = summary },
        )
        if (chosen != null) {
            Text(
                text = stringResource(Strings.Review.greenTarget, chosen.label()),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (!isEditable) return@Column

        val open = controller.surface
        if (open is ImportReviewSurface.GameTarget && open.blockId == block.id) {
            GamePicker(
                games = controller.games,
                chosenId = block.completionTargetGameId,
                enabled = !controller.isSaving,
                onChoose = { gameId -> scope.launch { controller.acceptGameHint(block.id, gameId) } },
                onClose = { controller.closeInnermost() },
            )
            return@Column
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val acceptLabel =
                stringResource(
                    if (block.gameCompletionHint == HintDecision.ACCEPTED) {
                        Strings.Review.greenChangeGame
                    } else {
                        Strings.Review.greenAccept
                    },
                )
            val rejectLabel = stringResource(Strings.Review.greenReject)
            Button(
                onClick = { controller.openGameTarget(block.id) },
                enabled = !controller.isSaving,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = acceptLabel },
            ) {
                Text(text = acceptLabel, style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = { scope.launch { controller.rejectGameHint(block.id) } },
                enabled = !controller.isSaving && block.gameCompletionHint != HintDecision.REJECTED,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = rejectLabel },
            ) {
                Text(text = rejectLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The games an accepted green cell can name; never a place to make one. */
@Composable
private fun GamePicker(
    games: List<GameChoice>,
    chosenId: EntityId?,
    enabled: Boolean,
    onChoose: (EntityId) -> Unit,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(Strings.Review.greenChooseGame), style = MaterialTheme.typography.labelMedium)
        if (games.isEmpty()) {
            NoteLine(text = stringResource(Strings.Review.greenNoGames), isProblem = true)
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = InnerListHeight)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            games.forEach { game ->
                val isChosen = game.id == chosenId
                val label = game.label()
                val stateText =
                    stringResource(
                        if (isChosen) Strings.Accessibility.selected else Strings.Accessibility.notSelected,
                    )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = isChosen, enabled = enabled, onClick = { onChoose(game.id) })
                            .focusOutline(PanelShape)
                            .padding(horizontal = 4.dp, vertical = 3.dp)
                            .semantics {
                                contentDescription = label
                                stateDescription = stateText
                            },
                )
            }
        }
        TextButton(
            onClick = onClose,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.focusOutline(PanelShape),
        ) {
            Text(text = stringResource(Strings.Review.editCancel), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** How a cell reads in a list where another game may share its game's name. */
@Composable
private fun TargetCellChoice.label(): String {
    val named =
        sharedNameOrdinal?.let { stringResource(Strings.Review.greenGameShared, gameName, it) } ?: gameName
    return stringResource(Strings.Aim.cellLabel, named, stringResource(columnNameOf(columnType)))
}

/** How a game reads in a list where another game may share its name. */
@Composable
private fun GameChoice.label(): String {
    val named =
        sharedNameOrdinal?.let { stringResource(Strings.Review.greenGameShared, name, it) } ?: name
    return if (isCompleted) stringResource(Strings.Review.greenGameCompleted, named) else named
}

// ------------------------------------------------------------- the drafts

@Composable
private fun DraftPane(
    state: ImportReviewState.Content,
    controller: ImportReviewController,
    confirmation: ImportConfirmationController,
    batchId: EntityId,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val open = controller.surface
        if (open is ImportReviewSurface.ManualDraft && state.canEdit) {
            ManualDraftCard(manual = open, controller = controller)
        }
        if (state.canEdit) {
            open.openForm?.let { form ->
                DraftEditorCard(
                    form = form,
                    isChoosingColor = open is ImportReviewSurface.ColorChoice,
                    colorQuery = (open as? ImportReviewSurface.ColorChoice)?.query.orEmpty(),
                    controller = controller,
                    targetCells = confirmation.targetCells,
                )
            }
        }
        Text(
            text =
                stringResource(
                    if (state.selectedBlockId == null) Strings.Review.draftsTitle else Strings.Review.draftsOfSelected,
                ),
            style = MaterialTheme.typography.titleMedium,
        )

        val drafts = state.visibleDrafts
        if (drafts.isEmpty()) {
            EmptyDraftPane()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(drafts, key = { it.id.toString() }) { draft ->
                    DraftRow(
                        draft = draft,
                        controller = controller,
                        confirmation = confirmation,
                        isEditable = state.canEdit,
                    )
                }
            }
        }

        HorizontalDivider()
        NoteLine(text = stringResource(Strings.Review.noRealRecords), isProblem = false)
    }
}

@Composable
private fun ManualDraftCard(
    manual: ImportReviewSurface.ManualDraft,
    controller: ImportReviewController,
) {
    val scope = rememberCoroutineScope()
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = stringResource(Strings.Review.manualTitle), style = MaterialTheme.typography.titleSmall)
            NoteLine(text = stringResource(Strings.Review.manualHint), isProblem = false)
            OutlinedTextField(
                value = manual.name,
                onValueChange = controller::editManualName,
                enabled = !controller.isSaving,
                singleLine = true,
                isError = manual.name.isNotEmpty() && !manual.canSave,
                label = { Text(stringResource(Strings.Review.editNameLabel)) },
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
            )
            if (!manual.canSave) {
                NoteLine(text = stringResource(Strings.Review.errorTaskNameEmpty), isProblem = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val saveLabel = stringResource(Strings.Review.manualCreate)
                Button(
                    onClick = { scope.launch { controller.createByHand() } },
                    enabled = manual.canSave && !controller.isSaving,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = saveLabel },
                ) {
                    Text(text = saveLabel, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = controller::cancelManualDraft,
                    enabled = !controller.isSaving,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.focusOutline(PanelShape),
                ) {
                    Text(
                        text = stringResource(Strings.Review.editCancel),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDraftPane() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = stringResource(Strings.Review.draftsEmpty), style = MaterialTheme.typography.bodyLarge)
        Text(text = stringResource(Strings.Review.draftsEmptyHint), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One draft as it stands, said once for a reader and shown in parts for a
 * looker.
 *
 * The whole of what the draft says is carried on a single line's description, so
 * a screen reader announces it once instead of spelling out every chip. The
 * controls beside it keep their own labels, because they are actions rather than
 * parts of the sentence.
 */
@Composable
private fun DraftRow(
    draft: ReviewDraftTask,
    controller: ImportReviewController,
    confirmation: ImportConfirmationController,
    isEditable: Boolean,
) {
    val requester = remember(draft.id) { FocusRequester() }
    val wanted = controller.focus
    LaunchedEffect(wanted) {
        if (wanted is ReviewFocus.Draft && wanted.draftId == draft.id) {
            requester.requestFocus()
            controller.focusHonoured()
        }
    }
    val target = confirmation.targetCells.firstOrNull { it.cellId == draft.targetCellId }
    val spoken = draft.spokenSummary(target, controller.colors)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = draft.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .focusRequester(requester)
                        // A requester alone does not make a node take the
                        // keyboard, and a stop nobody can reach is not a stop.
                        .focusable()
                        .focusOutline(PanelShape)
                        .semantics { contentDescription = spoken },
            )
            Text(
                text = target?.label() ?: stringResource(Strings.Aim.none),
                style = MaterialTheme.typography.labelMedium,
                color = if (target == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    if (draft.requiredQuantity == null) {
                        stringResource(Strings.Review.draftQuantityUnknown)
                    } else {
                        stringResource(Strings.Review.draftQuantity, draft.requiredQuantity)
                    },
                style = MaterialTheme.typography.labelMedium,
            )
            ChosenColorSwatches(colorIds = draft.colorIds, catalogue = controller.colors)
            DraftFlagLine(draft)
            draft.notes?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(Strings.Review.draftNotes, it),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (draft.materializedTaskId != null) {
                // A confirmed draft is evidence now, not something to edit.
                Text(
                    text = stringResource(Strings.Aim.materialized),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (isEditable) {
                CompletionHintDecision(draft = draft, controller = controller)
                val editLabel = stringResource(Strings.Review.editOpen)
                OutlinedButton(
                    onClick = { controller.openDraft(draft) },
                    enabled = !controller.isSaving,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = editLabel },
                ) {
                    Text(text = editLabel, style = MaterialTheme.typography.labelMedium)
                }
            }

            if (draft.isReady) {
                Text(
                    text = stringResource(Strings.Aim.ready),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** The `**` a draft carries, and the two answers to it. */
@Composable
private fun CompletionHintDecision(
    draft: ReviewDraftTask,
    controller: ImportReviewController,
) {
    if (draft.completionHint == HintDecision.NONE) return
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = stringResource(Strings.Review.completionTitle), style = MaterialTheme.typography.labelMedium)
        NoteLine(
            text =
                when (draft.completionHint) {
                    HintDecision.ACCEPTED -> stringResource(Strings.Review.completionAccepted)
                    HintDecision.REJECTED -> stringResource(Strings.Review.completionRejected)
                    else -> stringResource(Strings.Review.completionUndecided, draft.name)
                },
            isProblem = draft.completionHint == HintDecision.PENDING,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val acceptLabel = stringResource(Strings.Review.completionAccept)
            val rejectLabel = stringResource(Strings.Review.completionReject)
            FilterChip(
                selected = draft.completionHint == HintDecision.ACCEPTED,
                enabled = !controller.isSaving,
                onClick = {
                    scope.launch { controller.answerCompletionHint(draft.id, HintDecision.ACCEPTED) }
                },
                label = { Text(acceptLabel, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = acceptLabel },
            )
            FilterChip(
                selected = draft.completionHint == HintDecision.REJECTED,
                enabled = !controller.isSaving,
                onClick = {
                    scope.launch { controller.answerCompletionHint(draft.id, HintDecision.REJECTED) }
                },
                label = { Text(rejectLabel, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = rejectLabel },
            )
        }
    }
}

/** The four import marks a draft carries, named rather than only coloured. */
@Composable
private fun DraftFlagLine(draft: ReviewDraftTask) {
    val marks =
        buildList {
            if (draft.isMissing) add(stringResource(Strings.Review.flagMissing))
            if (draft.isBorrowed) add(stringResource(Strings.Review.flagBorrowed))
            if (draft.needsInfo) add(stringResource(Strings.Review.flagNeedsInfo))
            if (draft.needsClassification) add(stringResource(Strings.Review.flagNeedsClassification))
        }
    if (marks.isEmpty()) return
    Text(text = marks.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
}

/** The colours a draft carries, in the user's own order, named and drawn. */
@Composable
private fun ChosenColorSwatches(
    colorIds: List<EntityId>,
    catalogue: List<ColorSummary>,
) {
    if (colorIds.isEmpty()) {
        Text(
            text = stringResource(Strings.Review.draftColorsNone),
            style = MaterialTheme.typography.labelMedium,
        )
        return
    }
    val unknown = stringResource(Strings.CellTask.colorUnknown)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        colorIds.forEach { colorId ->
            val color = catalogue.firstOrNull { it.id == colorId }
            val swatch = color?.let { opaqueColorOf(it.hex) }
            if (swatch != null) {
                Box(
                    modifier =
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(swatch)
                            .border(1.dp, visibleEdgeOn(swatch), RoundedCornerShape(3.dp)),
                )
            }
            Text(
                text = color?.canonicalName ?: unknown,
                style = MaterialTheme.typography.labelMedium,
                color = if (color == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The whole of one draft, in one form saved by one action.
 *
 * Everything here is written together or not at all: PLAN 11.4 lists the name,
 * the pool, the colours, the amount, the state and the note as what a draft
 * says, and a half applied panel is a state the user never asked for. A refusal
 * leaves every field exactly as they left it.
 */
@Composable
private fun DraftEditorCard(
    form: DraftForm,
    isChoosingColor: Boolean,
    colorQuery: String,
    controller: ImportReviewController,
    targetCells: List<TargetCellChoice>,
) {
    val scope = rememberCoroutineScope()
    val nameFocus = remember(form.draftId) { FocusRequester() }
    LaunchedEffect(form.draftId) { nameFocus.requestFocus() }
    val save = { if (form.canSave && !controller.isSaving) scope.launch { controller.saveDraft() } }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                // Ctrl+Enter saves the whole form, once: the guard inside the
                // controller makes a second press while one is in flight nothing.
                .onPreviewKeyEvent { event ->
                    val isSave =
                        event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed
                    if (isSave) {
                        save()
                        true
                    } else {
                        false
                    }
                },
    ) {
        Column(
            modifier =
                Modifier
                    .padding(12.dp)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = stringResource(Strings.Review.editTitle), style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = form.name,
                onValueChange = controller::editName,
                enabled = !controller.isSaving,
                singleLine = true,
                isError = form.name.isNotEmpty() && !form.isNameUsable,
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text(stringResource(Strings.Review.editNameLabel)) },
                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
            )
            if (form.name.isNotEmpty() && !form.isNameUsable) {
                NoteLine(text = stringResource(Strings.Review.errorSelectionLineBreak), isProblem = true)
            }

            TargetChoice(form = form, targetCells = targetCells, controller = controller)

            // The amount, or the user's answer that it is not known. PLAN 11.7
            // makes the second a real answer, so it is a box to tick rather than
            // an empty field to be guessed at.
            val unknownLabel = stringResource(Strings.Review.editQuantityUnknown)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.quantityUnknown,
                    enabled = !controller.isSaving,
                    onCheckedChange = controller::setQuantityUnknown,
                    modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = unknownLabel },
                )
                Text(text = unknownLabel, style = MaterialTheme.typography.labelMedium)
            }
            if (!form.quantityUnknown) {
                OutlinedTextField(
                    value = form.quantityText,
                    onValueChange = controller::editQuantity,
                    enabled = !controller.isSaving,
                    singleLine = true,
                    isError = isUnusableQuantity(form.quantityText) || !form.isQuantityUsable,
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text(stringResource(Strings.Review.editQuantityLabel)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!form.isQuantityUsable) {
                    NoteLine(text = stringResource(Strings.Review.editQuantityInvalid), isProblem = true)
                }
            }

            OutlinedTextField(
                value = form.notes,
                onValueChange = controller::editNotes,
                enabled = !controller.isSaving,
                singleLine = false,
                minLines = 1,
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text(stringResource(Strings.Review.editNotesLabel)) },
                modifier = Modifier.fillMaxWidth(),
            )

            FlagChoices(form = form, controller = controller)
            CompletionChoiceInForm(form = form, controller = controller)
            ColorSection(
                form = form,
                isChoosing = isChoosingColor,
                query = colorQuery,
                controller = controller,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val saveLabel = stringResource(Strings.Review.editSave)
                val cancelLabel = stringResource(Strings.Review.editCancel)
                Button(
                    onClick = { save() },
                    enabled = form.canSave && !controller.isSaving,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = saveLabel },
                ) {
                    Text(text = saveLabel, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = controller::closeInnermost,
                    enabled = !controller.isSaving,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = cancelLabel },
                ) {
                    Text(text = cancelLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
            NoteLine(
                text =
                    if (controller.isSaving) {
                        stringResource(Strings.Review.editSaving)
                    } else {
                        stringResource(Strings.Review.editHint)
                    },
                isProblem = false,
            )
        }
    }
}

/**
 * Where the task will go, which pool it is in, and how it is tracked.
 *
 * The three move together through the one rule that keeps a cell and a pool from
 * contradicting each other, so a pool the user changes never leaves a cell or a
 * tracking mode behind that the database would refuse. A pool allowing exactly
 * one mode is set outright rather than offered as a list of one, and the value
 * is still written explicitly.
 */
@Composable
private fun TargetChoice(
    form: DraftForm,
    targetCells: List<TargetCellChoice>,
    controller: ImportReviewController,
) {
    if (targetCells.isEmpty()) {
        NoteLine(text = stringResource(Strings.Aim.noCells), isProblem = true)
        return
    }
    Text(text = stringResource(Strings.Aim.chooseCell), style = MaterialTheme.typography.labelMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        targetCells.filter { it.columnType.suitsPool(form.poolType) }.forEach { target ->
            val label = target.label()
            val isChosen = target.cellId == form.targetCellId
            val stateText =
                stringResource(if (isChosen) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
            FilterChip(
                selected = isChosen,
                enabled = !controller.isSaving,
                onClick = { controller.chooseTarget(target.cellId, target.columnType) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                modifier =
                    Modifier.focusOutline(PanelShape).semantics {
                        contentDescription = label
                        stateDescription = stateText
                    },
            )
        }
    }

    Text(text = stringResource(Strings.Aim.choosePool), style = MaterialTheme.typography.labelMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        PoolType.entries.forEach { pool ->
            val label = stringResource(labelOf(pool))
            val isChosen = pool == form.poolType
            val stateText =
                stringResource(if (isChosen) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
            FilterChip(
                selected = isChosen,
                enabled = !controller.isSaving,
                onClick = { controller.choosePool(pool) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier =
                    Modifier.focusOutline(PanelShape).semantics {
                        contentDescription = label
                        stateDescription = stateText
                    },
            )
        }
    }

    val modes = form.poolType?.let(::trackingModesOf).orEmpty()
    if (modes.size > 1) {
        Text(text = stringResource(Strings.Aim.chooseTracking), style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            modes.forEach { mode -> TrackingChip(mode, form.trackingMode, controller) }
        }
    }
}

@Composable
private fun TrackingChip(
    mode: TrackingMode,
    chosen: TrackingMode?,
    controller: ImportReviewController,
) {
    val label = stringResource(labelOf(mode))
    val isChosen = mode == chosen
    val stateText =
        stringResource(if (isChosen) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
    FilterChip(
        selected = isChosen,
        enabled = !controller.isSaving,
        onClick = { controller.chooseTracking(mode) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier =
            Modifier.focusOutline(PanelShape).semantics {
                contentDescription = label
                stateDescription = stateText
            },
    )
}

/**
 * The four import marks, as things to tick.
 *
 * They are not pools and the line under them says so: PLAN 10 keeps `Eksik` and
 * `Ödünç Parçalar` as notes about work whose pool the user still chooses. Ticking
 * one of those two unticks the other, because a cell came from one column.
 */
@Composable
private fun FlagChoices(
    form: DraftForm,
    controller: ImportReviewController,
) {
    Text(text = stringResource(Strings.Review.flagsTitle), style = MaterialTheme.typography.labelMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        FlagBox(stringResource(Strings.Review.flagMissing), form.isMissing, controller.isSaving, controller::setMissing)
        FlagBox(
            stringResource(Strings.Review.flagBorrowed),
            form.isBorrowed,
            controller.isSaving,
            controller::setBorrowed,
        )
        FlagBox(
            stringResource(Strings.Review.flagNeedsInfo),
            form.needsInfo,
            controller.isSaving,
            controller::setNeedsInfo,
        )
        FlagBox(
            stringResource(Strings.Review.flagNeedsClassification),
            form.needsClassification,
            controller.isSaving,
            controller::setNeedsClassification,
        )
    }
    NoteLine(text = stringResource(Strings.Review.flagsNote), isProblem = false)
    if (form.needsInfo) {
        NoteLine(text = stringResource(Strings.Review.flagNeedsInfoNote), isProblem = false)
    }
    if (form.flagsConflict) {
        NoteLine(text = stringResource(Strings.Review.flagConflict), isProblem = true)
    }
}

@Composable
private fun FlagBox(
    label: String,
    checked: Boolean,
    isSaving: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            enabled = !isSaving,
            onCheckedChange = onChange,
            modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = label },
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

/** The `**` answer, saved with the rest of the form rather than on its own. */
@Composable
private fun CompletionChoiceInForm(
    form: DraftForm,
    controller: ImportReviewController,
) {
    if (form.completionHint == HintDecision.NONE) return
    Text(text = stringResource(Strings.Review.completionTitle), style = MaterialTheme.typography.labelMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        listOf(
            HintDecision.ACCEPTED to stringResource(Strings.Review.completionAccept),
            HintDecision.REJECTED to stringResource(Strings.Review.completionReject),
        ).forEach { (decision, label) ->
            val isChosen = form.completionHint == decision
            val stateText =
                stringResource(if (isChosen) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
            FilterChip(
                selected = isChosen,
                enabled = !controller.isSaving,
                onClick = { controller.decideCompletion(decision) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier =
                    Modifier.focusOutline(PanelShape).semantics {
                        contentDescription = label
                        stateDescription = stateText
                    },
            )
        }
    }
    if (form.completionHint == HintDecision.PENDING) {
        NoteLine(text = stringResource(Strings.Review.completionUndecided, form.name), isProblem = true)
    }
}

/**
 * The colours the draft is made in, in the user's own order.
 *
 * Zero is a real answer (PLAN 11.6), one is a real answer, and there is no
 * ceiling on how many there may be. The order is the slot PLAN 5.10 stores and
 * PLAN 12.7 draws the name across, so it can be moved. Nothing here makes a
 * colour: the catalogue is the catalogue.
 */
@Composable
private fun ColorSection(
    form: DraftForm,
    isChoosing: Boolean,
    query: String,
    controller: ImportReviewController,
) {
    Text(text = stringResource(Strings.Review.colorsTitle), style = MaterialTheme.typography.labelMedium)
    if (form.colorIds.isEmpty()) {
        NoteLine(text = stringResource(Strings.Review.colorsNone), isProblem = false)
    }
    val duplicate = form.duplicateColorSlots
    val unknown = stringResource(Strings.CellTask.colorUnknown)
    form.colorIds.forEachIndexed { slot, colorId ->
        val color = controller.colors.firstOrNull { it.id == colorId }
        val colorName = color?.canonicalName ?: unknown
        val swatch = color?.let { opaqueColorOf(it.hex) }
        val requester = remember(form.draftId, slot) { FocusRequester() }
        val wanted = controller.focus
        LaunchedEffect(wanted) {
            if (wanted is ReviewFocus.ColorSlot && wanted.slot == slot) {
                requester.requestFocus()
                controller.focusHonoured()
            }
        }
        val isClashing = duplicate != null && (slot == duplicate.first || slot == duplicate.second)
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                val slotLabel = stringResource(Strings.CellTask.colorSlot, slot + 1, colorName)
                Text(
                    text = slotLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (color == null || isClashing) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .focusRequester(requester)
                            .focusable()
                            .focusOutline(PanelShape)
                            .semantics { contentDescription = slotLabel },
                )
            }
            val upLabel = stringResource(Strings.CellTask.colorMoveUp, colorName)
            val downLabel = stringResource(Strings.CellTask.colorMoveDown, colorName)
            val dropLabel = stringResource(Strings.CellTask.colorDrop, colorName)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallAction(upLabel, stringResource(Strings.CellTask.colorMoveUpShort), slot > 0) {
                    controller.moveColorUp(slot)
                }
                SmallAction(
                    downLabel,
                    stringResource(Strings.CellTask.colorMoveDownShort),
                    slot < form.colorIds.lastIndex,
                ) { controller.moveColorDown(slot) }
                SmallAction(dropLabel, stringResource(Strings.CellTask.colorDropShort), true) {
                    controller.removeColorAt(slot)
                }
            }
        }
    }
    if (duplicate != null) {
        NoteLine(
            text = stringResource(Strings.Review.colorsDuplicate, duplicate.first + 1, duplicate.second + 1),
            isProblem = true,
        )
    }

    if (isChoosing) {
        OutlinedTextField(
            value = query,
            onValueChange = controller::editColorQuery,
            enabled = !controller.isSaving,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text(stringResource(Strings.CellTask.colorSearch)) },
            modifier = Modifier.fillMaxWidth(),
        )
        CatalogueList(
            colors = controller.colors.matching(query),
            chosen = form.colorIds,
            enabled = !controller.isSaving,
            emptyQuery = query.isBlank(),
            onChoose = controller::chooseColor,
        )
        TextButton(
            onClick = controller::closeInnermost,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.focusOutline(PanelShape),
        ) {
            Text(text = stringResource(Strings.Review.colorsClose), style = MaterialTheme.typography.labelMedium)
        }
    } else {
        val addLabel = stringResource(Strings.Review.colorsOpen)
        OutlinedButton(
            onClick = controller::openColorChoice,
            enabled = !controller.isSaving,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = addLabel },
        ) {
            Text(text = addLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SmallAction(
    description: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = description },
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

/** The catalogue narrowed by what has been typed, each entry named as well as drawn. */
@Composable
private fun CatalogueList(
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
                .heightIn(max = InnerListHeight)
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
                        .focusOutline(PanelShape)
                        .padding(horizontal = 4.dp, vertical = 3.dp)
                        .semantics {
                            contentDescription = color.canonicalName
                            stateDescription = stateText
                        },
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

/** The catalogue narrowed by a search that folds the four Turkish i letters. */
private fun List<ColorSummary>.matching(query: String): List<ColorSummary> {
    val wanted = query.trim()
    if (wanted.isEmpty()) return this
    val folded =
        dev.pnptracker.domain.rules
            .normalizeColorTerm(wanted)
    return filter {
        dev.pnptracker.domain.rules
            .normalizeColorTerm(it.canonicalName)
            .contains(folded)
    }
}

// -------------------------------------------------------- the confirmation

/**
 * The confirmation step: what would be created, what is stopping it, and the one
 * action that writes it.
 */
@Composable
private fun ConfirmationSection(
    confirmation: ImportConfirmationController,
    controller: ImportReviewController,
    state: ImportReviewState.Content,
    batchId: EntityId,
) {
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Strings.Confirm.sectionTitle),
                style = MaterialTheme.typography.titleMedium,
            )

            when (val confirmState = confirmation.state) {
                ImportConfirmationState.Loading -> BusyRow(stringResource(Strings.Confirm.loading))

                ImportConfirmationState.Unavailable ->
                    Text(
                        text = stringResource(Strings.Confirm.unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                is ImportConfirmationState.Confirmed -> {
                    Text(
                        text = stringResource(Strings.Confirm.doneTitle),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text =
                            stringResource(
                                Strings.Confirm.doneBody,
                                confirmState.result.createdTaskCount,
                                confirmState.result.createdGameCount,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    NoteLine(text = stringResource(Strings.Confirm.readOnly), isProblem = false)
                }

                is ImportConfirmationState.Ready ->
                    ConfirmationBody(
                        summary = confirmState.summary,
                        failure = confirmState.failure,
                        state = state,
                        controller = controller,
                        confirmation = confirmation,
                        onConfirm = { scope.launch { confirmation.confirm(batchId) } },
                    )
            }
        }
    }
}

@Composable
private fun ConfirmationBody(
    summary: ImportConfirmationSummary,
    failure: dev.pnptracker.domain.importconfirm.ImportConfirmationFailure?,
    state: ImportReviewState.Content,
    controller: ImportReviewController,
    confirmation: ImportConfirmationController,
    onConfirm: () -> Unit,
) {
    if (summary.isConfirmed) {
        // Reopened after a confirmation: read only, and it says so.
        NoteLine(text = stringResource(Strings.Confirm.readOnly), isProblem = false)
    } else {
        Text(
            text = stringResource(Strings.Confirm.summary, summary.draftTaskCount, summary.readyTaskCount),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (summary.canConfirm) {
            Text(
                text = stringResource(Strings.Confirm.ready, summary.readyTaskCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        NoteLine(text = stringResource(Strings.Confirm.noGamesCreated), isProblem = false)
    }

    Blockers(summary = summary, state = state, controller = controller)

    if (summary.isStillADraft && summary.needsUnprocessedAcknowledgement) {
        Text(
            text = stringResource(Strings.Confirm.unprocessedWarning, summary.unprocessedBlockCount),
            style = MaterialTheme.typography.bodyMedium,
        )
        val acknowledge = stringResource(Strings.Confirm.unprocessedAcknowledge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = confirmation.hasAcknowledgedUnprocessed,
                enabled = !confirmation.isBusy,
                onCheckedChange = { confirmation.acknowledgeUnprocessed(it) },
                modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = acknowledge },
            )
            Text(text = acknowledge, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (failure != null) {
        NoteLine(text = stringResource(messageOf(failure)), isProblem = true)
    }

    if (confirmation.isBusy) {
        BusyRow(stringResource(Strings.Confirm.running))
    }

    if (summary.isStillADraft) {
        val canAct =
            summary.canConfirm &&
                !confirmation.isBusy &&
                (!summary.needsUnprocessedAcknowledgement || confirmation.hasAcknowledgedUnprocessed)
        val label = stringResource(Strings.Confirm.action)
        Button(
            onClick = { confirmation.ask() },
            enabled = canAct,
            modifier = Modifier.focusOutline(PanelShape).semantics { contentDescription = label },
        ) {
            Text(label)
        }
    }

    if (confirmation.isAsking) {
        ConfirmationDialog(
            taskCount = summary.readyTaskCount,
            isBusy = confirmation.isBusy,
            onDismiss = { confirmation.stopAsking() },
            onConfirm = onConfirm,
        )
    }
}

/**
 * Everything stopping the confirmation, each one a way to get to what is wrong.
 *
 * Pressing an entry takes the user to the cell it is about and puts the keyboard
 * on the draft, because PLAN 17 does not leave a list of problems as a list of
 * things to go and find by hand.
 */
@Composable
private fun Blockers(
    summary: ImportConfirmationSummary,
    state: ImportReviewState.Content,
    controller: ImportReviewController,
) {
    if (summary.problems.isEmpty() && summary.blockProblems.isEmpty()) return
    val requester = remember { FocusRequester() }
    val wanted = controller.focus
    LaunchedEffect(wanted) {
        if (wanted is ReviewFocus.Problems) {
            requester.requestFocus()
            controller.focusHonoured()
        }
    }
    val title = stringResource(Strings.Review.blockersTitle)
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier =
            Modifier
                .focusRequester(requester)
                .focusable()
                .focusOutline(PanelShape)
                .semantics { contentDescription = title },
    )
    val goto = stringResource(Strings.Review.blockerGoto)
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = InnerListHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        summary.problems.forEach { problem ->
            val line =
                stringResource(
                    Strings.Review.blockerDraft,
                    problem.draftTaskName,
                    stringResource(messageOf(problem.failure)),
                )
            BlockerLine(text = line, action = goto) {
                controller.goToProblem(problem.rawImportBlockId, problem.draftTaskId)
            }
        }
        summary.blockProblems.forEach { problem ->
            val line =
                stringResource(
                    Strings.Review.blockerBlock,
                    problem.rowIndex + 1,
                    problem.columnIndex + 1,
                    stringResource(messageOf(problem.failure)),
                )
            BlockerLine(text = line, action = goto) {
                controller.goToProblem(problem.rawImportBlockId, draftId = null)
            }
        }
    }
    // Only cells this import really holds can be reached; a problem about one
    // that has gone is still said, and simply has nowhere to go.
    if (state.workspace.rawBlocks.isEmpty()) {
        NoteLine(text = stringResource(Strings.Review.draftsEmptyHint), isProblem = false)
    }
}

@Composable
private fun BlockerLine(
    text: String,
    action: String,
    onGo: () -> Unit,
) {
    val spoken = "$text — $action"
    Text(
        text = "• $text",
        style = MaterialTheme.typography.labelMedium,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = false, onClick = onGo)
                .focusOutline(PanelShape)
                .padding(horizontal = 2.dp, vertical = 2.dp)
                .semantics { contentDescription = spoken },
    )
}

@Composable
private fun ConfirmationDialog(
    taskCount: Int,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Strings.Confirm.dialogTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Strings.Confirm.dialogBody, taskCount))
                Text(
                    text = stringResource(Strings.Confirm.dialogCancelNote),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        confirmButton = {
            // Disabled while one is in flight, so a second click cannot start another.
            Button(onClick = onConfirm, enabled = !isBusy) {
                Text(stringResource(Strings.Confirm.dialogAccept))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(Strings.Confirm.dialogCancel))
            }
        },
    )
}

@Composable
private fun BusyRow(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Everything one draft says, as one sentence.
 *
 * Said once and in words: the amount, the pool, the colours in the order they
 * will be drawn, and the marks. PLAN 17 does not let a colour be the only thing
 * carrying a meaning, so the names are read out and the swatches beside them are
 * the second way of saying the same thing.
 */
@Composable
private fun ReviewDraftTask.spokenSummary(
    target: TargetCellChoice?,
    catalogue: List<ColorSummary>,
): String {
    val unknown = stringResource(Strings.CellTask.colorUnknown)
    val parts =
        buildList {
            add(name)
            add(target?.label() ?: stringResource(Strings.Aim.none))
            add(
                if (requiredQuantity == null) {
                    stringResource(Strings.Review.draftQuantityUnknown)
                } else {
                    stringResource(Strings.Review.draftQuantity, requiredQuantity)
                },
            )
            add(
                if (colorIds.isEmpty()) {
                    stringResource(Strings.Review.draftColorsNone)
                } else {
                    stringResource(
                        Strings.Review.draftColors,
                        colorIds.joinToString(", ") { id ->
                            catalogue.firstOrNull { it.id == id }?.canonicalName ?: unknown
                        },
                    )
                },
            )
            if (isMissing) add(stringResource(Strings.Review.flagMissing))
            if (isBorrowed) add(stringResource(Strings.Review.flagBorrowed))
            if (needsInfo) add(stringResource(Strings.Review.flagNeedsInfo))
            if (needsClassification) add(stringResource(Strings.Review.flagNeedsClassification))
            when (completionHint) {
                HintDecision.ACCEPTED -> add(stringResource(Strings.Review.completionAccepted))
                HintDecision.REJECTED -> add(stringResource(Strings.Review.completionRejected))
                HintDecision.PENDING -> add(stringResource(Strings.Review.completionUndecided, name))
                HintDecision.NONE -> Unit
            }
            notes?.takeIf { it.isNotBlank() }?.let { add(stringResource(Strings.Review.draftNotes, it)) }
        }
    return stringResource(Strings.Review.draftAccessibility, parts.joinToString(", "))
}
