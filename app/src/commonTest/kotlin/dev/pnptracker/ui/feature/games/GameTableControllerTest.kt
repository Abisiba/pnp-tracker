package dev.pnptracker.ui.feature.games

import dev.pnptracker.data.repository.CellTextEditing
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.data.repository.GameTableSource
import dev.pnptracker.data.repository.TaskCreationFromText
import dev.pnptracker.data.repository.TaskEditing
import dev.pnptracker.data.repository.TaskProgressOutcome
import dev.pnptracker.data.repository.TaskProgressing
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.WheelNudge
import dev.pnptracker.domain.colors.WheelPoint
import dev.pnptracker.domain.colors.baseColors
import dev.pnptracker.domain.games.CellPreview
import dev.pnptracker.domain.games.CellSegmentPreview
import dev.pnptracker.domain.games.CellSummary
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.games.GameCompletionSnapshot
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.games.GameTaskSnapshot
import dev.pnptracker.domain.games.TaskColorPreview
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.StageSnapshot
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskEditFailure
import dev.pnptracker.domain.tasks.TaskFlags
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import dev.pnptracker.domain.tasks.TaskProgressFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            flags: TaskFlags?,
        ): Boolean {
            failure?.let { throw TaskEditException(it, failedRow) }
            edits += EditedTask(taskId, name, colorIds, requiredQuantity, notes, trackingMode, flags)
            return true
        }

        override suspend fun convertTaskToText(taskId: EntityId): Boolean {
            failure?.let { throw TaskEditException(it) }
            converted += taskId
            return true
        }
    }

    /** A progress store that remembers what it was asked, and answers as told. */
    private class FakeTaskProgress(
        private val outcome: TaskProgressOutcome = TaskProgressOutcome.Done,
    ) : TaskProgressing {
        val completed = mutableListOf<Pair<EntityId, EntityId>>()
        val reopened = mutableListOf<EntityId>()
        val gamesCompleted = mutableListOf<Pair<EntityId, GameCompletionSnapshot?>>()

        /** What the database would say about each game, when it is asked. */
        var completions: Map<EntityId, GameCompletionSnapshot> = emptyMap()
        val reported = mutableListOf<RecordedMovement>()
        val resolved = mutableListOf<RecordedMovement>()

        /** The table has no pipeline surface; this is here to prove nothing calls it. */
        val staged = mutableListOf<EntityId>()

        /**
         * Run once, in the middle of the next call.
         *
         * Where a second press really lands: the first call has not answered
         * yet, so nothing about the task has changed that would stop it.
         */
        var whileWorking: (suspend () -> Unit)? = null

        private suspend fun answer(): TaskProgressOutcome {
            whileWorking?.let {
                whileWorking = null
                it()
            }
            return outcome
        }

        override suspend fun completeTask(
            taskId: EntityId,
            eventId: EntityId,
        ): TaskProgressOutcome {
            completed += taskId to eventId
            return answer()
        }

        override suspend fun reopenTask(taskId: EntityId): TaskProgressOutcome {
            reopened += taskId
            return answer()
        }

        override suspend fun gameCompletion(gameId: EntityId): GameCompletionSnapshot? = completions[gameId]

        override suspend fun completeGame(
            gameId: EntityId,
            expected: GameCompletionSnapshot?,
        ): TaskProgressOutcome {
            gamesCompleted += gameId to expected
            return answer()
        }

        override suspend fun reportFailure(
            eventId: EntityId,
            taskId: EntityId,
            quantity: Int,
            note: String?,
            cardReference: String?,
            stage: ProductionStage?,
        ): TaskProgressOutcome {
            reported += RecordedMovement(eventId, taskId, quantity, note, cardReference, stage)
            return answer()
        }

        override suspend fun setStageQuantities(
            taskId: EntityId,
            targets: Map<ProductionStage, Int>,
            expected: StageSnapshot?,
        ): TaskProgressOutcome {
            staged += taskId
            return answer()
        }

        override suspend fun resolveShortage(
            eventId: EntityId,
            taskId: EntityId,
            quantity: Int,
            note: String?,
            cardReference: String?,
        ): TaskProgressOutcome {
            resolved += RecordedMovement(eventId, taskId, quantity, note, cardReference, stage = null)
            return answer()
        }
    }

    private data class RecordedMovement(
        val eventId: EntityId,
        val taskId: EntityId,
        val quantity: Int,
        val note: String?,
        val cardReference: String?,
        val stage: ProductionStage?,
    )

    private data class EditedTask(
        val taskId: EntityId,
        val name: String,
        val colorIds: List<EntityId>,
        val requiredQuantity: Int?,
        val notes: String?,
        val trackingMode: TrackingMode,
        val flags: TaskFlags? = null,
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

    /** What the catalogue was asked to create. */
    private data class CreatedColor(
        val canonicalName: String,
        val hex: String,
    )

    /** Hands out whatever catalogue a test gives it, and records what was written. */
    private class FakeColors(
        colors: List<ColorSummary> = emptyList(),
    ) : ColorCatalogue {
        val colors = MutableStateFlow(colors)
        val created = mutableListOf<CreatedColor>()
        val hexLookups = mutableListOf<String>()
        var failWith: ColorSetupFailure? = null

        /** Held open so a test can act while a colour is still on its way. */
        var heldSave: CompletableDeferred<Unit>? = null

        override fun observeColors(): Flow<List<ColorSummary>> = colors

        override suspend fun colorsUsingHex(hex: String): List<ColorSummary> {
            hexLookups += hex
            return emptyList()
        }

        override suspend fun createColor(
            canonicalName: String,
            hex: String,
        ): EntityId {
            heldSave?.await()
            failWith?.let { throw ColorSetupException(it) }
            created += CreatedColor(canonicalName, hex)
            val id = IdGenerator.Random.newId()
            colors.value = colors.value + ColorSummary(id, canonicalName, hex, colors.value.size)
            return id
        }

        // The table never edits, removes or restores a colour: that belongs to
        // the colour section, and a table test reaching one of these is a test
        // asking the wrong object.
        override suspend fun editColor(
            id: EntityId,
            expectedName: String,
            expectedHex: String,
            canonicalName: String,
            hex: String,
        ): Unit = error("The table does not change colours.")

        override suspend fun usageOf(id: EntityId) = error("The table does not weigh colours.")

        override suspend fun deleteColor(id: EntityId) = error("The table does not remove colours.")

        override suspend fun previewBaseColorRestore() = error("The table does not restore colours.")

        override suspend fun restoreMissingBaseColors() = error("The table does not restore colours.")
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
        taskId: EntityId = IdGenerator.Random.newId(),
        isCompleted: Boolean = false,
        missing: Int = 0,
        poolType: PoolType = PoolType.THREE_D,
    ) = CellSegmentPreview(
        segmentId = IdGenerator.Random.newId(),
        taskId = taskId,
        text = name,
        isCompletedTask = isCompleted,
        requiredQuantity = quantity,
        poolType = poolType,
        currentMissingQuantity = missing,
    )

    private fun controllerOf(
        table: FakeTable,
        setup: FakeSetup = FakeSetup(),
        cells: FakeCells = FakeCells(),
        colors: FakeColors = FakeColors(),
        taskCreation: FakeTaskCreation = FakeTaskCreation(),
        taskEditing: FakeTaskEditing = FakeTaskEditing(),
        taskProgress: FakeTaskProgress = FakeTaskProgress(),
    ) = GameTableController(table, setup, cells, colors, taskCreation, taskEditing, taskProgress)

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

    // ------------------------------------------------- making a colour

    /** The twelve base colours, so the picker has its squares to work from. */
    private fun seededColors(): FakeColors = FakeColors(baseColors.map { ColorSummary(it.id, it.canonicalName, it.hex, it.sortOrder) })

    private fun GameTableController.creatorState(): CellWork.MakingColor? = state.work as? CellWork.MakingColor

    /** Opens the picker over whatever is open, and gives it a name to save. */
    private fun GameTableController.nameANewColor(
        target: NewColorTarget,
        name: String = "Lacivert",
    ) {
        beginColorCreation(target)
        editNewColorName(name)
    }

    @Test
    fun `a colour made from the single-colour mode is chosen on that mode's own draft`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)

            controller.nameANewColor(NewColorTarget.SingleDraft)
            controller.saveNewColor()

            val composer = assertNotNull(controller.composerState())
            val made = colors.colors.value.last()
            assertEquals("Lacivert", made.canonicalName)
            assertEquals(made.id, composer.single.colorId, "the new colour did not reach the draft it was made for")
            assertTrue(composer.rows.all { it.colorId == null }, "the batch was given a colour it never asked for")
            assertEquals(emptyList(), composer.palette.colorIds, "the several-colour list was given a colour")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a colour made from one task of a batch reaches that task and no other`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) =
                readyComposer(controller, row, colors, word = "Token", mode = TaskCreationMode.INDEPENDENT_TASKS)
            controller.addTaskRow()

            controller.nameANewColor(NewColorTarget.BatchRow(2))
            controller.saveNewColor()

            val composer = assertNotNull(controller.composerState())
            val made =
                colors.colors.value
                    .last()
                    .id
            assertEquals(made, composer.rows[2].colorId)
            assertNull(composer.rows[1].colorId, "a task nobody was making a colour for was given one")
            assertEquals(made != composer.rows[0].colorId, true, "the first task was given the new colour")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a colour made for a several-colour task goes on the end of its list`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Yarasa"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) =
                readyComposer(controller, row, colors, word = "Yarasa", mode = TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            val first = colors.colors.value[0].id
            val second = colors.colors.value[1].id
            controller.toggleMulticolorColor(first)
            controller.toggleMulticolorColor(second)

            controller.nameANewColor(NewColorTarget.MulticolorList)
            controller.saveNewColor()

            val made =
                colors.colors.value
                    .last()
                    .id
            // The end, because that is the order the name will be drawn in.
            assertEquals(listOf(first, second, made), assertNotNull(controller.composerState()).palette.colorIds)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a colour made while editing a one-colour task becomes its colour`() =
        runBlocking<Unit> {
            val piece = taskPiece("Knight", quantity = 4)
            val row = row("Harmonies", cells = mapOf(CellColumnType.THREE_D to listOf(piece)))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val collecting = collect(controller)
            val catalogue = launch { controller.observeColorCatalogue() }
            settle()
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, assertNotNull(piece.taskId))
            controller.beginTaskEdit()

            controller.nameANewColor(NewColorTarget.EditedTask)
            controller.saveNewColor()

            val made =
                colors.colors.value
                    .last()
                    .id
            // One colour, not a list of one and not a list of two: PLAN does not
            // let a task cross between the two kinds.
            assertEquals(listOf(made), assertNotNull(controller.taskEditorState()).colorIds)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a colour made while editing a several-colour task goes on the end of its list`() =
        runBlocking<Unit> {
            val colors = seededColors()
            val first = colors.colors.value[0]
            val second = colors.colors.value[1]
            val piece =
                CellSegmentPreview(
                    segmentId = IdGenerator.Random.newId(),
                    taskId = IdGenerator.Random.newId(),
                    text = "Yarasa",
                    requiredQuantity = 7,
                    colors =
                        listOf(
                            TaskColorPreview(colorId = first.id, hex = first.hex, canonicalName = first.canonicalName),
                            TaskColorPreview(colorId = second.id, hex = second.hex, canonicalName = second.canonicalName),
                        ),
                )
            val row = row("Harmonies", cells = mapOf(CellColumnType.THREE_D to listOf(piece)))
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val collecting = collect(controller)
            val catalogue = launch { controller.observeColorCatalogue() }
            settle()
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, assertNotNull(piece.taskId))
            controller.beginTaskEdit()

            controller.nameANewColor(NewColorTarget.EditedTask)
            controller.saveNewColor()

            val made =
                colors.colors.value
                    .last()
                    .id
            assertEquals(listOf(first.id, second.id, made), assertNotNull(controller.taskEditorState()).colorIds)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `making a colour leaves the other two modes' drafts exactly as they were`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors, word = "Token")
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            controller.editTaskQuantity(0, "9")
            controller.editTaskNotes(0, "toplu not")
            controller.chooseCreationMode(TaskCreationMode.SINGLE_ITEM_MULTICOLOR)
            controller.editMulticolorQuantity("4")
            controller.chooseCreationMode(TaskCreationMode.SINGLE_COLOR)
            val before = assertNotNull(controller.composerState())

            controller.nameANewColor(NewColorTarget.SingleDraft)
            controller.saveNewColor()

            val after = assertNotNull(controller.composerState())
            assertEquals(before.rows, after.rows, "the batch was changed by a colour made in another mode")
            assertEquals(before.palette, after.palette, "the several-colour draft was changed from another mode")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    /** Opens the picker over the edit panel of a task, with its save held open. */
    private suspend fun CoroutineScope.pickerOverAnEditedTask(
        colors: FakeColors,
        held: CompletableDeferred<Unit>,
    ): Triple<GameTableController, FakeTable, Pair<Job, Job>> {
        val piece = taskPiece("Knight", quantity = 4)
        val row = row("Harmonies", cells = mapOf(CellColumnType.THREE_D to listOf(piece)))
        colors.heldSave = held
        val table = FakeTable(listOf(row))
        val controller = controllerOf(table, colors = colors)
        val collecting = collect(controller)
        val catalogue = launch { controller.observeColorCatalogue() }
        settle()
        controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, assertNotNull(piece.taskId))
        controller.beginTaskEdit()
        controller.nameANewColor(NewColorTarget.EditedTask)
        return Triple(controller, table, collecting to catalogue)
    }

    @Test
    fun `a colour whose draft has gone while it was being written is kept, and said to be kept`() =
        runBlocking<Unit> {
            val colors = seededColors()
            val held = CompletableDeferred<Unit>()
            val (controller, table, jobs) = pickerOverAnEditedTask(colors, held)

            val saving = CoroutineScope(Job() + Dispatchers.Unconfined).launch { controller.saveNewColor() }
            // The game goes while the colour is still on its way, so the panel
            // the colour was made for is not there to receive it.
            table.rows.value = emptyList()
            settle()
            held.complete(Unit)
            saving.join()

            assertEquals(1, colors.created.size, "the colour was not written")
            assertTrue(colors.colors.value.any { it.canonicalName == "Lacivert" }, "the colour did not reach the catalogue")
            assertNull(controller.state.work, "a panel bound to something that is gone was left standing")
            assertEquals(
                "Lacivert",
                assertNotNull(controller.state.savedColorNotice, "the user was not told what happened").colorName,
            )
            jobs.first.cancelAndJoin()
            jobs.second.cancelAndJoin()
        }

    @Test
    fun `the notice about a colour with nowhere to go can be put away`() =
        runBlocking<Unit> {
            val colors = seededColors()
            val held = CompletableDeferred<Unit>()
            val (controller, table, jobs) = pickerOverAnEditedTask(colors, held)
            val saving = CoroutineScope(Job() + Dispatchers.Unconfined).launch { controller.saveNewColor() }
            table.rows.value = emptyList()
            settle()
            held.complete(Unit)
            saving.join()
            assertNotNull(controller.state.savedColorNotice)

            controller.acknowledgeSavedColor()

            assertNull(controller.state.savedColorNotice)
            jobs.first.cancelAndJoin()
            jobs.second.cancelAndJoin()
        }

    @Test
    fun `a picker whose panel has gone does not put the colour on whatever opens next`() =
        runBlocking<Unit> {
            val colors = seededColors()
            val held = CompletableDeferred<Unit>()
            val (controller, table, jobs) = pickerOverAnEditedTask(colors, held)
            val saving = CoroutineScope(Job() + Dispatchers.Unconfined).launch { controller.saveNewColor() }
            table.rows.value = emptyList()
            settle()

            // Something else is opened before the answer comes back.
            val other = row("Wingspan", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            table.rows.value = listOf(other)
            settle()
            controller.beginEditing(other.gameId, CellColumnType.THREE_D)
            controller.beginTaskComposer(0, "Knight".length)
            held.complete(Unit)
            saving.join()

            val composer = assertNotNull(controller.composerState())
            assertNull(composer.single.colorId, "a late answer landed on a panel it was never made for")
            assertTrue(composer.rows.all { it.colorId == null })
            assertNotNull(controller.state.savedColorNotice)
            jobs.first.cancelAndJoin()
            jobs.second.cancelAndJoin()
        }

    @Test
    fun `giving up on the picker writes nothing and leaves the draft alone`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            val before = assertNotNull(controller.composerState())

            controller.nameANewColor(NewColorTarget.SingleDraft)
            controller.setNewColorBrightness(0.2f)
            controller.cancelColorCreation()

            assertEquals(0, colors.created.size, "giving up on the picker wrote a colour")
            assertEquals(before, assertNotNull(controller.composerState()), "giving up on a colour changed the task")
            assertNull(controller.creatorState(), "the picker stayed open")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a colour is kept when the task it was made for is given up on`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.nameANewColor(NewColorTarget.SingleDraft)
            controller.saveNewColor()

            controller.cancelTaskComposer()

            // PLAN 5.7: a saved colour is a catalogue record from the moment it
            // is written, so it does not go away with a task nobody made.
            assertEquals(1, colors.created.size)
            assertTrue(colors.colors.value.any { it.canonicalName == "Lacivert" }, "the colour left with the task")
            assertEquals(0, creation.calls, "giving up on the task wrote one")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a task that will not save leaves the colour it was given standing`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val creation = FakeTaskCreation(failure = TaskFromTextFailure.COULD_NOT_SAVE)
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.nameANewColor(NewColorTarget.SingleDraft)
            controller.saveNewColor()
            val made =
                colors.colors.value
                    .last()
                    .id

            controller.saveTask()

            assertTrue(colors.colors.value.any { it.id == made }, "a refused task took a saved colour with it")
            val composer = assertNotNull(controller.composerState())
            assertEquals(made, composer.single.colorId, "the refusal lost the colour the user had chosen")
            assertEquals(TaskFromTextFailure.COULD_NOT_SAVE, composer.failure)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a refused colour leaves the name, the wheel and the brightness where they were`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            colors.failWith = ColorSetupFailure.NAME_ALREADY_USED
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.nameANewColor(NewColorTarget.SingleDraft, name = "Gri")
            controller.setNewColorBrightness(0.42f)
            controller.nudgeNewColorWheel(WheelNudge.HUE_FORWARD)
            val before = assertNotNull(controller.creatorState()).composer

            controller.saveNewColor()

            val creator = assertNotNull(controller.creatorState(), "the picker closed on a colour that was never saved")
            assertEquals(before, creator.composer, "the refusal took the answers the user had already given")
            assertEquals(ColorSetupFailure.NAME_ALREADY_USED, creator.failure)
            assertTrue(!creator.isSaving, "the saving flag was left stuck on")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a second click while the first colour is still on its way writes one colour`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val held = CompletableDeferred<Unit>()
            colors.heldSave = held
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.nameANewColor(NewColorTarget.SingleDraft)

            val first = CoroutineScope(Job() + Dispatchers.Unconfined).launch { controller.saveNewColor() }
            assertTrue(assertNotNull(controller.creatorState()).isSaving, "the first save is not in flight")

            // The button and Ctrl+Enter both reach the same call, so this is both.
            controller.saveNewColor()
            controller.saveNewColor()
            held.complete(Unit)
            first.join()

            assertEquals(1, colors.created.size, "one insistent click became two colours")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `nothing can be typed into a picker that is already saving`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val held = CompletableDeferred<Unit>()
            colors.heldSave = held
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.nameANewColor(NewColorTarget.SingleDraft)
            val saving = CoroutineScope(Job() + Dispatchers.Unconfined).launch { controller.saveNewColor() }
            val inFlight = assertNotNull(controller.creatorState()).composer

            controller.editNewColorName("Bordo")
            controller.setNewColorBrightness(0.1f)
            controller.cancelColorCreation()

            assertEquals(inFlight, assertNotNull(controller.creatorState()).composer, "a colour changed under its own save")
            held.complete(Unit)
            saving.join()
            assertEquals("Lacivert", colors.created.single().canonicalName)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `turning the wheel in a task panel asks the database nothing`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.nameANewColor(NewColorTarget.SingleDraft)

            repeat(400) { step ->
                controller.moveNewColorOnWheel(WheelPoint(x = (step % 60) - 30f, y = 30f - (step % 60)), radius = 55f)
                controller.setNewColorBrightness(step / 400f)
            }

            assertEquals(emptyList(), colors.hexLookups, "a drag went to the database")
            assertEquals(0, colors.created.size)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `Escape closes the picker first and leaves the task panel standing`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.nameANewColor(NewColorTarget.SingleDraft)

            controller.closeInnermost()
            assertNull(controller.creatorState(), "the picker did not close")
            assertNotNull(controller.composerState(), "the panel underneath went down with the picker")

            controller.closeInnermost()
            assertNull(controller.composerState(), "the panel did not close")
            assertNotNull(controller.editorState(), "the cell went down with the panel")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a picker with something in it counts as work that closing would throw away`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)

            controller.beginColorCreation(NewColorTarget.SingleDraft)
            // A form nobody has touched holds nothing, so a click away may close
            // it; one with a name or a moved wheel in it may not.
            assertTrue(!assertNotNull(controller.state.work).hasUnsavedChanges)

            controller.editNewColorName("L")
            assertTrue(assertNotNull(controller.state.work).hasUnsavedChanges)

            controller.editNewColorName("")
            controller.nudgeNewColorWheel(WheelNudge.SATURATION_OUT)
            assertTrue(
                assertNotNull(controller.state.work).hasUnsavedChanges,
                "a moved wheel could be thrown away by a click somewhere else",
            )
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a fresh catalogue from the database leaves an open picker exactly as it is`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val table = FakeTable(listOf(row))
            val controller = controllerOf(table, colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.nameANewColor(NewColorTarget.SingleDraft, name = "Yarı yazılmış")
            controller.setNewColorBrightness(0.33f)
            val before = assertNotNull(controller.creatorState())

            colors.colors.value = colors.colors.value + color("Bordo", "#880E4F", sortOrder = 12)
            table.rows.value = listOf(row)
            settle()

            assertEquals(before, controller.creatorState(), "a list arriving from the database disturbed the picker")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a picker opens on the colour its place already holds`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            val chosen = colors.colors.value.first { it.canonicalName == "Mavi" }
            controller.chooseTaskColor(0, chosen.id)

            controller.beginColorCreation(NewColorTarget.SingleDraft)

            val creator = assertNotNull(controller.creatorState())
            assertEquals(chosen.hex, creator.composer.hex, "the wheel did not open on the colour that is there")
            assertEquals("", creator.composer.name, "the old colour's name was offered as the new one's")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a picker opened where there is no colour yet opens on the first square`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Token"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) =
                readyComposer(controller, row, colors, word = "Token", mode = TaskCreationMode.INDEPENDENT_TASKS)

            controller.beginColorCreation(NewColorTarget.BatchRow(1))

            assertEquals("#FFFFFF", assertNotNull(controller.creatorState()).composer.hex)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a square takes the whole colour into the picker`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.nameANewColor(NewColorTarget.SingleDraft)

            controller.chooseNewColorBase(
                colors.colors.value
                    .first { it.canonicalName == "Yeşil" }
                    .id,
            )

            assertEquals("#43A047", assertNotNull(controller.creatorState()).composer.hex)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `the value written is the one the picker was showing`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.nameANewColor(NewColorTarget.SingleDraft, name = "  Lacivert  ")
            controller.chooseNewColorBase(
                colors.colors.value
                    .first { it.canonicalName == "Mor" }
                    .id,
            )
            controller.setNewColorBrightness(0.5f)
            val shown = assertNotNull(controller.creatorState()).composer.hex

            controller.saveNewColor()

            assertEquals(CreatedColor("Lacivert", shown), colors.created.single())
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a colour with no name never reaches the catalogue`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)

            listOf("", " ", "   ").forEach { attempt ->
                controller.nameANewColor(NewColorTarget.SingleDraft, name = attempt)
                controller.saveNewColor()
                assertTrue(!assertNotNull(controller.creatorState()).composer.canSave, "'$attempt' was accepted")
            }

            assertEquals(0, colors.created.size)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a saved colour turns up in the catalogue the panel picks from`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.nameANewColor(NewColorTarget.SingleDraft, name = "Lacivert")
            controller.saveNewColor()
            settle()

            controller.editTaskColorQuery(0, "laci")
            assertEquals(listOf("Lacivert"), controller.colorsOffered(0).map { it.canonicalName })
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `the picker cannot be opened over anything but a task panel`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val collecting = collect(controller)
            val catalogue = launch { controller.observeColorCatalogue() }
            settle()

            controller.beginColorCreation(NewColorTarget.SingleDraft)
            assertNull(controller.creatorState(), "a picker opened over nothing at all")

            controller.beginEditing(row.gameId, CellColumnType.THREE_D)
            controller.beginColorCreation(NewColorTarget.SingleDraft)
            assertNull(controller.creatorState(), "a picker opened over a cell with no panel in it")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    // ------------------------------- a colour removed while a draft is open

    @Test
    fun `a colour removed elsewhere is left where the user put it`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            val chosen = assertNotNull(controller.composerState()).single.colorId

            colors.colors.value = colors.colors.value.filterNot { it.id == chosen }
            settle()

            val composer = assertNotNull(controller.composerState())
            assertEquals(chosen, composer.single.colorId, "the choice was taken out from under the user")
            assertEquals("15", composer.single.quantityText, "the rest of the draft went with it")
            assertNotNull(controller.state.work, "the panel closed itself")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a draft naming a colour that is gone is not allowed to save`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            val chosen = assertNotNull(controller.composerState()).single.colorId

            colors.colors.value = colors.colors.value.filterNot { it.id == chosen }
            settle()
            controller.saveTask()

            assertEquals(emptyList(), creation.created, "a task was written pointing at a colour that is gone")
            assertEquals(setOf(chosen), controller.state.strandedColorIds)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `choosing another colour lets the draft be saved again`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            val chosen = assertNotNull(controller.composerState()).single.colorId

            colors.colors.value = colors.colors.value.filterNot { it.id == chosen }
            settle()
            val replacement =
                controller.state.colors
                    .first()
                    .id
            controller.chooseTaskColor(0, replacement)
            controller.saveTask()

            assertEquals(emptySet(), controller.state.strandedColorIds)
            assertEquals(listOf(replacement), creation.created.single().colorIds)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `only the mode being worked in decides whether a draft can be saved`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            // A colour chosen in the batch mode, then left behind for another mode.
            controller.chooseCreationMode(TaskCreationMode.INDEPENDENT_TASKS)
            val batchColor =
                controller.state.colors
                    .last()
                    .id
            controller.chooseTaskColor(0, batchColor)
            controller.chooseCreationMode(TaskCreationMode.SINGLE_COLOR)

            colors.colors.value = colors.colors.value.filterNot { it.id == batchColor }
            settle()

            assertEquals(
                emptySet(),
                controller.state.strandedColorIds,
                "a colour missing from a mode nobody is looking at stopped the one they are",
            )
            controller.saveTask()
            assertEquals(1, creation.created.size)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a colour that was only renamed keeps the draft saveable`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            val chosen = assertNotNull(controller.composerState()).single.colorId

            colors.colors.value =
                colors.colors.value.map { if (it.id == chosen) it.copy(canonicalName = "Kar", hex = "#FAFAFA") else it }
            settle()

            assertEquals(emptySet(), controller.state.strandedColorIds)
            assertEquals(
                "Kar" to "#FAFAFA",
                controller.state.colors
                    .first { it.id == chosen }
                    .let { it.canonicalName to it.hex },
                "the draft would not show the new name and value",
            )
            controller.saveTask()
            assertEquals(listOf(chosen), creation.created.single().colorIds)
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `a colour just made from the panel is not mistaken for one that was removed`() =
        runBlocking<Unit> {
            val row = row("Harmonies", cells = cellsOf(CellColumnType.THREE_D to "Knight"))
            val colors = seededColors()
            val creation = FakeTaskCreation()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskCreation = creation)
            val (collecting, catalogue) = readyComposer(controller, row, colors)
            controller.nameANewColor(NewColorTarget.SingleDraft)

            controller.saveNewColor()
            // The catalogue stream has not been given a chance to catch up yet.
            controller.saveTask()

            assertEquals(emptySet(), controller.state.strandedColorIds)
            assertEquals(1, creation.created.size, "the task the user had just given a colour would not save")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    @Test
    fun `an open task edit will not save a colour that has been removed`() =
        runBlocking<Unit> {
            val grey = baseColors[2]
            val (row, taskId) =
                rowWithTask(colors = listOf(TaskColorPreview(grey.id, grey.canonicalName, grey.hex)))
            val colors = seededColors()
            val editing = FakeTaskEditing()
            val controller = controllerOf(FakeTable(listOf(row)), colors = colors, taskEditing = editing)
            val collecting = collect(controller)
            val catalogue = launch { controller.observeColorCatalogue() }
            settle()
            controller.openTaskMenu(row.gameId, CellColumnType.THREE_D, taskId)
            controller.beginTaskEdit()
            controller.editTaskName("Token II")

            colors.colors.value = colors.colors.value.filterNot { it.id == grey.id }
            settle()
            controller.saveTaskEdit()

            assertEquals(emptyList(), editing.edits, "an edit was written naming a colour that is gone")
            assertEquals(setOf(grey.id), controller.state.strandedColorIds)
            val editor = assertNotNull(controller.taskEditorState())
            assertEquals(listOf(grey.id), editor.colorIds, "the colour was quietly dropped from the task")
            assertEquals("Token II", editor.name, "the rest of the edit was lost")
            collecting.cancelAndJoin()
            catalogue.cancelAndJoin()
        }

    // ------------------------------------- finishing work, and what it still owes

    /**
     * One game holding one task, and everything a test needs to amend it.
     *
     * The row itself comes back, not just its identity, because a test that
     * wants the task to change has to hand the same game back with a different
     * piece in it: building a fresh row would invent a new game, and the open
     * menu would rightly decide its task had gone.
     */
    private class ProgressFixture(
        val taskId: EntityId,
        val table: FakeTable,
        val piece: CellSegmentPreview,
        val gameRow: GameTableRow,
        val columnType: CellColumnType,
    ) {
        val gameId: EntityId get() = gameRow.gameId

        /** The same game, with this piece in place of the one it had. */
        fun holding(piece: CellSegmentPreview?) {
            table.rows.value =
                listOf(
                    gameRow.copy(
                        cells =
                            gameRow.cells.map { cell ->
                                if (cell.columnType == columnType) cell.copy(segments = listOfNotNull(piece)) else cell
                            },
                    ),
                )
        }
    }

    private fun progressFixture(
        isCompleted: Boolean = false,
        missing: Int = 0,
        poolType: PoolType = PoolType.THREE_D,
        gameCompleted: Boolean = false,
        columnType: CellColumnType = CellColumnType.THREE_D,
    ): ProgressFixture {
        val taskId = IdGenerator.Random.newId()
        val piece =
            taskPiece("Token", quantity = 40, taskId = taskId, isCompleted = isCompleted, missing = missing, poolType = poolType)
        val gameRow = row("Harmonies", isCompleted = gameCompleted, cells = mapOf(columnType to listOf(piece)))
        return ProgressFixture(taskId, FakeTable(listOf(gameRow)), piece, gameRow, columnType)
    }

    /**
     * A controller looking at every game, whichever way they stand.
     *
     * A finished game is not in the view the table opens on, so a test about a
     * task inside one has to be looking somewhere it can see it.
     */
    private suspend fun CoroutineScope.watching(
        fixture: ProgressFixture,
        progress: FakeTaskProgress,
    ): Pair<GameTableController, Job> {
        val controller = controllerOf(fixture.table, taskProgress = progress)
        val job = collect(controller)
        controller.showView(GameTableView.ALL)
        return controller to job
    }

    // ------------------------------------------------------ finishing a game

    /** The confirmation open on a row, which is where PLAN 12.9's question lives. */
    private fun confirmation(controller: GameTableController): RowWork.ConfirmingGameCompletion = assertIs(controller.state.rowWork)

    /** A game holding [open] unfinished tasks and [done] finished ones. */
    private fun gameOf(
        open: Int,
        done: Int = 0,
        isCompleted: Boolean = false,
        name: String = "Harmonies",
    ): GameTableRow {
        val pieces =
            List(open) { taskPiece("Açık $it", quantity = 20) } +
                List(done) { taskPiece("Bitmiş $it", quantity = 20, isCompleted = true) }
        return row(name, isCompleted = isCompleted, cells = mapOf(CellColumnType.THREE_D to pieces))
    }

    /** A controller looking at some games, with what it was built from to hand. */
    private class Looking(
        val controller: GameTableController,
        val job: Job,
        val progress: FakeTaskProgress,
        val table: FakeTable,
    ) {
        operator fun component1() = controller

        operator fun component2() = job

        operator fun component3() = progress
    }

    /**
     * What the database would say about a game, built from the row shown for it.
     *
     * The fake stands in for the read the controller now makes, so the test says
     * what that read finds. The pipelines are empty here because a game row does
     * not carry them and these tests are not about them; what a pipeline moving
     * under an open question does is settled against a real database in
     * `GameCompletionTest`.
     */
    private fun completionOf(row: GameTableRow): GameCompletionSnapshot =
        GameCompletionSnapshot(
            isGameCompleted = row.isCompleted,
            tasks =
                row.cells.flatMap { cell ->
                    cell.tasks.map { task ->
                        GameTaskSnapshot(
                            taskId = requireNotNull(task.taskId),
                            isCompleted = task.isCompletedTask,
                            currentMissingQuantity = task.currentMissingQuantity,
                            requiredQuantity = task.requiredQuantity,
                        )
                    }
                },
        )

    private suspend fun CoroutineScope.looking(
        rows: List<GameTableRow>,
        progress: FakeTaskProgress = FakeTaskProgress(),
    ): Looking {
        val table = FakeTable(rows)
        progress.completions = rows.associate { it.gameId to completionOf(it) }
        val controller = controllerOf(table, taskProgress = progress)
        return Looking(controller, collect(controller), progress, table)
    }

    @Test
    fun `a game with nothing unfinished in it is finished without being asked about`() =
        runBlocking<Unit> {
            // PLAN 12.9: the question is only asked where there is work to
            // declare finished on the user's behalf.
            val game = gameOf(open = 0, done = 2)
            val (controller, job, progress) = looking(listOf(game))

            controller.completeGame(game.gameId)

            assertNull(controller.state.rowWork, "a question was asked about a game with nothing left in it")
            assertEquals(listOf(game.gameId), progress.gamesCompleted.map { it.first })
            job.cancel()
        }

    @Test
    fun `a game with no tasks at all is finished without being asked about`() =
        runBlocking<Unit> {
            val game = gameOf(open = 0)
            val (controller, job, progress) = looking(listOf(game))

            controller.completeGame(game.gameId)

            assertNull(controller.state.rowWork)
            assertEquals(1, progress.gamesCompleted.size)
            job.cancel()
        }

    @Test
    fun `a game with unfinished work asks before anything is written`() =
        runBlocking<Unit> {
            val game = gameOf(open = 3, done = 1)
            val (controller, job, progress) = looking(listOf(game))

            controller.completeGame(game.gameId)

            val asked = confirmation(controller)
            assertEquals(game.gameId, asked.gameId)
            assertEquals("Harmonies", asked.gameName)
            assertEquals(3, asked.unfinishedCount, "the user is told the wrong number of unfinished tasks")
            assertTrue(progress.gamesCompleted.isEmpty(), "the question was asked after the writing")
            job.cancel()
        }

    @Test
    fun `saying no writes nothing and leaves the game alone`() =
        runBlocking<Unit> {
            val game = gameOf(open = 2)
            val (controller, job, progress) = looking(listOf(game))
            controller.completeGame(game.gameId)

            controller.closeInnermost()

            assertNull(controller.state.rowWork, "the question stayed open")
            assertTrue(progress.gamesCompleted.isEmpty(), "`Hayır` wrote something")
            job.cancel()
        }

    @Test
    fun `saying yes sends the game and the picture it was asked about`() =
        runBlocking<Unit> {
            val game = gameOf(open = 2, done = 1)
            val (controller, job, progress) = looking(listOf(game))
            controller.completeGame(game.gameId)
            val asked = confirmation(controller).expected

            controller.confirmGameCompletion()

            val (gameId, expected) = progress.gamesCompleted.single()
            assertEquals(game.gameId, gameId)
            assertEquals(asked, expected, "the answer was sent without the question it belonged to")
            assertNull(controller.state.rowWork, "the question stayed open over a game that is finished")
            job.cancel()
        }

    @Test
    fun `the picture carries every task of every column of the row`() =
        runBlocking<Unit> {
            val game =
                row(
                    "Harmonies",
                    cells =
                        mapOf(
                            CellColumnType.THREE_D to listOf(taskPiece("Token", quantity = 20, missing = 3)),
                            CellColumnType.CARD to listOf(taskPiece("Kartlar", quantity = 40, isCompleted = true)),
                        ),
                )
            val (controller, job, _) = looking(listOf(game))

            controller.completeGame(game.gameId)

            val expected = confirmation(controller).expected
            assertEquals(2, expected.tasks.size, "a column's work was left out of what the user is agreeing to")
            assertEquals(1, expected.unfinishedCount)
            assertEquals(3, expected.tasks.single { !it.isCompleted }.currentMissingQuantity)
            job.cancel()
        }

    @Test
    fun `two answers arriving together make one transaction`() =
        runBlocking<Unit> {
            val game = gameOf(open = 2)
            val progress = FakeTaskProgress()
            val (controller, job, _) = looking(listOf(game), progress)
            controller.completeGame(game.gameId)
            // Where a second Enter really lands: the first has not answered yet.
            progress.whileWorking = { controller.confirmGameCompletion() }

            controller.confirmGameCompletion()

            assertEquals(1, progress.gamesCompleted.size, "one answer became two transactions")
            job.cancel()
        }

    @Test
    fun `a refusal leaves the question standing with what happened on it`() =
        runBlocking<Unit> {
            val game = gameOf(open = 2)
            val progress = FakeTaskProgress(TaskProgressOutcome.Refused(TaskProgressFailure.STALE_GAME_COMPLETION))
            val (controller, job, _) = looking(listOf(game), progress)
            controller.completeGame(game.gameId)

            controller.confirmGameCompletion()

            val standing = confirmation(controller)
            assertEquals(TaskProgressFailure.STALE_GAME_COMPLETION, standing.failure)
            assertFalse(standing.isSaving, "the question was left looking as though it were still sending")
            job.cancel()
        }

    @Test
    fun `a game refused without a question says so on its own row`() =
        runBlocking<Unit> {
            val game = gameOf(open = 0, done = 1)
            val progress = FakeTaskProgress(TaskProgressOutcome.Refused(TaskProgressFailure.GAME_NOT_AVAILABLE))
            val (controller, job, _) = looking(listOf(game), progress)

            controller.completeGame(game.gameId)

            assertEquals(game.gameId to TaskProgressFailure.GAME_NOT_AVAILABLE, controller.state.gameCompletionFailure)
            controller.acknowledgeGameCompletionFailure()
            assertNull(controller.state.gameCompletionFailure)
            job.cancel()
        }

    @Test
    fun `a game that is already finished is not offered the tick again`() =
        runBlocking<Unit> {
            // PLAN describes finishing a game and describes a shortage reopening
            // one. It describes nothing that simply takes the mark back.
            val game = gameOf(open = 1, isCompleted = true)
            val (controller, job, progress) = looking(listOf(game))
            controller.showView(GameTableView.ALL)

            controller.completeGame(game.gameId)

            assertTrue(progress.gamesCompleted.isEmpty(), "a finished game was written to again")
            assertNull(controller.state.rowWork)
            job.cancel()
        }

    @Test
    fun `a question about a game that has gone closes with it`() =
        runBlocking<Unit> {
            val game = gameOf(open = 2)
            val looking = looking(listOf(game))
            val controller = looking.controller
            controller.completeGame(game.gameId)
            assertNotNull(controller.state.rowWork)

            looking.table.rows.value = emptyList()
            settle()

            assertNull(controller.state.rowWork, "a question stayed open over a game that is no longer there")
            looking.job.cancel()
        }

    @Test
    fun `a fresh list does not close a question about a game that is still there`() =
        runBlocking<Unit> {
            val game = gameOf(open = 2)
            val looking = looking(listOf(game))
            val controller = looking.controller
            controller.completeGame(game.gameId)
            val asked = confirmation(controller)

            looking.table.rows.value = listOf(game, gameOf(open = 1, name = "Wingspan"))
            settle()

            assertEquals(asked, confirmation(controller), "an unrelated change closed the question")
            looking.job.cancel()
        }

    @Test
    fun `finishing a game sends the keyboard to the next row it can still reach`() =
        runBlocking<Unit> {
            val first = gameOf(open = 0, done = 1, name = "Harmonies")
            val second = gameOf(open = 0, done = 1, name = "Wingspan")
            val (controller, job, _) = looking(listOf(first, second))

            controller.completeGame(first.gameId)

            assertEquals(RowFocusTarget.Game(second.gameId), controller.state.focusAfterRow)
            job.cancel()
        }

    @Test
    fun `finishing the last row sends the keyboard back to the filter`() =
        runBlocking<Unit> {
            val only = gameOf(open = 0, done = 1)
            val (controller, job, _) = looking(listOf(only))

            controller.completeGame(only.gameId)

            assertEquals(RowFocusTarget.ViewFilter, controller.state.focusAfterRow)
            job.cancel()
        }

    @Test
    fun `finishing a game in a view it stays in moves nothing`() =
        runBlocking<Unit> {
            val game = gameOf(open = 0, done = 1)
            val (controller, job, _) = looking(listOf(game))
            controller.showView(GameTableView.ALL)

            controller.completeGame(game.gameId)

            assertNull(controller.state.focusAfterRow, "the keyboard was moved off a row that is still there")
            job.cancel()
        }

    @Test
    fun `a question about a game refuses every other surface while it is open`() =
        runBlocking<Unit> {
            val game = gameOf(open = 2)
            val (controller, job, _) = looking(listOf(game))
            controller.completeGame(game.gameId)

            controller.beginEditing(game.gameId, CellColumnType.NOTES)

            assertNull(controller.state.work, "a cell opened underneath an open question")
            assertTrue(controller.state.blockedByEditor)
            assertNotNull(controller.state.rowWork)
            job.cancel()
        }

    @Test
    fun `an open cell refuses the question rather than losing what is typed`() =
        runBlocking<Unit> {
            val game = gameOf(open = 2)
            val (controller, job, progress) = looking(listOf(game))
            controller.beginEditing(game.gameId, CellColumnType.THREE_D)

            controller.completeGame(game.gameId)

            assertNull(controller.state.rowWork, "a question opened over a cell being typed in")
            assertTrue(controller.state.blockedByEditor)
            assertTrue(progress.gamesCompleted.isEmpty())
            job.cancel()
        }

    @Test
    fun `a view cannot be changed out from under an open question`() =
        runBlocking<Unit> {
            val game = gameOf(open = 2)
            val (controller, job, _) = looking(listOf(game))
            controller.completeGame(game.gameId)

            controller.showView(GameTableView.ALL)

            assertEquals(GameTableView.ONGOING, controller.state.view)
            assertNotNull(controller.state.rowWork)
            job.cancel()
        }

    @Test
    fun `a row keeps its place in the table while something is open on it`() =
        runBlocking<Unit> {
            // Every surface is drawn inside its own row, so a row filtered away
            // takes what is open on it off the screen while leaving it open —
            // and the table then refuses everything else for the sake of
            // something the user can neither see nor close. It is reachable: a
            // shortage on a task in a finished game reopens the game in the same
            // transaction, and the row leaves `Tamamlanan` while the menu on it
            // is still standing.
            val fixture = progressFixture(isCompleted = true, gameCompleted = true)
            val progress = FakeTaskProgress()
            val controller = controllerOf(fixture.table, taskProgress = progress)
            val job = collect(controller)
            controller.showView(GameTableView.COMPLETED)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            // The report lands and the game is reopened underneath the menu.
            fixture.table.rows.value = listOf(fixture.gameRow.copy(isCompleted = false))
            settle()

            assertNotNull(controller.state.work, "the menu closed itself")
            assertEquals(
                listOf("Harmonies"),
                visibleNames(controller),
                "the row carrying the open menu was filtered away with the menu still on it",
            )

            controller.closeInnermost()

            assertNull(controller.state.work)
            assertIs<GameTableRowsState.Empty>(
                controller.state.rows,
                "the row stayed in a view it does not belong to after the menu closed",
            )
            job.cancel()
        }

    private fun openMenu(controller: GameTableController): CellWork.TaskMenu = assertIs(controller.state.work)

    private fun reportingForm(controller: GameTableController): CellWork.ReportingShortage = assertIs(controller.state.work)

    private fun resolvingForm(controller: GameTableController): CellWork.ResolvingShortage = assertIs(controller.state.work)

    @Test
    fun `every surface a task can open is one the cell can find again`() =
        runBlocking<Unit> {
            // The popover hangs off the cell, and the cell finds it by asking
            // which menu is open in it. A surface the cell cannot find is a
            // surface nothing draws — and because open work also blocks every
            // other cell, the window would be left saying something is open with
            // nothing on screen to close. So each of them is opened for real and
            // looked for the way the screen looks for it.
            val fixture = progressFixture(missing = 3)
            val (controller, job) = watching(fixture, FakeTaskProgress())
            val found = { controller.state.menuIn(fixture.gameId, CellColumnType.THREE_D) }

            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            assertNotNull(found(), "the menu itself cannot be found")

            controller.beginReportShortage()
            assertIs<CellWork.ReportingShortage>(controller.state.work)
            assertNotNull(found(), "the form for what came up short is drawn nowhere")
            controller.closeInnermost()

            controller.beginResolveShortage()
            assertIs<CellWork.ResolvingShortage>(controller.state.work)
            assertNotNull(found(), "the form for what was made good is drawn nowhere")
            controller.closeInnermost()

            controller.beginTaskEdit()
            assertNotNull(found(), "the edit panel is drawn nowhere")
            controller.closeInnermost()

            controller.beginConvertToText()
            assertNotNull(found(), "the confirmation is drawn nowhere")
            job.cancel()
        }

    @Test
    fun `the tick finishes an unfinished task and takes a finished one back`() =
        runBlocking<Unit> {
            val open = progressFixture()
            val openProgress = FakeTaskProgress()
            val (a, jobA) = watching(open, openProgress)
            a.toggleTaskCompletion(open.gameId, CellColumnType.THREE_D, open.taskId)
            assertEquals(listOf(open.taskId), openProgress.completed.map { it.first })
            assertTrue(openProgress.reopened.isEmpty(), "finishing also reopened something")
            jobA.cancel()

            val done = progressFixture(isCompleted = true)
            val doneProgress = FakeTaskProgress()
            val (b, jobB) = watching(done, doneProgress)
            b.toggleTaskCompletion(done.gameId, CellColumnType.THREE_D, done.taskId)
            assertEquals(listOf(done.taskId), doneProgress.reopened)
            assertTrue(doneProgress.completed.isEmpty(), "reopening also finished something")
            jobB.cancel()
        }

    @Test
    fun `a second press while the first is on its way writes nothing more`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            val gameId = fixture.gameId
            progress.whileWorking = { controller.toggleTaskCompletion(gameId, CellColumnType.THREE_D, fixture.taskId) }

            controller.toggleTaskCompletion(gameId, CellColumnType.THREE_D, fixture.taskId)

            assertEquals(1, progress.completed.size, "a double click finished the task twice")
            job.cancel()
        }

    @Test
    fun `nothing left to do is not shown as something going wrong`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val (controller, job) = watching(fixture, FakeTaskProgress(TaskProgressOutcome.AlreadySo))

            controller.toggleTaskCompletion(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            assertNull(controller.tickFailure, "a task that was already finished was reported as a failure")
            job.cancel()
        }

    @Test
    fun `a refused tick is remembered against the task it happened on`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val refusing = FakeTaskProgress(TaskProgressOutcome.Refused(TaskProgressFailure.TASK_NOT_AVAILABLE))
            val (controller, job) = watching(fixture, refusing)

            controller.toggleTaskCompletion(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            assertEquals(fixture.taskId to TaskProgressFailure.TASK_NOT_AVAILABLE, controller.tickFailure)
            job.cancel()
        }

    @Test
    fun `the menu finishes a task the same way the tick does`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            controller.toggleCompletionFromMenu()

            assertEquals(listOf(fixture.taskId), progress.completed.map { it.first })
            assertFalse(openMenu(controller).isWorking, "the menu stayed busy after the write landed")
            job.cancel()
        }

    @Test
    fun `a finish settles under the name the menu was opened with, however often it is tried`() =
        runBlocking<Unit> {
            // A finish may have to settle what the task owes, and PLAN 5.12 makes
            // that a real event. Tried twice it has to be the same event, or the
            // settling is recorded twice over.
            val fixture = progressFixture(missing = 4)
            val refusing = FakeTaskProgress(TaskProgressOutcome.Refused(TaskProgressFailure.TASK_NOT_AVAILABLE))
            val (controller, job) = watching(fixture, refusing)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            val named = openMenu(controller).completionEventId

            controller.toggleCompletionFromMenu()
            controller.toggleCompletionFromMenu()

            assertEquals(listOf(named, named), refusing.completed.map { it.second })
            job.cancel()
        }

    @Test
    fun `a finish made elsewhere turns the menu's offer round without closing it`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val (controller, job) = watching(fixture, FakeTaskProgress())
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            assertFalse(openMenu(controller).isCompleted)

            fixture.holding(fixture.piece.copy(isCompletedTask = true))
            yield()

            assertTrue(openMenu(controller).isCompleted, "the menu still offers to finish a finished task")
            job.cancel()
        }

    @Test
    fun `a shortage form is given the name its movement will be written under`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val (controller, job) = watching(fixture, FakeTaskProgress())
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            controller.beginReportShortage()

            assertNotNull(reportingForm(controller).draft.eventId)
            job.cancel()
        }

    @Test
    fun `a refused send keeps the draft and the name it was sent under`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val refusing = FakeTaskProgress(TaskProgressOutcome.Refused(TaskProgressFailure.TASK_NOT_AVAILABLE))
            val (controller, job) = watching(fixture, refusing)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            controller.editShortageQuantity("3")
            controller.editShortageNote("Tabla kenarı")
            val named = reportingForm(controller).draft.eventId

            controller.saveShortage()

            val after = reportingForm(controller)
            assertEquals("3", after.draft.quantity, "what was typed was lost")
            assertEquals("Tabla kenarı", after.draft.note)
            assertEquals(named, after.draft.eventId, "a retry would go in as a second report")
            assertEquals(TaskProgressFailure.TASK_NOT_AVAILABLE, after.failure)
            assertFalse(after.isSaving, "the form stayed stuck sending")
            job.cancel()
        }

    @Test
    fun `sending the same form again is the same movement`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val refusing = FakeTaskProgress(TaskProgressOutcome.Refused(TaskProgressFailure.TASK_NOT_AVAILABLE))
            val (controller, job) = watching(fixture, refusing)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            controller.editShortageQuantity("3")

            controller.saveShortage()
            controller.saveShortage()

            assertEquals(2, refusing.reported.size)
            assertEquals(
                1,
                refusing.reported
                    .map { it.eventId }
                    .toSet()
                    .size,
                "the retry went in as a new report",
            )
            job.cancel()
        }

    @Test
    fun `two sends racing out of one form are still one report`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            controller.editShortageQuantity("3")
            progress.whileWorking = { controller.saveShortage() }

            controller.saveShortage()

            assertEquals(1, progress.reported.size, "one form sent two reports")
            job.cancel()
        }

    @Test
    fun `a new form after one that landed is a new movement`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            val gameId = fixture.gameId
            controller.openTaskMenu(gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            controller.editShortageQuantity("3")
            controller.saveShortage()
            controller.beginReportShortage()
            controller.editShortageQuantity("2")
            controller.saveShortage()

            assertEquals(2, progress.reported.size)
            assertEquals(
                2,
                progress.reported
                    .map { it.eventId }
                    .toSet()
                    .size,
                "the second report reused the first name",
            )
            job.cancel()
        }

    @Test
    fun `a list arriving from the database leaves an open draft and its name alone`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val (controller, job) = watching(fixture, FakeTaskProgress())
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            controller.editShortageQuantity("7")
            controller.editShortageNote("Yarım kaldı")
            val named = reportingForm(controller).draft.eventId

            fixture.holding(fixture.piece)
            yield()

            val after = reportingForm(controller)
            assertEquals("7", after.draft.quantity)
            assertEquals("Yarım kaldı", after.draft.note)
            assertEquals(named, after.draft.eventId)
            job.cancel()
        }

    @Test
    fun `an amount that is not a number never reaches the database`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()

            controller.saveShortage()

            assertTrue(progress.reported.isEmpty(), "an empty amount was sent to the database")
            assertEquals(TaskProgressFailure.INVALID_QUANTITY, reportingForm(controller).failure)
            job.cancel()
        }

    @Test
    fun `the amount field takes digits and nothing else`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val (controller, job) = watching(fixture, FakeTaskProgress())
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()

            controller.editShortageQuantity("-4a2")

            // A minus sign is not a number of pieces anybody meant, and reading
            // one would turn a slip into a movement the wrong way round.
            assertEquals("42", reportingForm(controller).draft.quantity)
            job.cancel()
        }

    @Test
    fun `a note of nothing but spaces is kept as no note at all`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            controller.editShortageQuantity("2")
            controller.editShortageNote("   ")

            controller.saveShortage()

            assertNull(progress.reported.single().note)
            job.cancel()
        }

    @Test
    fun `a card is named only on a task made of cards, and a step only where there is one`() =
        runBlocking<Unit> {
            val plain = progressFixture()
            val plainProgress = FakeTaskProgress()
            val (a, jobA) = watching(plain, plainProgress)
            a.openTaskMenu(plain.gameId, CellColumnType.THREE_D, plain.taskId)
            a.beginReportShortage()
            a.editShortageQuantity("2")
            a.editShortageCardReference("Bird 12")
            a.chooseShortageStage(ProductionStage.PRINT)
            a.saveShortage()
            assertNull(plainProgress.reported.single().cardReference, "a 3D task carried a card's name")
            assertNull(plainProgress.reported.single().stage, "a 3D task carried a step it never runs through")
            jobA.cancel()

            val cards = progressFixture(poolType = PoolType.CARD, columnType = CellColumnType.CARD)
            val cardProgress = FakeTaskProgress()
            val (b, jobB) = watching(cards, cardProgress)
            b.openTaskMenu(cards.gameId, CellColumnType.CARD, cards.taskId)
            b.beginReportShortage()
            b.editShortageQuantity("3")
            b.editShortageCardReference("Bird 12")
            b.chooseShortageStage(ProductionStage.LAMINATE)
            b.saveShortage()
            assertEquals("Bird 12", cardProgress.reported.single().cardReference)
            assertEquals(ProductionStage.LAMINATE, cardProgress.reported.single().stage)
            jobB.cancel()
        }

    @Test
    fun `making good is offered only on a task that owes something`() =
        runBlocking<Unit> {
            val fixture = progressFixture(missing = 0)
            val (controller, job) = watching(fixture, FakeTaskProgress())
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            controller.beginResolveShortage()

            assertIs<CellWork.TaskMenu>(controller.state.work, "a task owing nothing opened a form to make good")
            job.cancel()
        }

    @Test
    fun `making good sends what was typed against the task that owes it`() =
        runBlocking<Unit> {
            val fixture = progressFixture(missing = 5)
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            controller.beginResolveShortage()
            assertEquals(5, resolvingForm(controller).outstanding)
            controller.editShortageQuantity("2")
            controller.saveShortage()

            assertEquals(2, progress.resolved.single().quantity)
            assertEquals(fixture.taskId, progress.resolved.single().taskId)
            assertTrue(progress.reported.isEmpty(), "making good was recorded as a shortage")
            job.cancel()
        }

    @Test
    fun `making good more than is owed comes back as something to read`() =
        runBlocking<Unit> {
            val fixture = progressFixture(missing = 2)
            val refusing = FakeTaskProgress(TaskProgressOutcome.Refused(TaskProgressFailure.MORE_RESOLVED_THAN_OUTSTANDING))
            val (controller, job) = watching(fixture, refusing)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginResolveShortage()
            controller.editShortageQuantity("9")

            controller.saveShortage()

            assertEquals(TaskProgressFailure.MORE_RESOLVED_THAN_OUTSTANDING, resolvingForm(controller).failure)
            job.cancel()
        }

    @Test
    fun `a movement that landed closes the form and leaves the menu`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val (controller, job) = watching(fixture, FakeTaskProgress())
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            controller.editShortageQuantity("3")

            controller.saveShortage()

            assertIs<CellWork.TaskMenu>(controller.state.work, "the form did not close, or it took the menu with it")
            job.cancel()
        }

    @Test
    fun `closing a shortage form goes back to the menu and not out of the task`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val (controller, job) = watching(fixture, FakeTaskProgress())
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()

            controller.closeInnermost()
            assertIs<CellWork.TaskMenu>(controller.state.work)

            controller.closeInnermost()
            assertNull(controller.state.work)
            job.cancel()
        }

    @Test
    fun `a shortage form with something typed in it says so`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val (controller, job) = watching(fixture, FakeTaskProgress())
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            assertFalse(reportingForm(controller).hasUnsavedChanges, "an untouched form claims to hold something")

            controller.editShortageQuantity("3")

            // What a click away reads to decide whether it may close this.
            assertTrue(reportingForm(controller).hasUnsavedChanges, "a click away would throw this away silently")
            job.cancel()
        }

    @Test
    fun `a task that goes while its form is open takes the form with it`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val (controller, job) = watching(fixture, FakeTaskProgress())
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()

            fixture.holding(null)
            yield()

            assertNull(controller.state.work, "a form stayed open over a task that is no longer there")
            job.cancel()
        }

    @Test
    fun `a shortage may be reported on a task inside a finished game`() =
        runBlocking<Unit> {
            // PLAN 6.3 reopens the task and the game in one transaction, so the
            // form is offered here exactly as anywhere else: what used to be a
            // half applied state is now a single write.
            val fixture = progressFixture(gameCompleted = true)
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            controller.beginReportShortage()
            controller.editShortageQuantity("2")
            controller.saveShortage()

            assertEquals(2, progress.reported.single().quantity, "a shortage in a finished game was refused")
            job.cancel()
        }

    @Test
    fun `a task in a finished game may still be finished and taken back`() =
        runBlocking<Unit> {
            // Only the shortage waits: finishing a task says nothing about the
            // game, so there is no half applied state to avoid.
            val fixture = progressFixture(gameCompleted = true)
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)

            controller.toggleTaskCompletion(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            assertEquals(listOf(fixture.taskId), progress.completed.map { it.first })
            job.cancel()
        }

    @Test
    fun `a finished task whose game is still going may be reported against`() =
        runBlocking<Unit> {
            val fixture = progressFixture(isCompleted = true)
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            controller.beginReportShortage()
            controller.editShortageQuantity("2")
            controller.saveShortage()

            assertEquals(2, progress.reported.single().quantity)
            job.cancel()
        }

    @Test
    fun `a form still open when its game is finished elsewhere still writes, and once`() =
        runBlocking<Unit> {
            // The screen does not decide whether the game has to be reopened, so
            // a game finished under an open form changes nothing about what is
            // sent: the transaction reads the game itself and reopens it there.
            // What must not happen is the form quietly dropping what was typed.
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            controller.editShortageQuantity("2")
            fixture.table.rows.value = listOf(fixture.gameRow.copy(isCompleted = true))
            settle()
            assertEquals("2", reportingForm(controller).draft.quantity, "the finished game wiped the draft")

            controller.saveShortage()

            assertEquals(1, progress.reported.size, "one form sent more or less than one report")
            assertEquals(fixture.taskId, progress.reported.single().taskId)
            job.cancel()
        }

    @Test
    fun `making good is still allowed on a task whose game has been finished`() =
        runBlocking<Unit> {
            // A task that owes something was never finished, so settling what it
            // owes cannot reopen anything. There is no half applied state to
            // avoid, and refusing would leave the debt with no way to clear it.
            val fixture = progressFixture(missing = 3)
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginResolveShortage()
            controller.editShortageQuantity("3")
            fixture.table.rows.value = listOf(fixture.gameRow.copy(isCompleted = true))
            settle()

            controller.saveShortage()

            assertEquals(3, progress.resolved.single().quantity)
            job.cancel()
        }

    @Test
    fun `the tick finishes a task without a menu having been opened on it`() =
        runBlocking<Unit> {
            // The name a settling event is written under is chosen when the menu
            // opens. The tick is reached without opening one, so it must name its
            // own movement rather than reach for a name that is not there.
            val fixture = progressFixture(missing = 2)
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)

            controller.toggleTaskCompletion(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            val finished = progress.completed.single()
            assertEquals(fixture.taskId, finished.first)
            assertNotNull(finished.second, "the tick finished a task under no name at all")
            job.cancel()
        }

    @Test
    fun `a list arriving from the database leaves the name a finish would settle under alone`() =
        runBlocking<Unit> {
            val fixture = progressFixture(missing = 2)
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            val chosen = openMenu(controller).completionEventId

            fixture.holding(fixture.piece.copy(text = "Token bir"))

            assertEquals(chosen, openMenu(controller).completionEventId, "the row that arrived renamed the movement")
            job.cancel()
        }

    @Test
    fun `a card named as nothing but spaces is kept as no card at all`() =
        runBlocking<Unit> {
            val fixture = progressFixture(poolType = PoolType.CARD, columnType = CellColumnType.CARD)
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.CARD, fixture.taskId)
            controller.beginReportShortage()
            controller.editShortageQuantity("1")
            controller.editShortageCardReference("   ")

            controller.saveShortage()

            assertNull(progress.reported.single().cardReference, "a card named with nothing was written down")
            job.cancel()
        }

    @Test
    fun `an amount of nothing is refused on both forms alike`() =
        runBlocking<Unit> {
            val fixture = progressFixture(missing = 3)
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            controller.beginResolveShortage()
            controller.editShortageQuantity("0")
            controller.saveShortage()

            assertTrue(progress.resolved.isEmpty(), "nothing at all was made good")
            assertEquals(TaskProgressFailure.INVALID_QUANTITY, resolvingForm(controller).failure)
            assertEquals("0", resolvingForm(controller).draft.quantity, "the refusal threw away what had been typed")
            job.cancel()
        }

    @Test
    fun `two presses meant as one leave the task where the first press put it`() =
        runBlocking<Unit> {
            // The write comes back long before the row carrying its result does,
            // and until then the tick is still drawn unfinished. A second press
            // in that gap would ask for the opposite of the first and undo it —
            // leaving a task that looks untouched while the shortage it owed has
            // been settled into its history for good.
            val fixture = progressFixture(missing = 3)
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)

            controller.toggleTaskCompletion(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.toggleTaskCompletion(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            assertEquals(1, progress.completed.size, "the task was finished more than once")
            assertTrue(progress.reopened.isEmpty(), "the second press took the first one back")
            job.cancel()
        }

    @Test
    fun `the tick can be pressed again once the table has shown the answer`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)

            controller.toggleTaskCompletion(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            fixture.holding(fixture.piece.copy(isCompletedTask = true))
            settle()
            controller.toggleTaskCompletion(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            assertEquals(1, progress.completed.size)
            assertEquals(listOf(fixture.taskId), progress.reopened, "the tick was left unusable")
            job.cancel()
        }

    @Test
    fun `a tick refused by the database does not leave the task unpressable`() =
        runBlocking<Unit> {
            // A refusal is answered by nothing arriving, so it cannot be waited
            // for. Waiting anyway would leave a task that can never be worked on
            // again without reopening the whole window.
            val fixture = progressFixture()
            val progress = FakeTaskProgress(TaskProgressOutcome.Refused(TaskProgressFailure.TASK_NOT_AVAILABLE))
            val (controller, job) = watching(fixture, progress)

            controller.toggleTaskCompletion(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.toggleTaskCompletion(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)

            assertEquals(2, progress.completed.size, "a refused press was never let go of")
            assertEquals(fixture.taskId to TaskProgressFailure.TASK_NOT_AVAILABLE, controller.tickFailure)
            job.cancel()
        }

    @Test
    fun `a task that leaves the view lets its pressed tick go`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)

            controller.toggleTaskCompletion(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            fixture.holding(null)
            settle()

            assertNull(controller.busyTaskId, "a tick was left waiting for a row that will never come")
            job.cancel()
        }

    // ------------------------------------ the whole range an amount may be

    @Test
    fun `the amount field holds the largest amount there is`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()

            // Ten digits. The field used to stop at nine, so this arrived as
            // 214748364 — a tenth of what was typed, written without a word.
            controller.editShortageQuantity(Int.MAX_VALUE.toString())

            assertEquals("2147483647", reportingForm(controller).draft.quantity)
            assertFalse(reportingForm(controller).draft.isQuantityUnusable)
            controller.saveShortage()
            assertEquals(Int.MAX_VALUE, progress.reported.single().quantity)
            job.cancel()
        }

    @Test
    fun `an amount that will not fit stays where it was typed and is refused`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            val tooLarge =
                Int.MAX_VALUE
                    .toLong()
                    .plus(1)
                    .toString()

            controller.editShortageQuantity(tooLarge)

            assertEquals(tooLarge, reportingForm(controller).draft.quantity, "what was typed was trimmed")
            assertTrue(reportingForm(controller).draft.isQuantityUnusable, "the box did not say it will not do")

            controller.saveShortage()

            assertTrue(progress.reported.isEmpty(), "a number that is not an amount reached the database")
            assertEquals(TaskProgressFailure.INVALID_QUANTITY, reportingForm(controller).failure)
            assertEquals(tooLarge, reportingForm(controller).draft.quantity, "the refusal threw the draft away")
            job.cancel()
        }

    @Test
    fun `hundreds of digits are refused rather than thrown over`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            val absurd = "9".repeat(400)

            controller.editShortageQuantity(absurd)
            controller.saveShortage()

            assertEquals(absurd, reportingForm(controller).draft.quantity)
            assertEquals(TaskProgressFailure.INVALID_QUANTITY, reportingForm(controller).failure)
            assertTrue(progress.reported.isEmpty())
            job.cancel()
        }

    @Test
    fun `nothing at all is refused where the user can see it`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()

            // Nothing typed, and then nothing meaning nothing: neither is a
            // movement, and neither may pass silently.
            controller.saveShortage()
            assertEquals(TaskProgressFailure.INVALID_QUANTITY, reportingForm(controller).failure)

            controller.editShortageQuantity("0")
            controller.saveShortage()
            assertEquals(TaskProgressFailure.INVALID_QUANTITY, reportingForm(controller).failure)
            assertEquals("0", reportingForm(controller).draft.quantity, "the zero the user typed vanished")
            assertTrue(progress.reported.isEmpty())
            job.cancel()
        }

    @Test
    fun `zeros written in front of an amount neither lose it nor change it`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()

            controller.editShortageQuantity("0042")
            controller.saveShortage()

            assertEquals(42, progress.reported.single().quantity)
            job.cancel()
        }

    @Test
    fun `an amount refused for its size keeps the name the movement would be written under`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            val named = reportingForm(controller).draft.eventId

            controller.editShortageQuantity(
                Int.MAX_VALUE
                    .toLong()
                    .plus(1)
                    .toString(),
            )
            controller.saveShortage()
            assertEquals(named, reportingForm(controller).draft.eventId, "a refusal renamed the movement")

            // Corrected and sent: still the same movement, because it is still
            // the same form and the user still means one report.
            controller.editShortageQuantity(Int.MAX_VALUE.toString())
            controller.saveShortage()

            assertEquals(named, progress.reported.single().eventId)
            job.cancel()
        }

    @Test
    fun `a large amount sent twice in a row is sent once`() =
        runBlocking<Unit> {
            val fixture = progressFixture()
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginReportShortage()
            controller.editShortageQuantity(Int.MAX_VALUE.toString())
            progress.whileWorking = { controller.saveShortage() }

            controller.saveShortage()

            assertEquals(1, progress.reported.size, "one report was sent twice")
            job.cancel()
        }

    @Test
    fun `the whole of what a task owes can be made good in one go`() =
        runBlocking<Unit> {
            val fixture = progressFixture(missing = 3)
            val progress = FakeTaskProgress()
            val (controller, job) = watching(fixture, progress)
            controller.openTaskMenu(fixture.gameId, CellColumnType.THREE_D, fixture.taskId)
            controller.beginResolveShortage()

            controller.editShortageQuantity(Int.MAX_VALUE.toString())

            assertEquals("2147483647", resolvingForm(controller).draft.quantity)
            controller.saveShortage()

            assertEquals(Int.MAX_VALUE, progress.resolved.single().quantity)
            job.cancel()
        }

    @Test
    fun `a restore lets go of everything open, because the rows underneath have been replaced`() =
        runBlocking<Unit> {
            val controller = controllerOf(FakeTable())
            controller.openFilters()
            assertEquals(TableFilterSurface.OPEN, controller.state.filterSurface)

            controller.abandonOpenWork()

            // Not one layer, as Escape takes, but all of them: there is no row
            // left for any of them to have been about.
            assertEquals(TableFilterSurface.CLOSED, controller.state.filterSurface)
            assertNull(controller.state.work)
            assertNull(controller.state.rowWork)
            assertNull(controller.state.gameComposer)
        }
}
