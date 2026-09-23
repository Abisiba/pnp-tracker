package dev.pnptracker.ui.feature.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Renaming a game where its name is written.
 *
 * A game could be created with a name and never called anything else: a typo
 * made on the day it was added stayed there, and the only way out of it was to
 * make another game and move the work by hand. PLAN 12.3 now opens the name for
 * editing on a double click in its own cell — one field, `Enter` and `Escape`,
 * and nothing else about the game touched.
 *
 * Driven with a real double click through the real stack, because the press and
 * the release are exactly what a gesture is made of.
 */
class RenameGameTest {
    private val nameField = "Oyun adı"

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

    /** Harmonies, with a word written in its 3D cell and made into a task. */
    private fun ComposeSceneHarness.harmonies(
        stack: RealStack,
        table: GameTableController = stack.table,
    ): EntityId {
        settle("the table is read") { table.state.rows !is GameTableRowsState.Loading }
        runBlocking {
            table.startGameComposer()
            table.editGameName("Harmoies")
            table.saveGame()
        }
        settle("the game is shown") { rowsOf(table).any { it.gameName == "Harmoies" } }
        val gameId = rowsOf(table).first { it.gameName == "Harmoies" }.gameId
        table.beginEditing(gameId, CellColumnType.THREE_D)
        settle("the editor opens") { table.state.work is CellWork.WritingText }
        table.editCellText("Figür")
        render()
        runBlocking { table.saveEditing() }
        settle("the word is stored") { piecesOf(table, gameId).any { it.text == "Figür" } }
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

    /**
     * Where a game's name is drawn, found through the row that speaks for it.
     *
     * The name itself is drawn and not spoken — the tick beside it says the name,
     * the state and what can be done — so the point is worked out from the tick:
     * the name is the next thing along the row, inside the name column.
     */
    private fun ComposeSceneHarness.whereTheNameIs(name: String): Offset {
        val tick =
            spokenNodes()
                .firstOrNull { node -> node.contentDescriptions().any { it.startsWith(name) } }
                ?.boundsInRoot
                ?: fail("the row for $name is not drawn at all")
        return Offset(tick.right + 30f, tick.top + 6f)
    }

    @Test
    fun `a double click on the name opens an editor holding that name`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                screen.harmonies(stack)

                screen.mouseDoubleClick(screen.whereTheNameIs("Harmoies"))
                screen.render()

                val renaming = stack.table.state.rowWork as? RowWork.RenamingGame
                assertNotNull(renaming, "a double click on the name did not open the name for editing")
                assertEquals("Harmoies", renaming.draft, "the field does not hold the name it is about")
                assertNotNull(screen.boundsOf(nameField), "there is no field to type the name in")
            }
        }
    }

    @Test
    fun `Enter writes the new name and leaves the game, its cells and its work alone`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val gameId = screen.harmonies(stack)
                val before = piecesOf(stack.table, gameId)

                stack.table.beginRenaming(gameId)
                screen.render()
                stack.table.editGameRename("Harmonies")
                screen.render()
                screen.press(Key.Enter)
                screen.settle("the new name is shown") { stack.rows().any { it.gameName == "Harmonies" } }

                val row = stack.rows().single()
                assertEquals(gameId, row.gameId, "renaming made another game")
                assertEquals("Harmonies", row.gameName)
                assertEquals(before, piecesOf(stack.table, gameId), "renaming changed what is written in the game")
                assertNull(stack.table.state.rowWork, "the editor is still open after it saved")
            }
        }
    }

    @Test
    fun `Escape leaves the name as it was and writes nothing`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val gameId = screen.harmonies(stack)

                stack.table.beginRenaming(gameId)
                screen.render()
                stack.table.editGameRename("Something else")
                screen.render()
                screen.press(Key.Escape)
                screen.render()

                assertNull(stack.table.state.rowWork, "Escape did not close the editor")
                assertEquals("Harmoies", stack.rows().single().gameName, "Escape saved the name anyway")
            }
        }
    }

    @Test
    fun `a name that says nothing cannot be saved`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val gameId = screen.harmonies(stack)

                stack.table.beginRenaming(gameId)
                screen.render()
                stack.table.editGameRename("   ")
                screen.render()
                screen.press(Key.Enter)
                screen.render()

                assertTrue(stack.table.state.rowWork is RowWork.RenamingGame, "an empty name closed the editor")
                assertEquals("Harmoies", stack.rows().single().gameName, "an empty name was written")
                val said = screen.writtenText().joinToString(" ")
                assertTrue("Oyun adı boş olamaz." in said, "nothing says why the name will not save: $said")
            }
        }
    }

    @Test
    fun `the name it already has is not written again`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val gameId = screen.harmonies(stack)
                val before = runBlocking { stack.database.gameDao().activeGameById(gameId) }

                stack.table.beginRenaming(gameId)
                screen.render()
                // The spaces around it are a slip, so what is being asked for is
                // the name the game already has.
                stack.table.editGameRename("  Harmoies  ")
                screen.render()
                screen.press(Key.Enter)
                screen.render()

                val after = runBlocking { stack.database.gameDao().activeGameById(gameId) }
                assertNull(stack.table.state.rowWork, "the editor stayed open over a name that needed no writing")
                assertEquals(before, after, "a name that did not change was written to the database anyway")
            }
        }
    }

    @Test
    fun `storage refusing the change keeps the old name, the field and what was typed`() {
        RealStack().use { stack ->
            val refusing =
                object : GameSetup by stack.gameSetup {
                    override suspend fun renameGame(
                        gameId: EntityId,
                        name: String,
                    ) = throw GameSetupException(GameSetupFailure.COULD_NOT_SAVE)
                }
            val controller = stack.tableControllerWith(setup = refusing)
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(controller) }.use { screen ->
                val gameId = screen.harmonies(stack, controller)

                controller.beginRenaming(gameId)
                screen.render()
                controller.editGameRename("Harmonies")
                screen.render()
                screen.press(Key.Enter)
                screen.settle("the refusal is shown") { (controller.state.rowWork as? RowWork.RenamingGame)?.failure != null }

                val renaming = controller.state.rowWork as? RowWork.RenamingGame
                assertNotNull(renaming, "the refusal closed the editor")
                assertEquals("Harmonies", renaming.draft, "the refusal threw away what the user typed")
                assertEquals("Harmoies", rowsOf(controller).single().gameName, "the refused name was shown as if it saved")
                val said = screen.writtenText().joinToString(" ")
                assertTrue("kaydedilemedi" in said.lowercase(), "the refusal is not said in Turkish: $said")
                listOf("SQL", "Exception", "COULD_NOT_SAVE", gameId.value.toString()).forEach { leak ->
                    assertFalse(leak in said, "the screen shows something technical: $leak")
                }
            }
        }
    }
}
