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
import dev.pnptracker.data.repository.TaskProgressOutcome
import dev.pnptracker.data.repository.TaskProgressing
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.WheelNudge
import dev.pnptracker.domain.colors.WheelPoint
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
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.rules.normalizeColorTerm
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import dev.pnptracker.domain.tasks.TaskProgressFailure
import dev.pnptracker.domain.tasks.onlyTrackingModeOf
import dev.pnptracker.domain.tasks.splitForTaskName
import dev.pnptracker.ui.feature.colors.ColorComposer
import dev.pnptracker.ui.feature.colors.baseColorsIn
import dev.pnptracker.ui.feature.tasks.TaskEditingHost
import kotlinx.coroutines.flow.collect

/**
 * The most digits a shortage amount may be typed in.
 *
 * Nine of them cannot reach the top of an `Int`, so the number the user typed is
 * always the number that arrives. What is too much for the task is still refused
 * by the transaction — this only keeps the field from collecting something that
 * could not be read back as what it says.
 */
private const val SHORTAGE_DIGITS = 9

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
    private val taskProgress: TaskProgressing,
    private val idGenerator: IdGenerator = IdGenerator.Random,
) : TaskEditingHost {
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
            state =
                state.copy(
                    colors = catalogue,
                    // Whatever has arrived is no longer being waited for.
                    awaitedColorIds = state.awaitedColorIds - catalogue.mapTo(mutableSetOf()) { it.id },
                )
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
                is CellWork.ReportingShortage -> work.taskId
                is CellWork.ResolvingShortage -> work.taskId
                // The picker stands on the panel below it: if that panel has
                // nothing left to act on, neither has this.
                is CellWork.MakingColor -> return work.takeIf { stillValid(work.from) != null }
                else -> return work
            }
        val cell = rowOf(work.gameId)?.cell(work.columnType)
        val task = cell?.tasks.orEmpty().firstOrNull { it.taskId == taskId } ?: return null
        // The menu is refreshed from the row that just arrived, so what it
        // offers follows the task: a finish made elsewhere turns `Tamamla` into
        // `Yeniden aç` without the menu having to close and be reopened. Only
        // the menu's own facts move. Anything the user is part way through
        // typing — a draft, an editor, the name a movement will be written
        // under — is left exactly where it was.
        return work.withMenu(freshMenu(work.menuOf() ?: return work, task))
    }

    /** The menu this piece of work stands on, if it stands on one. */
    private fun CellWork.menuOf(): CellWork.TaskMenu? =
        when (this) {
            is CellWork.TaskMenu -> this
            is CellWork.EditingTask -> from
            is CellWork.ConfirmingConvert -> from
            is CellWork.ReportingShortage -> from
            is CellWork.ResolvingShortage -> from
            else -> null
        }

    /** The same piece of work, standing on a menu that has been brought up to date. */
    private fun CellWork.withMenu(menu: CellWork.TaskMenu): CellWork =
        when (this) {
            is CellWork.TaskMenu -> menu
            is CellWork.EditingTask -> copy(from = menu)
            is CellWork.ConfirmingConvert -> copy(from = menu)
            is CellWork.ReportingShortage -> copy(from = menu)
            is CellWork.ResolvingShortage -> copy(from = menu)
            else -> this
        }

    /** The menu's facts as the task now stands, keeping everything else it holds. */
    private fun freshMenu(
        menu: CellWork.TaskMenu,
        task: CellSegmentPreview,
    ): CellWork.TaskMenu =
        menu.copy(
            name = task.text,
            isCompleted = task.isCompletedTask,
            currentMissingQuantity = task.currentMissingQuantity,
            poolType = task.poolType ?: menu.poolType,
            gameIsCompleted = rowOf(menu.gameId)?.isCompleted ?: menu.gameIsCompleted,
        )

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
    override fun closeInnermost() {
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
                                single = emptyRowFor(poolType),
                                // The batch has its rows from the start rather
                                // than being handed the other mode's when it is
                                // opened: nothing is carried between the modes.
                                rows = List(TaskComposer.LEAST_INDEPENDENT_TASKS) { emptyRowFor(poolType) },
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

    /**
     * Changes one row of the mode that is open, and nothing else.
     *
     * Which draft that row belongs to depends on the mode, and deliberately so:
     * the single-colour mode has one task of its own and the batch has its own
     * rows, so typing in one is never typing in the other. The panel says "the
     * row I am showing at this place"; this is where that is resolved.
     */
    private fun onRow(
        row: Int,
        change: (TaskDraftRow) -> TaskDraftRow,
    ) = onComposer { composer ->
        when {
            composer.mode == TaskCreationMode.SINGLE_COLOR ->
                if (row != 0) {
                    composer
                } else {
                    composer.copy(single = change(composer.single), failure = null, failureRow = null, failureConflictsWith = null)
                }

            row !in composer.rows.indices -> composer

            else ->
                composer.copy(
                    rows =
                        composer.rows.mapIndexed { index, existing ->
                            if (index == row) change(existing) else existing
                        },
                    failure = null,
                    failureRow = null,
                    failureConflictsWith = null,
                )
        }
    }

    /**
     * Switches which way tasks are being made.
     *
     * Nothing is copied, nothing is merged and nothing is asked. Each mode keeps
     * its own draft, so leaving one leaves it exactly as it stands and arriving
     * at another finds it exactly as it was left — including having nothing in
     * it. The only thing ever filled in is what the pool settles, which is not a
     * choice being made on the user's behalf.
     */
    fun chooseCreationMode(mode: TaskCreationMode) =
        onComposer { composer ->
            if (composer.mode == mode) {
                composer
            } else {
                val poolType = composer.columnType.poolType
                val palette =
                    if (mode == TaskCreationMode.SINGLE_ITEM_MULTICOLOR && poolType != null) {
                        composer.palette.copy(
                            trackingMode = composer.palette.trackingMode ?: onlyTrackingModeOf(poolType),
                        )
                    } else {
                        composer.palette
                    }
                composer.copy(mode = mode, palette = palette, failure = null, failureRow = null, failureConflictsWith = null)
            }
        }

    /** Adds a row at the end, which is where the next task goes. */
    fun addTaskRow() =
        onComposer { composer ->
            val poolType = composer.columnType.poolType ?: return@onComposer composer
            composer.copy(
                rows = composer.rows + emptyRowFor(poolType),
                failure = null,
                failureRow = null,
                failureConflictsWith = null,
            )
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
                composer.copy(
                    rows = composer.rows.filterIndexed { index, _ -> index != row },
                    failure = null,
                    failureRow = null,
                    failureConflictsWith = null,
                )
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
     * Changes the several-colour draft.
     *
     * [recallFocus] is for the changes that take away the control the user is
     * standing on: an entry removed takes its own buttons with it, and one moved
     * to either end leaves the button that moved it disabled. Focus on a control
     * that is gone is focus nowhere — the keyboard falls out of the panel
     * altogether — so it is called back to the panel's first field, which is
     * where colours are chosen.
     */
    private fun onPalette(
        recallFocus: Boolean = false,
        change: (MulticolorDraft) -> MulticolorDraft,
    ) {
        val making = composing() ?: return
        val composer = making.composer
        if (composer.isSaving) return
        state =
            state.copy(
                work =
                    making.copy(
                        composer =
                            composer.copy(
                                palette = change(composer.palette),
                                failure = null,
                                failureRow = null,
                                failureConflictsWith = null,
                            ),
                    ),
                focusRecall = if (recallFocus) state.focusRecall + 1 else state.focusRecall,
            )
    }

    fun editMulticolorColorQuery(query: String) = onPalette { it.copy(colorQuery = query) }

    /**
     * Puts a colour into the list, or takes it back out if it is already in it.
     *
     * Adding at the end, because the end is where the next colour goes and the
     * order is the order the name will be drawn in (PLAN 12.7). Choosing one
     * that is already there removes it rather than repeating it: PLAN 5.10
     * numbers a task's colours uniquely, so the same colour twice is not a thing
     * the user can be describing, and taking it out is the only reading of the
     * click that means anything.
     */
    fun toggleMulticolorColor(colorId: EntityId) {
        val removes =
            composing()
                ?.composer
                ?.palette
                ?.colorIds
                ?.contains(colorId) == true
        onPalette(recallFocus = removes) { palette ->
            val colors =
                if (colorId in palette.colorIds) {
                    palette.colorIds - colorId
                } else {
                    palette.colorIds + colorId
                }
            palette.copy(colorIds = colors)
        }
    }

    /** Moves one colour one place towards the front of the list. */
    fun moveMulticolorColorUp(slot: Int) = onPalette(recallFocus = true) { it.copy(colorIds = it.colorIds.movedUp(slot)) }

    /** Moves one colour one place towards the back of the list. */
    fun moveMulticolorColorDown(slot: Int) = onPalette(recallFocus = true) { it.copy(colorIds = it.colorIds.movedUp(slot + 1)) }

    /** Takes the quantity as typed; what is not a usable number stays visible. */
    fun editMulticolorQuantity(text: String) = onPalette { it.copy(quantityText = text) }

    /** Takes the note exactly as typed, spaces and all. */
    fun editMulticolorNotes(text: String) = onPalette { it.copy(notes = text) }

    fun chooseMulticolorTracking(trackingMode: TrackingMode) = onPalette { it.copy(trackingMode = trackingMode) }

    // ------------------------------------------------------ making a colour

    /**
     * Opens the colour picker over the panel that asked for it.
     *
     * [target] is fixed here and carried, rather than worked out when the colour
     * comes back. Each mode keeps its own draft and a batch has several rows, so
     * "where the new colour goes" is a question only the click that opened this
     * can answer.
     *
     * The wheel starts on the colour that place already holds, so a user
     * adjusting a colour they can see starts from it rather than from somewhere
     * else. The name always starts empty: PLAN 5.7 makes naming a decision, and
     * offering the old colour's name would be making it for them.
     */
    fun beginColorCreation(target: NewColorTarget) {
        val over = state.work
        val busy =
            when (over) {
                is CellWork.MakingTask -> over.composer.isSaving
                is CellWork.EditingTask -> over.editor.isSaving
                else -> return
            }
        if (busy) return
        state =
            state.copy(
                work =
                    CellWork.MakingColor(
                        from = over,
                        target = target,
                        composer = ColorComposer.startingFrom(startingColorFor(over, target)),
                    ),
                blockedByEditor = false,
                savedColorNotice = null,
                focusRecall = state.focusRecall + 1,
            )
    }

    /** The colour the target already holds, or the first base colour there is. */
    private fun startingColorFor(
        over: CellWork,
        target: NewColorTarget,
    ): String? {
        val standing =
            when {
                over is CellWork.MakingTask && target is NewColorTarget.SingleDraft -> over.composer.single.colorId
                over is CellWork.MakingTask && target is NewColorTarget.BatchRow ->
                    over.composer.rows
                        .getOrNull(target.row)
                        ?.colorId

                over is CellWork.MakingTask && target is NewColorTarget.MulticolorList ->
                    over.composer.palette.colorIds
                        .lastOrNull()

                over is CellWork.EditingTask -> over.editor.colorIds.lastOrNull()
                else -> null
            }
        return standing?.let { id -> state.colors.firstOrNull { it.id == id }?.hex }
            ?: baseColorsIn(state.colors).firstOrNull()?.hex
    }

    private fun making(): CellWork.MakingColor? = state.work as? CellWork.MakingColor

    private fun onNewColor(change: (ColorComposer) -> ColorComposer) {
        val making = making() ?: return
        if (making.isSaving) return
        state = state.copy(work = making.copy(composer = change(making.composer), failure = null))
    }

    fun editNewColorName(name: String) = onNewColor { it.copy(name = name) }

    /** Takes the whole colour from one of the twelve squares. */
    fun chooseNewColorBase(colorId: EntityId) {
        val hex = state.colors.firstOrNull { it.id == colorId }?.hex ?: return
        onNewColor { it.setTo(hex) }
    }

    /** Moves the colour to where the pointer is; the brightness does not move. */
    fun moveNewColorOnWheel(
        point: WheelPoint,
        radius: Float,
    ) = onNewColor { it.movedTo(point, radius) }

    /** The same, one arrow key press at a time. */
    fun nudgeNewColorWheel(nudge: WheelNudge) = onNewColor { it.nudgedBy(nudge) }

    /** Only the brightness moves; the place on the wheel stays. */
    fun setNewColorBrightness(brightness: Float) = onNewColor { it.brightenedTo(brightness) }

    /**
     * Closes the picker and writes nothing.
     *
     * The panel underneath comes back exactly as it was: nothing about the task
     * was being decided here.
     */
    fun cancelColorCreation() {
        val making = making() ?: return
        if (making.isSaving) return
        state = state.copy(work = making.from, focusRecall = state.focusRecall + 1)
    }

    /** Puts away the word that a colour was saved with nowhere to go. */
    fun acknowledgeSavedColor() {
        state = state.copy(savedColorNotice = null)
    }

    /**
     * Writes the colour, and then puts it where the picker was opened from.
     *
     * Two things, deliberately not one. PLAN 5.7 makes a saved colour a global
     * catalogue record from the moment it is written, so it is written on its
     * own — no task, no relation and no cell is touched by it — and choosing it
     * on a draft afterwards is nothing but a change to what is on screen. That
     * is also why giving up on the task afterwards leaves the colour standing:
     * it was never part of the task's transaction.
     *
     * The guard is set before anything suspends, which is the only place it
     * works, so two clicks in one frame make one colour.
     *
     * What comes back is applied to the panel this was opened from and to no
     * other. If that panel has gone, or moved on to another mode, the colour
     * still exists and the user is told so rather than having it dropped onto
     * whatever is open now.
     */
    suspend fun saveNewColor() {
        val making = making() ?: return
        if (!making.composer.canSave || making.isSaving) return
        val armed = making.copy(isSaving = true, failure = null)
        state = state.copy(work = armed)
        val created =
            try {
                colors.createColor(canonicalName = armed.composer.cleanName, hex = armed.composer.hex)
            } catch (failure: ColorSetupException) {
                if (state.work === armed) {
                    state = state.copy(work = armed.copy(isSaving = false, failure = failure.failure))
                }
                return
            }
        // Identity rather than equality: the picker that is open has to be the
        // one this call armed, not another one that happens to look the same.
        if (state.work !== armed) {
            state = state.copy(savedColorNotice = SavedColorNotice(armed.composer.cleanName))
            return
        }
        val applied = appliedTo(armed.from, armed.target, created)
        state =
            state.copy(
                work = applied ?: armed.from,
                savedColorNotice = if (applied == null) SavedColorNotice(armed.composer.cleanName) else null,
                focusRecall = state.focusRecall + 1,
                // Written, but the catalogue stream has not caught up. Until it
                // does, the draft holding it is not holding something missing.
                awaitedColorIds = if (applied == null) state.awaitedColorIds else state.awaitedColorIds + created,
            )
    }

    /**
     * The panel with the new colour chosen on it, or null when it cannot be.
     *
     * The mode is checked as well as the place: a target names a row of one
     * draft, and each mode has its own, so applying a batch row's colour to a
     * panel now showing another mode would change a draft the user was not
     * looking at.
     */
    private fun appliedTo(
        over: CellWork,
        target: NewColorTarget,
        colorId: EntityId,
    ): CellWork? =
        when {
            over is CellWork.MakingTask -> {
                val composer = over.composer
                val changed =
                    when {
                        target is NewColorTarget.SingleDraft && composer.mode == TaskCreationMode.SINGLE_COLOR ->
                            composer.copy(single = composer.single.copy(colorId = colorId))

                        target is NewColorTarget.BatchRow &&
                            composer.mode == TaskCreationMode.INDEPENDENT_TASKS &&
                            target.row in composer.rows.indices ->
                            composer.copy(
                                rows =
                                    composer.rows.mapIndexed { index, row ->
                                        if (index == target.row) row.copy(colorId = colorId) else row
                                    },
                            )

                        target is NewColorTarget.MulticolorList &&
                            composer.mode == TaskCreationMode.SINGLE_ITEM_MULTICOLOR ->
                            // At the end, which is where the next colour goes and
                            // the order the name will be drawn in (PLAN 12.7).
                            composer.copy(
                                palette =
                                    composer.palette.copy(
                                        colorIds = composer.palette.colorIds + colorId,
                                    ),
                            )

                        else -> null
                    }
                changed?.let {
                    over.copy(
                        composer = it.copy(failure = null, failureRow = null, failureConflictsWith = null),
                    )
                }
            }

            over is CellWork.EditingTask && target is NewColorTarget.EditedTask -> {
                val editor = over.editor
                // Which of the two it is was settled by the task, not here: PLAN
                // does not let a task cross between one colour and several.
                val colorIds = if (editor.holdsSeveralColors) editor.colorIds + colorId else listOf(colorId)
                over.copy(
                    editor = editor.copy(colorIds = colorIds, failure = null, failureRow = null, failureConflictsWith = null),
                )
            }

            else -> null
        }

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
        // A colour the draft still names but the catalogue has lost. The choice
        // is left exactly where the user put it and the save is refused instead,
        // so nothing is written pointing at a colour that is not there.
        if (state.strandedColorIds.isNotEmpty()) return
        // Read once, here, so a second Enter arriving while the first save is in
        // flight finds isSaving already set and does nothing: the same words
        // cannot become two sets of tasks.
        val drafts =
            if (composer.mode == TaskCreationMode.SINGLE_ITEM_MULTICOLOR) {
                val palette = composer.palette
                // One draft carrying every colour: one task, one total, one
                // counter, one place in the cell (PLAN 5.10).
                listOf(
                    TaskDraft(
                        colorIds = palette.colorIds.takeIf { it.size >= MulticolorDraft.LEAST_COLORS } ?: return,
                        requiredQuantity = palette.quantity ?: return,
                        trackingMode = palette.trackingMode ?: return,
                        notes = palette.notes.takeIf { it.isNotEmpty() },
                    ),
                )
            } else {
                composer.usedRows.map { row ->
                    TaskDraft(
                        colorIds = listOf(row.colorId ?: return),
                        requiredQuantity = row.quantity ?: return,
                        trackingMode = row.trackingMode ?: return,
                        // An empty note is no note; anything else is kept as typed.
                        notes = row.notes.takeIf { it.isNotEmpty() },
                    )
                }
            }

        state =
            state.copy(
                work =
                    making.copy(
                        composer =
                            composer.copy(
                                isSaving = true,
                                failure = null,
                                failureRow = null,
                                failureConflictsWith = null,
                            ),
                    ),
            )
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
                            it.copy(
                                composer =
                                    it.composer.copy(
                                        isSaving = false,
                                        failure = refusal.failure,
                                        failureRow = refusal.row,
                                        failureConflictsWith = refusal.conflictsWith,
                                    ),
                            )
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
                    if (open.composer.mode == TaskCreationMode.SINGLE_ITEM_MULTICOLOR) {
                        open.composer.palette.colorQuery
                    } else {
                        open.composer
                            .rowAt(row)
                            ?.colorQuery
                            .orEmpty()
                    }
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
                work = menuOver(gameId, columnType, task),
                blockedByEditor = false,
            )
    }

    /** A menu standing on one task, carrying everything it needs to decide what to offer. */
    private fun menuOver(
        gameId: EntityId,
        columnType: CellColumnType,
        task: CellSegmentPreview,
    ): CellWork.TaskMenu =
        CellWork.TaskMenu(
            gameId = gameId,
            columnType = columnType,
            taskId = requireNotNull(task.taskId),
            name = task.text,
            isCompleted = task.isCompletedTask,
            currentMissingQuantity = task.currentMissingQuantity,
            poolType = task.poolType ?: columnType.poolType,
            gameIsCompleted = rowOf(gameId)?.isCompleted == true,
            completionEventId = idGenerator.newId(),
        )

    private fun taskIn(
        gameId: EntityId,
        columnType: CellColumnType,
        taskId: EntityId,
    ): CellSegmentPreview? = rowOf(gameId)?.cell(columnType)?.tasks?.firstOrNull { it.taskId == taskId }

    // ------------------------------------------- finishing work, and what it owes

    /**
     * The task the inline tick is being pressed on, while its write is on its way.
     *
     * A finish and a reopen are one click each, and the click can be made again
     * before the first has landed. Holding the task here makes the second press
     * do nothing rather than send a second write — which would be harmless for
     * the finish itself (it is idempotent) but would settle a shortage twice
     * under two different names.
     */
    var busyTaskId: EntityId? by mutableStateOf(null)
        private set

    /** Why the last tick did not take, and on which task; cleared by the next try. */
    var tickFailure: Pair<EntityId, TaskProgressFailure>? by mutableStateOf(null)
        private set

    /**
     * Finishes an unfinished task, or reopens a finished one.
     *
     * What the tick on the piece does, and PLAN 12.5 puts a tick on every piece.
     * It reads which way to go from the task rather than from the caller, so the
     * control has one meaning — "this is done" — in both directions.
     */
    suspend fun toggleTaskCompletion(
        gameId: EntityId,
        columnType: CellColumnType,
        taskId: EntityId,
    ) {
        if (busyTaskId != null) return
        val task = taskIn(gameId, columnType, taskId) ?: return
        busyTaskId = taskId
        tickFailure = null
        val outcome =
            if (task.isCompletedTask) {
                taskProgress.reopenTask(taskId)
            } else {
                taskProgress.completeTask(taskId = taskId, eventId = idGenerator.newId())
            }
        busyTaskId = null
        // Nothing left to do is not a failure. A second press that arrives after
        // the first has landed has got what it asked for.
        if (outcome is TaskProgressOutcome.Refused) tickFailure = taskId to outcome.failure
    }

    /** The same, asked for from the menu, so the keyboard reaches it too. */
    suspend fun toggleCompletionFromMenu() {
        val menu = state.menu() ?: return
        if (menu.isWorking) return
        state = state.copy(work = state.work?.withMenu(menu.copy(isWorking = true, failure = null)))
        val outcome =
            if (menu.isCompleted) {
                taskProgress.reopenTask(menu.taskId)
            } else {
                taskProgress.completeTask(taskId = menu.taskId, eventId = menu.completionEventId)
            }
        val settled = state.menu() ?: return
        state =
            state.copy(
                work =
                    state.work?.withMenu(
                        settled.copy(
                            isWorking = false,
                            failure = (outcome as? TaskProgressOutcome.Refused)?.failure,
                        ),
                    ),
            )
    }

    /**
     * Opens the form that says how many pieces came out missing or spoiled.
     *
     * PLAN 6.3 has one action for both, so there is one form. A task in a
     * finished game is left alone until the slice that reopens games arrives:
     * reporting here would reopen the task and leave its game marked finished,
     * which is the half applied state PLAN 6.3 writes as one transaction.
     */
    fun beginReportShortage() {
        val menu = state.menu() ?: return
        if (menu.gameIsCompleted) {
            state = state.copy(work = state.work?.withMenu(menu.copy(failure = TaskProgressFailure.TASK_NOT_AVAILABLE)))
            return
        }
        state = state.copy(work = CellWork.ReportingShortage(from = menu.copy(failure = null), draft = newDraft()))
    }

    /** Opens the form that says how many of the pieces owed have been made again. */
    fun beginResolveShortage() {
        val menu = state.menu() ?: return
        if (!menu.owesSomething) return
        state = state.copy(work = CellWork.ResolvingShortage(from = menu.copy(failure = null), draft = newDraft()))
    }

    /** A fresh draft, with the name its movement will be written under already chosen. */
    private fun newDraft(): ShortageDraft = ShortageDraft(eventId = idGenerator.newId())

    fun editShortageQuantity(text: String) = onDraft { it.copy(quantity = text.filter(Char::isDigit).take(SHORTAGE_DIGITS)) }

    fun editShortageNote(text: String) = onDraft { it.copy(note = text) }

    fun editShortageCardReference(text: String) = onDraft { it.copy(cardReference = text) }

    /** Chooses the step the pieces were noticed at, or takes the choice back. */
    fun chooseShortageStage(stage: ProductionStage?) = onDraft { it.copy(stage = stage) }

    private fun onDraft(change: (ShortageDraft) -> ShortageDraft) {
        state =
            state.copy(
                work =
                    when (val open = state.work) {
                        is CellWork.ReportingShortage -> open.copy(draft = change(open.draft), failure = null)
                        is CellWork.ResolvingShortage -> open.copy(draft = change(open.draft), failure = null)
                        else -> return
                    },
            )
    }

    /**
     * Sends whichever shortage form is open.
     *
     * The name the movement is written under comes from the draft and is not
     * made again here, so a send that failed uncertainly and is sent again is
     * the same movement. A send already on its way is not sent a second time:
     * PLAN 5.12 makes the identity what tells a retry from a second report, and
     * two sends of one form are one report however fast they arrive.
     */
    suspend fun saveShortage() {
        val open = state.work
        if (open !is CellWork.ReportingShortage && open !is CellWork.ResolvingShortage) return
        val menu = open.menuOf() ?: return
        val draft =
            when (open) {
                is CellWork.ReportingShortage -> open.draft
                is CellWork.ResolvingShortage -> open.draft
                else -> return
            }
        if (isSavingShortage(open)) return
        val counted = draft.countedQuantity
        if (counted == null || counted <= 0) {
            state = state.copy(work = failing(open, TaskProgressFailure.INVALID_QUANTITY))
            return
        }
        state = state.copy(work = saving(open))
        val outcome =
            when (open) {
                is CellWork.ReportingShortage ->
                    taskProgress.reportFailure(
                        eventId = draft.eventId,
                        taskId = menu.taskId,
                        quantity = counted,
                        note = draft.writtenNote,
                        cardReference = draft.writtenCardReference(menu.poolType),
                        stage = draft.chosenStage(menu.poolType),
                    )

                else ->
                    taskProgress.resolveShortage(
                        eventId = draft.eventId,
                        taskId = menu.taskId,
                        quantity = counted,
                        note = draft.writtenNote,
                        cardReference = draft.writtenCardReference(menu.poolType),
                    )
            }
        val current = state.work
        if (current !is CellWork.ReportingShortage && current !is CellWork.ResolvingShortage) return
        state =
            when (outcome) {
                // Done or already recorded: either way the movement the user
                // asked for is in the history, so the form has finished its job
                // and closing back to the menu shows them the task as it now is.
                is TaskProgressOutcome.Done, TaskProgressOutcome.AlreadySo ->
                    state.copy(work = current.menuOf()?.copy(failure = null))

                // The draft stays exactly as typed, and so does the name the
                // movement would be written under, so trying again is the same
                // movement rather than a second one.
                is TaskProgressOutcome.Refused -> state.copy(work = failing(current, outcome.failure))
            }
    }

    private fun isSavingShortage(work: CellWork): Boolean =
        when (work) {
            is CellWork.ReportingShortage -> work.isSaving
            is CellWork.ResolvingShortage -> work.isSaving
            else -> false
        }

    private fun saving(work: CellWork): CellWork =
        when (work) {
            is CellWork.ReportingShortage -> work.copy(isSaving = true, failure = null)
            is CellWork.ResolvingShortage -> work.copy(isSaving = true, failure = null)
            else -> work
        }

    private fun failing(
        work: CellWork,
        failure: TaskProgressFailure,
    ): CellWork =
        when (work) {
            is CellWork.ReportingShortage -> work.copy(isSaving = false, failure = failure)
            is CellWork.ResolvingShortage -> work.copy(isSaving = false, failure = failure)
            else -> work
        }

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
                                colorIds = task.colors.map { it.colorId },
                                originalColorIds = task.colors.map { it.colorId },
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

    private fun GameTableScreenState.menu(): CellWork.TaskMenu? = work?.menuOf()

    private fun onEditor(
        recallFocus: Boolean = false,
        change: (TaskEditor) -> TaskEditor,
    ) {
        val editing = state.work as? CellWork.EditingTask ?: return
        if (editing.editor.isSaving) return
        state =
            state.copy(
                work = editing.copy(editor = change(editing.editor)),
                // See onPalette: a control that has been taken away cannot keep
                // the keyboard, so it is handed back to the panel itself.
                focusRecall = if (recallFocus) state.focusRecall + 1 else state.focusRecall,
            )
    }

    override fun editTaskName(name: String) = onEditor { it.copy(name = name, failure = null) }

    override fun editTaskEditColorQuery(query: String) = onEditor { it.copy(colorQuery = query) }

    /**
     * Chooses what the task is made in.
     *
     * A task made in one colour has that colour replaced, because one is all it
     * may have: PLAN describes no way of turning it into a task made in several,
     * and adding a second here would be inventing one. A task made in several
     * gains the colour at the end of its list, or loses it again if it is
     * already in there — the list is ordered and its entries are unique (PLAN
     * 5.10), so the same colour twice is not something to describe.
     */
    override fun chooseTaskEditColor(colorId: EntityId) {
        val editor = (state.work as? CellWork.EditingTask)?.editor
        val removes = editor?.holdsSeveralColors == true && colorId in editor.colorIds
        onEditor(recallFocus = removes) {
            val colors =
                when {
                    !it.holdsSeveralColors -> listOf(colorId)
                    colorId in it.colorIds -> it.colorIds - colorId
                    else -> it.colorIds + colorId
                }
            it.copy(colorIds = colors, failure = null, failureRow = null, failureConflictsWith = null)
        }
    }

    /** Moves one of a several-colour task's colours towards the front. */
    override fun moveTaskEditColorUp(slot: Int) =
        onEditor(recallFocus = true) {
            it.copy(colorIds = it.colorIds.movedUp(slot), failure = null, failureRow = null, failureConflictsWith = null)
        }

    /** Moves one of a several-colour task's colours towards the back. */
    override fun moveTaskEditColorDown(slot: Int) =
        onEditor(recallFocus = true) {
            it.copy(
                colorIds = it.colorIds.movedUp(slot + 1),
                failure = null,
                failureRow = null,
                failureConflictsWith = null,
            )
        }

    override fun editTaskEditQuantity(text: String) = onEditor { it.copy(quantityText = text, failure = null) }

    override fun editTaskEditNotes(text: String) = onEditor { it.copy(notes = text) }

    override fun chooseTaskEditTracking(trackingMode: TrackingMode) = onEditor { it.copy(trackingMode = trackingMode, failure = null) }

    /**
     * Saves everything the user changed about the task, all at once.
     *
     * A refusal leaves the panel standing with what they typed, so they can read
     * what went wrong and still have their answers.
     */
    override suspend fun saveTaskEdit() {
        val editing = state.work as? CellWork.EditingTask ?: return
        val editor = editing.editor
        if (!editor.canSave) return
        if (state.strandedColorIds.isNotEmpty()) return
        state = state.copy(work = editing.copy(editor = editor.copy(isSaving = true, failure = null)))
        try {
            taskEditing.editTask(
                taskId = editor.taskId,
                name = editor.name,
                colorIds = editor.colorIds,
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
                            it.copy(
                                editor =
                                    it.editor.copy(
                                        isSaving = false,
                                        failure = refusal.failure,
                                        failureRow = refusal.row,
                                        failureConflictsWith = refusal.conflictsWith,
                                    ),
                            )
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

/**
 * The same list with one entry moved one place towards the front.
 *
 * Unchanged when there is nowhere to move to, so the first entry going up and
 * the last going down are quietly nothing rather than an error: the buttons that
 * would do it are disabled, and a list is not a place to throw from.
 */
private fun <T> List<T>.movedUp(at: Int): List<T> =
    if (at <= 0 || at >= size) {
        this
    } else {
        toMutableList().apply { add(at - 1, removeAt(at)) }
    }
