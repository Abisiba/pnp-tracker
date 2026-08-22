package dev.pnptracker.ui.feature.games

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.rules.allowedTrackingModes
import dev.pnptracker.domain.tasks.TaskPoolGroup
import dev.pnptracker.domain.tasks.TaskSetupFailure

/**
 * Where one game's tasks section is.
 *
 * "None yet" is its own case rather than an empty list of groups, because a game
 * with no tasks has something to say to the user and a game with tasks does not.
 *
 * There is deliberately no completion anywhere in here. PLAN 6.4 says a task is
 * finished because its pool's counters and events say so, and those do not exist
 * yet, so this section shows what a task is and nothing about how far along it is.
 */
sealed interface GameTasksState {
    data object Loading : GameTasksState

    /** The game has no tasks yet. */
    data object Empty : GameTasksState

    /**
     * The game's tasks, split into pool sections.
     *
     * The sections are in the order the pools are listed in, and a pool with
     * nothing in it is left out rather than drawn empty.
     */
    data class Content(
        val groups: List<TaskPoolGroup>,
    ) : GameTasksState
}

/**
 * A task the user is typing, before anything is written.
 *
 * The quantity is held as the text that was typed rather than as a number, so
 * the form can tell "not filled in", which means the amount is unknown, apart
 * from "filled in with something that is not a usable count".
 */
data class TaskComposer(
    val cellId: EntityId? = null,
    val name: String = "",
    val poolType: PoolType? = null,
    val trackingMode: TrackingMode? = null,
    val quantity: String = "",
    val notes: String = "",
) {
    /** The amount that would be saved, or null when it is being left unknown. */
    val requiredQuantity: Int? get() = quantity.trim().toIntOrNull()

    /** True while the quantity box is empty, which is what an unknown amount looks like. */
    val isQuantityUnknown: Boolean get() = quantity.isBlank()

    /** True when a quantity was typed that is not a count anything could be made in. */
    val hasUnusableQuantity: Boolean
        get() {
            if (isQuantityUnknown) return false
            val typed = requiredQuantity ?: return true
            return typed <= 0
        }

    /** True when the pool leaves the user a real choice of tracking mode. */
    val offersTrackingChoice: Boolean get() = poolType != null && trackingModesOf(poolType).size > 1

    val canSave: Boolean
        get() =
            name.isNotBlank() &&
                cellId != null &&
                poolType != null &&
                trackingMode != null &&
                !hasUnusableQuantity
}

/** The tracking modes a pool allows, in a fixed order the screen can draw. */
fun trackingModesOf(poolType: PoolType): List<TrackingMode> = allowedTrackingModes.getValue(poolType).sortedBy { it.ordinal }

/** The mode a pool leaves no choice about, or null when it allows more than one. */
fun onlyTrackingModeOf(poolType: PoolType): TrackingMode? = allowedTrackingModes.getValue(poolType).singleOrNull()

/** What the tasks section is showing, what is being typed, and what did not save. */
data class GameTasksScreenState(
    val tasks: GameTasksState = GameTasksState.Loading,
    val composer: TaskComposer? = null,
    val failure: TaskSetupFailure? = null,
)
