package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import dev.pnptracker.data.repository.UnfinishedImport
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.feature.tasks.NoteLine
import dev.pnptracker.ui.feature.tasks.focusOutline
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val CardShape = RoundedCornerShape(8.dp)

/**
 * The imports the user has not confirmed: continuing one, and removing one.
 *
 * Two lists on one screen, as PLAN 11.4.5 has it. Drafts whose records agree
 * can be continued in the review screen or removed; drafts whose records
 * contradict each other are shown apart, under a warning, with removal as the
 * only thing offered — they cannot be opened and cannot be confirmed.
 *
 * Nothing here decides anything about a draft. Which list a row is in comes
 * from the classifier; whether it may be opened is asked again at the moment of
 * opening; whether it may be removed is decided inside the removal's own
 * transaction, and what is shown afterwards is that transaction's answer.
 *
 * [onOpen] is called only for a draft that has just passed that fresh check.
 */
@Composable
fun UnfinishedImportsSection(
    controller: UnfinishedImportsController,
    onOpen: (EntityId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { controller.observeUnfinishedImports() }

    val readyToOpen = controller.readyToOpen
    LaunchedEffect(readyToOpen) {
        if (readyToOpen == null) return@LaunchedEffect
        controller.openHonoured()
        onOpen(readyToOpen)
    }

    when (val list = controller.list) {
        // Nothing to say before the first reading, and nothing at all when there
        // are no drafts: the section has always stayed out of the way then.
        UnfinishedImportsState.Loading -> Unit

        UnfinishedImportsState.Unreadable ->
            Column(modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)) {
                Text(stringResource(Strings.Review.resumableTitle), style = MaterialTheme.typography.titleMedium)
                NoteLine(text = stringResource(Strings.Unfinished.unreadable), isProblem = true)
            }

        is UnfinishedImportsState.Ready ->
            if (!list.isEmpty || controller.opening !is OpenAttempt.Idle) {
                Column(
                    modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpenAttemptLine(controller.opening)
                    if (list.sound.isNotEmpty()) {
                        SoundDrafts(
                            rows = list.sound,
                            controller = controller,
                            onOpen = { batchId -> scope.launch { controller.open(batchId) } },
                        )
                    }
                    if (list.contradicting.isNotEmpty()) {
                        ContradictingDrafts(rows = list.contradicting, controller = controller)
                    }
                }
            }
    }

    RemovalSurface(
        flow = controller.removal,
        onRemove = { scope.launch { controller.remove() } },
        onClose = { controller.close() },
    )
}

@Composable
private fun SoundDrafts(
    rows: List<UnfinishedImport>,
    controller: UnfinishedImportsController,
    onOpen: (EntityId) -> Unit,
) {
    Text(stringResource(Strings.Review.resumableTitle), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(Strings.Unfinished.hint), style = MaterialTheme.typography.bodyMedium)
    val label = stringResource(Strings.Unfinished.listLabel)
    Column(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row -> DraftRow(row = row, controller = controller, onOpen = { onOpen(row.batchId) }) }
    }
}

@Composable
private fun ContradictingDrafts(
    rows: List<UnfinishedImport>,
    controller: UnfinishedImportsController,
) {
    Text(stringResource(Strings.Unfinished.contradictingTitle), style = MaterialTheme.typography.titleMedium)
    NoteLine(text = stringResource(Strings.Unfinished.contradictingHint), isProblem = true)
    val label = stringResource(Strings.Unfinished.contradictingListLabel)
    Column(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // No way in: a contradicting draft cannot be opened or confirmed.
        rows.forEach { row -> DraftRow(row = row, controller = controller, onOpen = null) }
    }
}

/**
 * One unfinished import, and its buttons.
 *
 * Both buttons are off while any surface is open or a draft is being checked,
 * so a second click or a repeated Enter cannot start a second check or open a
 * second question.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftRow(
    row: UnfinishedImport,
    controller: UnfinishedImportsController,
    onOpen: (() -> Unit)?,
) {
    val firstButton = remember(row.batchId) { FocusRequester() }
    val removeButton = remember(row.batchId) { FocusRequester() }
    val focus = controller.focus
    LaunchedEffect(focus, controller.removal) {
        if (focus == null || focus.batchId != row.batchId || controller.removal !is RemovalFlowState.Closed) return@LaunchedEffect
        // A frame first: the dialog that just closed takes its layer with it, and
        // the button is only focusable again once that has happened.
        withFrameNanos { }
        when (focus.target) {
            RowFocusTarget.REMOVE -> removeButton.requestFocus()
            RowFocusTarget.FIRST -> (if (onOpen != null) firstButton else removeButton).requestFocus()
        }
        controller.focusHonoured()
    }
    val idle = controller.removal is RemovalFlowState.Closed && controller.opening !is OpenAttempt.Checking

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Strings.Review.resumableEntry, row.fileName, row.sheetName),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (row.mayBeHeldByRecords) NoteLine(text = stringResource(Strings.Unfinished.heldNote), isProblem = false)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onOpen != null) {
                    val openLabel = stringResource(Strings.Unfinished.openDescription, row.fileName)
                    Button(
                        onClick = onOpen,
                        enabled = idle,
                        modifier =
                            Modifier
                                .focusRequester(firstButton)
                                .focusOutline(CardShape)
                                .semantics { contentDescription = openLabel },
                    ) {
                        Text(stringResource(Strings.Unfinished.open))
                    }
                }
                val removeLabel = stringResource(Strings.Unfinished.removeDescription, row.fileName)
                OutlinedButton(
                    onClick = { controller.askToRemove(row.batchId) },
                    enabled = idle,
                    modifier =
                        Modifier
                            .focusRequester(removeButton)
                            .focusOutline(CardShape)
                            .semantics { contentDescription = removeLabel },
                ) {
                    Text(stringResource(Strings.Unfinished.remove))
                }
            }
        }
    }
}

/** What the last attempt at opening a draft came to, when it came to anything. */
@Composable
private fun OpenAttemptLine(opening: OpenAttempt) {
    when (opening) {
        OpenAttempt.Idle -> Unit
        is OpenAttempt.Checking -> BusyLine(stringResource(Strings.Unfinished.checking))
        is OpenAttempt.Refused -> NoteLine(text = stringResource(sentenceOf(opening.refusal), opening.entry.fileName), isProblem = true)
    }
}

/**
 * Whatever the open removal has to say, drawn over the screen.
 *
 * One dialog for every step of it, so attention stays in one place from the
 * question to the answer.
 */
@Composable
private fun RemovalSurface(
    flow: RemovalFlowState,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    when (flow) {
        RemovalFlowState.Closed -> Unit
        is RemovalFlowState.Offered -> RemovalQuestion(flow.entry, isBusy = false, onRemove = onRemove, onClose = onClose)
        is RemovalFlowState.Removing -> RemovalQuestion(flow.entry, isBusy = true, onRemove = onRemove, onClose = onClose)
        is RemovalFlowState.Removed ->
            AnswerDialog(
                title = stringResource(Strings.Unfinished.removedTitle),
                body = stringResource(Strings.Unfinished.removedBody, flow.outcome.rawBlockCount, flow.outcome.draftTaskCount),
                isProblem = false,
                onClose = onClose,
            )
        is RemovalFlowState.Refused ->
            AnswerDialog(
                title = stringResource(Strings.Unfinished.refusedTitle),
                body = stringResource(sentenceOf(flow.refusal)),
                // Asking twice is not a failure, and is not drawn as one.
                isProblem = flow.refusal != DraftRemovalRefusal.ALREADY_REMOVED,
                onClose = onClose,
            )
    }
}

/**
 * The question PLAN 11.4.5 wants asked before a draft is removed.
 *
 * It says which file's draft goes, that its raw cells and task drafts are
 * deleted for good, that no game, task or cell changes, and that there is no
 * way back. It does not promise that the removal will happen: the engine can
 * still refuse, and says why.
 */
@Composable
private fun RemovalQuestion(
    entry: UnfinishedImport,
    isBusy: Boolean,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val accept = stringResource(Strings.Unfinished.removeAccept)
    val cancel = stringResource(Strings.Unfinished.removeCancel)
    // The keyboard starts on the way out, so a stray Enter cancels rather than
    // deletes. After a frame, because the dialog is drawn in a layer of its own.
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requester.requestFocus()
    }

    AlertDialog(
        // A click outside cannot abandon a transaction that is already running.
        onDismissRequest = { if (!isBusy) onClose() },
        title = { Text(stringResource(Strings.Unfinished.removeTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Strings.Unfinished.removeWhich, entry.fileName))
                Text(stringResource(Strings.Unfinished.removeWhat), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(Strings.Unfinished.removeUntouched), style = MaterialTheme.typography.bodyMedium)
                if (entry.mayBeHeldByRecords) NoteLine(text = stringResource(Strings.Unfinished.heldNote), isProblem = false)
                Text(stringResource(Strings.Unfinished.removeIrreversible), style = MaterialTheme.typography.labelMedium)
                if (isBusy) BusyLine(stringResource(Strings.Unfinished.removing))
            }
        },
        confirmButton = {
            Button(
                onClick = onRemove,
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
        modifier = Modifier.closingOnEscape(onClose.takeIf { !isBusy }),
    )
}

@Composable
private fun AnswerDialog(
    title: String,
    body: String,
    isProblem: Boolean,
    onClose: () -> Unit,
) {
    val close = stringResource(Strings.Unfinished.close)
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requester.requestFocus()
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            if (isProblem) NoteLine(text = body, isProblem = true) else Text(body)
        },
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

/** Escape leaves the dialog; a null [onEscape] leaves the key alone. See [SettledImportsSection]. */
private fun Modifier.closingOnEscape(onEscape: (() -> Unit)?): Modifier =
    onPreviewKeyEvent { event ->
        if (onEscape != null && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
            onEscape()
            true
        } else {
            false
        }
    }

@Composable
private fun BusyLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator()
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * What to tell the user about a removal the engine refused.
 *
 * Exhaustive with no fallback, so a refusal added later cannot reach the screen
 * without words of its own. [DraftRemovalRefusal.HELD_BY_RECORDS] has the
 * separate sentence PLAN 11.4.5 asks for.
 */
fun sentenceOf(refusal: DraftRemovalRefusal): StringResource =
    when (refusal) {
        DraftRemovalRefusal.ALREADY_REMOVED -> Strings.Unfinished.refusedAlreadyRemoved
        DraftRemovalRefusal.NOT_A_DRAFT -> Strings.Unfinished.refusedNotADraft
        DraftRemovalRefusal.HELD_BY_RECORDS -> Strings.Unfinished.refusedHeld
        DraftRemovalRefusal.COULD_NOT_SAVE -> Strings.Unfinished.refusedCouldNotSave
    }

/** What to tell the user about a draft that was not opened. Takes the file name. */
fun sentenceOf(refusal: OpenRefusal): StringResource =
    when (refusal) {
        OpenRefusal.RECORDS_CONTRADICT -> Strings.Unfinished.openRefusedContradict
        OpenRefusal.NO_LONGER_THERE -> Strings.Unfinished.openRefusedGone
        OpenRefusal.NO_LONGER_A_DRAFT -> Strings.Unfinished.openRefusedNotDraft
        OpenRefusal.COULD_NOT_READ -> Strings.Unfinished.openRefusedUnreadable
    }
