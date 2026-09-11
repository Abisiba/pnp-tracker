package dev.pnptracker.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.backup.retention.DEFAULT_AUTOMATIC_BACKUPS
import dev.pnptracker.domain.settings.SettingsProblem
import dev.pnptracker.domain.settings.SettingsWriteFailure
import dev.pnptracker.ui.Strings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * How many automatic backups to keep (PLAN 12.16, 14.4.12).
 *
 * The sentences under the heading are not decoration. Somebody changing this
 * number is entitled to know what it counts, what it does not count and when it
 * takes effect, and each of those three has an answer they would not guess: the
 * count is per kind rather than overall, their own backups are outside it
 * altogether, and lowering it deletes nothing today.
 *
 * A field with two steppers beside it. The steppers are for the ordinary change
 * of one or two and cannot leave the range; the field is there because the range
 * runs to fifty and nobody should have to press a button forty times. What can
 * be typed into a field is anything at all, which is why the refusal under it is
 * part of the design rather than an afterthought.
 */
@Composable
fun RetentionSection(
    controller: RetentionController,
    modifier: Modifier = Modifier,
) {
    val state = controller.state
    val saveButton = remember { FocusRequester() }

    LaunchedEffect(Unit) { controller.load() }
    // Sent back to the button whenever a result lands, so the keyboard never
    // ends up nowhere after a save.
    LaunchedEffect(controller.focusRecall) {
        if (controller.focusRecall > 0) runCatching { saveButton.requestFocus() }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Strings.Retention.sectionTitle),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        listOf(
            Strings.Retention.explains,
            Strings.Retention.scopeNote,
            Strings.Retention.manualNote,
            Strings.Retention.delayNote,
        ).forEach { sentence ->
            Text(
                text = stringResource(sentence),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = MaxTextWidth),
            )
        }

        when (state) {
            RetentionScreenState.Loading -> Waiting(Strings.Retention.loading)
            RetentionScreenState.Saving -> Waiting(Strings.Retention.saving)
            is RetentionScreenState.Ready -> {
                Chooser(controller, saveButton)
                if (state.problem != null) FileProblem(state.problem)
                if (state.justSaved) {
                    Announcement(stringResource(Strings.Retention.saved, state.automaticBackupCount))
                }
            }
            is RetentionScreenState.Failed -> {
                Chooser(controller, saveButton)
                WriteFailure(state.failure, state.automaticBackupCount)
            }
        }
    }
}

/** The field, the two steps and the save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chooser(
    controller: RetentionController,
    saveButton: FocusRequester,
) {
    val scope = rememberCoroutineScope()
    val busy = controller.state.isBusy
    val inUse = controller.state.countInUse
    // A button showing a symbol needs words of its own, and PLAN 17 keeps every
    // word this application says in the catalogue rather than in the screen.
    val decrease = stringResource(Strings.Retention.decrease)
    val increase = stringResource(Strings.Retention.increase)

    // Wrapped rather than laid out in a row, so a narrow window or a large font
    // moves the save button under the field instead of off the edge (PLAN 17).
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        OutlinedButton(
            onClick = { controller.step(-1) },
            enabled = !busy,
            modifier = Modifier.semantics { contentDescription = decrease },
        ) {
            Text("−")
        }
        OutlinedTextField(
            value = controller.draftText,
            onValueChange = controller::type,
            enabled = !busy,
            singleLine = true,
            isError = controller.draftIsRefused,
            label = { Text(stringResource(Strings.Retention.countLabel)) },
            modifier = Modifier.widthIn(max = CountFieldWidth),
        )
        OutlinedButton(
            onClick = { controller.step(1) },
            enabled = !busy,
            modifier = Modifier.semantics { contentDescription = increase },
        ) {
            Text("+")
        }
        Button(
            onClick = { scope.launch { controller.save() } },
            enabled = controller.canSave,
            modifier = Modifier.focusRequester(saveButton),
        ) {
            Text(stringResource(Strings.Retention.save))
        }
    }
    if (controller.draftIsRefused) {
        Text(
            text = stringResource(Strings.Retention.invalid),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.widthIn(max = MaxTextWidth).semantics { liveRegion = LiveRegionMode.Polite },
        )
    } else {
        Text(
            text = stringResource(Strings.Retention.rangeHint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = MaxTextWidth),
        )
    }
    if (inUse != null) {
        Text(
            text = stringResource(Strings.Retention.current, inUse),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = MaxTextWidth),
        )
    }
}

/**
 * What is wrong with the settings file, and that it has been left alone.
 *
 * Shown rather than hidden because the number in use is not the one the file
 * asked for, and somebody who edited that file deserves to know it did not take
 * (PLAN 14.4.12). The second sentence is the important one: their file is still
 * there, exactly as they left it.
 */
@Composable
private fun FileProblem(problem: SettingsProblem) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(top = 8.dp).widthIn(max = MaxTextWidth),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(Strings.Retention.problemTitle),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = stringResource(messageFor(problem)), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(Strings.Retention.problemDefaultNote, DEFAULT_AUTOMATIC_BACKUPS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A save that did not happen, and what is still true after it. */
@Composable
private fun WriteFailure(
    failure: SettingsWriteFailure,
    stillInUse: Int,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(top = 8.dp).widthIn(max = MaxTextWidth),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Strings.Retention.errorTitle),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(messageFor(failure)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(Strings.Retention.errorKept, stillInUse),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun Waiting(text: StringResource) {
    FlowRowWaiting(stringResource(text))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowWaiting(text: String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun Announcement(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp).widthIn(max = MaxTextWidth).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * Every reason the file could not be used becomes its own sentence.
 *
 * Exhaustive and without an `else`, so a reason added later has to be given
 * words rather than quietly becoming one of these.
 */
internal fun messageFor(problem: SettingsProblem): StringResource =
    when (problem) {
        SettingsProblem.COULD_NOT_READ -> Strings.Retention.problemCouldNotRead
        SettingsProblem.NOT_THE_EXPECTED_SHAPE -> Strings.Retention.problemNotExpectedShape
        SettingsProblem.VERSION_NOT_SUPPORTED -> Strings.Retention.problemVersion
        SettingsProblem.VALUE_OUT_OF_RANGE -> Strings.Retention.problemValue
    }

/** The same, for the two ways a save can fail. */
internal fun messageFor(failure: SettingsWriteFailure): StringResource =
    when (failure) {
        SettingsWriteFailure.NOT_WRITABLE -> Strings.Retention.errorNotWritable
        SettingsWriteFailure.COULD_NOT_WRITE -> Strings.Retention.errorCouldNotWrite
    }

/** How wide the number box gets; it holds two digits and never more. */
private val CountFieldWidth = 148.dp
