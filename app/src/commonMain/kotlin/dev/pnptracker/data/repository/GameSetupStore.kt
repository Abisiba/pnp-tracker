package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.GameDao
import dev.pnptracker.data.database.dao.ItemDao
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.entity.ItemEntity
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.games.ItemSummary
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * Building up the games and items the user is tracking.
 *
 * Everything here is the user's own doing. Nothing on this path is ever reached
 * by an import: a game or an item exists because someone typed it, which is what
 * keeps `sourceImportBatchId` meaningful as a record of where a game came from.
 */
interface GameSetup {
    /** Every game the user still has, newest edits included, ordered for the list. */
    fun observeGames(): Flow<List<GameSummary>>

    /** The items of one game, and never another game's. */
    fun observeItems(gameId: EntityId): Flow<List<ItemSummary>>

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
     * Creates an item under a game that is really there.
     *
     * @throws IllegalArgumentException if the name says nothing.
     * @throws GameSetupException if the game is gone, or the item did not save.
     */
    suspend fun createItem(
        gameId: EntityId,
        name: String,
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
    private val itemDao: ItemDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
) : GameSetup {
    override fun observeGames(): Flow<List<GameSummary>> =
        gameDao.observeActiveGames().map { games ->
            games.map { GameSummary(it.id, it.name, it.isManuallyCompleted) }
        }

    override fun observeItems(gameId: EntityId): Flow<List<ItemSummary>> =
        itemDao.observeActiveItemsOfGame(gameId).map { items ->
            items.map { ItemSummary(it.id, it.gameId, it.name) }
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

    override suspend fun createItem(
        gameId: EntityId,
        name: String,
    ): EntityId {
        val cleanName = requireUsableName(name, "item")
        val moment = clock.now()
        val item =
            ItemEntity(
                id = idGenerator.newId(),
                gameId = gameId,
                name = cleanName,
                createdAt = moment,
                updatedAt = moment,
            )
        try {
            itemDao.addItemToActiveGame(item)
        } catch (cause: IllegalArgumentException) {
            // The one thing this check reports is a game that is not there any
            // more, which is something the user can see and act on.
            throw GameSetupException(GameSetupFailure.GAME_NOT_AVAILABLE, cause)
        } catch (cause: SQLiteException) {
            throw GameSetupException(GameSetupFailure.COULD_NOT_SAVE, cause)
        }
        return item.id
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
