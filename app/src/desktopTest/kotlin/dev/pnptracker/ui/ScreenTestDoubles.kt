package dev.pnptracker.ui

import dev.pnptracker.data.repository.CellTextEditing
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.data.repository.TaskCreationFromText
import dev.pnptracker.data.repository.TaskEditing
import dev.pnptracker.data.repository.TaskProgressOutcome
import dev.pnptracker.data.repository.TaskProgressing
import dev.pnptracker.domain.colors.BaseColorRestore
import dev.pnptracker.domain.colors.BaseColorRestorePlan
import dev.pnptracker.domain.colors.ColorRemoval
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.ColorUsage
import dev.pnptracker.domain.games.CellSummary
import dev.pnptracker.domain.games.GameCompletionSnapshot
import dev.pnptracker.domain.games.GameRenameOutcome
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.StageSnapshot
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskFlags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The collaborators a screen has to be handed but that a search or a filter
 * never reaches.
 *
 * Narrowing what is on a screen writes nothing and reads nothing: it is
 * arithmetic over rows already in hand. These stand in for everything else so a
 * test about that can say so — and several of them refuse outright, which is how
 * a test that quietly started writing would be caught rather than passing.
 *
 * Shared rather than copied into each test, because there are now several and a
 * copy per file is a copy per file to keep in step with the interfaces.
 */
class NoColors(
    private val catalogue: List<ColorSummary> = emptyList(),
) : ColorCatalogue {
    override fun observeColors(): Flow<List<ColorSummary>> = MutableStateFlow(catalogue)

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

    private fun unreached(): Nothing = error("Narrowing a screen never changes the colour catalogue.")
}

class NoEditing : TaskEditing {
    override suspend fun editTask(
        taskId: EntityId,
        name: String,
        colorIds: List<EntityId>,
        requiredQuantity: Int?,
        notes: String?,
        trackingMode: TrackingMode,
        flags: TaskFlags?,
    ): Boolean = error("Narrowing a screen never edits a task.")

    override suspend fun convertTaskToText(taskId: EntityId): Boolean = error("Narrowing a screen never edits a task.")
}

class NoProgress : TaskProgressing {
    override suspend fun completeTask(
        taskId: EntityId,
        eventId: EntityId,
    ): TaskProgressOutcome = unreached()

    override suspend fun reopenTask(taskId: EntityId): TaskProgressOutcome = unreached()

    override suspend fun gameCompletion(gameId: EntityId): GameCompletionSnapshot? = null

    override suspend fun completeGame(
        gameId: EntityId,
        expected: GameCompletionSnapshot?,
    ): TaskProgressOutcome = unreached()

    override suspend fun reportFailure(
        eventId: EntityId,
        taskId: EntityId,
        quantity: Int,
        note: String?,
        cardReference: String?,
        stage: ProductionStage?,
    ): TaskProgressOutcome = unreached()

    override suspend fun resolveShortage(
        eventId: EntityId,
        taskId: EntityId,
        quantity: Int,
        note: String?,
        cardReference: String?,
    ): TaskProgressOutcome = unreached()

    override suspend fun setStageQuantities(
        taskId: EntityId,
        targets: Map<ProductionStage, Int>,
        expected: StageSnapshot?,
    ): TaskProgressOutcome = unreached()

    private fun unreached(): Nothing = error("Narrowing a screen never moves a task's progress.")
}

class NoSetup : GameSetup {
    override fun observeGames(): Flow<List<GameSummary>> = MutableStateFlow(emptyList())

    override fun observeCells(gameId: EntityId): Flow<List<CellSummary>> = MutableStateFlow(emptyList())

    override suspend fun createGame(name: String): EntityId = IdGenerator.Random.newId()

    override suspend fun openCell(
        gameId: EntityId,
        columnType: CellColumnType,
    ): EntityId = IdGenerator.Random.newId()

    override suspend fun renameGame(
        gameId: EntityId,
        name: String,
    ): GameRenameOutcome = GameRenameOutcome.RENAMED

    override suspend fun setGameCompleted(
        gameId: EntityId,
        isCompleted: Boolean,
    ) = Unit
}

class NoCells : CellTextEditing {
    override suspend fun saveDocumentText(
        gameId: EntityId,
        columnType: CellColumnType,
        expectedDocumentText: String,
        newDocumentText: String,
    ): Boolean = error("Narrowing a screen never rewrites a cell.")
}

class NoTaskCreation : TaskCreationFromText {
    override suspend fun createTasks(
        selection: CellTextSelection,
        drafts: List<TaskDraft>,
    ): List<EntityId> = error("Narrowing a screen never creates a task.")
}
