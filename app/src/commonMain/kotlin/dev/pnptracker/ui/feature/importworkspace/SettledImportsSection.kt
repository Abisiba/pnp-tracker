package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pnptracker.data.repository.SettledImport
import dev.pnptracker.domain.importrollback.BlockedCell
import dev.pnptracker.domain.importrollback.BlockedTask
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.columnNameOf
import dev.pnptracker.ui.feature.tasks.NoteLine
import dev.pnptracker.ui.feature.tasks.focusOutline
import dev.pnptracker.ui.importStatusNameOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val CardShape = RoundedCornerShape(8.dp)

/** Tall enough to read, short enough that the buttons under it stay on screen. */
private val BlockedListHeight = 180.dp

/**
 * The imports that have already been confirmed, and the way back out of one.
 *
 * A rollback is offered here rather than inside the review screen because the
 * review screen is about a file being read, and this is about one that was read
 * some time ago: PLAN 11.4.3 makes a confirmed import read only, and the only
 * thing left to do with it is the one thing PLAN 11.4.4 allows.
 *
 * Nothing on this screen decides whether a rollback is safe. The button asks the
 * engine, the engine answers twice — once for the confirmation the user reads
 * and once inside the transaction — and what is drawn here is whichever of those
 * two answers came back.
 */
@Composable
fun SettledImportsSection(
    controller: ImportRollbackController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { controller.observeSettledImports() }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Strings.Rollback.sectionTitle),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(Strings.Rollback.sectionHint),
            style = MaterialTheme.typography.bodyMedium,
        )

        when (val state = controller.imports) {
            SettledImportsState.Loading -> BusyLine(stringResource(Strings.Rollback.sectionLoading))

            SettledImportsState.Empty ->
                Text(
                    text = stringResource(Strings.Rollback.sectionEmpty),
                    style = MaterialTheme.typography.bodyMedium,
                )

            SettledImportsState.Unreadable ->
                NoteLine(text = stringResource(Strings.Rollback.sectionUnreadable), isProblem = true)

            is SettledImportsState.Ready ->
                SettledList(
                    imports = state.imports,
                    controller = controller,
                    onAsk = { batchId -> scope.launch { controller.ask(batchId) } },
                )
        }
    }

    RollbackSurface(
        flow = controller.flow,
        onTakeBack = { scope.launch { controller.takeBack() } },
        onClose = { controller.close() },
    )
}

@Composable
private fun SettledList(
    imports: List<SettledImport>,
    controller: ImportRollbackController,
    onAsk: (EntityId) -> Unit,
) {
    val listLabel = stringResource(Strings.Rollback.listLabel)
    Column(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = listLabel },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        imports.forEach { settled ->
            SettledRow(
                settled = settled,
                controller = controller,
                onAsk = { onAsk(settled.batchId) },
            )
        }
    }
}

/**
 * One confirmed import, and the button when there is one.
 *
 * A row that was taken back keeps its place and says so. PLAN 11.4.4 has the
 * user re-import the same file as a new batch rather than reapply this one, and
 * a row that vanished would leave them wondering whether it had.
 */
@Composable
private fun SettledRow(
    settled: SettledImport,
    controller: ImportRollbackController,
    onAsk: () -> Unit,
) {
    // Where the keyboard goes back to once the surface over this row closes.
    val requester = remember(settled.batchId) { FocusRequester() }
    val flow = controller.flow
    LaunchedEffect(flow, settled.canBeTakenBack) {
        if (flow !is RollbackFlowState.Closed || controller.lastAsked != settled.batchId) return@LaunchedEffect
        // A row that was taken back has no button any more, so there is nothing
        // to put the keyboard on; the token is spent either way, so a later
        // rollback of another import cannot pull the focus back here.
        if (settled.canBeTakenBack) requester.requestFocus()
        controller.focusHonoured()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Strings.Rollback.entry, settled.fileName, settled.sheetName),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text =
                    stringResource(
                        Strings.Rollback.entryDetail,
                        settled.createdTaskCount,
                        stringResource(importStatusNameOf(settled.status)),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (settled.canBeTakenBack) {
                val label = stringResource(Strings.Rollback.actionDescription, settled.fileName)
                Button(
                    onClick = onAsk,
                    // Off while any surface is open, so a second press or a
                    // repeated Enter cannot start a second reading.
                    enabled = controller.flow is RollbackFlowState.Closed,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    modifier =
                        Modifier
                            .focusRequester(requester)
                            .focusOutline(CardShape)
                            .semantics { contentDescription = label },
                ) {
                    Text(stringResource(Strings.Rollback.action))
                }
            }
        }
    }
}

/**
 * Whatever the open rollback has to say, drawn over the screen.
 *
 * One dialog for every step of it, so the user's attention stays in one place
 * from the question to the answer, and closing is always the same gesture.
 */
@Composable
private fun RollbackSurface(
    flow: RollbackFlowState,
    onTakeBack: () -> Unit,
    onClose: () -> Unit,
) {
    when (flow) {
        RollbackFlowState.Closed -> Unit

        is RollbackFlowState.Asking ->
            // No dismiss while the preview is being read: there would be nowhere
            // for its answer to land.
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(Strings.Rollback.dialogTitle)) },
                text = { BusyLine(stringResource(Strings.Rollback.previewLoading)) },
                confirmButton = {},
            )

        is RollbackFlowState.Offered ->
            OfferDialog(
                taskCount = flow.preview.taskCount,
                cellCount = flow.preview.cellCount,
                gameCount = flow.preview.gameCount,
                isBusy = false,
                blockage = null,
                onTakeBack = onTakeBack,
                onClose = onClose,
            )

        is RollbackFlowState.TakingBack ->
            OfferDialog(
                taskCount = flow.preview.taskCount,
                cellCount = flow.preview.cellCount,
                gameCount = flow.preview.gameCount,
                isBusy = true,
                blockage = null,
                onTakeBack = onTakeBack,
                onClose = onClose,
            )

        is RollbackFlowState.Refused ->
            // The panel stays where it was, with what the user was reading, and
            // the reason it did not happen underneath. Nothing was written.
            OfferDialog(
                taskCount = flow.preview.taskCount,
                cellCount = flow.preview.cellCount,
                gameCount = flow.preview.gameCount,
                isBusy = false,
                blockage = flow.blockage,
                onTakeBack = onTakeBack,
                onClose = onClose,
            )

        is RollbackFlowState.Blocked -> BlockedDialog(blockage = flow.blockage, onClose = onClose)

        is RollbackFlowState.TakenBack ->
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(stringResource(Strings.Rollback.doneTitle)) },
                text = {
                    Text(
                        stringResource(
                            Strings.Rollback.doneBody,
                            flow.result.removedTaskCount,
                            flow.result.restoredCellCount,
                        ),
                    )
                },
                confirmButton = {
                    val close = stringResource(Strings.Rollback.close)
                    Button(
                        onClick = onClose,
                        modifier = Modifier.focusOutline(CardShape).semantics { contentDescription = close },
                    ) {
                        Text(close)
                    }
                },
                modifier = Modifier.closingOnEscape(onClose),
            )
    }
}

/**
 * The question PLAN 11.4.4 wants asked before anything is taken back.
 *
 * It says how much would go, that it is all of it or none, that the completion
 * marks are the user's own and stay, and that there is no way back afterwards.
 */
@Composable
private fun OfferDialog(
    taskCount: Int,
    cellCount: Int,
    gameCount: Int,
    isBusy: Boolean,
    blockage: RollbackBlockage?,
    onTakeBack: () -> Unit,
    onClose: () -> Unit,
) {
    val accept = stringResource(Strings.Rollback.dialogAccept)
    val cancel = stringResource(Strings.Rollback.dialogCancel)
    // The keyboard starts on the way out rather than on the destructive action,
    // so a stray Enter on a dialog nobody has read yet cancels instead of
    // removing anything. Asked for after a frame has passed, because the dialog
    // is drawn in a layer of its own and there is nothing to focus until it is
    // really there.
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requester.requestFocus()
    }

    AlertDialog(
        // A click outside cannot abandon a transaction that is already running.
        onDismissRequest = { if (!isBusy) onClose() },
        title = { Text(stringResource(Strings.Rollback.dialogTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Strings.Rollback.dialogBody, taskCount))
                Text(stringResource(Strings.Rollback.dialogScope), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(Strings.Rollback.dialogCells, cellCount, gameCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                NoteLine(text = stringResource(Strings.Rollback.dialogCompletion), isProblem = false)
                Text(
                    text = stringResource(Strings.Rollback.dialogIrreversible),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (isBusy) BusyLine(stringResource(Strings.Rollback.running))
                if (blockage != null) Blockage(blockage)
            }
        },
        confirmButton = {
            Button(
                onClick = onTakeBack,
                enabled = !isBusy,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                modifier = Modifier.focusOutline(CardShape).semantics { contentDescription = accept },
            ) {
                Text(accept)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onClose,
                enabled = !isBusy,
                modifier =
                    Modifier
                        .focusRequester(requester)
                        .focusOutline(CardShape)
                        .semantics { contentDescription = cancel },
            ) {
                Text(cancel)
            }
        },
        // Not while a transaction is running, for the same reason a click
        // outside cannot end one.
        modifier = Modifier.closingOnEscape(onClose.takeIf { !isBusy }),
    )
}

/**
 * Why an import cannot be taken back, with no way to do it anyway.
 *
 * There is deliberately no "remove the safe ones" here: PLAN 11.4.4 has one
 * conflict stop the whole operation, and offering half of it would be offering
 * something the engine will not do.
 */
@Composable
private fun BlockedDialog(
    blockage: RollbackBlockage,
    onClose: () -> Unit,
) {
    val close = stringResource(Strings.Rollback.close)
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(Strings.Rollback.blockedTitle)) },
        text = { Blockage(blockage) },
        confirmButton = {
            Button(
                onClick = onClose,
                modifier =
                    Modifier
                        .focusRequester(requester)
                        .focusOutline(CardShape)
                        .semantics { contentDescription = close },
            ) {
                Text(close)
            }
        },
        modifier = Modifier.closingOnEscape(onClose),
    )
}

/**
 * Escape leaves the innermost surface, which for these is the dialog itself.
 *
 * On the dialog rather than on the section under it: the key travels to whatever
 * holds the keyboard, and while a dialog is open that is something inside the
 * dialog's own layer, where a handler on the section behind it is never
 * consulted. A null [onEscape] means there is nothing to leave yet — the key is
 * left alone rather than swallowed.
 */
private fun Modifier.closingOnEscape(onEscape: (() -> Unit)?): Modifier =
    onPreviewKeyEvent { event ->
        if (onEscape != null && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
            onEscape()
            true
        } else {
            false
        }
    }

/**
 * The refusal in words, and what stood in the way if anything was named.
 *
 * The lists scroll on their own, because an import of forty-two blocked tasks
 * would otherwise push the only button off the bottom of the dialog.
 */
@Composable
private fun Blockage(blockage: RollbackBlockage) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NoteLine(text = stringResource(messageOf(blockage.failure)), isProblem = true)

        if (blockage.blockedTasks.isNotEmpty()) {
            Text(
                text = stringResource(Strings.Rollback.blockedTasksTitle),
                style = MaterialTheme.typography.titleSmall,
            )
            ScrollingList {
                blockage.blockedTasks.forEach { BlockedTaskLine(it) }
            }
        }

        if (blockage.blockedCells.isNotEmpty()) {
            Text(
                text = stringResource(Strings.Rollback.blockedCellsTitle),
                style = MaterialTheme.typography.titleSmall,
            )
            ScrollingList {
                blockage.blockedCells.forEach { BlockedCellLine(it) }
            }
        }
    }
}

@Composable
private fun ScrollingList(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = BlockedListHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content()
    }
}

@Composable
private fun BlockedTaskLine(blocked: BlockedTask) {
    val line = stringResource(Strings.Rollback.blockedTask, blocked.taskName, stringResource(reasonOf(blocked.obstacle)))
    Text(
        text = "• $line",
        style = MaterialTheme.typography.labelMedium,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = line },
    )
}

/**
 * One cell in the way, named by its game and its column.
 *
 * Both are read from the row the engine handed over, and both can be missing —
 * a cell whose game was deleted has no name to give. It is said as such rather
 * than left blank, because a line with a hole in it reads as a defect.
 */
@Composable
private fun BlockedCellLine(blocked: BlockedCell) {
    val game = blocked.gameName ?: stringResource(Strings.Rollback.blockedCellUnknownGame)
    val column =
        blocked.columnType?.let { stringResource(columnNameOf(it)) }
            ?: stringResource(Strings.Rollback.blockedCellUnknownColumn)
    val line = stringResource(Strings.Rollback.blockedCell, game, column, stringResource(reasonOf(blocked.obstacle)))
    Text(
        text = "• $line",
        style = MaterialTheme.typography.labelMedium,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = line },
    )
}

@Composable
private fun BusyLine(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
