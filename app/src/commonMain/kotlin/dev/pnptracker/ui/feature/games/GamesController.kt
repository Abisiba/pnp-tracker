package dev.pnptracker.ui.feature.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.games.ItemSummary
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.flow.collect

/**
 * Setting up the games and items the user tracks.
 *
 * Both lists are read as streams, so a game or an item that is created shows up
 * without anything here having to remember to reload.
 *
 * Nothing on this path touches an import. Games and items exist because someone
 * typed them, and finishing a game is the user's own statement: it changes that
 * one game and nothing underneath it.
 */
class GamesController(
    private val setup: GameSetup,
) {
    var state: GamesScreenState by mutableStateOf(GamesScreenState())
        private set

    /** True while a change is on its way to the database. */
    var isSaving: Boolean by mutableStateOf(false)
        private set

    private var games: List<GameSummary> = emptyList()
    private var itemsOfOpenGame: List<ItemSummary> = emptyList()

    /** Collects the games list until cancelled. */
    suspend fun observeGames() {
        setup.observeGames().collect { list ->
            games = list
            state =
                state.copy(
                    list = if (list.isEmpty()) GamesListState.Empty else GamesListState.Content(list),
                    detail = detailFor(state.openGameId, itemsOfOpenGame),
                )
        }
    }

    /** Puts a game in focus; the screen starts collecting its items in response. */
    fun openGame(gameId: EntityId) {
        itemsOfOpenGame = emptyList()
        state =
            state.copy(
                openGameId = gameId,
                detail = GameDetailState.Loading,
                itemComposer = null,
                failure = null,
            )
    }

    /** Collects the open game's items until cancelled. */
    suspend fun observeItems(gameId: EntityId) {
        setup.observeItems(gameId).collect { items ->
            itemsOfOpenGame = items
            state = state.copy(detail = detailFor(gameId, items))
        }
    }

    fun closeGame() {
        itemsOfOpenGame = emptyList()
        state = state.copy(openGameId = null, detail = GameDetailState.Loading, itemComposer = null, failure = null)
    }

    fun startGameComposer() {
        state = state.copy(gameComposer = NameComposer(""), failure = null)
    }

    fun editGameName(name: String) {
        state = state.copy(gameComposer = state.gameComposer?.copy(name = name))
    }

    /** Changes nothing anywhere; the game was never written. */
    fun cancelGameComposer() {
        state = state.copy(gameComposer = null)
    }

    fun startItemComposer() {
        state = state.copy(itemComposer = NameComposer(""), failure = null)
    }

    fun editItemName(name: String) {
        state = state.copy(itemComposer = state.itemComposer?.copy(name = name))
    }

    fun cancelItemComposer() {
        state = state.copy(itemComposer = null)
    }

    /**
     * Saves the game being typed, if it has a name.
     *
     * A name that is empty or only spaces is refused before anything is written.
     * A game that does not save leaves the form open with what was typed in it.
     */
    suspend fun saveGame() {
        val composer = state.gameComposer ?: return
        if (!composer.canSave || isSaving) return
        runSaving {
            setup.createGame(composer.name)
            state = state.copy(gameComposer = null)
        }
    }

    /** Saves the item being typed, under whichever game is open. */
    suspend fun saveItem() {
        val composer = state.itemComposer ?: return
        val gameId = state.openGameId ?: return
        if (!composer.canSave || isSaving) return
        runSaving {
            setup.createItem(gameId, composer.name)
            state = state.copy(itemComposer = null)
        }
    }

    /**
     * Records that the user considers a game finished, or takes it back.
     *
     * Reversible on purpose, and confined to the one game: nothing below it and no
     * other game is touched.
     */
    suspend fun setCompleted(
        gameId: EntityId,
        isCompleted: Boolean,
    ) {
        if (isSaving) return
        runSaving { setup.setGameCompleted(gameId, isCompleted) }
    }

    private suspend fun runSaving(work: suspend () -> Unit) {
        isSaving = true
        try {
            work()
            state = state.copy(failure = null)
        } catch (failure: GameSetupException) {
            state = state.copy(failure = failure.failure)
        } finally {
            isSaving = false
        }
    }

    private fun detailFor(
        gameId: EntityId?,
        items: List<ItemSummary>,
    ): GameDetailState {
        if (gameId == null) return GameDetailState.Loading
        val game = games.firstOrNull { it.id == gameId } ?: return GameDetailState.Unavailable
        return if (items.isEmpty()) GameDetailState.Empty(game) else GameDetailState.Content(game, items)
    }
}
