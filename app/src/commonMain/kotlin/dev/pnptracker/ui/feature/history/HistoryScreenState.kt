package dev.pnptracker.ui.feature.history

import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.history.HistoryChange
import dev.pnptracker.domain.history.HistoryEntry
import dev.pnptracker.domain.history.HistoryFilter
import dev.pnptracker.domain.history.HistoryLog
import dev.pnptracker.domain.model.EntityId

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

        /**
         * The `Oyun silindi` lines that can still be taken back.
         *
         * One per deleted game at most, and only while the game is still
         * deleted: its newest deletion, which is the one in force. An older
         * deletion was already taken back once, and offering it again would
         * offer the same thing twice.
         *
         * Only deletion lines are compared. A deletion, a restore and another
         * deletion made within one millisecond share a moment, and lines that
         * share a moment are ordered by identity, which says nothing about which
         * came first — so "the newest of the deletions and restores" could be a
         * restore and offer nothing. Whether the game is deleted now is read
         * from the game itself instead.
         */
        val restorableLines: Set<EntityId> by lazy {
            log.entries
                .filter { it.change == HistoryChange.GameDeleted && it.gameIsDeleted && it.gameId != null }
                .distinctBy { it.gameId }
                .map { it.id }
                .toSet()
        }
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
 * What bringing a deleted game back came to, said under the toolbar.
 *
 * [Restored.namesakes] is how many other games in the table share the name, so
 * the screen can say so rather than leave two identical rows to be discovered.
 */
sealed interface GameRestoreNotice {
    data class Restored(
        val gameName: String,
        val namesakes: Int,
    ) : GameRestoreNotice

    data class Failed(
        val failure: GameSetupFailure,
    ) : GameRestoreNotice
}

/**
 * What the history screen is showing and what is open over it.
 *
 * The filter is held apart from the reading, so a line arriving from the
 * database is not a reason to forget what somebody asked to see. The lines
 * themselves cannot be edited — a history that could be would not be a history
 * (PLAN 5.12). The one thing done from here is bringing a deleted game back,
 * which is not an edit of a line but a new act with a line of its own.
 */
data class HistoryScreenState(
    val content: HistoryContentState = HistoryContentState.Loading,
    val filter: HistoryFilter = HistoryFilter.NONE,
    val filterSurface: HistoryFilterSurface = HistoryFilterSurface.CLOSED,
    /** Bumped whenever the keyboard has to be handed back to the filter button. */
    val focusRecall: Int = 0,
    /** The game on its way back, while the change is in flight. */
    val restoring: EntityId? = null,
    val restoreNotice: GameRestoreNotice? = null,
) {
    /** The lines on screen right now, or none while it is loading or broken. */
    val shown: List<HistoryEntry>
        get() = (content as? HistoryContentState.Content)?.shown.orEmpty()

    /** How many choices to show on the filter button. */
    val chosenFilterCount: Int get() = filter.chosenCount

    /** True when [entry] is a deletion that `Geri al` can still take back. */
    fun canRestore(entry: HistoryEntry): Boolean = (content as? HistoryContentState.Content)?.restorableLines?.contains(entry.id) == true
}
