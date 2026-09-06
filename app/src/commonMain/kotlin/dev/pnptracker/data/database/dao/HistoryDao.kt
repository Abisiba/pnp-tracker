package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import dev.pnptracker.data.database.entity.HistoryEventEntity
import dev.pnptracker.data.database.projection.HistoryEventRow
import dev.pnptracker.data.database.projection.ProgressHistoryRow
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.flow.Flow

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
 * The history screen of PLAN 12.15 is drawn from the two observed reads at the
 * bottom of this file, and from nothing else. Both are whole-table rather than
 * per row: PLAN 16 rules out a read per record, and a screen that asked which
 * game each event belonged to one event at a time is exactly that shape.
 *
 * Everything one transaction writes carries the same moment, because it is the
 * same moment: a save that moves three steps of a pipeline and finishes the task
 * did all of that at once. So every read below settles ties by identity to stay
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

    /**
     * Everything recorded in `history_events`, newest first, with the names the
     * screen shows.
     *
     * Newest first because that is what a history is opened for: PLAN 12.15 asks
     * what has happened, and the answer somebody wants at the top is the last
     * thing that did. Identity settles ties in the same direction, so lines
     * written by one transaction keep a fixed order between themselves without
     * that order meaning anything.
     *
     * Neither join can multiply a row. `games.id` and `tasks.id` are primary
     * keys, so each event matches exactly one of each — the task join is LEFT
     * only because the two game kinds name no task at all, never because a task
     * might be missing. A deleted task and a task turned back into words are
     * both still here (PLAN 141 tombstones rather than erases), which is what
     * lets PLAN 1123's lines be shown at all.
     */
    @Query(
        """
        SELECT history_events.id AS event_id,
               history_events.kind AS kind,
               history_events.occurred_at AS occurred_at,
               games.id AS game_id,
               games.name AS game_name,
               tasks.id AS task_id,
               tasks.name AS task_name,
               history_events.stage AS stage,
               history_events.previous_quantity AS previous_quantity,
               history_events.new_quantity AS new_quantity
        FROM history_events
        INNER JOIN games ON games.id = history_events.game_id
        LEFT JOIN tasks ON tasks.id = history_events.task_id
        ORDER BY history_events.occurred_at DESC, history_events.id DESC
        """,
    )
    fun observeHistoryEvents(): Flow<List<HistoryEventRow>>

    /**
     * The 3D shortage lines PLAN 1119 and 1120 ask the same screen for.
     *
     * They live in `progress_events` and always have: PLAN 5.12 records a
     * shortage and its making-good as events long before `history_events`
     * existed, and copying them into a second table would be two records of one
     * fact, free to disagree.
     *
     * A progress event carries no game, so one is worked out here. The first
     * answer is the ordinary one — the task's piece of a cell, and the cell's
     * game — and `cell_segments.task_id` is unique, so that subquery is a single
     * value and cannot multiply anything. The second answer is for a task whose
     * piece is gone: turning a task back into words takes its segment away
     * (PLAN 12.8) and leaves a shortage nothing can place, so the game the
     * history itself recorded at that moment stands in. A row that still cannot
     * be placed comes back with no game rather than not at all — the movement
     * happened, and PLAN 12.15 asks for it.
     */
    @Query(
        """
        SELECT progress_events.id AS event_id,
               progress_events.kind AS kind,
               progress_events.recorded_at AS occurred_at,
               games.id AS game_id,
               games.name AS game_name,
               tasks.id AS task_id,
               tasks.name AS task_name,
               progress_events.quantity AS quantity,
               progress_events.note AS note,
               progress_events.card_reference AS card_reference,
               progress_events.stage AS stage
        FROM progress_events
        INNER JOIN tasks ON tasks.id = progress_events.task_id
        LEFT JOIN games ON games.id = COALESCE(
            (SELECT game_cells.game_id
               FROM cell_segments
               INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
              WHERE cell_segments.task_id = progress_events.task_id),
            (SELECT history_events.game_id
               FROM history_events
              WHERE history_events.task_id = progress_events.task_id
              ORDER BY history_events.occurred_at, history_events.id
              LIMIT 1)
        )
        ORDER BY progress_events.recorded_at DESC, progress_events.id DESC
        """,
    )
    fun observeProgressHistory(): Flow<List<ProgressHistoryRow>>
}
