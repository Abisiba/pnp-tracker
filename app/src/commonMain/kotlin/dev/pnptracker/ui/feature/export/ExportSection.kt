package dev.pnptracker.ui.feature.export

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.export.ExportFailure
import dev.pnptracker.ui.Strings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** How wide a message is allowed to get before it wraps. */
private val MaxMessageWidth = 560.dp

/**
 * The action that writes every task out, and whatever it has to say afterwards.
 *
 * It sits with the table's own controls rather than on a screen of its own,
 * because what it writes is the table: every game, every column, every task. The
 * note beside it says so, since the search box directly above changes what is
 * *listed* and changes nothing at all about what is written.
 *
 * The overwrite question is drawn inline rather than in a popup. A popup is a
 * separate focus layer, and a question with two answers has to be reachable by
 * keyboard before anything else on the screen is.
 */
@Composable
fun TaskExportAction(
    controller: ExportController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = controller.state
    val exportButton = remember { FocusRequester() }

    // Sent back to the button whenever a surface closes, so the keyboard never
    // lands nowhere after a confirmation or a result.
    LaunchedEffect(controller.focusRecall) {
        if (controller.focusRecall > 0) runCatching { exportButton.requestFocus() }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val description = stringResource(Strings.Export.actionDescription)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { scope.launch { controller.exportTasks() } },
                enabled = !controller.isBusy && state !is ExportScreenState.ConfirmingOverwrite,
                modifier =
                    Modifier
                        .focusRequester(exportButton)
                        .semantics { contentDescription = description },
            ) {
                Text(stringResource(Strings.Export.action))
            }
            if (controller.isBusy) BusyLine(state)
        }
        Text(
            text = stringResource(Strings.Export.scopeNote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = MaxMessageWidth),
        )

        when (state) {
            is ExportScreenState.ConfirmingOverwrite ->
                OverwriteQuestion(
                    fileName = state.fileName,
                    onConfirm = { scope.launch { controller.confirmOverwrite() } },
                    onCancel = controller::cancelOverwrite,
                )

            is ExportScreenState.Written ->
                ResultLine(
                    text = stringResource(Strings.Export.written, state.taskCount, state.fileName),
                    isProblem = false,
                    onDismiss = controller::startOver,
                )

            is ExportScreenState.Failed ->
                ResultLine(
                    text = stringResource(messageFor(state.failure)),
                    isProblem = true,
                    onDismiss = controller::startOver,
                )

            else -> Unit
        }
    }
}

@Composable
private fun BusyLine(state: ExportScreenState) {
    val message =
        stringResource(
            if (state is ExportScreenState.Writing) Strings.Export.writing else Strings.Export.choosing,
        )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.widthIn(max = 20.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            // Announced as it changes, so the wait is audible and not only visible.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * The one question this screen asks.
 *
 * Escape answers it with a no, which is the answer that changes nothing, and the
 * keyboard goes back to the button that asked. The two buttons wrap onto a
 * second line at a narrow width rather than running off the edge.
 */
@Composable
private fun OverwriteQuestion(
    fileName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = stringResource(Strings.Export.overwriteTitle)
    val question = remember { FocusRequester() }
    // Focused as it appears, so Escape means no from the moment it is asked
    // rather than only after somebody has tabbed into it. The panel itself takes
    // the focus and not either button: the answer that changes nothing should
    // not be one keystroke away from the answer that replaces a file.
    LaunchedEffect(fileName) { runCatching { question.requestFocus() } }
    Surface(
        tonalElevation = 2.dp,
        modifier =
            Modifier
                .widthIn(max = MaxMessageWidth)
                .semantics { contentDescription = title }
                .focusRequester(question)
                .focusGroup()
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onCancel()
                        true
                    } else {
                        false
                    }
                },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text = stringResource(Strings.Export.overwriteQuestion, fileName))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onConfirm) {
                    Text(stringResource(Strings.Export.overwriteConfirm))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(Strings.Export.overwriteCancel))
                }
            }
        }
    }
}

/**
 * What happened, said once and dismissible.
 *
 * A live region, so a screen reader hears the outcome without the user having to
 * go looking for it. There is no path in it and no cause: a message tells the
 * user what to do next, and the details a developer needs stay on the exception.
 */
@Composable
private fun ResultLine(
    text: String,
    isProblem: Boolean,
    onDismiss: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.widthIn(max = MaxMessageWidth),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isProblem) {
                Text(
                    text = stringResource(Strings.ExportErrors.title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(text = text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = onDismiss) {
                Text(stringResource(Strings.Export.dismiss))
            }
        }
    }
}

internal fun messageFor(failure: ExportFailure): StringResource =
    when (failure) {
        ExportFailure.NOTHING_TO_EXPORT -> Strings.ExportErrors.nothingToExport
        ExportFailure.BROKEN_DATA -> Strings.ExportErrors.brokenData
        ExportFailure.UNSUPPORTED_FILE_TYPE -> Strings.ExportErrors.unsupportedFileType
        ExportFailure.NOT_WRITABLE -> Strings.ExportErrors.notWritable
        ExportFailure.WRITE_FAILED -> Strings.ExportErrors.writeFailed
        ExportFailure.NOT_ATOMIC -> Strings.ExportErrors.notAtomic
    }
