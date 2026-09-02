package dev.pnptracker.ui.feature.games

import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.games.DocumentRun
import dev.pnptracker.domain.games.GameCompletionSnapshot
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.games.documentText
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.model.hasStages
import dev.pnptracker.domain.search.GameTableFilter
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskEditFailure
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import dev.pnptracker.domain.tasks.TaskProgressFailure
import dev.pnptracker.domain.tasks.countedQuantityOf
import dev.pnptracker.domain.tasks.isUnusableQuantity
import dev.pnptracker.ui.feature.colors.ColorComposer

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
        /**
         * True when the view really does hold rows and the filter hid them all.
         *
         * A third thing to say, and it has to be said differently: an empty view
         * is answered by making a game or looking at another view, and an empty
         * result is answered by loosening what was asked for. One sentence for
         * both would send the user to make a game they already have.
         */
        val hiddenByFilter: Boolean = false,
    ) : GameTableRowsState

    data class Content(
        val rows: List<GameTableRow>,
    ) : GameTableRowsState
}

/**
 * Whether the panel of filter choices is open over the table.
 *
 * Its own value and not one of [CellWork]'s cases: that set is what is being
 * done *inside one cell*, and every one of them names the cell it is in. This
 * belongs to the whole table and to no cell at all.
 */
enum class TableFilterSurface {
    CLOSED,
    OPEN,
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
        /** Whether the task is finished, which decides what the menu offers. */
        val isCompleted: Boolean = false,
        /** How much it still owes; more than none is what offers making good. */
        val currentMissingQuantity: Int = 0,
        /** Which pool it is worked in, so a shortage form asks for the right detail. */
        val poolType: PoolType? = null,
        /** True while a finish or a reopen is being written. */
        val isWorking: Boolean = false,
        /** Why the last finish or reopen did not happen; cleared by the next try. */
        val failure: TaskProgressFailure? = null,
        /**
         * The name the settling event of a completion will be written under.
         *
         * Fixed when the menu opens rather than made at the moment of writing,
         * so a finish that failed uncertainly and is tried again is the same
         * settling rather than a second one.
         */
        val completionEventId: EntityId,
    ) : CellWork {
        override val parent: CellWork? get() = null
        override val hasUnsavedChanges: Boolean get() = false

        /** True when there is something owed that could be made good. */
        val owesSomething: Boolean get() = currentMissingQuantity > 0
    }

    /**
     * Saying how many pieces came out missing or spoiled (PLAN 6.3).
     *
     * One surface for both, because PLAN gives one action: a piece that was
     * spoiled has to be made again exactly as one that never arrived does.
     */
    data class ReportingShortage(
        val from: TaskMenu,
        val draft: ShortageDraft,
        val isSaving: Boolean = false,
        val failure: TaskProgressFailure? = null,
    ) : CellWork {
        override val gameId: EntityId get() = from.gameId
        override val columnType: CellColumnType get() = from.columnType
        override val parent: CellWork get() = from
        override val hasUnsavedChanges: Boolean get() = draft.isTouched
        val taskId: EntityId get() = from.taskId
    }

    /** Saying how many of the pieces that were owed have been made again. */
    data class ResolvingShortage(
        val from: TaskMenu,
        val draft: ShortageDraft,
        val isSaving: Boolean = false,
        val failure: TaskProgressFailure? = null,
    ) : CellWork {
        override val gameId: EntityId get() = from.gameId
        override val columnType: CellColumnType get() = from.columnType
        override val parent: CellWork get() = from
        override val hasUnsavedChanges: Boolean get() = draft.isTouched
        val taskId: EntityId get() = from.taskId

        /** What is owed right now, which is the most that can be made good. */
        val outstanding: Int get() = from.currentMissingQuantity
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

    /**
     * Making a colour that does not exist yet, over the panel that wanted it.
     *
     * The innermost surface there is. It carries the panel it was opened from,
     * so saving a colour or giving up on one returns to a draft that has not
     * moved: PLAN 5.7 makes a colour a catalogue record in its own right, and
     * nothing about a task is decided here.
     *
     * [target] is where the colour goes once it exists, fixed when this opened.
     * Named rather than worked out afterwards, because by then the panel may
     * have moved on — and a colour applied to whatever happens to be open is a
     * colour applied to the wrong row.
     */
    data class MakingColor(
        val from: CellWork,
        val target: NewColorTarget,
        val composer: ColorComposer,
        val isSaving: Boolean = false,
        val failure: ColorSetupFailure? = null,
    ) : CellWork {
        init {
            require(from is MakingTask || from is EditingTask) {
                "A colour is made over a task panel, not over ${from::class.simpleName}"
            }
        }

        override val gameId: EntityId get() = from.gameId
        override val columnType: CellColumnType get() = from.columnType
        override val parent: CellWork get() = from
        override val hasUnsavedChanges: Boolean get() = composer.isTouched
    }
}

/**
 * The one thing the user is doing to a whole game row.
 *
 * Its own state beside [CellWork] rather than a case inside it. A [CellWork] is
 * always in one cell — it names a column, and everything about it is anchored to
 * a word written there — while finishing a game is about the row and no column
 * of it. Pressing one into the other would mean inventing a column for something
 * that has none, and every screen that reads the column would have to learn to
 * distrust it.
 *
 * The two do not overlap in practice either: one thing happens at a time, and
 * the controller refuses to open either over the other.
 */
sealed interface RowWork {
    val gameId: EntityId

    /** What closing this one goes back to, or null when it closes outright. */
    val parent: RowWork? get() = null

    /** True when there is something here that closing would throw away. */
    val hasUnsavedChanges: Boolean get() = false

    /**
     * Asking whether a game's unfinished work really is finished (PLAN 12.9).
     *
     * Only ever open when there *is* unfinished work: a game with nothing left
     * in it is finished without a question, and asking anyway would be a
     * ceremony over an act that costs nothing.
     *
     * [expected] is the game the count was taken from. What the user is agreeing
     * to is "finish these", so the answer is carried back to the transaction
     * with the picture it was given and refused if the game has moved since.
     */
    data class ConfirmingGameCompletion(
        override val gameId: EntityId,
        val gameName: String,
        val expected: GameCompletionSnapshot,
        val isSaving: Boolean = false,
        val failure: TaskProgressFailure? = null,
    ) : RowWork {
        init {
            require(expected.needsConfirmation) {
                "A game with nothing unfinished in it is finished without being asked about."
            }
        }

        /** How many tasks the user is being asked to declare finished. */
        val unfinishedCount: Int get() = expected.unfinishedCount
    }
}

/**
 * Where the keyboard goes when the row it was on leaves the open view.
 *
 * Finishing a game in `Devam Eden` takes its row out of the list, and focus on a
 * row that is gone is focus nowhere — the keyboard falls out of the table
 * altogether and the user has to reach for the mouse. So the next place is
 * chosen while the row is still there to have neighbours.
 */
sealed interface RowFocusTarget {
    /** Another game's row, named while it was still beside the one that left. */
    data class Game(
        val gameId: EntityId,
    ) : RowFocusTarget

    /** The view filter, when the row that left was the last one in the view. */
    data object ViewFilter : RowFocusTarget
}

/**
 * What the user is filling in about a shortage, reported or made good.
 *
 * One draft for both surfaces, because PLAN 6.3 asks the same things of each: an
 * amount, and optionally something about which pieces and where. The amount is
 * held as text rather than as a number so a half typed one is still what the
 * user typed — a field that turned `1` into `1` and `` into `0` would answer
 * back while they were still writing.
 *
 * [eventId] is the name this movement will be written under. It is made when the
 * form opens and kept for as long as the form is open, so a submit that failed
 * uncertainly can be sent again as the same movement. PLAN 5.12 makes the
 * identity the whole of what tells a retry from a second report.
 */
data class ShortageDraft(
    val eventId: EntityId,
    val quantity: String = "",
    val note: String = "",
    val cardReference: String = "",
    val stage: ProductionStage? = null,
) {
    /** True once there is anything here that closing would throw away. */
    val isTouched: Boolean
        get() = quantity.isNotBlank() || note.isNotBlank() || cardReference.isNotBlank() || stage != null

    /** The amount as a number, or null when what is typed is not one. */
    val countedQuantity: Int? get() = countedQuantityOf(quantity)

    /** True once the field holds something that is not an amount, so it can say so. */
    val isQuantityUnusable: Boolean get() = isUnusableQuantity(quantity)

    /** The note with nothing in it treated as no note at all. */
    val writtenNote: String? get() = note.trim().takeIf { it.isNotEmpty() }

    /** The card named, or null; only a card task may carry one (PLAN 7.4). */
    fun writtenCardReference(poolType: PoolType?): String? = cardReference.trim().takeIf { it.isNotEmpty() && poolType == PoolType.CARD }

    /** The stage chosen, or null; only a pool with a pipeline has one. */
    fun chosenStage(poolType: PoolType?): ProductionStage? = stage.takeIf { poolType?.hasStages == true }
}

/**
 * Where a colour made from a task surface is to be applied once it exists.
 *
 * Fixed when the picker opens, and named rather than inferred. Each of the
 * panel's modes keeps its own draft, so "the colour the user just made" has no
 * meaning on its own: it belongs to one row of one draft, and saying which is
 * the only way it cannot land in another.
 *
 * There is nothing here for the global colour section. A colour made there goes
 * into the catalogue and nowhere else, because there is no draft in front of the
 * user to put it in.
 */

sealed interface NewColorTarget {
    /** The one task of the single-colour mode. */
    data object SingleDraft : NewColorTarget

    /** One exact task of the batch mode, by the place it is shown at. */
    data class BatchRow(
        val row: Int,
    ) : NewColorTarget

    /** The end of the several-colour list being composed. */
    data object MulticolorList : NewColorTarget

    /**
     * The task being edited: its one colour, or the end of its list.
     *
     * One case rather than two, because which it is is not the picker's to
     * decide: PLAN does not let a task cross between being made in one colour
     * and being made in several, so the task itself already settles it.
     */
    data object EditedTask : NewColorTarget
}

/**
 * A colour that reached the catalogue but had nowhere left to be put.
 *
 * Only happens when the draft it was meant for went away while the colour was
 * being written. The colour is real and stays — PLAN 5.7 has no unsaved
 * colours — so the honest thing is to say both halves: it was saved, and it was
 * not applied. Guessing another home for it would put it on a task the user
 * never pointed at.
 */
data class SavedColorNotice(
    val colorName: String,
)

/**
 * Which of the panel's ways of creating tasks the user is working in.
 *
 * A convenience of the form and nothing more (PLAN 12.6): the mode is never
 * stored, and once tasks exist nothing in the database says which way they were
 * made. [INDEPENDENT_TASKS] in particular is not a kind of task, a group or a
 * parent — PLAN 12.7 is explicit — it is a way of typing one name once.
 *
 * What separates the last two is what they make, not what they are called.
 * [INDEPENDENT_TASKS] makes N tasks that share nothing; [SINGLE_ITEM_MULTICOLOR]
 * makes one task made in N colours, with one total, one counter and one place in
 * the cell (PLAN 5.10 and 12.7). Neither writes anything that ties records
 * together, because in the first there is nothing to tie and in the second there
 * is only one record to begin with.
 */
enum class TaskCreationMode {
    SINGLE_COLOR,
    INDEPENDENT_TASKS,
    SINGLE_ITEM_MULTICOLOR,
}

/**
 * One task the user is describing in the panel, before anything is written.
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
data class TaskDraftRow(
    val colorQuery: String = "",
    val colorId: EntityId? = null,
    val quantityText: String = "",
    /** The user's own words, kept exactly; PLAN 5.6 stores a note as written. */
    val notes: String = "",
    val trackingMode: TrackingMode? = null,
) {
    /** The quantity if it is a whole number greater than zero, and null otherwise. */
    val quantity: Int? get() = quantityText.toIntOrNull()?.takeIf { it > 0 }

    /** False once there is something in the field that is not a usable quantity. */
    val isQuantityUsable: Boolean get() = quantityText.isEmpty() || quantity != null

    /** True once this row describes a task that could actually be created. */
    val isComplete: Boolean get() = colorId != null && quantity != null && trackingMode != null

    /** True while nothing has been typed or chosen here at all. */
    val isUntouched: Boolean
        get() = colorId == null && quantityText.isEmpty() && notes.isEmpty() && colorQuery.isEmpty()
}

/**
 * The one task being described in several colours, before anything is written.
 *
 * One quantity, one note, one tracking mode and an ordered list of colours,
 * because that is exactly what PLAN 5.10 and 12.7 say such a task is: a single
 * record whose colours are ordered, not several records that happen to agree.
 * There is nowhere here to type a second quantity, which is the point — a form
 * that offered one would be describing something the product does not have.
 *
 * The list cannot hold the same colour twice, because choosing a colour already
 * in it takes it back out. A duplicate is therefore not a state the user can
 * reach by typing, which is a better answer than a message about one; the
 * transaction refuses one all the same, for anything that did not come from
 * here.
 */
data class MulticolorDraft(
    val colorQuery: String = "",
    /** The colours the task will be made in, in the order they were chosen. */
    val colorIds: List<EntityId> = emptyList(),
    val quantityText: String = "",
    /** The user's own words, kept exactly; PLAN 5.6 stores a note as written. */
    val notes: String = "",
    val trackingMode: TrackingMode? = null,
) {
    val quantity: Int? get() = quantityText.toIntOrNull()?.takeIf { it > 0 }

    val isQuantityUsable: Boolean get() = quantityText.isEmpty() || quantity != null

    /** True once there are enough colours for this to be that kind of task. */
    val hasEnoughColors: Boolean get() = colorIds.size >= LEAST_COLORS

    val isComplete: Boolean get() = hasEnoughColors && quantity != null && trackingMode != null

    /** True while nothing has been typed or chosen here at all. */
    val isUntouched: Boolean
        get() = colorIds.isEmpty() && quantityText.isEmpty() && notes.isEmpty() && colorQuery.isEmpty()

    companion object {
        /**
         * How many colours make a task of this kind.
         *
         * Two. One colour is the single-colour mode, and offering to save it
         * from here would be two ways to make the same record. There is no
         * ceiling to match it: PLAN puts no limit on how many colours a thing
         * comes in, and a name shorter than its colour list is drawn rather than
         * refused — the colours with no character of their own become swatches
         * beside it (PLAN 12.7).
         */
        const val LEAST_COLORS = 2
    }
}

/**
 * The tasks being made out of words the user selected.
 *
 * The selection is fixed when the panel opens and never moves again: it carries
 * the piece it was made in and what that piece said, so a save either lands
 * exactly where the user pointed or is refused. Nothing in here is stored until
 * they save, and closing the panel leaves the cell and its text as they were.
 *
 * Each of the three modes keeps its own draft, and none of them can reach
 * another. Switching is then free in both directions: nothing is copied, nothing
 * is merged, and coming back to a mode finds it exactly as it was left — which
 * is the only version of "loses nothing" that also means "changes nothing".
 * Sharing one row between the first two modes did lose something: a quantity
 * typed while making one task landed in the first task of a batch prepared
 * earlier, and the user was never told.
 *
 * The name is in none of the drafts, because it is not theirs to differ in —
 * every task made here starts from the same selected words (PLAN 12.7), and
 * telling them apart afterwards is a rename.
 */
data class TaskComposer(
    val selection: CellTextSelection,
    val columnType: CellColumnType,
    /** The selected words, with the whitespace at their edges already left behind. */
    val name: String,
    val mode: TaskCreationMode = TaskCreationMode.SINGLE_COLOR,
    /** The one task of the single-colour mode. */
    val single: TaskDraftRow,
    /** The tasks of the batch mode, never fewer than [LEAST_INDEPENDENT_TASKS]. */
    val rows: List<TaskDraftRow>,
    /**
     * The one several-colour task.
     *
     * Its own state rather than a row pressed into service, because it is not
     * one: a row is a task with a colour, and this is a task with a list of
     * them.
     */
    val palette: MulticolorDraft = MulticolorDraft(),
    val isSaving: Boolean = false,
    val failure: TaskFromTextFailure? = null,
    /**
     * Which row the refusal was about, or null when it was about the whole panel.
     *
     * A batch is several tasks described at once, so "a colour is gone" is only
     * half an answer: the user has rows in front of them and needs to be told
     * which one to change.
     */
    val failureRow: Int? = null,
    /** The place [failureRow] clashes with, when the refusal was about a pair. */
    val failureConflictsWith: Int? = null,
) {
    init {
        require(rows.size >= LEAST_INDEPENDENT_TASKS) {
            "The batch mode always has its own rows to type in: ${rows.size}"
        }
    }

    /**
     * The rows this mode will actually create tasks from.
     *
     * Empty in the several-colour mode, which describes its one task in
     * [palette] instead: there is no row of it to use.
     */
    val usedRows: List<TaskDraftRow>
        get() =
            when (mode) {
                TaskCreationMode.SINGLE_COLOR -> listOf(single)
                TaskCreationMode.INDEPENDENT_TASKS -> rows
                TaskCreationMode.SINGLE_ITEM_MULTICOLOR -> emptyList()
            }

    /** Every colour the mode being worked in would actually save. */
    val colorsInPlay: List<EntityId>
        get() =
            when (mode) {
                TaskCreationMode.SINGLE_COLOR -> listOfNotNull(single.colorId)
                TaskCreationMode.INDEPENDENT_TASKS -> rows.mapNotNull { it.colorId }
                TaskCreationMode.SINGLE_ITEM_MULTICOLOR -> palette.colorIds
            }

    /** The row the panel shows at this place, in whichever mode is open. */
    fun rowAt(row: Int): TaskDraftRow? = if (mode == TaskCreationMode.SINGLE_COLOR) single.takeIf { row == 0 } else rows.getOrNull(row)

    /** How many tasks saving this panel would create. */
    val taskCount: Int
        get() = if (mode == TaskCreationMode.SINGLE_ITEM_MULTICOLOR) 1 else usedRows.size

    /**
     * Each row that repeats a colour, and the earlier row it repeats.
     *
     * A pair rather than a set, because a duplicate is never about one row on
     * its own: the panel marks the second one — the user chose the first one
     * first and it is not the one being asked to change — and names the first,
     * so they can see what the clash is rather than hunting for it.
     */
    val repeatedColorRows: Map<Int, Int>
        get() {
            val firstSeenAt = mutableMapOf<EntityId, Int>()
            val repeated = mutableMapOf<Int, Int>()
            usedRows.forEachIndexed { index, row ->
                val colorId = row.colorId ?: return@forEachIndexed
                val earlier = firstSeenAt.put(colorId, index)
                if (earlier != null) {
                    firstSeenAt[colorId] = earlier
                    repeated[index] = earlier
                }
            }
            return repeated
        }

    /** True while a row could be taken away and the mode still have enough. */
    val canRemoveRow: Boolean
        get() = mode == TaskCreationMode.INDEPENDENT_TASKS && rows.size > LEAST_INDEPENDENT_TASKS

    /**
     * The first row that needs attention, so the keyboard can be sent to it.
     *
     * A row the storage refused comes first: the user filled it in and was told
     * it will not do, which is more urgent than one they have not reached yet.
     */
    val firstUnusableRow: Int?
        get() =
            failureRow?.takeIf { it in usedRows.indices }
                ?: usedRows.indexOfFirst { !it.isComplete }.takeIf { it >= 0 }
                ?: repeatedColorRows.keys.minOrNull()

    /** How many tasks this mode will not save fewer than. */
    val leastRows: Int
        get() = if (mode == TaskCreationMode.SINGLE_COLOR) 1 else LEAST_INDEPENDENT_TASKS

    /** Which colour of the several-colour list the storage refused, if any. */
    val failedColorSlot: Int?
        get() =
            failureRow?.takeIf {
                mode == TaskCreationMode.SINGLE_ITEM_MULTICOLOR && it in palette.colorIds.indices
            }

    val canSave: Boolean
        get() =
            !isSaving &&
                if (mode == TaskCreationMode.SINGLE_ITEM_MULTICOLOR) {
                    palette.isComplete
                } else {
                    usedRows.size >= leastRows &&
                        usedRows.all { it.isComplete } &&
                        repeatedColorRows.isEmpty()
                }

    companion object {
        /**
         * How many tasks the batch mode is for.
         *
         * Two, because one task made through a form built for several is the
         * single-colour mode with more work. There is no ceiling to match it:
         * PLAN puts no limit on how many colours a thing comes in, and inventing
         * one would refuse a real note somebody wrote.
         */
        const val LEAST_INDEPENDENT_TASKS = 2
    }
}

/**
 * What the user has changed about a task that already exists.
 *
 * Every field starts at what the task says now, so [hasChanges] is the honest
 * answer to whether closing this would lose anything. The quantity is text for
 * the same reason it is in [TaskComposer]: what was typed stays visible even
 * when it is not a number.
 *
 * The colours are an ordered list, whether the task has one or several. PLAN
 * 5.10 numbers them from the user's own order and PLAN 12.7 draws the name split
 * across them in it, so the order is part of what the task is and reordering it
 * is a real change to save.
 *
 * What may not change is how many kinds of thing the task is. A task made in
 * several colours stays made in several, and one made in one stays made in one:
 * PLAN describes neither crossing, and the panel does not offer what the
 * transaction would refuse.
 */
data class TaskEditor(
    val taskId: EntityId,
    val originalName: String,
    val name: String,
    val colorQuery: String = "",
    /** Every colour the task is to carry, in the user's own order. */
    val colorIds: List<EntityId>,
    val originalColorIds: List<EntityId>,
    val quantityText: String,
    val originalQuantityText: String,
    val notes: String,
    val originalNotes: String,
    val trackingMode: TrackingMode,
    val originalTrackingMode: TrackingMode,
    /** PLAN 10 and 11.7: the four marks the import puts on a task, editable here. */
    val isMissing: Boolean = false,
    val isBorrowed: Boolean = false,
    val needsInfo: Boolean = false,
    val needsClassification: Boolean = false,
    val originalIsMissing: Boolean = false,
    val originalIsBorrowed: Boolean = false,
    val originalNeedsInfo: Boolean = false,
    val originalNeedsClassification: Boolean = false,
    val isSaving: Boolean = false,
    val failure: TaskEditFailure? = null,
    /** Which colour of the list the refusal was about, if it was about one. */
    val failureRow: Int? = null,
    /** The colour [failureRow] clashes with, when the refusal was a pair. */
    val failureConflictsWith: Int? = null,
) {
    /** True when this is a task made in several colours (PLAN 12.7). */
    val holdsSeveralColors: Boolean get() = originalColorIds.size > 1

    /** The one colour a single-colour task is made in, or null when it has none. */
    val colorId: EntityId? get() = colorIds.firstOrNull()

    /** False once a several-colour task has been emptied below what it is. */
    val hasEnoughColors: Boolean
        get() = !holdsSeveralColors || colorIds.size >= MulticolorDraft.LEAST_COLORS

    val quantity: Int? get() = quantityText.toIntOrNull()?.takeIf { it > 0 }

    val isQuantityUsable: Boolean get() = quantityText.isBlank() || quantity != null

    val isNameUsable: Boolean get() = name.isNotBlank() && name.none { it == '\n' || it == '\r' }

    val hasChanges: Boolean
        get() =
            name != originalName ||
                colorIds != originalColorIds ||
                quantityText != originalQuantityText ||
                notes != originalNotes ||
                trackingMode != originalTrackingMode ||
                isMissing != originalIsMissing ||
                isBorrowed != originalIsBorrowed ||
                needsInfo != originalNeedsInfo ||
                needsClassification != originalNeedsClassification

    /** A task is in the missing column or the borrowed one, never in both (PLAN 10). */
    val flagsConflict: Boolean get() = isMissing && isBorrowed

    val canSave: Boolean
        get() = !isSaving && isNameUsable && isQuantityUsable && hasEnoughColors && hasChanges && !flagsConflict
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
    /**
     * What the user has asked the table to show inside the open view (PLAN 13).
     *
     * Beside the view rather than folded into it: the view is about games and
     * this is about the work in them, and PLAN 12.4 and 5.3 keep those apart.
     * Not stored, for the same reason the view is not.
     */
    val filter: GameTableFilter = GameTableFilter.NONE,
    /** What is typed in the search box, before it is trimmed and folded. */
    val searchText: String = "",
    /** Whether the panel of filter choices is open over the table. */
    val filterSurface: TableFilterSurface = TableFilterSurface.CLOSED,
    val gameComposer: NameComposer? = null,
    val failure: GameSetupFailure? = null,
    /** The one thing being done in one cell, or null when the table is only read. */
    val work: CellWork? = null,
    /** The one thing being done to a whole row, or null. */
    val rowWork: RowWork? = null,
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
    /** A colour that was saved while the draft it was for went away. */
    val savedColorNotice: SavedColorNotice? = null,
    /**
     * Why a game could not be finished, and which one; cleared by the next try.
     *
     * Kept beside the confirmation rather than only inside it, because a game
     * with nothing unfinished in it is never asked about — there is no surface
     * open for the refusal to land on, and the row is where it belongs.
     */
    val gameCompletionFailure: Pair<EntityId, TaskProgressFailure>? = null,
    /** Where the keyboard is to go now that a row has left the open view. */
    val focusAfterRow: RowFocusTarget? = null,
    /**
     * Bumped with [focusAfterRow], so the same place can be asked for twice.
     *
     * Two games finished one after another can both send the keyboard to the
     * same neighbour, and a target that had not changed would ask for nothing
     * the second time.
     */
    val rowFocusRecall: Int = 0,
    /**
     * Colours written from a panel that the catalogue stream has not shown yet.
     *
     * The write and the stream are two different journeys, and the second one is
     * slower. Without this, a colour the user made a moment ago would look to
     * the draft exactly like a colour somebody else had deleted, and the save
     * they were about to make would be refused for it.
     */
    val awaitedColorIds: Set<EntityId> = emptySet(),
) {
    /** True while anything at all is open, in a cell or on a row. */
    val isBusy: Boolean get() = work != null || rowWork != null

    /** True when anything has been asked for beyond the open view. */
    val isNarrowed: Boolean get() = filter.isNarrowed

    /** How many choices to show on the filter button, the search included. */
    val chosenFilterCount: Int get() = filter.chosenCount + if (filter.query.isEmpty) 0 else 1

    /** The confirmation open on this row, if there is one. */
    fun confirmingCompletionOf(gameId: EntityId): RowWork.ConfirmingGameCompletion? =
        (rowWork as? RowWork.ConfirmingGameCompletion)?.takeIf { it.gameId == gameId }

    /** The text editor open in this cell, whatever is layered over it. */
    fun writingIn(
        gameId: EntityId,
        columnType: CellColumnType,
    ): CellWork.WritingText? =
        when (val open = work) {
            is CellWork.WritingText -> open.takeIf { it.isOn(gameId, columnType) }
            is CellWork.MakingTask -> open.from.takeIf { it.isOn(gameId, columnType) }
            // The picker is layered over the panel, and the panel over the cell:
            // the cell it is all standing on is still open and still drawn.
            is CellWork.MakingColor -> (open.from as? CellWork.MakingTask)?.from?.takeIf { it.isOn(gameId, columnType) }
            else -> null
        }

    /** The task panel open in this cell, whatever is layered over it. */
    fun composingIn(
        gameId: EntityId,
        columnType: CellColumnType,
    ): CellWork.MakingTask? =
        when (val open = work) {
            is CellWork.MakingTask -> open.takeIf { it.isOn(gameId, columnType) }
            is CellWork.MakingColor -> (open.from as? CellWork.MakingTask)?.takeIf { it.isOn(gameId, columnType) }
            else -> null
        }

    /** The colour picker open in this cell, if there is one. */
    fun creatingColorIn(
        gameId: EntityId,
        columnType: CellColumnType,
    ): CellWork.MakingColor? = (work as? CellWork.MakingColor)?.takeIf { it.isOn(gameId, columnType) }

    /** The task whose menu or panel is open in this cell, if any. */
    fun menuIn(
        gameId: EntityId,
        columnType: CellColumnType,
    ): CellWork.TaskMenu? =
        when (val open = work) {
            is CellWork.TaskMenu -> open.takeIf { it.isOn(gameId, columnType) }
            is CellWork.EditingTask -> open.from.takeIf { it.isOn(gameId, columnType) }
            is CellWork.ConfirmingConvert -> open.from.takeIf { it.isOn(gameId, columnType) }
            is CellWork.ReportingShortage -> open.from.takeIf { it.isOn(gameId, columnType) }
            is CellWork.ResolvingShortage -> open.from.takeIf { it.isOn(gameId, columnType) }
            is CellWork.MakingColor -> (open.from as? CellWork.EditingTask)?.from?.takeIf { it.isOn(gameId, columnType) }
            else -> null
        }

    /**
     * Colours the open draft still names that the catalogue does not have.
     *
     * A colour removed from somewhere else does not reach in and edit what
     * somebody is typing: the choice stays exactly where they put it, and this
     * is what lets the panel say so and refuse to save until they decide what
     * to do about it. Quietly dropping the row would change their task without
     * asking, and quietly keeping it would write a task pointing at nothing.
     *
     * Only the mode being worked in is counted. The other two modes keep their
     * own drafts, and a colour missing from one they are not looking at is not
     * a reason to refuse the one they are.
     */
    val strandedColorIds: Set<EntityId>
        get() {
            // Nothing is known until the catalogue has arrived, and nothing that
            // is not known can be said to be missing. Without this, every draft
            // would look stranded for as long as the first emission took.
            if (colors.isEmpty()) return emptySet()
            val wanted = colorsTheDraftNames(work)
            if (wanted.isEmpty()) return emptySet()
            val known = colors.mapTo(mutableSetOf()) { it.id }
            known += awaitedColorIds
            return wanted.filterTo(mutableSetOf()) { it !in known }
        }

    private fun colorsTheDraftNames(open: CellWork?): List<EntityId> =
        when (open) {
            is CellWork.MakingTask -> open.composer.colorsInPlay
            is CellWork.EditingTask -> open.editor.colorIds
            is CellWork.MakingColor -> colorsTheDraftNames(open.from)
            else -> emptyList()
        }
}
