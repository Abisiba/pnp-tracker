package dev.pnptracker.data.repository

import dev.pnptracker.data.database.dao.HistoryDao
import dev.pnptracker.data.database.projection.HistoryEventRow
import dev.pnptracker.data.database.projection.ProgressHistoryRow
import dev.pnptracker.domain.history.HistoryChange
import dev.pnptracker.domain.history.HistoryEntry
import dev.pnptracker.domain.history.HistoryLog
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.ProgressEventKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Where the history screen reads what it shows. */
interface HistorySource {
    /**
     * The whole history as a stream, newest first.
     *
     * One stream for the screen rather than one per filter. What the user asks
     * to see is decided above this, over the lines this already returned: PLAN
     * 13 has filters that are moved between freely, and a fresh query and a
     * fresh stream on every change of mind is the shape PLAN 16 rules out.
     */
    fun observeHistory(): Flow<HistoryLog>
}

/**
 * Gathers the two records of the past into one reading.
 *
 * Two reads and never more, whether the history holds ten lines or ten thousand:
 * PLAN 16 rules out a read per row, and both queries carry the game and task
 * names with them so nothing above has to go back and ask who anybody is.
 *
 * The two are kept apart in the database and joined here rather than unioned in
 * SQL. They are different tables with different columns and different meanings —
 * one is about pieces owed, the other about things that happened — and a union
 * would have to flatten both into a shape that suits neither, with a column that
 * means one thing on half the rows.
 *
 * Nothing here writes. Opening the history costs two reads and nothing else,
 * which is what makes the screen a reading of the record rather than a second
 * record of its own.
 */
class HistoryStore(
    private val historyDao: HistoryDao,
) : HistorySource {
    override fun observeHistory(): Flow<HistoryLog> =
        combine(
            historyDao.observeHistoryEvents(),
            historyDao.observeProgressHistory(),
        ) { events, progress ->
            HistoryLog(merged(events.map(::entryOf) + progress.map(::entryOf)))
        }

    /**
     * The two readings put in one order, newest first.
     *
     * The comparison is the same total order both queries already answered in —
     * moment first, then identity — so a line cannot change places with another
     * because of which table it came from. Identity is compared as text because
     * that is how it is stored and ordered in SQLite; comparing it any other way
     * would give the screen a different order from the database's.
     */
    private fun merged(entries: List<HistoryEntry>): List<HistoryEntry> =
        entries.sortedWith(
            compareByDescending<HistoryEntry> { it.occurredAt }
                .thenByDescending { it.id.toString() },
        )

    private fun entryOf(row: HistoryEventRow): HistoryEntry =
        HistoryEntry(
            id = row.eventId,
            occurredAt = row.occurredAt,
            change = changeOf(row),
            gameId = row.gameId,
            gameName = row.gameName,
            taskId = row.taskId,
            taskName = row.taskName,
        )

    /**
     * What one recorded event says happened.
     *
     * A stage line that came back without its counts is dropped to a plain
     * completion rather than crashing the screen. It cannot happen — the entity
     * requires all three together and nothing can edit a row afterwards — but a
     * history screen is the last place to answer an impossible row with an
     * exception, and this is the only branch in the file that exists for a case
     * that should not arise.
     */
    private fun changeOf(row: HistoryEventRow): HistoryChange =
        when (row.kind) {
            HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED ->
                if (row.stage != null && row.previousQuantity != null && row.newQuantity != null) {
                    HistoryChange.StageMoved(
                        stage = row.stage,
                        previousQuantity = row.previousQuantity,
                        newQuantity = row.newQuantity,
                    )
                } else {
                    HistoryChange.TaskCompleted
                }

            HistoryEventKind.TASK_COMPLETED -> HistoryChange.TaskCompleted
            HistoryEventKind.TASK_REOPENED -> HistoryChange.TaskReopened
            HistoryEventKind.TASK_DELETED -> HistoryChange.TaskDeleted
            HistoryEventKind.TASK_RESTORED -> HistoryChange.TaskRestored
            HistoryEventKind.TASK_CONVERTED_TO_TEXT -> HistoryChange.TaskConvertedToText
            HistoryEventKind.GAME_DELETED -> HistoryChange.GameDeleted
            HistoryEventKind.GAME_RESTORED -> HistoryChange.GameRestored
        }

    private fun entryOf(row: ProgressHistoryRow): HistoryEntry =
        HistoryEntry(
            id = row.eventId,
            occurredAt = row.occurredAt,
            change =
                when (row.kind) {
                    ProgressEventKind.FAILURE_REPORTED ->
                        HistoryChange.ShortageReported(
                            quantity = row.quantity,
                            note = row.note,
                            cardReference = row.cardReference,
                            stage = row.stage,
                        )

                    ProgressEventKind.SHORTAGE_RESOLVED ->
                        HistoryChange.ShortageResolved(
                            quantity = row.quantity,
                            note = row.note,
                            cardReference = row.cardReference,
                            stage = row.stage,
                        )
                },
            gameId = row.gameId,
            gameName = row.gameName,
            taskId = row.taskId,
            taskName = row.taskName,
        )
}
