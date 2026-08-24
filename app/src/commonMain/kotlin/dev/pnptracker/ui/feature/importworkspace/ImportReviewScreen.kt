package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.importconfirm.ImportConfirmationSummary
import dev.pnptracker.domain.importconfirm.TargetCellChoice
import dev.pnptracker.domain.importreview.ReviewDraftTask
import dev.pnptracker.domain.importreview.ReviewRawBlock
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellPoolChoice
import dev.pnptracker.domain.tasks.suitsPool
import dev.pnptracker.domain.tasks.trackingModesOf
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.columnNameOf
import dev.pnptracker.ui.feature.importreview.nameOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Reviewing one saved import: the cells the file held on the left, the task
 * drafts made from them on the right.
 *
 * The left pane shows the cell text exactly as the file had it — line breaks,
 * `**` markers and spacing included — which is why it is set in a monospaced
 * face and never trimmed for display.
 */
@Composable
fun ImportReviewScreen(
    controller: ImportReviewController,
    confirmation: ImportConfirmationController,
    batchId: EntityId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(batchId) { controller.observe(batchId) }
    LaunchedEffect(batchId) { confirmation.refresh(batchId) }
    LaunchedEffect(Unit) { confirmation.observeTargetCells() }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                ContentPanes(
                    state = state,
                    confirmation = confirmation,
                    batchId = batchId,
                    composer = controller.composer,
                    isSaving = controller.isSaving,
                    onSelect = { blockId -> controller.select(blockId) },
                    onToggleProcessed = { block ->
                        scope.launch { controller.setProcessed(block.id, !block.isProcessed) }
                    },
                    onStartDraft = { block -> controller.startDraft(block.id) },
                    onEditDraftName = { name -> controller.editDraftName(name) },
                    onSaveDraft = { scope.launch { controller.saveDraft() } },
                    onDiscardDraft = { controller.cancelDraft() },
                )
        }
    }
}

@Composable
private fun ContentPanes(
    state: ImportReviewState.Content,
    confirmation: ImportConfirmationController,
    batchId: EntityId,
    composer: ImportReviewController.DraftComposer?,
    isSaving: Boolean,
    onSelect: (EntityId) -> Unit,
    onToggleProcessed: (ReviewRawBlock) -> Unit,
    onStartDraft: (ReviewRawBlock) -> Unit,
    onEditDraftName: (String) -> Unit,
    onSaveDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
) {
    val workspace = state.workspace

    Text(
        text = stringResource(Strings.Import.fileLabel, workspace.fileName),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(Strings.Review.progress, workspace.processedBlockCount, workspace.rawBlockCount),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (state.failure != null) {
        Text(
            text = stringResource(Strings.Review.couldNotSave),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    ConfirmationSection(
        confirmation = confirmation,
        batchId = batchId,
        drafts = workspace.draftTasks,
    )

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        RawBlockPane(
            state = state,
            isEditable = state.canEdit,
            isSaving = isSaving,
            onSelect = onSelect,
            onToggleProcessed = onToggleProcessed,
            onStartDraft = onStartDraft,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        DraftPane(
            state = state,
            confirmation = confirmation,
            batchId = batchId,
            composer = composer,
            isSaving = isSaving,
            onEditDraftName = onEditDraftName,
            onSaveDraft = onSaveDraft,
            onDiscardDraft = onDiscardDraft,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun RawBlockPane(
    state: ImportReviewState.Content,
    isEditable: Boolean,
    isSaving: Boolean,
    onSelect: (EntityId) -> Unit,
    onToggleProcessed: (ReviewRawBlock) -> Unit,
    onStartDraft: (ReviewRawBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Strings.Review.rawBlocksTitle),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.workspace.rawBlocks, key = { it.id.toString() }) { block ->
                RawBlockRow(
                    block = block,
                    draftCount = state.workspace.draftsOf(block.id).size,
                    isSelected = block.id == state.selectedBlockId,
                    isEditable = isEditable,
                    isSaving = isSaving,
                    onSelect = { onSelect(block.id) },
                    onToggleProcessed = { onToggleProcessed(block) },
                    onStartDraft = { onStartDraft(block) },
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
    isSaving: Boolean,
    onSelect: () -> Unit,
    onToggleProcessed: () -> Unit,
    onStartDraft: () -> Unit,
) {
    val selectCell = stringResource(Strings.Review.selectCellAccessibility)
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
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .selectable(selected = isSelected, onClick = onSelect)
                .semantics { contentDescription = selectCell },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
            }

            // The cell text, byte for byte as the file had it.
            Text(
                text = block.rawText,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )

            if (block.gameCompletionHint == HintDecision.PENDING) {
                Text(
                    text = stringResource(Strings.Review.greenHintPending),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (draftCount > 0) {
                Text(
                    text = stringResource(Strings.Review.draftCount, draftCount),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            // A confirmed import is evidence, not a workspace: PLAN 11.4.3 makes it
            // read only, so the two actions that write are not offered at all. The
            // data layer refuses them too, but a button that is going to be refused
            // should never have been on screen.
            if (isEditable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onToggleProcessed, enabled = !isSaving) {
                        Text(
                            stringResource(
                                if (block.isProcessed) {
                                    Strings.Review.markUnprocessed
                                } else {
                                    Strings.Review.markProcessed
                                },
                            ),
                        )
                    }
                    if (isSelected) {
                        Button(onClick = onStartDraft, enabled = !isSaving) {
                            Text(stringResource(Strings.Review.createDraft))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftPane(
    state: ImportReviewState.Content,
    confirmation: ImportConfirmationController,
    batchId: EntityId,
    composer: ImportReviewController.DraftComposer?,
    isSaving: Boolean,
    onEditDraftName: (String) -> Unit,
    onSaveDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // A form still open when the import was confirmed would otherwise keep a
        // save button pointing at a write the data layer now refuses.
        if (composer != null && state.canEdit) {
            DraftComposerCard(
                composer = composer,
                sourceBlock = state.workspace.blockOrNull(composer.blockId),
                isSaving = isSaving,
                onEditDraftName = onEditDraftName,
                onSaveDraft = onSaveDraft,
                onDiscardDraft = onDiscardDraft,
            )
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
                        confirmation = confirmation,
                        batchId = batchId,
                        isEditable = state.canEdit,
                    )
                }
            }
        }

        HorizontalDivider()
        Text(
            text = stringResource(Strings.Review.noRealRecords),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun DraftComposerCard(
    composer: ImportReviewController.DraftComposer,
    sourceBlock: ReviewRawBlock?,
    isSaving: Boolean,
    onEditDraftName: (String) -> Unit,
    onSaveDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Strings.Review.draftFormTitle),
                style = MaterialTheme.typography.titleSmall,
            )
            if (sourceBlock != null) {
                Text(
                    text =
                        stringResource(
                            Strings.Review.draftSource,
                            sourceBlock.rowIndex + 1,
                            sourceBlock.columnIndex + 1,
                        ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            OutlinedTextField(
                value = composer.name,
                onValueChange = onEditDraftName,
                label = { Text(stringResource(Strings.Review.draftNameLabel)) },
                isError = !composer.canSave,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!composer.canSave) {
                Text(
                    text = stringResource(Strings.Review.draftNameRequired),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(Strings.Review.draftOnlyNote),
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveDraft, enabled = composer.canSave && !isSaving) {
                    Text(stringResource(Strings.Review.draftSave))
                }
                OutlinedButton(onClick = onDiscardDraft, enabled = !isSaving) {
                    Text(stringResource(Strings.Review.draftDiscard))
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
        Text(
            text = stringResource(Strings.Review.draftsEmpty),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(Strings.Review.draftsEmptyHint),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DraftRow(
    draft: ReviewDraftTask,
    confirmation: ImportConfirmationController,
    batchId: EntityId,
    isEditable: Boolean,
) {
    val scope = rememberCoroutineScope()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = draft.name, style = MaterialTheme.typography.bodyLarge)
            if (draft.completionHint == HintDecision.PENDING) {
                Text(
                    text = stringResource(Strings.Review.completionHintPending),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            val target = confirmation.targetCells.firstOrNull { it.cellId == draft.targetCellId }
            Text(
                text =
                    if (target == null) {
                        stringResource(Strings.Aim.none)
                    } else {
                        stringResource(
                            Strings.Aim.cellLabel,
                            target.gameName,
                            stringResource(columnNameOf(target.columnType)),
                        )
                    },
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (target == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )

            if (draft.materializedTaskId != null) {
                // A confirmed draft is evidence now, not something to edit.
                Text(
                    text = stringResource(Strings.Aim.materialized),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (isEditable) {
                DraftAiming(
                    draft = draft,
                    targetCells = confirmation.targetCells,
                    isBusy = confirmation.isBusy,
                    onAim = { cellId, poolType, trackingMode ->
                        scope.launch {
                            confirmation.aim(batchId, draft.id, cellId, poolType, trackingMode)
                        }
                    },
                )
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

/**
 * Choosing the cell, the pool and — only when the pool leaves a choice — the
 * tracking mode for one draft.
 *
 * A pool that allows exactly one mode sets that mode outright rather than
 * offering a list of one, but the value is still written explicitly; nothing
 * later guesses it.
 */
@Composable
private fun DraftAiming(
    draft: ReviewDraftTask,
    targetCells: List<TargetCellChoice>,
    isBusy: Boolean,
    onAim: (EntityId?, PoolType?, TrackingMode?) -> Unit,
) {
    if (targetCells.isEmpty()) {
        Text(
            text = stringResource(Strings.Aim.noCells),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    // What the draft already says, read back through the one rule that keeps a
    // cell and a pool from contradicting each other. Both chip rows below change
    // it through that rule, so the pair sent to the database is always one it
    // will take — the same way the game's task form does it.
    val aimedAt = targetCells.firstOrNull { it.cellId == draft.targetCellId }
    val choice =
        CellPoolChoice()
            .let { start -> aimedAt?.let { start.withCell(it.cellId, it.columnType) } ?: start }
            .let { withCell -> draft.selectedPoolType?.let(withCell::withPool) ?: withCell }
            .let { withPool -> draft.selectedTrackingMode?.let(withPool::withTracking) ?: withPool }
    val poolType = choice.poolType
    val trackingMode = choice.trackingMode

    Text(text = stringResource(Strings.Aim.chooseCell), style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        targetCells.filter { it.columnType.suitsPool(poolType) }.forEach { target ->
            val aimed = choice.withCell(target.cellId, target.columnType)
            FilterChip(
                selected = target.cellId == choice.cellId,
                enabled = !isBusy,
                onClick = { onAim(aimed.cellId, aimed.poolType, aimed.trackingMode) },
                label = {
                    Text(
                        stringResource(
                            Strings.Aim.cellLabel,
                            target.gameName,
                            stringResource(columnNameOf(target.columnType)),
                        ),
                    )
                },
            )
        }
    }

    Text(text = stringResource(Strings.Aim.choosePool), style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PoolType.entries.forEach { pool ->
            // Changing the pool drops a mode it does not allow, and a cell it
            // cannot be written in.
            val picked = choice.withPool(pool)
            FilterChip(
                selected = pool == poolType,
                enabled = !isBusy,
                onClick = { onAim(picked.cellId, picked.poolType, picked.trackingMode) },
                label = { Text(stringResource(labelOf(pool))) },
            )
        }
    }

    val modes = poolType?.let(::trackingModesOf).orEmpty()
    if (modes.size > 1) {
        Text(text = stringResource(Strings.Aim.chooseTracking), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            modes.forEach { mode ->
                FilterChip(
                    selected = mode == trackingMode,
                    enabled = !isBusy,
                    onClick = { onAim(choice.cellId, poolType, choice.withTracking(mode).trackingMode) },
                    label = { Text(stringResource(labelOf(mode))) },
                )
            }
        }
    }
}

/**
 * The confirmation step: what would be created, what is stopping it, and the
 * one action that writes it.
 */
@Composable
private fun ConfirmationSection(
    confirmation: ImportConfirmationController,
    batchId: EntityId,
    drafts: List<ReviewDraftTask>,
) {
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Strings.Confirm.sectionTitle),
                style = MaterialTheme.typography.titleMedium,
            )

            when (val state = confirmation.state) {
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
                                state.result.createdTaskCount,
                                state.result.createdGameCount,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(Strings.Confirm.readOnly),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                is ImportConfirmationState.Ready ->
                    ConfirmationBody(
                        summary = state.summary,
                        failure = state.failure,
                        drafts = drafts,
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
    drafts: List<ReviewDraftTask>,
    confirmation: ImportConfirmationController,
    onConfirm: () -> Unit,
) {
    if (summary.isConfirmed) {
        // Reopened after a confirmation: read only, and it says so.
        Text(
            text = stringResource(Strings.Confirm.readOnly),
            style = MaterialTheme.typography.bodyMedium,
        )
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
        Text(
            text = stringResource(Strings.Confirm.noGamesCreated),
            style = MaterialTheme.typography.labelMedium,
        )
    }

    if (summary.problems.isNotEmpty()) {
        Text(
            text = stringResource(Strings.Confirm.problemsTitle),
            style = MaterialTheme.typography.titleSmall,
        )
        summary.problems.forEach { problem ->
            Text(
                // The draft's own name, which the user is already looking at.
                text = "• ${problem.draftTaskName}: ${stringResource(messageOf(problem.failure))}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }

    if (summary.isStillADraft && summary.needsUnprocessedAcknowledgement) {
        Text(
            text = stringResource(Strings.Confirm.unprocessedWarning, summary.unprocessedBlockCount),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = confirmation.hasAcknowledgedUnprocessed,
                enabled = !confirmation.isBusy,
                onCheckedChange = { confirmation.acknowledgeUnprocessed(it) },
            )
            Text(
                text = stringResource(Strings.Confirm.unprocessedAcknowledge),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (failure != null) {
        Text(
            text = stringResource(messageOf(failure)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (confirmation.isBusy) {
        BusyRow(stringResource(Strings.Confirm.running))
    }

    if (summary.isStillADraft) {
        val canAct =
            summary.canConfirm &&
                !confirmation.isBusy &&
                (!summary.needsUnprocessedAcknowledgement || confirmation.hasAcknowledgedUnprocessed)
        Button(onClick = { confirmation.ask() }, enabled = canAct) {
            Text(stringResource(Strings.Confirm.action))
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

    // Nothing above this line writes anything; only the dialog's accept does.
    if (drafts.isEmpty()) {
        Text(
            text = stringResource(Strings.Review.draftsEmptyHint),
            style = MaterialTheme.typography.labelMedium,
        )
    }
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
