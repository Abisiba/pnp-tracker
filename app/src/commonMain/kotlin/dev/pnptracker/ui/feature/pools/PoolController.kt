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
import dev.pnptracker.domain.model.stagesOf
import dev.pnptracker.domain.pools.PoolModel
import dev.pnptracker.domain.pools.PoolSnapshot
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.domain.pools.poolModelOf
import dev.pnptracker.domain.rules.normalizeColorTerm
import dev.pnptracker.domain.search.PoolFilter
import dev.pnptracker.domain.search.SearchQuery
import dev.pnptracker.domain.search.TaskFlagFilter
import dev.pnptracker.domain.search.TaskStateFilter
import dev.pnptracker.domain.search.filterPoolTasks
import dev.pnptracker.domain.tasks.StageSnapshot
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskFlags
import dev.pnptracker.domain.tasks.TaskProgressFailure
import dev.pnptracker.domain.tasks.quantityDigitsOf
import dev.pnptracker.domain.tasks.trackingModesOf
import dev.pnptracker.ui.StaleSurfaces
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
) : TaskEditingHost,
    StaleSurfaces {
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
     * The last whole reading of the pool, before any filter was applied to it.
     *
     * Kept because the filter is answered from here and never from the database:
     * PLAN 16 will not have the number of queries grow with what the user is
     * looking at, and a filter bound into the query would open a new stream on
     * every keystroke. The rows arrive once; narrowing them is arithmetic.
     */
    private var lastSnapshot: PoolSnapshot? = null

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
            .map { it as PoolSnapshot? }
            .catch { emit(null) }
            .collect { snapshot -> if (snapshot == null) show(PoolContentState.Failed) else show(snapshot) }
    }

    /** Shows one reading of the pool, laid out for whatever is being asked for. */
    fun show(snapshot: PoolSnapshot) {
        lastSnapshot = snapshot
        redraw()
    }

    /**
     * Lays the last reading out again under the filter as it stands.
     *
     * The one place the filter is applied, so changing a filter and receiving a
     * new list go through exactly the same arithmetic and cannot disagree. It
     * reads nothing: a filter change costs no query and opens no stream.
     */
    private fun redraw() {
        val snapshot = lastSnapshot ?: return
        val kept = filterPoolTasks(snapshot.tasks, state.filter)
        show(
            content = PoolContentState.Content(poolModelOf(snapshot.copy(tasks = kept), state.filter.shortagesFirst)),
            // Something to loosen rather than nothing to do: the pool holds work,
            // and the filter is why none of it is on screen.
            hasHiddenTasks = kept.isEmpty() && snapshot.tasks.isNotEmpty(),
        )
    }

    /**
     * Shows one state of the pool.
     *
     * The one place a new reading arrives, so what survives it is decided once
     * rather than at every call site.
     */
    fun show(
        content: PoolContentState,
        hasHiddenTasks: Boolean = false,
    ) {
        // A read that failed says nothing about any task, so it takes nothing
        // away; a list that arrived is the whole truth about which cards there
        // are, so an expansion over a task that has left it has nothing to be
        // about any more. Dropping it here is what stops a task finished from
        // the panel and reopened from the table coming back already open, with
        // the counters showing, when the user only asked for it to be worked on
        // again. Every other card keeps whatever it was showing.
        val model = (content as? PoolContentState.Content)?.model
        state =
            state.copy(
                content = content,
                hasHiddenTasks = hasHiddenTasks,
                work = stillOpen(content, state.work),
                expandedStages =
                    model?.let { shown -> state.expandedStages.filterTo(mutableSetOf()) { shown.taskNamed(it) != null } }
                        ?: state.expandedStages,
            )
    }

    /** Follows the catalogue, which only the editor uses. */
    suspend fun observeColorCatalogue() {
        colors.observeColors().collect(::showColors)
    }

    /**
     * Shows one reading of the catalogue.
     *
     * A colour the user was filtering by that has since been deleted stops being
     * a filter. It cannot match anything any more — nothing is made in a colour
     * that is not there — so leaving it selected would silently empty the screen
     * and offer no way to find out why. Everything else they chose is left alone.
     */
    fun showColors(colors: List<ColorSummary>) {
        catalogue = colors
        if (colors.isEmpty() || state.filter.colorIds.isEmpty()) return
        val known = colors.mapTo(mutableSetOf()) { it.id }
        val kept = state.filter.colorIds.intersect(known)
        if (kept.size != state.filter.colorIds.size) onFilter { it.copy(colorIds = kept) }
    }

    // ------------------------------------------------------- what is shown

    /**
     * Changes what the pool is being asked for, and draws it again.
     *
     * The single door every filter action goes through. Nothing is read and
     * nothing is written; the rows already in hand are narrowed and laid out
     * again, so a filter costs exactly one pass over a list.
     */
    private fun onFilter(change: (PoolFilter) -> PoolFilter) {
        val next = change(state.filter)
        if (next == state.filter) return
        state = state.copy(filter = next)
        redraw()
    }

    /** What is typed in the search box, kept as typed and folded when it is used. */
    fun search(text: String) {
        if (text == state.searchText) return
        state = state.copy(searchText = text)
        onFilter { it.copy(query = SearchQuery(text)) }
        // A query that trims to the same thing changes no rows, but the box has
        // to show what was typed either way.
    }

    /** Empties the search box on its own, leaving every other choice alone. */
    fun clearSearch() = search("")

    /** Adds a colour to the filter, or takes it out again. */
    fun toggleColor(colorId: EntityId) =
        onFilter { filter ->
            filter.copy(
                colorIds = if (colorId in filter.colorIds) filter.colorIds - colorId else filter.colorIds + colorId,
            )
        }

    /** Whether tasks with no colour at all are being asked for (PLAN 12.10). */
    fun toggleAwaitingColor() = onFilter { it.copy(awaitingColor = !it.awaitingColor) }

    /** Which of the three kinds of task the pool shows; one at a time. */
    fun showState(taskState: TaskStateFilter) = onFilter { it.copy(state = taskState) }

    /** Adds one of the import marks to the filter, or takes it out again. */
    fun toggleFlag(flag: TaskFlagFilter) =
        onFilter { filter ->
            filter.copy(flags = if (flag in filter.flags) filter.flags - flag else filter.flags + flag)
        }

    /** Adds a pipeline step to the filter, or takes it out again. */
    fun toggleStage(stage: ProductionStage) =
        onFilter { filter ->
            filter.copy(stages = if (stage in filter.stages) filter.stages - stage else filter.stages + stage)
        }

    /** Whether what is owed right now comes first (PLAN 13). */
    fun toggleShortagesFirst() = onFilter { it.copy(shortagesFirst = !it.shortagesFirst) }

    /** Puts everything back the way the screen opens, search included. */
    fun clearFilters() {
        if (state.filter == PoolFilter.NONE && state.searchText.isEmpty()) return
        state = state.copy(filter = PoolFilter.NONE, searchText = "")
        redraw()
    }

    /** Opens the panel of filter choices. */
    fun openFilters() {
        if (state.filterSurface == PoolFilterSurface.OPEN) return
        state = state.copy(filterSurface = PoolFilterSurface.OPEN)
    }

    /** Closes it, and hands the keyboard back to the button it was opened from. */
    fun closeFilters() {
        if (state.filterSurface == PoolFilterSurface.CLOSED) return
        state = state.copy(filterSurface = PoolFilterSurface.CLOSED, focusRecall = state.focusRecall + 1)
    }

    /** Which stages this pool's tasks can be waiting at, or none at all. */
    fun stageChoices(): List<ProductionStage> = stagesOf(poolType)

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
     * Reading only, and asking the database nothing: the counts arrived with the
     * pool. Changing one is [beginStageEdit]'s job, and opening that leaves this
     * showing underneath it so closing the panel does not fold the card away.
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
                        // The total belongs to the picture as much as the counts
                        // do: a target of 15/10/5 is most of a task of twenty and
                        // impossible for a task of twelve, so a total that moves
                        // while this is open must refuse the save rather than
                        // quietly change what the user described.
                        expected = StageSnapshot(requiredQuantity = task.requiredQuantity, stages = standing),
                        draft = standing.mapValues { (_, count) -> count.toString() },
                    ),
                // Opening it to be changed leaves it open to be read too, so
                // closing the panel does not fold the card away underneath it.
                expandedStages = state.expandedStages + card.taskId,
                focusRecall = state.focusRecall + 1,
            )
    }

    /**
     * Types into one step's box. Digits only, so nothing else can be sent.
     *
     * Nothing is cut short. A count is an [Int] and the largest one is ten
     * digits, so a box that stopped at nine would refuse a number the task's own
     * total is allowed to be — and would refuse it by quietly dropping what was
     * typed rather than by saying so. What will not fit is kept as it was typed
     * and refused where the user can see it instead, which is the same answer
     * the form that sets the total gives.
     */
    fun editStageDraft(
        stage: ProductionStage,
        typed: String,
    ) = onStages { open ->
        open.copy(
            draft = open.draft + (stage to quantityDigitsOf(typed)),
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
        val total = open.total ?: return@onStages open
        val at = open.draft[stage]?.toIntOrNull() ?: 0
        val moved = (at + by).coerceIn(0, total)
        open.copy(draft = open.draft + (stage to moved.toString()), failure = null, invalidStage = null)
    }

    /**
     * Whether an arrow has anywhere to move its step to.
     *
     * Only what a count can be: never below nothing, never past the total. The
     * ordering rule is deliberately **not** asked here, because it is a rule
     * about the pipeline the user ends up describing and not about every state
     * they pass through on the way. Asking it here locked the arrows: with the
     * print run behind the lamination the step that had to move was the one
     * whose every move made the picture no better on its own, so both of its
     * arrows went out and a user working by keyboard had nowhere left to go. The
     * whole target is checked once, when it is sent.
     */
    fun stageStepAllowed(
        stage: ProductionStage,
        by: Int,
    ): Boolean {
        val open = state.work as? PoolWork.EditingStages ?: return false
        val total = open.total ?: return false
        // A box holding something that is not a count has not said where it
        // stands, so an arrow may still put a count in it.
        val at = open.draft[stage]?.toIntOrNull() ?: return true
        val moved = at + by
        return moved in 0..total
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
                            invalidStage = open.firstUnusableStage,
                        ),
                )
            return
        }
        state = state.copy(work = open.copy(isSaving = true, failure = null, invalidStage = null))
        val outcome =
            taskProgress.setStageQuantities(
                taskId = open.task.taskId,
                targets = targets,
                expected = open.expected,
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
            TaskProgressFailure.INVALID_QUANTITY -> open.firstUnusableStage ?: open.steps.firstOrNull()
            TaskProgressFailure.STAGE_QUANTITY_EXCEEDS_REQUIRED ->
                open.total?.let { total ->
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
            isMissing = task.isMissing,
            isBorrowed = task.isBorrowed,
            needsInfo = task.needsInfo,
            needsClassification = task.needsClassification,
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

    /** Lets go of everything open; the tasks it was about are not there any more. */
    override fun abandonOpenWork() {
        state = state.copy(work = null, filterSurface = PoolFilterSurface.CLOSED, expandedStages = emptySet())
    }

    /** Closes the innermost surface, changing nothing anywhere. */
    override fun closeInnermost() {
        // The filter panel is drawn over the toolbar and over everything below
        // it, so it is the innermost thing there is whenever it is open. Closing
        // it leaves the task surface underneath exactly as it was — a panel over
        // a menu is two layers, and Escape takes one.
        if (state.filterSurface == PoolFilterSurface.OPEN) {
            closeFilters()
            return
        }
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
            isMissing = snapshot.isMissing,
            isBorrowed = snapshot.isBorrowed,
            needsInfo = snapshot.needsInfo,
            needsClassification = snapshot.needsClassification,
            originalIsMissing = snapshot.isMissing,
            originalIsBorrowed = snapshot.isBorrowed,
            originalNeedsInfo = snapshot.needsInfo,
            originalNeedsClassification = snapshot.needsClassification,
        )

    private fun onEditor(change: (TaskEditor) -> TaskEditor) {
        val editing = state.work as? PoolWork.Editing ?: return
        if (editing.editor.isSaving) return
        state = state.copy(work = editing.copy(editor = change(editing.editor)))
    }

    override fun editTaskName(name: String) = onEditor { it.copy(name = name, failure = null) }

    // The same rule the table applies, because it is the same transaction that
    // would refuse the pair: a task is missing or borrowed, never both.
    override fun setTaskEditMissing(isMissing: Boolean) =
        onEditor { it.copy(isMissing = isMissing, isBorrowed = if (isMissing) false else it.isBorrowed, failure = null) }

    override fun setTaskEditBorrowed(isBorrowed: Boolean) =
        onEditor {
            it.copy(isBorrowed = isBorrowed, isMissing = if (isBorrowed) false else it.isMissing, failure = null)
        }

    override fun setTaskEditNeedsInfo(needsInfo: Boolean) = onEditor { it.copy(needsInfo = needsInfo, failure = null) }

    override fun setTaskEditNeedsClassification(needsClassification: Boolean) =
        onEditor { it.copy(needsClassification = needsClassification, failure = null) }

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
                flags =
                    TaskFlags(
                        isMissing = editor.isMissing,
                        isBorrowed = editor.isBorrowed,
                        needsInfo = editor.needsInfo,
                        needsClassification = editor.needsClassification,
                    ),
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
