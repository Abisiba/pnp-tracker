package dev.pnptracker.ui.feature.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.TaskSetup
import dev.pnptracker.domain.games.CellSummary
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellPoolChoice
import dev.pnptracker.domain.tasks.TaskSetupException
import dev.pnptracker.domain.tasks.groupByPool
import kotlinx.coroutines.flow.collect

/**
 * The tasks of whichever game is open, and the one form that adds to them.
 *
 * Kept apart from [GamesController] on purpose: the games and cells section
 * already works, and growing it would put the two under one saving flag, where
 * adding a task would grey out the game form and the other way round.
 *
 * The pool sections are worked out here rather than while drawing, so what the
 * screen is told to show is something a test can hold on to.
 *
 * Nothing in here can finish a task or say how far along one is. That is not an
 * oversight: PLAN 6.4 derives completion from each pool's counters and events,
 * and until those exist there is nothing to derive it from.
 */
class GameTasksController(
    private val setup: TaskSetup,
) {
    var state: GameTasksScreenState by mutableStateOf(GameTasksScreenState())
        private set

    /** True while a task is on its way to the database. */
    var isSaving: Boolean by mutableStateOf(false)
        private set

    /** Collects the open game's tasks until cancelled. */
    suspend fun observeTasks(gameId: EntityId) {
        setup.observeTasks(gameId).collect { tasks ->
            state =
                state.copy(
                    tasks =
                        if (tasks.isEmpty()) {
                            GameTasksState.Empty
                        } else {
                            GameTasksState.Content(groupByPool(tasks))
                        },
                )
        }
    }

    /** Puts the section back to where it starts, for when another game is opened. */
    fun forget() {
        state = GameTasksScreenState()
    }

    /**
     * Opens the form.
     *
     * @param cell the cell the user came from, when they came from one; they can
     *   still pick another before saving. Coming from a cell settles the pool
     *   too, because the column it belongs to decides that.
     */
    fun startComposer(cell: CellSummary? = null) {
        val choice = cell?.let { CellPoolChoice().withCell(it.id, it.columnType) } ?: CellPoolChoice()
        state = state.copy(composer = TaskComposer(choice = choice), failure = null)
    }

    /**
     * Aims the form at a cell, taking the pool from its column.
     *
     * The two are never asked separately, so the form cannot be carrying a pair
     * the database would refuse by the time the user presses save.
     */
    fun chooseCell(cell: CellSummary) {
        state = state.copy(composer = state.composer?.let { it.copy(choice = it.choice.withCell(cell.id, cell.columnType)) })
    }

    fun editName(name: String) {
        state = state.copy(composer = state.composer?.copy(name = name))
    }

    /**
     * Picks the pool, and with it the only tracking mode that pool allows.
     *
     * A pool with one mode has it set outright rather than being offered a list
     * of one, but the value is written explicitly; nothing later guesses it. A
     * pool with a real choice starts with none made, so the user has to say.
     *
     * Changing the pool drops a cell that no longer suits it. There is no honest
     * way to guess which other cell was meant, so the user says where it goes.
     */
    fun choosePool(poolType: PoolType) {
        state = state.copy(composer = state.composer?.let { it.copy(choice = it.choice.withPool(poolType)) })
    }

    fun chooseTracking(trackingMode: TrackingMode) {
        state = state.copy(composer = state.composer?.let { it.copy(choice = it.choice.withTracking(trackingMode)) })
    }

    fun editQuantity(quantity: String) {
        state = state.copy(composer = state.composer?.copy(quantity = quantity))
    }

    fun editNotes(notes: String) {
        state = state.copy(composer = state.composer?.copy(notes = notes))
    }

    /** Changes nothing anywhere; the task was never written. */
    fun cancelComposer() {
        state = state.copy(composer = null)
    }

    /**
     * Saves the task being typed, if it is complete enough to save.
     *
     * A second call while the first is still on its way does nothing, so one
     * insistent click cannot turn into two tasks. A task that does not save
     * leaves the form open with what was typed in it.
     */
    suspend fun save() {
        val composer = state.composer ?: return
        val cellId = composer.cellId ?: return
        val poolType = composer.poolType ?: return
        val trackingMode = composer.trackingMode ?: return
        if (!composer.canSave || isSaving) return
        isSaving = true
        try {
            setup.createTask(
                cellId = cellId,
                name = composer.name,
                poolType = poolType,
                trackingMode = trackingMode,
                requiredQuantity = composer.requiredQuantity.takeIf { !composer.isQuantityUnknown },
                notes = composer.notes,
            )
            state = state.copy(composer = null, failure = null)
        } catch (failure: TaskSetupException) {
            state = state.copy(failure = failure.failure)
        } finally {
            isSaving = false
        }
    }
}
