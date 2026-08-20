package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.ItemEntity
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Reads and writes item rows.
 *
 * An item counts as active only when neither the item nor the game it belongs to
 * is soft deleted, so every active query joins `games`.
 */
@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ItemEntity)

    @Query(
        """
        SELECT items.* FROM items
        INNER JOIN games ON games.id = items.game_id
        WHERE items.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY items.name
        """,
    )
    suspend fun activeItems(): List<ItemEntity>

    @Query(
        """
        SELECT items.* FROM items
        INNER JOIN games ON games.id = items.game_id
        WHERE items.game_id = :gameId AND items.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY items.name
        """,
    )
    suspend fun activeItemsOfGame(gameId: EntityId): List<ItemEntity>

    /**
     * The items of one game, in the order the list shows them.
     *
     * The join is what keeps a game's items to itself: no other game's row can
     * come back from here, whatever id is passed in.
     */
    @Query(
        """
        SELECT items.* FROM items
        INNER JOIN games ON games.id = items.game_id
        WHERE items.game_id = :gameId AND items.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY items.name, items.id
        """,
    )
    fun observeActiveItemsOfGame(gameId: EntityId): Flow<List<ItemEntity>>

    /**
     * Every active item of every active game, for screens that let the user pick
     * one across games. Ordered by game first so the list reads the way it is
     * grouped; `id` keeps it from reshuffling when two names tie.
     */
    @Query(
        """
        SELECT items.* FROM items
        INNER JOIN games ON games.id = items.game_id
        WHERE items.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY games.name, items.name, items.id
        """,
    )
    fun observeActiveItems(): Flow<List<ItemEntity>>

    /** 1 while the game exists and has not been deleted, 0 otherwise. */
    @Query("SELECT COUNT(*) FROM games WHERE id = :gameId AND deleted_at IS NULL")
    suspend fun activeGameCount(gameId: EntityId): Int

    /**
     * Adds an item under a game that is really there.
     *
     * The foreign key already refuses an unknown game, but not a soft deleted one:
     * its row still exists. Checking in the same transaction as the insert closes
     * that gap, so an item can never end up under a game the user has thrown away.
     *
     * @throws IllegalArgumentException if the game is missing or deleted; nothing
     *   is written in that case.
     */
    @Transaction
    suspend fun addItemToActiveGame(item: ItemEntity) {
        require(activeGameCount(item.gameId) == 1) {
            "There is no game ${item.gameId} to add an item to."
        }
        insert(item)
    }

    @Query(
        """
        SELECT items.* FROM items
        INNER JOIN games ON games.id = items.game_id
        WHERE items.id = :id AND items.deleted_at IS NULL AND games.deleted_at IS NULL
        """,
    )
    suspend fun activeItemById(id: EntityId): ItemEntity?

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun itemByIdIncludingDeleted(id: EntityId): ItemEntity?

    @Query("SELECT * FROM items ORDER BY name")
    suspend fun allItemsIncludingDeleted(): List<ItemEntity>

    /**
     * Marks an item as deleted; a second call changes nothing.
     *
     * @return how many rows changed: 1 on the first call, 0 afterwards.
     */
    @Query("UPDATE items SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :id AND deleted_at IS NULL")
    suspend fun softDelete(
        id: EntityId,
        deletedAt: Instant,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM items
        INNER JOIN games ON games.id = items.game_id
        WHERE items.game_id = :gameId AND items.deleted_at IS NULL AND games.deleted_at IS NULL
        """,
    )
    suspend fun activeCountOfGame(gameId: EntityId): Int
}
