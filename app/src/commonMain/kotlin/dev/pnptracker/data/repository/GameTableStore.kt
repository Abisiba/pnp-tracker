package dev.pnptracker.data.repository

import dev.pnptracker.data.database.dao.GameCellDao
import dev.pnptracker.data.database.dao.GameDao
import dev.pnptracker.data.database.dao.GameTableDao
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.projection.CellContentRow
import dev.pnptracker.domain.games.CellPreview
import dev.pnptracker.domain.games.CellSegmentPreview
import dev.pnptracker.domain.games.GameTableRow
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
 * Folds the three table reads into rows.
 *
 * The reads are deliberately whole-table rather than per game. Drawing a row is
 * then a matter of looking things up in two maps, and a library of two hundred
 * games costs the same three queries as a library of two.
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
        ) { games, cells, contents -> rowsOf(games, cells, contents) }

    private fun rowsOf(
        games: List<GameEntity>,
        cells: List<GameCellEntity>,
        contents: List<CellContentRow>,
    ): List<GameTableRow> {
        val cellsByGame: Map<EntityId, List<GameCellEntity>> = cells.groupBy { it.gameId }
        val contentsByCell: Map<EntityId, List<CellContentRow>> = contents.groupBy { it.cellId }
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
                        CellPreview(
                            columnType = columnType,
                            cellId = cell?.id,
                            segments = cell?.let { previewsOf(contentsByCell[it.id].orEmpty()) }.orEmpty(),
                        )
                    },
            )
        }
    }

    /**
     * Turns the pieces of one cell into what the table shows of them.
     *
     * A piece naming a task the user deleted is left out — the task is gone from
     * every active view, and its piece has nothing to show — while the pieces
     * around it keep their places. The read is ordered by position already, so
     * nothing is sorted here.
     */
    private fun previewsOf(rows: List<CellContentRow>): List<CellSegmentPreview> =
        rows.mapNotNull { row ->
            when (row.kind) {
                SegmentKind.PLAIN_TEXT ->
                    row.text?.let { CellSegmentPreview(taskId = null, text = it) }

                SegmentKind.TASK ->
                    row.taskId?.let { taskId ->
                        CellSegmentPreview(
                            taskId = taskId,
                            text = row.taskName.orEmpty(),
                            isCompletedTask = row.taskIsCompleted == true,
                        )
                    }
            }
        }
}
