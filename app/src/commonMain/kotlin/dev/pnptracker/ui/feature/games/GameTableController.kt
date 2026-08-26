package dev.pnptracker.ui.feature.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.CellTextEditing
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.data.repository.GameTableSource
import dev.pnptracker.data.repository.TaskCreationFromText
import dev.pnptracker.data.repository.TaskEditing
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.games.CellSegmentPreview
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.games.DocumentChange
import dev.pnptracker.domain.games.DocumentEditRefusal
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.games.planDocumentChange
import dev.pnptracker.domain.games.runsFrom
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.rules.normalizeColorTerm
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import dev.pnptracker.domain.tasks.onlyTrackingModeOf
import dev.pnptracker.domain.tasks.splitForTaskName
import kotlinx.coroutines.flow.collect

/**
 * The game table: which games it shows, what is being written in it, and what is
 * being done to the tasks written there.
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
 * One thing happens in one cell at a time, and which thing it is lives in
 * [GameTableScreenState.work] as a typed state rather than as a pile of flags.
 * A fresh list from the database replaces the rows and leaves that alone, so a
 * change to another game cannot wipe out what is being typed in this one.
 * Nothing saves on the user's behalf: PLAN describes no automatic save, and one
 * invented here would write words they never agreed to keep.
 */
class GameTableController(
    private val table: GameTableSource,
    private val setup: GameSetup,
    private val cells: CellTextEditing,
    private val colors: ColorCatalogue,
    private val taskCreation: TaskCreationFromText,
    private val taskEditing: TaskEditing,
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
            state = state.copy(rows = rowsFor(state.view), work = stillValid(state.work))
        }
    }

    /**
     * Collects the colour catalogue until cancelled.
     *
     * Only the catalogue is replaced. A colour the user has already picked stays
     * picked even when the list changes underneath them; whether it is still
     * there at all is settled once, inside the transaction that writes.
     */
    suspend fun observeColorCatalogue() {
        colors.observeColors().collect { catalogue ->
            state = state.copy(colors = catalogue)
        }
    }

    /**
     * Closes a surface bound to a task that is no longer there.
     *
     * The menu, the edit panel and the confirmation are all anchored to one task.
     * If it goes — converted elsewhere, or its game deleted — there is nothing
     * left for them to act on, so they close rather than sit over a word that has
     * gone. What is being typed in plain text is not touched: it is the user's
     * and the row it belongs to is still there.
     */
    private fun stillValid(work: CellWork?): CellWork? {
        val taskId =
            when (work) {
                is CellWork.TaskMenu -> work.taskId
                is CellWork.EditingTask -> work.from.taskId
                is CellWork.ConfirmingConvert -> work.taskId
                else -> return work
            }
        val cell = rowOf(work.gameId)?.cell(work.columnType)
        return work.takeIf { cell?.tasks.orEmpty().any { it.taskId == taskId } }
    }

    /**
     * Shows another view of the same rows. Reads nothing and writes nothing.
     *
     * Refused while a cell is being worked in. The row being edited may not be in
     * the view being moved to, and dropping the work to make the move would throw
     * away typing the user never agreed to lose. Saving it for them would be
     * worse: PLAN describes no automatic save.
     */
    fun showView(view: GameTableView) {
        if (state.work != null) {
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
     * they had just pressed rather than the surface it is meant to close, and
     * would have to go back to the mouse to get out of a state they never chose.
     */
    private fun blockedByOpenWork(): GameTableScreenState = state.copy(blockedByEditor = true, focusRecall = state.focusRecall + 1)

    /**
     * Closes the innermost surface that is open.
     *
     * One layer at a time: a confirmation goes back to the panel it was asked
     * from, the panel to the menu, the menu to nothing. Collapsing several at
     * once would take the user somewhere they did not ask to be.
     */
    fun closeInnermost() {
        val work = state.work ?: return
        state = state.copy(work = work.parent, blockedByEditor = false, focusRecall = state.focusRecall + 1)
    }

    // -------------------------------------------------------- writing a cell

    /**
     * Opens one cell for writing.
     *
     * The editor works on the whole document — the plain text and the names of
     * the tasks among it — because that is what the user sees and points at. The
     * tasks in it are not theirs to type over: every change is checked against
     * [planDocumentChange] before it is taken.
     *
     * Only one cell is worked in at a time. Asking for another while one is open
     * changes nothing and says so, rather than closing the first: that would
     * either lose the draft or save it behind the user's back.
     */
    fun beginEditing(
        gameId: EntityId,
        columnType: CellColumnType,
    ) {
        val existing = state.work
        if (existing != null) {
            if (existing is CellWork.WritingText && existing.isOn(gameId, columnType)) return
            state = blockedByOpenWork()
            return
        }
        val cell = rowOf(gameId)?.cell(columnType) ?: return
        val text = cell.editableText
        state =
            state.copy(
                work =
                    CellWork.WritingText(
                        gameId = gameId,
                        columnType = columnType,
                        originalText = text,
                        runs = cell.runs,
                    ),
                blockedByEditor = false,
            )
    }

    /**
     * Takes a change the user made to the document, or refuses it.
     *
     * The change is worked out from what the text said before and says now, and
     * has to fall inside a stretch of plain text. A backspace at the edge of a
     * task, a selection that swallowed one, a paste over the top of one: all
     * refused, and the draft is left exactly as it was, so the caret never
     * appears to have eaten a task and then put it back.
     *
     * The same rule is applied again inside the transaction. This one is here so
     * the user finds out at the keystroke rather than at the save.
     */
    fun editCellText(text: String) {
        val editor = writing() ?: return
        if (editor.isSaving || state.work !is CellWork.WritingText) return
        if (text == editor.draft) return
        // Planned against what the user is actually looking at, not against what
        // is stored: they are editing the draft on the screen.
        when (val change = planDocumentChange(editor.runs, editor.draft, text)) {
            is DocumentChange.Planned ->
                state =
                    state.copy(
                        work =
                            editor.copy(
                                runs = runsFrom(editor.runs, change.plan.gapTexts),
                                failure = null,
                                refusal = null,
                                selectionFailure = null,
                            ),
                    )

            is DocumentChange.Refused ->
                state =
                    state.copy(
                        work =
                            editor.copy(
                                refusal =
                                    when (change.reason) {
                                        DocumentEditRefusal.CROSSES_A_TASK -> CellTextFailure.CHANGE_CROSSES_A_TASK
                                    },
                            ),
                    )
        }
    }

    fun cancelEditing() {
        state = state.copy(work = null, blockedByEditor = false)
    }

    /**
     * Writes what has been typed.
     *
     * A save that is refused leaves the editor open with the draft untouched, so
     * the user can read what went wrong and still have their words.
     */
    suspend fun saveEditing() {
        val editor = writing() ?: return
        if (editor.isSaving || state.work !is CellWork.WritingText) return
        state = state.copy(work = editor.copy(isSaving = true, failure = null, refusal = null))
        try {
            cells.saveDocumentText(editor.gameId, editor.columnType, editor.originalText, editor.draft)
            state = state.copy(work = null, blockedByEditor = false)
        } catch (failure: CellTextException) {
            state =
                state.copy(
                    work = (state.work as? CellWork.WritingText)?.copy(isSaving = false, failure = failure.failure),
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
     * The offsets arrive counted across the whole document, which is what the
     * field reports, and are handed to the one piece that wholly contains them. A
     * selection spanning two pieces, or touching a task, is refused rather than
     * guessed at: cutting the wrong piece is exactly the damage the anchor exists
     * to prevent, and a selection that swallowed a task has no meaning as a name.
     *
     * Only ever offered against text that is already stored. The panel is not
     * available while there are unsaved changes, because the offsets would point
     * into a draft and the transaction cuts what the database holds. Saving on
     * the user's behalf to close that gap would be an automatic save PLAN does
     * not describe, so the answer is to ask them to save first.
     */
    fun beginTaskComposer(
        startOffset: Int,
        endOffset: Int,
    ) {
        val editor = state.work as? CellWork.WritingText ?: return
        if (editor.isSaving) return
        if (editor.hasUnsavedChanges) return refuseSelection(TaskFromTextFailure.STALE_TEXT_SELECTION)

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
                work =
                    CellWork.MakingTask(
                        from = editor.copy(selectionFailure = null),
                        composer =
                            TaskComposer(
                                selection = selection,
                                columnType = editor.columnType,
                                name = name,
                                rows = listOf(emptyRowFor(poolType)),
                            ),
                    ),
                blockedByEditor = false,
            )
    }

    private fun composing(): CellWork.MakingTask? = state.work as? CellWork.MakingTask

    /** A row with only what the pool settles already filled in. */
    private fun emptyRowFor(poolType: PoolType) =
        TaskDraftRow(
            // Settled where the pool leaves no choice, asked for where it does;
            // never invented either way.
            trackingMode = onlyTrackingModeOf(poolType),
        )

    private fun onComposer(change: (TaskComposer) -> TaskComposer) {
        val making = composing() ?: return
        if (making.composer.isSaving) return
        state = state.copy(work = making.copy(composer = change(making.composer)))
    }

    /** Changes one row and leaves every other row of the panel alone. */
    private fun onRow(
        row: Int,
        change: (TaskDraftRow) -> TaskDraftRow,
    ) = onComposer { composer ->
        if (row !in composer.rows.indices) {
            composer
        } else {
            composer.copy(
                rows = composer.rows.mapIndexed { index, existing -> if (index == row) change(existing) else existing },
                failure = null,
            )
        }
    }

    /**
     * Switches which way tasks are being made, keeping everything typed.
     *
     * Nothing is thrown away and nothing is asked: single-colour mode works on
     * the first row and leaves the rest standing, so a user who tries it and
     * comes back finds their other rows exactly as they left them. Going the
     * other way opens a second row when there is only one, because a batch of one
     * is the mode they just left.
     */
    fun chooseCreationMode(mode: TaskCreationMode) =
        onComposer { composer ->
            if (composer.mode == mode) {
                composer
            } else {
                val poolType = composer.columnType.poolType
                val rows =
                    if (mode == TaskCreationMode.INDEPENDENT_TASKS && poolType != null) {
                        composer.rows +
                            List(TaskComposer.LEAST_INDEPENDENT_TASKS - composer.rows.size) { emptyRowFor(poolType) }
                    } else {
                        composer.rows
                    }
                composer.copy(mode = mode, rows = rows, failure = null)
            }
        }

    /** Adds a row at the end, which is where the next task goes. */
    fun addTaskRow() =
        onComposer { composer ->
            val poolType = composer.columnType.poolType ?: return@onComposer composer
            composer.copy(rows = composer.rows + emptyRowFor(poolType), failure = null)
        }

    /**
     * Takes one row away, down to what the mode needs.
     *
     * Refused rather than silently ignored at the floor: the button is disabled
     * there and says why, so nothing disappears without the user asking twice.
     */
    fun removeTaskRow(row: Int) =
        onComposer { composer ->
            if (!composer.canRemoveRow || row !in composer.rows.indices) {
                composer
            } else {
                composer.copy(rows = composer.rows.filterIndexed { index, _ -> index != row }, failure = null)
            }
        }

    fun editTaskColorQuery(
        row: Int,
        query: String,
    ) = onRow(row) { it.copy(colorQuery = query) }

    fun chooseTaskColor(
        row: Int,
        colorId: EntityId,
    ) = onRow(row) { it.copy(colorId = colorId) }

    /** Takes the quantity as typed; what is not a usable number stays visible. */
    fun editTaskQuantity(
        row: Int,
        text: String,
    ) = onRow(row) { it.copy(quantityText = text) }

    /** Takes the note exactly as typed, spaces and all. */
    fun editTaskNotes(
        row: Int,
        text: String,
    ) = onRow(row) { it.copy(notes = text) }

    fun chooseTaskTracking(
        row: Int,
        trackingMode: TrackingMode,
    ) = onRow(row) { it.copy(trackingMode = trackingMode) }

    /**
     * Closes the panel and nothing else.
     *
     * The cell stays open with its text exactly as it was: giving up on making a
     * task is not giving up on what was written.
     */
    fun cancelTaskComposer() {
        val making = composing() ?: return
        state = state.copy(work = making.from, focusRecall = state.focusRecall + 1)
    }

    /**
     * Writes the task, and closes the cell when it lands.
     *
     * A refusal leaves the panel standing with the colour, the quantity and the
     * note the user chose, so they can read what went wrong without losing the
     * answers they already gave.
     */
    suspend fun saveTask() {
        val making = composing() ?: return
        val composer = making.composer
        if (composer.isSaving || !composer.canSave) return
        // Read once, here, so a second Enter arriving while the first save is in
        // flight finds isSaving already set and does nothing: the same words
        // cannot become two sets of tasks.
        val drafts =
            composer.usedRows.map { row ->
                TaskDraft(
                    colorId = row.colorId ?: return,
                    requiredQuantity = row.quantity ?: return,
                    trackingMode = row.trackingMode ?: return,
                    // An empty note is no note; anything else is kept as typed.
                    notes = row.notes.takeIf { it.isNotEmpty() },
                )
            }

        state = state.copy(work = making.copy(composer = composer.copy(isSaving = true, failure = null)))
        try {
            taskCreation.createTasks(selection = composer.selection, drafts = drafts)
            // The cell has changed underneath the editor, so it closes rather
            // than going on with offsets into a document that has moved. The
            // user opens it again to write more, or picks the next word.
            state = state.copy(work = null, blockedByEditor = false)
        } catch (refusal: TaskFromTextException) {
            state =
                state.copy(
                    work =
                        (state.work as? CellWork.MakingTask)?.let {
                            it.copy(composer = it.composer.copy(isSaving = false, failure = refusal.failure))
                        },
                    // Whatever the panel is showing has the keyboard put back on
                    // it, so the user can read what went wrong and fix it there.
                    focusRecall = state.focusRecall + 1,
                )
        }
    }

    /**
     * The colours a panel is offering, narrowed by what has been typed.
     *
     * Matched on the catalogue's own folding of a name, so `gri`, `Gri` and `GRİ`
     * are the one colour they are on the user's keyboard. That folding is only
     * ever right for colour names — it merges the two Turkish i's — which is why
     * it is used here and nowhere near general search.
     */
    fun colorsOffered(row: Int = 0): List<ColorSummary> {
        val query =
            when (val open = state.work) {
                is CellWork.MakingTask ->
                    open.composer.rows
                        .getOrNull(row)
                        ?.colorQuery
                        .orEmpty()
                is CellWork.EditingTask -> open.editor.colorQuery
                else -> ""
            }
        if (query.isBlank()) return state.colors
        val needle = normalizeColorTerm(query)
        return state.colors.filter { normalizeColorTerm(it.canonicalName).contains(needle) }
    }

    private fun refuseSelection(failure: TaskFromTextFailure) {
        val editor = state.work as? CellWork.WritingText ?: return
        state = state.copy(work = editor.copy(selectionFailure = failure))
    }

    // ------------------------------------------------ working on a task

    /**
     * Opens the menu anchored to one task.
     *
     * Refused while anything else is open in a cell, the same as every other
     * surface: PLAN 12.5 opens this over the word it belongs to, and a second one
     * over a half-typed cell would be two places to look at once.
     */
    fun openTaskMenu(
        gameId: EntityId,
        columnType: CellColumnType,
        taskId: EntityId,
    ) {
        val existing = state.work
        if (existing != null) {
            if (existing is CellWork.TaskMenu && existing.taskId == taskId) return
            state = blockedByOpenWork()
            return
        }
        val task = taskIn(gameId, columnType, taskId) ?: return
        state =
            state.copy(
                work = CellWork.TaskMenu(gameId, columnType, taskId, task.text),
                blockedByEditor = false,
            )
    }

    private fun taskIn(
        gameId: EntityId,
        columnType: CellColumnType,
        taskId: EntityId,
    ): CellSegmentPreview? = rowOf(gameId)?.cell(columnType)?.tasks?.firstOrNull { it.taskId == taskId }

    /** Opens the panel that changes what the task is, over the same word. */
    fun beginTaskEdit() {
        val menu = state.menu() ?: return
        val task = taskIn(menu.gameId, menu.columnType, menu.taskId) ?: return
        val poolType = menu.columnType.poolType ?: return
        state =
            state.copy(
                work =
                    CellWork.EditingTask(
                        from = menu,
                        editor =
                            TaskEditor(
                                taskId = menu.taskId,
                                originalName = task.text,
                                name = task.text,
                                colorId = task.colors.firstOrNull()?.colorId,
                                originalColorId = task.colors.firstOrNull()?.colorId,
                                colorNames = task.colors.map { it.canonicalName },
                                quantityText = task.requiredQuantity?.toString().orEmpty(),
                                originalQuantityText = task.requiredQuantity?.toString().orEmpty(),
                                notes = task.notes.orEmpty(),
                                originalNotes = task.notes.orEmpty(),
                                trackingMode = task.trackingMode ?: onlyTrackingModeOf(poolType) ?: return,
                                originalTrackingMode = task.trackingMode ?: onlyTrackingModeOf(poolType) ?: return,
                            ),
                    ),
            )
    }

    private fun GameTableScreenState.menu(): CellWork.TaskMenu? =
        when (val open = work) {
            is CellWork.TaskMenu -> open
            is CellWork.EditingTask -> open.from
            is CellWork.ConfirmingConvert -> open.from
            else -> null
        }

    private fun onEditor(change: (TaskEditor) -> TaskEditor) {
        val editing = state.work as? CellWork.EditingTask ?: return
        if (editing.editor.isSaving) return
        state = state.copy(work = editing.copy(editor = change(editing.editor)))
    }

    fun editTaskName(name: String) = onEditor { it.copy(name = name, failure = null) }

    fun editTaskEditColorQuery(query: String) = onEditor { it.copy(colorQuery = query) }

    fun chooseTaskEditColor(colorId: EntityId) = onEditor { it.copy(colorId = colorId, failure = null) }

    fun editTaskEditQuantity(text: String) = onEditor { it.copy(quantityText = text, failure = null) }

    fun editTaskEditNotes(text: String) = onEditor { it.copy(notes = text) }

    fun chooseTaskEditTracking(trackingMode: TrackingMode) = onEditor { it.copy(trackingMode = trackingMode, failure = null) }

    /**
     * Saves everything the user changed about the task, all at once.
     *
     * A refusal leaves the panel standing with what they typed, so they can read
     * what went wrong and still have their answers.
     */
    suspend fun saveTaskEdit() {
        val editing = state.work as? CellWork.EditingTask ?: return
        val editor = editing.editor
        if (!editor.canSave) return
        state = state.copy(work = editing.copy(editor = editor.copy(isSaving = true, failure = null)))
        try {
            taskEditing.editTask(
                taskId = editor.taskId,
                name = editor.name,
                colorId = editor.colorId,
                requiredQuantity = editor.quantity,
                notes = editor.notes.takeIf { it.isNotEmpty() },
                trackingMode = editor.trackingMode,
            )
            state = state.copy(work = editing.from, focusRecall = state.focusRecall + 1)
        } catch (refusal: TaskEditException) {
            state =
                state.copy(
                    work =
                        (state.work as? CellWork.EditingTask)?.let {
                            it.copy(editor = it.editor.copy(isSaving = false, failure = refusal.failure))
                        },
                )
        }
    }

    /**
     * Asks whether the task really should become ordinary text again.
     *
     * PLAN 17 asks for this to be confirmed and PLAN 12.8 makes it final: the
     * colours, the pipeline and the history go with the task. Whether there is
     * any history to lose is carried into the question, because it changes what
     * the user is agreeing to.
     */
    fun beginConvertToText() {
        val menu = state.menu() ?: return
        val task = taskIn(menu.gameId, menu.columnType, menu.taskId) ?: return
        state =
            state.copy(
                work =
                    CellWork.ConfirmingConvert(
                        from = menu,
                        hasProgress = task.hasProgress,
                    ),
            )
    }

    /** Turns the task back into text. Nothing is written until this is called. */
    suspend fun confirmConvertToText() {
        val confirming = state.work as? CellWork.ConfirmingConvert ?: return
        if (confirming.isSaving) return
        state = state.copy(work = confirming.copy(isSaving = true, failure = null))
        try {
            taskEditing.convertTaskToText(confirming.taskId)
            state = state.copy(work = null, focusRecall = state.focusRecall + 1)
        } catch (refusal: TaskEditException) {
            state =
                state.copy(
                    work =
                        (state.work as? CellWork.ConfirmingConvert)?.copy(
                            isSaving = false,
                            failure = refusal.failure,
                        ),
                )
        }
    }

    private fun writing(): CellWork.WritingText? =
        when (val open = state.work) {
            is CellWork.WritingText -> open
            is CellWork.MakingTask -> open.from
            else -> null
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
     * Only the rows are worked out here. The open work is never touched by a
     * fresh list: a change to some other game must not disturb what is being
     * typed in this one, and a row that leaves the view keeps its editor open —
     * the user is still writing in it.
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
