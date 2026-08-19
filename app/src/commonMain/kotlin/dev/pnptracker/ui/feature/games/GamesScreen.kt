package dev.pnptracker.ui.feature.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.ui.Strings
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
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = controller.state

    LaunchedEffect(Unit) { controller.observeGames() }

    val openGameId = state.openGameId
    LaunchedEffect(openGameId) { openGameId?.let { controller.observeItems(it) } }

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
                isSaving = controller.isSaving,
                onBack = { controller.closeGame() },
                onStartComposer = { controller.startItemComposer() },
                onEditName = { name -> controller.editItemName(name) },
                onSave = { scope.launch { controller.saveItem() } },
                onDiscard = { controller.cancelItemComposer() },
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

@Composable
private fun GameDetail(
    state: GamesScreenState,
    isSaving: Boolean,
    onBack: () -> Unit,
    onStartComposer: () -> Unit,
    onEditName: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
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
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GameHeader(detail.game, isSaving, onSetCompleted)
                ItemComposerOrButton(state, isSaving, onStartComposer, onEditName, onSave, onDiscard)
                MessageCard(
                    title = stringResource(Strings.Games.itemsEmpty),
                    body = stringResource(Strings.Games.itemsEmptyHint),
                )
            }

        is GameDetailState.Content ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GameHeader(detail.game, isSaving, onSetCompleted)
                ItemComposerOrButton(state, isSaving, onStartComposer, onEditName, onSave, onDiscard)
                Text(
                    text = stringResource(Strings.Games.itemsTitle),
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize().widthIn(max = MAX_CONTENT_WIDTH),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(detail.items, key = { it.id.toString() }) { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(text = item.name, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }
    }
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
private fun ItemComposerOrButton(
    state: GamesScreenState,
    isSaving: Boolean,
    onStartComposer: () -> Unit,
    onEditName: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    if (state.itemComposer == null) {
        Button(onClick = onStartComposer, enabled = !isSaving) {
            Text(stringResource(Strings.Games.itemCreate))
        }
    } else {
        NameForm(
            composer = state.itemComposer,
            label = Strings.Games.itemNameLabel,
            requiredMessage = Strings.Games.itemNameRequired,
            saveLabel = Strings.Games.itemSave,
            isSaving = isSaving,
            onEditName = onEditName,
            onSave = onSave,
            onDiscard = onDiscard,
        )
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
