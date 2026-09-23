package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.entity.HistoryEventEntity
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.IdGenerator
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

    @Query("UPDATE games SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :id AND deleted_at IS NULL")
    suspend fun writeGameTombstone(
        id: EntityId,
        deletedAt: Instant,
    ): Int

    /**
     * Appends one line to the history. Only [softDelete] below has one to write.
     *
     * Public because a Room interface has no other visibility to offer, not
     * because anything outside this file should call it. There is no update and
     * no delete for these rows anywhere in the application: appending is the
     * whole of what the table supports (PLAN 5.12).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun appendHistoryEvent(event: HistoryEventEntity)

    /**
     * Marks a game as deleted, and records that it happened.
     *
     * Deleting an already deleted game changes nothing — the stored timestamps
     * keep pointing at the first deletion — and writes no second history line
     * either: the row count is what says whether this call was the deletion, so a
     * repeat cannot leave the history claiming a game was deleted twice.
     *
     * The event carries the tombstone's own moment rather than a second reading
     * of a clock, so the row and the history can never disagree about when it
     * happened. Both are written in one transaction: a history line that could
     * not be written takes the deletion down with it.
     *
     * @return how many rows changed: 1 on the first call, 0 afterwards.
     */
    @Transaction
    suspend fun softDelete(
        id: EntityId,
        deletedAt: Instant,
        eventId: EntityId = IdGenerator.Random.newId(),
    ): Int {
        val changed = writeGameTombstone(id, deletedAt)
        if (changed == 0) return 0
        appendHistoryEvent(
            HistoryEventEntity(
                id = eventId,
                kind = HistoryEventKind.GAME_DELETED,
                occurredAt = deletedAt,
                gameId = id,
            ),
        )
        return changed
    }

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

    /**
     * Writes a game a new name, and only if it really is a new one.
     *
     * The name it already has is part of the predicate rather than something
     * checked before the statement: PLAN 12.3 will not have a rename that
     * changes nothing touch the row at all, and a check made in another
     * statement is a check something could slip between. Nothing but the name
     * and the moment is written — a game's cells, tasks and completion are not
     * this call's business.
     *
     * @return 1 when the name was written, 0 when the game is gone **or** was
     *   already called this; the two are told apart by reading the row back,
     *   which is only ever needed on the path that wrote nothing.
     */
    @Query(
        """
        UPDATE games
        SET name = :name, updated_at = :updatedAt
        WHERE id = :id AND deleted_at IS NULL AND name <> :name
        """,
    )
    suspend fun rename(
        id: EntityId,
        name: String,
        updatedAt: Instant,
    ): Int

    @Query("SELECT COUNT(*) FROM games WHERE deleted_at IS NULL")
    suspend fun activeCount(): Int

    @Query("SELECT COUNT(*) FROM games WHERE deleted_at IS NOT NULL")
    suspend fun deletedCount(): Int
}
