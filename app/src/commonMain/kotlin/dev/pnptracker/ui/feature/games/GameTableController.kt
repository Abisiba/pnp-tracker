package dev.pnptracker.ui.feature.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.data.repository.GameTableSource
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import kotlinx.coroutines.flow.collect

/**
 * The game table: which games it shows, and adding one.
 *
 * Every active game is collected once and kept. The three views of PLAN 12.4 are
 * worked out from those rows, so moving between them is a change of state and
 * nothing else — no query is re-run and nothing is written, which is what makes
 * them views of one table rather than three screens.
 *
 * Nothing here opens a cell or writes into one. Reading the table must not change
 * it, and a game with columns nobody has written in is drawn with empty ones
 * rather than having five rows created behind the user's back.
 */
class GameTableController(
    private val table: GameTableSource,
    private val setup: GameSetup,
) {
    var state: GameTableScreenState by mutableStateOf(GameTableScreenState())
        private set

    /** True while a change is on its way to the database. */
    var isSaving: Boolean by mutableStateOf(false)
        private set

    /** Every active game, whatever view is open. */
    private var allRows: List<GameTableRow> = emptyList()

    /** Collects the table until cancelled. */
    suspend fun observeTable() {
        table.observeTable().collect { rows ->
            allRows = rows
            state = state.copy(rows = rowsFor(state.view))
        }
    }

    /** Shows another view of the same rows. Reads nothing and writes nothing. */
    fun showView(view: GameTableView) {
        if (view == state.view) return
        state = state.copy(view = view, rows = rowsFor(view))
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
        isSaving = true
        try {
            setup.createGame(composer.name)
            state = state.copy(gameComposer = null, failure = null)
        } catch (failure: GameSetupException) {
            state = state.copy(failure = failure.failure)
        } finally {
            isSaving = false
        }
    }

    private fun rowsFor(view: GameTableView): GameTableRowsState {
        val visible = allRows.filter(view::includes)
        return if (visible.isEmpty()) {
            GameTableRowsState.Empty(view = view, hasGamesInOtherViews = allRows.isNotEmpty())
        } else {
            GameTableRowsState.Content(visible)
        }
    }
}
