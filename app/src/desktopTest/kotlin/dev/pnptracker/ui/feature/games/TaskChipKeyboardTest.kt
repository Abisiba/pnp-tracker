package dev.pnptracker.ui.feature.games

import androidx.compose.ui.input.key.Key
import dev.pnptracker.data.repository.CellTextEditing
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.data.repository.GameTableSource
import dev.pnptracker.data.repository.TaskCreationFromText
import dev.pnptracker.data.repository.TaskEditing
import dev.pnptracker.data.repository.TaskProgressOutcome
import dev.pnptracker.data.repository.TaskProgressing
import dev.pnptracker.domain.colors.BaseColorRestore
import dev.pnptracker.domain.colors.BaseColorRestorePlan
import dev.pnptracker.domain.colors.ColorRemoval
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.ColorUsage
import dev.pnptracker.domain.games.CellPreview
import dev.pnptracker.domain.games.CellSegmentPreview
import dev.pnptracker.domain.games.CellSummary
import dev.pnptracker.domain.games.GameCompletionSnapshot
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.games.GameTableRow
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
import dev.pnptracker.domain.tasks.TaskFlags
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.contentDescriptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keyboard really reaching a task written in a cell (PLAN 17:1274).
 *
 * These render the real screen and send real key strokes through Compose's own
 * dispatch, because that is the only place the defect they exist for could be
 * seen. The chain was `focusable().clickable().onPreviewKeyEvent {}`: two focus
 * nodes where one was meant, and the key handler written below both of them,
 * where no key travelling from the root to the focused node ever passes. Every
 * unit around it passed — the controller was right, the modifier list contained
 * the right words — and Enter and Space did nothing at all.
 *
 * So nothing here asks a controller what it would have done. The scene is
 * composed, Tab walks the real focus order, the key goes in as an AWT stroke,
 * and what is read afterwards is the real semantics tree.
 */
class TaskChipKeyboardTest {
    // ------------------------------------------------------------- fixtures

    private fun colorOf(
        name: String,
        hex: String,
    ) = TaskColorPreview(colorId = IdGenerator.Random.newId(), canonicalName = name, hex = hex)

    private fun taskPiece(
        name: String,
        quantity: Int? = 14,
        taskId: EntityId = IdGenerator.Random.newId(),
        isCompleted: Boolean = false,
        colors: List<TaskColorPreview> = listOf(colorOf("Gri", "#808080")),
    ) = CellSegmentPreview(
        segmentId = IdGenerator.Random.newId(),
        taskId = taskId,
        text = name,
        isCompletedTask = isCompleted,
        requiredQuantity = quantity,
        colors = colors,
        poolType = PoolType.THREE_D,
    )

    private fun textPiece(text: String) = CellSegmentPreview(segmentId = IdGenerator.Random.newId(), taskId = null, text = text)

    private fun rowOf(
        name: String = "Harmonies",
        pieces: List<CellSegmentPreview>,
    ) = GameTableRow(
        gameId = IdGenerator.Random.newId(),
        gameName = name,
        isCompleted = false,
        cells =
            CellColumnType.entries.map { columnType ->
                CellPreview(
                    columnType = columnType,
                    cellId = IdGenerator.Random.newId(),
                    segments = if (columnType == CellColumnType.THREE_D) pieces else emptyList(),
                )
            },
    )

    /**
     * What a reader is told about one task, which is how a test finds its chip.
     *
     * Built from `strings.xml`'s own wording rather than asserted against a
     * copy of it: this is how the test *finds* the control, and a name that
     * drifted would make every test here quietly stop testing anything.
     */
    private fun spokenNameOf(task: CellSegmentPreview): String {
        val colors =
            if (task.colors.isEmpty()) {
                textOf(Strings.CellTask.noColor)
            } else {
                task.colors.joinToString(separator = ", ") { it.canonicalName }
            }
        return task.requiredQuantity
            ?.let { textOf(Strings.CellTask.description, task.text, it, colors) }
            ?: textOf(Strings.CellTask.descriptionUnknownQuantity, task.text, colors)
    }

    private fun textOf(
        resource: StringResource,
        vararg formatArgs: Any,
    ): String = runBlocking { if (formatArgs.isEmpty()) getString(resource) else getString(resource, *formatArgs) }

    // --------------------------------------------------------------- fakes

    private class FakeTable(
        rows: List<GameTableRow>,
    ) : GameTableSource {
        val rows = MutableStateFlow(rows)

        override fun observeTable(): Flow<List<GameTableRow>> = rows
    }

    private class FakeSetup : GameSetup {
        override fun observeGames(): Flow<List<GameSummary>> = MutableStateFlow(emptyList())

        override fun observeCells(gameId: EntityId): Flow<List<CellSummary>> = MutableStateFlow(emptyList())

        override suspend fun createGame(name: String): EntityId = IdGenerator.Random.newId()

        override suspend fun openCell(
            gameId: EntityId,
            columnType: CellColumnType,
        ): EntityId = IdGenerator.Random.newId()

        override suspend fun setGameCompleted(
            gameId: EntityId,
            isCompleted: Boolean,
        ) = Unit
    }

    private class FakeCells : CellTextEditing {
        override suspend fun saveDocumentText(
            gameId: EntityId,
            columnType: CellColumnType,
            expectedDocumentText: String,
            newDocumentText: String,
        ): Boolean = true
    }

    /** No colour is made, edited or removed from a task's own word (PLAN 5.7). */
    private class FakeColors : ColorCatalogue {
        override fun observeColors(): Flow<List<ColorSummary>> = MutableStateFlow(emptyList())

        override suspend fun colorsUsingHex(hex: String): List<ColorSummary> = emptyList()

        override suspend fun createColor(
            canonicalName: String,
            hex: String,
        ): EntityId = unreached()

        override suspend fun editColor(
            id: EntityId,
            expectedName: String,
            expectedHex: String,
            canonicalName: String,
            hex: String,
        ) = unreached()

        override suspend fun usageOf(id: EntityId): ColorUsage = unreached()

        override suspend fun deleteColor(id: EntityId): ColorRemoval = unreached()

        override suspend fun previewBaseColorRestore(): BaseColorRestorePlan = unreached()

        override suspend fun restoreMissingBaseColors(): BaseColorRestore = unreached()

        private fun unreached(): Nothing = error("Nothing about a task's keyboard reaches the colour catalogue.")
    }

    private class FakeTaskCreation : TaskCreationFromText {
        override suspend fun createTasks(
            selection: CellTextSelection,
            drafts: List<TaskDraft>,
        ): List<EntityId> = emptyList()
    }

    private class FakeTaskEditing : TaskEditing {
        override suspend fun editTask(
            taskId: EntityId,
            name: String,
            colorIds: List<EntityId>,
            requiredQuantity: Int?,
            notes: String?,
            trackingMode: TrackingMode,
            flags: TaskFlags?,
        ): Boolean = true

        override suspend fun convertTaskToText(taskId: EntityId): Boolean = true
    }

    private class FakeProgress : TaskProgressing {
        override suspend fun completeTask(
            taskId: EntityId,
            eventId: EntityId,
        ): TaskProgressOutcome = TaskProgressOutcome.Done

        override suspend fun reopenTask(taskId: EntityId): TaskProgressOutcome = TaskProgressOutcome.Done

        override suspend fun gameCompletion(gameId: EntityId): GameCompletionSnapshot? = null

        override suspend fun completeGame(
            gameId: EntityId,
            expected: GameCompletionSnapshot?,
        ): TaskProgressOutcome = TaskProgressOutcome.Done

        override suspend fun reportFailure(
            eventId: EntityId,
            taskId: EntityId,
            quantity: Int,
            note: String?,
            cardReference: String?,
            stage: ProductionStage?,
        ): TaskProgressOutcome = TaskProgressOutcome.Done

        override suspend fun resolveShortage(
            eventId: EntityId,
            taskId: EntityId,
            quantity: Int,
            note: String?,
            cardReference: String?,
        ): TaskProgressOutcome = TaskProgressOutcome.Done

        override suspend fun setStageQuantities(
            taskId: EntityId,
            targets: Map<ProductionStage, Int>,
            expected: StageSnapshot?,
        ): TaskProgressOutcome = TaskProgressOutcome.Done
    }

    // ------------------------------------------------------------- harness

    /**
     * The real screen, composed, with one game row in it.
     *
     * `GameTableScreen` and nothing smaller: the chip's behaviour comes from
     * where it sits in the row's modifier chain, so a copy of it hoisted into a
     * test would be a different chain and could not fail the way the real one
     * did.
     */
    private fun sceneOf(
        rows: List<GameTableRow>,
        block: (ComposeSceneHarness, GameTableController) -> Unit,
    ) {
        val table = FakeTable(rows)
        val controller =
            GameTableController(
                table = table,
                setup = FakeSetup(),
                cells = FakeCells(),
                colors = FakeColors(),
                taskCreation = FakeTaskCreation(),
                taskEditing = FakeTaskEditing(),
                taskProgress = FakeProgress(),
            )
        ComposeSceneHarness(width = 1300, height = 900) {
            GameTableScreen(controller)
        }.use { harness ->
            // The table is collected inside the composition; a few frames let
            // the first list and the string resources land.
            repeat(SETTLING_FRAMES) { harness.render() }
            block(harness, controller)
        }
    }

    private fun GameTableController.openTask(): EntityId? = (state.work as? CellWork.TaskMenu)?.taskId

    // ------------------------------------------------------- the key strokes

    @Test
    fun `Enter on a task opens that task's panel`() {
        val task = taskPiece("Gri token")
        sceneOf(listOf(rowOf(pieces = listOf(task)))) { harness, controller ->
            assertTrue(harness.tabTo(spokenNameOf(task)), "the keyboard never reaches the task")
            assertNull(controller.state.work, "something was open before a key was pressed")

            harness.press(Key.Enter)

            assertEquals(task.taskId, controller.openTask(), "Enter did not open the task's own panel")
        }
    }

    @Test
    fun `Space on a task opens that task's panel`() {
        val task = taskPiece("Gri token")
        sceneOf(listOf(rowOf(pieces = listOf(task)))) { harness, controller ->
            assertTrue(harness.tabTo(spokenNameOf(task)))

            harness.press(Key.Spacebar)

            assertEquals(task.taskId, controller.openTask(), "Space did not open the task's own panel")
        }
    }

    @Test
    fun `the numeric keypad's Enter opens it too`() {
        val task = taskPiece("Gri token")
        sceneOf(listOf(rowOf(pieces = listOf(task)))) { harness, controller ->
            assertTrue(harness.tabTo(spokenNameOf(task)))

            harness.down(Key.NumPadEnter)
            harness.up(Key.NumPadEnter)

            assertEquals(task.taskId, controller.openTask(), "the keypad's Enter does nothing")
        }
    }

    @Test
    fun `the release of a stroke is not a second press`() {
        val task = taskPiece("Gri token")
        sceneOf(listOf(rowOf(pieces = listOf(task)))) { harness, controller ->
            assertTrue(harness.tabTo(spokenNameOf(task)))

            harness.down(Key.Enter)
            val afterPress = controller.state.work
            harness.up(Key.Enter)

            assertEquals(task.taskId, controller.openTask())
            assertTrue(afterPress === controller.state.work, "the release opened a second surface over the first")
        }
    }

    @Test
    fun `a key held down does not reopen or reset what is already open`() {
        val task = taskPiece("Gri token")
        sceneOf(listOf(rowOf(pieces = listOf(task)))) { harness, controller ->
            assertTrue(harness.tabTo(spokenNameOf(task)))
            harness.down(Key.Enter)
            val opened = assertNotNull(controller.state.work)

            // What a held key really sends: more presses, no release between.
            repeat(4) { harness.down(Key.Enter) }
            harness.up(Key.Enter)

            assertTrue(opened === controller.state.work, "a repeat replaced the panel that was already open")
        }
    }

    @Test
    fun `a second stroke arriving at once still leaves one surface open`() {
        val task = taskPiece("Gri token")
        sceneOf(listOf(rowOf(pieces = listOf(task)))) { harness, controller ->
            assertTrue(harness.tabTo(spokenNameOf(task)))

            harness.press(Key.Enter)
            harness.press(Key.Spacebar)

            assertEquals(task.taskId, controller.openTask())
            assertTrue(controller.state.work is CellWork.TaskMenu, "two strokes left something other than the menu open")
        }
    }

    @Test
    fun `each task in a cell answers only for itself`() {
        // PLAN 12.7's independent tasks: two words, two tasks, nothing shared.
        val first = taskPiece("Gri token")
        val second = taskPiece("Mavi token", colors = listOf(colorOf("Mavi", "#3070C0")))
        sceneOf(listOf(rowOf(pieces = listOf(first, textPiece(" ve "), second)))) { harness, controller ->
            assertTrue(harness.tabTo(spokenNameOf(second)), "the second task cannot be reached")

            harness.press(Key.Enter)

            assertEquals(second.taskId, controller.openTask(), "the wrong task's panel opened")
        }
    }

    @Test
    fun `a task made in several colours is one stop and one thing said`() {
        // PLAN 5.10 and 12.7: one task, its name split across its colours. The
        // pieces are drawn separately and must not become separate controls.
        val many =
            taskPiece(
                "Ev",
                colors = listOf(colorOf("Gri", "#808080"), colorOf("Mavi", "#3070C0"), colorOf("Yeşil", "#2E8B57")),
            )
        sceneOf(listOf(rowOf(pieces = listOf(many)))) { harness, _ ->
            val spoken = spokenNameOf(many)
            assertEquals(
                1,
                harness.spokenNodes().count { spoken in it.contentDescriptions() },
                "a task in three colours is announced more than once",
            )
            assertEquals(
                1,
                harness.focusableNodes().count { spoken in it.contentDescriptions() },
                "a task in three colours is more than one tab stop",
            )
            assertTrue(harness.tabTo(spoken), "a task in three colours cannot be reached")
            assertTrue(harness.tabLeaves(spoken), "the word answers the keyboard more than once")
        }
    }

    @Test
    fun `a name long enough to wrap is still one stop and still opens`() {
        val long =
            taskPiece(
                "Çok uzun bir görev adı ki hücrenin genişliğini aşsın ve birden fazla satıra kaysın",
                quantity = 40,
            )
        sceneOf(listOf(rowOf(pieces = listOf(long)))) { harness, controller ->
            val spoken = spokenNameOf(long)
            assertEquals(1, harness.focusableNodes().count { spoken in it.contentDescriptions() }, "a wrapped name is several stops")

            assertTrue(harness.tabTo(spoken), "a wrapped name cannot be reached from the keyboard")
            assertTrue(harness.tabLeaves(spoken), "a wrapped name answers the keyboard once per line it covers")
            assertTrue(harness.tabTo(spoken))
            harness.press(Key.Enter)

            assertEquals(long.taskId, controller.openTask(), "a wrapped name does not answer the keyboard")
        }
    }

    @Test
    fun `a task with no colour yet is reachable and says so`() {
        val bare = taskPiece("Renksiz parça", colors = emptyList())
        sceneOf(listOf(rowOf(pieces = listOf(bare)))) { harness, controller ->
            val spoken = spokenNameOf(bare)
            assertTrue(
                harness.spokenNodes().any { spoken in it.contentDescriptions() },
                "a task with no colour is announced to nobody",
            )

            assertTrue(harness.tabTo(spoken))
            harness.press(Key.Enter)

            assertEquals(bare.taskId, controller.openTask())
        }
    }

    @Test
    fun `a task reduced to one colour behaves like any other`() {
        val one = taskPiece("Tek renk", colors = listOf(colorOf("Gri", "#808080")))
        sceneOf(listOf(rowOf(pieces = listOf(one)))) { harness, controller ->
            assertEquals(1, harness.focusableNodes().count { spokenNameOf(one) in it.contentDescriptions() })
            assertTrue(harness.tabTo(spokenNameOf(one)), "the word cannot be reached")
            assertTrue(harness.tabLeaves(spokenNameOf(one)), "one word is two tab stops")

            assertTrue(harness.tabTo(spokenNameOf(one)))
            harness.press(Key.Spacebar)

            assertEquals(one.taskId, controller.openTask())
        }
    }

    @Test
    fun `the pointer still opens the same task it always did`() {
        val task = taskPiece("Gri token")
        sceneOf(listOf(rowOf(pieces = listOf(task)))) { harness, controller ->
            assertTrue(harness.click(spokenNameOf(task)), "the task no longer answers a click")

            assertEquals(task.taskId, controller.openTask(), "a click opened the wrong thing")
        }
    }

    @Test
    fun `closing the panel hands the keyboard back to the word it was opened from`() {
        // The panel is opened with a real key and closed through the one call
        // Escape is bound to. The binding itself is held by
        // `GameTableLayoutTest` and by the manual round, because a popup is a
        // scene layer of its own and an off-screen scene delivers keys only to
        // the main one — so an Escape sent here would never reach the popover
        // and would prove nothing about it either way.
        //
        // What is proved here is the half that is new and could regress: with
        // the panel gone, the keyboard is on the word again rather than nowhere.
        val task = taskPiece("Gri token")
        sceneOf(listOf(rowOf(pieces = listOf(task)))) { harness, controller ->
            val spoken = spokenNameOf(task)
            assertTrue(harness.tabTo(spoken))
            harness.press(Key.Enter)
            assertNotNull(controller.state.work, "the panel never opened")

            controller.closeInnermost()
            repeat(SETTLING_FRAMES) { harness.render() }

            assertNull(controller.state.work, "the panel stayed open")
            assertEquals(
                listOf(spoken),
                harness.focusedNode()?.contentDescriptions(),
                "the keyboard was left nowhere after the panel closed",
            )
        }
    }

    @Test
    fun `closing a panel does not pull the keyboard off another task`() {
        val first = taskPiece("Gri token")
        val second = taskPiece("Mavi token", colors = listOf(colorOf("Mavi", "#3070C0")))
        sceneOf(listOf(rowOf(pieces = listOf(first, textPiece(" ve "), second)))) { harness, controller ->
            assertTrue(harness.tabTo(spokenNameOf(first)))
            harness.press(Key.Enter)
            controller.closeInnermost()
            repeat(SETTLING_FRAMES) { harness.render() }
            assertTrue(harness.tabTo(spokenNameOf(second)), "the keyboard never reached the second task")

            // Nothing is open now, so nothing should be calling the keyboard
            // back anywhere. The second task keeps it.
            repeat(SETTLING_FRAMES) { harness.render() }

            assertEquals(listOf(spokenNameOf(second)), harness.focusedNode()?.contentDescriptions())
        }
    }

    @Test
    fun `a task is announced once, with its name, its count and its colours in order`() {
        val many =
            taskPiece(
                "Ev",
                quantity = 12,
                colors = listOf(colorOf("Gri", "#808080"), colorOf("Mavi", "#3070C0")),
            )
        sceneOf(listOf(rowOf(pieces = listOf(many)))) { harness, _ ->
            val spoken = harness.spokenNodes().flatMap { it.contentDescriptions() }.filter { it.startsWith("Ev") }

            assertEquals(1, spoken.size, "the task is announced more than once: $spoken")
            val said = spoken.single()
            assertTrue("12" in said, "how many are needed is not said: $said")
            assertTrue(said.indexOf("Gri") < said.indexOf("Mavi"), "the colours are not read in the user's order: $said")
        }
    }

    private companion object {
        /** Frames enough for the first list and the string resources to land. */
        const val SETTLING_FRAMES = 6
    }
}
