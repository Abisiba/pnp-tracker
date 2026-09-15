package dev.pnptracker.ui.feature.colors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.colors.BaseColorRestoreBlock
import dev.pnptracker.domain.colors.BlockedBaseColor
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.ColorUsage
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.theme.opaqueColorOf
import dev.pnptracker.ui.theme.visibleEdgeOn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val MAX_CONTENT_WIDTH = 720.dp
private val SWATCH_SIZE = 28.dp
private val SWATCH_SHAPE = RoundedCornerShape(6.dp)

/**
 * The global colour catalogue.
 *
 * Every swatch is drawn beside the written name of its colour and never alone,
 * because PLAN 17 does not allow a colour to be the only thing carrying a
 * meaning: a reader who cannot tell two swatches apart still reads two names.
 *
 * The twelve the catalogue starts with and the ones the user made are the same
 * kind of record here, and every row offers the same two actions. PLAN 5.7 makes
 * no distinction between them, so neither does this.
 */
@Composable
fun ColorCatalogueScreen(
    controller: ColorCatalogueController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = controller.state

    LaunchedEffect(Unit) { controller.observeColors() }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Strings.ScreenTitles.colors),
            style = MaterialTheme.typography.headlineSmall,
        )

        when (val catalogue = state.catalogue) {
            ColorCatalogueState.Loading -> BusyRow(stringResource(Strings.Colors.loading))

            is ColorCatalogueState.Content ->
                LazyColumn(
                    // Height first and width capped, in that order: filling the
                    // size before the cap fixes the width at the window's, and
                    // the cap can then never bring it back down — which is how
                    // a form meant to be 720 dp wide came to span a whole
                    // monitor, with a name field and a brightness slider
                    // stretched across all of it.
                    modifier = Modifier.fillMaxHeight().widthIn(max = MAX_CONTENT_WIDTH),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "notice") { NoticeCard(state.notice, controller) }
                    item(key = "work") {
                        WorkSurface(
                            state = state,
                            catalogue = catalogue.colors,
                            controller = controller,
                            onSave = { scope.launch { controller.save() } },
                        )
                    }
                    item(key = "count") {
                        Text(
                            text = stringResource(Strings.Colors.count, catalogue.colors.size.toString()),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    items(catalogue.colors, key = { it.id.toString() }) { color ->
                        ColorRow(
                            color = color,
                            enabled = state.work == null && !state.isSaving,
                            wantsFocus = controller.focusTarget == color.id,
                            focusRecall = controller.focusRecall,
                            onEdit = { controller.startEditing(color.id) },
                            onDelete = { scope.launch { controller.startDeleting(color.id) } },
                        )
                    }
                }
        }
    }
}

/**
 * One catalogue colour, with the two things that can be done to it.
 *
 * The actions are written words in the row and not an icon behind a menu: PLAN
 * 17 wants every main action reachable by keyboard, and something a pointer has
 * to uncover is not that.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorRow(
    color: ColorSummary,
    enabled: Boolean,
    wantsFocus: Boolean,
    focusRecall: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val edit = remember { FocusRequester() }
    LaunchedEffect(wantsFocus, focusRecall, enabled) {
        if (wantsFocus && enabled) runCatching { edit.requestFocus() }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                // Given the room that is left, so the actions sit at the edge of
                // every row instead of trailing whatever length the name happens
                // to be. When the row is too narrow for both, they wrap instead.
                modifier = Modifier.weight(1f),
            ) {
                Swatch(hex = color.hex, name = color.canonicalName)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = color.canonicalName, style = MaterialTheme.typography.bodyLarge)
                    Text(text = color.hex, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val editLabel = stringResource(Strings.Colors.editAction, color.canonicalName)
                val deleteLabel = stringResource(Strings.Colors.deleteAction, color.canonicalName)
                OutlinedButton(
                    onClick = onEdit,
                    enabled = enabled,
                    modifier = Modifier.focusRequester(edit).semantics { contentDescription = editLabel },
                ) {
                    Text(stringResource(Strings.Colors.editShort))
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = deleteLabel },
                ) {
                    Text(stringResource(Strings.Colors.deleteShort))
                }
            }
        }
    }
}

/**
 * A square of one colour.
 *
 * It carries the colour's name and its value as its description, so what it
 * shows is also available to a reader who is not looking at it. The border is
 * drawn in something that stands out from the fill, so white on white and black
 * on black are still squares.
 */
@Composable
private fun Swatch(
    hex: String,
    name: String,
) {
    val fill = opaqueColorOf(hex)
    val description = stringResource(Strings.Colors.swatchOf, name, hex)
    Box(
        modifier =
            Modifier
                .size(SWATCH_SIZE)
                .clip(SWATCH_SHAPE)
                .background(fill)
                .border(1.dp, visibleEdgeOn(fill), SWATCH_SHAPE)
                .semantics { contentDescription = description },
    )
}

/** Whatever is open, or the two things that can be started when nothing is. */
@Composable
private fun WorkSurface(
    state: ColorCatalogueScreenState,
    catalogue: List<ColorSummary>,
    controller: ColorCatalogueController,
    onSave: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val start = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    LaunchedEffect(controller.focusRecall, state.work == null) {
        if (state.work != null) return@LaunchedEffect
        // A row asks for the keyboard itself, so there is nothing to do here for
        // one. The restore action cannot be stood in for by a row and has to be
        // named, or it never gets the keyboard back at all.
        runCatching {
            when {
                controller.focusesTheRestore -> restore.requestFocus()
                controller.focusTarget == null -> start.requestFocus()
                else -> Unit
            }
        }
    }

    when (val work = state.work) {
        null ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { controller.startComposer() },
                    enabled = !state.isSaving,
                    modifier = Modifier.focusRequester(start),
                ) {
                    Text(stringResource(Strings.Colors.create))
                }
                OutlinedButton(
                    onClick = { scope.launch { controller.startRestoring() } },
                    enabled = !state.isSaving,
                    modifier = Modifier.focusRequester(restore),
                ) {
                    Text(stringResource(Strings.Colors.restoreAction))
                }
            }

        is ColorWork.Creating ->
            ComposerCard(
                composer = work.composer,
                catalogue = catalogue,
                isSaving = work.isSaving,
                failure = work.failure,
                title = null,
                focusRecall = controller.focusRecall,
                controller = controller,
                onSave = onSave,
            )

        is ColorWork.Editing ->
            ComposerCard(
                composer = work.composer,
                catalogue = catalogue,
                isSaving = work.isSaving,
                failure = work.failure,
                title = stringResource(Strings.Colors.editTitle),
                focusRecall = controller.focusRecall,
                controller = controller,
                onSave = onSave,
            )

        is ColorWork.Deleting -> DeleteCard(work = work, controller = controller, onConfirm = onSave)

        is ColorWork.Restoring -> RestoreCard(work = work, controller = controller, onConfirm = onSave)
    }
}

/**
 * The one form that makes a colour and the one that changes one.
 *
 * Deliberately the same form. PLAN 5.7 describes one small picker, and a second
 * one built for editing would be a second thing to keep in step with it.
 */
@Composable
private fun ComposerCard(
    composer: ColorComposer,
    catalogue: List<ColorSummary>,
    isSaving: Boolean,
    failure: ColorSetupFailure?,
    title: String?,
    focusRecall: Int,
    controller: ColorCatalogueController,
    onSave: () -> Unit,
) {
    val name = remember { FocusRequester() }
    LaunchedEffect(focusRecall) { runCatching { name.requestFocus() } }

    SurfaceCard(
        onEscape = { controller.cancel() },
        onCommit = { if (composer.canSave && !isSaving) onSave() },
    ) {
        if (title != null) Text(text = title, style = MaterialTheme.typography.titleSmall)

        OutlinedTextField(
            value = composer.name,
            onValueChange = { controller.editName(it) },
            enabled = !isSaving,
            label = { Text(stringResource(Strings.Colors.nameLabel)) },
            isError = !composer.isNameUsable,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(name),
        )
        if (!composer.isNameUsable) NoteLine(stringResource(Strings.Colors.nameRequired), isError = true)

        ColorPicker(
            composer = composer,
            catalogue = catalogue,
            enabled = !isSaving,
            onChooseBase = { controller.chooseBaseColor(it) },
            onMoveWheel = { point, radius -> controller.moveOnWheel(point, radius) },
            onNudgeWheel = { controller.nudgeWheel(it) },
            onBrightness = { controller.setBrightness(it) },
        )

        // The value is shown and not offered as a field: PLAN 5.7 refuses a
        // standing hex box, but the number itself is what the user will read
        // back to somebody else, so it is written out.
        NoteLine(stringResource(Strings.Colors.value, composer.hex), isError = false)

        val sharing = composer.sharedWith(catalogue)
        if (sharing.isNotEmpty()) {
            // A remark and not a refusal: PLAN 5.7 allows the same value under
            // two names. It is read out like any other line here, so it reaches
            // somebody who cannot see that the squares match.
            NoteLine(
                stringResource(Strings.Colors.hexShared, sharing.joinToString(", ") { it.canonicalName }),
                isError = false,
            )
        }

        FailureLine(failure)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = composer.canSave && !isSaving) {
                Text(stringResource(Strings.Colors.save))
            }
            OutlinedButton(onClick = { controller.cancel() }, enabled = !isSaving) {
                Text(stringResource(Strings.Colors.discard))
            }
        }
        if (isSaving) NoteLine(stringResource(Strings.Colors.saving), isError = false)
    }
}

/**
 * What losing a colour would cost, put to the user before it happens.
 *
 * Every number is written out rather than implied by the swatch, and the swatch
 * is beside the name rather than instead of it. The thing being confirmed cannot
 * be undone, so the sentence saying so is part of the question.
 */
@Composable
private fun DeleteCard(
    work: ColorWork.Deleting,
    controller: ColorCatalogueController,
    onConfirm: () -> Unit,
) {
    // The keyboard starts on the way out, not on the deletion: a stray Enter on a
    // question nobody has read yet must keep the colour rather than lose it for
    // good. Ctrl+Enter still deletes, because that one has to be meant.
    val keep = remember { FocusRequester() }
    LaunchedEffect(controller.focusRecall) { runCatching { keep.requestFocus() } }

    SurfaceCard(
        onEscape = { controller.cancel() },
        onCommit = { if (!work.isSaving) onConfirm() },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Swatch(hex = work.color.hex, name = work.color.canonicalName)
            Text(
                text = stringResource(Strings.Colors.deleteTitle, work.color.canonicalName),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        NoteLine(stringResource(Strings.Colors.value, work.color.hex), isError = false)

        val usage = work.usage
        if (!usage.isUsed) {
            NoteLine(stringResource(Strings.Colors.deleteUnused), isError = false)
        } else {
            NoteLine(
                stringResource(
                    Strings.Colors.deleteUsed,
                    usage.taskCount.toString(),
                    usage.unfinishedTaskCount.toString(),
                    usage.gameCount.toString(),
                ),
                isError = false,
            )
            if (usage.tasksLosingTheirLastColor > 0) {
                NoteLine(
                    stringResource(Strings.Colors.deleteLosing, usage.tasksLosingTheirLastColor.toString()),
                    isError = false,
                )
            }
            UsageSamples(usage)
        }

        NoteLine(stringResource(Strings.Colors.deleteIrreversible), isError = false)
        FailureLine(work.failure)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onConfirm,
                enabled = !work.isSaving,
            ) {
                Text(stringResource(Strings.Colors.deleteConfirm))
            }
            OutlinedButton(
                onClick = { controller.cancel() },
                enabled = !work.isSaving,
                modifier = Modifier.focusRequester(keep),
            ) {
                Text(stringResource(Strings.Colors.discard))
            }
        }
        if (work.isSaving) NoteLine(stringResource(Strings.Colors.deleting), isError = false)
    }
}

/** A few of the tasks by name, and a count for the ones there was no room for. */
@Composable
private fun UsageSamples(usage: ColorUsage) {
    if (usage.samples.isEmpty()) return
    NoteLine(stringResource(Strings.Colors.deleteExamples), isError = false)
    usage.samples.forEach { sample ->
        NoteLine(
            text =
                if (sample.gameName == null) {
                    sample.taskName
                } else {
                    stringResource(Strings.Colors.deleteSample, sample.gameName, sample.taskName)
                },
            isError = false,
        )
    }
    if (usage.beyondTheSamples > 0) {
        NoteLine(stringResource(Strings.Colors.deleteMore, usage.beyondTheSamples.toString()), isError = false)
    }
}

/**
 * Which of the twelve are missing, and what is standing in the way.
 *
 * The heading says what this does and what it does not: PLAN 5.8 only puts back
 * what is gone, and an action that read as "reset every colour" would be asking
 * the user to agree to something else entirely.
 */
@Composable
private fun RestoreCard(
    work: ColorWork.Restoring,
    controller: ColorCatalogueController,
    onConfirm: () -> Unit,
) {
    val confirm = remember { FocusRequester() }
    val dismiss = remember { FocusRequester() }
    val canGoAhead = work.plan.canRestore && !work.isSaving
    // The keyboard goes to whichever of the two can take it. A disabled button
    // cannot be focused, so asking it to hold the focus left the focus outside
    // the card — and then Escape never reached the card that was meant to close.
    LaunchedEffect(controller.focusRecall, canGoAhead) {
        runCatching { if (canGoAhead) confirm.requestFocus() else dismiss.requestFocus() }
    }

    SurfaceCard(
        onEscape = { controller.cancel() },
        onCommit = { if (canGoAhead) onConfirm() },
    ) {
        Text(text = stringResource(Strings.Colors.restoreTitle), style = MaterialTheme.typography.titleSmall)
        NoteLine(stringResource(Strings.Colors.restoreExplains), isError = false)

        if (work.plan.missing.isNotEmpty()) {
            NoteLine(stringResource(Strings.Colors.restoreMissing), isError = false)
            work.plan.missing.forEach { base ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Swatch(hex = base.hex, name = base.canonicalName)
                    NoteLine(stringResource(Strings.Colors.restoreItem, base.canonicalName, base.hex), isError = false)
                }
            }
        }

        if (work.plan.blocked.isNotEmpty()) {
            NoteLine(stringResource(Strings.Colors.restoreBlocked), isError = true)
            work.plan.blocked.forEach { blocked -> NoteLine(reasonOf(blocked), isError = true) }
            NoteLine(stringResource(Strings.Colors.restoreBlockedNote), isError = true)
        }

        FailureLine(work.failure)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onConfirm,
                enabled = canGoAhead,
                modifier = Modifier.focusRequester(confirm),
            ) {
                Text(stringResource(Strings.Colors.restoreConfirm))
            }
            OutlinedButton(
                onClick = { controller.cancel() },
                enabled = !work.isSaving,
                modifier = Modifier.focusRequester(dismiss),
            ) {
                Text(stringResource(Strings.Colors.discard))
            }
        }
        if (work.isSaving) NoteLine(stringResource(Strings.Colors.restoring), isError = false)
    }
}

@Composable
private fun reasonOf(blocked: BlockedBaseColor): String =
    stringResource(
        when (blocked.reason) {
            BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR -> Strings.Colors.restoreBlockName
            BaseColorRestoreBlock.NAME_TAKEN_BY_ALIAS -> Strings.Colors.restoreBlockAlias
            BaseColorRestoreBlock.APPEARED_MEANWHILE -> Strings.Colors.restoreBlockRace
        },
        blocked.canonicalName,
        blocked.hex,
    )

/** What just happened, said once and dismissed by the user rather than by a timer. */
@Composable
private fun NoticeCard(
    notice: ColorNotice?,
    controller: ColorCatalogueController,
) {
    if (notice == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    when (notice) {
                        is ColorNotice.Removed ->
                            stringResource(
                                Strings.Colors.removed,
                                notice.colorName,
                                notice.removal.removedRelationCount.toString(),
                                notice.removal.tasksLeftWithoutAColor.toString(),
                            )

                        is ColorNotice.Restored ->
                            stringResource(Strings.Colors.restored, notice.canonicalNames.joinToString(", "))

                        ColorNotice.NothingMissing -> stringResource(Strings.Colors.restoreNothingMissing)
                    },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { controller.acknowledgeNotice() }) {
                Text(stringResource(Strings.Colors.noticeDismiss))
            }
        }
    }
}

/**
 * The card every open surface sits in, with the keyboard it answers.
 *
 * Caught for the whole surface rather than for one field in it: the user may be
 * on the wheel, the slider or a button when they finish. The wheel takes the
 * arrow keys before this sees them, so moving it never leaks out into the list
 * behind.
 */
@Composable
private fun SurfaceCard(
    onEscape: () -> Unit,
    onCommit: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(max = MAX_CONTENT_WIDTH)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Escape -> {
                            onEscape()
                            true
                        }

                        event.isCtrlPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                            onCommit()
                            true
                        }

                        else -> false
                    }
                },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun NoteLine(
    text: String,
    isError: Boolean,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FailureLine(failure: ColorSetupFailure?) {
    if (failure == null) return
    Text(
        text = stringResource(messageOf(failure)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun messageOf(failure: ColorSetupFailure) =
    when (failure) {
        ColorSetupFailure.COULD_NOT_SAVE -> Strings.Colors.errorCouldNotSave
        ColorSetupFailure.NAME_ALREADY_USED -> Strings.Colors.errorNameUsed
        ColorSetupFailure.NAME_IS_ANOTHER_COLORS_ALIAS -> Strings.Colors.errorNameIsAlias
        ColorSetupFailure.COLOR_NO_LONGER_EXISTS -> Strings.Colors.errorColorGone
        ColorSetupFailure.COLOR_CHANGED_MEANWHILE -> Strings.Colors.errorChanged
    }

@Composable
private fun BusyRow(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator()
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}
