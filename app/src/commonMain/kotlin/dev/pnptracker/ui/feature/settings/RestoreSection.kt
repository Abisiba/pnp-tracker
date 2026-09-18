package dev.pnptracker.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.BackupSummary
import dev.pnptracker.domain.backup.restore.RestoreProblem
import dev.pnptracker.domain.time.localMomentOf
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.feature.history.momentArgumentsOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * Putting a backup back over everything that is here now.
 *
 * The two sentences under the action say the two things somebody has to know
 * before they press it: that this replaces rather than merges, and that a copy
 * of what they have now is made first. Nothing about checksums, schema versions
 * or transactions appears anywhere on this screen — those are promises the
 * application keeps to itself, and a person deciding whether to replace their
 * data is not helped by them.
 *
 * The destructive question is not on this screen until a file has been chosen
 * and has passed every check there is (PLAN 12.16). Until then there is a button
 * and a line saying what is happening.
 */
@Composable
internal fun RestoreSection(
    controller: RestoreController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = controller.state
    val restoreButton = remember { FocusRequester() }

    // Sent back to the button whenever a surface closes, so the keyboard never
    // lands nowhere after a question or a result.
    LaunchedEffect(controller.focusRecall) {
        if (controller.focusRecall > 0) runCatching { restoreButton.requestFocus() }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Strings.Restore.scopeNote),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = MaxTextWidth),
        )
        Text(
            text = stringResource(Strings.Restore.safetyNote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = MaxTextWidth),
        )

        val description = stringResource(Strings.Restore.actionDescription)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { scope.launch { controller.chooseBackup() } },
                enabled = !state.isBusy && state !is RestoreScreenState.Confirming,
                modifier =
                    Modifier
                        .focusRequester(restoreButton)
                        .semantics { contentDescription = description },
            ) {
                Text(stringResource(Strings.Restore.action))
            }
            if (state.isBusy) RestoreBusyLine(state)
        }

        when (state) {
            is RestoreScreenState.Confirming ->
                RestoreQuestion(
                    summary = state.summary,
                    onConfirm = { scope.launch { controller.confirmRestore() } },
                    onCancel = controller::cancelRestore,
                )

            is RestoreScreenState.Restored ->
                RestoreResult(
                    text = stringResource(Strings.Restore.done, state.fileName),
                    detail = stringResource(Strings.Restore.doneSafety, state.safetyFileName),
                    isProblem = false,
                    onDismiss = controller::startOver,
                )

            is RestoreScreenState.Rejected ->
                RestoreResult(
                    text = stringResource(messageFor(state.problem)),
                    detail = null,
                    isProblem = true,
                    onDismiss = controller::startOver,
                )

            is RestoreScreenState.Failed ->
                RestoreResult(
                    text = stringResource(messageFor(state.problem)),
                    // Named only when there is one, and it is the most useful
                    // thing on the screen when there is: it is the way back.
                    detail = state.safetyFileName?.let { stringResource(Strings.RestoreErrors.safetyKept, it) },
                    isProblem = true,
                    onDismiss = controller::startOver,
                )

            else -> Unit
        }
    }
}

@Composable
private fun RestoreBusyLine(state: RestoreScreenState) {
    val message =
        stringResource(
            when (state) {
                is RestoreScreenState.Validating -> Strings.Restore.validating
                is RestoreScreenState.CreatingSafetyBackup -> Strings.Restore.creatingSafety
                is RestoreScreenState.WritingSafetyBackup -> Strings.Restore.writingSafety
                is RestoreScreenState.Applying -> Strings.Restore.applying
                else -> Strings.Restore.choosing
            },
        )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            // Announced as it changes, so a long wait is audible and not only visible.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * The one destructive question this application asks.
 *
 * The keyboard starts on `Vazgeç`. Every other confirmation in the application
 * puts the focus on the panel so a stray Enter answers neither way; this one goes
 * further and puts it on the answer that changes nothing, because this is the
 * only question whose other answer replaces everything the user has. Escape
 * answers no as well.
 *
 * The summary above the buttons is counts and a date and nothing else. Not a game
 * name, not a task, not an identifier: the file has been checked but it was
 * written by somebody, and a screen that repeated its contents would be putting
 * unverified words in the application's own voice.
 *
 * The buttons wrap onto a second line rather than running off the edge of a
 * narrow window, and the whole settings screen scrolls, so neither answer can end
 * up somewhere the user cannot reach.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RestoreQuestion(
    summary: BackupSummary,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = stringResource(Strings.Restore.confirmTitle)
    val cancel = stringResource(Strings.Restore.confirmNo)
    val cancelButton = remember { FocusRequester() }
    LaunchedEffect(summary) { runCatching { cancelButton.requestFocus() } }
    Surface(
        tonalElevation = 2.dp,
        modifier =
            Modifier
                .widthIn(max = MaxTextWidth)
                .semantics { contentDescription = title }
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
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(text = stringResource(Strings.Restore.confirmReplace))
            Text(text = stringResource(Strings.Restore.confirmSafety))
            RestoreSummaryLines(summary)
            val confirm = stringResource(Strings.Restore.confirmYes)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    onClick = onCancel,
                    modifier =
                        Modifier
                            .focusRequester(cancelButton)
                            .semantics { contentDescription = cancel },
                ) {
                    Text(cancel)
                }
                Button(
                    onClick = onConfirm,
                    // The colour says what the button does, and the word beside
                    // it says the same thing, so neither is the only carrier
                    // (PLAN 17).
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    modifier = Modifier.semantics { contentDescription = confirm },
                ) {
                    Text(confirm)
                }
            }
        }
    }
}

/** What can safely be said about a backup: its name, when it was taken, how much is in it. */
@Composable
private fun RestoreSummaryLines(summary: BackupSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(Strings.Restore.summaryFile, summary.fileName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Strings.Restore.summaryTaken, momentTextOf(summary.createdAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                stringResource(
                    Strings.Restore.summaryCounts,
                    summary.gameCount.toString(),
                    summary.taskCount.toString(),
                    summary.colorCount.toString(),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The moment the backup was taken, read in the user's own calendar.
 *
 * The file carries it as the format's canonical UTC text, which is precise and
 * unreadable; what goes on screen is the same arrangement the history uses, so
 * one date in this application looks like another. Parsing cannot fail here: a
 * backup only reaches this screen after the envelope gate has parsed that very
 * text and checked it round-trips (PLAN 14.4.1).
 */
@Composable
private fun momentTextOf(createdAt: String): String =
    stringResource(
        Strings.History.moment,
        *momentArgumentsOf(localMomentOf(Instant.parse(createdAt))).toTypedArray(),
    )

@Composable
private fun RestoreResult(
    text: String,
    detail: String?,
    isProblem: Boolean,
    onDismiss: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.widthIn(max = MaxTextWidth)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isProblem) {
                Text(
                    text = stringResource(Strings.RestoreErrors.title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(text = text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val dismiss = stringResource(Strings.Restore.dismiss)
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
 * Every reason a backup file can be refused, each with a sentence a person can act on.
 *
 * Exhaustive with no fallback and no enum name anywhere near the screen: a reason
 * added later will not compile until somebody has decided what to tell the user
 * about it, which is the only way a new refusal avoids becoming "something went
 * wrong".
 *
 * Several reasons share a sentence, and that is a decision rather than laziness.
 * The vocabulary is the reader's and it is precise because the code needs it to
 * be; what a person can *do* is a much shorter list. A file whose UTF-8 is broken
 * and a file whose JSON is truncated call for the same thing — another file — and
 * telling somebody which of the two it was would be telling them about this
 * application rather than about their backup. Where the action really differs —
 * update the application, choose a different file, this file has been altered —
 * the sentences differ too.
 */
internal fun messageFor(problem: BackupProblem): StringResource =
    when (problem) {
        BackupProblem.UNREADABLE -> Strings.RestoreErrors.unreadable
        BackupProblem.EMPTY_FILE -> Strings.RestoreErrors.empty
        BackupProblem.SAFETY_LIMIT -> Strings.RestoreErrors.tooLarge

        // Not text this application could have written, whatever the byte was.
        BackupProblem.INVALID_UTF8 -> Strings.RestoreErrors.brokenFile
        BackupProblem.MALFORMED_JSON -> Strings.RestoreErrors.brokenFile
        BackupProblem.TOO_DEEPLY_NESTED -> Strings.RestoreErrors.brokenFile

        // Readable JSON that is not shaped like a backup: the usual cause is a
        // file somebody opened and edited.
        BackupProblem.DUPLICATE_KEY -> Strings.RestoreErrors.notThisShape
        BackupProblem.UNKNOWN_FIELD -> Strings.RestoreErrors.notThisShape
        BackupProblem.MISSING_FIELD -> Strings.RestoreErrors.notThisShape
        BackupProblem.WRONG_TYPE -> Strings.RestoreErrors.notThisShape
        BackupProblem.INVALID_CREATED_AT -> Strings.RestoreErrors.notThisShape
        BackupProblem.MALFORMED_CHECKSUM -> Strings.RestoreErrors.notThisShape

        BackupProblem.WRONG_FORMAT -> Strings.RestoreErrors.notABackup

        // The two that are worth telling apart, because one of them has a remedy.
        BackupProblem.FORMAT_TOO_NEW -> Strings.RestoreErrors.tooNew
        BackupProblem.SCHEMA_TOO_NEW -> Strings.RestoreErrors.tooNew
        BackupProblem.FORMAT_TOO_OLD -> Strings.RestoreErrors.tooOld
        BackupProblem.SCHEMA_TOO_OLD -> Strings.RestoreErrors.tooOld

        BackupProblem.CHECKSUM_MISMATCH -> Strings.RestoreErrors.changedFile

        // A backup whose rows do not stand up. Split in two because one is about
        // single values and the other about how they fit together, and a person
        // reading it can tell which of their files is which.
        BackupProblem.INVALID_ID -> Strings.RestoreErrors.badRecords
        BackupProblem.INVALID_ENUM -> Strings.RestoreErrors.badRecords
        BackupProblem.INVALID_VALUE -> Strings.RestoreErrors.badRecords
        BackupProblem.DUPLICATE_RECORD -> Strings.RestoreErrors.inconsistent
        BackupProblem.BROKEN_REFERENCE -> Strings.RestoreErrors.inconsistent
        BackupProblem.DOMAIN_INVARIANT -> Strings.RestoreErrors.inconsistent

        BackupProblem.TEMP_VALIDATION_FAILED -> Strings.RestoreErrors.didNotPass

        BackupProblem.IMPORT_RECORDS_CONTRADICT -> Strings.RestoreErrors.importsContradict
    }

/**
 * Every way a good backup can still not be put back, each with its own sentence.
 *
 * All six are distinct, unlike the refusals above, because all six mean something
 * different about the user's data: four of them say it is exactly as it was, one
 * says somebody else changed it, and one says the application could not check.
 */
internal fun messageFor(problem: RestoreProblem): StringResource =
    when (problem) {
        RestoreProblem.SAFETY_BACKUP_NOT_MADE -> Strings.RestoreErrors.safetyNotMade
        RestoreProblem.SAFETY_BACKUP_NOT_WRITTEN -> Strings.RestoreErrors.safetyNotWritten
        RestoreProblem.DATA_CHANGED_MEANWHILE -> Strings.RestoreErrors.dataChanged
        RestoreProblem.COULD_NOT_APPLY -> Strings.RestoreErrors.notApplied
        RestoreProblem.REFERENCES_NOT_WHOLE -> Strings.RestoreErrors.references
        RestoreProblem.NOT_VERIFIED_AFTERWARDS -> Strings.RestoreErrors.notVerified
    }
