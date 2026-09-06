package dev.pnptracker.domain.history

import dev.pnptracker.domain.model.EntityId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * How far back the history is being asked to go.
 *
 * A window measured from now rather than a calendar period, and that is a
 * decision about honesty: `Son 7 gün` means the last seven days and not
 * "since Monday", so what it shows does not change meaning depending on which
 * day of the week the user opens it. The boundary is inclusive at the far end —
 * an event exactly seven days old is within the last seven days.
 */
enum class HistoryPeriod(
    /** How far back this reaches, or null for the whole history. */
    val window: Duration?,
) {
    ALL(null),
    LAST_WEEK(7.days),
    LAST_MONTH(30.days),
    ;

    /** The earliest moment this period admits, or null when it admits everything. */
    fun startingFrom(now: Instant): Instant? = window?.let { now - it }

    /** Whether [moment] falls inside this period, read at [now]. */
    fun admits(
        moment: Instant,
        now: Instant,
    ): Boolean = startingFrom(now)?.let { moment >= it } ?: true
}

/**
 * What the history screen is being asked to show.
 *
 * Two narrowings, and they narrow together: a line has to pass both. PLAN 13
 * gives the application filters that are moved between rather than settled once,
 * so nothing here is stored — it lasts as long as the window does and costs no
 * column and no file.
 *
 * There is deliberately no text search. PLAN 13's searches are over names of
 * games and tasks, and both of those are already answerable here by choosing
 * the game; a second box that searched the *sentences* would be searching text
 * this screen generates rather than anything the user wrote.
 */
data class HistoryFilter(
    /** The one game being asked about, or null for every game. */
    val gameId: EntityId? = null,
    val period: HistoryPeriod = HistoryPeriod.ALL,
) {
    /** True when this is asking for anything other than the whole history. */
    val isNarrowed: Boolean get() = this != NONE

    /** How many choices the user has made, for the count on the filter button. */
    val chosenCount: Int get() = (if (gameId != null) 1 else 0) + (if (period != HistoryPeriod.ALL) 1 else 0)

    companion object {
        /** The whole history, in the order it happened. */
        val NONE = HistoryFilter()
    }
}

/**
 * The lines of [entries] that answer [filter], read at [now].
 *
 * Order is left exactly as it arrived. The reading is already a total order —
 * moment first, identity second — and filtering removes lines rather than
 * rearranging the ones that stay, so a filter can never change what is above
 * what.
 *
 * A line with no game at all is kept only while no particular game is being
 * asked about. It cannot answer "was this Harmonies?" either way, and answering
 * yes would put a line under a game it may have nothing to do with.
 */
fun filterHistory(
    entries: List<HistoryEntry>,
    filter: HistoryFilter,
    now: Instant,
): List<HistoryEntry> =
    entries.filter { entry ->
        (filter.gameId == null || entry.gameId == filter.gameId) &&
            filter.period.admits(entry.occurredAt, now)
    }
