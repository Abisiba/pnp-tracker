package dev.pnptracker.ui.feature.games

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A name typed into a task column, and Enter (PLAN 12.6).
 *
 * The real table over the real database: the name is typed into the cell's
 * editor, Enter is pressed on the keyboard, and the task window opens over the
 * words without their being saved or selected first. Nothing is written until
 * `Görevi kaydet`, and then the words and the task are written together.
 */
class TaskFromEnterTest {
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

    private fun open(
        stack: RealStack,
        width: Int = 1100,
        height: Int = 720,
        density: Density = Density(1f),
    ): ComposeSceneHarness {
        val screen =
            ComposeSceneHarness(
                width = (width * density.density).toInt(),
                height = (height * density.density).toInt(),
                density = density,
            ) { GameTableScreen(stack.table) }
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

    /** Opens the cell's editor, types [draft] into it, and presses Enter on the keyboard. */
    private fun ComposeSceneHarness.typeAndEnter(
        stack: RealStack,
        gameId: EntityId,
        column: CellColumnType,
        draft: String,
    ) {
        stack.table.beginEditing(gameId, column)
        settle("the editor opens") { stack.table.state.work is CellWork.WritingText }
        stack.table.editCellText(draft)
        render()
        press(Key.Enter)
        render()
    }

    private fun composerOf(stack: RealStack): TaskComposer =
        (stack.table.state.work as? CellWork.MakingTask)?.composer ?: fail("the task window did not open: ${stack.table.state.work}")

    private fun tasksIn(stack: RealStack) = runBlocking { stack.database.taskDao().allTasksIncludingDeleted() }

    @Test
    fun `Enter on a new name opens the task window with that name, and writes nothing`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.typeAndEnter(stack, gameId, CellColumnType.THREE_D, "Ejderha")

                val composer = composerOf(stack)
                assertEquals("Ejderha", composer.name)
                assertTrue(composer.holdsColors, "a 3D task is not offered its colours")
                assertTrue(screen.spokenNodes().any { node -> node.contentDescriptions().any { it == "Görev adı: Ejderha" } })
                assertEquals(emptyList(), stack.piecesOf(gameId, CellColumnType.THREE_D), "the words were saved before the task")
                assertEquals(emptyList(), tasksIn(stack), "a task was made before it was saved")
            }
        }
    }

    @Test
    fun `saving the task writes the words and the task together, shown at once with its note`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.typeAndEnter(stack, gameId, CellColumnType.THREE_D, "Ejderha")
                stack.table.chooseTaskColor(
                    0,
                    stack.table.state.colors
                        .first()
                        .id,
                )
                stack.table.editTaskQuantity(0, "3")
                stack.table.editTaskNotes(0, NOTE)
                screen.render()
                assertTrue(screen.click("Görevi kaydet"), "there is no save in the window")
                screen.settle("the task is made") { stack.table.state.work == null && tasksIn(stack).isNotEmpty() }

                val task = tasksIn(stack).single()
                assertEquals("Ejderha", task.name)
                assertEquals(3, task.requiredQuantity)
                assertEquals(NOTE, task.notes)
                assertEquals(listOf(task.id), stack.piecesOf(gameId, CellColumnType.THREE_D).mapNotNull { it.taskId })
                assertEquals(
                    "Ejderha",
                    stack
                        .rows()
                        .single()
                        .cell(CellColumnType.THREE_D)
                        .editableText,
                )
                val texts = screen.nodes().flatMap { node -> node.reads(SemanticsProperties.Text).orEmpty().map { it.text } }
                assertTrue(texts.any { !it.startsWith("3D Baskı: ") && "Ejderha" in it && "($NOTE)" in it }, "not in its column: $texts")
                assertTrue(texts.any { it.startsWith("3D Baskı: ") && "Ejderha" in it && "($NOTE)" in it }, "not in Eksik: $texts")
                // Nothing went where it does not belong.
                assertEquals(
                    "",
                    stack
                        .rows()
                        .single()
                        .cell(CellColumnType.NOTES)
                        .editableText,
                )
                assertEquals(
                    "",
                    stack
                        .rows()
                        .single()
                        .cell(CellColumnType.BORROWED)
                        .editableText,
                )
            }
        }
    }

    @Test
    fun `giving up keeps the words in the editor and makes no task`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.typeAndEnter(stack, gameId, CellColumnType.THREE_D, "Ejderha")
                composerOf(stack)
                assertTrue(screen.click("Vazgeç"), "there is no way out of the window")
                screen.render()

                val editor =
                    stack.table.state.work as? CellWork.WritingText ?: fail("the editor did not come back: ${stack.table.state.work}")
                assertEquals("Ejderha", editor.draft, "the words were lost")
                assertEquals(emptyList(), tasksIn(stack), "a task was made")
                assertEquals(emptyList(), stack.piecesOf(gameId, CellColumnType.THREE_D), "the words were saved")
            }
        }
    }

    @Test
    fun `a card task is asked for its count and note, and no colour`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.typeAndEnter(stack, gameId, CellColumnType.CARD, "Gri token")

                val composer = composerOf(stack)
                assertEquals("Gri token", composer.name)
                assertFalse(composer.holdsColors, "a card task is offered colours")
                assertFalse(screen.spokenNodes().any { node -> node.contentDescriptions().any { it == "Renk ara" } })
                assertTrue(screen.nodes().none { node -> node.reads(SemanticsProperties.Text).orEmpty().any { it.text == "Renk ara" } })

                stack.table.editTaskQuantity(0, "12")
                stack.table.editTaskNotes(0, "arka yüz")
                screen.render()
                assertTrue(screen.click("Görevi kaydet"))
                screen.settle("the task is made") { tasksIn(stack).isNotEmpty() }
                assertEquals(TrackingMode.PIPELINE, tasksIn(stack).single().trackingMode)
                assertEquals("arka yüz", tasksIn(stack).single().notes)
            }
        }
    }

    @Test
    fun `the saved words and the tasks already there are left as they were`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                // Saved words, one of which is already a task.
                stack.table.beginEditing(gameId, CellColumnType.THREE_D)
                screen.settle("the editor opens") { stack.table.state.work is CellWork.WritingText }
                stack.table.editCellText("Kule, sur")
                runBlocking { stack.table.saveEditing() }
                screen.settle("the words are saved") { stack.table.state.work == null }
                val cell = stack.rows().single().cell(CellColumnType.THREE_D)
                val tower =
                    runBlocking {
                        stack.taskCreation
                            .createTasks(
                                requireNotNull(cell.locateSelection(gameId, 0, 4)),
                                listOf(
                                    TaskDraft(
                                        listOf(
                                            stack.table.state.colors
                                                .first()
                                                .id,
                                        ),
                                        2,
                                        TrackingMode.THREE_D_BATCH,
                                        null,
                                    ),
                                ),
                            ).single()
                    }
                screen.settle("the old task is shown") { stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId == tower } }
                val before = tasksIn(stack).single()

                screen.typeAndEnter(stack, gameId, CellColumnType.THREE_D, "Kule, sur\nEjderha")
                assertEquals("Ejderha", composerOf(stack).name)
                stack.table.chooseTaskColor(
                    0,
                    stack.table.state.colors
                        .last()
                        .id,
                )
                stack.table.editTaskQuantity(0, "1")
                screen.render()
                assertTrue(screen.click("Görevi kaydet"))
                screen.settle("the new task is made") { tasksIn(stack).size == 2 }

                assertEquals(before, tasksIn(stack).first { it.id == tower }, "the old task changed")
                assertEquals(
                    "Kule, sur\nEjderha",
                    stack
                        .rows()
                        .single()
                        .cell(CellColumnType.THREE_D)
                        .editableText,
                )
                val pieces = stack.piecesOf(gameId, CellColumnType.THREE_D)
                assertEquals(listOf("Kule", ", sur\n", "Ejderha"), pieces.map { it.text })
                assertEquals(tower, pieces.first().taskId)
            }
        }
    }

    @Test
    fun `Ctrl and Enter breaks the line and saves nothing, in every kind of cell`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                listOf(CellColumnType.THREE_D, CellColumnType.NOTES, CellColumnType.BORROWED).forEach { column ->
                    stack.table.beginEditing(gameId, column)
                    screen.settle("the editor opens") { stack.table.state.work is CellWork.WritingText }
                    stack.table.editCellText("Kutu")
                    screen.render()
                    screen.press(Key.Enter, ctrl = true)
                    screen.render()

                    val editor = stack.table.state.work as? CellWork.WritingText ?: fail("Ctrl+Enter closed the $column editor")
                    assertTrue("\n" in editor.draft, "Ctrl+Enter made no line in $column: ${editor.draft}")
                    assertEquals("Kutu", editor.draft.replace("\n", ""), "Ctrl+Enter changed the words in $column")
                    assertEquals(emptyList(), stack.piecesOf(gameId, column), "Ctrl+Enter saved $column")
                    stack.table.cancelEditing()
                    screen.render()
                }
                assertEquals(emptyList(), tasksIn(stack))
            }
        }
    }

    @Test
    fun `Enter saves the notes and the borrowed parts`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.typeAndEnter(stack, gameId, CellColumnType.NOTES, "Kutusu ezik")
                screen.settle("the notes are saved") { stack.table.state.work == null }
                screen.typeAndEnter(stack, gameId, CellColumnType.BORROWED, "Zarlar Wingspan'den")
                screen.settle("the borrowed parts are saved") { stack.table.state.work == null }

                val row = stack.rows().single()
                assertEquals("Kutusu ezik", row.cell(CellColumnType.NOTES).editableText)
                assertEquals("Zarlar Wingspan'den", row.cell(CellColumnType.BORROWED).editableText)
                assertEquals(emptyList(), tasksIn(stack), "Enter in a note made a task")
            }
        }
    }

    @Test
    fun `Enter in a task column with nothing new typed saves the cell`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                stack.table.beginEditing(gameId, CellColumnType.THREE_D)
                screen.settle("the editor opens") { stack.table.state.work is CellWork.WritingText }
                stack.table.editCellText("Kule ve sur")
                runBlocking { stack.table.saveEditing() }
                screen.settle("the words are saved") { stack.table.state.work == null }

                // Something taken away is no new name, so Enter is a save.
                screen.typeAndEnter(stack, gameId, CellColumnType.THREE_D, "Kule")
                screen.settle("the cell is saved") { stack.table.state.work == null }

                assertEquals(
                    "Kule",
                    stack
                        .rows()
                        .single()
                        .cell(CellColumnType.THREE_D)
                        .editableText,
                )
                assertEquals(emptyList(), tasksIn(stack))
            }
        }
    }

    @Test
    fun `at an ordinary window size every base colour is in sight, and the actions too`() {
        RealStack().use { stack ->
            open(stack, width = 1100, height = 720).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.typeAndEnter(stack, gameId, CellColumnType.THREE_D, "Ejderha")
                composerOf(stack)

                val save = screen.boundsOf("Görevi kaydet") ?: fail("no save")
                val colours =
                    stack.table.state.colors
                        .map { it.canonicalName }
                colours.forEach { name ->
                    val drawn =
                        screen
                            .nodes()
                            .filter { node -> node.reads(SemanticsProperties.Text).orEmpty().any { it.text == name } }
                            .map { it.boundsInRoot }
                            .firstOrNull { it.height > 0f } ?: fail("$name is not drawn")
                    assertTrue(drawn.bottom <= save.top, "$name is below the actions, out of sight: $drawn, save at $save")
                }
            }
        }
    }

    @Test
    fun `in a small window the colours scroll and the actions stay in sight`() {
        RealStack().use { stack ->
            open(stack, width = 640, height = 460, density = Density(2f, 1.3f)).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.typeAndEnter(stack, gameId, CellColumnType.THREE_D, "Ejderha")
                composerOf(stack)

                val heightPx = 460 * 2f
                val save = screen.boundsOf("Görevi kaydet") ?: fail("no save")
                val discard = screen.boundsOf("Vazgeç") ?: fail("no discard")
                assertTrue(save.height > 0f && save.bottom <= heightPx, "the save is out of sight: $save")
                assertTrue(discard.height > 0f && discard.bottom <= heightPx, "the discard is out of sight: $discard")
                // The last colour is reachable, below the fold, by scrolling.
                val last =
                    stack.table.state.colors
                        .last()
                assertNotNull(
                    screen.nodes().firstOrNull { node ->
                        node.reads(SemanticsProperties.Text).orEmpty().any { it.text == last.canonicalName }
                    },
                    "the last colour is not in the window at all",
                )
                stack.table.chooseTaskColor(0, last.id)
                assertNull(composerOf(stack).failure)
            }
        }
    }

    private companion object {
        const val NOTE = "boyası kuruyor"
    }
}
