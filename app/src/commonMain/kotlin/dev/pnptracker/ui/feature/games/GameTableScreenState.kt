package dev.pnptracker.ui.feature.games

import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId

/** Where the table is. */
sealed interface GameTableRowsState {
    data object Loading : GameTableRowsState

    /**
     * Nothing to show in the view that is open.
     *
     * The view is carried along because the two empty tables mean different
     * things: a library with no games at all invites the user to start one, and
     * a `Tamamlanan` view with nothing in it simply means nothing is finished yet.
     */
    data class Empty(
        val view: GameTableView,
        val hasGamesInOtherViews: Boolean,
    ) : GameTableRowsState

    data class Content(
        val rows: List<GameTableRow>,
    ) : GameTableRowsState
}

/** A name the user is typing, before anything is written. */
data class NameComposer(
    val name: String,
) {
    val canSave: Boolean get() = name.isNotBlank()
}

/**
 * One cell being written in.
 *
 * The draft is held here rather than inside the text field, so a fresh list
 * arriving from the database cannot wipe out what the user is halfway through
 * typing: the rows are replaced and this is left exactly where it was.
 *
 * [isSaving] and [failure] are separate from the draft for the same reason. A
 * save that fails leaves all three standing — the editor open, the text as it
 * was typed, and a line saying what went wrong — because the alternative is
 * throwing away work the user has not agreed to lose.
 */
data class CellEditor(
    val gameId: EntityId,
    val columnType: CellColumnType,
    /** What the cell said when the editor opened, so a no-op save is recognisable. */
    val originalText: String,
    val draft: String,
    val isSaving: Boolean = false,
    val failure: CellTextFailure? = null,
) {
    val hasChanges: Boolean get() = draft != originalText

    fun isOn(
        gameId: EntityId,
        columnType: CellColumnType,
    ): Boolean = this.gameId == gameId && this.columnType == columnType
}

/**
 * What the table section is showing, what is being typed, and what did not save.
 *
 * The view lives here and nowhere else. It is not stored: PLAN asks for three
 * views that are easy to move between, not for a setting, so it lasts as long as
 * the window does and costs no column and no file.
 */
data class GameTableScreenState(
    val view: GameTableView = GameTableView.ONGOING,
    val rows: GameTableRowsState = GameTableRowsState.Loading,
    val gameComposer: NameComposer? = null,
    val failure: GameSetupFailure? = null,
    /** The one cell being written in, or null while the table is only being read. */
    val editor: CellEditor? = null,
    /** True when something was refused because a cell is still being edited. */
    val blockedByEditor: Boolean = false,
)
