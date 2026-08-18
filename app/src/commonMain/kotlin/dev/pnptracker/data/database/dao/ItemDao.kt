package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import dev.pnptracker.data.database.entity.ItemEntity
import dev.pnptracker.domain.model.EntityId
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
