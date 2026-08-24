package dev.pnptracker.ui.feature.games

import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskFromTextFailure

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
    /** Why the last stretch of text the user pointed at could not become a task. */
    val selectionFailure: TaskFromTextFailure? = null,
) {
    val hasChanges: Boolean get() = draft != originalText

    fun isOn(
        gameId: EntityId,
        columnType: CellColumnType,
    ): Boolean = this.gameId == gameId && this.columnType == columnType
}

/**
 * The task being made out of words the user selected.
 *
 * The selection is fixed when the panel opens and never moves again: it carries
 * the piece it was made in and what that piece said, so a save either lands
 * exactly where the user pointed or is refused. Nothing in here is stored until
 * they save, and closing the panel leaves the cell and its text as they were.
 *
 * The quantity is held as the user's own text rather than as a number. A field
 * that silently swallowed `1.5` or `-3` and showed something else would be
 * telling them their typing was accepted when it was not.
 *
 * [trackingMode] is settled from the pool where the pool leaves no choice, and
 * asked for where it genuinely does. It is never guessed: PLAN 5.6 stores it on
 * the task, so a value nobody chose would be a decision made on the user's
 * behalf and written to their database.
 */
data class TaskComposer(
    val selection: CellTextSelection,
    val columnType: CellColumnType,
    /** The selected words, with the whitespace at their edges already left behind. */
    val name: String,
    val colorQuery: String = "",
    val colorId: EntityId? = null,
    val quantityText: String = "",
    /** The user's own words, kept exactly; PLAN 5.6 stores a note as written. */
    val notes: String = "",
    val trackingMode: TrackingMode? = null,
    val isSaving: Boolean = false,
    val failure: TaskFromTextFailure? = null,
) {
    /** The quantity if it is a whole number greater than zero, and null otherwise. */
    val quantity: Int? get() = quantityText.toIntOrNull()?.takeIf { it > 0 }

    /** False once there is something in the field that is not a usable quantity. */
    val isQuantityUsable: Boolean get() = quantityText.isEmpty() || quantity != null

    val canSave: Boolean
        get() = !isSaving && colorId != null && quantity != null && trackingMode != null
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
    /** The task being made out of selected words, or null when none is. */
    val taskComposer: TaskComposer? = null,
    /** The whole colour catalogue, which the task panel picks one out of. */
    val colors: List<ColorSummary> = emptyList(),
    /** True when something was refused because a cell is still being edited. */
    val blockedByEditor: Boolean = false,
    /**
     * Bumped every time the keyboard has to be handed back to the open surface.
     *
     * Refusing an action is not enough on its own: the click that was refused
     * took the focus with it, so Escape would then reach a chip rather than the
     * editor it is meant to close. The screen watches this number and puts the
     * keyboard back where the work is.
     */
    val focusRecall: Int = 0,
)
