package dev.pnptracker.ui.feature.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.domain.games.CellSummary
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.flow.collect

/**
 * Setting up the games the user tracks and the cells in them.
 *
 * Both lists are read as streams, so a game or a cell that is opened shows up
 * without anything here having to remember to reload.
 *
 * Nothing on this path touches an import. Games and cells exist because someone
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
    private var cellsOfOpenGame: List<CellSummary> = emptyList()

    /** Collects the games list until cancelled. */
    suspend fun observeGames() {
        setup.observeGames().collect { list ->
            games = list
            state =
                state.copy(
                    list = if (list.isEmpty()) GamesListState.Empty else GamesListState.Content(list),
                    detail = detailFor(state.openGameId, cellsOfOpenGame),
                )
        }
    }

    /** Puts a game in focus; the screen starts collecting its cells in response. */
    fun openGame(gameId: EntityId) {
        cellsOfOpenGame = emptyList()
        state =
            state.copy(
                openGameId = gameId,
                detail = GameDetailState.Loading,
                failure = null,
            )
    }

    /** Collects the open game's cells until cancelled. */
    suspend fun observeCells(gameId: EntityId) {
        setup.observeCells(gameId).collect { cells ->
            cellsOfOpenGame = cells
            state = state.copy(detail = detailFor(gameId, cells))
        }
    }

    fun closeGame() {
        cellsOfOpenGame = emptyList()
        state = state.copy(openGameId = null, detail = GameDetailState.Loading, failure = null)
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

    suspend fun saveGame() {
        val composer = state.gameComposer ?: return
        if (!composer.canSave || isSaving) return
        runSaving {
            setup.createGame(composer.name)
            state = state.copy(gameComposer = null)
        }
    }

    /**
     * Opens one of the game's columns, or does nothing when it is already open.
     *
     * A cell has no name to type: which column it is says everything about it,
     * so this is one action rather than a form.
     */
    suspend fun openCell(columnType: CellColumnType) {
        val gameId = state.openGameId ?: return
        runSaving { setup.openCell(gameId, columnType) }
    }

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
        cells: List<CellSummary>,
    ): GameDetailState {
        if (gameId == null) return GameDetailState.Loading
        val game = games.firstOrNull { it.id == gameId } ?: return GameDetailState.Unavailable
        return if (cells.isEmpty()) GameDetailState.Empty(game) else GameDetailState.Content(game, cells)
    }
}
