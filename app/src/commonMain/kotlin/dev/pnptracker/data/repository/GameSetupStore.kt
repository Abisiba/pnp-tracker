package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.GameCellDao
import dev.pnptracker.data.database.dao.GameDao
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.domain.games.CellSummary
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * Building up the game rows the user is tracking, and the cells in them.
 *
 * Everything here is the user's own doing. Nothing on this path is ever reached
 * by an import: a game exists because someone typed it, which is what keeps
 * `sourceImportBatchId` meaningful as a record of where a game came from.
 */
interface GameSetup {
    /** Every game the user still has, newest edits included, ordered for the list. */
    fun observeGames(): Flow<List<GameSummary>>

    /** The cells one game has opened, and never another game's. */
    fun observeCells(gameId: EntityId): Flow<List<CellSummary>>

    /**
     * Creates a game the user typed.
     *
     * The name is trimmed at both ends, because trailing spaces are a slip rather
     * than a decision, and everything inside it is left alone.
     *
     * @throws IllegalArgumentException if the name says nothing.
     * @throws GameSetupException if the game did not reach the database.
     */
    suspend fun createGame(name: String): EntityId

    /**
     * Hands back a game's cell for one column, opening it the first time.
     *
     * Asking twice for the same column gives the same cell back rather than a
     * second one; a game has at most one cell per column.
     *
     * @throws GameSetupException if the game is gone, or the cell did not save.
     */
    suspend fun openCell(
        gameId: EntityId,
        columnType: CellColumnType,
    ): EntityId

    /**
     * Records that the user considers a game finished, or takes it back.
     *
     * @throws GameSetupException if the change did not reach the database.
     */
    suspend fun setGameCompleted(
        gameId: EntityId,
        isCompleted: Boolean,
    )
}

class GameSetupStore(
    private val gameDao: GameDao,
    private val gameCellDao: GameCellDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
) : GameSetup {
    override fun observeGames(): Flow<List<GameSummary>> =
        gameDao.observeActiveGames().map { games ->
            games.map { GameSummary(it.id, it.name, it.isManuallyCompleted) }
        }

    override fun observeCells(gameId: EntityId): Flow<List<CellSummary>> =
        gameCellDao.observeCellsOfActiveGames().map { cells ->
            cells.filter { it.gameId == gameId }.map { CellSummary(it.id, it.gameId, it.columnType) }
        }

    override suspend fun createGame(name: String): EntityId {
        val cleanName = requireUsableName(name, "game")
        val moment = clock.now()
        val game =
            GameEntity(
                id = idGenerator.newId(),
                name = cleanName,
                createdAt = moment,
                updatedAt = moment,
                // Typed by hand, so there is no import behind it.
                sourceImportBatchId = null,
            )
        try {
            gameDao.insert(game)
        } catch (cause: SQLiteException) {
            throw GameSetupException(GameSetupFailure.COULD_NOT_SAVE, cause)
        }
        return game.id
    }

    override suspend fun openCell(
        gameId: EntityId,
        columnType: CellColumnType,
    ): EntityId {
        val moment = clock.now()
        return try {
            gameCellDao
                .cellFor(
                    gameId = gameId,
                    columnType = columnType,
                    id = idGenerator.newId(),
                    moment = moment,
                ).id
        } catch (cause: IllegalArgumentException) {
            // The one thing this check reports is a game that is not there any
            // more, which is something the user can see and act on.
            throw GameSetupException(GameSetupFailure.GAME_NOT_AVAILABLE, cause)
        } catch (cause: SQLiteException) {
            throw GameSetupException(GameSetupFailure.COULD_NOT_SAVE, cause)
        }
    }

    override suspend fun setGameCompleted(
        gameId: EntityId,
        isCompleted: Boolean,
    ) {
        val moment = clock.now()
        val changed =
            try {
                gameDao.setManuallyCompleted(
                    id = gameId,
                    isCompleted = isCompleted,
                    // Un-finishing clears the date, so a stale one cannot outlive it.
                    completedAt = moment.takeIf { isCompleted },
                    updatedAt = moment,
                )
            } catch (cause: SQLiteException) {
                throw GameSetupException(GameSetupFailure.COULD_NOT_SAVE, cause)
            }
        if (changed == 0) throw GameSetupException(GameSetupFailure.GAME_NOT_AVAILABLE)
    }

    private fun requireUsableName(
        name: String,
        what: String,
    ): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A $what needs a name." }
        return trimmed
    }
}
