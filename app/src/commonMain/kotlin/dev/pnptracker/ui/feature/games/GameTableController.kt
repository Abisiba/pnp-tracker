package dev.pnptracker.ui.feature.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.CellTextEditing
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.data.repository.GameTableSource
import dev.pnptracker.data.repository.TaskCreationFromText
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.rules.normalizeColorTerm
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import dev.pnptracker.domain.tasks.onlyTrackingModeOf
import dev.pnptracker.domain.tasks.splitForTaskName
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
    private val colors: ColorCatalogue,
    private val taskCreation: TaskCreationFromText,
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
     * Collects the colour catalogue until cancelled.
     *
     * Only the catalogue is replaced. A colour the user has already picked stays
     * picked even when the list changes underneath them; whether it is still
     * there at all is settled once, inside the transaction that writes the task.
     */
    suspend fun observeColorCatalogue() {
        colors.observeColors().collect { catalogue ->
            state = state.copy(colors = catalogue)
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
        if (state.editor != null || state.taskComposer != null) {
            state = blockedByOpenWork()
            return
        }
        if (view == state.view) return
        state = state.copy(view = view, rows = rowsFor(view), blockedByEditor = false)
    }

    /**
     * Says an action was refused, and calls the keyboard back to the open work.
     *
     * Refusing is only half of it. The click that was refused took the focus with
     * it, so without the recall the user would press Escape and reach the chip
     * they had just pressed rather than the editor it is meant to close, and
     * would have to go back to the mouse to get out of a state they never chose.
     */
    private fun blockedByOpenWork(): GameTableScreenState = state.copy(blockedByEditor = true, focusRecall = state.focusRecall + 1)

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
            if (existing.isOn(gameId, columnType) && state.taskComposer == null) return
            state = blockedByOpenWork()
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

    /**
     * Takes what has been typed.
     *
     * Refused while the task panel is open: the panel holds offsets into the text
     * as it stands, and letting a keystroke through would move the words out from
     * under a selection the user made.
     */
    fun editCellText(text: String) {
        val editor = state.editor ?: return
        if (editor.isSaving || state.taskComposer != null) return
        state = state.copy(editor = editor.copy(draft = text, failure = null, selectionFailure = null))
    }

    /** Closes the editor, and any panel opened from it, without writing anything. */
    fun cancelEditing() {
        state = state.copy(editor = null, taskComposer = null, blockedByEditor = false)
    }

    /**
     * Writes what has been typed.
     *
     * A save that is refused leaves the editor open with the draft untouched, so
     * the user can read what went wrong and still have their words.
     */
    suspend fun saveEditing() {
        val editor = state.editor ?: return
        if (editor.isSaving || state.taskComposer != null) return
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

    // --------------------------------------------- making a task out of words

    /**
     * Opens the task panel on the stretch of text the user selected.
     *
     * The offsets arrive counted across the whole cell, which is what the field
     * reports, and are handed to the one piece that wholly contains them. A
     * selection spanning two pieces, or landing on a task, is refused rather than
     * guessed at: cutting the wrong piece is exactly the damage the anchor exists
     * to prevent.
     *
     * Only ever called against text that is already stored. The panel is not
     * offered while there are unsaved changes, because the offsets would point
     * into a draft and the transaction cuts what the database holds. Saving on
     * the user's behalf to close that gap would be an automatic save PLAN does
     * not describe, so the answer is to ask them to save first.
     *
     * Nothing is written here, and nothing is written when it refuses.
     */
    fun beginTaskComposer(
        startOffset: Int,
        endOffset: Int,
    ) {
        val editor = state.editor ?: return
        if (state.taskComposer != null || editor.isSaving) return
        if (editor.hasChanges) return refuseSelection(TaskFromTextFailure.STALE_TEXT_SELECTION)

        val poolType = editor.columnType.poolType ?: return refuseSelection(TaskFromTextFailure.CELL_DOES_NOT_HOLD_TASKS)
        val cell = rowOf(editor.gameId)?.cell(editor.columnType) ?: return
        val selection =
            cell.locateSelection(editor.gameId, startOffset, endOffset)
                ?: return refuseSelection(TaskFromTextFailure.INVALID_SELECTION)
        // Narrowed here only to show the user the name they are about to make.
        // The transaction narrows the same offsets again over the stored text,
        // so what is written is decided there and not from this preview.
        val name =
            try {
                splitForTaskName(selection.expectedText, selection.startOffset, selection.endOffset).name
            } catch (refusal: TaskFromTextException) {
                return refuseSelection(refusal.failure)
            }

        state =
            state.copy(
                editor = editor.copy(selectionFailure = null),
                taskComposer =
                    TaskComposer(
                        selection = selection,
                        columnType = editor.columnType,
                        name = name,
                        // Settled where the pool leaves no choice, asked for where
                        // it does; never invented either way.
                        trackingMode = onlyTrackingModeOf(poolType),
                    ),
                blockedByEditor = false,
            )
    }

    fun editTaskColorQuery(query: String) {
        val composer = state.taskComposer ?: return
        if (composer.isSaving) return
        state = state.copy(taskComposer = composer.copy(colorQuery = query))
    }

    fun chooseTaskColor(colorId: EntityId) {
        val composer = state.taskComposer ?: return
        if (composer.isSaving) return
        state = state.copy(taskComposer = composer.copy(colorId = colorId, failure = null))
    }

    /** Takes the quantity as typed; what is not a usable number stays visible. */
    fun editTaskQuantity(text: String) {
        val composer = state.taskComposer ?: return
        if (composer.isSaving) return
        state = state.copy(taskComposer = composer.copy(quantityText = text, failure = null))
    }

    /** Takes the note exactly as typed, spaces and all. */
    fun editTaskNotes(text: String) {
        val composer = state.taskComposer ?: return
        if (composer.isSaving) return
        state = state.copy(taskComposer = composer.copy(notes = text))
    }

    fun chooseTaskTracking(trackingMode: TrackingMode) {
        val composer = state.taskComposer ?: return
        if (composer.isSaving) return
        state = state.copy(taskComposer = composer.copy(trackingMode = trackingMode, failure = null))
    }

    /**
     * Closes the panel and nothing else.
     *
     * The cell stays open with its text exactly as it was: giving up on making a
     * task is not giving up on what was written.
     */
    fun cancelTaskComposer() {
        if (state.taskComposer == null) return
        state = state.copy(taskComposer = null, focusRecall = state.focusRecall + 1)
    }

    /**
     * Writes the task, and closes the cell when it lands.
     *
     * A refusal leaves the panel standing with the colour, the quantity and the
     * note the user chose, so they can read what went wrong without losing the
     * answers they already gave.
     */
    suspend fun saveTask() {
        val composer = state.taskComposer ?: return
        if (composer.isSaving) return
        val colorId = composer.colorId ?: return
        val quantity = composer.quantity ?: return
        val trackingMode = composer.trackingMode ?: return

        state = state.copy(taskComposer = composer.copy(isSaving = true, failure = null))
        try {
            taskCreation.createSingleColorTask(
                selection = composer.selection,
                colorId = colorId,
                requiredQuantity = quantity,
                trackingMode = trackingMode,
                // An empty note is no note; anything else is kept as typed.
                notes = composer.notes.takeIf { it.isNotEmpty() },
            )
            // The cell now holds a task, so the whole-text editor has nothing
            // left to edit: it closes along with the panel.
            state = state.copy(taskComposer = null, editor = null, blockedByEditor = false)
        } catch (refusal: TaskFromTextException) {
            state = state.copy(taskComposer = state.taskComposer?.copy(isSaving = false, failure = refusal.failure))
        }
    }

    /**
     * The colours the panel is offering, narrowed by what has been typed.
     *
     * Matched on the catalogue's own folding of a name, so `gri`, `Gri` and `GRİ`
     * are the one colour they are on the user's keyboard. That folding is only
     * ever right for colour names — it merges the two Turkish i's — which is why
     * it is used here and nowhere near general search.
     */
    fun colorsOffered(): List<ColorSummary> {
        val query = state.taskComposer?.colorQuery.orEmpty()
        if (query.isBlank()) return state.colors
        val needle = normalizeColorTerm(query)
        return state.colors.filter { normalizeColorTerm(it.canonicalName).contains(needle) }
    }

    private fun refuseSelection(failure: TaskFromTextFailure) {
        val editor = state.editor ?: return
        state = state.copy(editor = editor.copy(selectionFailure = failure))
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
