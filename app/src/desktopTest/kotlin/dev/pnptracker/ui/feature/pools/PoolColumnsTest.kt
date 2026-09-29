package dev.pnptracker.ui.feature.pools

import androidx.compose.ui.geometry.Rect
import dev.pnptracker.domain.colors.baseColors
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.feature.games.CellWork
import dev.pnptracker.ui.feature.games.GameTableRowsState
import dev.pnptracker.ui.feature.games.GameTableScreen
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A pool spread over the window's width, on the real screen through the real
 * stack.
 *
 * In the 3D pool every colour is one block — its heading and its tasks one under
 * the other — and it is the blocks that stand side by side. The other pools are
 * one run of cards, filled row by row. A narrow window has fewer columns.
 */
class PoolColumnsTest {
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

    private val black = baseColors.first { it.canonicalName == "Siyah" }.id
    private val red = baseColors.first { it.canonicalName == "Kırmızı" }.id

    /** A game with [words] written in [column], each made a task in the colour given beside it. */
    private fun makeTasks(
        stack: RealStack,
        column: CellColumnType,
        words: List<Pair<String, EntityId?>>,
    ) {
        ComposeSceneHarness(width = 1600, height = 900) { GameTableScreen(stack.table) }.use { table ->
            table.settle("the table is read") { stack.table.state.rows !is GameTableRowsState.Loading }
            runBlocking {
                stack.table.startGameComposer()
                stack.table.editGameName("Harmonies")
                stack.table.saveGame()
            }
            table.settle("the game is shown") { stack.rows().any { it.gameName == "Harmonies" } }
            val gameId = stack.gameNamed("Harmonies")
            val text = words.joinToString(" ve ") { it.first }
            stack.table.beginEditing(gameId, column)
            table.settle("the editor opens") { stack.table.state.work is CellWork.WritingText }
            stack.table.editCellText(text)
            runBlocking { stack.table.saveEditing() }
            table.settle("the words are stored") { stack.table.state.work == null }
            words.forEach { (word, color) ->
                val cell = stack.rows().first { it.gameId == gameId }.cell(column)
                val at = cell.editableText.indexOf(word)
                val selection = requireNotNull(cell.locateSelection(gameId, at, at + word.length)) { "$word is not there" }
                val made =
                    runBlocking {
                        stack.taskCreation
                            .createTasks(
                                selection = selection,
                                drafts =
                                    listOf(
                                        TaskDraft(
                                            colorIds = listOfNotNull(color),
                                            requiredQuantity = 2,
                                            trackingMode = if (color != null) TrackingMode.THREE_D_BATCH else TrackingMode.PIPELINE,
                                            notes = null,
                                        ),
                                    ),
                            ).single()
                    }
                table.settle("$word is a task") { stack.piecesOf(gameId, column).any { it.taskId == made } }
            }
        }
    }

    private fun ComposeSceneHarness.cardOf(name: String): Rect =
        nodes()
            .firstOrNull { node -> node.contentDescriptions().any { it.startsWith("$name, ") } }
            ?.boundsInRoot ?: fail("there is no card for $name")

    /** [names] in the order the pool holds them. */
    private fun inPoolOrder(
        stack: RealStack,
        poolType: PoolType,
        names: List<String>,
    ): List<String> =
        stack
            .cardsIn(poolType)
            .map { it.second.name }
            .filter { it in names }
            .distinct()

    private fun sameLeft(
        a: Rect,
        b: Rect,
    ) = abs(a.left - b.left) < 1f

    private val blackTasks = listOf("Ayı", "Kurt", "Tilki")
    private val redTasks = listOf("Kuş", "Balık")

    @Test
    fun `in a wide window each colour's tasks stand one under the other, and the colours side by side`() {
        RealStack().use { stack ->
            makeTasks(stack, CellColumnType.THREE_D, blackTasks.map { it to black } + redTasks.map { it to red })
            val pool = stack.pools.of(PoolType.THREE_D)
            ComposeSceneHarness(width = 1600, height = 1000) { PoolScreen(pool) }.use { screen ->
                screen.settle("every card is drawn") {
                    (blackTasks + redTasks).all { name ->
                        screen.nodes().any { node -> node.contentDescriptions().any { it.startsWith("$name, ") } }
                    }
                }
                // In the order the pool puts them, which is the order they are drawn in.
                val blacks = inPoolOrder(stack, PoolType.THREE_D, blackTasks).map { screen.cardOf(it) }
                val reds = inPoolOrder(stack, PoolType.THREE_D, redTasks).map { screen.cardOf(it) }

                blacks.zipWithNext().forEach { (upper, lower) ->
                    assertTrue(sameLeft(upper, lower), "two black tasks are not in one column: $upper, $lower")
                    assertTrue(lower.top >= upper.bottom, "a black task is not under the one before it: $upper, $lower")
                }
                reds.zipWithNext().forEach { (upper, lower) ->
                    assertTrue(sameLeft(upper, lower), "two red tasks are not in one column: $upper, $lower")
                    assertTrue(lower.top >= upper.bottom, "a red task is not under the one before it: $upper, $lower")
                }
                assertTrue(reds.first().left >= blacks.first().right, "the red group is not beside the black one")
                assertTrue(abs(reds.first().top - blacks.first().top) < 1f, "the two groups do not start on one line")
                // Four columns: a column is about a quarter of the width.
                assertTrue(blacks.first().width < 1600f / 3, "the cards are not laid out in four columns")
            }
        }
    }

    @Test
    fun `in a narrow window the colour groups stand one under the other`() {
        RealStack().use { stack ->
            makeTasks(stack, CellColumnType.THREE_D, blackTasks.map { it to black } + redTasks.map { it to red })
            val pool = stack.pools.of(PoolType.THREE_D)
            ComposeSceneHarness(width = 420, height = 3000) { PoolScreen(pool) }.use { screen ->
                screen.settle("every card is drawn") {
                    (blackTasks + redTasks).all { name ->
                        screen.nodes().any { node -> node.contentDescriptions().any { it.startsWith("$name, ") } }
                    }
                }
                val lastBlack = screen.cardOf(inPoolOrder(stack, PoolType.THREE_D, blackTasks).last())
                val firstRed = screen.cardOf(inPoolOrder(stack, PoolType.THREE_D, redTasks).first())

                assertTrue(sameLeft(lastBlack, firstRed), "one column, and the groups are side by side")
                assertTrue(firstRed.top > lastBlack.bottom, "the red group is not under the black one")
            }
        }
    }

    @Test
    fun `the card pool is one run of cards, four to a row in a wide window`() {
        RealStack().use { stack ->
            val names = listOf("Deste", "Jeton", "Harita", "Zarf", "Kutu")
            makeTasks(stack, CellColumnType.CARD, names.map { it to null })
            val pool = stack.pools.of(PoolType.CARD)
            ComposeSceneHarness(width = 1600, height = 1000) { PoolScreen(pool) }.use { screen ->
                screen.settle("every card is drawn") {
                    names.all { name -> screen.nodes().any { node -> node.contentDescriptions().any { it.startsWith("$name, ") } } }
                }
                val cards = inPoolOrder(stack, PoolType.CARD, names).map { screen.cardOf(it) }
                val firstRow = cards.take(4)

                firstRow.forEach { assertTrue(abs(it.top - firstRow.first().top) < 1f, "the first four cards are not on one row") }
                firstRow.zipWithNext().forEach { (left, right) ->
                    assertTrue(right.left >= left.right, "two cards of a row overlap or are out of order")
                }
                assertEquals(firstRow.first().left, cards.last().left, "the fifth card does not start the next row")
                assertTrue(cards.last().top >= firstRow.maxOf { it.bottom }, "the fifth card is not under the first row")
            }
        }
    }
}
