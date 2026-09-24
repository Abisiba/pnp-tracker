package dev.pnptracker.ui.feature.games

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.reads
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What a finished task looks like where it was written.
 *
 * It used to be struck through: a line drawn over the name, over the count and
 * over the colours it is made in, which is exactly the part of a cell that is
 * worth reading afterwards. PLAN 12.5 now gives it a ground of its own and puts
 * the finished work after the work still to do — while the cell's own document,
 * and the order the user typed it in, are left exactly as they are.
 *
 * What the state is said with is measured next door, in
 * `CompactCompletedTaskTest`: the box the task already carries, and the state a
 * reader hears. Nothing is written beside the name.
 *
 * Presentation only: nothing here changes what finishing writes.
 */
class FinishedTaskLookTest {
    /** A window the application really opens in, and how the text is scaled in it. */
    private data class Viewport(
        val widthDp: Int,
        val heightDp: Int,
        val density: Density,
    )

    private fun ComposeSceneHarness.settle(
        what: String,
        done: () -> Boolean,
    ) {
        repeat(200) {
            render()
            if (done()) {
                // One more frame: the state has changed, and what is asserted
                // afterwards is what the screen drew from it.
                render()
                return
            }
            Thread.sleep(10)
        }
        fail("never happened: $what")
    }

    /** Harmonies with `Ayı` and `Kuş` in its 3D cell, both tasks, in that order. */
    private fun ComposeSceneHarness.twoTasks(stack: RealStack): Pair<EntityId, List<EntityId>> {
        val table = stack.table
        settle("the table is read") { table.state.rows !is GameTableRowsState.Loading }
        runBlocking {
            table.startGameComposer()
            table.editGameName("Harmonies")
            table.saveGame()
        }
        settle("the game is shown") { stack.rows().any { it.gameName == "Harmonies" } }
        val gameId = stack.gameNamed("Harmonies")
        table.beginEditing(gameId, CellColumnType.THREE_D)
        settle("the editor opens") { table.state.work is CellWork.WritingText }
        table.editCellText("Ayı ve Kuş")
        render()
        runBlocking { table.saveEditing() }
        settle("the words are stored") { stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.text == "Ayı ve Kuş" } }
        settle("the colours are read") { table.state.colors.isNotEmpty() }

        val made = mutableListOf<EntityId>()
        listOf("Ayı", "Kuş").forEach { word ->
            // Counted across the whole cell, which is what the user drags over
            // and what the editor hands the controller.
            val cell =
                stack
                    .rows()
                    .first { it.gameId == gameId }
                    .cell(CellColumnType.THREE_D)
            val at = cell.editableText.indexOf(word)
            val selection =
                requireNotNull(
                    cell.locateSelection(gameId, at, at + word.length),
                ) { "the word $word is not where it was written" }
            made +=
                runBlocking {
                    stack.taskCreation
                        .createTasks(
                            selection = selection,
                            drafts =
                                listOf(
                                    TaskDraft(
                                        colorIds =
                                            listOf(
                                                table.state.colors
                                                    .first()
                                                    .id,
                                            ),
                                        requiredQuantity = 14,
                                        trackingMode = TrackingMode.THREE_D_BATCH,
                                        notes = null,
                                    ),
                                ),
                        ).single()
                }
            settle("$word is a task") { stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId in made } }
        }
        return gameId to made
    }

    /** The one drawn string that holds a cell's document. */
    private fun ComposeSceneHarness.cellText(word: String): AnnotatedString =
        nodes()
            .flatMap { node -> node.reads(SemanticsProperties.Text).orEmpty() }
            .firstOrNull { word in it.text && it.spanStyles.isNotEmpty() }
            ?: fail("no drawn cell holds $word")

    private fun AnnotatedString.styleOver(word: String) =
        spanStyles.firstOrNull { it.start <= text.indexOf(word) && it.end >= text.indexOf(word) + word.length }
            ?: fail("$word is drawn with no style of its own in [$text] with ${spanStyles.map { it.start to it.end }}")

    /** The style over one run of the drawn document, found by where it is rather than by what it says. */
    private fun AnnotatedString.styleAt(
        at: Int,
        length: Int,
    ) = spanStyles.firstOrNull { it.start <= at && it.end >= at + length }?.item
        ?: fail("nothing is styled at $at in [$text] with ${spanStyles.map { it.start to it.end }}")

    @Test
    fun `a finished task is not struck through, and keeps a ground of its own`() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            RealStack().use { stack ->
                ComposeSceneHarness(width = 1300, height = 900) {
                    PnpTrackerTheme(theme) { GameTableScreen(stack.table) }
                }.use { screen ->
                    val (gameId, tasks) = screen.twoTasks(stack)
                    runBlocking { stack.table.toggleTaskCompletion(gameId, CellColumnType.THREE_D, tasks.first()) }
                    screen.settle("the task is finished") {
                        stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId == tasks.first() && it.isCompletedTask }
                    }

                    val drawn = screen.cellText("Ayı")
                    assertNull(
                        drawn
                            .styleOver("Ayı")
                            .item.textDecoration
                            ?.takeIf { it == TextDecoration.LineThrough },
                        "the finished task is still struck through in $theme",
                    )
                    val finished = drawn.styleOver("Ayı").item.background
                    val unfinished = drawn.styleOver("Kuş").item.background
                    assertTrue(finished != unfinished, "finished and unfinished work are drawn on the same ground in $theme")

                    assertTrue("Ayı" in drawn.text && "×14" in drawn.text, "the name or the count stopped being readable in $theme")
                    // The count is on the finished ground too, so what is drawn
                    // is one compact surface and not a name with a number
                    // hanging off it (PLAN 12.5). The cell holds two counts, so
                    // this is the one after the finished name and not the first.
                    val countAt = drawn.text.indexOf("×14", startIndex = drawn.text.indexOf("Ayı"))
                    assertEquals(
                        finished,
                        drawn.styleAt(countAt, "×14".length).background,
                        "the count of a finished task is drawn off its own ground in $theme",
                    )
                }
            }
        }
    }

    @Test
    fun `finished work is drawn after the work still to do, and the document is untouched`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val (gameId, tasks) = screen.twoTasks(stack)
                val documentBefore = stack.piecesOf(gameId, CellColumnType.THREE_D).map { it.text }

                // The first of the two is finished, so what is left to do is now
                // the second: PLAN 12.5 draws it first.
                runBlocking { stack.table.toggleTaskCompletion(gameId, CellColumnType.THREE_D, tasks.first()) }
                screen.settle("the task is finished") {
                    stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId == tasks.first() && it.isCompletedTask }
                }

                val drawn = screen.cellText("Ayı").text
                assertTrue(drawn.indexOf("Kuş") < drawn.indexOf("Ayı"), "the finished task is still drawn first: $drawn")
                assertEquals(
                    documentBefore,
                    stack.piecesOf(gameId, CellColumnType.THREE_D).map { it.text },
                    "the cell's own document was reordered",
                )
            }
        }
    }

    @Test
    fun `a task made active again looks like any other`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val (gameId, tasks) = screen.twoTasks(stack)
                runBlocking { stack.table.toggleTaskCompletion(gameId, CellColumnType.THREE_D, tasks.first()) }
                screen.settle("the task is finished") {
                    stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId == tasks.first() && it.isCompletedTask }
                }

                runBlocking { stack.table.toggleTaskCompletion(gameId, CellColumnType.THREE_D, tasks.first()) }
                screen.settle("the task is active again") {
                    stack.piecesOf(gameId, CellColumnType.THREE_D).none { it.isCompletedTask }
                }

                val drawn = screen.cellText("Ayı")
                assertEquals(
                    drawn.styleOver("Kuş").item.background,
                    drawn.styleOver("Ayı").item.background,
                    "a task made active again is still drawn as finished",
                )
                // Its count comes off the finished ground with it: nothing about
                // the task is drawn as finished any more.
                val countAt = drawn.text.indexOf("×14", startIndex = drawn.text.indexOf("Ayı"))
                val otherCountAt = drawn.text.indexOf("×14", startIndex = drawn.text.indexOf("Kuş"))
                assertEquals(
                    drawn.styleAt(otherCountAt, "×14".length).background,
                    drawn.styleAt(countAt, "×14".length).background,
                    "the count of a reopened task is still drawn on the finished ground",
                )
                assertTrue(drawn.text.indexOf("Ayı") < drawn.text.indexOf("Kuş"), "the order the user typed did not come back")
            }
        }
    }

    @Test
    fun `the finished ground is there in the smallest window and at larger text`() {
        listOf(
            Viewport(1100, 720, Density(1f, 1.5f)),
            Viewport(640, 460, Density(2f, 1.3f)),
        ).forEach { (widthDp, heightDp, density) ->
            RealStack().use { stack ->
                ComposeSceneHarness(
                    width = (widthDp * density.density).toInt(),
                    height = (heightDp * density.density).toInt(),
                    density = density,
                ) { PnpTrackerTheme(ThemeMode.LIGHT) { GameTableScreen(stack.table) } }.use { screen ->
                    val (gameId, tasks) = screen.twoTasks(stack)
                    runBlocking { stack.table.toggleTaskCompletion(gameId, CellColumnType.THREE_D, tasks.first()) }
                    screen.settle("the task is finished") {
                        stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId == tasks.first() && it.isCompletedTask }
                    }

                    val drawn = assertNotNull(screen.cellText("Ayı"), "at ${widthDp}x$heightDp dp the cell is not drawn at all")
                    assertTrue(
                        drawn.styleOver("Ayı").item.background != drawn.styleOver("Kuş").item.background,
                        "at ${widthDp}x$heightDp dp finished and unfinished work are drawn on the same ground",
                    )
                    assertTrue("×14" in drawn.text, "at ${widthDp}x$heightDp dp the count stopped being drawn")
                }
            }
        }
    }
}
