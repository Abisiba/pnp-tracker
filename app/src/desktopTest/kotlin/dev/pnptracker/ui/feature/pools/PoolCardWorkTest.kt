package dev.pnptracker.ui.feature.pools

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import dev.pnptracker.domain.colors.baseColors
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskProgressFailure
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.feature.games.CellWork
import dev.pnptracker.ui.feature.games.GameTableRowsState
import dev.pnptracker.ui.feature.games.GameTableScreen
import dev.pnptracker.ui.reads
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What can be done from a pool card, on the real screen through the real stack:
 * the missing count of a task set and changed, the card and board steps counted
 * as separate pieces, and Enter saving both.
 */
class PoolCardWorkTest {
    private fun ComposeSceneHarness.settle(
        what: String,
        done: () -> Boolean,
    ) {
        repeat(200) {
            render()
            if (done()) {
                repeat(2) { render() }
                return
            }
            Thread.sleep(10)
        }
        fail("never happened: $what")
    }

    /** One game with one task of [quantity] in [column], made the way the table makes it. */
    private fun makeTask(
        stack: RealStack,
        column: CellColumnType,
        name: String,
        quantity: Int,
        table: ComposeSceneHarness,
    ): Pair<EntityId, EntityId> {
        run {
            table.settle("the table is read") { stack.table.state.rows !is GameTableRowsState.Loading }
            if (stack.rows().none { it.gameName == "Harmonies" }) {
                runBlocking {
                    stack.table.startGameComposer()
                    stack.table.editGameName("Harmonies")
                    stack.table.saveGame()
                }
                table.settle("the game is shown") { stack.rows().any { it.gameName == "Harmonies" } }
            }
            val gameId = stack.gameNamed("Harmonies")
            stack.table.beginEditing(gameId, column)
            table.settle("the editor opens") { stack.table.state.work is CellWork.WritingText }
            stack.table.editCellText(name)
            runBlocking { stack.table.saveEditing() }
            table.settle("the words are stored") { stack.table.state.work == null }
            val cell = stack.rows().first { it.gameId == gameId }.cell(column)
            val selection = requireNotNull(cell.locateSelection(gameId, 0, name.length))
            val mode =
                when (column) {
                    CellColumnType.THREE_D -> TrackingMode.THREE_D_BATCH
                    CellColumnType.SPECIAL -> TrackingMode.COUNTED
                    else -> TrackingMode.PIPELINE
                }
            val taskId =
                runBlocking {
                    stack.taskCreation
                        .createTasks(
                            selection = selection,
                            drafts =
                                listOf(
                                    TaskDraft(
                                        colorIds = if (column == CellColumnType.THREE_D) listOf(baseColors.first().id) else emptyList(),
                                        requiredQuantity = quantity,
                                        trackingMode = mode,
                                        notes = null,
                                    ),
                                ),
                        ).single()
                }
            table.settle("$name is a task") { stack.piecesOf(gameId, column).any { it.taskId == taskId } }
            return gameId to taskId
        }
    }

    private fun ComposeSceneHarness.texts(): List<String> =
        nodes().flatMap { node ->
            node.reads(SemanticsProperties.Text).orEmpty().map {
                it.text
            }
        }

    private fun ComposeSceneHarness.boundsOfSpoken(prefix: String): Rect =
        nodes().firstOrNull { node -> node.contentDescriptions().any { it.startsWith(prefix) } }?.boundsInRoot
            ?: fail("nothing is said as `$prefix…`")

    private fun RealStack.missingOf(
        gameId: EntityId,
        column: CellColumnType,
        taskId: EntityId,
    ): Int = piecesOf(gameId, column).first { it.taskId == taskId }.currentMissingQuantity

    /** The game table, open beside the pool the way the user has it, so it follows every write. */
    private fun tableOf(stack: RealStack) = ComposeSceneHarness(width = 1600, height = 900) { GameTableScreen(stack.table) }

    private fun tasksIn(stack: RealStack) = runBlocking { stack.database.taskDao().allTasksIncludingDeleted() }

    // ------------------------------------------------------- the missing count

    @Test
    fun `the missing count is set and changed from the card in every pool, on the same task`() {
        RealStack().use { stack ->
            tableOf(stack).use { table ->
                val made =
                    listOf(
                        Triple(PoolType.THREE_D, CellColumnType.THREE_D, "Ejderha"),
                        Triple(PoolType.CARD, CellColumnType.CARD, "Deste"),
                        Triple(PoolType.BOARD, CellColumnType.BOARD, "Tahta"),
                        Triple(PoolType.SPECIAL, CellColumnType.SPECIAL, "Zar"),
                    ).map { (pool, column, name) -> Triple(pool, column, name) to makeTask(stack, column, name, quantity = 5, table) }
                val tasksBefore = tasksIn(stack).size

                made.forEach { (what, ids) ->
                    val (poolType, column, name) = what
                    val (gameId, taskId) = ids
                    val pool = stack.pools.of(poolType)
                    ComposeSceneHarness(width = 1600, height = 1000) { PoolScreen(pool) }.use { screen ->
                        screen.settle("$name is in the pool") { stack.cardsIn(poolType).any { it.first.taskId == taskId } }

                        // Up to three, with Enter.
                        assertTrue(screen.click("$name görevinin eksik adedini düzenle"), "the $poolType card offers no missing count")
                        screen.settle("the missing count opens") { pool.state.work is PoolWork.EditingMissing }
                        pool.editMissingDraft("3")
                        screen.render()
                        screen.press(Key.Enter)
                        table.settle("three are missing on $name, in the table too") { stack.missingOf(gameId, column, taskId) == 3 }
                        screen.settle("the card says so") { screen.texts().any { it == "3 eksik" } }
                        assertEquals(null, pool.state.work, "the panel stayed open after it saved")

                        // Down to one: the same task, changed.
                        assertTrue(screen.click("$name görevinin eksik adedini düzenle"))
                        screen.settle("the missing count opens again") { pool.state.work is PoolWork.EditingMissing }
                        assertEquals("3", (pool.state.work as PoolWork.EditingMissing).typed, "it did not open on what is owed now")
                        pool.editMissingDraft("1")
                        screen.render()
                        screen.press(Key.Enter)
                        table.settle("one is missing on $name, in the table too") { stack.missingOf(gameId, column, taskId) == 1 }
                        screen.settle("the card says so") { screen.texts().any { it == "1 eksik" } }
                    }
                }
                assertEquals(tasksBefore, tasksIn(stack).size, "a task was made rather than the existing one changed")
                // And the table's own Eksik column carries the same task.
                made.forEach { (what, ids) ->
                    val (_, column, _) = what
                    val (gameId, taskId) = ids
                    assertEquals(1, stack.missingOf(gameId, column, taskId))
                }
            }
        }
    }

    @Test
    fun `a missing count over the total or not a number is said and not saved, and Escape writes nothing`() {
        RealStack().use { stack ->
            tableOf(stack).use { table ->
                val (gameId, taskId) = makeTask(stack, CellColumnType.CARD, "Deste", quantity = 5, table)
                val pool = stack.pools.of(PoolType.CARD)
                ComposeSceneHarness(width = 1600, height = 1000) { PoolScreen(pool) }.use { screen ->
                    screen.settle("the task is in the pool") { stack.cardsIn(PoolType.CARD).any { it.first.taskId == taskId } }
                    assertTrue(screen.click("Deste görevinin eksik adedini düzenle"))
                    screen.settle("the missing count opens") { pool.state.work is PoolWork.EditingMissing }

                    pool.editMissingDraft("6")
                    screen.render()
                    screen.press(Key.Enter)
                    screen.render()
                    val open = pool.state.work as? PoolWork.EditingMissing ?: fail("the panel closed on a count it could not save")
                    assertEquals(MissingProblem.OVER_TOTAL, open.problem)
                    assertTrue(screen.texts().any { "geçemez" in it }, "the refusal is not said: ${screen.texts()}")
                    assertEquals(0, stack.missingOf(gameId, CellColumnType.CARD, taskId))

                    pool.editMissingDraft("2")
                    screen.render()
                    screen.press(Key.Escape)
                    screen.render()
                    assertEquals(null, pool.state.work, "Escape did not close the panel")
                    assertEquals(0, stack.missingOf(gameId, CellColumnType.CARD, taskId), "Escape wrote the count")
                }
            }
        }
    }

    @Test
    fun `the card still opens its menu, and a 3D card still finishes with one press`() {
        RealStack().use { stack ->
            tableOf(stack).use { table ->
                val (gameId, taskId) = makeTask(stack, CellColumnType.THREE_D, "Ejderha", quantity = 5, table)
                val pool = stack.pools.of(PoolType.THREE_D)
                ComposeSceneHarness(width = 1600, height = 1000) { PoolScreen(pool) }.use { screen ->
                    screen.settle("the task is in the pool") { stack.cardsIn(PoolType.THREE_D).any { it.first.taskId == taskId } }

                    val opens =
                        screen
                            .nodes()
                            .firstOrNull { node -> node.contentDescriptions().any { it.startsWith("Ejderha, Harmonies oyunu") } }
                            ?.reads(SemanticsActions.OnClick) ?: fail("the card can no longer be pressed")
                    opens.action?.invoke()
                    screen.render()
                    screen.settle("the menu opens") { pool.state.work is PoolWork.Menu }
                    pool.closeInnermost()
                    screen.render()

                    val finish = screen.boundsOf("Ejderha görevini tamamla") ?: fail("the one-press finish is gone")
                    screen.mouseClick(finish.center)
                    table.settle("the task is finished, in the table too") {
                        stack.piecesOf(gameId, CellColumnType.THREE_D).first { it.taskId == taskId }.isCompletedTask
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ the card itself

    @Test
    fun `in four columns the game name, the task and the card's controls are all inside the card`() {
        RealStack().use { stack ->
            tableOf(stack).use { table ->
                val (_, taskId) = makeTask(stack, CellColumnType.THREE_D, "Uzun adlı bir ejderha figürü", quantity = 12, table)
                val pool = stack.pools.of(PoolType.THREE_D)
                ComposeSceneHarness(width = 1280, height = 1000) { PoolScreen(pool) }.use { screen ->
                    screen.settle("the task is in the pool") { stack.cardsIn(PoolType.THREE_D).any { it.first.taskId == taskId } }
                    val card = screen.boundsOfSpoken("Uzun adlı bir ejderha figürü, Harmonies oyunu")
                    assertTrue(card.width < 1280f / 3, "the pool is not in four columns (${card.width})")

                    listOf(
                        screen.boundsOf("Uzun adlı bir ejderha figürü görevini tamamla") ?: fail("no finish"),
                        screen.boundsOf("Uzun adlı bir ejderha figürü görevinin eksik adedini düzenle") ?: fail("no missing count"),
                    ).forEach { control ->
                        assertTrue(control.width > 0f && control.height > 0f, "a control is squeezed to nothing: $control")
                        assertTrue(control.right <= 1280f + 1f, "a control runs off the window: $control")
                    }
                    assertTrue(screen.texts().any { it == "Harmonies" }, "the game name is not written on the card")
                }
            }
        }
    }

    // ------------------------------------------------------------------ the steps

    @Test
    fun `the steps are separate pieces, five of five cannot be counted three times, and Enter saves`() {
        RealStack().use { stack ->
            tableOf(stack).use { table ->
                val (gameId, taskId) = makeTask(stack, CellColumnType.CARD, "Deste", quantity = 5, table)
                val pool = stack.pools.of(PoolType.CARD)
                ComposeSceneHarness(width = 1600, height = 1000) { PoolScreen(pool) }.use { screen ->
                    screen.settle("the task is in the pool") { stack.cardsIn(PoolType.CARD).any { it.first.taskId == taskId } }
                    val card = stack.cardsIn(PoolType.CARD).first { it.first.taskId == taskId }.first
                    pool.toggleStageDetails(taskId)
                    pool.beginStageEdit(card)
                    screen.settle("the counters open") { pool.state.work is PoolWork.EditingStages }

                    listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT).forEach { pool.editStageDraft(it, "5") }
                    screen.render()
                    assertTrue(screen.texts().any { it == "Aşamalardaki parça: 15 / 5" }, "the sum is not shown: ${screen.texts()}")
                    assertTrue(screen.texts().any { "toplamı görevin adedini geçemez" in it }, "the warning is not shown")

                    // Enter in a box: refused, at the write as well as on the screen.
                    screen.press(Key.Enter)
                    screen.settle("the save comes back") { (pool.state.work as? PoolWork.EditingStages)?.isSaving == false }
                    val refused = pool.state.work as? PoolWork.EditingStages ?: fail("the panel closed on counters it could not save")
                    assertEquals(TaskProgressFailure.STAGES_EXCEED_REQUIRED, refused.failure)
                    assertEquals(
                        listOf(0, 0, 0),
                        runBlocking { stack.database.taskProgressDao().stagesOfTask(taskId) }.map { it.completedQuantity },
                    )

                    // Five pieces spread over the steps, and plain Enter saves them.
                    pool.editStageDraft(ProductionStage.PRINT, "2")
                    pool.editStageDraft(ProductionStage.LAMINATE, "2")
                    pool.editStageDraft(ProductionStage.CUT, "1")
                    screen.render()
                    screen.press(Key.Enter)
                    screen.settle("the counters are saved") { pool.state.work == null }
                    assertEquals(
                        listOf(2, 2, 1),
                        runBlocking { stack.database.taskProgressDao().stagesOfTask(taskId) }.map { it.completedQuantity },
                    )
                    assertFalse(stack.piecesOf(gameId, CellColumnType.CARD).first { it.taskId == taskId }.isCompletedTask)
                }
            }
        }
    }

    @Test
    fun `the counters are on every card and board card, finished or not, and can be changed from the card or its menu`() {
        RealStack().use { stack ->
            tableOf(stack).use { table ->
                listOf(
                    Triple(PoolType.CARD, CellColumnType.CARD, listOf("Basıldı", "Lamine edildi", "Kesildi")),
                    Triple(PoolType.BOARD, CellColumnType.BOARD, listOf("Basıldı", "Yapıştırıldı", "Kesildi")),
                ).forEach { (poolType, column, steps) ->
                    val (_, taskId) = makeTask(stack, column, "Parça", quantity = 5, table)
                    val pool = stack.pools.of(poolType)
                    ComposeSceneHarness(width = 1600, height = 1000) { PoolScreen(pool) }.use { screen ->
                        screen.settle("the task is in the pool") { stack.cardsIn(poolType).any { it.first.taskId == taskId } }

                        // Nothing unfolded: every counter and the way to change them are there.
                        steps.forEach { step ->
                            assertTrue(step + ": 0 / 5" in screen.texts(), "$poolType: `$step` is not on the card: ${screen.texts()}")
                        }
                        assertTrue("Aşamaları düzenle" in screen.texts(), "$poolType: the counters cannot be changed from the card")

                        // Finished: shown under Tamamlandı with the counters it finished at.
                        runBlocking {
                            stack.taskProgress.completeTask(
                                taskId,
                                dev.pnptracker.domain.model.IdGenerator.Random
                                    .newId(),
                            )
                        }
                        pool.showState(dev.pnptracker.domain.search.TaskStateFilter.COMPLETED)
                        screen.settle("the finished task is shown") {
                            stack.cardsIn(poolType).any { it.first.taskId == taskId && it.second.isCompleted }
                        }
                        assertTrue(
                            steps.last() + ": 5 / 5" in screen.texts(),
                            "$poolType: a finished task's counters are not shown: ${screen.texts()}",
                        )
                        assertTrue("Aşamaları düzenle" in screen.texts(), "$poolType: a finished task's counters cannot be changed")

                        // From the card's own menu as well.
                        val card = stack.cardsIn(poolType).first { it.first.taskId == taskId }.first
                        pool.openTaskMenu(card)
                        screen.render()
                        assertTrue(screen.click("Aşama sayaçları"), "$poolType: the task's menu has no way to the counters")
                        screen.settle("the counters open") { pool.state.work is PoolWork.EditingStages }
                        val open = pool.state.work as PoolWork.EditingStages
                        assertEquals(steps.size, open.draft.size)
                        assertEquals("5", open.draft[open.steps.last()], "$poolType: the counters did not open on what is stored")
                        // The sum rule still holds when changing a finished task's counters.
                        open.steps.forEach { pool.editStageDraft(it, "5") }
                        screen.render()
                        screen.press(Key.Enter)
                        screen.settle("the save comes back") { (pool.state.work as? PoolWork.EditingStages)?.isSaving == false }
                        assertEquals(
                            TaskProgressFailure.STAGES_EXCEED_REQUIRED,
                            (pool.state.work as PoolWork.EditingStages).failure,
                        )
                        pool.closeInnermost()
                        screen.render()
                    }
                }
            }
        }
    }

    @Test
    fun `counters saved under the old reading are left alone until the user fixes them`() {
        RealStack().use { stack ->
            tableOf(stack).use { table ->
                val (_, taskId) = makeTask(stack, CellColumnType.CARD, "Deste", quantity = 5, table)
                // 3 + 3 + 1 was a valid picture before each counter meant its own
                // pieces; it is seven pieces for a task of five now.
                runBlocking {
                    stack.database.useWriterConnection { connection ->
                        connection.executeSQL(
                            "UPDATE task_stages SET completed_quantity = 3 WHERE task_id = '$taskId' AND order_index IN (0, 1)",
                        )
                        connection.executeSQL("UPDATE task_stages SET completed_quantity = 1 WHERE task_id = '$taskId' AND order_index = 2")
                    }
                }
                val pool = stack.pools.of(PoolType.CARD)
                ComposeSceneHarness(width = 1600, height = 1000) { PoolScreen(pool) }.use { screen ->
                    screen.settle("the task is in the pool") {
                        stack.cardsIn(PoolType.CARD).any {
                            it.first.taskId == taskId &&
                                it.second.stages.any { stage -> stage.completedQuantity == 3 }
                        }
                    }
                    val card = stack.cardsIn(PoolType.CARD).first { it.first.taskId == taskId }.first
                    pool.toggleStageDetails(taskId)
                    pool.beginStageEdit(card)
                    screen.settle("the counters open") { pool.state.work is PoolWork.EditingStages }
                    val open = assertNotNull(pool.state.work as? PoolWork.EditingStages)
                    assertTrue(open.isOverTotal, "the old counters were not said to be too many")
                    assertEquals(
                        listOf(3, 3, 1),
                        runBlocking { stack.database.taskProgressDao().stagesOfTask(taskId) }.map { it.completedQuantity },
                        "opening the counters changed them",
                    )

                    pool.editStageDraft(ProductionStage.PRINT, "1")
                    screen.render()
                    screen.press(Key.Enter)
                    screen.settle("the fixed counters are saved") { pool.state.work == null }
                    assertEquals(
                        listOf(1, 3, 1),
                        runBlocking { stack.database.taskProgressDao().stagesOfTask(taskId) }.map { it.completedQuantity },
                    )
                }
            }
        }
    }
}
