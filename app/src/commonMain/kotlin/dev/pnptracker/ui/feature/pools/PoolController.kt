package dev.pnptracker.ui.feature.pools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.data.repository.PoolSource
import dev.pnptracker.data.repository.TaskEditing
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.pools.PoolModel
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.domain.pools.poolModelOf
import dev.pnptracker.domain.rules.normalizeColorTerm
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.trackingModesOf
import dev.pnptracker.ui.feature.games.TaskEditor
import dev.pnptracker.ui.feature.tasks.TaskEditingHost
import dev.pnptracker.ui.feature.tasks.TaskEditingSnapshot
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map

/**
 * One pool screen.
 *
 * A pool is a reflection and never a record of its own: PLAN 12.10 says so in as
 * many words, and it shows in what this can do. Nothing here writes a task, a
 * segment or a pool row. What it does write goes through the very same editing
 * transaction the game table uses, on the very same task — which is why this
 * takes [TaskEditing] rather than anything of its own, and why it answers the
 * shared [TaskEditingHost] rather than growing a second editor beside it.
 *
 * The colour catalogue is here for the editor and for nothing else. The pool's
 * own colours arrive with its tasks, already named, so the list on screen owes
 * the catalogue nothing.
 */
class PoolController(
    val poolType: PoolType,
    private val pools: PoolSource,
    private val colors: ColorCatalogue,
    private val taskEditing: TaskEditing,
) : TaskEditingHost {
    var state: PoolScreenState by mutableStateOf(PoolScreenState(poolType = poolType))
        private set

    /**
     * The card the keyboard is owed, once whatever was over it closes.
     *
     * The card and not the task: a multi-colour task is drawn once under each of
     * its colours (PLAN 12.10), and coming back to the wrong one of those would
     * move the user somewhere they had not been.
     */
    var cardToFocus: PoolCardKey? by mutableStateOf(null)
        private set

    /** The catalogue, for the editor's colour list. */
    var catalogue: List<ColorSummary> by mutableStateOf(emptyList())
        private set

    /**
     * Follows the pool until cancelled.
     *
     * The grouping and the ordering happen here, on the way through, so what the
     * screen holds is already laid out and recomposing it draws rather than
     * decides. A read that fails leaves the screen standing and says so in the
     * one word PLAN 17 allows: nothing of the failure itself reaches the user.
     */
    suspend fun observePool() {
        pools
            .observePool(poolType)
            .map { PoolContentState.Content(poolModelOf(it)) as PoolContentState }
            .catch { emit(PoolContentState.Failed) }
            .collect(::show)
    }

    /** Shows one reading of the pool, laid out. */
    fun show(snapshot: dev.pnptracker.domain.pools.PoolSnapshot) {
        show(PoolContentState.Content(poolModelOf(snapshot)))
    }

    /**
     * Shows one state of the pool.
     *
     * The one place a new reading arrives, so what survives it is decided once
     * rather than at every call site.
     */
    fun show(content: PoolContentState) {
        state = state.copy(content = content, work = stillOpen(content, state.work))
    }

    /** Follows the catalogue, which only the editor uses. */
    suspend fun observeColorCatalogue() {
        colors.observeColors().collect(::showColors)
    }

    /** Shows one reading of the catalogue. */
    fun showColors(colors: List<ColorSummary>) {
        catalogue = colors
    }

    /**
     * What stays open when a new list arrives.
     *
     * A task the user is working on that is no longer in the pool — finished
     * somewhere else, deleted, turned back into words — closes what is over it,
     * because there is nothing left for it to be about. Everything else is left
     * exactly as it was: a list arriving is not a reason to throw away what
     * somebody is halfway through typing.
     */
    private fun stillOpen(
        content: PoolContentState,
        work: PoolWork?,
    ): PoolWork? {
        if (work == null) return null
        // A read that failed says nothing about the task, so it takes nothing away.
        val model = (content as? PoolContentState.Content)?.model ?: return work
        val current = model.taskNamed(work.task.taskId) ?: return null
        return work.about(current)
    }

    /** The same surface, over the task as it now stands. */
    private fun PoolWork.about(task: PoolTask): PoolWork =
        when (this) {
            is PoolWork.Menu -> copy(task = task)
            // The panels keep the menu they came from, so the name over them
            // follows the task. What is typed in the panel is the user's and is
            // never replaced: a change saved elsewhere must not reach in and
            // rewrite a draft.
            is PoolWork.Editing -> copy(from = from.copy(task = task))
            is PoolWork.ConfirmingConvert -> copy(from = from.copy(task = task))
        }

    /** The task by that identity as the pool now holds it, or null when it is gone. */
    private fun PoolModel.taskNamed(taskId: EntityId): PoolTask? =
        when (this) {
            is PoolModel.Flat -> tasks.firstOrNull { it.taskId == taskId }
            is PoolModel.ThreeD ->
                sections.awaitingColor.tasks.firstOrNull { it.taskId == taskId }
                    ?: sections.singleColorGroups.firstNotNullOfOrNull { group ->
                        group.tasks.firstOrNull { it.taskId == taskId }
                    }
                    ?: sections.multicolorGroups.firstNotNullOfOrNull { group ->
                        group.tasks.firstOrNull { it.taskId == taskId }
                    }
        }

    // ------------------------------------------------------ the stage details

    /**
     * Shows or hides one task's stage counters.
     *
     * Reading only. PLAN 12.11 has the counters edited from the badge in a later
     * slice; this step shows what they are and offers nothing that would change
     * one, so nothing here writes and nothing is asked of the database.
     */
    fun toggleStageDetails(taskId: EntityId) {
        val open = state.expandedStages
        state = state.copy(expandedStages = if (taskId in open) open - taskId else open + taskId)
    }

    fun isShowingStages(taskId: EntityId): Boolean = taskId in state.expandedStages

    // ----------------------------------------------------- working on a task

    /**
     * Opens the menu over one card.
     *
     * The task comes from the list the screen is already holding, so pressing a
     * card asks the database nothing. Refused while something else is open, the
     * same as the table refuses it: two menus over two tasks would be two places
     * to look at once.
     */
    fun openTaskMenu(card: PoolCardKey) {
        val open = state.work
        if (open != null) {
            if (open is PoolWork.Menu && open.card == card) return
            return
        }
        val task = taskOf(card.taskId) ?: return
        cardToFocus = card
        state = state.copy(work = PoolWork.Menu(card = card, task = task), focusRecall = state.focusRecall + 1)
    }

    /** The task behind a card, from the list already on screen. */
    private fun taskOf(taskId: EntityId): PoolTask? {
        val model = (state.content as? PoolContentState.Content)?.model ?: return null
        return when (model) {
            is PoolModel.Flat -> model.tasks.firstOrNull { it.taskId == taskId }
            is PoolModel.ThreeD ->
                model.sections.awaitingColor.tasks
                    .firstOrNull { it.taskId == taskId }
                    ?: model.sections.singleColorGroups.firstNotNullOfOrNull { group ->
                        group.tasks.firstOrNull { it.taskId == taskId }
                    }
                    ?: model.sections.multicolorGroups.firstNotNullOfOrNull { group ->
                        group.tasks.firstOrNull { it.taskId == taskId }
                    }
        }
    }

    /**
     * What the editor needs to open over a task, taken from what is on screen.
     *
     * The same shape the game table hands it, so both open the same form over
     * the same record. Everything in it came out of the pool's bulk reads, so
     * pressing a card costs no query.
     */
    fun editingSnapshotOf(taskId: EntityId): TaskEditingSnapshot? {
        val task = taskOf(taskId) ?: return null
        return TaskEditingSnapshot(
            taskId = task.taskId,
            segmentId = task.segmentId,
            cellId = task.cellId,
            gameId = task.gameId,
            columnType =
                dev.pnptracker.domain.model.CellColumnType
                    .of(poolType),
            name = task.name,
            colorIds = task.colors.map { it.colorId },
            requiredQuantity = task.requiredQuantity,
            notes = task.notes,
            trackingMode = task.trackingMode,
        )
    }

    /** Opens the panel that changes what the task is, over the same card. */
    fun beginTaskEdit() {
        val menu = state.menu() ?: return
        val snapshot = editingSnapshotOf(menu.task.taskId) ?: return
        state =
            state.copy(
                work = PoolWork.Editing(from = menu, editor = editorOf(snapshot)),
                focusRecall = state.focusRecall + 1,
            )
    }

    private fun PoolScreenState.menu(): PoolWork.Menu? =
        when (val open = work) {
            is PoolWork.Menu -> open
            is PoolWork.Editing -> open.from
            is PoolWork.ConfirmingConvert -> open.from
            null -> null
        }

    /** Asks whether the task really should go back to being words. */
    fun beginConvertToText() {
        val menu = state.menu() ?: return
        state =
            state.copy(
                work =
                    PoolWork.ConfirmingConvert(
                        from = menu,
                        // What there is to lose, from what the pool already knows:
                        // anything reported, anything owed, or any stage begun.
                        hasProgress =
                            menu.task.failureTotal > 0 ||
                                menu.task.currentMissingQuantity > 0 ||
                                menu.task.primaryBatchCompleted ||
                                menu.task.stages.any { it.completedQuantity > 0 },
                    ),
                focusRecall = state.focusRecall + 1,
            )
    }

    /** Turns the task back into text. Nothing is written until this is called. */
    suspend fun confirmConvertToText() {
        val confirming = state.work as? PoolWork.ConfirmingConvert ?: return
        if (confirming.isSaving) return
        state = state.copy(work = confirming.copy(isSaving = true))
        try {
            taskEditing.convertTaskToText(confirming.task.taskId)
            state = state.copy(work = null, focusRecall = state.focusRecall + 1)
        } catch (refusal: TaskEditException) {
            // The task is gone or would not go: either way the question has no
            // answer left, so it closes rather than standing over nothing.
            refusal.failure
            state = state.copy(work = confirming.copy(isSaving = false), focusRecall = state.focusRecall + 1)
        }
    }

    /** Closes the innermost surface, changing nothing anywhere. */
    override fun closeInnermost() {
        val open = state.work ?: return
        if (state.isSaving) return
        state = state.copy(work = open.parent, focusRecall = state.focusRecall + 1)
    }

    // ------------------------------------------------------- the shared form

    private fun editorOf(snapshot: TaskEditingSnapshot): TaskEditor =
        TaskEditor(
            taskId = snapshot.taskId,
            originalName = snapshot.name,
            name = snapshot.name,
            colorIds = snapshot.colorIds,
            originalColorIds = snapshot.colorIds,
            quantityText = snapshot.requiredQuantity?.toString().orEmpty(),
            originalQuantityText = snapshot.requiredQuantity?.toString().orEmpty(),
            notes = snapshot.notes.orEmpty(),
            originalNotes = snapshot.notes.orEmpty(),
            trackingMode = snapshot.trackingMode,
            originalTrackingMode = snapshot.trackingMode,
        )

    private fun onEditor(change: (TaskEditor) -> TaskEditor) {
        val editing = state.work as? PoolWork.Editing ?: return
        if (editing.editor.isSaving) return
        state = state.copy(work = editing.copy(editor = change(editing.editor)))
    }

    override fun editTaskName(name: String) = onEditor { it.copy(name = name, failure = null) }

    override fun editTaskEditColorQuery(query: String) = onEditor { it.copy(colorQuery = query) }

    /**
     * Chooses what the task is made in.
     *
     * The same rule the table uses, because it is the same transaction that will
     * refuse it: a task made in one colour has that colour replaced, and one made
     * in several gains or loses one from its ordered list. Neither may become the
     * other, so nothing here offers it.
     */
    override fun chooseTaskEditColor(colorId: EntityId) =
        onEditor {
            val colorIds =
                when {
                    !it.holdsSeveralColors -> listOf(colorId)
                    colorId in it.colorIds -> it.colorIds - colorId
                    else -> it.colorIds + colorId
                }
            it.copy(colorIds = colorIds, failure = null, failureRow = null, failureConflictsWith = null)
        }

    override fun moveTaskEditColorUp(slot: Int) =
        onEditor {
            it.copy(colorIds = it.colorIds.movedUp(slot), failure = null, failureRow = null, failureConflictsWith = null)
        }

    override fun moveTaskEditColorDown(slot: Int) =
        onEditor {
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
     * Through the same transaction the table saves through, on the same task, so
     * PLAN 12.10's promise is literal: a write begun from a pool happens on the
     * one `Task` and writes no record of its own. A refusal leaves the panel
     * standing with what they typed.
     */
    override suspend fun saveTaskEdit() {
        val editing = state.work as? PoolWork.Editing ?: return
        val editor = editing.editor
        if (!editor.canSave) return
        if (!catalogue.stillHasEveryColor(editor.colorIds)) return
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
                        (state.work as? PoolWork.Editing)?.copy(
                            editor =
                                editing.editor.copy(
                                    isSaving = false,
                                    failure = refusal.failure,
                                    failureRow = refusal.row,
                                    failureConflictsWith = refusal.conflictsWith,
                                ),
                        ),
                )
        }
    }

    /**
     * The catalogue narrowed by what has been typed.
     *
     * Folded the way the colour catalogue itself folds a name, so `gri`, `Gri`
     * and `GRİ` are the one colour they are on the user's keyboard. That folding
     * is only ever right for colour names.
     */
    fun colorsOffered(): List<ColorSummary> {
        val query = (state.work as? PoolWork.Editing)?.editor?.colorQuery.orEmpty()
        if (query.isBlank()) return catalogue
        val needle = normalizeColorTerm(query)
        return catalogue.filter { normalizeColorTerm(it.canonicalName).contains(needle) }
    }

    /** The tracking modes this pool allows, in the order it lists them. */
    fun trackingChoices(): List<TrackingMode> = trackingModesOf(poolType)
}

private fun List<ColorSummary>.stillHasEveryColor(ids: List<EntityId>): Boolean = isEmpty() || ids.all { id -> any { it.id == id } }

/** Moves the entry at [at] one place towards the front, or leaves the list alone. */
private fun <T> List<T>.movedUp(at: Int): List<T> =
    if (at <= 0 || at >= size) {
        this
    } else {
        toMutableList().apply { add(at - 1, removeAt(at)) }
    }
