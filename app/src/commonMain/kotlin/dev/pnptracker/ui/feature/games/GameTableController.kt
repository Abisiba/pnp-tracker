package dev.pnptracker.ui.feature.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.CellTextEditing
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.data.repository.GameTableSource
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.flow.collect

/**
 * The game table: which games it shows, and adding one.
 *
 * Every active game is collected once and kept. The three views of PLAN 12.4 are
 * worked out from those rows, so moving between them is a change of state and
 * nothing else — no query is re-run and nothing is written, which is what makes
 * them views of one table rather than three screens.
 *
 * Reading the table never changes it: a game with columns nobody has written in
 * is drawn with empty ones rather than having five rows created behind the
 * user's back. A cell is opened only when the user writes something into it.
 *
 * One cell is written in at a time, and its draft lives here rather than in the
 * text field. A fresh list from the database replaces the rows and leaves the
 * draft alone, so a change to another game cannot wipe out what is being typed
 * in this one. Nothing saves on the user's behalf: PLAN describes no automatic
 * save, and one invented here would write words they never agreed to keep.
 */
class GameTableController(
    private val table: GameTableSource,
    private val setup: GameSetup,
    private val cells: CellTextEditing,
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

    /**
     * Shows another view of the same rows. Reads nothing and writes nothing.
     *
     * Refused while a cell is being written in. The row being edited may not be
     * in the view being moved to, and dropping the editor to make the move would
     * throw away typing the user never agreed to lose. Saving it for them would
     * be worse: PLAN describes no automatic save, so inventing one would write
     * something they never asked to keep.
     */
    fun showView(view: GameTableView) {
        if (state.editor != null) {
            state = state.copy(blockedByEditor = true)
            return
        }
        if (view == state.view) return
        state = state.copy(view = view, rows = rowsFor(view), blockedByEditor = false)
    }

    // -------------------------------------------------------- writing a cell

    /**
     * Opens one cell for writing.
     *
     * Only one cell is written in at a time. Asking for another while one is
     * open changes nothing and says so, rather than closing the first: a second
     * editor would either lose the first draft or save it behind the user's back.
     *
     * A cell holding a task has no [dev.pnptracker.domain.games.CellPreview.editableText],
     * so it cannot be opened at all — the screen says why instead.
     */
    fun beginEditing(
        gameId: EntityId,
        columnType: CellColumnType,
    ) {
        val existing = state.editor
        if (existing != null) {
            if (existing.isOn(gameId, columnType)) return
            state = state.copy(blockedByEditor = true)
            return
        }
        val cell = rowOf(gameId)?.cell(columnType) ?: return
        val text = cell.editableText ?: return
        state =
            state.copy(
                editor =
                    CellEditor(
                        gameId = gameId,
                        columnType = columnType,
                        originalText = text,
                        draft = text,
                    ),
                blockedByEditor = false,
            )
    }

    fun editCellText(text: String) {
        val editor = state.editor ?: return
        if (editor.isSaving) return
        state = state.copy(editor = editor.copy(draft = text, failure = null))
    }

    /** Closes the editor without writing anything at all. */
    fun cancelEditing() {
        state = state.copy(editor = null, blockedByEditor = false)
    }

    /**
     * Writes what has been typed.
     *
     * A save that is refused leaves the editor open with the draft untouched, so
     * the user can read what went wrong and still have their words.
     */
    suspend fun saveEditing() {
        val editor = state.editor ?: return
        if (editor.isSaving) return
        state = state.copy(editor = editor.copy(isSaving = true, failure = null))
        try {
            cells.savePlainText(editor.gameId, editor.columnType, editor.draft)
            state = state.copy(editor = null, blockedByEditor = false)
        } catch (failure: CellTextException) {
            state =
                state.copy(
                    editor = state.editor?.copy(isSaving = false, failure = failure.failure),
                )
        }
    }

    /** Clears the note saying an action was refused while a cell was open. */
    fun acknowledgeEditorBlock() {
        state = state.copy(blockedByEditor = false)
    }

    private fun rowOf(gameId: EntityId): GameTableRow? = allRows.firstOrNull { it.gameId == gameId }

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

    /**
     * What the open view shows.
     *
     * Only the rows are worked out here. The editor is never touched by a fresh
     * list: a change to some other game must not disturb what is being typed in
     * this one, and a row that leaves the view keeps its editor open — the user
     * is still writing in it.
     */
    private fun rowsFor(view: GameTableView): GameTableRowsState {
        val visible = allRows.filter(view::includes)
        return if (visible.isEmpty()) {
            GameTableRowsState.Empty(view = view, hasGamesInOtherViews = allRows.isNotEmpty())
        } else {
            GameTableRowsState.Content(visible)
        }
    }
}
