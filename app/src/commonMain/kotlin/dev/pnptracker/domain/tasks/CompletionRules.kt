package dev.pnptracker.domain.tasks

import dev.pnptracker.domain.model.PoolType

/**
 * What finishing a task means, as rules rather than as a step in one write path.
 *
 * Two things create a finished task: the user ticking one they already had, and
 * an import carrying a `**` marker they agreed to. PLAN 6.4 gives one answer for
 * both, so the answer lives here and neither writes its own version of it.
 */
object CompletionRules {
    /**
     * Whether a finished task counts as having had its print run made.
     *
     * PLAN 6.2 measures a 3D task by one print run, so finishing one says the run
     * happened. No other pool is measured that way, and a run that has already
     * been recorded stays recorded whatever the pool.
     */
    fun primaryBatchCompletedWhenFinished(
        poolType: PoolType,
        alreadyMade: Boolean,
    ): Boolean = alreadyMade || poolType == PoolType.THREE_D

    /**
     * What each stage of a finished task's pipeline should read.
     *
     * The whole total, so a finished pipeline is finished at every step. With no
     * total there is nothing for a stage to reach — PLAN 6.4 leaves such a task to
     * be finished by hand — so the pipeline is left where it stands.
     */
    fun stageCountWhenFinished(
        requiredQuantity: Int?,
        current: Int,
    ): Int = requiredQuantity ?: current
}
