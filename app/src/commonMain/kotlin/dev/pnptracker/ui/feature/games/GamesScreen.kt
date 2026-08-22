package dev.pnptracker.ui.feature.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.games.CellSummary
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskSetupFailure
import dev.pnptracker.domain.tasks.TaskSummary
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.columnNameOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val MAX_CONTENT_WIDTH = 720.dp

/**
 * The games the user is tracking, and the items under each one.
 *
 * This is where the target structure an import will eventually write into gets
 * built, and it is built by hand: nothing here is derived from a spreadsheet.
 */
@Composable
fun GamesScreen(
    controller: GamesController,
    tasks: GameTasksController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = controller.state

    LaunchedEffect(Unit) { controller.observeGames() }

    val openGameId = state.openGameId
    LaunchedEffect(openGameId) { openGameId?.let { controller.observeCells(it) } }
    LaunchedEffect(openGameId) {
        // Cleared first, so the game being opened never shows the last one's
        // tasks for the moment before its own arrive.
        tasks.forget()
        openGameId?.let { tasks.observeTasks(it) }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (openGameId == null) {
            GamesList(
                state = state,
                isSaving = controller.isSaving,
                onOpen = { gameId -> controller.openGame(gameId) },
                onStartComposer = { controller.startGameComposer() },
                onEditName = { name -> controller.editGameName(name) },
                onSave = { scope.launch { controller.saveGame() } },
                onDiscard = { controller.cancelGameComposer() },
                onSetCompleted = { game -> scope.launch { controller.setCompleted(game.id, !game.isManuallyCompleted) } },
            )
        } else {
            GameDetail(
                state = state,
                tasks = tasks,
                isSaving = controller.isSaving,
                onBack = { controller.closeGame() },
                onOpenCell = { columnType -> scope.launch { controller.openCell(columnType) } },
                onSetCompleted = { game -> scope.launch { controller.setCompleted(game.id, !game.isManuallyCompleted) } },
            )
        }
    }
}

@Composable
private fun GamesList(
    state: GamesScreenState,
    isSaving: Boolean,
    onOpen: (EntityId) -> Unit,
    onStartComposer: () -> Unit,
    onEditName: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onSetCompleted: (GameSummary) -> Unit,
) {
    Text(
        text = stringResource(Strings.ScreenTitles.games),
        style = MaterialTheme.typography.headlineSmall,
    )
    FailureLine(state.failure)

    if (state.gameComposer == null) {
        Button(onClick = onStartComposer, enabled = !isSaving) {
            Text(stringResource(Strings.Games.create))
        }
    } else {
        NameForm(
            composer = state.gameComposer,
            label = Strings.Games.nameLabel,
            requiredMessage = Strings.Games.nameRequired,
            saveLabel = Strings.Games.save,
            isSaving = isSaving,
            onEditName = onEditName,
            onSave = onSave,
            onDiscard = onDiscard,
        )
    }

    when (val list = state.list) {
        GamesListState.Loading -> BusyRow(stringResource(Strings.Games.loading))

        GamesListState.Empty ->
            MessageCard(
                title = stringResource(Strings.Games.emptyTitle),
                body = stringResource(Strings.Games.emptyHint),
            )

        is GamesListState.Content ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().widthIn(max = MAX_CONTENT_WIDTH),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list.games, key = { it.id.toString() }) { game ->
                    GameRow(
                        game = game,
                        isSaving = isSaving,
                        onOpen = { onOpen(game.id) },
                        onSetCompleted = { onSetCompleted(game) },
                    )
                }
            }
    }
}

@Composable
private fun GameRow(
    game: GameSummary,
    isSaving: Boolean,
    onOpen: () -> Unit,
    onSetCompleted: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = game.name, style = MaterialTheme.typography.bodyLarge)
                if (game.isManuallyCompleted) {
                    Text(
                        text = stringResource(Strings.Games.completedBadge),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) { Text(stringResource(Strings.Games.open)) }
                OutlinedButton(onClick = onSetCompleted, enabled = !isSaving) {
                    Text(
                        stringResource(
                            if (game.isManuallyCompleted) Strings.Games.markActive else Strings.Games.markCompleted,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * One game: what it is, the cells it has opened, and its tasks by pool.
 *
 * The whole detail is a single scrolling list. Two lists that scroll inside a
 * column that also scrolls is how the review workspace once ended up measuring
 * itself as infinitely tall, and a game with many tasks is exactly the shape
 * that would find it again.
 */
@Composable
private fun GameDetail(
    state: GamesScreenState,
    tasks: GameTasksController,
    isSaving: Boolean,
    onBack: () -> Unit,
    onOpenCell: (CellColumnType) -> Unit,
    onSetCompleted: (GameSummary) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(stringResource(Strings.Games.back)) }
    }
    FailureLine(state.failure)

    when (val detail = state.detail) {
        GameDetailState.Loading -> BusyRow(stringResource(Strings.Games.loading))

        GameDetailState.Unavailable ->
            MessageCard(
                title = stringResource(Strings.Games.unavailableTitle),
                body = stringResource(Strings.Games.unavailable),
            )

        is GameDetailState.Empty ->
            DetailBody(detail.game, emptyList(), tasks, isSaving, onOpenCell, onSetCompleted)

        is GameDetailState.Content ->
            DetailBody(detail.game, detail.cells, tasks, isSaving, onOpenCell, onSetCompleted)
    }
}

@Composable
private fun DetailBody(
    game: GameSummary,
    cells: List<CellSummary>,
    tasks: GameTasksController,
    isSaving: Boolean,
    onOpenCell: (CellColumnType) -> Unit,
    onSetCompleted: (GameSummary) -> Unit,
) {
    // Read here, in composition, rather than inside the list builder below, so
    // the screen redraws on a change without depending on when that builder runs.
    val tasksState = tasks.state
    val isSavingTask = tasks.isSaving

    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = MAX_CONTENT_WIDTH),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { GameHeader(game, isSaving, onSetCompleted) }

        item(key = "cells-title") {
            Text(text = stringResource(Strings.Games.cellsTitle), style = MaterialTheme.typography.titleMedium)
        }
        item(key = "cells-openers") { CellOpeners(cells, isSaving, onOpenCell) }
        if (cells.isEmpty()) {
            item(key = "cells-empty") {
                MessageCard(
                    title = stringResource(Strings.Games.cellsEmpty),
                    body = stringResource(Strings.Games.cellsEmptyHint),
                )
            }
        } else {
            items(cells, key = { "cell-${it.id}" }) { cell ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(columnNameOf(cell.columnType)), modifier = Modifier.padding(12.dp))
                }
            }
        }

        tasksSection(tasksState, isSavingTask, cells, tasks)
    }
}

/**
 * The game's tasks, under a pool heading each.
 *
 * The cells above stay where they are: this section lists tasks and offers the
 * form that makes one, and it never becomes a second place to open cells.
 */
private fun LazyListScope.tasksSection(
    tasksState: GameTasksScreenState,
    isSaving: Boolean,
    cells: List<CellSummary>,
    tasks: GameTasksController,
) {
    item(key = "tasks-title") {
        Text(text = stringResource(Strings.Tasks.title), style = MaterialTheme.typography.titleMedium)
    }
    item(key = "tasks-failure") { TaskFailureLine(tasksState.failure) }
    item(key = "tasks-composer") { TaskComposerOrButton(tasksState.composer, isSaving, cells, tasks) }

    when (val shown = tasksState.tasks) {
        GameTasksState.Loading -> item(key = "tasks-loading") { BusyRow(stringResource(Strings.Tasks.loading)) }

        GameTasksState.Empty ->
            item(key = "tasks-empty") {
                MessageCard(
                    title = stringResource(Strings.Tasks.empty),
                    body = stringResource(Strings.Tasks.emptyHint),
                )
            }

        is GameTasksState.Content ->
            shown.groups.forEach { group ->
                item(key = "pool-${group.poolType}") {
                    Text(
                        text = stringResource(labelOf(group.poolType)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(group.tasks, key = { "task-${it.id}" }) { task -> TaskRow(task) }
            }
    }
}

/**
 * One task.
 *
 * It names the item it belongs to, because the sections above it are pools
 * rather than items. An unknown amount is said to be unknown rather than shown
 * as a zero nobody typed. Nothing here says whether the task is finished.
 */
@Composable
private fun TaskRow(task: TaskSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = task.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(Strings.Tasks.rowItem, task.columnType),
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        task.requiredQuantity?.let { stringResource(Strings.Tasks.rowQuantity, it.toString()) }
                            ?: stringResource(Strings.Tasks.rowQuantityUnknown),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = stringResource(labelOf(task.trackingMode)), style = MaterialTheme.typography.labelMedium)
                if (task.isFromImport) {
                    Text(
                        text = stringResource(Strings.Tasks.rowFromImport),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            task.notes?.let { note -> Text(text = note, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun TaskComposerOrButton(
    composer: TaskComposer?,
    isSaving: Boolean,
    cells: List<CellSummary>,
    tasks: GameTasksController,
) {
    val scope = rememberCoroutineScope()

    val usable = cells.filter { it.columnType.holdsTasks }
    if (usable.isEmpty()) {
        Text(text = stringResource(Strings.Tasks.needsCell), style = MaterialTheme.typography.labelMedium)
        return
    }

    if (composer == null) {
        Button(onClick = { tasks.startComposer(usable.singleOrNull()?.id) }, enabled = !isSaving) {
            Text(stringResource(Strings.Tasks.create))
        }
        return
    }

    TaskForm(
        composer = composer,
        cells = cells,
        isSaving = isSaving,
        onChooseCell = { id -> tasks.chooseCell(id) },
        onEditName = { name -> tasks.editName(name) },
        onChoosePool = { pool -> tasks.choosePool(pool) },
        onChooseTracking = { mode -> tasks.chooseTracking(mode) },
        onEditQuantity = { quantity -> tasks.editQuantity(quantity) },
        onEditNotes = { notes -> tasks.editNotes(notes) },
        onSave = { scope.launch { tasks.save() } },
        onDiscard = { tasks.cancelComposer() },
    )
}

/**
 * The one form that makes a task.
 *
 * The pool decides the tracking mode wherever it leaves no choice, and only the
 * special pool, which allows two, asks. Saving stays out of reach until the form
 * is complete, and is refused while a save is already on its way.
 */
@Composable
private fun TaskForm(
    composer: TaskComposer,
    cells: List<CellSummary>,
    isSaving: Boolean,
    onChooseCell: (EntityId) -> Unit,
    onEditName: (String) -> Unit,
    onChoosePool: (PoolType) -> Unit,
    onChooseTracking: (TrackingMode) -> Unit,
    onEditQuantity: (String) -> Unit,
    onEditNotes: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().widthIn(max = MAX_CONTENT_WIDTH)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = composer.name,
                onValueChange = onEditName,
                label = { Text(stringResource(Strings.Tasks.nameLabel)) },
                isError = composer.name.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (composer.name.isBlank()) {
                RequiredLine(stringResource(Strings.Tasks.nameRequired))
            }

            Text(text = stringResource(Strings.Tasks.cellLabel), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cells.filter { it.columnType.holdsTasks }.forEach { cell ->
                    FilterChip(
                        selected = cell.id == composer.cellId,
                        enabled = !isSaving,
                        onClick = { onChooseCell(cell.id) },
                        label = { Text(stringResource(columnNameOf(cell.columnType))) },
                    )
                }
            }
            if (composer.cellId == null) RequiredLine(stringResource(Strings.Tasks.cellRequired))

            Text(text = stringResource(Strings.Tasks.poolLabel), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PoolType.entries.forEach { pool ->
                    FilterChip(
                        selected = pool == composer.poolType,
                        enabled = !isSaving,
                        onClick = { onChoosePool(pool) },
                        label = { Text(stringResource(labelOf(pool))) },
                    )
                }
            }
            if (composer.poolType == null) RequiredLine(stringResource(Strings.Tasks.poolRequired))

            if (composer.offersTrackingChoice) {
                val poolType = composer.poolType
                Text(text = stringResource(Strings.Tasks.trackingLabel), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    poolType?.let(::trackingModesOf).orEmpty().forEach { mode ->
                        FilterChip(
                            selected = mode == composer.trackingMode,
                            enabled = !isSaving,
                            onClick = { onChooseTracking(mode) },
                            label = { Text(stringResource(labelOf(mode))) },
                        )
                    }
                }
                if (composer.trackingMode == null) RequiredLine(stringResource(Strings.Tasks.trackingRequired))
            }

            OutlinedTextField(
                value = composer.quantity,
                onValueChange = onEditQuantity,
                label = { Text(stringResource(Strings.Tasks.quantityLabel)) },
                isError = composer.hasUnusableQuantity,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text =
                    if (composer.hasUnusableQuantity) {
                        stringResource(Strings.Tasks.quantityUnusable)
                    } else {
                        stringResource(Strings.Tasks.quantityHint)
                    },
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (composer.hasUnusableQuantity) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )

            OutlinedTextField(
                value = composer.notes,
                onValueChange = onEditNotes,
                label = { Text(stringResource(Strings.Tasks.notesLabel)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = composer.canSave && !isSaving) {
                    Text(stringResource(Strings.Tasks.save))
                }
                OutlinedButton(onClick = onDiscard, enabled = !isSaving) {
                    Text(stringResource(Strings.Games.discard))
                }
            }
        }
    }
}

@Composable
private fun RequiredLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun TaskFailureLine(failure: TaskSetupFailure?) {
    if (failure == null) return
    Text(
        text = stringResource(messageOf(failure)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun GameHeader(
    game: GameSummary,
    isSaving: Boolean,
    onSetCompleted: (GameSummary) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = game.name, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onSetCompleted(game) }, enabled = !isSaving) {
                Text(
                    stringResource(
                        if (game.isManuallyCompleted) Strings.Games.markActive else Strings.Games.markCompleted,
                    ),
                )
            }
            if (game.isManuallyCompleted) {
                Text(
                    text = stringResource(Strings.Games.completedBadge),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = stringResource(Strings.Games.completionNote),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun CellOpeners(
    cells: List<CellSummary>,
    isSaving: Boolean,
    onOpenCell: (CellColumnType) -> Unit,
) {
    val open = cells.map { it.columnType }.toSet()
    val closed = CellColumnType.entries.filterNot { it in open }
    if (closed.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        closed.forEach { columnType ->
            val name = stringResource(columnNameOf(columnType))
            OutlinedButton(onClick = { onOpenCell(columnType) }, enabled = !isSaving) {
                Text(stringResource(Strings.Games.cellOpen, name))
            }
        }
    }
}

@Composable
private fun NameForm(
    composer: NameComposer,
    label: StringResource,
    requiredMessage: StringResource,
    saveLabel: StringResource,
    isSaving: Boolean,
    onEditName: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().widthIn(max = MAX_CONTENT_WIDTH)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = composer.name,
                onValueChange = onEditName,
                label = { Text(stringResource(label)) },
                isError = !composer.canSave,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!composer.canSave) {
                Text(
                    text = stringResource(requiredMessage),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = composer.canSave && !isSaving) {
                    Text(stringResource(saveLabel))
                }
                OutlinedButton(onClick = onDiscard, enabled = !isSaving) {
                    Text(stringResource(Strings.Games.discard))
                }
            }
        }
    }
}

@Composable
private fun FailureLine(failure: GameSetupFailure?) {
    if (failure == null) return
    Text(
        text =
            stringResource(
                when (failure) {
                    GameSetupFailure.COULD_NOT_SAVE -> Strings.Games.errorCouldNotSave
                    GameSetupFailure.GAME_NOT_AVAILABLE -> Strings.Games.errorGameUnavailable
                },
            ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun BusyRow(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator()
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(max = MAX_CONTENT_WIDTH)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}
