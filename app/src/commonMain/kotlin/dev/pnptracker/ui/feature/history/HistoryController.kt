package dev.pnptracker.ui.feature.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.HistorySource
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
 * Read only, all the way down. There is no save, no retry that writes and no
 * action that touches a record — the screen's whole job is to show what was
 * recorded, and PLAN 385 makes that record something appended by the
 * transaction that caused it and never edited afterwards.
 *
 * The reading is a stream, so a task finished in the pool while this section is
 * open appears here without anything having to remember to reload. The filters
 * live beside it rather than inside it: a fresh reading replaces the lines and
 * leaves what the user asked to see exactly as it was.
 */
class HistoryController(
    private val history: HistorySource,
    private val clock: kotlin.time.Clock = kotlin.time.Clock.System,
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
        } catch (_: Exception) {
            state = state.copy(content = HistoryContentState.Failed)
        }
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
