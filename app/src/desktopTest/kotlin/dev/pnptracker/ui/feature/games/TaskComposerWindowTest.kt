package dev.pnptracker.ui.feature.games

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.reads
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The window a task is described in, and the room it is given.
 *
 * It used to be a panel inside the cell it was opened from: one column as wide
 * as a table cell, with the modes, the colours, the quantity and the note under
 * one another, so describing one task meant scrolling a strip the width of a
 * thumb. PLAN 12.6 now gives it a window of its own — centred, `900 × 720 dp`
 * where there is room, two columns where it is wide enough, and never larger
 * than the window it opens in.
 *
 * Measured on the real screen at real sizes, because every one of these claims
 * is about where something is drawn. The stack underneath is the real one.
 */
class TaskComposerWindowTest {
    private val window = "Görev oluşturma penceresi"
    private val save = "Görevi kaydet"
    private val cancel = "Vazgeç"

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

    /** A game with [text] written in the column named, and the panel open over all of it. */
    private fun ComposeSceneHarness.composing(
        stack: RealStack,
        text: String = "Figür",
        columnType: CellColumnType = CellColumnType.THREE_D,
    ): EntityId {
        val table = stack.table
        settle("the table is read") { table.state.rows !is GameTableRowsState.Loading }
        runBlocking {
            table.startGameComposer()
            table.editGameName("Harmonies")
            table.saveGame()
        }
        settle("Harmonies is shown") { stack.rows().any { it.gameName == "Harmonies" } }
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

    /** Where a plain piece of writing is drawn — a field's label, for one. */
    private fun ComposeSceneHarness.boundsOfText(text: String): Rect? =
        nodes()
            .firstOrNull { node -> node.reads(SemanticsProperties.Text).orEmpty().any { it.text == text } }
            ?.boundsInRoot

    private fun Rect.holds(inner: Rect): Boolean =
        left <= inner.left + TOLERANCE &&
            top <= inner.top + TOLERANCE &&
            right >= inner.right - TOLERANCE &&
            bottom >= inner.bottom - TOLERANCE

    @Test
    fun `the window opens in the middle of the screen at the size PLAN gives it`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                screen.composing(stack)

                val drawn = screen.boundsOf(window) ?: fail("there is no task window on the screen")
                assertEquals(900f, drawn.width, TOLERANCE, "the window is not 900 dp wide: $drawn")
                assertEquals(720f, drawn.height, TOLERANCE, "the window is not 720 dp tall: $drawn")
                assertTrue(abs(drawn.center.x - 650f) < 2f, "the window is not centred across: $drawn")
                assertTrue(abs(drawn.center.y - 450f) < 2f, "the window is not centred down: $drawn")
            }
        }
    }

    @Test
    fun `the smallest window still holds the whole thing, with room around it`() {
        RealStack().use { stack ->
            // The narrowest window `Main` allows, at twice the density and with
            // the text scaled up: the hardest case the application can be in.
            val density = 2f
            ComposeSceneHarness(
                width = (640 * density).toInt(),
                height = (460 * density).toInt(),
                density = Density(density, 1.3f),
            ) { GameTableScreen(stack.table) }.use { screen ->
                screen.composing(stack)

                val drawn = screen.boundsOf(window) ?: fail("there is no task window on the screen")
                val screenRect = Rect(0f, 0f, 640 * density, 460 * density)
                assertTrue(screenRect.holds(drawn), "the window does not fit the screen: $drawn")
                // PLAN 12.6: about 24 dp of daylight on every side.
                val margin = 20f * density
                assertTrue(drawn.left >= margin, "no room on the left: $drawn")
                assertTrue(drawn.top >= margin, "no room at the top: $drawn")
                assertTrue(screenRect.right - drawn.right >= margin, "no room on the right: $drawn")
                assertTrue(screenRect.bottom - drawn.bottom >= margin, "no room at the bottom: $drawn")

                listOf(save, cancel).forEach { control ->
                    val at = screen.boundsOf(control) ?: fail("`$control` is not drawn at all")
                    assertTrue(drawn.holds(at), "`$control` is outside the window: $at against $drawn")
                }
            }
        }
    }

    @Test
    fun `the colours stand beside what is being described, not under it`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                screen.composing(stack)

                val quantity = screen.boundsOfText("Gerekli adet") ?: fail("the quantity field is not drawn")
                val colors = screen.boundsOfText("Renk ara") ?: fail("the colour search is not drawn")
                assertTrue(colors.left > quantity.right, "the colours are not in a column of their own: $colors, $quantity")
                assertTrue(colors.top < quantity.bottom, "the colours are not beside the task: $colors, $quantity")
            }
        }
    }

    @Test
    fun `a name too long to read does not push the actions off the window`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                // One long name rather than many words: PLAN 12.6 has it scroll
                // in its own place rather than grow the window.
                screen.composing(stack, text = "Ejderha".repeat(40))

                val drawn = screen.boundsOf(window) ?: fail("there is no task window on the screen")
                assertEquals(720f, drawn.height, TOLERANCE, "the name made the window taller: $drawn")
                listOf(save, cancel).forEach { control ->
                    val at = screen.boundsOf(control) ?: fail("`$control` is not drawn at all")
                    assertTrue(drawn.holds(at), "`$control` was pushed out of the window: $at against $drawn")
                }
            }
        }
    }

    @Test
    fun `the keyboard stays inside the window`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                screen.composing(stack)
                val drawn = screen.boundsOf(window) ?: fail("there is no task window on the screen")

                repeat(30) {
                    screen.tab()
                    val landed = screen.focusedNode()?.boundsInRoot ?: return@repeat
                    assertTrue(drawn.holds(landed), "Tab left the window and landed on the table: $landed")
                }
            }
        }
    }

    @Test
    fun `Escape closes the window and writes nothing`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val gameId = screen.composing(stack)

                screen.press(Key.Escape)
                screen.render()

                assertTrue(stack.work is CellWork.WritingText, "Escape did not go back to the cell: ${stack.work}")
                assertNull(screen.boundsOf(window), "the window is still drawn")
                assertEquals(
                    0,
                    stack.piecesOf(gameId, CellColumnType.THREE_D).count { it.taskId != null },
                    "giving up on the window wrote a task",
                )
            }
        }
    }

    private companion object {
        /** A pixel of rounding, which a layout in dp is entitled to. */
        const val TOLERANCE = 1.5f
    }
}
