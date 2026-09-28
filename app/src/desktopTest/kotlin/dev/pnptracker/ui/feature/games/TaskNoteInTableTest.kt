package dev.pnptracker.ui.feature.games

import androidx.compose.ui.semantics.SemanticsProperties
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.reads
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A task's note, drawn in brackets beside the task (PLAN 12.6).
 *
 * On the real screen through the real stack: the note is stored on the task, so
 * what is checked is where it is drawn — in the task's own column and in
 * `Eksik` — and where it is not written: the cell's document, the notes and the
 * borrowed parts.
 */
class TaskNoteInTableTest {
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

    private fun open(stack: RealStack): ComposeSceneHarness {
        val screen = ComposeSceneHarness(width = 2000, height = 900) { GameTableScreen(stack.table) }
        screen.settle("the table is read") { stack.table.state.rows !is GameTableRowsState.Loading }
        screen.settle("the colours are read") {
            stack.table.state.colors
                .isNotEmpty()
        }
        return screen
    }

    private fun ComposeSceneHarness.makeGame(stack: RealStack): EntityId {
        runBlocking {
            stack.table.startGameComposer()
            stack.table.editGameName("Harmonies")
            stack.table.saveGame()
        }
        settle("the game is shown") { stack.rows().any { it.gameName == "Harmonies" } }
        return stack.gameNamed("Harmonies")
    }

    /** Writes [word] in [column] and makes it a task carrying [note]. */
    private fun ComposeSceneHarness.taskWithNote(
        stack: RealStack,
        gameId: EntityId,
        column: CellColumnType,
        word: String,
        note: String?,
    ) {
        val table = stack.table
        table.beginEditing(gameId, column)
        settle("the editor opens") { table.state.work is CellWork.WritingText }
        table.editCellText(word)
        render()
        runBlocking { table.saveEditing() }
        settle("the word is stored") { stack.piecesOf(gameId, column).any { it.text == word } }
        settle("the editor closes") { table.state.work == null }
        val cell = stack.rows().first { it.gameId == gameId }.cell(column)
        val selection = requireNotNull(cell.locateSelection(gameId, 0, word.length))
        val threeD = column == CellColumnType.THREE_D
        val made =
            runBlocking {
                stack.taskCreation
                    .createTasks(
                        selection = selection,
                        drafts =
                            listOf(
                                TaskDraft(
                                    colorIds =
                                        if (threeD) {
                                            listOf(
                                                table.state.colors
                                                    .first()
                                                    .id,
                                            )
                                        } else {
                                            emptyList()
                                        },
                                    requiredQuantity = 3,
                                    trackingMode = if (threeD) TrackingMode.THREE_D_BATCH else TrackingMode.PIPELINE,
                                    notes = note,
                                ),
                            ),
                    ).single()
            }
        settle("$word is a task") { stack.piecesOf(gameId, column).any { it.taskId == made } }
    }

    private fun ComposeSceneHarness.drawnTexts(): List<String> =
        nodes().flatMap { node -> node.reads(SemanticsProperties.Text).orEmpty().map { it.text } }

    @Test
    fun `the note is drawn in brackets beside its task, in its column and in Eksik`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.taskWithNote(stack, gameId, CellColumnType.THREE_D, "Ejderha", NOTE)
                screen.taskWithNote(stack, gameId, CellColumnType.CARD, "Gri token", CARD_NOTE)
                screen.render()

                val texts = screen.drawnTexts()
                val threeD = texts.firstOrNull { "Ejderha" in it && !it.startsWith("3D Baskı: ") } ?: fail("no 3D cell: $texts")
                assertTrue("($NOTE)" in threeD, "the note is not beside the task in its column: $threeD")
                assertTrue(threeD.indexOf("($NOTE)") > threeD.indexOf("Ejderha"), "the note is not after the name: $threeD")
                val card = texts.firstOrNull { "Gri token" in it && !it.startsWith("Kart: ") } ?: fail("no card cell: $texts")
                assertTrue("($CARD_NOTE)" in card, "the note is not beside the card task: $card")

                val missing3D = texts.firstOrNull { it.startsWith("3D Baskı: ") } ?: fail("no 3D line in Eksik: $texts")
                val missingCard = texts.firstOrNull { it.startsWith("Kart: ") } ?: fail("no card line in Eksik: $texts")
                assertTrue("Ejderha" in missing3D && "($NOTE)" in missing3D, "Eksik does not show the note the same way: $missing3D")
                assertTrue("($CARD_NOTE)" in missingCard, "Eksik does not show the card note: $missingCard")

                // Heard as well as seen.
                val spoken = screen.spokenNodes().flatMap { it.contentDescriptions() }
                assertTrue(spoken.any { it.startsWith("3D Baskı hücresi: ") && NOTE in it }, "the note is not spoken: $spoken")
            }
        }
    }

    @Test
    fun `the note is written nowhere but the task`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.taskWithNote(stack, gameId, CellColumnType.THREE_D, "Ejderha", NOTE)

                val row = stack.rows().single { it.gameId == gameId }
                assertEquals("", row.cell(CellColumnType.NOTES).editableText, "the note went into the notes")
                assertEquals("", row.cell(CellColumnType.BORROWED).editableText, "the note went into the borrowed parts")
                assertEquals("Ejderha", row.cell(CellColumnType.THREE_D).editableText, "the note went into the cell's own text")
                val stored =
                    runBlocking {
                        stack.database
                            .taskDao()
                            .allTasksIncludingDeleted()
                            .single()
                    }
                assertEquals(NOTE, stored.notes)

                // And the editor still shows the document, and only the document.
                stack.table.beginEditing(gameId, CellColumnType.THREE_D)
                screen.settle("the editor opens") { stack.table.state.work is CellWork.WritingText }
                assertEquals("Ejderha", (stack.table.state.work as CellWork.WritingText).draft)
                assertFalse(screen.drawnTexts().any { it == "Ejderha ($NOTE)" }, "the editor draws the note as text")
            }
        }
    }

    @Test
    fun `a task without a note draws no brackets`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.taskWithNote(stack, gameId, CellColumnType.THREE_D, "Ejderha", null)
                screen.render()

                val drawn = screen.drawnTexts().filter { "Ejderha" in it }
                assertTrue(drawn.isNotEmpty())
                assertTrue(drawn.none { "(" in it }, "brackets were drawn with nothing in them: $drawn")
            }
        }
    }

    private companion object {
        const val NOTE = "boyası kuruyor"
        const val CARD_NOTE = "arka yüzü eksik"
    }
}
