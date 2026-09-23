package dev.pnptracker.ui

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.repository.CellTextStore
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.data.repository.GameSetupStore
import dev.pnptracker.data.repository.GameTableStore
import dev.pnptracker.data.repository.PoolStore
import dev.pnptracker.data.repository.TaskCreationFromText
import dev.pnptracker.data.repository.TaskEditStore
import dev.pnptracker.data.repository.TaskFromTextStore
import dev.pnptracker.data.repository.TaskProgressStore
import dev.pnptracker.data.repository.TaskProgressing
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.pools.PoolModel
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.ui.feature.games.CellWork
import dev.pnptracker.ui.feature.games.GameTableController
import dev.pnptracker.ui.feature.games.GameTableRowsState
import dev.pnptracker.ui.feature.pools.PoolCardKey
import dev.pnptracker.ui.feature.pools.PoolContentState
import dev.pnptracker.ui.feature.pools.PoolController
import dev.pnptracker.ui.feature.pools.PoolControllers
import java.nio.file.Files

/**
 * The application's own stack, over a database of its own.
 *
 * Built the way `Main.kt` builds it — the same stores over the same DAOs, one
 * progress store shared by the table and the pools — so a test that drives this
 * is driving what the user drives, down to the transaction. The database lives
 * in a temporary directory, and closing this checks that the real application
 * file was not touched.
 */
class RealStack : AutoCloseable {
    private val realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
    private val directory = TemporaryDatabaseDirectory()
    val database: AppDatabase = DatabaseFactory().open(directory.databaseFile)

    private val colorCatalogue = ColorCatalogueStore(database.colorDao())
    val taskProgress = TaskProgressStore(database.taskProgressDao())
    private val overrides = mutableMapOf<PoolType, PoolController>()

    val taskCreation = TaskFromTextStore(database.taskFromTextDao())
    val gameSetup: GameSetup = GameSetupStore(database.gameDao(), database.gameCellDao())

    val table = tableControllerWith()

    /**
     * A table controller over the real stores, with [creation] making the tasks
     * and [setup] holding the games — either of them replaceable for the one
     * write a test needs to go wrong.
     */
    fun tableControllerWith(
        creation: TaskCreationFromText = taskCreation,
        setup: GameSetup = gameSetup,
    ): GameTableController =
        GameTableController(
            table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao()),
            setup = setup,
            cells = CellTextStore(database.cellSegmentDao()),
            colors = colorCatalogue,
            taskCreation = creation,
            taskEditing = TaskEditStore(database.taskEditDao()),
            taskProgress = taskProgress,
        )

    val pools =
        PoolControllers(
            pools = PoolStore(database.poolDao()),
            colors = colorCatalogue,
            taskEditing = TaskEditStore(database.taskEditDao()),
            taskProgress = taskProgress,
        )

    /** The rows the table is showing now, or none. */
    fun rows() = (table.state.rows as? GameTableRowsState.Content)?.rows.orEmpty()

    fun gameNamed(name: String): EntityId = rows().first { it.gameName == name }.gameId

    /** The pieces of one cell as the table holds them now. */
    fun piecesOf(
        gameId: EntityId,
        columnType: CellColumnType,
    ) = rows()
        .firstOrNull { it.gameId == gameId }
        ?.cell(columnType)
        ?.segments
        .orEmpty()

    /**
     * A pool controller over the real stores with [progress] in place of the
     * real progress store — for the one write a test needs to go wrong. It
     * becomes the pool [cardsIn] reads.
     */
    fun poolControllerWith(
        poolType: PoolType,
        progress: TaskProgressing,
    ): PoolController =
        PoolController(
            poolType = poolType,
            pools = PoolStore(database.poolDao()),
            colors = colorCatalogue,
            taskEditing = TaskEditStore(database.taskEditDao()),
            taskProgress = progress,
        ).also { overrides[poolType] = it }

    /**
     * Every card one pool is drawing now, with the key the screen draws it under.
     *
     * A task made in several colours is drawn once per colour, so it can appear
     * here more than once — as it does on the screen.
     */
    fun cardsIn(poolType: PoolType): List<Pair<PoolCardKey, PoolTask>> {
        val pool = overrides[poolType] ?: pools.of(poolType)
        val model = (pool.state.content as? PoolContentState.Content)?.model ?: return emptyList()
        return when (model) {
            is PoolModel.Flat -> model.tasks.map { PoolCardKey(it.taskId) to it }
            is PoolModel.ThreeD ->
                model.sections.awaitingColor.tasks
                    .map { PoolCardKey(it.taskId) to it } +
                    (model.sections.singleColorGroups + model.sections.multicolorGroups).flatMap { group ->
                        group.tasks.map { PoolCardKey(it.taskId, group.color.colorId) to it }
                    }
        }
    }

    /** The work open in the table, whatever it is. */
    val work: CellWork? get() = table.state.work

    override fun close() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }
}
