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

    /**
     * Renders until [done] holds, giving the database's own threads the time they need.
     *
     * [detail] is read only when the wait runs out: a panel that refused to open
     * cannot be explained by a message saying it did not open.
     */
    private fun ComposeSceneHarness.settle(
        what: String,
        detail: () -> String = { "" },
        done: () -> Boolean,
    ) {
        repeat(200) {
            render()
            if (done()) return
            Thread.sleep(10)
        }
        fail("never happened: $what${detail()}")
    }

    /**
     * What the table is in the middle of, in words safe to print.
     *
     * Structure only — the kind of work, the flags on it, and the names of the
     * refusals, which are a fixed vocabulary. Nothing the user typed, nothing
     * with an identity in it: a message that named a game or a cell would put a
     * person's own words in a build log to explain a test.
     *
     * [storedReachedExpected] is the one thing about the database worth saying
     * here: whether the words the editor was saving are in the cell now. A save
     * that stored them and an editor that still calls itself unsaved cannot both
     * be right, and knowing which is the difference between a defect in the
     * transaction and a defect in how the screen was driven.
     */
    private fun whatIsOpen(
        table: GameTableController,
        storedReachedExpected: Boolean,
    ): String {
        val work = table.state.work
        val editor = work as? CellWork.WritingText
        return "; the work is ${work?.let { it::class.simpleName } ?: "nothing"}" +
            ", isSaving=${editor?.isSaving}" +
            ", unsaved=${editor?.hasUnsavedChanges}" +
            ", failure=${editor?.failure?.name ?: "none"}" +
            ", refused=${editor?.selectionFailure?.name ?: "none"}" +
            ", blockedByEditor=${table.state.blockedByEditor}" +
            ", storedReachedExpected=$storedReachedExpected"
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
        val stored = { stack.piecesOf(gameId, columnType).any { it.text == text } }
        settle("the word is stored") { stored() }
        // Waited for rather than assumed: the window refuses to open over an
        // editor with unsaved changes, so opening one over the editor that was
        // still there would refuse for a reason the test never asked about.
        settle("the editor closes after the save", { whatIsOpen(table, stored()) }) { table.state.work == null }
        table.beginEditing(gameId, columnType)
        settle("the editor opens again on the stored word", { whatIsOpen(table, stored()) }) {
            (table.state.work as? CellWork.WritingText)?.let { it.originalText == text && !it.hasUnsavedChanges } == true
        }
        table.beginTaskComposer(0, text.length)
        settle("the window opens", { whatIsOpen(table, stored()) }) { table.state.work is CellWork.MakingTask }
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
    fun `at the size a full screen window opens at, it is still its own size`() {
        RealStack().use { stack ->
            // 1920 by 1080: the window the application is most often really used
            // in. PLAN 12.6 keeps it 900 by 720 there rather than growing with
            // the screen.
            ComposeSceneHarness(width = 1920, height = 1080) { GameTableScreen(stack.table) }.use { screen ->
                screen.composing(stack)

                val drawn = screen.boundsOf(window) ?: fail("there is no task window on the screen")
                assertEquals(900f, drawn.width, TOLERANCE, "the window grew with the screen: $drawn")
                assertEquals(720f, drawn.height, TOLERANCE, "the window grew with the screen: $drawn")
                assertTrue(abs(drawn.center.x - 960f) < 2f, "the window is not centred across: $drawn")
                assertTrue(abs(drawn.center.y - 540f) < 2f, "the window is not centred down: $drawn")
                listOf(save, cancel).forEach { control ->
                    assertTrue(drawn.holds(screen.boundsOf(control) ?: fail("`$control` is not drawn")), "`$control` is outside")
                }
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
