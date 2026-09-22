package dev.pnptracker.ui.feature.pools

import dev.pnptracker.data.repository.TaskProgressOutcome
import dev.pnptracker.data.repository.TaskProgressing
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.search.TaskStateFilter
import dev.pnptracker.domain.tasks.TaskProgressFailure
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.feature.games.CellWork
import dev.pnptracker.ui.feature.games.GameTableRowsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A task finished by mistake, and the way back from where it went.
 *
 * Reported from real use: one tap on the tick beside a task's word finished it
 * — no question, nothing to take it back — and it left the active pool for the
 * pool's `Tamamlandı` list. From there it could not be made active again: that
 * list offered `Düzenle` and `Görevi metne dönüştür`, and nothing else.
 *
 * Everything here runs the real stack on a database of its own: the task is
 * made and finished through the same controller the table uses, and the pool is
 * the real pool screen over the real store.
 */
class ReopenFinishedTaskTest {
    private val reopen = "Tamamlanmadı olarak işaretle"

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

    /** Harmonies with one 3D task, `Figür ×3`, finished with one tap on its tick. */
    private fun finishedByMistake(
        stack: RealStack,
        table: ComposeSceneHarness,
    ): EntityId {
        table.settle("the table is read") { stack.table.state.rows !is GameTableRowsState.Loading }
        runBlocking {
            stack.table.startGameComposer()
            stack.table.editGameName("Harmonies")
            stack.table.saveGame()
        }
        table.settle("Harmonies is shown") { stack.rows().any { it.gameName == "Harmonies" } }
        val gameId = stack.gameNamed("Harmonies")
        stack.table.beginEditing(gameId, CellColumnType.THREE_D)
        stack.table.editCellText("Figür")
        runBlocking { stack.table.saveEditing() }
        table.settle("the word is stored") { stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.text == "Figür" } }
        stack.table.beginEditing(gameId, CellColumnType.THREE_D)
        table.settle("the colours are read") {
            stack.table.state.colors
                .isNotEmpty()
        }
        stack.table.beginTaskComposer(0, "Figür".length)
        stack.table.chooseTaskColor(
            0,
            stack.table.state.colors
                .first()
                .id,
        )
        stack.table.editTaskQuantity(0, "3")
        runBlocking { stack.table.saveTask() }
        table.settle("the task is made") { stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId != null } }
        assertTrue(stack.work !is CellWork.MakingTask)
        val taskId = requireNotNull(stack.piecesOf(gameId, CellColumnType.THREE_D).first { it.taskId != null }.taskId)
        // What the one-tap tick does.
        runBlocking { stack.table.toggleTaskCompletion(gameId, CellColumnType.THREE_D, taskId) }
        table.settle("the task is finished") {
            stack.piecesOf(gameId, CellColumnType.THREE_D).first { it.taskId == taskId }.isCompletedTask
        }
        return taskId
    }

    /** The table screen and the 3D pool screen over one stack, with the task finished by mistake. */
    private fun withFinishedTask(
        stack: RealStack,
        pool: PoolController = stack.pools.of(PoolType.THREE_D),
        body: (screen: ComposeSceneHarness, pool: PoolController, taskId: EntityId) -> Unit,
    ) {
        ComposeSceneHarness(width = 1300, height = 900) {
            dev.pnptracker.ui.feature.games
                .GameTableScreen(stack.table)
        }.use { table ->
            val taskId = finishedByMistake(stack, table)
            ComposeSceneHarness(width = 1300, height = 900) { PoolScreen(pool) }.use { screen ->
                pool.showState(TaskStateFilter.COMPLETED)
                screen.settle(
                    "the finished task is in the finished list",
                ) { stack.cardsIn(PoolType.THREE_D).any { it.first.taskId == taskId } }
                pool.openTaskMenu(stack.cardsIn(PoolType.THREE_D).first { it.first.taskId == taskId }.first)
                screen.render()
                body(screen, pool, taskId)
            }
        }
    }

    private fun reopenEvents(
        stack: RealStack,
        taskId: EntityId,
    ) = runBlocking { stack.database.historyDao().eventsOfTask(taskId) }.count { it.kind == HistoryEventKind.TASK_REOPENED }

    @Test
    fun `the finished list offers a way to make the task active again`() {
        RealStack().use { stack ->
            withFinishedTask(stack) { screen, _, _ ->
                val offered = screen.spokenNodes().flatMap { it.contentDescriptions() }
                assertTrue(reopen in offered, "the finished list offers no way back; it offers only $offered")
            }
        }
    }

    @Test
    fun `it asks first, and asking writes nothing`() {
        RealStack().use { stack ->
            withFinishedTask(stack) { screen, pool, taskId ->
                assertTrue(screen.click(reopen))
                screen.settle("the question is asked") { pool.state.work is PoolWork.ConfirmingReopen }
                assertEquals(0, reopenEvents(stack, taskId))
                assertTrue(stack.cardsIn(PoolType.THREE_D).any { it.first.taskId == taskId && it.second.isCompleted })
                // Answering no leaves it finished and goes back to the menu.
                pool.closeInnermost()
                screen.render()
                assertTrue(pool.state.work is PoolWork.Menu)
                assertEquals(0, reopenEvents(stack, taskId))
            }
        }
    }

    @Test
    fun `reopening brings the task back to the active list, whole`() {
        RealStack().use { stack ->
            withFinishedTask(stack) { screen, pool, taskId ->
                val before = stack.cardsIn(PoolType.THREE_D).first { it.first.taskId == taskId }.second
                pool.beginReopen()
                runBlocking { pool.confirmReopen() }
                screen.settle("it leaves the finished list") { stack.cardsIn(PoolType.THREE_D).none { it.first.taskId == taskId } }
                assertTrue(pool.state.work == null, "the question is still standing after it was answered")

                pool.showState(TaskStateFilter.ACTIVE)
                screen.settle("it is in the active list") { stack.cardsIn(PoolType.THREE_D).any { it.first.taskId == taskId } }
                val after = stack.cardsIn(PoolType.THREE_D).first { it.first.taskId == taskId }.second
                assertFalse(after.isCompleted)
                // Only the mark went; everything the task is stayed as it was.
                assertEquals(before.copy(isCompleted = false), after, "reopening changed more than the finished mark")
                assertEquals(1, reopenEvents(stack, taskId), "the history does not say it was reopened, exactly once")
                val piece = stack.piecesOf(stack.gameNamed("Harmonies"), CellColumnType.THREE_D).first { it.taskId == taskId }
                assertFalse(piece.isCompletedTask, "the table still shows it finished")
            }
        }
    }

    @Test
    fun `two presses reopen it once`() {
        RealStack().use { stack ->
            withFinishedTask(stack) { screen, pool, taskId ->
                pool.beginReopen()
                runBlocking {
                    launch { pool.confirmReopen() }
                    launch { pool.confirmReopen() }
                }
                screen.settle("it leaves the finished list") { stack.cardsIn(PoolType.THREE_D).none { it.first.taskId == taskId } }
                assertEquals(1, reopenEvents(stack, taskId), "a double press wrote the reopening twice")
            }
        }
    }

    @Test
    fun `a refused reopening leaves the task finished and says so safely`() {
        RealStack().use { stack ->
            // The real store for everything, except that this one write is refused.
            val refusing =
                object : TaskProgressing by stack.taskProgress {
                    override suspend fun reopenTask(taskId: EntityId) = TaskProgressOutcome.Refused(TaskProgressFailure.TASK_NOT_AVAILABLE)
                }
            val pool = stack.poolControllerWith(PoolType.THREE_D, refusing)
            withFinishedTask(stack, pool) { screen, _, taskId ->
                pool.beginReopen()
                runBlocking { pool.confirmReopen() }
                screen.render()
                val standing = pool.state.work as? PoolWork.ConfirmingReopen
                assertTrue(
                    standing != null && standing.failure != null && !standing.isSaving,
                    "the refusal was not kept on the question: ${pool.state.work}",
                )
                val written = screen.writtenText().joinToString("\n")
                assertTrue("Görev yeniden açılamadı" in written, "the refusal is not said: $written")
                assertFalse(Regex("[0-9a-f]{8}-[0-9a-f]{4}-").containsMatchIn(written), "an identifier reached the screen")
                listOf(
                    "SQL",
                    "Exception",
                    "TASK_NOT_AVAILABLE",
                    "/",
                ).forEach { assertFalse(it in written, "technical text on screen: $it") }
                assertTrue(stack.cardsIn(PoolType.THREE_D).any { it.first.taskId == taskId && it.second.isCompleted })
                assertEquals(0, reopenEvents(stack, taskId))
                assertTrue(screen.spokenNodes().flatMap { it.contentDescriptions() }.contains(reopen), "no way to try again")
            }
        }
    }
}
