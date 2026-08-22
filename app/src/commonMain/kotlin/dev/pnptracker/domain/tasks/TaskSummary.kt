package dev.pnptracker.domain.tasks

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode

/**
 * A task as the game detail screen shows it.
 *
 * There is no completion field here, and that is a decision rather than an
 * omission: PLAN 6.4 says a task's completion is worked out from the counters and
 * events each pool keeps, so nothing may cache it as a value of its own until
 * those counters exist.
 *
 * [columnType] travels with the task because a game's tasks are shown grouped by
 * pool rather than by item, so a row has to say for itself what it belongs to.
 *
 * [isFromImport] only says whether there is a source cell behind this task; the
 * cell's identity stays in the database, where the audit trail belongs.
 */
data class TaskSummary(
    val id: EntityId,
    val cellId: EntityId,
    val columnType: CellColumnType,
    val poolType: PoolType,
    val trackingMode: TrackingMode,
    val name: String,
    val requiredQuantity: Int? = null,
    val notes: String? = null,
    val isFromImport: Boolean = false,
)

/** One pool's worth of a game's tasks, in the order the section lists them. */
data class TaskPoolGroup(
    val poolType: PoolType,
    val tasks: List<TaskSummary>,
)

/**
 * Splits a game's tasks into the pool sections the screen draws.
 *
 * The order of the sections is the order the pools are declared in, which is the
 * order PLAN 3.4 lists them: 3D, cards, board, special. A pool with nothing in it
 * is left out rather than drawn empty, and the tasks inside a section keep the
 * order they arrived in, so the caller's query decides the row order and this
 * decides nothing but the grouping.
 */
fun groupByPool(tasks: List<TaskSummary>): List<TaskPoolGroup> =
    PoolType.entries.mapNotNull { pool ->
        val inPool = tasks.filter { it.poolType == pool }
        if (inPool.isEmpty()) null else TaskPoolGroup(pool, inPool)
    }
