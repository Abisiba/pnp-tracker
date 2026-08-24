package dev.pnptracker.ui.feature.games

import dev.pnptracker.data.repository.CellTextEditing
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.data.repository.GameTableSource
import dev.pnptracker.domain.games.CellPreview
import dev.pnptracker.domain.games.CellSegmentPreview
import dev.pnptracker.domain.games.CellSummary
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The table's three views, and adding a game to it.
 *
 * The views are the thing most of these are about. PLAN 12.4 makes them views of
 * one table rather than three screens, so what has to be true is that moving
 * between them reads nothing new and writes nothing at all — which is why the
 * source below counts how often it is asked for anything.
 */
class GameTableControllerTest {
    /** Hands out whatever rows a test gives it, and counts who asked. */
    private class FakeTable(
        rows: List<GameTableRow> = emptyList(),
    ) : GameTableSource {
        val rows = MutableStateFlow(rows)
        var subscriptions: Int = 0
            private set

        override fun observeTable(): Flow<List<GameTableRow>> {
            subscriptions++
            return rows
        }
    }

    /** Records what was asked of it; nothing here reaches a database. */
    private class FakeSetup(
        private val failure: GameSetupFailure? = null,
    ) : GameSetup {
        val createdNames = mutableListOf<String>()
        val openedCells = mutableListOf<Pair<EntityId, CellColumnType>>()
        var completionChanges: Int = 0
            private set

        override fun observeGames(): Flow<List<GameSummary>> = MutableStateFlow(emptyList())

        override fun observeCells(gameId: EntityId): Flow<List<CellSummary>> = MutableStateFlow(emptyList())

        override suspend fun createGame(name: String): EntityId {
            failure?.let { throw GameSetupException(it) }
            createdNames += name
            return IdGenerator.Random.newId()
        }

        override suspend fun openCell(
            gameId: EntityId,
            columnType: CellColumnType,
        ): EntityId {
            openedCells += gameId to columnType
            return IdGenerator.Random.newId()
        }

        override suspend fun setGameCompleted(
            gameId: EntityId,
            isCompleted: Boolean,
        ) {
            completionChanges++
        }
    }

    private fun row(
        name: String,
        isCompleted: Boolean = false,
        cells: Map<CellColumnType, List<CellSegmentPreview>> = emptyMap(),
        cellIds: Set<CellColumnType> = cells.keys,
    ) = GameTableRow(
        gameId = IdGenerator.Random.newId(),
        gameName = name,
        isCompleted = isCompleted,
        cells =
            CellColumnType.entries.map { columnType ->
                CellPreview(
                    columnType = columnType,
                    cellId = if (columnType in cellIds) IdGenerator.Random.newId() else null,
                    segments = cells[columnType].orEmpty(),
                )
            },
    )

    /** Starts the collection and lets the first list land before asserting. */
    private suspend fun CoroutineScope.collect(controller: GameTableController): Job {
        val job = launch { controller.observeTable() }
        yield()
        return job
    }

    /** Lets a value pushed into the source reach the controller. */
    private suspend fun settle() = yield()

    /** Records what was written, and can be told to refuse. */
    private class FakeCells(
        private val failure: CellTextFailure? = null,
    ) : CellTextEditing {
        val saved = mutableListOf<Triple<EntityId, CellColumnType, String>>()

        override suspend fun savePlainText(
            gameId: EntityId,
            columnType: CellColumnType,
            exactText: String,
        ): Boolean {
            failure?.let { throw CellTextException(it) }
            saved += Triple(gameId, columnType, exactText)
            return true
        }
    }

    private fun controllerOf(
        table: FakeTable,
        setup: FakeSetup = FakeSetup(),
        cells: FakeCells = FakeCells(),
    ) = GameTableController(table, setup, cells)

    private fun visibleNames(controller: GameTableController): List<String> =
        assertIs<GameTableRowsState.Content>(controller.state.rows).rows.map { it.gameName }

    // ------------------------------------------------------------ the views

    @Test
    fun `the table opens on what is still being made`() =
        runBlocking<Unit> {
            val controller = controllerOf(FakeTable())

            assertEquals(GameTableView.ONGOING, controller.state.view)
        }

    @Test
    fun `the ongoing view shows only games that are not finished`() =
        runBlocking<Unit> {
            val table = FakeTable(listOf(row("Harmonies"), row("Wingspan", isCompleted = true)))
            val controller = controllerOf(table)
            val collecting = collect(controller)

            assertEquals(listOf("Harmonies"), visibleNames(controller))
            collecting.cancelAndJoin()
        }

    @Test
    fun `the completed view shows only games the user has finished`() =
        runBlocking<Unit> {
            val table = FakeTable(listOf(row("Harmonies"), row("Wingspan", isCompleted = true)))
            val controller = controllerOf(table)
            val collecting = collect(controller)

            controller.showView(GameTableView.COMPLETED)

            assertEquals(listOf("Wingspan"), visibleNames(controller))
            collecting.cancelAndJoin()
        }

    @Test
    fun `the all view shows both together`() =
        runBlocking<Unit> {
            val table = FakeTable(listOf(row("Harmonies"), row("Wingspan", isCompleted = true)))
            val controller = controllerOf(table)
            val collecting = collect(controller)

            controller.showView(GameTableView.ALL)

            assertEquals(listOf("Harmonies", "Wingspan"), visibleNames(controller))
            collecting.cancelAndJoin()
        }

    @Test
    fun `changing the view writes nothing and reads nothing new`() =
        runBlocking<Unit> {
            // PLAN 12.4: three views of one table. If switching cost a query or a
            // write they would be three screens wearing one name.
            val setup = FakeSetup()
            val table = FakeTable(listOf(row("Harmonies"), row("Wingspan", isCompleted = true)))
            val controller = controllerOf(table, setup)
            val collecting = collect(controller)
            val subscriptionsAfterFirstRead = table.subscriptions

            GameTableView.entries.forEach(controller::showView)
            controller.showView(GameTableView.ONGOING)

            assertEquals(subscriptionsAfterFirstRead, table.subscriptions, "switching view read the table again")
            assertEquals(emptyList(), setup.createdNames)
            assertEquals(emptyList(), setup.openedCells)
            assertEquals(0, setup.completionChanges, "switching view changed a game")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a deleted game is in no view at all`() =
        runBlocking<Unit> {
            // Deletion is not a view: the source hands out active games only, so
            // there is nowhere for a deleted one to reappear.
            val table = FakeTable(listOf(row("Harmonies"), row("Wingspan", isCompleted = true)))
            val controller = controllerOf(table)
            val collecting = collect(controller)

            table.rows.value = emptyList()
            settle()

            GameTableView.entries.forEach { view ->
                controller.showView(view)
                assertIs<GameTableRowsState.Empty>(controller.state.rows, "a deleted game survived in $view")
            }
            collecting.cancelAndJoin()
        }

    @Test
    fun `an empty view says whether the library is empty or only this view is`() =
        runBlocking<Unit> {
            val table = FakeTable(listOf(row("Harmonies")))
            val controller = controllerOf(table)
            val collecting = collect(controller)

            controller.showView(GameTableView.COMPLETED)
            val onlyThisView = assertIs<GameTableRowsState.Empty>(controller.state.rows)
            assertTrue(onlyThisView.hasGamesInOtherViews)
            assertEquals(GameTableView.COMPLETED, onlyThisView.view)

            table.rows.value = emptyList()
            settle()
            assertFalse(assertIs<GameTableRowsState.Empty>(controller.state.rows).hasGamesInOtherViews)
            collecting.cancelAndJoin()
        }

    @Test
    fun `a game finished elsewhere moves between the views without a reload`() =
        runBlocking<Unit> {
            val table = FakeTable(listOf(row("Harmonies")))
            val controller = controllerOf(table)
            val collecting = collect(controller)
            assertEquals(listOf("Harmonies"), visibleNames(controller))

            table.rows.value = listOf(row("Harmonies", isCompleted = true))
            settle()

            assertIs<GameTableRowsState.Empty>(controller.state.rows)
            controller.showView(GameTableView.COMPLETED)
            assertEquals(listOf("Harmonies"), visibleNames(controller))
            collecting.cancelAndJoin()
        }

    @Test
    fun `a change to one game reaches the table without disturbing the view`() =
        runBlocking<Unit> {
            val table = FakeTable(listOf(row("Harmonies"), row("Wingspan")))
            val controller = controllerOf(table)
            val collecting = collect(controller)
            controller.showView(GameTableView.ALL)

            table.rows.value = listOf(row("Harmonies"), row("Wingspan"), row("Zeus"))
            settle()

            assertEquals(listOf("Harmonies", "Wingspan", "Zeus"), visibleNames(controller))
            assertEquals(GameTableView.ALL, controller.state.view, "another game's change moved the view")
            collecting.cancelAndJoin()
        }

    // ------------------------------------------------------------ the rows

    @Test
    fun `every row carries all five columns, whether or not the cells exist`() =
        runBlocking<Unit> {
            // PLAN 5.4 gives a game at most one cell per column, so a column
            // nobody has written in has none. The table still shows the slot.
            val table =
                FakeTable(
                    listOf(
                        row(
                            "Harmonies",
                            cells = mapOf(CellColumnType.THREE_D to listOf(CellSegmentPreview(null, "40 gri"))),
                        ),
                    ),
                )
            val controller = controllerOf(table)
            val collecting = collect(controller)

            val row = assertIs<GameTableRowsState.Content>(controller.state.rows).rows.single()
            assertEquals(CellColumnType.entries, row.cells.map { it.columnType })
            assertFalse(row.cell(CellColumnType.THREE_D).isEmpty)
            CellColumnType.entries.filterNot { it == CellColumnType.THREE_D }.forEach { columnType ->
                val cell = row.cell(columnType)
                assertTrue(cell.isEmpty, "$columnType was not empty")
                assertNull(cell.cellId, "$columnType had a cell nobody opened")
            }
            collecting.cancelAndJoin()
        }

    // --------------------------------------------------------- adding a game

    @Test
    fun `a game is added with the name that was typed`() =
        runBlocking<Unit> {
            val setup = FakeSetup()
            val controller = controllerOf(FakeTable(), setup)

            controller.startGameComposer()
            controller.editGameName("Harmonies")
            controller.saveGame()

            assertEquals(listOf("Harmonies"), setup.createdNames)
            assertNull(controller.state.gameComposer, "the form stayed open after saving")
        }

    @Test
    fun `a name that is only spaces cannot be saved`() =
        runBlocking<Unit> {
            val setup = FakeSetup()
            val controller = controllerOf(FakeTable(), setup)

            controller.startGameComposer()
            controller.editGameName("   ")

            assertFalse(assertIs<NameComposer>(controller.state.gameComposer).canSave)
            controller.saveGame()
            assertEquals(emptyList(), setup.createdNames, "a blank name reached the database")
            assertIs<NameComposer>(controller.state.gameComposer, "the form closed on a name it refused")
        }

    @Test
    fun `an empty name cannot be saved either`() =
        runBlocking<Unit> {
            val setup = FakeSetup()
            val controller = controllerOf(FakeTable(), setup)

            controller.startGameComposer()
            controller.saveGame()

            assertEquals(emptyList(), setup.createdNames)
        }

    @Test
    fun `adding a game opens no cells`() =
        runBlocking<Unit> {
            // Five rows written for a game nobody has typed in yet would be five
            // rows saying something nobody said.
            val setup = FakeSetup()
            val controller = controllerOf(FakeTable(), setup)

            controller.startGameComposer()
            controller.editGameName("Harmonies")
            controller.saveGame()

            assertEquals(emptyList(), setup.openedCells, "creating a game opened cells nobody asked for")
        }

    @Test
    fun `giving up on the form writes nothing`() =
        runBlocking<Unit> {
            val setup = FakeSetup()
            val controller = controllerOf(FakeTable(), setup)

            controller.startGameComposer()
            controller.editGameName("Harmonies")
            controller.cancelGameComposer()

            assertNull(controller.state.gameComposer)
            assertEquals(emptyList(), setup.createdNames)
        }

    @Test
    fun `a refused save is reported and leaves the form open`() =
        runBlocking<Unit> {
            val setup = FakeSetup(failure = GameSetupFailure.COULD_NOT_SAVE)
            val controller = controllerOf(FakeTable(), setup)

            controller.startGameComposer()
            controller.editGameName("Harmonies")
            controller.saveGame()

            assertEquals(GameSetupFailure.COULD_NOT_SAVE, controller.state.failure)
            assertIs<NameComposer>(controller.state.gameComposer, "the typed name was thrown away")
            assertFalse(controller.isSaving)
        }

    @Test
    fun `two games may share a name`() =
        runBlocking<Unit> {
            // PLAN 5.2 gives every game its own identity; the name is a label.
            val setup = FakeSetup()
            val controller = controllerOf(FakeTable(), setup)

            repeat(2) {
                controller.startGameComposer()
                controller.editGameName("Harmonies")
                controller.saveGame()
            }

            assertEquals(listOf("Harmonies", "Harmonies"), setup.createdNames)
        }

    // ------------------------------------------------------ writing in a cell

    private fun cellsOf(vararg pieces: Pair<CellColumnType, String>) =
        pieces.associate { (column, text) -> column to listOf(CellSegmentPreview(null, text)) }

    @Test
    fun `opening a cell puts what it already says into the editor`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "40 gri"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)

            controller.beginEditing(row.gameId, CellColumnType.THREE_D)

            val editor = assertNotNull(controller.state.editor)
            assertEquals("40 gri", editor.draft)
            assertEquals("40 gri", editor.originalText)
            assertFalse(editor.hasChanges)
            collecting.cancelAndJoin()
        }

    @Test
    fun `a cell holding a task cannot be opened as whole text`() =
        runBlocking<Unit> {
            val row =
                GameTableRow(
                    gameId = IdGenerator.Random.newId(),
                    gameName = "Harmonies",
                    isCompleted = false,
                    cells =
                        CellColumnType.entries.map { columnType ->
                            CellPreview(
                                columnType = columnType,
                                cellId = IdGenerator.Random.newId(),
                                segments =
                                    if (columnType == CellColumnType.THREE_D) {
                                        listOf(CellSegmentPreview(IdGenerator.Random.newId(), "Gri token"))
                                    } else {
                                        emptyList()
                                    },
                                holdsTasks = columnType == CellColumnType.THREE_D,
                            )
                        },
                )
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)

            controller.beginEditing(row.gameId, CellColumnType.THREE_D)

            assertNull(controller.state.editor, "a cell holding a task was opened for whole text editing")
            collecting.cancelAndJoin()
        }

    @Test
    fun `only one cell is written in at a time`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "40 gri"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            controller.editCellText("yarım kalmış")

            controller.beginEditing(row.gameId, CellColumnType.CARD)

            val editor = assertNotNull(controller.state.editor)
            assertEquals(CellColumnType.THREE_D, editor.columnType, "the open cell was swapped out")
            assertEquals("yarım kalmış", editor.draft, "a half typed note was thrown away")
            assertTrue(controller.state.blockedByEditor, "the user was not told why nothing happened")
            collecting.cancelAndJoin()
        }

    @Test
    fun `giving up writes nothing`() =
        runBlocking<Unit> {
            val cells = FakeCells()
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "40 gri"))
            val controller = controllerOf(FakeTable(listOf(row)), cells = cells)
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            controller.editCellText("bambaşka bir şey")

            controller.cancelEditing()

            assertNull(controller.state.editor)
            assertEquals(emptyList(), cells.saved, "giving up reached the database")
            collecting.cancelAndJoin()
        }

    @Test
    fun `saving hands over exactly what was typed`() =
        runBlocking<Unit> {
            val cells = FakeCells()
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "40 gri"))
            val controller = controllerOf(FakeTable(listOf(row)), cells = cells)
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            val exact = "  40  gri  token \n ikinci satır "
            controller.editCellText(exact)

            controller.saveEditing()

            assertEquals(listOf(Triple(row.gameId, CellColumnType.THREE_D, exact)), cells.saved)
            assertNull(controller.state.editor, "the editor stayed open after a good save")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a refused save keeps the editor open with the words in it`() =
        runBlocking<Unit> {
            val cells = FakeCells(failure = CellTextFailure.COULD_NOT_SAVE)
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "40 gri"))
            val controller = controllerOf(FakeTable(listOf(row)), cells = cells)
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            controller.editCellText("kaybolmaması gereken metin")

            controller.saveEditing()

            val editor = assertNotNull(controller.state.editor, "a failed save closed the editor")
            assertEquals("kaybolmaması gereken metin", editor.draft, "a failed save lost the typing")
            assertEquals(CellTextFailure.COULD_NOT_SAVE, editor.failure)
            assertFalse(editor.isSaving)
            collecting.cancelAndJoin()
        }

    @Test
    fun `a change to another game leaves the draft alone`() =
        runBlocking<Unit> {
            val first = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "40 gri"))
            val second = row("Wingspan")
            val table = FakeTable(listOf(first, second))
            val controller = controllerOf(table)
            val collecting = collect(controller)
            controller.beginEditing(first.gameId, CellColumnType.THREE_D)
            controller.editCellText("yazmakta olduğum metin")

            table.rows.value = listOf(first, second, row("Azul"))
            settle()

            val editor = assertNotNull(controller.state.editor, "another game's change closed the editor")
            assertEquals("yazmakta olduğum metin", editor.draft, "another game's change wiped the draft")
            collecting.cancelAndJoin()
        }

    @Test
    fun `the same cell arriving again does not rewind what is being typed`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "40 gri"))
            val table = FakeTable(listOf(row))
            val controller = controllerOf(table)
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            controller.editCellText("yeni hâli")

            table.rows.value = listOf(row)
            settle()

            assertEquals("yeni hâli", assertNotNull(controller.state.editor).draft)
            collecting.cancelAndJoin()
        }

    @Test
    fun `changing the view is refused while a cell is open, and loses nothing`() =
        runBlocking<Unit> {
            // The row being written in may not be in the view being moved to.
            // Dropping the draft would lose typing; saving it would write words
            // the user never agreed to keep, and PLAN describes no automatic save.
            val cells = FakeCells()
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "40 gri"))
            val controller = controllerOf(FakeTable(listOf(row)), cells = cells)
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            controller.editCellText("yarım kalmış")

            controller.showView(GameTableView.COMPLETED)

            assertEquals(GameTableView.ONGOING, controller.state.view, "the view moved out from under the editor")
            assertEquals("yarım kalmış", assertNotNull(controller.state.editor).draft)
            assertTrue(controller.state.blockedByEditor)
            assertEquals(emptyList(), cells.saved, "the view change saved the draft behind the user")

            controller.cancelEditing()
            controller.showView(GameTableView.COMPLETED)
            assertEquals(GameTableView.COMPLETED, controller.state.view, "the view stayed stuck after giving up")
            collecting.cancelAndJoin()
        }

    @Test
    fun `text typed into the editor never reaches the database on its own`() =
        runBlocking<Unit> {
            val cells = FakeCells()
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "40 gri"))
            val controller = controllerOf(FakeTable(listOf(row)), cells = cells)
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)

            listOf("4", "40", "40 ", "40 g", "40 gri token").forEach(controller::editCellText)

            assertEquals(emptyList(), cells.saved, "the editor saved as the user typed")
            collecting.cancelAndJoin()
        }

    @Test
    fun `multi line text pasted in is kept as one piece of text`() =
        runBlocking<Unit> {
            // Nothing here parses what arrives. Turning lines into tasks is a
            // separate action the user asks for, in a later step.
            val cells = FakeCells()
            val row = row("Harmonies")
            val controller = controllerOf(FakeTable(listOf(row)), cells = cells)
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.CARD)
            val pasted = "Bird Cards 170\nBonus Cards 26\nGoal Cards 16"

            controller.editCellText(pasted)
            controller.saveEditing()

            assertEquals(listOf(Triple(row.gameId, CellColumnType.CARD, pasted)), cells.saved)
            collecting.cancelAndJoin()
        }
}
