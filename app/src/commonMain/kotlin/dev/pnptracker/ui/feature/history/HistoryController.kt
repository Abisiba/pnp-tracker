package dev.pnptracker.ui.feature.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.HistorySource
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.readShownAsFailed
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.games.GameRestoreOutcome
import dev.pnptracker.domain.games.GameRestoring
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.history.HistoryEntry
import dev.pnptracker.domain.history.HistoryFilter
import dev.pnptracker.domain.history.HistoryLog
import dev.pnptracker.domain.history.HistoryPeriod
import dev.pnptracker.domain.history.filterHistory
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/**
 * The history section: what has happened, and which part of it is being read.
 *
 * No line is ever changed from here — PLAN 5.12 makes the record something
 * appended by the transaction that caused it and never edited afterwards. The
 * one thing this section does besides reading is bring a deleted game back
 * ([restoreGame]), and that is a new act written by its own transaction with a
 * line of its own, not an edit of the line it was asked from.
 *
 * The reading is a stream, so a task finished in the pool while this section is
 * open appears here without anything having to remember to reload. The filters
 * live beside it rather than inside it: a fresh reading replaces the lines and
 * leaves what the user asked to see exactly as it was.
 */
class HistoryController(
    private val history: HistorySource,
    private val clock: kotlin.time.Clock = kotlin.time.Clock.System,
    private val diagnostics: Diagnostics = Diagnostics.None,
    /** What brings a deleted game back; a history with none offers nothing to take back. */
    private val games: GameRestoring = GameRestoring.Unavailable,
) {
    var state: HistoryScreenState by mutableStateOf(HistoryScreenState())
        private set

    /**
     * Follows the history until cancelled.
     *
     * A read that fails leaves the section saying so rather than sitting on
     * `Loading` for ever. Cancellation is not a failure and is passed on
     * untouched, so leaving the section does not paint an error on the way out.
     */
    suspend fun observeHistory() {
        try {
            history.observeHistory().collect { log -> show(log) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // The screen says the same thing whatever it was; the record says which.
            diagnostics.recordSafely { readShownAsFailed(DiagnosticArea.HISTORY, failure) }
            state = state.copy(content = HistoryContentState.Failed)
        }
    }

    /**
     * Brings back the game [entry] says was deleted, as the same game (PLAN 12.15).
     *
     * Only from a line that can still be taken back, and once: a second press
     * arriving while the first is in flight finds it restoring and does nothing.
     * The line that says so arrives through the reading, like every other one;
     * what is said here is only how it went, and whether another game in the
     * table now has the same name.
     */
    suspend fun restoreGame(entry: HistoryEntry) {
        if (state.restoring != null || !state.canRestore(entry)) return
        val gameId = entry.gameId ?: return
        state = state.copy(restoring = gameId, restoreNotice = null)
        val notice =
            try {
                when (val outcome = games.restoreGame(gameId)) {
                    is GameRestoreOutcome.Restored -> GameRestoreNotice.Restored(outcome.name, outcome.namesakes)
                    // Brought back already, from somewhere else: nothing to say.
                    GameRestoreOutcome.AlreadyThere -> null
                }
            } catch (refusal: GameSetupException) {
                GameRestoreNotice.Failed(refusal.failure)
            }
        state = state.copy(restoring = null, restoreNotice = notice)
    }

    /** Clears what the last `Geri al` said. */
    fun dismissRestoreNotice() {
        state = state.copy(restoreNotice = null)
    }

    /** Shows one game's lines, or every game's when [gameId] is null. */
    fun chooseGame(gameId: EntityId?) {
        narrowTo(state.filter.copy(gameId = gameId))
    }

    /** Shows only what happened inside [period]. */
    fun choosePeriod(period: HistoryPeriod) {
        narrowTo(state.filter.copy(period = period))
    }

    /** Goes back to the whole history. */
    fun clearFilters() {
        narrowTo(HistoryFilter.NONE)
    }

    fun openFilters() {
        state = state.copy(filterSurface = HistoryFilterSurface.OPEN)
    }

    fun closeFilters() {
        state = state.copy(filterSurface = HistoryFilterSurface.CLOSED, focusRecall = state.focusRecall + 1)
    }

    private fun narrowTo(filter: HistoryFilter) {
        if (filter == state.filter) return
        state = state.copy(filter = filter)
        reapply()
    }

    /**
     * Puts a fresh reading on screen, keeping the filters in force.
     *
     * A game that has been filtered to and then stops appearing in the history
     * is left chosen rather than quietly dropped. It cannot happen from inside
     * the application — history is only ever appended to — and silently widening
     * what somebody asked for would be worse than an empty screen they can see
     * the reason for.
     */
    private fun show(log: HistoryLog) {
        state =
            state.copy(
                content =
                    HistoryContentState.Content(
                        log = log,
                        shown = filterHistory(log.entries, state.filter, clock.now()),
                    ),
            )
    }

    /** Re-reads the filters over the lines already in hand, without asking again. */
    private fun reapply() {
        val content = state.content as? HistoryContentState.Content ?: return
        state =
            state.copy(
                content = content.copy(shown = filterHistory(content.log.entries, state.filter, clock.now())),
            )
    }
}
