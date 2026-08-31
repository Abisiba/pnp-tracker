package dev.pnptracker.ui.feature.pools

import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.data.repository.PoolSource
import dev.pnptracker.data.repository.TaskEditing
import dev.pnptracker.domain.colors.BaseColorRestore
import dev.pnptracker.domain.colors.BaseColorRestorePlan
import dev.pnptracker.domain.colors.ColorRemoval
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.ColorUsage
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.pools.PoolColor
import dev.pnptracker.domain.pools.PoolModel
import dev.pnptracker.domain.pools.PoolNavigationSummary
import dev.pnptracker.domain.pools.PoolSnapshot
import dev.pnptracker.domain.pools.PoolStage
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskEditFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A pool that answers from a list a test sets, so nothing here needs a database. */
private class FakePools : PoolSource {
    val snapshots = MutableStateFlow(PoolSnapshot(PoolType.THREE_D, emptyList()))
    val summary = MutableStateFlow(PoolNavigationSummary.EMPTY)

    override fun observePool(poolType: PoolType): Flow<PoolSnapshot> = snapshots

    override fun observeNavigationSummary(): Flow<PoolNavigationSummary> = summary
}

/** A catalogue that only has to answer the editor's colour list. */
private class FakeColors : ColorCatalogue {
    val colors = MutableStateFlow<List<ColorSummary>>(emptyList())

    override fun observeColors(): Flow<List<ColorSummary>> = colors

    override suspend fun colorsUsingHex(hex: String): List<ColorSummary> = emptyList()

    override suspend fun createColor(
        canonicalName: String,
        hex: String,
    ): EntityId = error("the pool does not make colours")

    override suspend fun editColor(
        id: EntityId,
        expectedName: String,
        expectedHex: String,
        canonicalName: String,
        hex: String,
    ) = error("the pool does not change colours")

    override suspend fun usageOf(id: EntityId): ColorUsage = error("the pool does not ask this")

    override suspend fun deleteColor(id: EntityId): ColorRemoval = error("the pool does not remove colours")

    override suspend fun previewBaseColorRestore(): BaseColorRestorePlan = error("the pool does not restore colours")

    override suspend fun restoreMissingBaseColors(): BaseColorRestore = error("the pool does not restore colours")
}

/** The editing transaction, recorded rather than run. */
private class FakeEditing : TaskEditing {
    var edits: MutableList<EditRecord> = mutableListOf()
    var converted: MutableList<EntityId> = mutableListOf()
    var refuseWith: TaskEditException? = null

    data class EditRecord(
        val taskId: EntityId,
        val name: String,
        val colorIds: List<EntityId>,
        val requiredQuantity: Int?,
        val notes: String?,
        val trackingMode: TrackingMode,
    )

    override suspend fun editTask(
        taskId: EntityId,
        name: String,
        colorIds: List<EntityId>,
        requiredQuantity: Int?,
        notes: String?,
        trackingMode: TrackingMode,
    ): Boolean {
        refuseWith?.let { throw it }
        edits += EditRecord(taskId, name, colorIds, requiredQuantity, notes, trackingMode)
        return true
    }

    override suspend fun convertTaskToText(taskId: EntityId): Boolean {
        refuseWith?.let { throw it }
        converted += taskId
        return true
    }
}

/**
 * What a pool screen does, without a database or a window.
 *
 * The three things that matter here are the ones PLAN 12.10 makes promises
 * about: a pool never writes a record of its own, a task shown in several colour
 * groups is one task with one menu, and a change begun from a pool lands on that
 * task through the same transaction the table uses.
 */
class PoolControllerTest {
    private val pools = FakePools()
    private val colors = FakeColors()
    private val editing = FakeEditing()

    private val red = PoolColor(IdGenerator.Random.newId(), "Kırmızı", "#E53935", sortOrder = 4)
    private val yellow = PoolColor(IdGenerator.Random.newId(), "Sarı", "#FDD835", sortOrder = 5)
    private val black = PoolColor(IdGenerator.Random.newId(), "Siyah", "#111111", sortOrder = 1)

    private fun controllerFor(poolType: PoolType = PoolType.THREE_D) =
        PoolController(poolType = poolType, pools = pools, colors = colors, taskEditing = editing)

    private fun task(
        name: String = "Yarasa",
        taskId: EntityId = IdGenerator.Random.newId(),
        quantity: Int? = 10,
        colors: List<PoolColor> = emptyList(),
        stages: List<PoolStage> = emptyList(),
        notes: String? = null,
        tracking: TrackingMode = TrackingMode.THREE_D_BATCH,
    ) = PoolTask(
        taskId = taskId,
        segmentId = IdGenerator.Random.newId(),
        cellId = IdGenerator.Random.newId(),
        gameId = IdGenerator.Random.newId(),
        gameName = "Harmonies",
        name = name,
        requiredQuantity = quantity,
        notes = notes,
        trackingMode = tracking,
        primaryBatchCompleted = false,
        currentMissingQuantity = 0,
        colors = colors,
        stages = stages,
    )

    /**
     * Runs the body with the pool holding [tasks].
     *
     * The stream is read once rather than collected forever, so the test ends
     * when the body does instead of waiting on a flow that never completes.
     */
    private fun withPool(
        poolType: PoolType = PoolType.THREE_D,
        tasks: List<PoolTask>,
        body: suspend (PoolController) -> Unit,
    ) = runBlocking {
        pools.snapshots.value = PoolSnapshot(poolType, tasks)
        val controller = controllerFor(poolType)
        controller.show(pools.observePool(poolType).first())
        body(controller)
    }

    /** Every task the screen is showing, in the order it shows them. */
    private fun PoolController.everyTask(): List<PoolTask> {
        val model = assertIs<PoolContentState.Content>(state.content).model
        return when (model) {
            is PoolModel.Flat -> model.tasks
            is PoolModel.ThreeD ->
                model.sections.awaitingColor.tasks +
                    model.sections.singleColorGroups.flatMap { it.tasks } +
                    model.sections.multicolorGroups.flatMap { it.tasks }
        }
    }

    /** Every card the screen draws, which is not the same as every task. */
    private fun PoolController.everyCard(): List<PoolCardKey> {
        val model = assertIs<PoolContentState.Content>(state.content).model
        return when (model) {
            is PoolModel.Flat -> model.tasks.map { PoolCardKey(it.taskId) }
            is PoolModel.ThreeD ->
                model.sections.awaitingColor.tasks
                    .map { PoolCardKey(it.taskId) } +
                    model.sections.singleColorGroups.flatMap { group ->
                        group.tasks.map { PoolCardKey(it.taskId, group.color.colorId) }
                    } +
                    model.sections.multicolorGroups.flatMap { group ->
                        group.tasks.map { PoolCardKey(it.taskId, group.color.colorId) }
                    }
        }
    }

    // ------------------------------------------------------- one task, one menu

    @Test
    fun `every card of one task opens the same task`() =
        withPool(tasks = listOf(task(colors = listOf(red, yellow, black)))) { controller ->
            val taskId = controller.everyCard().first().taskId

            controller.everyCard().forEach { card ->
                controller.openTaskMenu(card)
                assertEquals(taskId, assertNotNull(controller.state.work).task.taskId)
                controller.closeInnermost()
            }
        }

    @Test
    fun `a card names the colour group it was pressed in`() =
        withPool(tasks = listOf(task(colors = listOf(red, yellow)))) { controller ->
            val cards = controller.everyCard()

            assertEquals(setOf(red.colorId, yellow.colorId), cards.mapNotNull { it.colorId }.toSet())
            assertEquals(1, cards.map { it.taskId }.toSet().size, "the cards are not all the same task")
        }

    @Test
    fun `the keyboard is owed the card that was pressed and not another of the same task`() =
        withPool(tasks = listOf(task(colors = listOf(red, yellow, black)))) { controller ->
            val second = controller.everyCard()[1]

            controller.openTaskMenu(second)

            assertEquals(second, controller.cardToFocus)
            controller.closeInnermost()
            assertEquals(second, controller.cardToFocus, "the keyboard was sent to a different card")
        }

    @Test
    fun `only one surface is open at a time`() =
        withPool(tasks = listOf(task(colors = listOf(red)), task(name = "Ağaç", colors = listOf(red)))) { controller ->
            val cards = controller.everyCard()
            controller.openTaskMenu(cards[0])

            controller.openTaskMenu(cards[1])

            assertEquals(cards[0].taskId, assertNotNull(controller.state.work).task.taskId)
        }

    // ------------------------------------------------------------- editing

    @Test
    fun `a change begun from a pool is written on the same task`() =
        withPool(tasks = listOf(task(name = "Yarasa", colors = listOf(red)))) { controller ->
            val card = controller.everyCard().single()
            controller.openTaskMenu(card)
            controller.beginTaskEdit()

            controller.editTaskName("Yarasa kanadı")
            controller.saveTaskEdit()

            assertEquals(1, editing.edits.size, "a pool wrote more than one thing")
            assertEquals(card.taskId, editing.edits.single().taskId, "the change landed on another task")
            assertEquals("Yarasa kanadı", editing.edits.single().name)
        }

    @Test
    fun `the editor opens on what the task already is`() =
        withPool(
            tasks = listOf(task(name = "Yarasa", quantity = 10, notes = "iki kat", colors = listOf(red, yellow))),
        ) { controller ->
            controller.openTaskMenu(controller.everyCard().first())
            controller.beginTaskEdit()

            val editor = assertIs<PoolWork.Editing>(controller.state.work).editor

            assertEquals("Yarasa", editor.name)
            assertEquals(listOf(red.colorId, yellow.colorId), editor.colorIds)
            assertEquals("10", editor.quantityText)
            assertEquals("iki kat", editor.notes)
            assertFalse(editor.hasChanges, "an untouched form already reads as changed")
        }

    @Test
    fun `the snapshot the editor opens on carries the cell and the piece`() =
        withPool(tasks = listOf(task(colors = listOf(red)))) { controller ->
            val task = controller.everyTask().single()

            val snapshot = assertNotNull(controller.editingSnapshotOf(task.taskId))

            assertEquals(task.taskId, snapshot.taskId)
            assertEquals(task.segmentId, snapshot.segmentId)
            assertEquals(task.cellId, snapshot.cellId)
            assertEquals(task.gameId, snapshot.gameId)
        }

    @Test
    fun `a several colour task keeps its whole ordered list`() =
        withPool(tasks = listOf(task(colors = listOf(red, yellow, black)))) { controller ->
            controller.openTaskMenu(controller.everyCard().first())
            controller.beginTaskEdit()

            controller.moveTaskEditColorUp(2)
            controller.saveTaskEdit()

            assertEquals(listOf(red.colorId, black.colorId, yellow.colorId), editing.edits.single().colorIds)
        }

    @Test
    fun `a single colour task has its one colour replaced rather than added to`() =
        withPool(tasks = listOf(task(colors = listOf(red)))) { controller ->
            controller.showColors(listOf(summaryOf(red), summaryOf(yellow)))
            controller.openTaskMenu(controller.everyCard().first())
            controller.beginTaskEdit()

            controller.chooseTaskEditColor(yellow.colorId)
            controller.saveTaskEdit()

            assertEquals(listOf(yellow.colorId), editing.edits.single().colorIds)
        }

    @Test
    fun `a refusal leaves the panel standing with what was typed`() =
        withPool(tasks = listOf(task(colors = listOf(red)))) { controller ->
            editing.refuseWith = TaskEditException(TaskEditFailure.QUANTITY_BELOW_PROGRESS)
            controller.openTaskMenu(controller.everyCard().first())
            controller.beginTaskEdit()
            controller.editTaskName("Yeni ad")

            controller.saveTaskEdit()

            val editor = assertIs<PoolWork.Editing>(controller.state.work).editor
            assertEquals("Yeni ad", editor.name, "what was typed was thrown away")
            assertEquals(TaskEditFailure.QUANTITY_BELOW_PROGRESS, editor.failure)
            assertFalse(editor.isSaving, "the panel was left stuck saving")
        }

    @Test
    fun `saving closes the panel and goes back to the menu`() =
        withPool(tasks = listOf(task(colors = listOf(red)))) { controller ->
            controller.openTaskMenu(controller.everyCard().first())
            controller.beginTaskEdit()
            controller.editTaskName("Başka")

            controller.saveTaskEdit()

            assertIs<PoolWork.Menu>(controller.state.work)
        }

    // ------------------------------------------------ turning back into text

    @Test
    fun `turning a task back into text happens on the same task`() =
        withPool(tasks = listOf(task(colors = listOf(red)))) { controller ->
            val card = controller.everyCard().single()
            controller.openTaskMenu(card)
            controller.beginConvertToText()

            controller.confirmConvertToText()

            assertEquals(listOf(card.taskId), editing.converted)
            assertNull(controller.state.work, "the question stayed open after it was answered")
        }

    @Test
    fun `the question says when there is a history to lose`() =
        withPool(tasks = listOf(task(colors = listOf(red)).copy(failureTotal = 2))) { controller ->
            controller.openTaskMenu(controller.everyCard().single())

            controller.beginConvertToText()

            assertTrue(assertIs<PoolWork.ConfirmingConvert>(controller.state.work).hasProgress)
        }

    @Test
    fun `a task nothing has happened to is not said to have a history`() =
        withPool(tasks = listOf(task(colors = listOf(red)))) { controller ->
            controller.openTaskMenu(controller.everyCard().single())

            controller.beginConvertToText()

            assertFalse(assertIs<PoolWork.ConfirmingConvert>(controller.state.work).hasProgress)
        }

    // -------------------------------------------------- what a new list does

    @Test
    fun `a new list does not close a panel with something typed in it`() =
        withPool(tasks = listOf(task(name = "Yarasa", colors = listOf(red)))) { controller ->
            val existing = controller.everyTask().single()
            controller.openTaskMenu(controller.everyCard().single())
            controller.beginTaskEdit()
            controller.editTaskName("yarım kalan ad")

            // Another task changes; this one is untouched.
            controller.show(
                PoolSnapshot(PoolType.THREE_D, listOf(existing, task(name = "Başka", colors = listOf(yellow)))),
            )

            assertEquals("yarım kalan ad", assertIs<PoolWork.Editing>(controller.state.work).editor.name)
        }

    @Test
    fun `a task renamed elsewhere is named its new name on the surface over it`() =
        withPool(tasks = listOf(task(name = "Yarasa", colors = listOf(red)))) { controller ->
            val existing = controller.everyTask().single()
            controller.openTaskMenu(controller.everyCard().first())

            controller.show(
                PoolSnapshot(PoolType.THREE_D, listOf(existing.copy(name = "Yarasa kanadı"))),
            )

            assertEquals("Yarasa kanadı", assertNotNull(controller.state.work).task.name)
        }

    @Test
    fun `a draft is not rewritten by the task changing underneath it`() =
        withPool(tasks = listOf(task(name = "Yarasa", colors = listOf(red)))) { controller ->
            val existing = controller.everyTask().single()
            controller.openTaskMenu(controller.everyCard().first())
            controller.beginTaskEdit()
            controller.editTaskName("kullanıcının yazdığı")

            controller.show(PoolSnapshot(PoolType.THREE_D, listOf(existing.copy(name = "başkasının yazdığı"))))

            val editing = assertIs<PoolWork.Editing>(controller.state.work)
            assertEquals("kullanıcının yazdığı", editing.editor.name, "a draft was overwritten from the database")
            assertEquals("başkasının yazdığı", editing.task.name, "the surface still names the old task")
        }

    @Test
    fun `a task that leaves the pool takes its panel with it`() =
        withPool(tasks = listOf(task(colors = listOf(red)))) { controller ->
            controller.openTaskMenu(controller.everyCard().single())
            controller.beginTaskEdit()

            controller.show(PoolSnapshot(PoolType.THREE_D, emptyList()))

            assertNull(controller.state.work, "a panel was left over a task that is not there")
        }

    @Test
    fun `a read that fails leaves what is open alone`() =
        withPool(tasks = listOf(task(colors = listOf(red)))) { controller ->
            controller.openTaskMenu(controller.everyCard().single())

            controller.show(PoolContentState.Failed)

            // A read saying nothing is not a read saying the task is gone.
            assertNotNull(controller.state.work)
            assertIs<PoolContentState.Failed>(controller.state.content)
        }

    // ------------------------------------------------------ the stage details

    @Test
    fun `stage details open and close without writing anything`() =
        withPool(
            poolType = PoolType.CARD,
            tasks =
                listOf(
                    task(
                        tracking = TrackingMode.PIPELINE,
                        stages = listOf(PoolStage(ProductionStage.PRINT, 2), PoolStage(ProductionStage.CUT, 0)),
                    ),
                ),
        ) { controller ->
            val id = controller.everyTask().single().taskId

            assertFalse(controller.isShowingStages(id))
            controller.toggleStageDetails(id)
            assertTrue(controller.isShowingStages(id))
            controller.toggleStageDetails(id)
            assertFalse(controller.isShowingStages(id))

            assertEquals(emptyList(), editing.edits, "expanding a badge wrote something")
            assertEquals(emptyList(), editing.converted)
        }

    @Test
    fun `one task's stage details are not another's`() =
        withPool(
            poolType = PoolType.BOARD,
            tasks =
                listOf(
                    task(name = "Bir", tracking = TrackingMode.PIPELINE),
                    task(name = "İki", tracking = TrackingMode.PIPELINE),
                ),
        ) { controller ->
            val tasks = controller.everyTask()

            controller.toggleStageDetails(tasks[0].taskId)

            assertTrue(controller.isShowingStages(tasks[0].taskId))
            assertFalse(controller.isShowingStages(tasks[1].taskId))
        }

    // ------------------------------------------------------------- closing

    @Test
    fun `closing unwinds one layer at a time`() =
        withPool(tasks = listOf(task(colors = listOf(red)))) { controller ->
            controller.openTaskMenu(controller.everyCard().single())
            controller.beginTaskEdit()

            controller.closeInnermost()
            assertIs<PoolWork.Menu>(controller.state.work, "closing the panel skipped past the menu")

            controller.closeInnermost()
            assertNull(controller.state.work)
        }

    @Test
    fun `closing does not write anything`() =
        withPool(tasks = listOf(task(colors = listOf(red)))) { controller ->
            controller.openTaskMenu(controller.everyCard().single())
            controller.beginTaskEdit()
            controller.editTaskName("başka")

            controller.closeInnermost()
            controller.closeInnermost()

            assertEquals(emptyList(), editing.edits)
        }

    private fun summaryOf(color: PoolColor) =
        ColorSummary(id = color.colorId, canonicalName = color.canonicalName, hex = color.hex, sortOrder = color.sortOrder)
}
