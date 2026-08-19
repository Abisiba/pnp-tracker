package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.pnptracker.domain.importreview.ReviewDraftTask
import dev.pnptracker.domain.importreview.ReviewRawBlock
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.ui.Strings
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
    batchId: EntityId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(batchId) { controller.observe(batchId) }

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
                    isSaving = controller.isSaving,
                    onSelect = { blockId -> controller.select(blockId) },
                    onToggleProcessed = { block ->
                        scope.launch { controller.setProcessed(block.id, !block.isProcessed) }
                    },
                )
        }
    }
}

@Composable
private fun ContentPanes(
    state: ImportReviewState.Content,
    isSaving: Boolean,
    onSelect: (EntityId) -> Unit,
    onToggleProcessed: (ReviewRawBlock) -> Unit,
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

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        RawBlockPane(
            state = state,
            isSaving = isSaving,
            onSelect = onSelect,
            onToggleProcessed = onToggleProcessed,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        DraftPane(
            state = state,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun RawBlockPane(
    state: ImportReviewState.Content,
    isSaving: Boolean,
    onSelect: (EntityId) -> Unit,
    onToggleProcessed: (ReviewRawBlock) -> Unit,
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
                    isSaving = isSaving,
                    onSelect = { onSelect(block.id) },
                    onToggleProcessed = { onToggleProcessed(block) },
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
    isSaving: Boolean,
    onSelect: () -> Unit,
    onToggleProcessed: () -> Unit,
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

            OutlinedButton(onClick = onToggleProcessed, enabled = !isSaving) {
                Text(
                    stringResource(
                        if (block.isProcessed) Strings.Review.markUnprocessed else Strings.Review.markProcessed,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DraftPane(
    state: ImportReviewState.Content,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                items(drafts, key = { it.id.toString() }) { draft -> DraftRow(draft) }
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
private fun DraftRow(draft: ReviewDraftTask) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = draft.name, style = MaterialTheme.typography.bodyLarge)
            if (draft.completionHint == HintDecision.PENDING) {
                Text(
                    text = stringResource(Strings.Review.completionHintPending),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
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
