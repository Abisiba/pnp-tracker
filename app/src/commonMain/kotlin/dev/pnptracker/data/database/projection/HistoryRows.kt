package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.ProgressEventKind
import kotlin.time.Instant

/**
 * One line of the history that came from `history_events`, with the names the
 * screen shows instead of the identities it stores.
 *
 * The names are joined rather than snapshotted, which is a decision and not an
 * omission: a game or a task renamed after the event is shown under the name it
 * has now, because that is the one the user would look for. Nothing is lost by
 * it — both rows are tombstoned rather than erased (PLAN 141), so the join
 * still answers for a task that was deleted or turned back into words.
 */
data class HistoryEventRow(
    @ColumnInfo(name = "event_id")
    val eventId: EntityId,
    @ColumnInfo(name = "kind")
    val kind: HistoryEventKind,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Instant,
    @ColumnInfo(name = "game_id")
    val gameId: EntityId,
    @ColumnInfo(name = "game_name")
    val gameName: String,
    /** Absent only on the two kinds that are about the game itself. */
    @ColumnInfo(name = "task_id")
    val taskId: EntityId?,
    @ColumnInfo(name = "task_name")
    val taskName: String?,
    @ColumnInfo(name = "stage")
    val stage: ProductionStage?,
    @ColumnInfo(name = "previous_quantity")
    val previousQuantity: Int?,
    @ColumnInfo(name = "new_quantity")
    val newQuantity: Int?,
)

/**
 * One line of the history that came from `progress_events`: a shortage reported
 * or a shortage made good (PLAN 1119, 1120).
 *
 * These rows predate `history_events` and carry no game of their own, so the
 * game is worked out on the way out. It can be absent — a task with no piece in
 * any cell and nothing in `history_events` to place it — and the row is still
 * returned rather than dropped, because a movement that happened is part of the
 * history whether or not its game can still be named.
 */
data class ProgressHistoryRow(
    @ColumnInfo(name = "event_id")
    val eventId: EntityId,
    @ColumnInfo(name = "kind")
    val kind: ProgressEventKind,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Instant,
    @ColumnInfo(name = "game_id")
    val gameId: EntityId?,
    @ColumnInfo(name = "game_name")
    val gameName: String?,
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    @ColumnInfo(name = "task_name")
    val taskName: String,
    @ColumnInfo(name = "quantity")
    val quantity: Int,
    @ColumnInfo(name = "note")
    val note: String?,
    @ColumnInfo(name = "card_reference")
    val cardReference: String?,
    @ColumnInfo(name = "stage")
    val stage: ProductionStage?,
)
