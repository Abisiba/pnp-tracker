package dev.pnptracker.domain.tasks

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.rules.allowedTrackingModes
import dev.pnptracker.domain.rules.offeredTrackingModes

/**
 * Where a task is going and which pool it belongs to, kept from contradicting
 * itself.
 *
 * A cell and a pool are not two independent answers. PLAN 5.4 gives a game one
 * cell per column and PLAN 5.6 gives a task one pool, and the column decides the
 * pool: a card task written in the cardboard column would turn up in a pool its
 * cell says nothing about. The database refuses such a pair, so a screen that
 * lets the user assemble one is a screen that collects an answer only to throw
 * it away.
 *
 * Both places a user aims a task from — the game's task form and the import
 * review — hold their choice as one of these and change it only through the two
 * transitions below, so neither screen invents its own version of the rule.
 *
 * The transitions are deliberately asymmetric, because the two acts mean
 * different things. Choosing a cell is choosing where the work is written down,
 * and the pool follows from it with nothing left to decide. Choosing a pool is
 * changing what kind of work it is, which can leave the cell behind: there is no
 * honest way to guess which other cell was meant, so the target is dropped and
 * the user says where it goes now.
 */
data class CellPoolChoice(
    val cellId: EntityId? = null,
    val columnType: CellColumnType? = null,
    val poolType: PoolType? = null,
    val trackingMode: TrackingMode? = null,
) {
    init {
        require((cellId == null) == (columnType == null)) {
            "A chosen cell is known by its column too, was: $cellId / $columnType"
        }
        require(columnType == null || poolType == null || columnType.poolType == poolType) {
            "A $poolType task does not belong in the $columnType column."
        }
    }

    /** True once the target is settled enough to write a task with. */
    val isComplete: Boolean get() = cellId != null && poolType != null && trackingMode != null

    /**
     * Aims at a cell, taking the pool from its column.
     *
     * A column that holds no tasks is not a target and is refused rather than
     * quietly accepted; PLAN 5.4 keeps tasks out of the notes column entirely,
     * and the lists a user picks from never offer one.
     */
    fun withCell(
        cellId: EntityId,
        columnType: CellColumnType,
    ): CellPoolChoice {
        val poolType =
            requireNotNull(columnType.poolType) {
                "The $columnType column holds no tasks, so nothing can be aimed at it."
            }
        return CellPoolChoice(
            cellId = cellId,
            columnType = columnType,
            poolType = poolType,
            trackingMode = onlyTrackingModeOf(poolType),
        )
    }

    /**
     * Changes the kind of work, dropping a target that no longer suits it.
     *
     * The tracking mode goes with the pool: a pool that allows one has it set
     * outright — written explicitly, never guessed later — and a pool that
     * allows more starts with none made, so the user has to say.
     */
    fun withPool(poolType: PoolType): CellPoolChoice {
        val keepsCell = columnType?.poolType == poolType
        return CellPoolChoice(
            cellId = cellId.takeIf { keepsCell },
            columnType = columnType.takeIf { keepsCell },
            poolType = poolType,
            trackingMode = onlyTrackingModeOf(poolType),
        )
    }

    /** Sets the tracking mode, ignoring one the pool does not allow. */
    fun withTracking(trackingMode: TrackingMode): CellPoolChoice {
        val poolType = poolType ?: return this
        if (trackingMode !in trackingModesOf(poolType)) return this
        return copy(trackingMode = trackingMode)
    }
}

/** True when a cell of this column can be aimed at while [poolType] is chosen. */
fun CellColumnType.suitsPool(poolType: PoolType?): Boolean = holdsTasks && (poolType == null || this.poolType == poolType)

/**
 * The tracking modes a new task in this pool can be given, in a fixed order the
 * screen can draw. One for every pool now, so a screen that offers a choice only
 * when there is more than one never asks.
 */
fun trackingModesOf(poolType: PoolType): List<TrackingMode> = listOf(offeredTrackingModes.getValue(poolType))

/** The mode a new task in this pool is given without asking, or null when the user would have to choose. */
fun onlyTrackingModeOf(poolType: PoolType): TrackingMode? = trackingModesOf(poolType).singleOrNull()

/**
 * The pool a tracking mode belongs to, read from every mode a task may carry —
 * including a mode no longer offered for new tasks, which older tasks still have.
 */
fun poolOfTrackingMode(trackingMode: TrackingMode): PoolType? =
    PoolType.entries.firstOrNull { trackingMode in allowedTrackingModes.getValue(it) }
