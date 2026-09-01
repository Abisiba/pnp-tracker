package dev.pnptracker.ui.feature.pools

import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.data.repository.PoolSource
import dev.pnptracker.data.repository.TaskEditing
import dev.pnptracker.data.repository.TaskProgressOutcome
import dev.pnptracker.data.repository.TaskProgressing
import dev.pnptracker.domain.colors.BaseColorRestore
import dev.pnptracker.domain.colors.BaseColorRestorePlan
import dev.pnptracker.domain.colors.ColorRemoval
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.ColorUsage
import dev.pnptracker.domain.games.GameCompletionSnapshot
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.pools.PoolColor
import dev.pnptracker.domain.pools.PoolNavigationSummary
import dev.pnptracker.domain.pools.PoolSnapshot
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.domain.tasks.StageSnapshot
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A colour can head a group in both halves of the 3D pool.
 *
 * PLAN 12.10 lists tasks made in one colour apart from tasks made in several,
 * and a colour belongs to whichever tasks use it: grey heads a group of grey
 * tokens *and* appears again among the colours a grey-and-green house is made
 * in. The two sections are drawn as one list, so a key made only of the colour
 * is the same key twice — which a lazy list does not draw wrongly, it refuses.
 *
 * This is composed for real rather than read out of the source, because the
 * refusal only happens when the list is actually laid out.
 */
class PoolListKeyTest {
    private val grey = PoolColor(IdGenerator.Random.newId(), "Gri", "#808080", sortOrder = 2)
    private val green = PoolColor(IdGenerator.Random.newId(), "Yeşil", "#00A000", sortOrder = 5)

    private fun task(
        name: String,
        colors: List<PoolColor>,
    ) = PoolTask(
        taskId = IdGenerator.Random.newId(),
        segmentId = IdGenerator.Random.newId(),
        cellId = IdGenerator.Random.newId(),
        gameId = IdGenerator.Random.newId(),
        gameName = "Harmonies",
        name = name,
        requiredQuantity = 10,
        notes = null,
        trackingMode = TrackingMode.THREE_D_BATCH,
        primaryBatchCompleted = false,
        currentMissingQuantity = 0,
        failureTotal = 0,
        colors = colors,
        stages = emptyList(),
    )

    @Test
    fun `a colour heading a group in both sections does not collide`() {
        val pools = FixedPools()
        pools.snapshots.value =
            PoolSnapshot(
                PoolType.THREE_D,
                listOf(
                    // Grey heads a single-colour group…
                    task("Gri token", listOf(grey)),
                    // …and appears again inside a multi-colour one.
                    task("Ev", listOf(green, grey)),
                ),
            )
        val controller =
            PoolController(
                poolType = PoolType.THREE_D,
                pools = pools,
                colors = NoColors(),
                taskEditing = NoEditing(),
                taskProgress = NoProgress(),
            )

        ComposeSceneHarness(width = 900, height = 900) {
            PnpTrackerTheme(ThemeMode.LIGHT) { PoolScreen(controller) }
        }.use { harness ->
            harness.render()
            val spoken = harness.nodes().count()
            assertTrue(spoken > 0, "the pool drew nothing at all")
        }
    }

    private class FixedPools : PoolSource {
        val snapshots = MutableStateFlow(PoolSnapshot(PoolType.THREE_D, emptyList()))

        override fun observePool(poolType: PoolType): Flow<PoolSnapshot> = snapshots

        override fun observeNavigationSummary(): Flow<PoolNavigationSummary> = MutableStateFlow(PoolNavigationSummary.EMPTY)
    }

    private class NoColors : ColorCatalogue {
        override fun observeColors(): Flow<List<ColorSummary>> = MutableStateFlow(emptyList())

        override suspend fun colorsUsingHex(hex: String): List<ColorSummary> = emptyList()

        override suspend fun createColor(
            canonicalName: String,
            hex: String,
        ): EntityId = error("not asked")

        override suspend fun editColor(
            id: EntityId,
            expectedName: String,
            expectedHex: String,
            canonicalName: String,
            hex: String,
        ) = error("not asked")

        override suspend fun usageOf(id: EntityId): ColorUsage = error("not asked")

        override suspend fun deleteColor(id: EntityId): ColorRemoval = error("not asked")

        override suspend fun previewBaseColorRestore(): BaseColorRestorePlan = error("not asked")

        override suspend fun restoreMissingBaseColors(): BaseColorRestore = error("not asked")
    }

    private class NoEditing : TaskEditing {
        override suspend fun editTask(
            taskId: EntityId,
            name: String,
            colorIds: List<EntityId>,
            requiredQuantity: Int?,
            notes: String?,
            trackingMode: TrackingMode,
        ): Boolean = error("not asked")

        override suspend fun convertTaskToText(taskId: EntityId): Boolean = error("not asked")
    }

    private class NoProgress : TaskProgressing {
        override suspend fun completeTask(
            taskId: EntityId,
            eventId: EntityId,
        ): TaskProgressOutcome = error("not asked")

        override suspend fun reopenTask(taskId: EntityId): TaskProgressOutcome = error("not asked")

        override suspend fun gameCompletion(gameId: EntityId): GameCompletionSnapshot? = error("not asked")

        override suspend fun completeGame(
            gameId: EntityId,
            expected: GameCompletionSnapshot?,
        ): TaskProgressOutcome = error("not asked")

        override suspend fun reportFailure(
            eventId: EntityId,
            taskId: EntityId,
            quantity: Int,
            note: String?,
            cardReference: String?,
            stage: ProductionStage?,
        ): TaskProgressOutcome = error("not asked")

        override suspend fun resolveShortage(
            eventId: EntityId,
            taskId: EntityId,
            quantity: Int,
            note: String?,
            cardReference: String?,
        ): TaskProgressOutcome = error("not asked")

        override suspend fun setStageQuantities(
            taskId: EntityId,
            targets: Map<ProductionStage, Int>,
            expected: StageSnapshot?,
        ): TaskProgressOutcome = error("not asked")
    }
}
