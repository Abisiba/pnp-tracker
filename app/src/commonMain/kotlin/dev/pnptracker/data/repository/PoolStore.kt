package dev.pnptracker.data.repository

import dev.pnptracker.data.database.dao.PoolDao
import dev.pnptracker.data.database.projection.PoolColorRow
import dev.pnptracker.data.database.projection.PoolFailureRow
import dev.pnptracker.data.database.projection.PoolStageRow
import dev.pnptracker.data.database.projection.PoolTaskRow
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.hasStages
import dev.pnptracker.domain.pools.PoolColor
import dev.pnptracker.domain.pools.PoolCounts
import dev.pnptracker.domain.pools.PoolNavigationSummary
import dev.pnptracker.domain.pools.PoolSnapshot
import dev.pnptracker.domain.pools.PoolStage
import dev.pnptracker.domain.pools.PoolTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Where a pool screen reads what it shows. */
interface PoolSource {
    /**
     * Everything one pool holds, as a stream.
     *
     * One pool, not all four. A pool screen shows one at a time, and keeping the
     * other three running behind it would pay for reads nobody is looking at.
     */
    fun observePool(poolType: PoolType): Flow<PoolSnapshot>

    /** How much each pool is holding, for the sidebar. */
    fun observeNavigationSummary(): Flow<PoolNavigationSummary>
}

/**
 * Folds a pool's reads into its tasks.
 *
 * Three reads for a pool with colours, three for one with a pipeline, two for
 * one with neither — and the same number whether the pool holds one task or a
 * hundred. The reads are kept apart rather than joined because joining them
 * would multiply them: a task in three colours with three stages would come back
 * nine times and every quantity on it nine times over. Joining them here instead
 * is done by identity, which cannot multiply anything.
 *
 * Nothing here writes. Opening a pool costs reads and nothing else, which is
 * what makes it a reflection of the tasks rather than a record of its own.
 */
class PoolStore(
    private val poolDao: PoolDao,
) : PoolSource {
    override fun observePool(poolType: PoolType): Flow<PoolSnapshot> =
        combine(
            poolDao.observeTasksOfPool(poolType),
            // Only what the pool actually shows is read. The card pools carry no
            // colours and the 3D pool no stages, so asking for either there would
            // be a query whose answer is always empty.
            if (poolType == PoolType.THREE_D) poolDao.observeColorsOfPool(poolType) else flowOf(emptyList()),
            if (poolType.hasStages) poolDao.observeStagesOfPool(poolType) else flowOf(emptyList()),
            poolDao.observeFailureTotalsOfPool(poolType),
        ) { tasks, colors, stages, failures ->
            PoolSnapshot(poolType = poolType, tasks = tasksOf(tasks, colors, stages, failures))
        }

    override fun observeNavigationSummary(): Flow<PoolNavigationSummary> =
        poolDao.observePoolCounts().map { rows ->
            PoolNavigationSummary(
                counts = rows.associate { it.poolType to PoolCounts(it.activeCount, it.taskCount) },
            )
        }

    /**
     * Joins the reads by task identity.
     *
     * Each read is grouped once for the whole pool rather than looked up per
     * task, so the cost of folding grows with the rows and not with their
     * product. The colours and the stages arrive in their own order already —
     * slot order and pipeline order — so nothing is sorted here and the order
     * the user chose survives to the screen.
     *
     * A task with nothing reported against it is given a total of zero rather
     * than left absent, because a card that shows nothing and a card that shows
     * nothing wrong are different things to read.
     */
    private fun tasksOf(
        rows: List<PoolTaskRow>,
        colors: List<PoolColorRow>,
        stages: List<PoolStageRow>,
        failures: List<PoolFailureRow>,
    ): List<PoolTask> {
        val colorsByTask: Map<EntityId, List<PoolColor>> =
            colors.groupBy { it.taskId }.mapValues { (_, held) ->
                held.map { PoolColor(it.colorId, it.canonicalName, it.hex, it.sortOrder) }
            }
        val stagesByTask: Map<EntityId, List<PoolStage>> =
            stages.groupBy { it.taskId }.mapValues { (_, held) ->
                held.map { PoolStage(it.stage, it.completedQuantity) }
            }
        val failureByTask: Map<EntityId, Long> = failures.associate { it.taskId to it.failureTotal }
        return rows.map { row ->
            PoolTask(
                taskId = row.taskId,
                segmentId = row.segmentId,
                cellId = row.cellId,
                gameId = row.gameId,
                gameName = row.gameName,
                name = row.taskName,
                requiredQuantity = row.requiredQuantity,
                notes = row.notes,
                trackingMode = row.trackingMode,
                primaryBatchCompleted = row.primaryBatchCompleted,
                currentMissingQuantity = row.currentMissingQuantity,
                isMissing = row.isMissing,
                isBorrowed = row.isBorrowed,
                needsInfo = row.needsInfo,
                needsClassification = row.needsClassification,
                colors = colorsByTask[row.taskId].orEmpty(),
                stages = stagesByTask[row.taskId].orEmpty(),
                failureTotal = failureByTask[row.taskId] ?: 0L,
            )
        }
    }
}
