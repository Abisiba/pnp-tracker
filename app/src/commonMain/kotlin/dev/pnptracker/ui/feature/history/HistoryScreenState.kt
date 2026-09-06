package dev.pnptracker.ui.feature.history

import dev.pnptracker.domain.history.HistoryEntry
import dev.pnptracker.domain.history.HistoryFilter
import dev.pnptracker.domain.history.HistoryLog

/** Where the history is. */
sealed interface HistoryContentState {
    data object Loading : HistoryContentState

    /**
     * The history, and the part of it the filters admit.
     *
     * Both, because the screen has to tell two empty screens apart: a history
     * with nothing in it is a new database, and a history whose filters admit
     * nothing is a filter to loosen. A screen holding only the shown lines could
     * not say which it was looking at.
     */
    data class Content(
        val log: HistoryLog,
        val shown: List<HistoryEntry>,
    ) : HistoryContentState {
        /** True when there is a history but nothing in it answers the filters. */
        val hasHiddenEntries: Boolean get() = shown.isEmpty() && !log.isEmpty
    }

    /**
     * The history could not be read.
     *
     * Kept apart from an empty one for the same reason the pools keep them
     * apart: one is news and the other is a fault. What broke is never shown —
     * PLAN 17 keeps the developer's words off the screen.
     */
    data object Failed : HistoryContentState
}

/** Whether the panel of filter choices is open over the history. */
enum class HistoryFilterSurface {
    CLOSED,
    OPEN,
}

/**
 * What the history screen is showing and what is open over it.
 *
 * The filter is held apart from the reading, so a line arriving from the
 * database is not a reason to forget what somebody asked to see. Nothing here
 * can write: the screen offers no action that changes a record, because a
 * history that could be edited would not be a history (PLAN 385).
 */
data class HistoryScreenState(
    val content: HistoryContentState = HistoryContentState.Loading,
    val filter: HistoryFilter = HistoryFilter.NONE,
    val filterSurface: HistoryFilterSurface = HistoryFilterSurface.CLOSED,
    /** Bumped whenever the keyboard has to be handed back to the filter button. */
    val focusRecall: Int = 0,
) {
    /** The lines on screen right now, or none while it is loading or broken. */
    val shown: List<HistoryEntry>
        get() = (content as? HistoryContentState.Content)?.shown.orEmpty()

    /** How many choices to show on the filter button. */
    val chosenFilterCount: Int get() = filter.chosenCount
}
