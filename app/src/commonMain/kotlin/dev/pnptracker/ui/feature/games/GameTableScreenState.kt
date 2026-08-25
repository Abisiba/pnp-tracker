package dev.pnptracker.ui.feature.games

import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.games.DocumentRun
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.games.documentText
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskEditFailure
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
 * The one thing the user is doing in one cell.
 *
 * A sealed set rather than a handful of flags, because the states genuinely
 * exclude one another: a cell cannot be having its text typed *and* its task
 * edited, and a pile of booleans would let it be. Every screen that has grown a
 * second overlapping flag has grown a state nobody meant to be reachable.
 *
 * [parent] is what closing this one goes back to. It is what makes Escape
 * unwind one layer at a time — confirmation to edit panel to menu to nothing —
 * without any caller having to know the order, and without one press collapsing
 * several layers at once.
 */
sealed interface CellWork {
    val gameId: EntityId
    val columnType: CellColumnType

    /** What closing this returns to, or null when it closes outright. */
    val parent: CellWork?

    /** True when there is something typed here that closing would throw away. */
    val hasUnsavedChanges: Boolean

    fun isOn(
        gameId: EntityId,
        columnType: CellColumnType,
    ): Boolean = this.gameId == gameId && this.columnType == columnType

    /**
     * Writing the plain text of a cell's document.
     *
     * The draft is held here rather than inside the text field, so a fresh list
     * arriving from the database cannot wipe out what the user is halfway
     * through typing: the rows are replaced and this is left where it was.
     *
     * [originalText] is the whole document as it stood when the editor opened —
     * tasks included, as their names. It is both what a no-op save is recognised
     * by and what the write is checked against, so a change someone else made in
     * the meantime is caught rather than overwritten.
     */
    data class WritingText(
        override val gameId: EntityId,
        override val columnType: CellColumnType,
        val originalText: String,
        /**
         * The document as it stands, run by run.
         *
         * Held as runs rather than as one string so the editor always knows
         * where the tasks are, even after the text around them has been typed
         * in. Searching the draft for a task's name would find the wrong one the
         * moment the user typed that word themselves.
         */
        val runs: List<DocumentRun>,
        val isSaving: Boolean = false,
        val failure: CellTextFailure? = null,
        /** Why the last change or selection was refused; cleared by the next one. */
        val refusal: CellTextFailure? = null,
        val selectionFailure: TaskFromTextFailure? = null,
    ) : CellWork {
        /** What the text field shows: the runs laid end to end. */
        val draft: String get() = runs.documentText()

        override val parent: CellWork? get() = null
        override val hasUnsavedChanges: Boolean get() = draft != originalText
    }

    /**
     * Making a task out of words selected in the text being written.
     *
     * Carries the editor it came from, so giving up on the task gives up on
     * nothing else: the cell is still open and still says what it said.
     */
    data class MakingTask(
        val from: WritingText,
        val composer: TaskComposer,
    ) : CellWork {
        override val gameId: EntityId get() = from.gameId
        override val columnType: CellColumnType get() = from.columnType
        override val parent: CellWork get() = from
        override val hasUnsavedChanges: Boolean get() = true
    }

    /**
     * The small menu anchored to one task in a cell.
     *
     * Nothing is being typed here, so closing it loses nothing; it is the point
     * the deeper panels return to.
     */
    data class TaskMenu(
        override val gameId: EntityId,
        override val columnType: CellColumnType,
        val taskId: EntityId,
        val name: String,
    ) : CellWork {
        override val parent: CellWork? get() = null
        override val hasUnsavedChanges: Boolean get() = false
    }

    /** Changing what a task is, in a panel bound to the same word. */
    data class EditingTask(
        val from: TaskMenu,
        val editor: TaskEditor,
    ) : CellWork {
        override val gameId: EntityId get() = from.gameId
        override val columnType: CellColumnType get() = from.columnType
        override val parent: CellWork get() = from
        override val hasUnsavedChanges: Boolean get() = editor.hasChanges
    }

    /**
     * Asking whether a task really should become ordinary text again.
     *
     * PLAN 17 wants this confirmed, and PLAN 12.8 makes it something that cannot
     * be taken back: the colours, the pipeline and the history go with the task.
     */
    data class ConfirmingConvert(
        val from: TaskMenu,
        val hasProgress: Boolean,
        val isSaving: Boolean = false,
        val failure: TaskEditFailure? = null,
    ) : CellWork {
        override val gameId: EntityId get() = from.gameId
        override val columnType: CellColumnType get() = from.columnType
        override val parent: CellWork get() = from
        override val hasUnsavedChanges: Boolean get() = false
        val taskId: EntityId get() = from.taskId
        val name: String get() = from.name
    }
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
 * What the user has changed about a task that already exists.
 *
 * Every field starts at what the task says now, so [hasChanges] is the honest
 * answer to whether closing this would lose anything. The quantity is text for
 * the same reason it is in [TaskComposer]: what was typed stays visible even
 * when it is not a number.
 *
 * A task carrying more than one colour is marked rather than edited. PLAN 5.10
 * gives it an ordered list and PLAN 12.7 draws its name split across them, and
 * one colour box has nowhere to put that order — so the list is shown and left
 * alone until the step that can edit it.
 */
data class TaskEditor(
    val taskId: EntityId,
    val originalName: String,
    val name: String,
    val colorQuery: String = "",
    val colorId: EntityId?,
    val originalColorId: EntityId?,
    /** Every colour the task carries, in the user's own order. */
    val colorNames: List<String> = emptyList(),
    val quantityText: String,
    val originalQuantityText: String,
    val notes: String,
    val originalNotes: String,
    val trackingMode: TrackingMode,
    val originalTrackingMode: TrackingMode,
    val isSaving: Boolean = false,
    val failure: TaskEditFailure? = null,
) {
    val holdsSeveralColors: Boolean get() = colorNames.size > 1

    val quantity: Int? get() = quantityText.toIntOrNull()?.takeIf { it > 0 }

    val isQuantityUsable: Boolean get() = quantityText.isBlank() || quantity != null

    val isNameUsable: Boolean get() = name.isNotBlank() && name.none { it == '\n' || it == '\r' }

    val hasChanges: Boolean
        get() =
            name != originalName ||
                colorId != originalColorId ||
                quantityText != originalQuantityText ||
                notes != originalNotes ||
                trackingMode != originalTrackingMode

    val canSave: Boolean get() = !isSaving && isNameUsable && isQuantityUsable && hasChanges
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
    /** The one thing being done in one cell, or null when the table is only read. */
    val work: CellWork? = null,
    /** The whole colour catalogue, which the task panels pick from. */
    val colors: List<ColorSummary> = emptyList(),
    /** True when something was refused because a cell is still being worked in. */
    val blockedByEditor: Boolean = false,
    /**
     * Bumped every time the keyboard has to be handed back to the open surface.
     *
     * Refusing an action is not enough on its own: the click that was refused
     * took the focus with it, so Escape would then reach a chip rather than the
     * surface it is meant to close. The screen watches this number and puts the
     * keyboard back where the work is.
     */
    val focusRecall: Int = 0,
) {
    /** The text editor open in this cell, whatever is layered over it. */
    fun writingIn(
        gameId: EntityId,
        columnType: CellColumnType,
    ): CellWork.WritingText? =
        when (val open = work) {
            is CellWork.WritingText -> open.takeIf { it.isOn(gameId, columnType) }
            is CellWork.MakingTask -> open.from.takeIf { it.isOn(gameId, columnType) }
            else -> null
        }

    /** The task whose menu or panel is open in this cell, if any. */
    fun menuIn(
        gameId: EntityId,
        columnType: CellColumnType,
    ): CellWork.TaskMenu? =
        when (val open = work) {
            is CellWork.TaskMenu -> open.takeIf { it.isOn(gameId, columnType) }
            is CellWork.EditingTask -> open.from.takeIf { it.isOn(gameId, columnType) }
            is CellWork.ConfirmingConvert -> open.from.takeIf { it.isOn(gameId, columnType) }
            else -> null
        }
}
