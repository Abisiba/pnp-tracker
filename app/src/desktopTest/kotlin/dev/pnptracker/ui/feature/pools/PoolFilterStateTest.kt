package dev.pnptracker.ui.feature.pools

import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.pools.PoolColor
import dev.pnptracker.domain.pools.PoolModel
import dev.pnptracker.domain.pools.PoolSnapshot
import dev.pnptracker.domain.pools.PoolStage
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.domain.search.TaskFlagFilter
import dev.pnptracker.domain.search.TaskStateFilter
import dev.pnptracker.ui.NoColors
import dev.pnptracker.ui.NoEditing
import dev.pnptracker.ui.NoProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a pool screen remembers while the database goes on talking to it.
 *
 * The one rule underneath all of it: a fresh reading is news about the tasks and
 * says nothing about what the user asked to see. A list arriving must not empty
 * the search box, and opening a task must not widen the filter — the two live in
 * different parts of the state on purpose.
 */
class PoolFilterStateTest {
    private val grey = PoolColor(IdGenerator.Random.newId(), "Gri", "#808080", sortOrder = 2)
    private val red = PoolColor(IdGenerator.Random.newId(), "Kırmızı", "#C62828", sortOrder = 4)

    private fun task(
        name: String,
        colors: List<PoolColor> = listOf(red),
        isCompleted: Boolean = false,
        needsInfo: Boolean = false,
        isMissing: Boolean = false,
        stages: List<PoolStage> = emptyList(),
        taskId: EntityId = IdGenerator.Random.newId(),
    ) = PoolTask(
        taskId = taskId,
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
        isCompleted = isCompleted,
        colors = colors,
        stages = stages,
        isMissing = isMissing,
        needsInfo = needsInfo,
    )

    private fun controller(
        poolType: PoolType = PoolType.THREE_D,
        catalogue: List<ColorSummary> = emptyList(),
    ) = PoolController(
        poolType = poolType,
        pools = NoPools(),
        colors = NoColors(catalogue),
        taskEditing = NoEditing(),
        taskProgress = NoProgress(),
    )

    private fun PoolController.namesShown(): List<String> {
        val model = (state.content as? PoolContentState.Content)?.model ?: return emptyList()
        return when (model) {
            is PoolModel.Flat -> model.tasks.map { it.name }
            is PoolModel.ThreeD ->
                (
                    model.sections.awaitingColor.tasks +
                        model.sections.singleColorGroups.flatMap { it.tasks } +
                        model.sections.multicolorGroups.flatMap { it.tasks }
                ).map { it.name }.distinct()
        }
    }

    private fun snapshot(
        poolType: PoolType = PoolType.THREE_D,
        tasks: List<PoolTask>,
    ) = PoolSnapshot(poolType, tasks)

    // ------------------------------------------------- what a new list leaves

    @Test
    fun `a fresh list does not empty the search box`() {
        val controller = controller()
        controller.show(snapshot(tasks = listOf(task("Kırmızı ev"), task("Mavi çatı"))))
        controller.search("kırmızı")
        assertEquals(listOf("Kırmızı ev"), controller.namesShown())

        controller.show(snapshot(tasks = listOf(task("Kırmızı ev"), task("Mavi çatı"), task("Yeni iş"))))

        assertEquals("kırmızı", controller.state.searchText)
        assertEquals(listOf("Kırmızı ev"), controller.namesShown(), "a new list widened the search")
    }

    @Test
    fun `a fresh list does not undo a filter`() {
        val controller = controller()
        controller.show(snapshot(tasks = listOf(task("Kırmızı ev", colors = listOf(red)), task("Gri zar", colors = listOf(grey)))))
        controller.toggleColor(red.colorId)
        controller.toggleFlag(TaskFlagFilter.MISSING)

        controller.show(snapshot(tasks = listOf(task("Kırmızı ev", colors = listOf(red), isMissing = true))))

        assertEquals(setOf(red.colorId), controller.state.filter.colorIds)
        assertEquals(setOf(TaskFlagFilter.MISSING), controller.state.filter.flags)
        assertEquals(listOf("Kırmızı ev"), controller.namesShown())
    }

    @Test
    fun `a task that is finished elsewhere leaves the active view and comes back when reopened`() {
        val id = IdGenerator.Random.newId()
        val controller = controller()
        controller.show(snapshot(tasks = listOf(task("Kırmızı ev", taskId = id))))
        assertEquals(listOf("Kırmızı ev"), controller.namesShown())

        controller.show(snapshot(tasks = listOf(task("Kırmızı ev", taskId = id, isCompleted = true))))
        assertEquals(emptyList(), controller.namesShown(), "a finished task stayed on the active list")

        controller.show(snapshot(tasks = listOf(task("Kırmızı ev", taskId = id))))
        assertEquals(listOf("Kırmızı ev"), controller.namesShown(), "a reopened task did not come back")
    }

    @Test
    fun `a finished task is shown only when it is asked for`() {
        val controller = controller()
        controller.show(snapshot(tasks = listOf(task("Açık"), task("Bitmiş", isCompleted = true))))

        assertEquals(listOf("Açık"), controller.namesShown())
        controller.showState(TaskStateFilter.COMPLETED)
        assertEquals(listOf("Bitmiş"), controller.namesShown())
    }

    @Test
    fun `work waiting on information is kept apart from active work`() {
        val controller = controller()
        controller.show(snapshot(tasks = listOf(task("Açık"), task("Bilinmiyor", needsInfo = true))))

        assertEquals(listOf("Açık"), controller.namesShown())
        controller.showState(TaskStateFilter.NEEDS_INFO)
        assertEquals(listOf("Bilinmiyor"), controller.namesShown())
    }

    // ------------------------------------------------ emptiness, told apart

    @Test
    fun `a pool with nothing in it is not the same as a filter that found nothing`() {
        val controller = controller()

        controller.show(snapshot(tasks = emptyList()))
        assertFalse(controller.state.hasHiddenTasks, "an empty pool was blamed on the filter")

        controller.show(snapshot(tasks = listOf(task("Kırmızı ev"))))
        controller.search("bulunamaz")
        assertTrue(controller.state.hasHiddenTasks, "a filter that hid everything was not said to have")
        assertEquals(emptyList(), controller.namesShown())
    }

    // ------------------------------------------------------------- clearing

    @Test
    fun `clearing the search leaves every other choice alone`() {
        val controller = controller()
        controller.show(snapshot(tasks = listOf(task("Kırmızı ev", colors = listOf(red)))))
        controller.search("kırmızı")
        controller.toggleColor(red.colorId)

        controller.clearSearch()

        assertEquals("", controller.state.searchText)
        assertTrue(controller.state.filter.query.isEmpty)
        assertEquals(setOf(red.colorId), controller.state.filter.colorIds, "clearing the search dropped a colour")
    }

    @Test
    fun `clearing everything puts the screen back the way it opened`() {
        val controller = controller()
        controller.show(snapshot(tasks = listOf(task("Kırmızı ev", colors = listOf(red)), task("Bitmiş", isCompleted = true))))
        controller.search("bit")
        controller.toggleColor(red.colorId)
        controller.showState(TaskStateFilter.COMPLETED)
        controller.toggleShortagesFirst()

        controller.clearFilters()

        assertEquals("", controller.state.searchText)
        assertFalse(controller.state.isNarrowed)
        assertEquals(0, controller.state.chosenFilterCount)
        assertEquals(listOf("Kırmızı ev"), controller.namesShown())
    }

    // ------------------------------------------ a colour that is no longer there

    @Test
    fun `a colour deleted from the catalogue stops being a filter`() {
        val controller = controller(catalogue = listOf(summary(red), summary(grey)))
        controller.show(snapshot(tasks = listOf(task("Kırmızı ev", colors = listOf(red)), task("Gri zar", colors = listOf(grey)))))
        controller.showColors(listOf(summary(red), summary(grey)))
        controller.toggleColor(red.colorId)
        controller.toggleColor(grey.colorId)

        // Red is deleted somewhere else. Keeping it selected would quietly empty
        // the screen with nothing on it to say why.
        controller.showColors(listOf(summary(grey)))

        assertEquals(setOf(grey.colorId), controller.state.filter.colorIds)
        assertEquals(listOf("Gri zar"), controller.namesShown())
    }

    @Test
    fun `an empty catalogue is not a reason to drop a choice`() {
        // Nothing is known before the first reading arrives, and nothing that is
        // not known can be said to be missing.
        val controller = controller()
        controller.show(snapshot(tasks = listOf(task("Kırmızı ev", colors = listOf(red)))))
        controller.toggleColor(red.colorId)

        controller.showColors(emptyList())

        assertEquals(setOf(red.colorId), controller.state.filter.colorIds)
    }

    // -------------------------------------------------------- the surfaces

    @Test
    fun `Escape closes the filter panel and leaves the task surface open`() {
        val controller = controller()
        val subject = task("Kırmızı ev")
        controller.show(snapshot(tasks = listOf(subject)))
        controller.openTaskMenu(PoolCardKey(subject.taskId, red.colorId))
        assertTrue(controller.state.work is PoolWork.Menu)
        controller.openFilters()

        controller.closeInnermost()

        assertEquals(PoolFilterSurface.CLOSED, controller.state.filterSurface)
        assertTrue(controller.state.work is PoolWork.Menu, "closing the filter panel closed the task menu too")

        controller.closeInnermost()
        assertEquals(null, controller.state.work)
    }

    @Test
    fun `closing the filter panel calls the keyboard back`() {
        val controller = controller()
        controller.show(snapshot(tasks = listOf(task("Kırmızı ev"))))
        controller.openFilters()
        val before = controller.state.focusRecall

        controller.closeFilters()

        assertTrue(controller.state.focusRecall > before, "nothing asked for the keyboard back")
    }

    @Test
    fun `choosing a filter leaves the panel open`() {
        val controller = controller()
        controller.show(snapshot(tasks = listOf(task("Kırmızı ev", colors = listOf(red)))))
        controller.openFilters()

        controller.toggleColor(red.colorId)

        assertEquals(PoolFilterSurface.OPEN, controller.state.filterSurface)
    }

    // ------------------------------------------------------- what is offered

    @Test
    fun `only a pipeline pool offers a step to filter by`() {
        assertEquals(emptyList(), controller(PoolType.THREE_D).stageChoices())
        assertEquals(emptyList(), controller(PoolType.SPECIAL).stageChoices())
        assertEquals(
            listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT),
            controller(PoolType.CARD).stageChoices(),
        )
        assertEquals(
            listOf(ProductionStage.PRINT, ProductionStage.GLUE, ProductionStage.CUT),
            controller(PoolType.BOARD).stageChoices(),
        )
    }

    private fun summary(color: PoolColor) =
        ColorSummary(id = color.colorId, canonicalName = color.canonicalName, hex = color.hex, sortOrder = color.sortOrder)

    /** The pool is handed its readings by the test; nothing here is collected. */
    private class NoPools : dev.pnptracker.data.repository.PoolSource {
        override fun observePool(poolType: PoolType) = kotlinx.coroutines.flow.MutableStateFlow(PoolSnapshot(poolType, emptyList()))

        override fun observeNavigationSummary() =
            kotlinx.coroutines.flow.MutableStateFlow(dev.pnptracker.domain.pools.PoolNavigationSummary.EMPTY)
    }
}
