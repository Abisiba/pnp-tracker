package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.DraftEdit
import dev.pnptracker.data.repository.GameChoice
import dev.pnptracker.data.repository.ImportReview
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.importhint.CellHintAnalysis
import dev.pnptracker.domain.importhint.ColorVocabulary
import dev.pnptracker.domain.importhint.ImportHintAnalyzer
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewWorkspace
import dev.pnptracker.domain.importreview.ReviewDraftTask
import dev.pnptracker.domain.importreview.ReviewRawBlock
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.spreadsheet.CellSnapshot
import dev.pnptracker.domain.spreadsheet.SpreadsheetCellKind
import dev.pnptracker.domain.tasks.CellPoolChoice
import dev.pnptracker.domain.tasks.onlyTrackingModeOf
import dev.pnptracker.ui.StaleSurfaces
import kotlinx.coroutines.flow.collect

/**
 * Drives the review of one saved import: reading the cells, cutting tasks out of
 * them, and answering what the file only hinted at.
 *
 * The workspace is read as a stream rather than fetched, so a change simply
 * arrives; nothing here has to remember to reload. What the user is part way
 * through typing is kept entirely separate from that stream — see [surface] —
 * because a new workspace must never overwrite a field somebody is still filling
 * in.
 *
 * Nothing in this class creates a game, a cell or a task, and nothing moves the
 * import out of being a draft. Every hint the analyzer finds is offered as a
 * suggestion and none of them is ever applied on the user's behalf.
 */
class ImportReviewController(
    private val review: ImportReview,
) : StaleSurfaces {
    var state: ImportReviewState by mutableStateOf(ImportReviewState.Loading)
        private set

    /** Every game an accepted green cell could be about, kept fresh. */
    var games: List<GameChoice> by mutableStateOf(emptyList())
        private set

    /** The catalogue the colour list offers, kept fresh. */
    var colors: List<ColorSummary> by mutableStateOf(emptyList())
        private set

    /**
     * Which panel is open over the two panes.
     *
     * Kept apart from [state], which mirrors the database: this is what somebody
     * is typing and has not saved, so it must not be replaced when a new
     * workspace arrives, and it must leave no trace if they change their mind.
     */
    var surface: ImportReviewSurface by mutableStateOf(ImportReviewSurface.None)
        private set

    /** The words the user has pointed at in the open cell, if any. */
    var selection: RawSelection? by mutableStateOf(null)
        private set

    /** Where the keyboard should go next; cleared once the screen has honoured it. */
    var focus: ReviewFocus? by mutableStateOf(null)
        private set

    /** True while a change is on its way to the database. */
    var isSaving: Boolean by mutableStateOf(false)
        private set

    private var selectedBlockId: EntityId? = null
    private var lastWorkspace: ImportReviewWorkspace? = null
    private var vocabulary: ColorVocabulary = ColorVocabulary.of(emptyList())

    // What the detectors found in the cell being read, worked out once for that
    // cell rather than on every frame it is drawn in.
    private var analyzedBlockId: EntityId? = null
    private var analyzedVocabulary: ColorVocabulary? = null
    private var analysis: CellHintAnalysis? = null

    /** Collects the games a green cell could be about, until cancelled. */
    suspend fun observeGames() {
        review.observeActiveGames().collect { games = it }
    }

    /** Collects the colour catalogue and the words the detectors know, until cancelled. */
    suspend fun observeColors() {
        review.observeColorVocabulary().collect {
            vocabulary = it
            // The words changed, so what was worked out with the old ones no
            // longer describes this cell.
            analysis = null
        }
    }

    /** Collects the colours the list offers, until cancelled. */
    suspend fun observeColorCatalogue() {
        review.observeColors().collect { colors = it }
    }

    /**
     * Collects one import's workspace until cancelled.
     *
     * Everything the user was part way through is dropped first: a form of the
     * import being left behind must never stay open over the one being opened.
     */
    suspend fun observe(batchId: EntityId) {
        selectedBlockId = null
        lastWorkspace = null
        surface = ImportReviewSurface.None
        selection = null
        focus = null
        state = ImportReviewState.Loading
        review.observeWorkspace(batchId).collect { workspace -> show(workspace) }
    }

    // ------------------------------------------------------- reading a cell

    /**
     * Puts a cell in focus. An unknown or absent id simply clears the selection.
     *
     * Moving to another cell drops the words pointed at in the last one: offsets
     * only mean anything against the text they were taken from.
     */
    fun select(blockId: EntityId?) {
        val workspace = lastWorkspace ?: return
        val chosen = blockId?.takeIf { id -> workspace.rawBlocks.any { it.id == id } }
        if (chosen != selectedBlockId) selection = null
        selectedBlockId = chosen
        show(workspace)
    }

    /**
     * Records the words the user has pointed at in one cell.
     *
     * Called from the text field itself, with the offsets it reports. An empty
     * or backwards selection is kept as "nothing pointed at" rather than being
     * refused: the user is still dragging, and an error under a moving caret is
     * noise.
     */
    fun pointAt(
        blockId: EntityId,
        startIndex: Int,
        endIndex: Int,
    ) {
        selection =
            if (endIndex <= startIndex) null else RawSelection(blockId, startIndex, endIndex)
    }

    /** What the detectors found in the cell being read, or null when none is open. */
    fun hintsOfSelectedBlock(): CellHintAnalysis? {
        val block = state.let { it as? ImportReviewState.Content }?.selectedBlock ?: return null
        if (analyzedBlockId != block.id || analyzedVocabulary !== vocabulary || analysis == null) {
            analyzedBlockId = block.id
            analyzedVocabulary = vocabulary
            analysis = ImportHintAnalyzer(vocabulary).analyze(block.asCellSnapshot(), block.sourceColumnType)
        }
        return analysis
    }

    // -------------------------------------------------- making a draft task

    /**
     * Cuts a draft out of the words the user pointed at.
     *
     * The offsets go to the database as they are and the name is read from the
     * stored text inside the transaction, so a cell that has gone is caught
     * there rather than guessed at here. A refusal leaves the selection exactly
     * where it was, which is what lets the user widen it and try again.
     */
    suspend fun createFromSelection() {
        val pointed = selection ?: return
        if (isSaving) return
        isSaving = true
        try {
            val draftId = review.createDraftFromSelection(pointed.blockId, pointed.startIndex, pointed.endIndex)
            selection = null
            focus = ReviewFocus.Draft(draftId)
            clearFailure()
        } catch (failure: ImportReviewException) {
            reportFailure(failure)
        } finally {
            isSaving = false
        }
    }

    /**
     * Opens the form for a task typed by hand.
     *
     * Refused while another form is open, so the two ways of making a draft
     * cannot be half way through at once and neither can quietly lose what the
     * user typed in the other.
     */
    fun beginManualDraft(blockId: EntityId) {
        if (surface !is ImportReviewSurface.None) return
        surface = ImportReviewSurface.ManualDraft(blockId, name = "")
    }

    fun editManualName(name: String) {
        val manual = surface as? ImportReviewSurface.ManualDraft ?: return
        surface = manual.copy(name = name)
    }

    /** Changes nothing anywhere; the draft was never written. */
    fun cancelManualDraft() {
        if (surface is ImportReviewSurface.ManualDraft) surface = ImportReviewSurface.None
    }

    /** Stores the typed draft, which carries no selection at all. */
    suspend fun createByHand() {
        val manual = surface as? ImportReviewSurface.ManualDraft ?: return
        if (!manual.canSave || isSaving) return
        isSaving = true
        try {
            val draftId = review.createDraftByHand(manual.blockId, manual.name)
            surface = ImportReviewSurface.None
            focus = ReviewFocus.Draft(draftId)
            clearFailure()
        } catch (failure: ImportReviewException) {
            reportFailure(failure)
        } finally {
            isSaving = false
        }
    }

    // ------------------------------------------------------- editing a draft

    /** Opens one draft's form, filled in with what it says now. */
    fun openDraft(draft: ReviewDraftTask) {
        if (surface !is ImportReviewSurface.None) return
        surface = ImportReviewSurface.DraftEditor(draft.asForm())
    }

    fun editName(name: String) = changeForm { it.copy(name = name) }

    fun editQuantity(text: String) = changeForm { it.copy(quantityText = text) }

    /**
     * Says whether the amount is known at all.
     *
     * Turning it on empties the box rather than remembering a number behind it:
     * PLAN 11.7 makes an unknown amount a real answer, and a number kept out of
     * sight would come back the next time somebody untick.
     */
    fun setQuantityUnknown(unknown: Boolean) =
        changeForm { it.copy(quantityUnknown = unknown, quantityText = if (unknown) "" else it.quantityText) }

    fun editNotes(text: String) = changeForm { it.copy(notes = text) }

    /**
     * Aims the draft at one of the cells the user has opened.
     *
     * The pool and the tracking mode move with it through the one rule that
     * keeps a cell and a pool from contradicting each other, so what is sent to
     * the database is always a combination it will take.
     */
    fun chooseTarget(
        cellId: EntityId,
        columnType: dev.pnptracker.domain.model.CellColumnType,
    ) = changeForm { form ->
        val aimed = form.asChoice().withCell(cellId, columnType)
        form.copy(targetCellId = aimed.cellId, poolType = aimed.poolType, trackingMode = aimed.trackingMode)
    }

    fun choosePool(poolType: PoolType) =
        changeForm { form ->
            val picked = form.asChoice().withPool(poolType)
            form.copy(
                targetCellId = picked.cellId,
                poolType = picked.poolType,
                // A pool that allows exactly one way of tracking sets it outright
                // rather than leaving the draft in a state the database refuses.
                trackingMode = picked.trackingMode ?: onlyTrackingModeOf(poolType),
            )
        }

    fun chooseTracking(trackingMode: TrackingMode) =
        changeForm { form -> form.copy(trackingMode = form.asChoice().withTracking(trackingMode).trackingMode) }

    fun setMissing(isMissing: Boolean) = changeForm { it.copy(isMissing = isMissing, isBorrowed = if (isMissing) false else it.isBorrowed) }

    fun setBorrowed(isBorrowed: Boolean) =
        changeForm { it.copy(isBorrowed = isBorrowed, isMissing = if (isBorrowed) false else it.isMissing) }

    /**
     * Turns the "something is still unknown" mark on or off.
     *
     * Only ever from here. PLAN 11.7 makes it the user's own judgement, so
     * typing an amount does not quietly take it off: they say when the question
     * is answered.
     */
    fun setNeedsInfo(needsInfo: Boolean) = changeForm { it.copy(needsInfo = needsInfo) }

    fun setNeedsClassification(needsClassification: Boolean) = changeForm { it.copy(needsClassification = needsClassification) }

    /** Answers the `**` inside the open form, to be saved with the rest of it. */
    fun decideCompletion(decision: HintDecision) =
        changeForm { form ->
            if (form.completionHint == HintDecision.NONE) form else form.copy(completionHint = decision)
        }

    // ------------------------------------------------------ the colour list

    /** Opens the catalogue over the form. */
    fun openColorChoice() {
        val editor = surface as? ImportReviewSurface.DraftEditor ?: return
        surface = ImportReviewSurface.ColorChoice(editor.form, query = "")
    }

    fun editColorQuery(query: String) {
        val choice = surface as? ImportReviewSurface.ColorChoice ?: return
        surface = choice.copy(query = query)
    }

    /**
     * Adds a colour to the end of the list, or takes it off again.
     *
     * The same colour twice is a state the form can be in — the two entries are
     * pointed at and the save is refused — but choosing an entry that is already
     * there is plainly meant as taking it away, so that is what it does.
     */
    fun chooseColor(colorId: EntityId) =
        changeForm { form ->
            val at = form.colorIds.indexOf(colorId)
            if (at >= 0) {
                form.copy(colorIds = form.colorIds.filterIndexed { index, _ -> index != at })
            } else {
                focus = ReviewFocus.ColorSlot(form.colorIds.size)
                form.copy(colorIds = form.colorIds + colorId)
            }
        }

    fun removeColorAt(slot: Int) =
        changeForm { form ->
            if (slot !in form.colorIds.indices) {
                form
            } else {
                // The keyboard lands on the neighbour that takes the freed place,
                // or on the one before it when the last entry went.
                focus = ReviewFocus.ColorSlot(slot.coerceAtMost(form.colorIds.size - 2).coerceAtLeast(0))
                form.copy(colorIds = form.colorIds.filterIndexed { index, _ -> index != slot })
            }
        }

    fun moveColorUp(slot: Int) = swapColors(slot, slot - 1)

    fun moveColorDown(slot: Int) = swapColors(slot, slot + 1)

    private fun swapColors(
        from: Int,
        to: Int,
    ) = changeForm { form ->
        if (from !in form.colorIds.indices || to !in form.colorIds.indices) {
            form
        } else {
            val moved = form.colorIds.toMutableList()
            moved[from] = form.colorIds[to]
            moved[to] = form.colorIds[from]
            // The colour keeps the keyboard, not the place: the user is moving
            // one thing and wants to keep moving it.
            focus = ReviewFocus.ColorSlot(to)
            form.copy(colorIds = moved)
        }
    }

    // ------------------------------------------------------------- saving

    /**
     * Saves everything the open form says, in one transaction.
     *
     * A save already in flight makes this do nothing, so a double press cannot
     * write twice. A refusal leaves the form open with every field still in it,
     * because the user has to be able to see what to change.
     */
    suspend fun saveDraft() {
        val form = surface.openForm ?: return
        if (!form.canSave || isSaving) return
        isSaving = true
        try {
            review.saveDraft(
                DraftEdit(
                    draftTaskId = form.draftId,
                    name = form.name,
                    targetCellId = form.targetCellId,
                    poolType = form.poolType,
                    trackingMode = form.trackingMode,
                    requiredQuantity = form.requiredQuantity,
                    notes = form.storedNotes,
                    isMissing = form.isMissing,
                    isBorrowed = form.isBorrowed,
                    needsInfo = form.needsInfo,
                    needsClassification = form.needsClassification,
                    completionHint = form.completionHint,
                    colorIds = form.colorIds,
                ),
            )
            surface = ImportReviewSurface.None
            focus = ReviewFocus.Draft(form.draftId)
            clearFailure()
        } catch (failure: ImportReviewException) {
            reportFailure(failure)
        } finally {
            isSaving = false
        }
    }

    /**
     * Answers one draft's `**` on its own, without opening the form.
     *
     * PLAN 11.4 lists accepting and rejecting the marker beside the other
     * actions, so a user who only means "this one is done" says it in one press.
     */
    suspend fun answerCompletionHint(
        draftId: EntityId,
        decision: HintDecision,
    ) {
        if (isSaving) return
        isSaving = true
        try {
            review.setCompletionDecision(draftId, decision)
            focus = ReviewFocus.Draft(draftId)
            clearFailure()
        } catch (failure: ImportReviewException) {
            reportFailure(failure)
        } finally {
            isSaving = false
        }
    }

    // --------------------------------------------- the green game cell hint

    /** Opens the list of games, so an accepted green cell can say which one it meant. */
    fun openGameTarget(blockId: EntityId) {
        if (surface !is ImportReviewSurface.None) return
        surface = ImportReviewSurface.GameTarget(blockId)
    }

    /** Accepts the green cell for one game, in one guarded write. */
    suspend fun acceptGameHint(
        blockId: EntityId,
        gameId: EntityId,
    ) = answerGameHint(blockId, HintDecision.ACCEPTED, gameId)

    /** Rejects the green cell, which clears whatever game it named. */
    suspend fun rejectGameHint(blockId: EntityId) = answerGameHint(blockId, HintDecision.REJECTED, null)

    private suspend fun answerGameHint(
        blockId: EntityId,
        decision: HintDecision,
        gameId: EntityId?,
    ) {
        if (isSaving) return
        isSaving = true
        try {
            review.setGameCompletionDecision(blockId, decision, gameId)
            if (surface is ImportReviewSurface.GameTarget) surface = ImportReviewSurface.None
            focus = ReviewFocus.GameDecision
            clearFailure()
        } catch (failure: ImportReviewException) {
            reportFailure(failure)
        } finally {
            isSaving = false
        }
    }

    /**
     * Marks a cell reviewed, or takes the mark off again.
     *
     * Reversible on purpose: this records how far someone has read, and reading
     * can be revisited. If the write fails the old value stands and the screen
     * says so, because the stream will re-emit what the database really holds.
     */
    suspend fun setProcessed(
        blockId: EntityId,
        isProcessed: Boolean,
    ) {
        if (isSaving) return
        isSaving = true
        try {
            review.setProcessed(blockId, isProcessed)
            clearFailure()
        } catch (failure: ImportReviewException) {
            reportFailure(failure)
        } finally {
            isSaving = false
        }
    }

    // ------------------------------------------------------------ closing

    /**
     * Steps out of the innermost open panel, and no further.
     *
     * The colour list closes back onto the form the user was filling in, with
     * everything they had typed still there; only then does a second press close
     * the form. PLAN 17 will not have one key throw away a form somebody is part
     * way through.
     */
    fun closeInnermost() {
        surface =
            when (val open = surface) {
                is ImportReviewSurface.ColorChoice -> ImportReviewSurface.DraftEditor(open.form)
                is ImportReviewSurface.DraftEditor -> ImportReviewSurface.None
                is ImportReviewSurface.ManualDraft -> ImportReviewSurface.None
                is ImportReviewSurface.GameTarget -> ImportReviewSurface.None
                ImportReviewSurface.None -> {
                    // Nothing is open, so the innermost thing is the selection.
                    selection = null
                    ImportReviewSurface.None
                }
            }
    }

    /**
     * Lets go of the open panel and of the block it was opened from.
     *
     * The whole workspace is about one import batch, and after a restore that
     * batch either is not there or is a different one carrying the same
     * identifier. What was being typed goes with it rather than being applied to
     * rows nobody was looking at.
     */
    override fun abandonOpenWork() {
        surface = ImportReviewSurface.None
        selection = null
        focus = null
    }

    /** Sends the user to the cell and the draft one blocking problem is about. */
    fun goToProblem(
        blockId: EntityId,
        draftId: EntityId?,
    ) {
        select(blockId)
        focus = draftId?.let { ReviewFocus.Draft(it) } ?: ReviewFocus.Block(blockId)
    }

    /** Called by the screen once it has moved the keyboard where it was asked to. */
    fun focusHonoured() {
        focus = null
    }

    // ------------------------------------------------------------ internals

    private fun changeForm(change: (DraftForm) -> DraftForm) {
        surface =
            when (val open = surface) {
                is ImportReviewSurface.DraftEditor -> ImportReviewSurface.DraftEditor(change(open.form))
                is ImportReviewSurface.ColorChoice -> open.copy(form = change(open.form))
                else -> open
            }
    }

    private fun show(workspace: ImportReviewWorkspace?) {
        lastWorkspace = workspace
        state =
            when {
                workspace == null -> ImportReviewState.Unavailable
                workspace.rawBlocks.isEmpty() ->
                    ImportReviewState.Empty(workspace.fileName, workspace.sheetName)

                else -> {
                    // A cell can disappear under the selection only if the import
                    // was discarded elsewhere; the selection follows it out.
                    selectedBlockId = selectedBlockId?.takeIf { id -> workspace.rawBlocks.any { it.id == id } }
                    if (selection?.blockId?.let { id -> workspace.rawBlocks.none { it.id == id } } == true) {
                        selection = null
                    }
                    closeSurfaceIfItsSubjectIsGone(workspace)
                    ImportReviewState.Content(
                        workspace = workspace,
                        selectedBlockId = selectedBlockId,
                        failure = (state as? ImportReviewState.Content)?.failure,
                    )
                }
            }
    }

    /**
     * Closes a panel whose subject is not there any more, and only then.
     *
     * A workspace arriving is not a reason to close anything: the whole point of
     * keeping the form out of [state] is that a colour saved elsewhere, or
     * another cell being marked read, leaves what somebody is typing alone. A
     * draft that has actually gone is different — the form would be editing
     * nothing.
     */
    private fun closeSurfaceIfItsSubjectIsGone(workspace: ImportReviewWorkspace) {
        val subjectIsGone =
            when (val open = surface) {
                is ImportReviewSurface.ManualDraft -> workspace.rawBlocks.none { it.id == open.blockId }
                is ImportReviewSurface.GameTarget -> workspace.rawBlocks.none { it.id == open.blockId }
                is ImportReviewSurface.DraftEditor -> workspace.draftTasks.none { it.id == open.form.draftId }
                is ImportReviewSurface.ColorChoice -> workspace.draftTasks.none { it.id == open.form.draftId }
                ImportReviewSurface.None -> false
            }
        if (subjectIsGone || !workspace.isStillADraft) surface = ImportReviewSurface.None
    }

    private fun clearFailure() {
        val content = state as? ImportReviewState.Content ?: return
        state = content.copy(failure = null)
    }

    private fun reportFailure(failure: ImportReviewException) {
        val content = state as? ImportReviewState.Content ?: return
        state = content.copy(failure = failure.failure)
    }
}

/** What one draft says now, as a form to change it in. */
private fun ReviewDraftTask.asForm(): DraftForm =
    DraftForm(
        draftId = id,
        blockId = rawImportBlockId,
        name = name,
        targetCellId = targetCellId,
        poolType = selectedPoolType,
        trackingMode = selectedTrackingMode,
        quantityText = requiredQuantity?.toString().orEmpty(),
        quantityUnknown = requiredQuantity == null,
        notes = notes.orEmpty(),
        isMissing = isMissing,
        isBorrowed = isBorrowed,
        needsInfo = needsInfo,
        needsClassification = needsClassification,
        completionHint = completionHint,
        colorIds = colorIds,
    )

/** The form's aim, read back through the rule that keeps a cell and a pool in step. */
private fun DraftForm.asChoice(): CellPoolChoice =
    CellPoolChoice()
        .let { start -> poolType?.let(start::withPool) ?: start }
        .let { withPool -> trackingMode?.let(withPool::withTracking) ?: withPool }

/**
 * One reviewed cell as the detectors read it.
 *
 * The fill is the only formatting carried across, because it is the only one the
 * detectors use: PLAN 11.5 makes the font colours in the file a reading aid and
 * not a record of anything.
 */
private fun ReviewRawBlock.asCellSnapshot(): CellSnapshot =
    CellSnapshot(
        rowIndex = rowIndex,
        columnIndex = columnIndex,
        rawText = rawText,
        kind = SpreadsheetCellKind.TEXT,
        fillColorArgb = fillColorArgb?.let { argb -> argbHexOf(argb) },
    )

/** A packed fill colour written back the way the detectors read it. */
private fun argbHexOf(argb: Int): String =
    argb
        .toUInt()
        .toString(16)
        .padStart(8, '0')
        .uppercase()
