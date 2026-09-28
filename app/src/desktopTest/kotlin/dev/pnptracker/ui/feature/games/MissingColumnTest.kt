package dev.pnptracker.ui.feature.games

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import dev.pnptracker.domain.games.DEFAULT_CELL_COLUMN_WIDTH_DP
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.reads
import dev.pnptracker.ui.theme.opaqueColorOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The `Eksik` column (PLAN 12.20), on the real screen through the real stack.
 *
 * A view of the tasks the cells already hold: what is unfinished in the four
 * production columns, drawn together, following every finish and reopen made in
 * the task's own column, and never a task or a row of its own.
 */
class MissingColumnTest {
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

    /** Writes [text] in [column] and makes each of [words] a task there. */
    private fun ComposeSceneHarness.tasksIn(
        stack: RealStack,
        gameId: EntityId,
        column: CellColumnType,
        text: String,
        words: List<String>,
        colored: Boolean,
    ): List<EntityId> {
        val table = stack.table
        table.beginEditing(gameId, column)
        settle("the editor opens") { table.state.work is CellWork.WritingText }
        table.editCellText(text)
        render()
        runBlocking { table.saveEditing() }
        settle("the words are stored") { stack.piecesOf(gameId, column).any { it.text == text } }
        settle("the editor closes") { table.state.work == null }
        return words.map { word ->
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
                                        colorIds =
                                            if (colored) {
                                                listOf(
                                                    table.state.colors
                                                        .first()
                                                        .id,
                                                )
                                            } else {
                                                emptyList()
                                            },
                                        requiredQuantity = 3,
                                        trackingMode = if (colored) TrackingMode.THREE_D_BATCH else TrackingMode.PIPELINE,
                                        notes = null,
                                    ),
                                ),
                        ).single()
                }
            settle("$word is a task") { stack.piecesOf(gameId, column).any { it.taskId == made } }
            made
        }
    }

    private fun ComposeSceneHarness.missingDescription(): String =
        spokenNodes()
            .flatMap { it.contentDescriptions() }
            .firstOrNull { it.startsWith("Eksik hücresi") }
            ?: fail("there is no Eksik cell")

    private fun ComposeSceneHarness.missingLine(label: String): AnnotatedString? =
        nodes()
            .flatMap { it.reads(SemanticsProperties.Text).orEmpty() }
            .firstOrNull { it.text.startsWith("$label: ") }

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
    fun `the columns are in the order the table is read in`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                screen.makeGame(stack)

                assertEquals(
                    listOf("Oyun", "Eksik", "3D Baskı", "Kart", "Mukavva", "Özel", "Ödünç Parçalar", "Notlar"),
                    screen.headings(),
                )
            }
        }
    }

    @Test
    fun `the unfinished tasks of every production column are shown together, and nothing else`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.tasksIn(stack, gameId, CellColumnType.THREE_D, "Ejderha ve Kule", listOf("Ejderha", "Kule"), colored = true)
                screen.tasksIn(stack, gameId, CellColumnType.CARD, "Gri token destesi", listOf("Gri token"), colored = false)
                screen.render()

                val said = screen.missingDescription()
                listOf("Ejderha", "Kule", "Gri token").forEach { assertTrue(it in said, "$it is not in Eksik: $said") }
                // The plain text around the tasks stays in its own cell.
                assertFalse(" ve " in said, "plain text was copied into Eksik: $said")
                assertFalse("destesi" in said, "plain text was copied into Eksik: $said")
                assertTrue(screen.missingLine("3D Baskı") != null, "the 3D tasks are not led by their column")
                assertTrue(screen.missingLine("Kart") != null, "the card tasks are not led by their column")
            }
        }
    }

    @Test
    fun `a 3D task is drawn in its own colour`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.tasksIn(stack, gameId, CellColumnType.THREE_D, "Ejderha", listOf("Ejderha"), colored = true)
                screen.render()

                val colour =
                    stack
                        .rows()
                        .single()
                        .cell(CellColumnType.THREE_D)
                        .segments
                        .single { it.isTask }
                        .colors
                        .single()
                val line = screen.missingLine("3D Baskı") ?: fail("the 3D tasks are not in Eksik")
                val at = line.text.indexOf("Ejderha")
                val painted =
                    line.spanStyles
                        .filter { it.start <= at && it.end > at }
                        .map { it.item.background }
                assertTrue(
                    painted.any { it == opaqueColorOf(colour.hex) },
                    "the task is not drawn in ${colour.hex}: $painted",
                )
            }
        }
    }

    @Test
    fun `finishing a task in its own column takes it out at once, and reopening it brings it back`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                val (dragon, _) =
                    screen.tasksIn(stack, gameId, CellColumnType.THREE_D, "Ejderha ve Kule", listOf("Ejderha", "Kule"), colored = true)
                val tasksBefore =
                    runBlocking {
                        stack.database
                            .taskDao()
                            .allTasksIncludingDeleted()
                            .map { it.id }
                            .toSet()
                    }
                screen.render()
                assertTrue("Ejderha" in screen.missingDescription())

                runBlocking { stack.table.toggleTaskCompletion(gameId, CellColumnType.THREE_D, dragon) }
                screen.settle("the task is finished") {
                    stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId == dragon && it.isCompletedTask }
                }
                assertFalse("Ejderha" in screen.missingDescription(), "a finished task is still missing")
                assertTrue("Kule" in screen.missingDescription(), "the unfinished one went with it")
                // Still in its own cell, finished, exactly where it was.
                assertTrue(stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId == dragon })

                runBlocking { stack.table.toggleTaskCompletion(gameId, CellColumnType.THREE_D, dragon) }
                screen.settle("the task is open again") {
                    stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.taskId == dragon && !it.isCompletedTask }
                }
                assertTrue("Ejderha" in screen.missingDescription(), "a reopened task did not come back")

                val tasksAfter =
                    runBlocking {
                        stack.database
                            .taskDao()
                            .allTasksIncludingDeleted()
                            .map { it.id }
                            .toSet()
                    }
                assertEquals(tasksBefore, tasksAfter, "showing Eksik made or lost a task")
            }
        }
    }

    @Test
    fun `a game with nothing left to do has an empty Eksik cell`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                val (only) = screen.tasksIn(stack, gameId, CellColumnType.THREE_D, "Ejderha", listOf("Ejderha"), colored = true)

                runBlocking { stack.table.toggleTaskCompletion(gameId, CellColumnType.THREE_D, only) }
                screen.settle("the task is finished") { stack.piecesOf(gameId, CellColumnType.THREE_D).any { it.isCompletedTask } }

                assertEquals("Eksik hücresi boş", screen.missingDescription())
            }
        }
    }

    @Test
    fun `a long list of missing work widens the column like any other`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val gameId = screen.makeGame(stack)
                screen.tasksIn(
                    stack,
                    gameId,
                    CellColumnType.THREE_D,
                    "Ejderha yavrusu, kule mazgalları, sur kapısı",
                    listOf("Ejderha yavrusu", "kule mazgalları", "sur kapısı"),
                    colored = true,
                )
                screen.render()
                screen.render()

                val cell =
                    screen
                        .spokenNodes()
                        .firstOrNull { node -> node.contentDescriptions().any { it.startsWith("Eksik hücresi: ") } }
                        ?.boundsInRoot ?: fail("there is no Eksik cell with work in it")
                assertTrue(cell.width > DEFAULT_CELL_COLUMN_WIDTH_DP + 2f, "the Eksik column did not grow: $cell")
                assertTrue(stack.table.state.sizes.isDefault, "fitting Eksik wrote a size down")
            }
        }
    }
}
