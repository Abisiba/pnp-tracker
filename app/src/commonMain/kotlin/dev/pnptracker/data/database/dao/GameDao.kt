package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.domain.model.EntityId
import kotlin.time.Instant

/**
 * Reads and writes game rows. Queries without an explicit "including deleted"
 * name only ever see rows that are not soft deleted.
 */
@Dao
interface GameDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(game: GameEntity)

    @Query("SELECT * FROM games WHERE deleted_at IS NULL ORDER BY name")
    suspend fun activeGames(): List<GameEntity>

    @Query("SELECT * FROM games WHERE id = :id AND deleted_at IS NULL")
    suspend fun activeGameById(id: EntityId): GameEntity?

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun gameByIdIncludingDeleted(id: EntityId): GameEntity?

    @Query("SELECT * FROM games ORDER BY name")
    suspend fun allGamesIncludingDeleted(): List<GameEntity>

    /**
     * Marks a game as deleted. Deleting an already deleted game changes nothing,
     * so the stored timestamps keep pointing at the first deletion.
     *
     * @return how many rows changed: 1 on the first call, 0 afterwards.
     */
    @Query("UPDATE games SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :id AND deleted_at IS NULL")
    suspend fun softDelete(
        id: EntityId,
        deletedAt: Instant,
    ): Int

    @Query("SELECT COUNT(*) FROM games WHERE deleted_at IS NULL")
    suspend fun activeCount(): Int

    @Query("SELECT COUNT(*) FROM games WHERE deleted_at IS NOT NULL")
    suspend fun deletedCount(): Int
}
