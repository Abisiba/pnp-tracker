package dev.pnptracker.ui.feature.pools

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.search.TaskStateFilter
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.feature.games.CellWork
import dev.pnptracker.ui.feature.games.GameTableRowsState
import dev.pnptracker.ui.feature.games.GameTableScreen
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * One 3D task finished with one press on its card (PLAN 12.10).
 *
 * The real table and the real 3D pool over one database: the task is made in
 * the table, finished from the pool, and looked for again in the table's
 * `Eksik` column — and then taken back the way that already existed.
 */
class PoolQuickFinishTest {
    private fun ComposeSceneHarness.settle(
        what: String,
        done: () -> Boolean,
    ) {
        repeat(200) {
            render()
            if (done()) return
            Thread.sleep(10)
        }
        fail("never happened: $what")
    }

    /** Harmonies with `Ejderha ve Kule` in its 3D cell, both of them tasks. */
    private fun twoTasks(
        stack: RealStack,
        table: ComposeSceneHarness,
    ): Pair<EntityId, List<EntityId>> {
        table.settle("the table is read") { stack.table.state.rows !is GameTableRowsState.Loading }
        table.settle("the colours are read") {
            stack.table.state.colors
                .isNotEmpty()
        }
        runBlocking {
            stack.table.startGameComposer()
            stack.table.editGameName("Harmonies")
            stack.table.saveGame()
        }
        table.settle("Harmonies is shown") { stack.rows().any { it.gameName == "Harmonies" } }
        val gameId = stack.gameNamed("Harmonies")
        stack.table.beginEditing(gameId, CellColumnType.THREE_D)
        table.settle("the editor opens") { stack.table.state.work is CellWork.WritingText }
        stack.table.editCellText("Ejderha ve Kule")
        runBlocking { stack.table.saveEditing() }
        table.settle("the words are stored") { stack.table.state.work == null }
        val made =
            listOf("Ejderha", "Kule").map { word ->
                val cell = stack.rows().first { it.gameId == gameId }.cell(CellColumnType.THREE_D)
                val at = cell.editableText.indexOf(word)
                val selection = requireNotNull(cell.locateSelection(gameId, at, at + word.length))
                val taskId =
                    runBlocking {
                        stack.taskCreation
                            .createTasks(
                                selection = selection,
                                drafts =
                                    listOf(
                                        TaskDraft(
                                            colorIds =
                                                listOf(
                                                    stack.table.state.colors
                                                        .first()
                                                        .id,
                                                ),
                                            requiredQuantity = 3,
                                            trackingMode = TrackingMode.THREE_D_BATCH,
                                            notes = null,
                                        ),
                                    ),
                            ).single()
                    }
                table.settle("$word is a task") { stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId == taskId } }
                taskId
            }
        return gameId to made
    }

    private fun RealStack.missingOf(gameId: EntityId): List<EntityId> =
        rows().first { it.gameId == gameId }.missing.flatMap { cell -> cell.segments.mapNotNull { it.taskId } }

    private fun RealStack.isFinished(
        gameId: EntityId,
        taskId: EntityId,
    ): Boolean = piecesOf(gameId, CellColumnType.THREE_D).first { it.taskId == taskId }.isCompletedTask

    @Test
    fun `one press finishes that task alone, and Eksik lets go of it at once`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { table ->
                val (gameId, tasks) = twoTasks(stack, table)
                val (dragon, tower) = tasks
                assertEquals(tasks, stack.missingOf(gameId))
                val pool = stack.pools.of(PoolType.THREE_D)
                ComposeSceneHarness(width = 1300, height = 900) { PoolScreen(pool) }.use { screen ->
                    screen.settle("both tasks are in the pool") {
                        stack.cardsIn(PoolType.THREE_D).map { it.first.taskId }.containsAll(tasks)
                    }

                    // A real press of the mouse, once, where the button is drawn.
                    val button = screen.boundsOf("Ejderha görevini tamamla") ?: fail("the 3D card offers no one-press finish")
                    screen.mouseClick(button.center)
                    screen.settle("the task is written as finished") { pool.state.finishing.isEmpty() }
                    table.settle("the table knows it is finished") { stack.isFinished(gameId, dragon) }

                    assertFalse(stack.isFinished(gameId, tower), "finishing one task finished another")
                    assertEquals(listOf(tower), stack.missingOf(gameId), "Eksik still holds the finished task")
                    assertEquals(null, pool.state.work, "the press opened the task's menu as well")
                    screen.settle("the card leaves the active pool") {
                        stack.cardsIn(PoolType.THREE_D).none { it.first.taskId == dragon }
                    }
                }
            }
        }
    }

    @Test
    fun `the way back is the one there already was, and Eksik has the task again`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { table ->
                val (gameId, tasks) = twoTasks(stack, table)
                val dragon = tasks.first()
                val pool = stack.pools.of(PoolType.THREE_D)
                ComposeSceneHarness(width = 1300, height = 900) { PoolScreen(pool) }.use { screen ->
                    screen.settle("the task is in the pool") { stack.cardsIn(PoolType.THREE_D).any { it.first.taskId == dragon } }
                    assertTrue(screen.click("Ejderha görevini tamamla"))
                    table.settle("the task is finished") { stack.isFinished(gameId, dragon) }

                    pool.showState(TaskStateFilter.COMPLETED)
                    screen.settle("it is in the finished list") { stack.cardsIn(PoolType.THREE_D).any { it.first.taskId == dragon } }
                    // A finished task is not offered the finish again.
                    assertFalse(
                        screen.spokenNodes().flatMap { it.contentDescriptions() }.any { it == "Ejderha görevini tamamla" },
                        "a finished task is offered the finish again",
                    )
                    pool.openTaskMenu(stack.cardsIn(PoolType.THREE_D).first { it.first.taskId == dragon }.first)
                    pool.beginReopen()
                    runBlocking { pool.confirmReopen() }
                    table.settle("the task is active again") { !stack.isFinished(gameId, dragon) }

                    assertTrue(dragon in stack.missingOf(gameId), "a reopened task did not come back to Eksik")
                }
            }
        }
    }

    @Test
    fun `the one-press finish is the 3D pool's alone`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { table ->
                val (gameId, tasks) = twoTasks(stack, table)
                val card = stack.pools.of(PoolType.CARD)
                runBlocking { card.finishTask(tasks.first()) }

                assertFalse(stack.isFinished(gameId, tasks.first()), "another pool finished a 3D task with one press")
            }
        }
    }
}
