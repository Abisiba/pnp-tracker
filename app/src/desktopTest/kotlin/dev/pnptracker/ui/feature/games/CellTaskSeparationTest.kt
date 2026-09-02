package dev.pnptracker.ui.feature.games

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
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two tasks in one cell, heard and drawn as two.
 *
 * The import used to write them into the document with nothing between them, and
 * the cell came out reading `Kırmızı evMavi ev`. A gap drawn between two chips
 * would have hidden that on this screen and nowhere else — not in the clipboard,
 * not in the editor, not in what a screen reader says — so the fix is a piece of
 * the document, and this is where that piece is checked from the outside: the
 * real screen composed, and the real semantics tree read back.
 */
class CellTaskSeparationTest {
    private val red = TaskColorPreview(colorId = IdGenerator.Random.newId(), canonicalName = "Kırmızı", hex = "#C62828")

    private fun taskPiece(name: String) =
        CellSegmentPreview(
            segmentId = IdGenerator.Random.newId(),
            taskId = IdGenerator.Random.newId(),
            text = name,
            isCompletedTask = false,
            requiredQuantity = 12,
            colors = listOf(red),
            poolType = PoolType.THREE_D,
        )

    private fun textPiece(text: String) = CellSegmentPreview(segmentId = IdGenerator.Random.newId(), taskId = null, text = text)

    private fun rowOf(pieces: List<CellSegmentPreview>) =
        GameTableRow(
            gameId = IdGenerator.Random.newId(),
            gameName = "Harmonies",
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
     * What a reader is told about one task.
     *
     * Built from `strings.xml`'s own wording rather than from a copy of it, so
     * this goes on finding the task if the phrasing is ever reworded.
     */
    private fun spokenNameOf(task: CellSegmentPreview): String {
        val colors = task.colors.joinToString(separator = ", ") { it.canonicalName }
        return runBlocking {
            getString(Strings.CellTask.description, task.text, requireNotNull(task.requiredQuantity), colors)
        }
    }

    /**
     * Everything said anywhere on the screen, laid out one description a line.
     *
     * Joined with a line ending rather than a space on purpose: a space here
     * would manufacture the very boundary these tests are asking about, and two
     * neighbouring nodes would look parted when the cell they are in was not.
     */
    private fun spokenOn(pieces: List<CellSegmentPreview>): String {
        val table = FakeTable(listOf(rowOf(pieces)))
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
        return ComposeSceneHarness(width = 1300, height = 900) {
            GameTableScreen(controller)
        }.use { harness ->
            repeat(SETTLING_FRAMES) { harness.render() }
            harness.spokenNodes().flatMap { it.contentDescriptions() }.joinToString(separator = "\n")
        }
    }

    @Test
    fun `two tasks parted by a space in the document are read as two`() {
        val red = taskPiece("Kırmızı ev")
        val blue = taskPiece("Mavi ev")

        val spoken = spokenOn(listOf(red, textPiece(" "), blue))

        assertTrue(spokenNameOf(red) in spoken, "the first task was not named")
        assertTrue(spokenNameOf(blue) in spoken, "the second task was not named")
        assertTrue(
            "${spokenNameOf(red)} ${spokenNameOf(blue)}" in spoken,
            "the cell was not read out with the space its document holds between the two tasks",
        )
    }

    @Test
    fun `the user's own words stay against the space they typed and no further`() {
        val task = taskPiece("Kırmızı ev")

        val spoken = spokenOn(listOf(textPiece("Kutu ölçüsü 30×30 "), task))

        assertTrue("Kutu ölçüsü 30×30 ${spokenNameOf(task)}" in spoken, "the cell was not read out as it is written")
        assertTrue("30×30  " !in spoken, "a second space appeared where the user had already left one")
    }

    /**
     * The defect this all exists for, still there when the document itself has
     * no space in it — which is what proves the check above is reading the
     * document rather than something the screen does on its own.
     */
    @Test
    fun `two tasks with nothing between them in the document really do run together`() {
        val red = taskPiece("Kırmızı ev")
        val blue = taskPiece("Mavi ev")

        val spoken = spokenOn(listOf(red, blue))

        assertTrue(
            "${spokenNameOf(red)}${spokenNameOf(blue)}" in spoken,
            "the screen is drawing a boundary the document does not have",
        )
    }

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

        private fun unreached(): Nothing = error("Reading a cell out loud reaches no colour catalogue.")
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

    private companion object {
        /** Frames enough for the first table and its strings to land. */
        const val SETTLING_FRAMES = 6
    }
}
