package dev.pnptracker.ui.feature.pools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.data.repository.PoolSource
import dev.pnptracker.data.repository.TaskEditing
import dev.pnptracker.data.repository.TaskProgressing
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.pools.PoolNavigationSummary
import kotlinx.coroutines.flow.collect

/**
 * The four pool screens, and the one small read the sidebar needs.
 *
 * One controller per pool, made once and kept, so moving away from a pool and
 * back does not start again from nothing. Only the pool being looked at is
 * reading: a controller follows its pool while its screen is composed and stops
 * when it leaves, so the other three cost nothing while they are out of sight.
 *
 * The summary is the exception and is always running. It is one grouped read
 * over the tasks, and the sidebar needs it whatever section is open — both to
 * say how much work each pool is holding and to decide whether the Special pool
 * is offered at all (PLAN 9).
 */
class PoolControllers(
    private val pools: PoolSource,
    private val colors: ColorCatalogue,
    private val taskEditing: TaskEditing,
    private val taskProgress: TaskProgressing,
) {
    private val controllers: Map<PoolType, PoolController> =
        PoolType.entries.associateWith { poolType ->
            PoolController(
                poolType = poolType,
                pools = pools,
                colors = colors,
                taskEditing = taskEditing,
                taskProgress = taskProgress,
            )
        }

    var summary: PoolNavigationSummary by mutableStateOf(PoolNavigationSummary.EMPTY)
        private set

    fun of(poolType: PoolType): PoolController = controllers.getValue(poolType)

    /** Follows how much each pool is holding, until cancelled. */
    suspend fun observeNavigationSummary() {
        pools.observeNavigationSummary().collect { summary = it }
    }
}
