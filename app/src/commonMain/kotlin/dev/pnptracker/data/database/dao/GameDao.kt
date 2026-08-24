package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.flow.Flow
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

    /**
     * Every game the user still has, in the order the list shows them.
     *
     * `name` decides the order and `id` settles a tie, so two games sharing a name
     * keep a fixed position instead of swapping places between reads.
     */
    @Query("SELECT * FROM games WHERE deleted_at IS NULL ORDER BY name, id")
    fun observeActiveGames(): Flow<List<GameEntity>>

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

    /**
     * Records the user's own judgement that a game is finished, or takes it back.
     *
     * Only this one game is touched, and only while it still exists: the id is the
     * whole of the predicate besides the soft delete check. Nothing below a game
     * is looked at, because a game being finished is the user's statement and not
     * a summary of its cells or tasks.
     *
     * @return 1 when the game was there and active, 0 otherwise.
     */
    @Query(
        """
        UPDATE games
        SET is_manually_completed = :isCompleted, completed_at = :completedAt, updated_at = :updatedAt
        WHERE id = :id AND deleted_at IS NULL
        """,
    )
    suspend fun setManuallyCompleted(
        id: EntityId,
        isCompleted: Boolean,
        completedAt: Instant?,
        updatedAt: Instant,
    ): Int

    @Query("SELECT COUNT(*) FROM games WHERE deleted_at IS NULL")
    suspend fun activeCount(): Int

    @Query("SELECT COUNT(*) FROM games WHERE deleted_at IS NOT NULL")
    suspend fun deletedCount(): Int
}
