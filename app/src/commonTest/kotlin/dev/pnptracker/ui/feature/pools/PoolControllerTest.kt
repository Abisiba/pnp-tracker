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
import dev.pnptracker.domain.pools.PoolModel
import dev.pnptracker.domain.pools.PoolNavigationSummary
import dev.pnptracker.domain.pools.PoolSnapshot
import dev.pnptracker.domain.pools.PoolStage
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.domain.tasks.StageSnapshot
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskEditFailure
import dev.pnptracker.domain.tasks.TaskFlags
import dev.pnptracker.domain.tasks.TaskProgressFailure
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
        val flags: TaskFlags? = null,
    )

    override suspend fun editTask(
        taskId: EntityId,
        name: String,
        colorIds: List<EntityId>,
        requiredQuantity: Int?,
        notes: String?,
        trackingMode: TrackingMode,
        flags: TaskFlags?,
    ): Boolean {
        refuseWith?.let { throw it }
        edits += EditRecord(taskId, name, colorIds, requiredQuantity, notes, trackingMode, flags)
        return true
    }

    override suspend fun convertTaskToText(taskId: EntityId): Boolean {
        refuseWith?.let { throw it }
        converted += taskId
        return true
    }
}

/** Records what the pipeline panel asked of the database; nothing reaches one. */
private class FakeProgress(
    var outcome: TaskProgressOutcome = TaskProgressOutcome.Done,
) : TaskProgressing {
    val staged = mutableListOf<StagedSave>()

    /** Run once, in the middle of the next save: where a second press lands. */
    var whileSaving: (suspend () -> Unit)? = null

    override suspend fun completeTask(
        taskId: EntityId,
        eventId: EntityId,
    ): TaskProgressOutcome = TaskProgressOutcome.Done

    override suspend fun reopenTask(taskId: EntityId): TaskProgressOutcome = TaskProgressOutcome.Done

    override suspend fun gameCompletion(gameId: EntityId): GameCompletionSnapshot? = null

    override suspend fun completeGame(
        gameId: EntityId,
        expected: GameCompletionSnapshot?,
    ): TaskProgressOutcome = TaskProgressOutcome.Done

    override suspend fun reportFailure(
        eventId: EntityId,
        taskId: EntityId,
        quantity: Int,
        note: String?,
        cardReference: String?,
        stage: ProductionStage?,
    ): TaskProgressOutcome = TaskProgressOutcome.Done

    override suspend fun resolveShortage(
        eventId: EntityId,
        taskId: EntityId,
        quantity: Int,
        note: String?,
        cardReference: String?,
    ): TaskProgressOutcome = TaskProgressOutcome.Done

    override suspend fun setStageQuantities(
        taskId: EntityId,
        targets: Map<ProductionStage, Int>,
        expected: StageSnapshot?,
    ): TaskProgressOutcome {
        staged += StagedSave(taskId, targets, expected)
        whileSaving?.let {
            whileSaving = null
            it()
        }
        return outcome
    }
}

private data class StagedSave(
    val taskId: EntityId,
    val targets: Map<ProductionStage, Int>,
    val expected: StageSnapshot?,
)

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
    private val progress = FakeProgress()

    private val red = PoolColor(IdGenerator.Random.newId(), "Kırmızı", "#E53935", sortOrder = 4)
    private val yellow = PoolColor(IdGenerator.Random.newId(), "Sarı", "#FDD835", sortOrder = 5)
    private val black = PoolColor(IdGenerator.Random.newId(), "Siyah", "#111111", sortOrder = 1)

    private fun controllerFor(poolType: PoolType = PoolType.THREE_D) =
        PoolController(
            poolType = poolType,
            pools = pools,
            colors = colors,
            taskEditing = editing,
            taskProgress = progress,
        )

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

    // ------------------------------------------------------- the pipeline panel

    private val cardPipeline =
        listOf(
            PoolStage(ProductionStage.PRINT, 15),
            PoolStage(ProductionStage.LAMINATE, 10),
            PoolStage(ProductionStage.CUT, 5),
        )

    /** The three steps of a card pipeline, in the order they are worked in. */
    private fun at(
        print: Int,
        laminate: Int,
        cut: Int,
    ) = mapOf(ProductionStage.PRINT to print, ProductionStage.LAMINATE to laminate, ProductionStage.CUT to cut)

    private fun cardTask(
        quantity: Int? = CARD_TOTAL,
        stages: List<PoolStage> = cardPipeline,
    ) = task(name = "Bird Cards", quantity = quantity, stages = stages, tracking = TrackingMode.PIPELINE)

    private fun withCardPool(
        task: PoolTask = cardTask(),
        body: suspend (PoolController, PoolCardKey) -> Unit,
    ) = withPool(poolType = PoolType.CARD, tasks = listOf(task)) { controller ->
        body(controller, controller.everyCard().first())
    }

    private fun stagePanel(controller: PoolController): PoolWork.EditingStages = assertIs(controller.state.work)

    @Test
    fun `opening the pipeline keeps the counts it was opened on`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)

            val open = stagePanel(controller)
            assertEquals(StageSnapshot(CARD_TOTAL, at(15, 10, 5)), open.expected)
            assertEquals(mapOf(ProductionStage.PRINT to "15", ProductionStage.LAMINATE to "10", ProductionStage.CUT to "5"), open.draft)
            assertEquals(listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT), open.steps)
            assertTrue(controller.isShowingStages(card.taskId), "opening the panel folded the card away")
        }

    @Test
    fun `a task with no total has no pipeline to open`() =
        withCardPool(task = cardTask(quantity = null)) { controller, card ->
            controller.beginStageEdit(card)

            assertNull(controller.state.work, "a pipeline opened with nothing to count up to")
        }

    @Test
    fun `only one pipeline may be open at a time`() =
        withPool(poolType = PoolType.CARD, tasks = listOf(cardTask(), cardTask())) { controller ->
            val cards = controller.everyCard()
            controller.beginStageEdit(cards[0])

            controller.beginStageEdit(cards[1])

            assertEquals(cards[0].taskId, stagePanel(controller).task.taskId, "a second panel took over the first")
        }

    @Test
    fun `the steps hold digits and nothing else`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)

            controller.editStageDraft(ProductionStage.PRINT, "1a2 x")

            assertEquals("12", stagePanel(controller).draft[ProductionStage.PRINT])
        }

    @Test
    fun `the arrows move the draft and nothing else`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)

            controller.stepStageDraft(ProductionStage.CUT, 1)
            controller.stepStageDraft(ProductionStage.CUT, 1)
            controller.stepStageDraft(ProductionStage.LAMINATE, -1)

            assertEquals("7", stagePanel(controller).draft[ProductionStage.CUT])
            assertEquals("9", stagePanel(controller).draft[ProductionStage.LAMINATE])
            assertTrue(progress.staged.isEmpty(), "an arrow wrote straight to the database")
        }

    // ------------------------------------------------ what a box will take

    @Test
    fun `a count is not cut short at nine digits`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            // Ten digits, because the largest count there is has ten. A box that
            // stopped at nine would refuse a number the task's own total is
            // allowed to be, and would refuse it by quietly dropping a digit.
            controller.editStageDraft(ProductionStage.PRINT, Int.MAX_VALUE.toString())

            assertEquals(Int.MAX_VALUE.toString(), stagePanel(controller).draft[ProductionStage.PRINT])
        }

    @Test
    fun `the largest count there is goes through when the task counts that high`() =
        withCardPool(
            task =
                cardTask(
                    quantity = Int.MAX_VALUE,
                    stages =
                        listOf(
                            PoolStage(ProductionStage.PRINT, 0),
                            PoolStage(ProductionStage.LAMINATE, 0),
                            PoolStage(ProductionStage.CUT, 0),
                        ),
                ),
        ) { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.PRINT, Int.MAX_VALUE.toString())

            controller.saveStages()

            assertEquals(Int.MAX_VALUE, progress.staged.single().targets[ProductionStage.PRINT])
        }

    @Test
    fun `a count too large to be one is refused where it was typed rather than trimmed`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            val tooLarge =
                Int.MAX_VALUE
                    .toLong()
                    .plus(1)
                    .toString()
            controller.editStageDraft(ProductionStage.PRINT, tooLarge)

            assertEquals(tooLarge, stagePanel(controller).draft[ProductionStage.PRINT], "what was typed was trimmed")
            assertTrue(stagePanel(controller).isUnusable(ProductionStage.PRINT), "the box did not say it will not do")

            controller.saveStages()

            val open = stagePanel(controller)
            assertTrue(progress.staged.isEmpty(), "a number that is not a count reached the database")
            assertEquals(TaskProgressFailure.INVALID_QUANTITY, open.failure)
            assertEquals(ProductionStage.PRINT, open.invalidStage, "the keyboard was not sent to the box at fault")
        }

    @Test
    fun `a very long run of digits is refused rather than thrown over`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            val absurd = "9".repeat(400)

            // Parsing this must not throw: it is a thing a user can hold a key
            // down and produce, and it is the panel's job to say no to it.
            controller.editStageDraft(ProductionStage.PRINT, absurd)
            controller.saveStages()

            assertEquals(absurd, stagePanel(controller).draft[ProductionStage.PRINT])
            assertEquals(TaskProgressFailure.INVALID_QUANTITY, stagePanel(controller).failure)
            assertTrue(progress.staged.isEmpty())
        }

    @Test
    fun `zeros written in front of a count neither lose it nor change it`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.PRINT, "0015")
            controller.editStageDraft(ProductionStage.LAMINATE, "0000")
            controller.editStageDraft(ProductionStage.CUT, "0")

            assertFalse(stagePanel(controller).isUnusable(ProductionStage.PRINT))
            controller.saveStages()

            assertEquals(at(15, 0, 0), progress.staged.single().targets)
        }

    @Test
    fun `nothing typed at all is not a count`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.LAMINATE, "")

            controller.saveStages()

            assertTrue(progress.staged.isEmpty())
            assertEquals(ProductionStage.LAMINATE, stagePanel(controller).invalidStage)
        }

    // ------------------------------------------------- two presses, one save

    @Test
    fun `pressing save twice in a row runs one save`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.CUT, "4")
            // The second press lands while the first is still with the database,
            // which is where a real second click lands.
            progress.whileSaving = { controller.saveStages() }

            controller.saveStages()

            assertEquals(1, progress.staged.size, "the same change was sent twice")
        }

    @Test
    fun `a list arriving mid-save does not let a second press through`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.CUT, "4")
            progress.whileSaving = {
                // A row arriving is not a reason to think the save has finished.
                controller.show(pools.observePool(PoolType.CARD).first())
                controller.saveStages()
            }

            controller.saveStages()

            assertEquals(1, progress.staged.size, "a row arriving mid-save let a second one through")
        }

    @Test
    fun `a total changing mid-save does not change what the save was checked against`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.CUT, "4")
            progress.whileSaving = {
                pools.snapshots.value =
                    PoolSnapshot(PoolType.CARD, listOf(cardTask(quantity = 30).copy(taskId = card.taskId)))
                controller.show(pools.observePool(PoolType.CARD).first())
            }

            controller.saveStages()

            // What went to the database is the picture the user described, total
            // and all. The database compares it with what is really there and
            // refuses it; the panel does not quietly re-aim at the new total.
            assertEquals(StageSnapshot(CARD_TOTAL, at(15, 10, 5)), progress.staged.single().expected)
        }

    @Test
    fun `a refused save can be sent again once`() =
        withCardPool { controller, card ->
            progress.outcome = TaskProgressOutcome.Refused(TaskProgressFailure.STALE_STAGE_PROGRESS)
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.CUT, "4")

            controller.saveStages()
            assertFalse(stagePanel(controller).isSaving, "a refusal left the panel sending for ever")
            controller.saveStages()

            assertEquals(2, progress.staged.size, "the panel would not try again after a refusal")
            assertEquals("4", stagePanel(controller).draft[ProductionStage.CUT], "the refusal threw the draft away")
        }

    @Test
    fun `a stale panel closed and opened again is checked against what is there now`() =
        withCardPool { controller, card ->
            progress.outcome = TaskProgressOutcome.Refused(TaskProgressFailure.STALE_STAGE_PROGRESS)
            controller.beginStageEdit(card)
            controller.saveStages()
            assertEquals(TaskProgressFailure.STALE_STAGE_PROGRESS, stagePanel(controller).failure)

            controller.closeInnermost()
            // What was really there all along, arriving the way a row does.
            pools.snapshots.value =
                PoolSnapshot(
                    PoolType.CARD,
                    listOf(
                        cardTask(
                            stages =
                                listOf(
                                    PoolStage(ProductionStage.PRINT, 18),
                                    PoolStage(ProductionStage.LAMINATE, 12),
                                    PoolStage(ProductionStage.CUT, 7),
                                ),
                        ).copy(taskId = card.taskId),
                    ),
                )
            controller.show(pools.observePool(PoolType.CARD).first())
            progress.outcome = TaskProgressOutcome.Done
            controller.beginStageEdit(card)

            val reopened = stagePanel(controller)
            assertEquals(StageSnapshot(CARD_TOTAL, at(18, 12, 7)), reopened.expected, "the panel opened on the old picture")
            assertEquals("18", reopened.draft[ProductionStage.PRINT])
            assertNull(reopened.failure, "the old refusal came back with the new panel")
            assertNull(reopened.invalidStage)
        }

    @Test
    fun `a task that leaves the pool mid-save closes the panel and is not written twice`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.CUT, "4")
            progress.whileSaving = {
                pools.snapshots.value = PoolSnapshot(PoolType.CARD, emptyList())
                controller.show(pools.observePool(PoolType.CARD).first())
            }

            controller.saveStages()

            assertNull(controller.state.work, "the panel stayed open over a task that had gone")
            assertEquals(1, progress.staged.size)
        }

    // -------------------------------------------- what an expansion is about

    @Test
    fun `a card that leaves the pool takes its expansion with it`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            assertTrue(controller.isShowingStages(card.taskId))

            // Finished from the panel: the pool no longer has it.
            pools.snapshots.value = PoolSnapshot(PoolType.CARD, emptyList())
            controller.show(pools.observePool(PoolType.CARD).first())

            assertFalse(controller.isShowingStages(card.taskId), "the card kept an expansion with nothing to expand")
            assertNull(controller.state.work)
        }

    @Test
    fun `a card reopened from elsewhere comes back folded away`() =
        withCardPool { controller, card ->
            controller.toggleStageDetails(card.taskId)
            pools.snapshots.value = PoolSnapshot(PoolType.CARD, emptyList())
            controller.show(pools.observePool(PoolType.CARD).first())

            // Reopened from the table, and back in the pool. The user asked for
            // it to be worked on again, not for its counters to be shown.
            pools.snapshots.value = PoolSnapshot(PoolType.CARD, listOf(cardTask().copy(taskId = card.taskId)))
            controller.show(pools.observePool(PoolType.CARD).first())

            assertFalse(controller.isShowingStages(card.taskId), "the card came back already open")
        }

    @Test
    fun `one card leaving leaves every other card as it was`() =
        runBlocking {
            val staying = cardTask()
            val going = cardTask()
            pools.snapshots.value = PoolSnapshot(PoolType.CARD, listOf(staying, going))
            val controller = controllerFor(PoolType.CARD)
            controller.show(pools.observePool(PoolType.CARD).first())
            controller.toggleStageDetails(staying.taskId)
            controller.toggleStageDetails(going.taskId)

            pools.snapshots.value = PoolSnapshot(PoolType.CARD, listOf(staying))
            controller.show(pools.observePool(PoolType.CARD).first())

            assertTrue(controller.isShowingStages(staying.taskId), "a card that never moved was folded away")
            assertFalse(controller.isShowingStages(going.taskId))
        }

    @Test
    fun `a read that failed folds nothing away`() =
        withCardPool { controller, card ->
            controller.toggleStageDetails(card.taskId)

            controller.show(PoolContentState.Failed)

            // A read that failed says nothing about which tasks there are, so it
            // may not take an expansion away on the strength of it.
            assertTrue(controller.isShowingStages(card.taskId), "a failed read folded the card away")
        }

    // -------------------------------------- a task with nothing to count up to

    @Test
    fun `a task with no total is sent to the form and comes back with a panel`() =
        withCardPool(task = cardTask(quantity = null)) { controller, card ->
            // There is nothing for a step to count up to, so there is no panel
            // to open — only the form that gives the task a total.
            controller.beginStageEdit(card)
            assertNull(controller.state.work, "a panel opened over a task with nothing to count up to")

            controller.beginTaskEditFor(card)
            assertIs<PoolWork.Editing>(controller.state.work)
            assertFalse(controller.isShowingStages(card.taskId), "the form opened the counters as well")

            // Out of the form and out of the menu behind it, the way closing it
            // for good goes.
            while (controller.state.work != null) controller.closeInnermost()
            // The total arriving the way a saved edit does.
            pools.snapshots.value =
                PoolSnapshot(
                    PoolType.CARD,
                    listOf(
                        cardTask(
                            quantity = 30,
                            stages =
                                listOf(
                                    PoolStage(ProductionStage.PRINT, 0),
                                    PoolStage(ProductionStage.LAMINATE, 0),
                                    PoolStage(ProductionStage.CUT, 0),
                                ),
                        ).copy(taskId = card.taskId),
                    ),
                )
            controller.show(pools.observePool(PoolType.CARD).first())
            controller.beginStageEdit(card)

            val open = stagePanel(controller)
            assertEquals(30, open.total, "the panel opened on something other than the total just given")
            assertEquals(mapOf(ProductionStage.PRINT to "0", ProductionStage.LAMINATE to "0", ProductionStage.CUT to "0"), open.draft)
            assertTrue(controller.stageStepAllowed(ProductionStage.PRINT, 1))
        }

    @Test
    fun `an arrow keeps working while the draft is out of order`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            // The print run behind the lamination: the state PLAN 7.2 will not
            // have saved, and the one a user typing towards 10 or 20 for all
            // three has to pass through.
            controller.editStageDraft(ProductionStage.PRINT, "8")

            val open = stagePanel(controller)
            assertTrue(open.isOutOfOrder, "a print run behind the lamination was not noticed")
            // Both arrows still move it. Asking the ordering rule here took both
            // of them away — every single step left the picture just as wrong —
            // and left a user working by keyboard with nowhere to go.
            assertTrue(controller.stageStepAllowed(ProductionStage.PRINT, 1), "the way out was taken away")
            assertTrue(controller.stageStepAllowed(ProductionStage.PRINT, -1), "the way back was taken away")
        }

    @Test
    fun `the keyboard alone climbs out of an order the pipeline may not be saved in`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.PRINT, "8")

            // Nothing but the arrow, the way a user with no mouse would do it.
            repeat(2) {
                assertTrue(controller.stageStepAllowed(ProductionStage.PRINT, 1))
                controller.stepStageDraft(ProductionStage.PRINT, 1)
            }

            val open = stagePanel(controller)
            assertEquals("10", open.draft[ProductionStage.PRINT])
            assertFalse(open.isOutOfOrder, "the arrows could not reach a pipeline that may be saved")

            controller.saveStages()
            assertEquals(at(10, 10, 5), progress.staged.single().targets)
        }

    @Test
    fun `an out of order draft is said to be so before it is ever sent`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            assertFalse(stagePanel(controller).isOutOfOrder)

            controller.editStageDraft(ProductionStage.CUT, "11")

            assertTrue(stagePanel(controller).isOutOfOrder, "the cut passing the lamination went unremarked")
            assertTrue(progress.staged.isEmpty(), "noticing it wrote to the database")
        }

    @Test
    fun `an arrow stops at nothing and at the total`() =
        withCardPool(
            task =
                cardTask(
                    stages =
                        listOf(
                            PoolStage(ProductionStage.PRINT, 0),
                            PoolStage(ProductionStage.LAMINATE, 0),
                            PoolStage(ProductionStage.CUT, 0),
                        ),
                ),
        ) { controller, card ->
            controller.beginStageEdit(card)

            assertFalse(controller.stageStepAllowed(ProductionStage.CUT, -1), "a step was offered below nothing")
            controller.editStageDraft(ProductionStage.PRINT, "20")
            assertFalse(controller.stageStepAllowed(ProductionStage.PRINT, 1), "a step was offered past the total")
        }

    @Test
    fun `the whole pipeline is sent as one, with the counts it was opened on`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.CUT, "9")

            controller.saveStages()

            val sent = progress.staged.single()
            assertEquals(
                mapOf(ProductionStage.PRINT to 15, ProductionStage.LAMINATE to 10, ProductionStage.CUT to 9),
                sent.targets,
                "the save did not carry the whole pipeline",
            )
            assertEquals(
                StageSnapshot(CARD_TOTAL, at(15, 10, 5)),
                sent.expected,
                "the save did not carry the counts and the total it was opened on",
            )
            assertNull(controller.state.work, "the panel stayed open after it landed")
        }

    @Test
    fun `a step left empty never reaches the database`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.LAMINATE, "")

            controller.saveStages()

            assertTrue(progress.staged.isEmpty(), "a pipeline with a blank step was sent")
            val open = stagePanel(controller)
            assertEquals(TaskProgressFailure.INVALID_QUANTITY, open.failure)
            assertEquals(ProductionStage.LAMINATE, open.invalidStage, "the keyboard was not sent to the empty step")
        }

    @Test
    fun `a refused save keeps the panel, the draft and the counts it was opened on`() =
        withCardPool { controller, card ->
            progress.outcome = TaskProgressOutcome.Refused(TaskProgressFailure.STAGE_ORDER_VIOLATED)
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.PRINT, "8")

            controller.saveStages()

            val open = stagePanel(controller)
            assertEquals("8", open.draft[ProductionStage.PRINT], "the refusal threw away what had been typed")
            assertEquals(StageSnapshot(CARD_TOTAL, at(15, 10, 5)), open.expected)
            assertEquals(TaskProgressFailure.STAGE_ORDER_VIOLATED, open.failure)
            assertFalse(open.isSaving)
        }

    @Test
    fun `a refusal names the step it is about`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            progress.outcome = TaskProgressOutcome.Refused(TaskProgressFailure.STAGE_ORDER_VIOLATED)
            controller.editStageDraft(ProductionStage.PRINT, "8")
            controller.saveStages()
            assertEquals(
                ProductionStage.LAMINATE,
                stagePanel(controller).invalidStage,
                "the step that passed the one before it was not named",
            )

            progress.outcome = TaskProgressOutcome.Refused(TaskProgressFailure.STAGE_QUANTITY_EXCEEDS_REQUIRED)
            controller.editStageDraft(ProductionStage.PRINT, "25")
            controller.saveStages()
            assertEquals(ProductionStage.PRINT, stagePanel(controller).invalidStage)

            // A pipeline that moved underneath the panel is about all of them at
            // once, so no box is singled out.
            progress.outcome = TaskProgressOutcome.Refused(TaskProgressFailure.STALE_STAGE_PROGRESS)
            controller.editStageDraft(ProductionStage.PRINT, "16")
            controller.saveStages()
            assertNull(stagePanel(controller).invalidStage, "a stale pipeline blamed one step")
            assertEquals(TaskProgressFailure.STALE_STAGE_PROGRESS, stagePanel(controller).failure)
        }

    @Test
    fun `a list arriving from the database leaves the draft and its counts alone`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.CUT, "9")

            pools.snapshots.value =
                PoolSnapshot(
                    PoolType.CARD,
                    listOf(
                        cardTask(
                            stages =
                                listOf(
                                    PoolStage(ProductionStage.PRINT, 18),
                                    PoolStage(ProductionStage.LAMINATE, 12),
                                    PoolStage(ProductionStage.CUT, 7),
                                ),
                        ).copy(taskId = card.taskId),
                    ),
                )
            controller.show(pools.observePool(PoolType.CARD).first())

            val open = stagePanel(controller)
            assertEquals("9", open.draft[ProductionStage.CUT], "the row that arrived rewrote the draft")
            assertEquals(
                StageSnapshot(CARD_TOTAL, at(15, 10, 5)),
                open.expected,
                "the row that arrived rewrote what the save is checked against",
            )
        }

    @Test
    fun `a task that leaves the pool takes its pipeline panel with it`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)

            pools.snapshots.value = PoolSnapshot(PoolType.CARD, emptyList())
            controller.show(pools.observePool(PoolType.CARD).first())

            assertNull(controller.state.work, "a panel was left over a task that has gone")
        }

    @Test
    fun `two saves racing out of one panel are one save`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.CUT, "9")
            progress.whileSaving = { controller.saveStages() }

            controller.saveStages()

            assertEquals(1, progress.staged.size, "one panel sent its pipeline twice")
        }

    @Test
    fun `closing the pipeline panel goes back to the card it was opened on`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            controller.editStageDraft(ProductionStage.CUT, "9")

            controller.closeInnermost()

            assertNull(controller.state.work)
            assertTrue(controller.isShowingStages(card.taskId), "closing the panel folded the card away too")
            assertTrue(progress.staged.isEmpty(), "closing the panel wrote what was in it")
        }

    @Test
    fun `an open pipeline panel says it is holding something unsaved`() =
        withCardPool { controller, card ->
            controller.beginStageEdit(card)
            assertFalse(assertIs<PoolWork.EditingStages>(controller.state.work).hasUnsavedChanges)

            controller.editStageDraft(ProductionStage.CUT, "9")

            assertTrue(assertIs<PoolWork.EditingStages>(controller.state.work).hasUnsavedChanges)
        }

    @Test
    fun `a task with no total is sent to the form that asks for one`() =
        withCardPool(task = cardTask(quantity = null)) { controller, card ->
            controller.beginTaskEditFor(card)

            val editing = assertIs<PoolWork.Editing>(controller.state.work)
            assertEquals(card.taskId, editing.task.taskId)
            assertTrue(progress.staged.isEmpty(), "asking for a total wrote a pipeline")
        }

    @Test
    fun `a restore lets go of everything open, because the tasks underneath have been replaced`() =
        runBlocking<Unit> {
            val controller = controllerFor()
            controller.openFilters()
            assertEquals(PoolFilterSurface.OPEN, controller.state.filterSurface)

            controller.abandonOpenWork()

            assertEquals(PoolFilterSurface.CLOSED, controller.state.filterSurface)
            assertNull(controller.state.work)
            assertEquals(emptySet(), controller.state.expandedStages)
        }
}

/** What a card pipeline in these fixtures counts up to, unless one says otherwise. */
private const val CARD_TOTAL = 20
