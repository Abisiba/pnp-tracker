package dev.pnptracker.ui.feature.games

import dev.pnptracker.data.repository.CellTextEditing
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.data.repository.GameTableSource
import dev.pnptracker.data.repository.TaskCreationFromText
import dev.pnptracker.domain.colors.ColorSummary
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
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
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

    /** Hands out whatever catalogue a test gives it. */
    private class FakeColors(
        colors: List<ColorSummary> = emptyList(),
    ) : ColorCatalogue {
        val colors = MutableStateFlow(colors)

        override fun observeColors(): Flow<List<ColorSummary>> = colors

        override suspend fun colorsUsingHex(hex: String): List<ColorSummary> = emptyList()

        override suspend fun createColor(
            canonicalName: String,
            hex: String,
        ): EntityId = IdGenerator.Random.newId()
    }

    /** Records the tasks that were asked for, and can be told to refuse. */
    private class FakeTaskCreation(
        private val failure: TaskFromTextFailure? = null,
    ) : TaskCreationFromText {
        val created = mutableListOf<CreatedTask>()

        override suspend fun createSingleColorTask(
            selection: CellTextSelection,
            colorId: EntityId,
            requiredQuantity: Int,
            trackingMode: TrackingMode,
            notes: String?,
        ): EntityId {
            failure?.let { throw TaskFromTextException(it) }
            created += CreatedTask(selection, colorId, requiredQuantity, trackingMode, notes)
            return IdGenerator.Random.newId()
        }
    }

    private data class CreatedTask(
        val selection: CellTextSelection,
        val colorId: EntityId,
        val requiredQuantity: Int,
        val trackingMode: TrackingMode,
        val notes: String?,
    )

    private fun color(
        name: String,
        hex: String = "#808080",
        sortOrder: Int = 0,
    ) = ColorSummary(
        id = IdGenerator.Random.newId(),
        canonicalName = name,
        hex = hex,
        sortOrder = sortOrder,
    )

    /** One piece of plain text, with an identity of its own like a stored row. */
    private fun plain(text: String) = CellSegmentPreview(segmentId = IdGenerator.Random.newId(), taskId = null, text = text)

    private fun taskPiece(
        name: String,
        quantity: Int? = null,
    ) = CellSegmentPreview(
        segmentId = IdGenerator.Random.newId(),
        taskId = IdGenerator.Random.newId(),
        text = name,
        requiredQuantity = quantity,
    )

    private fun controllerOf(
        table: FakeTable,
        setup: FakeSetup = FakeSetup(),
        cells: FakeCells = FakeCells(),
        colors: FakeColors = FakeColors(),
        taskCreation: FakeTaskCreation = FakeTaskCreation(),
    ) = GameTableController(table, setup, cells, colors, taskCreation)

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
                            cells = mapOf(CellColumnType.THREE_D to listOf(plain("40 gri"))),
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

    private fun cellsOf(vararg pieces: Pair<CellColumnType, String>) = pieces.associate { (column, text) -> column to listOf(plain(text)) }

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
                                        listOf(taskPiece("Gri token"))
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

    // -------------------------------------- making a task out of selected words

    private suspend fun CoroutineScope.editing(
        controller: GameTableController,
        row: GameTableRow,
        columnType: CellColumnType = CellColumnType.THREE_D,
    ): Job {
        val job = collect(controller)
        controller.beginEditing(row.gameId, columnType)
        return job
    }

    @Test
    fun `selecting a word offers a task with that word as its name`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Basılacak: Knight, token"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = editing(controller, row)

            controller.beginTaskComposer(11, 17)

            val composer = assertNotNull(controller.state.taskComposer)
            assertEquals("Knight", composer.name)
            assertEquals(CellColumnType.THREE_D, composer.columnType)
            assertEquals("Basılacak: Knight, token", composer.selection.expectedText)
            assertNotNull(controller.state.editor, "the cell was closed by opening the panel")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a pool that allows one way of tracking settles it without asking`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = editing(controller, row)

            controller.beginTaskComposer(0, 6)

            assertEquals(TrackingMode.THREE_D_BATCH, assertNotNull(controller.state.taskComposer).trackingMode)
            collecting.cancelAndJoin()
        }

    @Test
    fun `a pool that allows more than one way of tracking asks rather than guessing`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.SPECIAL to "kutu bandı"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = editing(controller, row, CellColumnType.SPECIAL)

            controller.beginTaskComposer(0, 4)

            val composer = assertNotNull(controller.state.taskComposer)
            assertNull(composer.trackingMode, "a tracking mode nobody chose was written into the task")
            assertFalse(composer.canSave, "the task could be saved without a tracking mode")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a selection of nothing but whitespace says why rather than opening the panel`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "40   token"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = editing(controller, row)

            controller.beginTaskComposer(2, 5)

            assertNull(controller.state.taskComposer)
            assertEquals(
                TaskFromTextFailure.TASK_NAME_EMPTY,
                assertNotNull(controller.state.editor).selectionFailure,
            )
            collecting.cancelAndJoin()
        }

    @Test
    fun `a selection across a line ending says why rather than opening the panel`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "40 gri token\n26 ağaç"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = editing(controller, row)

            controller.beginTaskComposer(3, 20)

            assertNull(controller.state.taskComposer)
            assertEquals(
                TaskFromTextFailure.SELECTION_CONTAINS_LINE_BREAK,
                assertNotNull(controller.state.editor).selectionFailure,
            )
            collecting.cancelAndJoin()
        }

    @Test
    fun `words that have not been saved yet are not offered as a task`() =
        runBlocking<Unit> {
            // The cut is made in the database at offsets counted over what is
            // stored. Cutting a draft would land somewhere else entirely, and
            // saving on the user's behalf is not something PLAN describes.
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "40 gri"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = editing(controller, row)
            controller.editCellText("40 gri token")

            controller.beginTaskComposer(7, 12)

            assertNull(controller.state.taskComposer, "a draft was cut at offsets into stored text")
            assertEquals("40 gri token", assertNotNull(controller.state.editor).draft, "the draft was lost")
            collecting.cancelAndJoin()
        }

    @Test
    fun `the notes column is never offered a task`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.NOTES to "kutu 30x30"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = editing(controller, row, CellColumnType.NOTES)

            controller.beginTaskComposer(0, 4)

            assertNull(controller.state.taskComposer)
            assertEquals(
                TaskFromTextFailure.CELL_DOES_NOT_HOLD_TASKS,
                assertNotNull(controller.state.editor).selectionFailure,
            )
            collecting.cancelAndJoin()
        }

    // -------------------------------------------------- the quantity as typed

    @Test
    fun `only a whole number greater than zero counts as a quantity`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = FakeColors(listOf(color("Siyah")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val collecting = collect(controller)
            val catalogue = launch { controller.observeColorCatalogue() }
            settle()
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            controller.beginTaskComposer(0, 6)
            controller.chooseTaskColor(
                controller.state.colors
                    .single()
                    .id,
            )

            listOf("0", "-3", "1.5", "12a", "99999999999", " ", "").forEach { typed ->
                controller.editTaskQuantity(typed)
                val composer = assertNotNull(controller.state.taskComposer)
                assertNull(composer.quantity, "'$typed' was taken as a quantity")
                assertFalse(composer.canSave, "'$typed' let the task be saved")
                // What was typed stays visible rather than being swallowed.
                assertEquals(typed, composer.quantityText)
            }

            controller.editTaskQuantity("14")
            val ready = assertNotNull(controller.state.taskComposer)
            assertEquals(14, ready.quantity)
            assertTrue(ready.canSave)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    // ------------------------------------------------------ the colour catalogue

    @Test
    fun `the colour list is searched by name, whichever way it is typed`() =
        runBlocking<Unit> {
            // PLAN 5.7: the two Turkish i's are the same letter on a colour name.
            val colors = FakeColors(listOf(color("Gri"), color("Kırmızı"), color("Açık Mavi")))
            val controller = controllerOf(FakeTable(), colors = colors)
            val catalogue = launch { controller.observeColorCatalogue() }
            settle()
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            controller.state.colors.let { assertEquals(3, it.size) }

            assertEquals(listOf("Gri", "Kırmızı", "Açık Mavi"), controller.colorsOffered().map { it.canonicalName })

            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            catalogue.cancelAndJoin()
        }

    @Test
    fun `typing part of a colour name narrows the list`() =
        runBlocking<Unit> {
            val colors = FakeColors(listOf(color("Gri"), color("Kırmızı"), color("Açık Mavi")))
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val collecting = collect(controller)
            val catalogue = launch { controller.observeColorCatalogue() }
            settle()
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            controller.beginTaskComposer(0, 6)

            controller.editTaskColorQuery("GRİ")
            assertEquals(listOf("Gri"), controller.colorsOffered().map { it.canonicalName })

            controller.editTaskColorQuery("mavi")
            assertEquals(listOf("Açık Mavi"), controller.colorsOffered().map { it.canonicalName })

            controller.editTaskColorQuery("yeşil")
            assertTrue(controller.colorsOffered().isEmpty())

            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    // ------------------------------------------------------------ saving

    private suspend fun CoroutineScope.readyComposer(
        controller: GameTableController,
        row: GameTableRow,
        colors: FakeColors,
    ): Pair<Job, Job> {
        val collecting = collect(controller)
        val catalogue = launch { controller.observeColorCatalogue() }
        settle()
        controller.beginEditing(row.gameId, CellColumnType.THREE_D)
        controller.beginTaskComposer(0, 6)
        controller.chooseTaskColor(
            controller.state.colors
                .first()
                .id,
        )
        controller.editTaskQuantity("15")
        return collecting to catalogue
    }

    @Test
    fun `a saved task closes the panel and the cell it was made in`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = FakeColors(listOf(color("Siyah")))
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.editTaskNotes("  ikisi yedek  ")

            controller.saveTask()

            val created = creation.created.single()
            assertEquals("Knight", created.selection.expectedText)
            assertEquals(15, created.requiredQuantity)
            assertEquals(TrackingMode.THREE_D_BATCH, created.trackingMode)
            // Kept exactly: a note is the user's own words.
            assertEquals("  ikisi yedek  ", created.notes)
            assertNull(controller.state.taskComposer, "the panel stayed open after a task was made")
            assertNull(controller.state.editor, "the cell stayed open for whole text editing")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `an empty note is no note at all`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = FakeColors(listOf(color("Siyah")))
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors)

            controller.saveTask()

            assertNull(creation.created.single().notes)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `a refused save keeps the panel, the colour, the quantity and the note`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = FakeColors(listOf(color("Siyah")))
            val creation = FakeTaskCreation(TaskFromTextFailure.COLOR_NOT_AVAILABLE)
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.editTaskNotes("iki yedek")

            controller.saveTask()

            val composer = assertNotNull(controller.state.taskComposer, "the panel was closed by a refusal")
            assertEquals(TaskFromTextFailure.COLOR_NOT_AVAILABLE, composer.failure)
            assertEquals("15", composer.quantityText)
            assertEquals("iki yedek", composer.notes)
            assertNotNull(composer.colorId)
            assertFalse(composer.isSaving)
            assertNotNull(controller.state.editor, "the cell was closed by a refusal")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `giving up on the task leaves the cell and its words alone`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = FakeColors(listOf(color("Siyah")))
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors)

            controller.cancelTaskComposer()

            assertNull(controller.state.taskComposer)
            val editor = assertNotNull(controller.state.editor, "giving up on the task closed the cell")
            assertEquals("Knight", editor.draft)
            assertTrue(creation.created.isEmpty(), "giving up wrote a task anyway")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    // ------------------------------------------- what an open panel protects

    @Test
    fun `a fresh list from the database leaves the panel exactly where it was`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val other = row("Wingspan")
            val table = FakeTable(listOf(row, other))
            val colors = FakeColors(listOf(color("Siyah")))
            val controller = controllerOf(table, colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.editTaskNotes("iki yedek")
            val before = assertNotNull(controller.state.taskComposer)

            table.rows.value = listOf(row, other.copy(gameName = "Wingspan Avrupa"))
            settle()

            assertEquals(before, controller.state.taskComposer, "a change to another game disturbed the panel")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `changing the view is refused while the panel is open`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = FakeColors(listOf(color("Siyah")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            val before = assertNotNull(controller.state.taskComposer)

            controller.showView(GameTableView.COMPLETED)

            assertEquals(GameTableView.ONGOING, controller.state.view)
            assertTrue(controller.state.blockedByEditor)
            assertEquals(before, controller.state.taskComposer, "the panel lost what had been chosen")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `a refused action hands the keyboard back to the open work`() =
        runBlocking<Unit> {
            // Refusing on its own is not enough: the click that was refused took
            // the focus with it, so Escape would reach the chip rather than the
            // cell it is meant to close.
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val other = row("Wingspan", cells = cellsOf(CellColumnType.CARD to "60 kart"))
            val controller = controllerOf(FakeTable(listOf(row, other)))
            val collecting = editing(controller, row)
            val start = controller.state.focusRecall

            controller.showView(GameTableView.ALL)
            assertEquals(start + 1, controller.state.focusRecall)

            controller.beginEditing(other.gameId, CellColumnType.CARD)
            assertEquals(start + 2, controller.state.focusRecall)

            // And Escape still reaches the cell it was always meant to.
            controller.cancelEditing()
            assertNull(controller.state.editor)
            collecting.cancelAndJoin()
        }

    @Test
    fun `closing the panel hands the keyboard back to the cell`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = FakeColors(listOf(color("Siyah")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            val start = controller.state.focusRecall

            controller.cancelTaskComposer()

            assertEquals(start + 1, controller.state.focusRecall)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `typing in the cell is held still while the panel is open`() =
        runBlocking<Unit> {
            // The panel holds offsets into the text as it stands; a keystroke
            // would move the words out from under the selection.
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = FakeColors(listOf(color("Siyah")))
            val cells = FakeCells()
            val controller = controllerOf(FakeTable(listOf(row)), cells = cells, colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)

            controller.editCellText("Knight değişti")
            controller.saveEditing()

            assertEquals("Knight", assertNotNull(controller.state.editor).draft)
            assertTrue(cells.saved.isEmpty(), "the cell was written while a task was being made from it")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }
}
