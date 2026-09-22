package dev.pnptracker.ui.feature.games

import androidx.compose.ui.input.key.Key
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.data.repository.TaskCreationFromText
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import dev.pnptracker.ui.contentDescriptions
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Making a task out of a word, the way it was reported from real use.
 *
 * Harmonies was made, a word was written into its 3D cell, the word was
 * selected, and `Görev oluştur` was pressed. The highlight went, the button
 * went, and nothing was made — no panel, no task, no message.
 *
 * The press took focus off the text field, a text field that loses focus drops
 * its selection, and the offer was drawn only while there was a selection. So
 * it left the screen between the press and the release, and the release landed
 * on nothing. [ComposeSceneHarness.click] could never have seen it: it invokes
 * the action directly. These use a real mouse and a real Tab, over the real
 * stack, on a database of their own.
 */
class TaskFromTextOnScreenTest {
    private val create = "Görev oluştur"

    /** Renders until [done] holds, giving the database's own threads the time they need. */
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

    /** Harmonies, with `Figür` written and saved in its 3D cell, and the cell open again. */
    private fun ComposeSceneHarness.harmoniesWithAWord(
        stack: RealStack,
        table: GameTableController = stack.table,
    ): EntityId {
        settle("the table is read") { table.state.rows !is GameTableRowsState.Loading }
        runBlocking {
            table.startGameComposer()
            table.editGameName("Harmonies")
            table.saveGame()
        }
        settle("Harmonies is shown") { rowsOf(table).any { it.gameName == "Harmonies" } }
        val gameId = rowsOf(table).first { it.gameName == "Harmonies" }.gameId
        table.beginEditing(gameId, CellColumnType.THREE_D)
        settle("the editor opens") { table.state.work is CellWork.WritingText }
        // What the user typed; the field follows the draft.
        table.editCellText("Figür")
        render()
        runBlocking { table.saveEditing() }
        settle("the word is stored") { piecesOf(table, gameId).any { it.text == "Figür" } }
        table.beginEditing(gameId, CellColumnType.THREE_D)
        settle("the editor opens again") { table.state.work is CellWork.WritingText }
        return gameId
    }

    private fun rowsOf(table: GameTableController) = (table.state.rows as? GameTableRowsState.Content)?.rows.orEmpty()

    private fun piecesOf(
        table: GameTableController,
        gameId: EntityId,
    ) = rowsOf(table)
        .firstOrNull { it.gameId == gameId }
        ?.cell(CellColumnType.THREE_D)
        ?.segments
        .orEmpty()

    @Test
    fun `pressing the offer with the mouse opens the task panel`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                screen.harmoniesWithAWord(stack)
                screen.press(Key.A, ctrl = true)
                screen.settle("the offer appears for the selected word") { screen.boundsOf(create) != null }

                screen.mouseClick(requireNotNull(screen.boundsOf(create)).center)
                screen.render()

                assertTrue(stack.work is CellWork.MakingTask, "the mouse press on the offer made nothing: ${stack.work}")
                val composer = (stack.work as CellWork.MakingTask).composer
                assertEquals("Figür", composer.name, "the panel is not about the word that was selected")
            }
        }
    }

    @Test
    fun `the offer can be reached with the keyboard and taken`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                screen.harmoniesWithAWord(stack)
                screen.press(Key.A, ctrl = true)
                screen.settle("the offer appears for the selected word") { screen.boundsOf(create) != null }

                assertTrue(screen.tabTo(create), "Tab never reaches the offer: it goes the moment the field loses focus")
                screen.press(Key.Enter)
                screen.render()

                assertTrue(stack.work is CellWork.MakingTask, "the offer taken from the keyboard made nothing: ${stack.work}")
            }
        }
    }

    /** Fills in the panel and saves, as the user does. */
    private fun ComposeSceneHarness.fillAndSave(stack: RealStack) {
        settle("the colours are read") { stack.table.state.colors.isNotEmpty() }
        stack.table.chooseTaskColor(0, stack.table.state.colors.first().id)
        stack.table.editTaskQuantity(0, "3")
        runBlocking { stack.table.saveTask() }
        render()
    }

    @Test
    fun `a saved task is written once, into the game it was made in, and said out loud`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val gameId = screen.harmoniesWithAWord(stack)
                screen.press(Key.A, ctrl = true)
                screen.settle("the offer appears") { screen.boundsOf(create) != null }
                screen.mouseClick(requireNotNull(screen.boundsOf(create)).center)
                screen.fillAndSave(stack)

                screen.settle("the word is a task") { stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId != null } }
                val tasks = stack.piecesOf(gameId, CellColumnType.THREE_D).filter { it.taskId != null }
                assertEquals(1, tasks.size, "one word made ${tasks.size} tasks")
                assertEquals("Figür", tasks.single().text)
                assertEquals("Harmonies", stack.rows().first { it.gameId == gameId }.gameName)

                // The screen says what was made and where it went.
                val said = screen.writtenText().joinToString(" ")
                assertTrue("Görev Harmonies oyununa eklendi." in said, "the save was not said out loud: $said")
                assertTrue(screen.click("Görevi görüntüle"), "there is no way to the task that was made")
                screen.render()
                val menu = stack.work as? CellWork.TaskMenu
                assertEquals(tasks.single().taskId, menu?.taskId, "the offer did not open the task that was made")
            }
        }
    }

    @Test
    fun `the same word cannot be made into a second task`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val gameId = screen.harmoniesWithAWord(stack)
                stack.table.beginTaskComposer(0, "Figür".length)
                screen.fillAndSave(stack)
                screen.settle("the word is a task") { stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId != null } }

                // Selecting the same stretch again is refused; it is a task now.
                stack.table.beginEditing(gameId, CellColumnType.THREE_D)
                screen.render()
                stack.table.beginTaskComposer(0, "Figür".length)
                screen.render()
                assertFalse(stack.work is CellWork.MakingTask, "the panel opened over a stretch that is already a task")
                assertEquals(1, stack.piecesOf(gameId, CellColumnType.THREE_D).count { it.taskId != null })
            }
        }
    }

    @Test
    fun `storage refusing to write leaves the panel, the answers and the offer`() {
        RealStack().use { stack ->
            // Everything real but the one write, which storage refuses.
            val refusing =
                object : TaskCreationFromText by stack.taskCreation {
                    override suspend fun createTasks(
                        selection: CellTextSelection,
                        drafts: List<TaskDraft>,
                    ): List<EntityId> = throw TaskFromTextException(TaskFromTextFailure.COULD_NOT_SAVE)
                }
            val controller = stack.tableControllerWith(refusing)
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(controller) }.use { screen ->
                val gameId = screen.harmoniesWithAWord(stack, controller)
                controller.beginTaskComposer(0, "Figür".length)
                screen.settle("the colours are read") { controller.state.colors.isNotEmpty() }
                controller.chooseTaskColor(0, controller.state.colors.first().id)
                controller.editTaskQuantity(0, "3")
                runBlocking { controller.saveTask() }
                screen.render()

                val making = controller.state.work as? CellWork.MakingTask
                assertEquals(TaskFromTextFailure.COULD_NOT_SAVE, making?.composer?.failure, "the refusal was not kept on the panel")
                assertEquals("3", making?.composer?.single?.quantityText, "the answers were thrown away")
                assertEquals(0, stack.piecesOf(gameId, CellColumnType.THREE_D).count { it.taskId != null }, "half a task was written")
                val said = screen.writtenText().joinToString(" ")
                assertFalse("eklendi" in said, "a refused save was announced as a success")
                assertFalse(Regex("[0-9a-f]{8}-[0-9a-f]{4}-").containsMatchIn(said), "an identifier reached the screen")
                listOf("SQL", "Exception", "COULD_NOT_SAVE").forEach { assertFalse(it in said, "technical text on screen: $it") }
                // And the way to try again is still there.
                assertTrue(screen.spokenNodes().flatMap { it.contentDescriptions() }.any { it == "Görevi kaydet" }, "no way to try again")
            }
        }
    }

    @Test
    fun `a broken invariant is not dressed up as something the user did wrong`() {
        RealStack().use { stack ->
            val broken =
                object : TaskCreationFromText by stack.taskCreation {
                    override suspend fun createTasks(
                        selection: CellTextSelection,
                        drafts: List<TaskDraft>,
                    ): List<EntityId> = throw IllegalStateException("a defect")
                }
            val controller = stack.tableControllerWith(broken)
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(controller) }.use { screen ->
                screen.harmoniesWithAWord(stack, controller)
                controller.beginTaskComposer(0, "Figür".length)
                screen.settle("the colours are read") { controller.state.colors.isNotEmpty() }
                controller.chooseTaskColor(0, controller.state.colors.first().id)
                controller.editTaskQuantity(0, "3")
                assertFailsWith<IllegalStateException> { runBlocking { controller.saveTask() } }
            }
        }
    }
}
