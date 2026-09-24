package dev.pnptracker.ui.feature.games

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.reads
import dev.pnptracker.ui.stateDescriptionOrNull
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What finishing a task costs the cell it was written in.
 *
 * A finished task used to carry the words `✓ Tamamlandı` beside its count. In a
 * cell 200 dp wide those twelve characters are most of a line: the name and the
 * count were pushed onto the next one, work written beside them fell past the
 * three lines a cell shows, and where the cut landed inside the label the cell
 * ended in `Tamaml…`. PLAN 12.5 now keeps the ground and the tick and drops the
 * word — the state is still there to see in the box the task already carries,
 * and still there to hear as the task's own state.
 *
 * So these measure the cell: how tall it is, what is still drawn in it, what is
 * written in it, and what is painted where the tick is.
 */
class CompactCompletedTaskTest {
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
                // A few more frames: the state has changed, and what is asserted
                // afterwards is what the screen drew from it — including the
                // handles, which are placed from the text layout of the frame
                // before and are therefore a frame behind the words.
                repeat(4) { render() }
                return
            }
            Thread.sleep(10)
        }
        fail("never happened: $what")
    }

    /** Harmonies, with [text] in its 3D cell and each of [words] made a task in it. */
    private fun ComposeSceneHarness.cellOf(
        stack: RealStack,
        text: String,
        words: List<String>,
    ): Pair<EntityId, List<EntityId>> {
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
        table.editCellText(text)
        render()
        runBlocking { table.saveEditing() }
        settle("the words are stored") { stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.text == text } }
        settle("the colours are read") { table.state.colors.isNotEmpty() }

        val made = mutableListOf<EntityId>()
        words.forEach { word ->
            // Counted across the whole cell, which is what the user drags over
            // and what the editor hands the controller.
            val cell =
                stack
                    .rows()
                    .first { it.gameId == gameId }
                    .cell(CellColumnType.THREE_D)
            val at = cell.editableText.indexOf(word)
            val selection =
                requireNotNull(cell.locateSelection(gameId, at, at + word.length)) {
                    "the word $word is not where it was written"
                }
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

    /** The one drawn node that holds a cell's document. */
    private fun ComposeSceneHarness.cellNode(word: String): SemanticsNode =
        nodes()
            .firstOrNull { node ->
                node.reads(SemanticsProperties.Text).orEmpty().any { word in it.text && it.spanStyles.isNotEmpty() }
            }
            ?: fail("no drawn cell holds $word")

    private fun ComposeSceneHarness.cellText(word: String): AnnotatedString =
        cellNode(word)
            .reads(SemanticsProperties.Text)
            .orEmpty()
            .first { word in it.text && it.spanStyles.isNotEmpty() }

    /** The task drawn for [name], as a reader reaches it — or null when it is not drawn at all. */
    private fun ComposeSceneHarness.taskNode(name: String): SemanticsNode? =
        spokenNodes().firstOrNull { node -> node.contentDescriptions().any { it.startsWith("$name,") } }

    /**
     * How much is painted in [region] that is not its own background.
     *
     * The corner of the region is taken as the ground: these regions are the
     * blank strip a tick is drawn in, so their corner is always empty whatever
     * the theme has made the surface.
     */
    private fun ComposeSceneHarness.inkIn(region: Rect): Int {
        val painted = pixels()
        val left = max(0, region.left.toInt())
        val top = max(0, region.top.toInt())
        val right = min(painted.width, region.right.toInt())
        val bottom = min(painted.height, region.bottom.toInt())
        if (left >= right || top >= bottom) fail("the region is not on the screen: $region")
        val ground: Color = painted[left, top]
        var ink = 0
        for (y in top until bottom) {
            for (x in left until right) {
                if (painted[x, y] != ground) ink++
            }
        }
        return ink
    }

    /** Where the tick of the task named [name] is drawn: the strip just before its word. */
    private fun ComposeSceneHarness.tickRegionOf(
        name: String,
        density: Density,
    ): Rect {
        val word =
            assertNotNull(
                taskNode(name),
                "the task $name is not drawn; the screen says ${spokenNodes().flatMap { it.contentDescriptions() }}",
            ).boundsInRoot
        val width = TICK_STRIP_DP * density.density
        return Rect(left = word.left - width, top = word.top, right = word.left - 1f, bottom = word.bottom)
    }

    private fun finish(
        stack: RealStack,
        gameId: EntityId,
        taskId: EntityId,
    ) = runBlocking { stack.table.toggleTaskCompletion(gameId, CellColumnType.THREE_D, taskId) }

    @Test
    fun `a finished task carries no written word, and is still announced as finished`() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            RealStack().use { stack ->
                ComposeSceneHarness(width = 1300, height = 900) {
                    PnpTrackerTheme(theme) { GameTableScreen(stack.table) }
                }.use { screen ->
                    val (gameId, tasks) = screen.cellOf(stack, "Ejderha ve Kuş", listOf("Ejderha", "Kuş"))
                    finish(stack, gameId, tasks.first())
                    screen.settle("the task is finished") {
                        stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId == tasks.first() && it.isCompletedTask }
                    }

                    val drawn = screen.cellText("Ejderha").text
                    assertFalse("Tamamlandı" in drawn, "the finished task is still labelled in $theme: $drawn")
                    assertFalse("Tamaml" in drawn, "a cut label is still drawn in $theme: $drawn")
                    assertFalse("✓" in drawn, "a mark is written into the document in $theme: $drawn")
                    assertFalse(
                        screen.writtenText().any { "✓" in it },
                        "something on the screen still writes the mark in $theme",
                    )
                    assertTrue("Ejderha" in drawn && "×14" in drawn, "the name or the count stopped being drawn in $theme")

                    // PLAN 12.5: heard, not written. The state belongs to the
                    // task's own node, so a reader hears it once and hears it change.
                    assertEquals(
                        "tamamlandı",
                        assertNotNull(screen.taskNode("Ejderha"), "the finished task is not drawn in $theme")
                            .stateDescriptionOrNull(),
                        "the finished task is no longer announced as finished in $theme",
                    )
                    assertEquals(
                        "sürüyor",
                        assertNotNull(screen.taskNode("Kuş"), "the unfinished task is not drawn in $theme")
                            .stateDescriptionOrNull(),
                        "the unfinished task stopped saying what it is in $theme",
                    )
                }
            }
        }
    }

    @Test
    fun `finishing a task does not make its cell any taller`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val (gameId, tasks) = screen.cellOf(stack, LONG_NAME, listOf(LONG_NAME))
                val before = screen.cellNode(LONG_NAME).boundsInRoot.height

                finish(stack, gameId, tasks.single())
                screen.settle("the task is finished") {
                    stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.isCompletedTask }
                }

                val after = screen.cellNode(LONG_NAME).boundsInRoot.height
                assertEquals(before, after, TOLERANCE, "finishing the task grew the cell: $before dp → $after dp")
            }
        }
    }

    @Test
    fun `a finished task does not crowd the work beside it out of a narrow cell`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1300, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                val (gameId, tasks) = screen.cellOf(stack, "$LONG_NAME ve Kuş", listOf(LONG_NAME, "Kuş"))
                val before = screen.cellNode(LONG_NAME).boundsInRoot.height

                finish(stack, gameId, tasks.first())
                screen.settle("the task is finished") {
                    stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.isCompletedTask }
                }

                val drawn = screen.cellText(LONG_NAME).text
                assertEquals(
                    before,
                    screen.cellNode(LONG_NAME).boundsInRoot.height,
                    TOLERANCE,
                    "the finished task took another line of the cell: $drawn",
                )
                // Both words are still reachable: a task whose name fell past
                // the lines the cell shows has no drawn word and therefore no
                // handle at all.
                assertNotNull(screen.taskNode(LONG_NAME), "the finished task is not drawn any more")
                assertNotNull(screen.taskNode("Kuş"), "the work beside the finished task was crowded out: $drawn")
                assertTrue("×14" in drawn, "the counts stopped being drawn: $drawn")
            }
        }
    }

    @Test
    fun `the box that says a task is finished draws its mark`() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            RealStack().use { stack ->
                val density = Density(1f)
                ComposeSceneHarness(width = 1300, height = 900, density = density) {
                    PnpTrackerTheme(theme) { GameTableScreen(stack.table) }
                }.use { screen ->
                    val (gameId, tasks) = screen.cellOf(stack, "Ejderha", listOf("Ejderha"))
                    val region = screen.tickRegionOf("Ejderha", density)
                    val open = screen.inkIn(region)

                    finish(stack, gameId, tasks.single())
                    screen.settle("the task is finished") {
                        stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.isCompletedTask }
                    }

                    val finished = screen.inkIn(screen.tickRegionOf("Ejderha", density))
                    assertTrue(open > 0, "nothing is drawn where the tick should be in $theme")
                    assertTrue(
                        finished > open,
                        "the box gained no mark when the task was finished in $theme: $open → $finished",
                    )
                }
            }
        }
    }

    @Test
    fun `the smallest window and larger text keep the same bargain`() {
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
                    val (gameId, tasks) = screen.cellOf(stack, LONG_NAME, listOf(LONG_NAME))
                    val before = screen.cellNode(LONG_NAME).boundsInRoot.height

                    finish(stack, gameId, tasks.single())
                    screen.settle("the task is finished") {
                        stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.isCompletedTask }
                    }

                    val drawn = screen.cellText(LONG_NAME).text
                    assertEquals(
                        before,
                        screen.cellNode(LONG_NAME).boundsInRoot.height,
                        TOLERANCE * density.density,
                        "at ${widthDp}x$heightDp dp finishing the task grew the cell",
                    )
                    assertFalse("Tamaml" in drawn, "at ${widthDp}x$heightDp dp the label is still drawn: $drawn")
                    assertEquals(
                        "tamamlandı",
                        assertNotNull(screen.taskNode(LONG_NAME), "at ${widthDp}x$heightDp dp the task is not drawn")
                            .stateDescriptionOrNull(),
                        "at ${widthDp}x$heightDp dp the task is not announced as finished",
                    )
                }
            }
        }
    }

    private companion object {
        /**
         * A name long enough to leave a line with no room for a label.
         *
         * A cell is 200 dp wide and shows three lines (`GameTableScreen`), so a
         * name of this length and its count fill the first line and reach into
         * the second — which is where the twelve characters of `✓ Tamamlandı`
         * used to land.
         */
        const val LONG_NAME = "Ejderha Yavrusu ve Kalkanı"

        /** How wide the strip a tick is drawn in is, in dp. */
        const val TICK_STRIP_DP = 20f

        /** A pixel of rounding, which a layout in dp is entitled to. */
        const val TOLERANCE = 1.5f
    }
}
