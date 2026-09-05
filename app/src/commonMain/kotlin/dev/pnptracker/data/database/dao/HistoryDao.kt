package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import dev.pnptracker.data.database.entity.HistoryEventEntity
import dev.pnptracker.domain.model.EntityId

/**
 * Reads the history back. It cannot write, and that is the point.
 *
 * A history event is written by the transaction that caused it — the one that
 * finishes the task, moves the pipeline or converts the words — because PLAN 385
 * has the record and the thing recorded stand or fall together. There is
 * therefore no `insert` here: a way to add a history line on its own would be a
 * way to write a past that never happened, and every caller that legitimately
 * has one to write is already inside the transaction it belongs to.
 *
 * There is no update and no delete either, in any DAO. Appending is the whole of
 * what this table supports.
 *
 * Nothing observes these rows yet. PLAN 1479's history screen is the next slice
 * and will read them; until then this is what the tests and that screen will use.
 *
 * Everything one transaction writes carries the same moment, because it is the
 * same moment: a save that moves three steps of a pipeline and finishes the task
 * did all of that at once. So the reads below settle ties by identity to stay
 * repeatable, and nothing should read an order into lines that share a time —
 * there is none to show.
 */
@Dao
interface HistoryDao {
    /** Everything that has happened, oldest first, with identity settling ties. */
    @Query("SELECT * FROM history_events ORDER BY occurred_at, id")
    suspend fun allEvents(): List<HistoryEventEntity>

    /** One task's history, in the order it happened. */
    @Query("SELECT * FROM history_events WHERE task_id = :taskId ORDER BY occurred_at, id")
    suspend fun eventsOfTask(taskId: EntityId): List<HistoryEventEntity>

    /** Everything that happened in one game, its tasks' events included. */
    @Query("SELECT * FROM history_events WHERE game_id = :gameId ORDER BY occurred_at, id")
    suspend fun eventsOfGame(gameId: EntityId): List<HistoryEventEntity>

    @Query("SELECT COUNT(*) FROM history_events")
    suspend fun eventCount(): Int
}
