package dev.pnptracker.ui.feature.games

import androidx.compose.ui.semantics.SemanticsProperties
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.reads
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The empty lines a user leaves in the `Notlar` column.
 *
 * Written through the real editor into the real database, then read back by a
 * table that knows nothing of the first one — what a restart of the application
 * does — and looked at on the screen.
 */
class NotesBlankLinesTest {
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

    private val written = "Birinci paragraf\n\nİkinci paragraf\n\n\nÜçüncü paragraf"

    @Test
    fun `the empty lines of a note are kept by the save and drawn again after the table is read afresh`() {
        RealStack().use { stack ->
            ComposeSceneHarness(width = 1600, height = 900) { GameTableScreen(stack.table) }.use { screen ->
                screen.settle("the table is read") { stack.table.state.rows !is GameTableRowsState.Loading }
                runBlocking {
                    stack.table.startGameComposer()
                    stack.table.editGameName("Harmonies")
                    stack.table.saveGame()
                }
                screen.settle("the game is shown") { stack.rows().any { it.gameName == "Harmonies" } }
                val gameId = stack.gameNamed("Harmonies")
                stack.table.beginEditing(gameId, CellColumnType.NOTES)
                screen.settle("the editor opens") { stack.table.state.work is CellWork.WritingText }
                stack.table.editCellText(written)
                runBlocking { stack.table.saveEditing() }
                screen.settle("the note is stored") { stack.table.state.work == null }
            }

            // A table that has never seen the note, reading it from the database.
            val afresh = stack.tableControllerWith()
            ComposeSceneHarness(width = 1600, height = 900) { GameTableScreen(afresh) }.use { screen ->
                screen.settle("the note is read back") {
                    (afresh.state.rows as? GameTableRowsState.Content)?.rows?.any { row ->
                        row.cell(CellColumnType.NOTES).segments.any { "Üçüncü" in it.text }
                    } == true
                }
                val stored =
                    (afresh.state.rows as GameTableRowsState.Content)
                        .rows
                        .single()
                        .cell(CellColumnType.NOTES)
                        .editableText
                assertEquals(written, stored, "the save changed the note")

                val lines = screen.writtenText()
                val from = lines.indexOf("Birinci paragraf")
                assertTrue(from >= 0, "the note is not drawn: $lines")
                assertEquals(
                    listOf("Birinci paragraf", "", "İkinci paragraf", "", "", "Üçüncü paragraf"),
                    lines.subList(from, from + 6),
                    "the empty lines were run together when the note was drawn",
                )

                // And they take room: the paragraphs are further apart than lines
                // that follow one another directly.
                val first = screen.boundsOfText("Birinci paragraf")
                val second = screen.boundsOfText("İkinci paragraf")
                assertTrue(
                    second.top - first.top > 1.5f * first.height,
                    "the empty line takes no room on the screen ($first, $second)",
                )
            }
        }
    }

    private fun ComposeSceneHarness.boundsOfText(text: String) =
        nodes()
            .firstOrNull { node -> node.reads(SemanticsProperties.Text).orEmpty().any { it.text == text } }
            ?.boundsInRoot ?: fail("`$text` is not drawn")
}
