package dev.pnptracker.ui.feature.games

import dev.pnptracker.data.repository.CellTextEditing
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.data.repository.GameTableSource
import dev.pnptracker.data.repository.TaskCreationFromText
import dev.pnptracker.data.repository.TaskEditing
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
import dev.pnptracker.domain.games.TaskColorPreview
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskEditFailure
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
        val expectations = mutableListOf<String>()

        override suspend fun saveDocumentText(
            gameId: EntityId,
            columnType: CellColumnType,
            expectedDocumentText: String,
            newDocumentText: String,
        ): Boolean {
            failure?.let { throw CellTextException(it) }
            expectations += expectedDocumentText
            saved += Triple(gameId, columnType, newDocumentText)
            return true
        }
    }

    /** Records how tasks were changed, and can be told to refuse. */
    private class FakeTaskEditing(
        private val failure: TaskEditFailure? = null,
        private val failedRow: Int? = null,
    ) : TaskEditing {
        val edits = mutableListOf<EditedTask>()
        val converted = mutableListOf<EntityId>()

        override suspend fun editTask(
            taskId: EntityId,
            name: String,
            colorIds: List<EntityId>,
            requiredQuantity: Int?,
            notes: String?,
            trackingMode: TrackingMode,
        ): Boolean {
            failure?.let { throw TaskEditException(it, failedRow) }
            edits += EditedTask(taskId, name, colorIds, requiredQuantity, notes, trackingMode)
            return true
        }

        override suspend fun convertTaskToText(taskId: EntityId): Boolean {
            failure?.let { throw TaskEditException(it) }
            converted += taskId
            return true
        }
    }

    private data class EditedTask(
        val taskId: EntityId,
        val name: String,
        val colorIds: List<EntityId>,
        val requiredQuantity: Int?,
        val notes: String?,
        val trackingMode: TrackingMode,
    )

    /** The text editor open in whatever cell, whatever is layered over it. */
    private fun GameTableController.editorState(): CellWork.WritingText? =
        when (val open = state.work) {
            is CellWork.WritingText -> open
            is CellWork.MakingTask -> open.from
            else -> null
        }

    private fun GameTableController.composerState(): TaskComposer? = (state.work as? CellWork.MakingTask)?.composer

    private fun GameTableController.taskEditorState(): TaskEditor? = (state.work as? CellWork.EditingTask)?.editor

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
        private val failedRow: Int? = null,
    ) : TaskCreationFromText {
        val created = mutableListOf<CreatedTask>()

        /** How many times a save was asked for, refusals included. */
        var calls: Int = 0
            private set

        override suspend fun createTasks(
            selection: CellTextSelection,
            drafts: List<TaskDraft>,
        ): List<EntityId> {
            calls++
            failure?.let { throw TaskFromTextException(it, failedRow) }
            created +=
                drafts.map {
                    CreatedTask(selection, it.colorIds, it.requiredQuantity, it.trackingMode, it.notes)
                }
            return drafts.map { IdGenerator.Random.newId() }
        }
    }

    private data class CreatedTask(
        val selection: CellTextSelection,
        val colorIds: List<EntityId>,
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
        taskEditing: FakeTaskEditing = FakeTaskEditing(),
    ) = GameTableController(table, setup, cells, colors, taskCreation, taskEditing)

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

            val editor = assertNotNull(controller.editorState())
            assertEquals("40 gri", editor.draft)
            assertEquals("40 gri", editor.originalText)
            assertFalse(editor.hasUnsavedChanges)
            collecting.cancelAndJoin()
        }

    @Test
    fun `a cell holding a task opens on the whole document, tasks and all`() =
        runBlocking<Unit> {
            // The whole-cell lock of the previous step is gone. PLAN 5.5 makes
            // the task atomic, not the cell: the editor opens on everything and
            // the task is protected by the change rule instead.
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
                                        listOf(plain("Basılacak: "), taskPiece("Knight"), plain(", token"))
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

            val editor = assertNotNull(controller.editorState(), "a cell holding a task would not open")
            assertEquals("Basılacak: Knight, token", editor.draft)
            assertEquals("Basılacak: Knight, token", editor.originalText)
            assertFalse(editor.hasUnsavedChanges)
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

            val editor = assertNotNull(controller.editorState())
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

            assertNull(controller.editorState())
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
            assertNull(controller.editorState(), "the editor stayed open after a good save")
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

            val editor = assertNotNull(controller.editorState(), "a failed save closed the editor")
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

            val editor = assertNotNull(controller.editorState(), "another game's change closed the editor")
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

            assertEquals("yeni hâli", assertNotNull(controller.editorState()).draft)
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
            assertEquals("yarım kalmış", assertNotNull(controller.editorState()).draft)
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

            val composer = assertNotNull(controller.composerState())
            assertEquals("Knight", composer.name)
            assertEquals(CellColumnType.THREE_D, composer.columnType)
            assertEquals("Basılacak: Knight, token", composer.selection.expectedText)
            assertNotNull(controller.editorState(), "the cell was closed by opening the panel")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a pool that allows one way of tracking settles it without asking`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = editing(controller, row)

            controller.beginTaskComposer(0, 6)

            assertEquals(TrackingMode.THREE_D_BATCH, assertNotNull(controller.composerState()).rows.first().trackingMode)
            collecting.cancelAndJoin()
        }

    @Test
    fun `a pool that allows more than one way of tracking asks rather than guessing`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.SPECIAL to "kutu bandı"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = editing(controller, row, CellColumnType.SPECIAL)

            controller.beginTaskComposer(0, 4)

            val composer = assertNotNull(controller.composerState())
            assertNull(composer.rows.first().trackingMode, "a tracking mode nobody chose was written into the task")
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

            assertNull(controller.composerState())
            assertEquals(
                TaskFromTextFailure.TASK_NAME_EMPTY,
                assertNotNull(controller.editorState()).selectionFailure,
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

            assertNull(controller.composerState())
            assertEquals(
                TaskFromTextFailure.SELECTION_CONTAINS_LINE_BREAK,
                assertNotNull(controller.editorState()).selectionFailure,
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

            assertNull(controller.composerState(), "a draft was cut at offsets into stored text")
            assertEquals("40 gri token", assertNotNull(controller.editorState()).draft, "the draft was lost")
            collecting.cancelAndJoin()
        }

    @Test
    fun `the notes column is never offered a task`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.NOTES to "kutu 30x30"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = editing(controller, row, CellColumnType.NOTES)

            controller.beginTaskComposer(0, 4)

            assertNull(controller.composerState())
            assertEquals(
                TaskFromTextFailure.CELL_DOES_NOT_HOLD_TASKS,
                assertNotNull(controller.editorState()).selectionFailure,
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
                0,
                controller.state.colors
                    .single()
                    .id,
            )

            listOf("0", "-3", "1.5", "12a", "99999999999", " ", "").forEach { typed ->
                controller.editTaskQuantity(0, typed)
                val composer = assertNotNull(controller.composerState())
                assertNull(composer.single.quantity, "'$typed' was taken as a quantity")
                assertFalse(composer.canSave, "'$typed' let the task be saved")
                // What was typed stays visible rather than being swallowed.
                assertEquals(typed, composer.single.quantityText)
            }

            controller.editTaskQuantity(0, "14")
            val ready = assertNotNull(controller.composerState())
            assertEquals(14, ready.single.quantity)
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

            controller.editTaskColorQuery(0, "GRİ")
            assertEquals(listOf("Gri"), controller.colorsOffered().map { it.canonicalName })

            controller.editTaskColorQuery(0, "mavi")
            assertEquals(listOf("Açık Mavi"), controller.colorsOffered().map { it.canonicalName })

            controller.editTaskColorQuery(0, "yeşil")
            assertTrue(controller.colorsOffered().isEmpty())

            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    // ------------------------------------------------------------ saving

    /**
     * Opens the panel with its first row filled in, in the mode being tested.
     *
     * The mode is chosen **before** anything is typed, because each mode has a
     * draft of its own: filling a row in one of them fills nothing in another.
     */
    private suspend fun CoroutineScope.readyComposer(
        controller: GameTableController,
        row: GameTableRow,
        colors: FakeColors,
        word: String = "Knight",
        mode: TaskCreationMode = TaskCreationMode.SINGLE_COLOR,
    ): Pair<Job, Job> {
        val collecting = collect(controller)
        val catalogue = launch { controller.observeColorCatalogue() }
        settle()
        controller.beginEditing(row.gameId, CellColumnType.THREE_D)
        controller.beginTaskComposer(0, word.length)
        controller.chooseCreationMode(mode)
        controller.chooseTaskColor(
            0,
            controller.state.colors
                .first()
                .id,
        )
        controller.editTaskQuantity(0, "15")
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
            controller.editTaskNotes(0, "  ikisi yedek  ")

            controller.saveTask()

            val created = creation.created.single()
            assertEquals("Knight", created.selection.expectedText)
            assertEquals(15, created.requiredQuantity)
            assertEquals(TrackingMode.THREE_D_BATCH, created.trackingMode)
            // Kept exactly: a note is the user's own words.
            assertEquals("  ikisi yedek  ", created.notes)
            assertNull(controller.composerState(), "the panel stayed open after a task was made")
            assertNull(controller.editorState(), "the cell stayed open for whole text editing")
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
            controller.editTaskNotes(0, "iki yedek")

            controller.saveTask()

            val composer = assertNotNull(controller.composerState(), "the panel was closed by a refusal")
            assertEquals(TaskFromTextFailure.COLOR_NOT_AVAILABLE, composer.failure)
            assertEquals("15", composer.single.quantityText)
            assertEquals("iki yedek", composer.single.notes)
            assertNotNull(composer.single.colorId)
            assertFalse(composer.isSaving)
            assertNotNull(controller.editorState(), "the cell was closed by a refusal")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    // ------------------------------------------ several tasks at one go

    @Test
    fun `the panel starts on one task and offers the other way`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val collecting = collect(controller)
            val catalogue = launch { controller.observeColorCatalogue() }
            settle()
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            controller.beginTaskComposer(0, 5)

            val composer = assertNotNull(controller.composerState())
            assertEquals(TaskCreationMode.SINGLE_COLOR, composer.mode)
            assertEquals(1, composer.usedRows.size)
            assertEquals(composer.single, composer.usedRows.single())
            // The batch has rows of its own from the start, untouched and empty.
            assertEquals(TaskComposer.LEAST_INDEPENDENT_TASKS, composer.rows.size)
            assertTrue(composer.rows.all { it.isUntouched }, "the batch was given somebody else's answers")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `going to the batch mode finds its own rows rather than the other mode's`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token")
            controller.editTaskNotes(0, "ilkinin notu")
            val single = assertNotNull(controller.composerState()).single

            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)

            val composer = assertNotNull(controller.composerState())
            // Nothing is carried across. A quantity typed while making one task
            // is about that task, and finding it in the first row of a batch
            // would be an answer the user never gave there.
            assertEquals(TaskComposer.LEAST_INDEPENDENT_TASKS, composer.rows.size)
            assertTrue(composer.rows.all { it.isUntouched }, "the batch was handed the other mode's answers")
            assertEquals(
                List(TaskComposer.LEAST_INDEPENDENT_TASKS) { TaskDraftRow(trackingMode = TrackingMode.THREE_D_BATCH) },
                composer.rows,
            )
            // And the single-colour draft is left exactly as it was.
            assertEquals(single, composer.single)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `switching back and forth loses nothing that was typed`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token")
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseTaskColor(1, controller.state.colors[1].id)
            controller.editTaskQuantity(1, "8")
            controller.editTaskNotes(1, "ikincinin notu")
            val filled = assertNotNull(controller.composerState()).rows

            controller.chooseCreationMode(TaskCreationMode.SINGLE_COLOR)
            val narrowed = assertNotNull(controller.composerState())
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)

            // Nothing is asked and nothing is dropped: the second row is simply
            // not used while one task is being made, and is there again after.
            assertEquals(filled, narrowed.rows, "switching away threw a row's answers away")
            assertEquals(1, narrowed.usedRows.size)
            assertEquals(filled, assertNotNull(controller.composerState()).rows)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `three rows become three drafts in the order they were typed`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz"), color("Sarı")))
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token", mode = TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            controller.editTaskQuantity(0, "14")
            controller.chooseTaskColor(1, controller.state.colors[1].id)
            controller.editTaskQuantity(1, "15")
            controller.addTaskRow()
            controller.chooseTaskColor(2, controller.state.colors[2].id)
            controller.editTaskQuantity(2, "8")

            controller.saveTask()

            assertEquals(listOf(14, 15, 8), creation.created.map { it.requiredQuantity })
            assertEquals(controller.state.colors.map { listOf(it.id) }, creation.created.map { it.colorIds })
            assertEquals(
                1,
                creation.created
                    .map { it.selection }
                    .toSet()
                    .size,
                "the batch used more than one selection",
            )
            assertNull(controller.composerState(), "the panel stayed open after the tasks were made")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `a batch of one row cannot be saved`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz")))
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token", mode = TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            // Removing the second row is refused at the floor, so the only way to
            // one row is to start there — and the batch mode will not take it.
            controller.removeTaskRow(1)

            val composer = assertNotNull(controller.composerState())
            assertEquals(2, composer.rows.size, "the last row of a batch was taken away")
            assertFalse(composer.canRemoveRow)
            assertFalse(composer.canSave, "a batch missing an answer could be saved")
            controller.saveTask()
            assertEquals(0, creation.calls, "an unfinished batch was written")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `a row can be added and taken away again`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz"), color("Sarı")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token", mode = TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            controller.addTaskRow()
            controller.chooseTaskColor(2, controller.state.colors[2].id)
            controller.editTaskQuantity(2, "8")

            assertEquals(3, assertNotNull(controller.composerState()).rows.size)
            controller.removeTaskRow(1)

            val composer = assertNotNull(controller.composerState())
            assertEquals(2, composer.rows.size)
            // The one that was taken away is the one that was pointed at, and the
            // rows after it move up rather than being renumbered into the wrong
            // answers.
            assertEquals("8", composer.rows[1].quantityText)
            assertEquals(controller.state.colors[2].id, composer.rows[1].colorId)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `the same colour twice is marked and stops the save`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz")))
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token", mode = TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseTaskColor(
                1,
                controller.state.colors
                    .first()
                    .id,
            )
            controller.editTaskQuantity(1, "8")

            val composer = assertNotNull(controller.composerState())
            // The second one is marked, not the first: the user chose that one
            // first and it is not the one they need to change.
            // The later row is the one marked, and it names the row it repeats.
            assertEquals(mapOf(1 to 0), composer.repeatedColorRows)
            assertFalse(composer.canSave)
            controller.saveTask()
            assertEquals(0, creation.calls, "two rows of one colour were written")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `a row with no colour or no quantity stops the save and is where the keyboard goes`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz")))
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token", mode = TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)

            val missingColor = assertNotNull(controller.composerState())
            assertFalse(missingColor.canSave)
            assertEquals(1, missingColor.firstUnusableRow, "the keyboard would go to a row that is ready")

            controller.chooseTaskColor(1, controller.state.colors[1].id)
            controller.editTaskQuantity(1, "0")
            val badQuantity = assertNotNull(controller.composerState())
            assertFalse(badQuantity.canSave, "a quantity of zero let the batch be saved")
            assertEquals("0", badQuantity.rows[1].quantityText, "what was typed stopped being visible")
            assertEquals(1, badQuantity.firstUnusableRow)

            controller.saveTask()
            assertEquals(0, creation.calls, "an unfinished batch was written")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `asking to save twice writes one batch`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz")))
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token", mode = TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseTaskColor(1, controller.state.colors[1].id)
            controller.editTaskQuantity(1, "8")

            controller.saveTask()
            // A double click, or Ctrl+Enter arriving twice. The panel is gone
            // after the first, so the second has nothing to save.
            controller.saveTask()

            assertEquals(1, creation.calls, "the same words became tasks twice")
            assertEquals(2, creation.created.size)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `a refused batch keeps every row exactly as it was typed`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz")))
            val creation = FakeTaskCreation(TaskFromTextFailure.COLOR_NOT_AVAILABLE)
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token", mode = TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseTaskColor(1, controller.state.colors[1].id)
            controller.editTaskQuantity(1, "8")
            controller.editTaskNotes(1, "ikincinin notu")
            val before = assertNotNull(controller.composerState()).rows
            val recallBefore = controller.state.focusRecall

            controller.saveTask()

            val composer = assertNotNull(controller.composerState(), "the panel was closed by a refusal")
            assertEquals(TaskFromTextFailure.COLOR_NOT_AVAILABLE, composer.failure)
            assertEquals(before, composer.rows, "a refusal changed what had been typed")
            assertFalse(composer.isSaving)
            assertTrue(controller.state.focusRecall > recallBefore, "the keyboard was left on whatever refused")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `a refusal about one row is marked on that row and takes the keyboard`() =
        runBlocking<Unit> {
            // A batch is refused about one of its rows. Saying only "a colour is
            // gone" would leave the user to work out which of three rows it was.
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz")))
            val creation = FakeTaskCreation(TaskFromTextFailure.COLOR_NOT_AVAILABLE, failedRow = 1)
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token", mode = TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseTaskColor(1, controller.state.colors[1].id)
            controller.editTaskQuantity(1, "8")

            controller.saveTask()

            val composer = assertNotNull(controller.composerState())
            assertEquals(1, composer.failureRow)
            assertEquals(1, composer.firstUnusableRow, "the keyboard would go somewhere else than the refused row")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `changing anything clears which row was refused`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz")))
            val creation = FakeTaskCreation(TaskFromTextFailure.COLOR_NOT_AVAILABLE, failedRow = 1)
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token", mode = TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseTaskColor(1, controller.state.colors[1].id)
            controller.editTaskQuantity(1, "8")
            controller.saveTask()

            controller.chooseTaskColor(
                1,
                controller.state.colors
                    .first()
                    .id,
            )

            val composer = assertNotNull(controller.composerState())
            assertNull(composer.failure, "the old refusal is still shown after it was answered")
            assertNull(composer.failureRow)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    // --------------------------------------- one task made in several colours

    @Test
    fun `the several colour mode starts empty and cannot be saved with one colour`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Yarasa"))
            val colors = FakeColors(listOf(color("Kırmızı"), color("Sarı"), color("Siyah")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Yarasa")

            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            val empty = assertNotNull(controller.composerState())
            assertTrue(empty.palette.colorIds.isEmpty(), "colours were chosen on the user's behalf")
            assertFalse(empty.canSave, "a task in no colours can be saved")

            controller.toggleMulticolorColor(
                controller.state.colors
                    .first()
                    .id,
            )
            controller.editMulticolorQuantity("10")
            assertFalse(assertNotNull(controller.composerState()).canSave, "one colour saves as a several-colour task")

            controller.toggleMulticolorColor(controller.state.colors[1].id)
            assertTrue(assertNotNull(controller.composerState()).canSave, "two colours and a quantity is not enough")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `three colours become one task carrying all three, in order`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Yarasa"))
            val colors = FakeColors(listOf(color("Kırmızı"), color("Sarı"), color("Siyah")))
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Yarasa")
            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            controller.state.colors.forEach { controller.toggleMulticolorColor(it.id) }
            controller.editMulticolorQuantity("10")
            controller.editMulticolorNotes("tek kat")

            controller.saveTask()

            // One draft, not three: one task, one quantity, one note, three
            // colours in the order they were chosen (PLAN 5.10).
            val created = creation.created.single()
            assertEquals(controller.state.colors.map { it.id }, created.colorIds)
            assertEquals(10, created.requiredQuantity)
            assertEquals("tek kat", created.notes)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `choosing a colour already in the list takes it back out`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Yarasa"))
            val colors = FakeColors(listOf(color("Kırmızı"), color("Sarı")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Yarasa")
            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            val red =
                controller.state.colors
                    .first()
                    .id

            controller.toggleMulticolorColor(red)
            controller.toggleMulticolorColor(controller.state.colors[1].id)
            controller.toggleMulticolorColor(red)

            // The same colour twice is not a thing the panel can describe.
            assertEquals(listOf(controller.state.colors[1].id), assertNotNull(controller.composerState()).palette.colorIds)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `the colours can be moved up and down without losing any of them`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Yarasa"))
            val colors = FakeColors(listOf(color("Kırmızı"), color("Sarı"), color("Siyah")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Yarasa")
            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            controller.state.colors.forEach { controller.toggleMulticolorColor(it.id) }
            val (red, yellow, black) =
                Triple(
                    controller.state.colors[0].id,
                    controller.state.colors[1].id,
                    controller.state.colors[2].id,
                )

            controller.moveMulticolorColorUp(2)
            assertEquals(listOf(red, black, yellow), assertNotNull(controller.composerState()).palette.colorIds)

            controller.moveMulticolorColorDown(0)
            assertEquals(listOf(black, red, yellow), assertNotNull(controller.composerState()).palette.colorIds)

            // Nothing to move to is quietly nothing rather than a lost colour.
            controller.moveMulticolorColorUp(0)
            controller.moveMulticolorColorDown(2)
            assertEquals(listOf(black, red, yellow), assertNotNull(controller.composerState()).palette.colorIds)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `the three modes keep their own answers and never mix them`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Yarasa"))
            val colors = FakeColors(listOf(color("Kırmızı"), color("Sarı"), color("Siyah")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Yarasa")
            controller.editTaskNotes(0, "tek rengin notu")
            val singleTyped = assertNotNull(controller.composerState()).single
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseTaskColor(1, controller.state.colors[1].id)
            controller.editTaskQuantity(1, "8")
            val rowsTyped = assertNotNull(controller.composerState()).rows

            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            controller.toggleMulticolorColor(controller.state.colors[2].id)
            controller.editMulticolorQuantity("10")
            controller.editMulticolorNotes("çok rengin notu")
            val paletteTyped = assertNotNull(controller.composerState()).palette

            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            val back = assertNotNull(controller.composerState())

            assertEquals(rowsTyped, back.rows, "the rows were disturbed by the other mode")
            assertEquals(paletteTyped, back.palette, "the several-colour draft was thrown away")
            assertEquals(singleTyped, back.single, "the single-colour draft was disturbed")
            // The note typed while making one task is that task's, and is in
            // none of the batch's rows.
            assertTrue(
                back.rows.none { it.notes == "tek rengin notu" },
                "an answer from the single-colour mode turned up in the batch",
            )
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `asking to save one task in several colours twice writes it once`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Yarasa"))
            val colors = FakeColors(listOf(color("Kırmızı"), color("Sarı")))
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Yarasa")
            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            controller.state.colors.forEach { controller.toggleMulticolorColor(it.id) }
            controller.editMulticolorQuantity("10")

            controller.saveTask()
            controller.saveTask()

            assertEquals(1, creation.calls, "the same words became a task twice")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `a refused several colour save keeps the list and marks the colour`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Yarasa"))
            val colors = FakeColors(listOf(color("Kırmızı"), color("Sarı"), color("Siyah")))
            val creation = FakeTaskCreation(TaskFromTextFailure.COLOR_NOT_AVAILABLE, failedRow = 2)
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Yarasa")
            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            controller.state.colors.forEach { controller.toggleMulticolorColor(it.id) }
            controller.editMulticolorQuantity("10")
            controller.editMulticolorNotes("tek kat")

            controller.saveTask()

            val composer = assertNotNull(controller.composerState(), "the panel closed on a refusal")
            assertEquals(TaskFromTextFailure.COLOR_NOT_AVAILABLE, composer.failure)
            assertEquals(2, composer.failedColorSlot, "the refusal is not shown on the colour it was about")
            assertEquals(controller.state.colors.map { it.id }, composer.palette.colorIds, "the list was disturbed")
            assertEquals("10", composer.palette.quantityText)
            assertEquals("tek kat", composer.palette.notes)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `changing the colour list clears which colour was refused`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Yarasa"))
            val colors = FakeColors(listOf(color("Kırmızı"), color("Sarı"), color("Siyah")))
            val creation = FakeTaskCreation(TaskFromTextFailure.COLOR_NOT_AVAILABLE, failedRow = 2)
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Yarasa")
            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            controller.state.colors.forEach { controller.toggleMulticolorColor(it.id) }
            controller.editMulticolorQuantity("10")
            controller.saveTask()

            controller.toggleMulticolorColor(controller.state.colors[2].id)

            val composer = assertNotNull(controller.composerState())
            assertNull(composer.failure, "the panel still says something the user has dealt with")
            assertNull(composer.failedColorSlot)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    /**
     * Fills all three drafts with answers that could not be confused.
     *
     * Deliberately the whole panel rather than one field of it: what is being
     * checked is that three drafts stand side by side, so all three have to have
     * something in them at once.
     */
    private suspend fun fillEveryMode(controller: GameTableController) {
        val catalogue = controller.state.colors
        controller.chooseCreationMode(TaskCreationMode.SINGLE_COLOR)
        controller.chooseTaskColor(0, catalogue[0].id)
        controller.editTaskQuantity(0, "3")
        controller.editTaskNotes(0, "tek renk")

        controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
        controller.chooseTaskColor(0, catalogue[1].id)
        controller.editTaskQuantity(0, "14")
        controller.editTaskNotes(0, "toplu bir")
        controller.chooseTaskColor(1, catalogue[2].id)
        controller.editTaskQuantity(1, "15")
        controller.editTaskNotes(1, "toplu iki")
        controller.addTaskRow()
        controller.chooseTaskColor(2, catalogue[3].id)
        controller.editTaskQuantity(2, "8")
        controller.editTaskNotes(2, "toplu üç")

        controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
        controller.toggleMulticolorColor(catalogue[0].id)
        controller.toggleMulticolorColor(catalogue[4].id)
        controller.editMulticolorQuantity("10")
        controller.editMulticolorNotes("çok renk")
    }

    @Test
    fun `each of the three modes keeps a draft of its own`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(List(5) { color("Renk $it") })
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token")
            fillEveryMode(controller)
            val filled = assertNotNull(controller.composerState())

            // Three drafts, each holding what was typed in it and nothing else.
            assertEquals("3", filled.single.quantityText)
            assertEquals("tek renk", filled.single.notes)
            assertEquals(listOf("14", "15", "8"), filled.rows.map { it.quantityText })
            assertEquals(listOf("toplu bir", "toplu iki", "toplu üç"), filled.rows.map { it.notes })
            assertEquals("10", filled.palette.quantityText)
            assertEquals("çok renk", filled.palette.notes)
            assertEquals(2, filled.palette.colorIds.size)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `going back and forth between the modes returns every draft unchanged`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(List(5) { color("Renk $it") })
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token")
            fillEveryMode(controller)
            val filled = assertNotNull(controller.composerState())

            repeat(3) {
                TaskCreationMode.entries.forEach { controller.chooseCreationMode(it) }
                TaskCreationMode.entries.reversed().forEach { controller.chooseCreationMode(it) }
            }

            val after = assertNotNull(controller.composerState())
            assertEquals(filled.single, after.single, "the single-colour draft changed")
            assertEquals(filled.rows, after.rows, "the batch changed")
            assertEquals(filled.palette, after.palette, "the several-colour draft changed")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `a change in one mode leaves the other two drafts exactly as they were`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(List(5) { color("Renk $it") })
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token")
            fillEveryMode(controller)
            val filled = assertNotNull(controller.composerState())

            // Each mode is changed in turn, and the other two are checked.
            controller.chooseCreationMode(TaskCreationMode.SINGLE_COLOR)
            controller.editTaskQuantity(0, "99")
            controller.editTaskNotes(0, "değişti")
            var now = assertNotNull(controller.composerState())
            assertEquals(filled.rows, now.rows, "the batch followed the single-colour mode")
            assertEquals(filled.palette, now.palette, "the several-colour draft followed the single-colour mode")

            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            controller.editTaskQuantity(1, "77")
            controller.editTaskNotes(0, "toplu değişti")
            now = assertNotNull(controller.composerState())
            assertEquals("99", now.single.quantityText, "the single-colour draft followed the batch")
            assertEquals("değişti", now.single.notes, "the single-colour draft followed the batch")
            assertEquals(filled.palette, now.palette, "the several-colour draft followed the batch")

            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            controller.editMulticolorQuantity("55")
            controller.moveMulticolorColorUp(1)
            now = assertNotNull(controller.composerState())
            assertEquals("99", now.single.quantityText, "the single-colour draft followed the colour list")
            assertEquals(listOf("toplu değişti", "toplu iki", "toplu üç"), now.rows.map { it.notes })
            assertEquals(listOf("14", "77", "8"), now.rows.map { it.quantityText })
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `the rows past the first survive a visit to the single-colour mode`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(List(5) { color("Renk $it") })
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token")
            fillEveryMode(controller)
            val batch = assertNotNull(controller.composerState()).rows

            controller.chooseCreationMode(TaskCreationMode.SINGLE_COLOR)
            controller.editTaskQuantity(0, "1")
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)

            assertEquals(3, assertNotNull(controller.composerState()).rows.size, "a row disappeared")
            assertEquals(batch, assertNotNull(controller.composerState()).rows)
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `a panel opened on new words starts with nothing in any of its drafts`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token, kutu"))
            val colors = FakeColors(List(5) { color("Renk $it") })
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token")
            fillEveryMode(controller)

            controller.cancelTaskComposer()
            controller.beginTaskComposer(7, 11)

            val fresh = assertNotNull(controller.composerState(), "the panel did not open on the new words")
            assertEquals("kutu", fresh.name)
            assertTrue(fresh.single.isUntouched, "the single-colour draft leaked into a new selection")
            assertTrue(fresh.rows.all { it.isUntouched }, "the batch leaked into a new selection")
            assertTrue(fresh.palette.isUntouched, "the several-colour draft leaked into a new selection")
            assertEquals(TaskCreationMode.SINGLE_COLOR, fresh.mode, "the panel did not open on the first mode")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `Escape closes the batch panel and leaves the cell open`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token")
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            val text = assertNotNull(controller.editorState()).draft

            controller.closeInnermost()

            assertNull(controller.composerState(), "the panel stayed open")
            val editor = assertNotNull(controller.editorState(), "closing the panel closed the cell as well")
            assertEquals(text, editor.draft, "the cell lost what was written in it")

            controller.closeInnermost()
            assertNull(controller.editorState(), "one Escape closed both layers")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `a fresh table leaves a half typed batch exactly where it is`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = FakeColors(listOf(color("Siyah"), color("Beyaz")))
            val table = FakeTable(listOf(row))
            val controller = controllerOf(table, colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token")
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            controller.chooseTaskColor(1, controller.state.colors[1].id)
            controller.editTaskQuantity(1, "8")
            val before = assertNotNull(controller.composerState())

            table.rows.value = listOf(row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token")))
            settle()

            assertEquals(before, controller.composerState(), "a change to the table disturbed the panel")
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

            assertNull(controller.composerState())
            val editor = assertNotNull(controller.editorState(), "giving up on the task closed the cell")
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
            controller.editTaskNotes(0, "iki yedek")
            val before = assertNotNull(controller.composerState())

            table.rows.value = listOf(row, other.copy(gameName = "Wingspan Avrupa"))
            settle()

            assertEquals(before, controller.composerState(), "a change to another game disturbed the panel")
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
            val before = assertNotNull(controller.composerState())

            controller.showView(GameTableView.COMPLETED)

            assertEquals(GameTableView.ONGOING, controller.state.view)
            assertTrue(controller.state.blockedByEditor)
            assertEquals(before, controller.composerState(), "the panel lost what had been chosen")
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
            assertNull(controller.editorState())
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

            assertEquals("Knight", assertNotNull(controller.editorState()).draft)
            assertTrue(cells.saved.isEmpty(), "the cell was written while a task was being made from it")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    // ------------------------------------------- working on a task in a cell

    // ------------------------- a task with one colour, and a task with none

    @Test
    fun `a task with no colour at all can still be opened and given one`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask(colors = emptyList())
            val editing = FakeTaskEditing()
            val controller = controllerOf(FakeTable(listOf(row)), taskEditing = editing)
            val collecting = collect(controller)

            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)

            // PLAN 5.10 calls a colourless task an ordinary state: it is still a
            // task, still pressable, and still the same identity.
            val menu = assertNotNull(controller.state.menuIn(row.gameId, CellColumnType.THREE_D))
            assertEquals(taskId, menu.taskId)

            controller.beginTaskEdit()
            val editor = assertNotNull(controller.taskEditorState())
            assertTrue(editor.colorIds.isEmpty())
            assertFalse(editor.holdsSeveralColors, "a task with no colour was taken for a several-colour one")
            assertTrue(editor.hasEnoughColors, "a task with no colour was told it needs two")

            val chosen = IdGenerator.Random.newId()
            controller.chooseTaskEditColor(chosen)
            controller.saveTaskEdit()

            assertEquals(listOf(chosen), editing.edits.single().colorIds)
            collecting.cancelAndJoin()
        }

    @Test
    fun `a task with one colour is edited as one colour rather than as a list`() =
        runBlocking<Unit> {
            val black = TaskColorPreview(IdGenerator.Random.newId(), "Siyah", "#111111")
            val (row, taskId) = rowWithTask(colors = listOf(black))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()

            val editor = assertNotNull(controller.taskEditorState())
            assertFalse(editor.holdsSeveralColors)
            assertEquals(black.colorId, editor.colorId)
            // Nothing about a list: one colour in, one colour out.
            assertEquals(listOf(black.colorId), editor.colorIds)
            assertTrue(editor.hasEnoughColors)
            collecting.cancelAndJoin()
        }

    // --------------------------------- the keyboard while colours are worked

    @Test
    fun `taking a colour away hands the keyboard back to the panel`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Yarasa"))
            val colors = FakeColors(listOf(color("Kırmızı"), color("Sarı"), color("Siyah")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Yarasa")
            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            controller.state.colors.forEach { controller.toggleMulticolorColor(it.id) }
            val afterAdding = controller.state.focusRecall

            // The buttons of the entry being removed go with it, so the keyboard
            // would be left on nothing at all.
            controller.toggleMulticolorColor(controller.state.colors[1].id)

            assertTrue(controller.state.focusRecall > afterAdding, "the keyboard was left on a control that is gone")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `moving a colour hands the keyboard back when the button it used is spent`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Yarasa"))
            val colors = FakeColors(listOf(color("Kırmızı"), color("Sarı"), color("Siyah")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Yarasa")
            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            controller.state.colors.forEach { controller.toggleMulticolorColor(it.id) }
            val before = controller.state.focusRecall

            // Moved to the front, so the button that moved it is now disabled.
            controller.moveMulticolorColorUp(1)

            assertTrue(controller.state.focusRecall > before, "the keyboard was left on a disabled button")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `adding a colour leaves the keyboard where the user is choosing them`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Yarasa"))
            val colors = FakeColors(listOf(color("Kırmızı"), color("Sarı")))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Yarasa")
            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            val before = controller.state.focusRecall

            controller.toggleMulticolorColor(
                controller.state.colors
                    .first()
                    .id,
            )

            // The entry in the catalogue is still there and still has the
            // keyboard; snatching it back would fight the user.
            assertEquals(before, controller.state.focusRecall, "choosing a colour moved the keyboard away")
            catalogue.cancelAndJoin()
            collecting.cancelAndJoin()
        }

    @Test
    fun `reordering a task's colours hands the keyboard back to the panel`() =
        runBlocking<Unit> {
            val red = TaskColorPreview(IdGenerator.Random.newId(), "Kırmızı", "#DD2222")
            val yellow = TaskColorPreview(IdGenerator.Random.newId(), "Sarı", "#EEDD22")
            val (row, taskId) = rowWithTask(colors = listOf(red, yellow))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            val before = controller.state.focusRecall

            controller.moveTaskEditColorUp(1)

            assertTrue(controller.state.focusRecall > before, "the keyboard was left on a disabled button")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a reordered colour list is something closing the panel would lose`() =
        runBlocking<Unit> {
            val red = TaskColorPreview(IdGenerator.Random.newId(), "Kırmızı", "#DD2222")
            val yellow = TaskColorPreview(IdGenerator.Random.newId(), "Sarı", "#EEDD22")
            val (row, taskId) = rowWithTask(colors = listOf(red, yellow))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            assertFalse(assertNotNull(controller.state.work).hasUnsavedChanges)

            controller.moveTaskEditColorUp(1)

            // What a click outside asks about: the order is a real change, so
            // the panel does not quietly go away with it.
            assertTrue(
                assertNotNull(controller.state.work).hasUnsavedChanges,
                "a reordered colour list would be thrown away by a click outside",
            )
            collecting.cancelAndJoin()
        }

    @Test
    fun `Escape leaves the edit panel on the menu rather than closing everything`() =
        runBlocking<Unit> {
            val red = TaskColorPreview(IdGenerator.Random.newId(), "Kırmızı", "#DD2222")
            val yellow = TaskColorPreview(IdGenerator.Random.newId(), "Sarı", "#EEDD22")
            val (row, taskId) = rowWithTask(colors = listOf(red, yellow))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()

            controller.closeInnermost()
            assertIs<CellWork.TaskMenu>(controller.state.work, "the panel closed more than itself")

            controller.closeInnermost()
            assertNull(controller.state.work, "the menu would not close")
            collecting.cancelAndJoin()
        }

    @Test
    fun `asking to save a colour list twice hands it over once`() =
        runBlocking<Unit> {
            val red = TaskColorPreview(IdGenerator.Random.newId(), "Kırmızı", "#DD2222")
            val yellow = TaskColorPreview(IdGenerator.Random.newId(), "Sarı", "#EEDD22")
            val (row, taskId) = rowWithTask(colors = listOf(red, yellow))
            val editing = FakeTaskEditing()
            val controller = controllerOf(FakeTable(listOf(row)), taskEditing = editing)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            controller.moveTaskEditColorUp(1)

            controller.saveTaskEdit()
            controller.saveTaskEdit()

            assertEquals(1, editing.edits.size, "the same change was saved twice")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a fresh list from the database leaves an open colour list exactly as it is`() =
        runBlocking<Unit> {
            val red = TaskColorPreview(IdGenerator.Random.newId(), "Kırmızı", "#DD2222")
            val yellow = TaskColorPreview(IdGenerator.Random.newId(), "Sarı", "#EEDD22")
            val (row, taskId) = rowWithTask(colors = listOf(red, yellow))
            val table = FakeTable(listOf(row))
            val controller = controllerOf(table)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            controller.moveTaskEditColorUp(1)
            controller.editTaskName("Yarasa Kanadı")
            val editor = assertNotNull(controller.taskEditorState())

            table.rows.value = listOf(row)
            settle()

            assertEquals(editor, controller.taskEditorState(), "a fresh list disturbed the open panel")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a task that goes away while its colours are being changed closes its panel`() =
        runBlocking<Unit> {
            val red = TaskColorPreview(IdGenerator.Random.newId(), "Kırmızı", "#DD2222")
            val yellow = TaskColorPreview(IdGenerator.Random.newId(), "Sarı", "#EEDD22")
            val (row, taskId) = rowWithTask(colors = listOf(red, yellow))
            val table = FakeTable(listOf(row))
            val controller = controllerOf(table)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            controller.moveTaskEditColorUp(1)

            // Converted to text somewhere else: there is nothing left to edit.
            table.rows.value = listOf(row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight")))
            settle()

            assertNull(controller.state.work, "a panel was left standing over a task that is gone")
            collecting.cancelAndJoin()
        }

    private fun rowWithTask(
        name: String = "Harmonies",
        taskName: String = "Knight",
        quantity: Int? = 15,
        colors: List<TaskColorPreview> = listOf(TaskColorPreview(IdGenerator.Random.newId(), "Siyah", "#111111")),
        notes: String? = null,
        hasProgress: Boolean = false,
    ): Pair<GameTableRow, EntityId> {
        val task =
            CellSegmentPreview(
                segmentId = IdGenerator.Random.newId(),
                taskId = IdGenerator.Random.newId(),
                text = taskName,
                requiredQuantity = quantity,
                colors = colors,
                notes = notes,
                trackingMode = TrackingMode.THREE_D_BATCH,
                hasProgress = hasProgress,
            )
        val row =
            GameTableRow(
                gameId = IdGenerator.Random.newId(),
                gameName = name,
                isCompleted = false,
                cells =
                    CellColumnType.entries.map { columnType ->
                        CellPreview(
                            columnType = columnType,
                            cellId = if (columnType == CellColumnType.THREE_D) IdGenerator.Random.newId() else null,
                            segments =
                                if (columnType == CellColumnType.THREE_D) {
                                    listOf(plain("Basılacak: "), task, plain(", token"))
                                } else {
                                    emptyList()
                                },
                            holdsTasks = columnType == CellColumnType.THREE_D,
                        )
                    },
            )
        return row to task.taskId!!
    }

    @Test
    fun `pressing a task opens its menu over that task`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)

            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)

            val menu = assertIs<CellWork.TaskMenu>(controller.state.work)
            assertEquals(taskId, menu.taskId)
            assertEquals("Knight", menu.name)
            collecting.cancelAndJoin()
        }

    @Test
    fun `a menu cannot be opened while a cell is being written in`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)

            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)

            assertIs<CellWork.WritingText>(controller.state.work, "the editor was replaced by a menu")
            assertTrue(controller.state.blockedByEditor)
            collecting.cancelAndJoin()
        }

    @Test
    fun `the edit panel opens on what the task already says`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask(quantity = 14, notes = "iki yedek")
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)

            controller.beginTaskEdit()

            val editor = assertNotNull(controller.taskEditorState())
            assertEquals("Knight", editor.name)
            assertEquals("14", editor.quantityText)
            assertEquals("iki yedek", editor.notes)
            assertEquals(1, editor.colorIds.size)
            assertFalse(editor.hasChanges)
            assertFalse(editor.canSave, "a panel with nothing changed offers to save")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a task carrying several colours says so rather than offering to pick one`() =
        runBlocking<Unit> {
            val (row, taskId) =
                rowWithTask(
                    colors =
                        listOf(
                            TaskColorPreview(IdGenerator.Random.newId(), "Gri", "#808080"),
                            TaskColorPreview(IdGenerator.Random.newId(), "Siyah", "#111111"),
                        ),
                )
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)

            controller.beginTaskEdit()

            val editor = assertNotNull(controller.taskEditorState())
            assertTrue(editor.holdsSeveralColors)
            assertEquals(2, editor.colorIds.size)
            collecting.cancelAndJoin()
        }

    @Test
    fun `a several colour task is edited as an ordered list`() =
        runBlocking<Unit> {
            val red = TaskColorPreview(IdGenerator.Random.newId(), "Kırmızı", "#DD2222")
            val yellow = TaskColorPreview(IdGenerator.Random.newId(), "Sarı", "#EEDD22")
            val black = TaskColorPreview(IdGenerator.Random.newId(), "Siyah", "#111111")
            val (row, taskId) = rowWithTask(colors = listOf(red, yellow, black))
            val editing = FakeTaskEditing()
            val controller = controllerOf(FakeTable(listOf(row)), taskEditing = editing)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()

            controller.moveTaskEditColorUp(2)
            controller.saveTaskEdit()

            assertEquals(
                listOf(red.colorId, black.colorId, yellow.colorId),
                editing.edits.single().colorIds,
                "the new order was not saved",
            )
            collecting.cancelAndJoin()
        }

    @Test
    fun `a several colour task will not be emptied below two colours`() =
        runBlocking<Unit> {
            val red = TaskColorPreview(IdGenerator.Random.newId(), "Kırmızı", "#DD2222")
            val yellow = TaskColorPreview(IdGenerator.Random.newId(), "Sarı", "#EEDD22")
            val (row, taskId) = rowWithTask(colors = listOf(red, yellow))
            val editing = FakeTaskEditing()
            val controller = controllerOf(FakeTable(listOf(row)), taskEditing = editing)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()

            controller.chooseTaskEditColor(yellow.colorId)

            val editor = assertNotNull(controller.taskEditorState())
            assertEquals(listOf(red.colorId), editor.colorIds)
            assertFalse(editor.canSave, "a several-colour task can be saved with one colour")
            controller.saveTaskEdit()
            assertTrue(editing.edits.isEmpty(), "a task was reduced to one colour")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a single colour task has its one colour replaced rather than added to`() =
        runBlocking<Unit> {
            val black = TaskColorPreview(IdGenerator.Random.newId(), "Siyah", "#111111")
            val (row, taskId) = rowWithTask(colors = listOf(black))
            val editing = FakeTaskEditing()
            val controller = controllerOf(FakeTable(listOf(row)), taskEditing = editing)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            val other = IdGenerator.Random.newId()

            controller.chooseTaskEditColor(other)

            assertEquals(listOf(other), assertNotNull(controller.taskEditorState()).colorIds)
            collecting.cancelAndJoin()
        }

    @Test
    fun `a refused edit says which colour it was about and keeps the list`() =
        runBlocking<Unit> {
            val red = TaskColorPreview(IdGenerator.Random.newId(), "Kırmızı", "#DD2222")
            val yellow = TaskColorPreview(IdGenerator.Random.newId(), "Sarı", "#EEDD22")
            val (row, taskId) = rowWithTask(colors = listOf(red, yellow))
            val editing = FakeTaskEditing(TaskEditFailure.COLOR_NOT_AVAILABLE, failedRow = 1)
            val controller = controllerOf(FakeTable(listOf(row)), taskEditing = editing)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            controller.moveTaskEditColorUp(1)

            controller.saveTaskEdit()

            val editor = assertNotNull(controller.taskEditorState(), "the panel closed on a refusal")
            assertEquals(TaskEditFailure.COLOR_NOT_AVAILABLE, editor.failure)
            assertEquals(1, editor.failureRow)
            assertEquals(listOf(yellow.colorId, red.colorId), editor.colorIds, "the list was thrown away")
            collecting.cancelAndJoin()
        }

    @Test
    fun `saving the panel hands everything over at once and goes back to the menu`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val editing = FakeTaskEditing()
            val controller = controllerOf(FakeTable(listOf(row)), taskEditing = editing)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            controller.editTaskName("Şövalye")
            controller.editTaskEditQuantity("20")
            controller.editTaskEditNotes("  iki yedek  ")

            controller.saveTaskEdit()

            val edit = editing.edits.single()
            assertEquals(taskId, edit.taskId)
            assertEquals("Şövalye", edit.name)
            assertEquals(20, edit.requiredQuantity)
            assertEquals("  iki yedek  ", edit.notes, "the note was not kept as typed")
            assertIs<CellWork.TaskMenu>(controller.state.work, "saving did not go back to the menu")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a refused save keeps the panel and everything typed in it`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val editing = FakeTaskEditing(TaskEditFailure.QUANTITY_BELOW_PROGRESS)
            val controller = controllerOf(FakeTable(listOf(row)), taskEditing = editing)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            controller.editTaskEditQuantity("2")
            controller.editTaskEditNotes("not")

            controller.saveTaskEdit()

            val editor = assertNotNull(controller.taskEditorState(), "the panel was closed by a refusal")
            assertEquals(TaskEditFailure.QUANTITY_BELOW_PROGRESS, editor.failure)
            assertEquals("2", editor.quantityText)
            assertEquals("not", editor.notes)
            assertFalse(editor.isSaving)
            collecting.cancelAndJoin()
        }

    @Test
    fun `only a whole number greater than zero counts as a total`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()

            listOf("0", "-3", "1.5", "12a", "99999999999").forEach { typed ->
                controller.editTaskEditQuantity(typed)
                val editor = assertNotNull(controller.taskEditorState())
                assertNull(editor.quantity, "'$typed' was taken as a total")
                assertFalse(editor.canSave, "'$typed' let the task be saved")
                assertEquals(typed, editor.quantityText)
            }
            collecting.cancelAndJoin()
        }

    @Test
    fun `a blank name or one with a line ending cannot be saved`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()

            listOf("   ", "gri\ntoken").forEach { typed ->
                controller.editTaskName(typed)
                assertFalse(assertNotNull(controller.taskEditorState()).canSave, "'$typed' could be saved as a name")
            }
            collecting.cancelAndJoin()
        }

    // ----------------------------------------- turning a task back into text

    @Test
    fun `converting asks first and writes nothing until it is answered`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val editing = FakeTaskEditing()
            val controller = controllerOf(FakeTable(listOf(row)), taskEditing = editing)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)

            controller.beginConvertToText()

            assertIs<CellWork.ConfirmingConvert>(controller.state.work)
            assertTrue(editing.converted.isEmpty(), "the task was converted before anyone agreed to it")
            collecting.cancelAndJoin()
        }

    @Test
    fun `the question says whether there is a history to lose`() =
        runBlocking<Unit> {
            listOf(false, true).forEach { hasProgress ->
                val (row, taskId) = rowWithTask(hasProgress = hasProgress)
                val controller = controllerOf(FakeTable(listOf(row)))
                val collecting = collect(controller)
                controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)

                controller.beginConvertToText()

                assertEquals(hasProgress, assertIs<CellWork.ConfirmingConvert>(controller.state.work).hasProgress)
                collecting.cancelAndJoin()
            }
        }

    @Test
    fun `answering yes converts the task and closes everything`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val editing = FakeTaskEditing()
            val controller = controllerOf(FakeTable(listOf(row)), taskEditing = editing)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginConvertToText()

            controller.confirmConvertToText()

            assertEquals(listOf(taskId), editing.converted)
            assertNull(controller.state.work, "something stayed open over a task that is gone")
            collecting.cancelAndJoin()
        }

    @Test
    fun `saying no leaves the task exactly as it was`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val editing = FakeTaskEditing()
            val controller = controllerOf(FakeTable(listOf(row)), taskEditing = editing)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginConvertToText()

            controller.closeInnermost()

            assertTrue(editing.converted.isEmpty(), "saying no converted the task anyway")
            assertIs<CellWork.TaskMenu>(controller.state.work, "saying no closed more than the question")
            collecting.cancelAndJoin()
        }

    // ------------------------------------------------- one layer at a time

    @Test
    fun `escape closes the innermost surface and no more`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()

            controller.closeInnermost()
            assertIs<CellWork.TaskMenu>(controller.state.work, "closing the panel closed the menu too")

            controller.closeInnermost()
            assertNull(controller.state.work, "closing the menu left something open")
            collecting.cancelAndJoin()
        }

    @Test
    fun `the confirmation unwinds to the menu and then to nothing`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginConvertToText()

            controller.closeInnermost()
            assertIs<CellWork.TaskMenu>(controller.state.work)
            controller.closeInnermost()
            assertNull(controller.state.work)
            collecting.cancelAndJoin()
        }

    @Test
    fun `giving up on a new task goes back to the cell rather than closing it`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            controller.beginTaskComposer(0, 6)
            assertIs<CellWork.MakingTask>(controller.state.work)

            controller.closeInnermost()

            assertIs<CellWork.WritingText>(controller.state.work, "giving up on the task closed the cell")
            collecting.cancelAndJoin()
        }

    // ------------------------------------------ what a fresh list may disturb

    @Test
    fun `a fresh list leaves an open edit panel exactly where it was`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val other = row("Wingspan")
            val table = FakeTable(listOf(row, other))
            val controller = controllerOf(table)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            controller.editTaskName("Şövalye")
            controller.editTaskEditNotes("iki yedek")
            val before = assertNotNull(controller.taskEditorState())

            table.rows.value = listOf(row, other.copy(gameName = "Wingspan Avrupa"))
            settle()

            assertEquals(before, controller.taskEditorState(), "a change to another game disturbed the panel")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a menu over a task that has gone closes itself`() =
        runBlocking<Unit> {
            // Converted somewhere else, or its game deleted: there is nothing
            // left for the menu to act on, so it does not sit over a gap.
            val (row, taskId) = rowWithTask()
            val table = FakeTable(listOf(row))
            val controller = controllerOf(table)
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()

            table.rows.value = listOf(row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight")))
            settle()

            assertNull(controller.state.work, "a panel stayed open over a task that is gone")
            collecting.cancelAndJoin()
        }

    @Test
    fun `changing the view is refused while a task panel is open`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            controller.editTaskName("Şövalye")
            val before = assertNotNull(controller.taskEditorState())

            controller.showView(GameTableView.COMPLETED)

            assertEquals(GameTableView.ONGOING, controller.state.view)
            assertTrue(controller.state.blockedByEditor)
            assertEquals(before, controller.taskEditorState(), "the panel lost what had been typed")
            collecting.cancelAndJoin()
        }

    @Test
    fun `a refused action hands the keyboard back to the open panel`() =
        runBlocking<Unit> {
            val (row, taskId) = rowWithTask()
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            val start = controller.state.focusRecall

            controller.showView(GameTableView.ALL)

            assertEquals(start + 1, controller.state.focusRecall)
            collecting.cancelAndJoin()
        }

    // ------------------------------------------ what a change may reach

    @Test
    fun `text before and after a task can be typed`() =
        runBlocking<Unit> {
            val (row, _) = rowWithTask()
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)

            controller.editCellText("Kesilecek: Knight, token")
            assertEquals("Kesilecek: Knight, token", assertNotNull(controller.editorState()).draft)

            controller.editCellText("Kesilecek: Knight, token ×14")
            assertEquals("Kesilecek: Knight, token ×14", assertNotNull(controller.editorState()).draft)
            collecting.cancelAndJoin()
        }

    @Test
    fun `a change that reaches into a task never becomes the draft`() =
        runBlocking<Unit> {
            val (row, _) = rowWithTask()
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)

            controller.editCellText("Basılacak: Knigh, token")

            val editor = assertNotNull(controller.editorState())
            assertEquals("Basılacak: Knight, token", editor.draft, "a task was eaten by the caret")
            assertEquals(CellTextFailure.CHANGE_CROSSES_A_TASK, editor.refusal)
            assertFalse(editor.hasUnsavedChanges)
            collecting.cancelAndJoin()
        }

    @Test
    fun `a selection swallowing a task is never offered as a name`() =
        runBlocking<Unit> {
            val (row, _) = rowWithTask()
            val controller = controllerOf(FakeTable(listOf(row)))
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)

            controller.beginTaskComposer(5, 20)

            assertNull(controller.composerState(), "a selection covering a task opened the task panel")
            assertEquals(
                TaskFromTextFailure.INVALID_SELECTION,
                assertNotNull(controller.editorState()).selectionFailure,
            )
            collecting.cancelAndJoin()
        }

    @Test
    fun `saving a cell is checked against what it said when it opened`() =
        runBlocking<Unit> {
            val (row, _) = rowWithTask()
            val cells = FakeCells()
            val controller = controllerOf(FakeTable(listOf(row)), cells = cells)
            val collecting = collect(controller)
            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            controller.editCellText("Kesilecek: Knight, token")

            controller.saveEditing()

            assertEquals(listOf("Basılacak: Knight, token"), cells.expectations)
            assertEquals("Kesilecek: Knight, token", cells.saved.single().third)
            collecting.cancelAndJoin()
        }
}
