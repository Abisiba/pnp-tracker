package dev.pnptracker.data.repository

import dev.pnptracker.data.database.dao.GameCellDao
import dev.pnptracker.data.database.dao.GameDao
import dev.pnptracker.data.database.dao.GameTableDao
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.projection.CellContentRow
import dev.pnptracker.data.database.projection.TaskColorRow
import dev.pnptracker.domain.games.CellPreview
import dev.pnptracker.domain.games.CellSegmentPreview
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.TaskColorPreview
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.SegmentKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The whole game table, as the screen draws it. */
interface GameTableSource {
    /**
     * Every game the user still has, each as one complete row.
     *
     * All of them, not a view of them: the three views of PLAN 12.4 are a filter
     * over these rows and not three different reads, so moving between them costs
     * nothing and cannot write anything.
     */
    fun observeTable(): Flow<List<GameTableRow>>
}

/**
 * Folds the four table reads into rows.
 *
 * The reads are deliberately whole-table rather than per game. Drawing a row is
 * then a matter of looking things up in three maps, and a library of two hundred
 * games costs the same four queries as a library of two.
 *
 * Nothing here writes. A game with no cells is shown with five empty ones rather
 * than having them created for it: PLAN 5.4 gives a game at most one cell per
 * column, and opening one is something the user does by writing in it.
 */
class GameTableStore(
    private val gameDao: GameDao,
    private val gameCellDao: GameCellDao,
    private val gameTableDao: GameTableDao,
) : GameTableSource {
    override fun observeTable(): Flow<List<GameTableRow>> =
        combine(
            gameDao.observeActiveGames(),
            gameCellDao.observeCellsOfActiveGames(),
            gameTableDao.observeCellContents(),
            gameTableDao.observeTaskColors(),
        ) { games, cells, contents, taskColors -> rowsOf(games, cells, contents, taskColors) }

    private fun rowsOf(
        games: List<GameEntity>,
        cells: List<GameCellEntity>,
        contents: List<CellContentRow>,
        taskColors: List<TaskColorRow>,
    ): List<GameTableRow> {
        val cellsByGame: Map<EntityId, List<GameCellEntity>> = cells.groupBy { it.gameId }
        val contentsByCell: Map<EntityId, List<CellContentRow>> = contents.groupBy { it.cellId }
        // Grouped once for the whole table rather than looked up per task: the
        // read already arrives in slot order, so this keeps it.
        val colorsByTask: Map<EntityId, List<TaskColorPreview>> =
            taskColors
                .groupBy { it.taskId }
                .mapValues { (_, rows) ->
                    rows.map { TaskColorPreview(colorId = it.colorId, canonicalName = it.canonicalName, hex = it.hex) }
                }
        return games.map { game ->
            val ofThisGame = cellsByGame[game.id].orEmpty().associateBy { it.columnType }
            GameTableRow(
                gameId = game.id,
                gameName = game.name,
                isCompleted = game.isManuallyCompleted,
                // Every column, every time: the table is rectangular whether or
                // not the user has written in a given cell.
                cells =
                    CellColumnType.entries.map { columnType ->
                        val cell = ofThisGame[columnType]
                        val contents = cell?.let { contentsByCell[it.id].orEmpty() }.orEmpty()
                        CellPreview(
                            columnType = columnType,
                            cellId = cell?.id,
                            segments = previewsOf(contents, colorsByTask),
                            // Asked of what is stored, not of what is shown: a
                            // piece naming a deleted task previews as nothing
                            // and still makes the cell one an editor must not
                            // rewrite as a single string.
                            holdsTasks = contents.any { it.kind == SegmentKind.TASK },
                        )
                    },
            )
        }
    }

    /**
     * Turns the pieces of one cell into what the table shows of them.
     *
     * Every piece the cell holds comes through. A task piece is drawn as the task
     * it names whatever state that task is in: leaving one out would show a
     * document shorter than the one stored, and the editor would then be working
     * around a boundary the reader was never shown. The read is ordered by
     * position already, so nothing is sorted here.
     */
    private fun previewsOf(
        rows: List<CellContentRow>,
        colorsByTask: Map<EntityId, List<TaskColorPreview>>,
    ): List<CellSegmentPreview> =
        rows.mapNotNull { row ->
            when (row.kind) {
                SegmentKind.PLAIN_TEXT ->
                    row.text?.let {
                        CellSegmentPreview(segmentId = row.segmentId, taskId = null, text = it)
                    }

                SegmentKind.TASK ->
                    row.taskId?.let { taskId ->
                        CellSegmentPreview(
                            segmentId = row.segmentId,
                            taskId = taskId,
                            // The document text of a task piece is its name and
                            // nothing more. The count beside it on screen is
                            // metadata and is deliberately not part of this.
                            text = row.taskName.orEmpty(),
                            isCompletedTask = row.taskIsCompleted == true,
                            requiredQuantity = row.taskRequiredQuantity,
                            colors = colorsByTask[taskId].orEmpty(),
                            notes = row.taskNotes,
                            trackingMode = row.taskTrackingMode,
                            poolType = row.taskPoolType,
                            currentMissingQuantity = row.taskCurrentMissingQuantity ?: 0,
                            hasProgress = row.taskHasProgress == true,
                        )
                    }
            }
        }
}
