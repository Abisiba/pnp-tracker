package dev.pnptracker.ui.feature.pools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.pools.PoolColor
import dev.pnptracker.domain.pools.PoolColorGroup
import dev.pnptracker.domain.pools.PoolModel
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.feature.games.AnchoredAboveWord
import dev.pnptracker.ui.feature.tasks.NoteLine
import dev.pnptracker.ui.feature.tasks.TaskEditPanel
import dev.pnptracker.ui.feature.tasks.focusOutline
import dev.pnptracker.ui.poolDescriptionOf
import dev.pnptracker.ui.poolNavigationNameOf
import dev.pnptracker.ui.stageNameOf
import dev.pnptracker.ui.theme.opaqueColorOf
import dev.pnptracker.ui.theme.readableInkOn
import dev.pnptracker.ui.theme.visibleEdgeOn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val MaxContentWidth = 720.dp
private val CardShape = RoundedCornerShape(10.dp)
private val SwatchShape = RoundedCornerShape(4.dp)
private val SwatchSize = 18.dp
private val PopoverWidth = 300.dp

/** How far the popover sits from the card it belongs to, in pixels. */
private const val POPOVER_GAP = 4

/**
 * One production pool, as a reading of the tasks it holds.
 *
 * Nothing here writes a pool record, because there is none to write: PLAN 12.10
 * makes a pool a reflection of the same tasks the game table shows, and what a
 * user changes from here goes onto that task through the one editing
 * transaction.
 *
 * A column of cards rather than a table. The table is wide because a game has
 * six cells side by side; a pool holds one kind of thing, so it reads down the
 * page and needs no sideways scrolling at any width.
 */
@Composable
fun PoolScreen(controller: PoolController) {
    LaunchedEffect(controller) { controller.observePool() }
    LaunchedEffect(controller) { controller.observeColorCatalogue() }

    val state = controller.state
    Column(
        modifier = Modifier.fillMaxHeight().widthIn(max = MaxContentWidth).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(poolNavigationNameOf(state.poolType)),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(poolDescriptionOf(state.poolType)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val content = state.content) {
            PoolContentState.Loading -> Message(stringResource(Strings.Pool.loading))
            PoolContentState.Failed -> Message(stringResource(Strings.Pool.error))
            is PoolContentState.Content ->
                if (content.model.isEmpty) {
                    Message(stringResource(Strings.Pool.empty))
                } else {
                    PoolBody(content.model, controller)
                }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The pool's own rows.
 *
 * The 3D pool is three sections in PLAN 12.10's order; the other three are one
 * list, because PLAN 12.11 to 12.13 describe them by their stages and their kind
 * and none of them carries a colour at all.
 *
 * An empty section is left out rather than drawn empty: a heading over nothing
 * says a group exists that does not.
 */
@Composable
private fun PoolBody(
    model: PoolModel,
    controller: PoolController,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        when (model) {
            is PoolModel.Flat ->
                items(
                    count = model.tasks.size,
                    key = { model.tasks[it].taskId.toString() },
                ) { at ->
                    val task = model.tasks[at]
                    TaskCard(task = task, card = PoolCardKey(task.taskId), controller = controller)
                }

            is PoolModel.ThreeD -> {
                val sections = model.sections
                if (!sections.awaitingColor.isEmpty) {
                    item {
                        SectionHeading(
                            title = stringResource(Strings.Pool.sectionAwaitingColor),
                            taskCount = sections.awaitingColor.taskCount,
                            requiredTotal = sections.awaitingColor.requiredTotal,
                            missingTotal = sections.awaitingColor.missingTotal,
                            failureTotal = sections.awaitingColor.failureTotal,
                        )
                    }
                    items(
                        count = sections.awaitingColor.tasks.size,
                        key = {
                            sections.awaitingColor.tasks[it]
                                .taskId
                                .toString()
                        },
                    ) { at ->
                        val task = sections.awaitingColor.tasks[at]
                        TaskCard(task = task, card = PoolCardKey(task.taskId), controller = controller)
                    }
                }
                if (sections.singleColorGroups.isNotEmpty()) {
                    item { SectionTitle(stringResource(Strings.Pool.sectionSingleColor)) }
                    colorGroups(sections.singleColorGroups, controller)
                }
                if (sections.multicolorGroups.isNotEmpty()) {
                    item { SectionTitle(stringResource(Strings.Pool.sectionMulticolor)) }
                    colorGroups(sections.multicolorGroups, controller)
                }
            }
        }
    }
}

/**
 * One colour's heading and the tasks under it.
 *
 * The key of a card is the colour and the task together, because the same task
 * really is in several of these groups (PLAN 12.10) and a key of the task alone
 * would be the same key twice.
 */
private fun LazyListScope.colorGroups(
    groups: List<PoolColorGroup>,
    controller: PoolController,
) {
    groups.forEach { group ->
        item(key = "group-" + group.color.colorId) { ColorGroupHeading(group) }
        items(
            count = group.tasks.size,
            key = { at -> "" + group.color.colorId + group.tasks[at].taskId },
        ) { at ->
            val task = group.tasks[at]
            TaskCard(
                task = task,
                card = PoolCardKey(task.taskId, group.color.colorId),
                controller = controller,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
}

/** What a section of the 3D pool holds, said in numbers. */
@Composable
private fun SectionHeading(
    title: String,
    taskCount: Int,
    requiredTotal: Int,
    missingTotal: Int,
    failureTotal: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(title)
        Summary(taskCount, requiredTotal, missingTotal, failureTotal)
    }
}

/**
 * The numbers under a heading.
 *
 * PLAN 12.10 puts the count, the total needed and what has gone wrong at the
 * head of a group. What is zero is not drawn: a group with no shortage says
 * nothing about shortages rather than showing a nought on every line for the
 * sake of the rare one that matters.
 */
@Composable
private fun Summary(
    taskCount: Int,
    requiredTotal: Int,
    missingTotal: Int,
    failureTotal: Long,
) {
    Text(
        text =
            if (requiredTotal > 0) {
                stringResource(Strings.Pool.groupSummary, taskCount.toString(), requiredTotal.toString())
            } else {
                stringResource(Strings.Pool.groupSummaryUnknown, taskCount.toString())
            },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (missingTotal > 0) {
        Text(
            text = stringResource(Strings.Pool.groupMissing, missingTotal.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (failureTotal > 0) {
        Text(
            text = stringResource(Strings.Pool.groupFailures, failureTotal.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The head of one colour group.
 *
 * The swatch and the written name together, never the swatch alone: PLAN 17 will
 * not have a colour be the only thing carrying a meaning, and two colours the
 * user has given the same value would otherwise be one heading twice.
 */
@Composable
private fun ColorGroupHeading(group: PoolColorGroup) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { heading() },
        ) {
            Swatch(group.color)
            Text(
                text = group.color.canonicalName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Summary(group.taskCount, group.requiredTotal, group.missingTotal, group.failureTotal)
    }
}

/** A square of one colour, with an edge that can be seen on any background. */
@Composable
private fun Swatch(color: PoolColor) {
    val fill = opaqueColorOf(color.hex)
    Box(
        modifier =
            Modifier
                .size(SwatchSize)
                .clip(SwatchShape)
                .background(fill)
                .border(1.dp, visibleEdgeOn(fill), SwatchShape),
    )
}

/**
 * One task, wherever it is shown.
 *
 * The whole card is the one thing that opens the task, so there is a single
 * place for the keyboard to land and a single thing a reader hears — PLAN 17
 * asks for the popover to be reachable from the keyboard, and a card scattered
 * with small targets would be several. The colour chips are drawn inside it and take
 * no focus of their own: they say what the task is made in, they are not choices.
 *
 * The stage list, where there is one, is the exception: it is a second control
 * with its own name, because folding it away is a different act from opening the
 * task and PLAN 12.11 asks for both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(
    task: PoolTask,
    card: PoolCardKey,
    controller: PoolController,
) {
    val open = stringResource(Strings.Pool.open, task.name)
    val spoken = spokenTaskOf(task, card)
    val focus = remember { FocusRequester() }
    val state = controller.state
    val isOpenHere = state.work != null && state.focusCard == card
    // The keyboard comes back to the card the surface was opened from, not to
    // the first card that names the same task: a multi-colour task is on several
    // of them and the user only pressed one.
    LaunchedEffect(state.focusRecall) {
        if (state.work == null && controller.cardToFocus == card) runCatching { focus.requestFocus() }
    }

    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .focusOutline(CardShape)
                        .clickable(onClickLabel = open) { controller.openTaskMenu(card) }
                        .semantics(mergeDescendants = true) { contentDescription = spoken }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = task.gameName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // A total when there is one, drawn to be read at a glance. A
                    // task with none says so quietly instead: PLAN 12.10 asks for
                    // the number, and a task that has none has nothing to shout.
                    Text(
                        text =
                            task.requiredQuantity?.let { stringResource(Strings.Pool.quantity, it.toString()) }
                                ?: stringResource(Strings.Pool.quantityUnknown),
                        style =
                            if (task.requiredQuantity != null) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.labelSmall
                            },
                        color =
                            if (task.requiredQuantity != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        fontWeight = if (task.requiredQuantity != null) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                if (task.colors.isNotEmpty()) {
                    ColorChips(task = task, current = card.colorId)
                }
                TaskFacts(task)
                if (task.stages.isNotEmpty()) {
                    StageBadge(task = task, controller = controller)
                }
                if (task.trackingMode == TrackingMode.CHECKLIST || task.trackingMode == TrackingMode.COUNTED) {
                    SpecialFacts(task)
                }
            }
            if (isOpenHere) TaskPopover(controller)
        }
    }
}

/**
 * Every colour the task is made in, in the user's own order.
 *
 * All of them, always. A multi-colour task is one task made in several colours
 * (PLAN 12.7), so showing only the group's own colour would describe a task that
 * does not exist. The group's colour is marked instead — in writing as well as
 * by the ring, so which group is being read does not rest on a border alone.
 *
 * The chips wrap rather than run off the side, and none of them is clickable:
 * the whole card is the one thing that opens the task.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChips(
    task: PoolTask,
    current: dev.pnptracker.domain.model.EntityId?,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
    ) {
        task.colors.forEach { color ->
            val fill = opaqueColorOf(color.hex)
            val isCurrent = color.colorId == current
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .clip(SwatchShape)
                        .background(fill)
                        .border(
                            width = if (isCurrent) 2.dp else 1.dp,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else visibleEdgeOn(fill),
                            shape = SwatchShape,
                        ).padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = color.canonicalName,
                    style = MaterialTheme.typography.labelSmall,
                    color = readableInkOn(fill),
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** What has happened to the task so far, in the words PLAN 12.10 asks for. */
@Composable
private fun TaskFacts(task: PoolTask) {
    Text(
        text =
            stringResource(
                if (task.primaryBatchCompleted) Strings.Pool.primaryDone else Strings.Pool.primaryPending,
            ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (task.currentMissingQuantity > 0) {
        NoteLine(
            text = stringResource(Strings.Pool.missing, task.currentMissingQuantity.toString()),
            isProblem = true,
        )
    }
    if (task.failureTotal > 0) {
        NoteLine(text = stringResource(Strings.Pool.failures, task.failureTotal.toString()), isProblem = false)
    }
}

/**
 * What kind of special work this is, and how much of it is left.
 *
 * PLAN 12.13 lets a special task be a checklist or a counted one, and the two
 * are read differently: one is done or not, the other has a number still to go.
 * Which it is is said in words, because the difference is not visible anywhere
 * else on the card.
 */
@Composable
private fun SpecialFacts(task: PoolTask) {
    Text(
        text =
            stringResource(
                if (task.trackingMode == TrackingMode.CHECKLIST) Strings.Pool.checklist else Strings.Pool.counted,
            ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (task.trackingMode == TrackingMode.COUNTED && task.requiredQuantity != null) {
        Text(
            text =
                stringResource(
                    Strings.Pool.remaining,
                    (task.requiredQuantity - if (task.primaryBatchCompleted) task.requiredQuantity else 0)
                        .coerceAtLeast(task.currentMissingQuantity)
                        .toString(),
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The stage the work has reached, and its counters when asked for.
 *
 * PLAN 12.11 puts the first unfinished stage on the badge, or `Tamamlandı` when
 * there is none left. Expanding it shows what each stage stands at and nothing
 * more: changing a counter belongs to a later slice, so there is no control here
 * that would write one, and opening this asks the database nothing.
 *
 * Its own named control rather than part of the card, so the keyboard reaches
 * the task and the detail separately instead of one swallowing the other.
 */
@Composable
private fun StageBadge(
    task: PoolTask,
    controller: PoolController,
) {
    val showing = controller.isShowingStages(task.taskId)
    val label =
        task.firstUnfinishedStage
            ?.let { stringResource(Strings.Pool.stageBadge, stringResource(stageNameOf(it))) }
            ?: stringResource(Strings.Pool.stageDone)
    val toggle =
        stringResource(
            if (showing) Strings.Pool.stageDetailsClose else Strings.Pool.stageDetailsOpen,
            task.name,
        )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(
            onClick = { controller.toggleStageDetails(task.taskId) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.focusOutline(CardShape).semantics { contentDescription = toggle },
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
        if (showing) {
            task.stages.forEach { stage ->
                Text(
                    text =
                        task.requiredQuantity?.let {
                            stringResource(
                                Strings.Pool.stageCountOf,
                                stringResource(stageNameOf(stage.stage)),
                                stage.completedQuantity.toString(),
                                it.toString(),
                            )
                        } ?: stringResource(
                            Strings.Pool.stageCount,
                            stringResource(stageNameOf(stage.stage)),
                            stage.completedQuantity.toString(),
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * Everything a reader hears about one card, once.
 *
 * The name, the game and the total, and then every colour in the user's own
 * order — PLAN 17 will not have a colour carry a meaning on its own, and a
 * reader who cannot see the chips would otherwise be told the task is made in
 * nothing. The whole card is one node, so none of this is read twice.
 */
@Composable
private fun spokenTaskOf(
    task: PoolTask,
    card: PoolCardKey,
): String {
    val quantity =
        task.requiredQuantity?.let { stringResource(Strings.Pool.quantity, it.toString()) }
            ?: stringResource(Strings.Pool.quantityUnknown)
    val head = stringResource(Strings.Pool.spokenTask, task.name, task.gameName, quantity)
    if (task.colors.isEmpty()) return head
    val colors = stringResource(Strings.Pool.colors, task.colors.joinToString { it.canonicalName })
    val current =
        card.colorId
            ?.let { id -> task.colors.firstOrNull { it.colorId == id } }
            ?.let { stringResource(Strings.Pool.currentColor, it.canonicalName) }
    return listOfNotNull(head, colors, current).joinToString(" ")
}

/**
 * The menu and the panels, anchored to the card they were opened from.
 *
 * The same panel the game table opens over a task, driven by the same interface
 * and ending in the same transaction. What differs is only what it is hung off:
 * a word inside a cell there, a card here.
 */
@Composable
private fun TaskPopover(controller: PoolController) {
    val state = controller.state
    val work = state.work ?: return
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(work::class) { runCatching { focus.requestFocus() } }

    val confirm = {
        when (work) {
            is PoolWork.Editing -> if (work.editor.canSave) scope.launch { controller.saveTaskEdit() } else Unit
            is PoolWork.ConfirmingConvert ->
                if (!work.isSaving) scope.launch { controller.confirmConvertToText() } else Unit

            else -> Unit
        }
    }

    // Anchored to the card rather than dropped wherever the popup lands. Without
    // this it opened on top of the very card it was about, hiding the task the
    // user had just pressed. The same arithmetic the table uses over a word:
    // above when there is room, below when there is not, never past the edge.
    val provider = remember { AnchoredAboveWord(POPOVER_GAP) }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = { if (!work.hasUnsavedChanges) controller.closeInnermost() },
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier =
                Modifier
                    .widthIn(max = PopoverWidth)
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Escape -> {
                                controller.closeInnermost()
                                true
                            }

                            event.isCtrlPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                                confirm()
                                true
                            }

                            else -> false
                        }
                    },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (work) {
                    is PoolWork.Menu -> TaskMenuActions(work, controller)
                    is PoolWork.Editing ->
                        TaskEditPanel(
                            editor = work.editor,
                            colors = controller.colorsOffered(),
                            catalogue = controller.catalogue,
                            host = controller,
                        )

                    is PoolWork.ConfirmingConvert -> ConvertConfirmation(work, controller)
                }
            }
        }
    }
}

/**
 * What can be done to a task from a pool.
 *
 * Only what the application already does to a task, and only through the paths
 * it already has. PLAN 12.5 also lists `Eksik parça`, and PLAN 18 gives
 * shortages to a later slice — so it is absent rather than present and dead.
 */
@Composable
private fun TaskMenuActions(
    work: PoolWork.Menu,
    controller: PoolController,
) {
    Text(
        text = work.task.name,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    val edit = stringResource(Strings.TaskMenu.edit)
    val convert = stringResource(Strings.TaskMenu.convertToText)
    TextButton(
        onClick = { controller.beginTaskEdit() },
        modifier = Modifier.fillMaxWidth().focusOutline(CardShape).semantics { contentDescription = edit },
    ) {
        Text(text = edit, style = MaterialTheme.typography.labelMedium)
    }
    TextButton(
        onClick = { controller.beginConvertToText() },
        modifier = Modifier.fillMaxWidth().focusOutline(CardShape).semantics { contentDescription = convert },
    ) {
        Text(text = convert, style = MaterialTheme.typography.labelMedium)
    }
    NoteLine(text = stringResource(Strings.TaskMenu.hint), isProblem = false)
}

/** Asking whether a task really should go back to being words. */
@Composable
private fun ConvertConfirmation(
    work: PoolWork.ConfirmingConvert,
    controller: PoolController,
) {
    val scope = rememberCoroutineScope()
    Text(text = stringResource(Strings.TaskConvert.title), style = MaterialTheme.typography.labelLarge)
    Text(
        text = stringResource(Strings.TaskConvert.body, work.task.name),
        style = MaterialTheme.typography.bodySmall,
    )
    if (work.hasProgress) {
        NoteLine(text = stringResource(Strings.TaskConvert.historyWarning), isProblem = true)
    }
    NoteLine(text = stringResource(Strings.TaskConvert.irreversible), isProblem = false)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val go = stringResource(Strings.TaskConvert.accept)
        val stop = stringResource(Strings.TaskConvert.cancel)
        TextButton(
            onClick = { if (!work.isSaving) scope.launch { controller.confirmConvertToText() } },
            enabled = !work.isSaving,
            modifier = Modifier.focusOutline(CardShape).semantics { contentDescription = go },
        ) {
            Text(text = go, style = MaterialTheme.typography.labelMedium)
        }
        TextButton(
            onClick = controller::closeInnermost,
            modifier = Modifier.focusOutline(CardShape).semantics { contentDescription = stop },
        ) {
            Text(text = stop, style = MaterialTheme.typography.labelMedium)
        }
    }
}
