package dev.pnptracker.domain.pools

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode

/** One colour a pool task is made in, as the pool shows it. */
data class PoolColor(
    val colorId: EntityId,
    val canonicalName: String,
    val hex: String,
    val sortOrder: Int,
)

/** One stage of a pipeline task, with how much of it is done. */
data class PoolStage(
    val stage: ProductionStage,
    val completedQuantity: Int,
)

/**
 * One task, as one pool shows it.
 *
 * There is exactly one of these per task however many colours it is made in.
 * PLAN 12.7 gives a single-item multi-colour task one identity, one quantity and
 * one counter, and PLAN 12.10 has it appear in each of its colour groups — so
 * what appears several times is a reference to this, never a copy of it. Two
 * copies would be two quantities to add up and two places for a total to go
 * wrong.
 */
data class PoolTask(
    val taskId: EntityId,
    val segmentId: EntityId,
    val cellId: EntityId,
    val gameId: EntityId,
    val gameName: String,
    val name: String,
    val requiredQuantity: Int?,
    val notes: String?,
    val trackingMode: TrackingMode,
    val primaryBatchCompleted: Boolean,
    val currentMissingQuantity: Int,
    /** PLAN 5.6: finished, and still in its cell and its colour groups. */
    val isCompleted: Boolean = false,
    /** In the user's own slot order (PLAN 5.10). */
    val colors: List<PoolColor> = emptyList(),
    /** In pipeline order, empty for the pools that have no pipeline. */
    val stages: List<PoolStage> = emptyList(),
    /** Uncapped, and so kept as wide as the database added it up (PLAN 6.4). */
    val failureTotal: Long = 0L,
    /** PLAN 10: came from the `Eksik` column. A note about the work, not a shortage. */
    val isMissing: Boolean = false,
    /** PLAN 10: came from the `Ödünç Parçalar` column. */
    val isBorrowed: Boolean = false,
    /** PLAN 11.7: something the work needs is still unknown. */
    val needsInfo: Boolean = false,
    /** PLAN 10: the pool was the user's own decision rather than the column's. */
    val needsClassification: Boolean = false,
) {
    /** True when something has gone wrong on this task, which sorts it forward. */
    val needsAttention: Boolean get() = currentMissingQuantity > 0 || failureTotal > 0

    /**
     * True when this task owes pieces right now.
     *
     * Narrower than [needsAttention], and deliberately so: PLAN 13's `eksik/hatalı
     * baskısı olanları öne alma` is about work that still has to be made, and a
     * task that failed once and was made good owes nothing today. The failure
     * total is history and is never deleted (PLAN 6.4), which is why it belongs
     * in the ordinary ordering and not in this.
     *
     * Nothing to do with [isMissing], which is a note the import carried over
     * from a spreadsheet column about a piece that is not in the box.
     */
    val hasCurrentShortage: Boolean get() = currentMissingQuantity > 0

    /**
     * The first stage that is not finished, or null when they all are.
     *
     * Finished means the whole required quantity has been through it. A task
     * with no required quantity has nothing to measure a stage against, so any
     * work at all counts it as begun and only the last stage having some counts
     * it as done — PLAN's pipeline invariant orders the stages, and reading them
     * in order is what makes the first gap the answer.
     */
    val firstUnfinishedStage: ProductionStage?
        get() =
            stages
                .firstOrNull { stage ->
                    requiredQuantity?.let { stage.completedQuantity < it } ?: (stage.completedQuantity == 0)
                }?.stage

    /** Where the first unfinished stage sits in the pipeline; last when there is none. */
    val stageRank: Int
        get() = firstUnfinishedStage?.let { first -> stages.indexOfFirst { it.stage == first } } ?: stages.size
}

/**
 * Everything one pool holds, before it is grouped or ordered.
 *
 * The four reads arrive separately and are joined here by task identity. They
 * are separate on purpose: a single query over colours, stages and events would
 * return their product, and every quantity on it would be counted once per
 * combination.
 */
data class PoolSnapshot(
    val poolType: PoolType,
    val tasks: List<PoolTask>,
)

/** How much each pool is holding, as the sidebar needs it. */
data class PoolCounts(
    val activeCount: Int,
    val taskCount: Int,
) {
    companion object {
        val NONE = PoolCounts(activeCount = 0, taskCount = 0)
    }
}

/**
 * What the sidebar knows about the pools without opening one.
 *
 * One small read for all four, so moving between sections never costs a pool's
 * worth of queries and the Special pool's visibility does not depend on anyone
 * having looked at it.
 */
data class PoolNavigationSummary(
    val counts: Map<PoolType, PoolCounts> = emptyMap(),
) {
    fun activeCountOf(poolType: PoolType): Int = counts[poolType]?.activeCount ?: 0

    /**
     * True when the Special pool is offered at all.
     *
     * PLAN 9 hides it until there is a special task, keeps it once there is one
     * — showing `0 aktif` after the last is finished — and hides it again only
     * when none is left. Finishing is not disappearing, so what decides this is
     * how many special tasks exist, not how many are still to do.
     */
    val showsSpecial: Boolean get() = (counts[PoolType.SPECIAL]?.taskCount ?: 0) > 0

    companion object {
        val EMPTY = PoolNavigationSummary()
    }
}
