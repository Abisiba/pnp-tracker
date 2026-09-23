package dev.pnptracker.ui.feature.games

import androidx.compose.ui.semantics.SemanticsProperties
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.reads
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Colour as a property of printing, and of nothing else.
 *
 * PLAN 5.10 gives a colour to three dimensional work alone: it is the filament a
 * thing is printed in. A card, a board piece or a special task was still being
 * asked which colour it was, and could not be saved until one was picked — a
 * question with no meaning that had to be answered anyway.
 *
 * So these are about what the window asks for, and what the database ends up
 * holding, in each of the four columns.
 */
class ColorBelongsToPrintingTest {
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

    /** A game with [text] in the column named, and the task window open over it. */
    private fun ComposeSceneHarness.composingIn(
        stack: RealStack,
        columnType: CellColumnType,
        text: String = "Figür",
    ): EntityId {
        val table = stack.table
        settle("the table is read") { table.state.rows !is GameTableRowsState.Loading }
        runBlocking {
            table.startGameComposer()
            table.editGameName("Harmonies")
            table.saveGame()
        }
        settle("the game is shown") { stack.rows().any { it.gameName == "Harmonies" } }
        val gameId = stack.gameNamed("Harmonies")
        table.beginEditing(gameId, columnType)
        settle("the editor opens") { table.state.work is CellWork.WritingText }
        table.editCellText(text)
        render()
        runBlocking { table.saveEditing() }
        settle("the word is stored") { stack.piecesOf(gameId, columnType).any { it.text == text } }
        table.beginEditing(gameId, columnType)
        settle("the editor opens again") { table.state.work is CellWork.WritingText }
        table.beginTaskComposer(0, text.length)
        settle("the window opens") { table.state.work is CellWork.MakingTask }
        settle("the colours are read") { table.state.colors.isNotEmpty() }
        return gameId
    }

    private fun ComposeSceneHarness.isWritten(text: String): Boolean =
        nodes().any { node -> node.reads(SemanticsProperties.Text).orEmpty().any { it.text == text } }

    @Test
    fun `the window for work that has no colour does not ask about one`() {
        listOf(CellColumnType.CARD, CellColumnType.BOARD, CellColumnType.SPECIAL).forEach { columnType ->
            RealStack().use { stack ->
                ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                    screen.composingIn(stack, columnType)

                    assertFalse(screen.isWritten("Renk ara"), "$columnType is asked which colour it is")
                    assertFalse(screen.isWritten("Tek öge çok renk"), "$columnType is offered a mode made of colours")
                    assertNull(screen.boundsOf("Yeni renk oluştur"), "$columnType is offered a colour to make")
                    assertTrue(screen.isWritten("Gerekli adet"), "the window lost the fields it should still have")
                }
            }
        }
    }

    @Test
    fun `a task with no colour to it saves once the count is there`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val gameId = screen.composingIn(stack, CellColumnType.CARD)

                stack.table.editTaskQuantity(0, "60")
                screen.render()
                val composer = (stack.work as? CellWork.MakingTask)?.composer
                assertTrue(composer?.canSave == true, "a card task cannot be saved without a colour it does not have")

                runBlocking { stack.table.saveTask() }
                screen.settle("the word is a task") { stack.piecesOf(gameId, CellColumnType.CARD).any { it.taskId != null } }

                val taskId = assertNotNull(stack.piecesOf(gameId, CellColumnType.CARD).first { it.taskId != null }.taskId)
                assertEquals(
                    emptyList(),
                    runBlocking { stack.database.taskColorDao().colorsOfTask(taskId) },
                    "a card task was written with a colour",
                )
                assertEquals(60, runBlocking { stack.database.taskDao().activeTaskById(taskId) }?.requiredQuantity)
            }
        }
    }

    @Test
    fun `printing is still asked which colour it is`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                screen.composingIn(stack, CellColumnType.THREE_D)

                assertTrue(screen.isWritten("Renk ara"), "printing is no longer asked which colour it is")
                val composer = (stack.work as? CellWork.MakingTask)?.composer
                stack.table.editTaskQuantity(0, "14")
                screen.render()
                assertFalse(
                    (stack.work as? CellWork.MakingTask)?.composer?.canSave == true,
                    "a 3D task saved without a colour: $composer",
                )
            }
        }
    }

    @Test
    fun `changing a task that has no colour does not ask about one`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val gameId = screen.composingIn(stack, CellColumnType.CARD)
                stack.table.editTaskQuantity(0, "60")
                screen.render()
                runBlocking { stack.table.saveTask() }
                screen.settle("the word is a task") { stack.piecesOf(gameId, CellColumnType.CARD).any { it.taskId != null } }
                val taskId = assertNotNull(stack.piecesOf(gameId, CellColumnType.CARD).first { it.taskId != null }.taskId)

                stack.table.openTaskMenu(gameId, CellColumnType.CARD, taskId)
                screen.render()
                stack.table.beginTaskEdit()
                screen.settle("the panel opens") { stack.work is CellWork.EditingTask }

                assertFalse(screen.isWritten("Renk ara"), "a card task is asked which colour it is")
                assertNull(screen.boundsOf("Yeni renk oluştur"), "a card task is offered a colour to make")
                assertTrue(screen.isWritten("Görev adı"), "the panel lost the fields it should still have")
            }
        }
    }
}
