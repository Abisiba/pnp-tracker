package dev.pnptracker.ui.feature.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.navigation.Screen
import dev.pnptracker.ui.textsOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** How wide a paragraph is allowed to get before it wraps. */
internal val MaxTextWidth = 640.dp

/**
 * The application's own housekeeping (PLAN 12.16).
 *
 * The two actions the plan names, in the order it names them: save everything to
 * a file, and put a file back over everything. They sit in one section because
 * they are one subject and because the second one is only ever as good as the
 * first. Under them is the one setting this application has — how many of the
 * backups it takes by itself to keep (PLAN 14.4.12) — which belongs here for the
 * same reason: it is about the same files.
 *
 * The whole screen scrolls, so a short window or a large font never puts either
 * action, or the answer to a question, out of reach.
 */
@Composable
fun SettingsScreen(
    controller: BackupController,
    restoreController: RestoreController,
    retentionController: RetentionController,
    modifier: Modifier = Modifier,
) {
    val texts = textsOf(Screen.Settings)
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(texts.title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(texts.description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = MaxTextWidth),
        )
        BackupSection(controller, modifier = Modifier.padding(top = 12.dp))
        RestoreSection(restoreController, modifier = Modifier.padding(top = 20.dp))
        RetentionSection(retentionController, modifier = Modifier.padding(top = 20.dp))
    }
}

/**
 * Saving everything the application holds to one file.
 *
 * The two sentences under the heading are the whole explanation the user gets,
 * and they answer the two questions somebody is actually asking: what goes into
 * it, and where does it end up. Nothing about the format, the version or the
 * checksum appears here — those are promises this application keeps to itself.
 */
@Composable
private fun BackupSection(
    controller: BackupController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = controller.state
    val saveButton = remember { FocusRequester() }

    // Sent back to the button whenever a surface closes, so the keyboard never
    // lands nowhere after a confirmation or a result.
    LaunchedEffect(controller.focusRecall) {
        if (controller.focusRecall > 0) runCatching { saveButton.requestFocus() }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Strings.Backup.sectionTitle),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(Strings.Backup.scopeNote),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = MaxTextWidth),
        )
        Text(
            text = stringResource(Strings.Backup.destinationNote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = MaxTextWidth),
        )

        val description = stringResource(Strings.Backup.actionDescription)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { scope.launch { controller.saveBackup() } },
                enabled = !state.isBusy && state !is BackupScreenState.ConfirmingOverwrite,
                modifier =
                    Modifier
                        .focusRequester(saveButton)
                        .semantics { contentDescription = description },
            ) {
                Text(stringResource(Strings.Backup.action))
            }
            if (state.isBusy) BusyLine(state)
        }

        when (state) {
            is BackupScreenState.ConfirmingOverwrite ->
                OverwriteQuestion(
                    fileName = state.fileName,
                    onConfirm = { scope.launch { controller.confirmOverwrite() } },
                    onCancel = controller::cancelOverwrite,
                )

            is BackupScreenState.Saved ->
                ResultLine(
                    text = stringResource(Strings.Backup.saved, state.fileName),
                    isProblem = false,
                    onDismiss = controller::startOver,
                )

            is BackupScreenState.Failed ->
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
private fun BusyLine(state: BackupScreenState) {
    val message =
        stringResource(
            when (state) {
                is BackupScreenState.Preparing -> Strings.Backup.preparing
                is BackupScreenState.Writing -> Strings.Backup.writing
                else -> Strings.Backup.choosing
            },
        )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
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
 * The panel itself takes the focus rather than either button: the answer that
 * changes nothing should not be one keystroke away from the answer that replaces
 * a file, so a stray Enter lands on neither. Escape answers no, and the keyboard
 * goes back to the button that asked. The two buttons wrap onto a second line at
 * a narrow width rather than running off the edge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverwriteQuestion(
    fileName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = stringResource(Strings.Backup.overwriteTitle)
    val question = remember { FocusRequester() }
    LaunchedEffect(fileName) { runCatching { question.requestFocus() } }
    Surface(
        tonalElevation = 2.dp,
        modifier =
            Modifier
                .widthIn(max = MaxTextWidth)
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
            Text(text = stringResource(Strings.Backup.overwriteQuestion, fileName))
            val confirm = stringResource(Strings.Backup.overwriteConfirm)
            val cancel = stringResource(Strings.Backup.overwriteCancel)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.semantics { contentDescription = confirm },
                ) {
                    Text(confirm)
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.semantics { contentDescription = cancel },
                ) {
                    Text(cancel)
                }
            }
        }
    }
}

/**
 * What happened, said once and dismissible.
 *
 * A live region, so a screen reader hears the outcome without going looking for
 * it. There is no path in it and no cause: the message says what to do next, and
 * the details a developer needs stay on the exception.
 */
@Composable
private fun ResultLine(
    text: String,
    isProblem: Boolean,
    onDismiss: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.widthIn(max = MaxTextWidth)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isProblem) {
                Text(
                    text = stringResource(Strings.BackupErrors.title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(text = text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            val dismiss = stringResource(Strings.Backup.dismiss)
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = dismiss },
            ) {
                Text(dismiss)
            }
        }
    }
}

/**
 * Every reason a backup can fail, each with its own sentence.
 *
 * Exhaustive with no fallback: a reason added later will not compile until
 * somebody has decided what to tell the user about it, which is the only way a
 * new failure avoids becoming "something went wrong".
 */
internal fun messageFor(failure: BackupFailure): StringResource =
    when (failure) {
        BackupFailure.NO_DESTINATION -> Strings.BackupErrors.noDestination
        BackupFailure.UNSUPPORTED_FILE_TYPE -> Strings.BackupErrors.unsupportedFileType
        BackupFailure.NOT_WRITABLE -> Strings.BackupErrors.notWritable
        BackupFailure.TEMPORARY_FILE_FAILED -> Strings.BackupErrors.temporaryFileFailed
        BackupFailure.TARGET_UNAVAILABLE -> Strings.BackupErrors.targetUnavailable
        BackupFailure.WRITE_FAILED -> Strings.BackupErrors.writeFailed
        BackupFailure.NOT_ATOMIC -> Strings.BackupErrors.notAtomic
        BackupFailure.COULD_NOT_READ_DATABASE -> Strings.BackupErrors.couldNotReadDatabase
        BackupFailure.COULD_NOT_BUILD_DOCUMENT -> Strings.BackupErrors.couldNotBuildDocument
    }
