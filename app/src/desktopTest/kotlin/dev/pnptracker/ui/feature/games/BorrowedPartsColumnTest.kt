package dev.pnptracker.ui.feature.games

import androidx.compose.ui.semantics.SemanticsProperties
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.reads
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The borrowed parts column (PLAN 12.19), on the real screen through the real stack.
 *
 * A note of its own beside the notes: written in the table like any cell, kept
 * when the application is opened again, and never a place a task can be made.
 */
class BorrowedPartsColumnTest {
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

    private fun open(stack: RealStack): Pair<ComposeSceneHarness, GameTableController> {
        val table = stack.tableControllerWith()
        val screen = ComposeSceneHarness(width = 1800, height = 900) { GameTableScreen(table) }
        screen.settle("the table is read") { table.state.rows !is GameTableRowsState.Loading }
        return screen to table
    }

    private fun rowsOf(table: GameTableController) = (table.state.rows as? GameTableRowsState.Content)?.rows.orEmpty()

    private fun makeGame(
        screen: ComposeSceneHarness,
        table: GameTableController,
    ): EntityId {
        runBlocking {
            table.startGameComposer()
            table.editGameName("Harmonies")
            table.saveGame()
        }
        screen.settle("the game is shown") { rowsOf(table).any { it.gameName == "Harmonies" } }
        return rowsOf(table).first { it.gameName == "Harmonies" }.gameId
    }

    private fun write(
        screen: ComposeSceneHarness,
        table: GameTableController,
        gameId: EntityId,
        column: CellColumnType,
        text: String,
    ) {
        table.beginEditing(gameId, column)
        screen.settle("the editor opens") { table.state.work is CellWork.WritingText }
        table.editCellText(text)
        screen.render()
        runBlocking { table.saveEditing() }
        screen.settle("the text is stored") { table.state.work == null }
    }

    private fun ComposeSceneHarness.headings(): List<String> {
        val names = listOf("Oyun", "Eksik", "3D Baskı", "Kart", "Mukavva", "Özel", "Ödünç Parçalar", "Notlar")
        return nodes()
            .mapNotNull { node ->
                val text =
                    node
                        .reads(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text }
                        .firstOrNull { it in names }
                text?.let { it to node.boundsInRoot }
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, bounds) -> bounds.minBy { it.top } }
            .entries
            .sortedBy { it.value.left }
            .map { it.key }
    }

    @Test
    fun `the borrowed parts have a column of their own, just before the notes`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                // The headings are drawn over a table with a game in it; an empty
                // library shows what to do first instead.
                makeGame(screen, table)
                val headings = screen.headings()
                val borrowed = headings.indexOf("Ödünç Parçalar")
                assertTrue(borrowed >= 0, "there is no borrowed parts column: $headings")
                assertEquals("Notlar", headings.getOrNull(borrowed + 1), "the notes are not right after it: $headings")
                assertEquals("Özel", headings.getOrNull(borrowed - 1), "the special column is not right before it: $headings")
            }
        }
    }

    @Test
    fun `what is written there is kept apart from the notes and is there when the table is opened again`() {
        RealStack().use { stack ->
            val gameId: EntityId
            val (first, table) = open(stack)
            first.use {
                gameId = makeGame(first, table)
                write(first, table, gameId, CellColumnType.BORROWED, BORROWED)
                write(first, table, gameId, CellColumnType.NOTES, NOTES)
                assertTrue(first.spokenNodes().any { node -> node.contentDescriptions().any { BORROWED in it } })
            }

            // A new controller and a new screen over the same database, which is
            // what opening the application again is.
            val (second, reopened) = open(stack)
            second.use {
                second.settle("the game is read") { rowsOf(reopened).isNotEmpty() }
                val row = rowsOf(reopened).single { it.gameId == gameId }
                assertEquals(BORROWED, row.cell(CellColumnType.BORROWED).editableText)
                assertEquals(NOTES, row.cell(CellColumnType.NOTES).editableText)
                assertTrue(
                    second.spokenNodes().any { node -> node.contentDescriptions().any { it == "Ödünç Parçalar hücresi: $BORROWED" } },
                    "the borrowed parts are not drawn in their own column after opening again",
                )
            }
        }
    }

    @Test
    fun `no task can be made in the borrowed parts`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                val gameId = makeGame(screen, table)
                write(screen, table, gameId, CellColumnType.BORROWED, BORROWED)

                table.beginEditing(gameId, CellColumnType.BORROWED)
                screen.settle("the editor opens") { table.state.work is CellWork.WritingText }
                table.beginTaskComposer(0, 4)
                screen.render()

                assertEquals(
                    TaskFromTextFailure.CELL_DOES_NOT_HOLD_TASKS,
                    (table.state.work as? CellWork.WritingText)?.selectionFailure,
                )
                table.cancelEditing()
                screen.render()
                assertTrue(
                    rowsOf(table)
                        .single()
                        .cell(CellColumnType.BORROWED)
                        .segments
                        .none { it.taskId != null },
                    "a task was made in the borrowed parts",
                )
            }
        }
    }

    private companion object {
        const val BORROWED = "Kule zarları Wingspan'den"
        const val NOTES = "Kutusu ezik"
    }
}
