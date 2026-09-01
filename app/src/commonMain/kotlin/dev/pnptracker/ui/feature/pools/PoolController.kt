package dev.pnptracker.ui.feature.pools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.data.repository.PoolSource
import dev.pnptracker.data.repository.TaskEditing
import dev.pnptracker.data.repository.TaskProgressOutcome
import dev.pnptracker.data.repository.TaskProgressing
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.pools.PoolModel
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.domain.pools.poolModelOf
import dev.pnptracker.domain.rules.normalizeColorTerm
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskProgressFailure
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
    private val taskProgress: TaskProgressing,
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
            // The facts follow the task; the draft, the counts it was opened on
            // and whether it is being sent do not. What the user typed is theirs,
            // and the counts it was opened on are the proof the save is checked
            // against — replacing either would turn a stale save into a silent
            // overwrite of whatever arrived.
            is PoolWork.EditingStages -> copy(task = task)
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

    /**
     * Opens the pipeline of one task to be changed (PLAN 7.3).
     *
     * The counts it opens on are kept as they are, not merely shown: they are
     * what the save is checked against, so a panel left open while the work moved
     * on is refused rather than allowed to put back what it was opened with.
     *
     * Refused while something else is open, the same as the menu is: two panels
     * over two tasks would be two places to look at once. A task with no total
     * has nothing for a step to count up to (PLAN 7.2), so there is nothing here
     * to open.
     */
    fun beginStageEdit(card: PoolCardKey) {
        if (state.work != null) return
        val task = taskOf(card.taskId) ?: return
        if (task.requiredQuantity == null || task.stages.isEmpty()) return
        val standing = task.stages.associate { it.stage to it.completedQuantity }
        cardToFocus = card
        state =
            state.copy(
                work =
                    PoolWork.EditingStages(
                        card = card,
                        task = task,
                        expected = standing,
                        draft = standing.mapValues { (_, count) -> count.toString() },
                    ),
                // Opening it to be changed leaves it open to be read too, so
                // closing the panel does not fold the card away underneath it.
                expandedStages = state.expandedStages + card.taskId,
                focusRecall = state.focusRecall + 1,
            )
    }

    /** Types into one step's box. Digits only, so nothing else can be sent. */
    fun editStageDraft(
        stage: ProductionStage,
        typed: String,
    ) = onStages { open ->
        open.copy(
            draft = open.draft + (stage to typed.filter(Char::isDigit).take(STAGE_DIGITS)),
            failure = null,
            invalidStage = null,
        )
    }

    /**
     * Moves one step by one, up or down.
     *
     * The draft only. PLAN 7.3 has the whole pipeline saved together, so a
     * button that wrote as it was pressed would make three saves out of one
     * change of mind — and could not reach a state the ordering rule forbids
     * only on the way there.
     */
    fun stepStageDraft(
        stage: ProductionStage,
        by: Int,
    ) = onStages { open ->
        val total = open.task.requiredQuantity ?: return@onStages open
        val at = open.draft[stage]?.toIntOrNull() ?: 0
        val moved = (at + by).coerceIn(0, total)
        open.copy(draft = open.draft + (stage to moved.toString()), failure = null, invalidStage = null)
    }

    /**
     * Whether moving a step that way would describe a pipeline that cannot have
     * happened, so the button saying so can be turned off rather than refused.
     */
    fun stageStepAllowed(
        stage: ProductionStage,
        by: Int,
    ): Boolean {
        val open = state.work as? PoolWork.EditingStages ?: return false
        val total = open.task.requiredQuantity ?: return false
        val at = open.draft[stage]?.toIntOrNull() ?: return true
        val moved = at + by
        if (moved < 0 || moved > total) return false
        val steps = open.steps
        val position = steps.indexOf(stage)
        val before = steps.getOrNull(position - 1)?.let { open.draft[it]?.toIntOrNull() }
        val after = steps.getOrNull(position + 1)?.let { open.draft[it]?.toIntOrNull() }
        if (before != null && moved > before) return false
        if (after != null && moved < after) return false
        return true
    }

    private fun onStages(change: (PoolWork.EditingStages) -> PoolWork.EditingStages) {
        val open = state.work as? PoolWork.EditingStages ?: return
        if (open.isSaving) return
        state = state.copy(work = change(open))
    }

    /**
     * Sends the pipeline as the user has described it.
     *
     * One call for all three steps, so a target that breaks the ordering rule is
     * refused whole: saving them one at a time would write the first before
     * finding out the third would not do.
     */
    suspend fun saveStages() {
        val open = state.work as? PoolWork.EditingStages ?: return
        if (open.isSaving) return
        val targets = open.targets
        if (targets == null) {
            state =
                state.copy(
                    work =
                        open.copy(
                            failure = TaskProgressFailure.INVALID_QUANTITY,
                            invalidStage = open.firstUntypedStage,
                        ),
                )
            return
        }
        state = state.copy(work = open.copy(isSaving = true, failure = null, invalidStage = null))
        val outcome =
            taskProgress.setStageQuantities(
                taskId = open.task.taskId,
                targets = targets,
                expectedStages = open.expected,
            )
        val current = state.work as? PoolWork.EditingStages ?: return
        state =
            when (outcome) {
                // Done, or already standing at what was asked for: either way the
                // pipeline says what the user wanted, so the panel has finished
                // its job and closing shows them the card as it now is. A task
                // finished by the save has left the pool, and the list arriving
                // closes the panel on its own.
                is TaskProgressOutcome.Done, TaskProgressOutcome.AlreadySo ->
                    state.copy(work = null, focusRecall = state.focusRecall + 1)

                // What was typed stays exactly as typed, and so do the counts the
                // panel was opened on: trying again is the same save, not one
                // aimed at a picture nobody has seen.
                is TaskProgressOutcome.Refused ->
                    state.copy(
                        work =
                            current.copy(
                                isSaving = false,
                                failure = outcome.failure,
                                invalidStage = stageBlamedFor(outcome.failure, current),
                            ),
                    )
            }
    }

    /**
     * Which step a refusal is about, so the keyboard can be sent to it.
     *
     * Only the refusals that are about one step name one. A pipeline that has
     * moved underneath the panel is about all of them at once, and sending the
     * keyboard into a box would suggest the answer were there.
     */
    private fun stageBlamedFor(
        failure: TaskProgressFailure,
        open: PoolWork.EditingStages,
    ): ProductionStage? =
        when (failure) {
            TaskProgressFailure.INVALID_QUANTITY -> open.firstUntypedStage ?: open.steps.firstOrNull()
            TaskProgressFailure.STAGE_QUANTITY_EXCEEDS_REQUIRED ->
                open.task.requiredQuantity?.let { total ->
                    open.steps.firstOrNull { (open.draft[it]?.toIntOrNull() ?: 0) > total }
                }

            TaskProgressFailure.STAGE_ORDER_VIOLATED ->
                open.steps
                    .zipWithNext()
                    .firstOrNull { (earlier, later) ->
                        val before = open.draft[earlier]?.toIntOrNull() ?: 0
                        val after = open.draft[later]?.toIntOrNull() ?: 0
                        after > before
                    }?.second

            else -> null
        }

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

    /**
     * Opens the task's own form straight from a card.
     *
     * The same form the menu opens, entered without the menu in between. PLAN
     * 7.2 sends a task with no total here to be given one, and offering a second
     * form for that would be two places to type the same thing.
     */
    fun beginTaskEditFor(card: PoolCardKey) {
        if (state.work != null) return
        val task = taskOf(card.taskId) ?: return
        val snapshot = editingSnapshotOf(task.taskId) ?: return
        cardToFocus = card
        state =
            state.copy(
                work =
                    PoolWork.Editing(
                        from = PoolWork.Menu(card = card, task = task),
                        editor = editorOf(snapshot),
                    ),
                focusRecall = state.focusRecall + 1,
            )
    }

    private fun PoolScreenState.menu(): PoolWork.Menu? =
        when (val open = work) {
            is PoolWork.Menu -> open
            is PoolWork.Editing -> open.from
            is PoolWork.ConfirmingConvert -> open.from
            // The pipeline panel is its own control on the card, opened without
            // a menu ever being shown, so there is none behind it.
            is PoolWork.EditingStages -> null
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

/** How long a step count may be typed. A pipeline counts pieces, not populations. */
private const val STAGE_DIGITS = 9
