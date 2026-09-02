package dev.pnptracker.domain.search

import dev.pnptracker.domain.games.CellSegmentPreview
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType

/**
 * What the game table is being asked to show, beyond the three global views.
 *
 * Kept apart from [dev.pnptracker.domain.games.GameTableView] on purpose. The
 * view is a statement about **games** — PLAN 12.4 gives the user `Devam Eden`,
 * `Tamamlanan` and `Tümü`, and finishing a game is their own decision about the
 * game (PLAN 5.3). What is here is a statement about the **work** inside those
 * games. Folding the two together would make finishing a game look like
 * finishing its tasks, which is exactly the thing PLAN keeps separate.
 *
 * This decides which rows are listed and nothing else. A row that is shown is
 * shown whole: every cell, every piece, and the document exactly as the user
 * wrote it. Hiding the pieces that did not match would leave a cell reading
 * something nobody ever typed — PLAN 5.5 makes the document the pieces in order,
 * and a filter is not a licence to take some of them out.
 */
data class GameTableFilter(
    val query: SearchQuery = SearchQuery.NONE,
    /** Pools a row must hold work in; empty means any (PLAN 13). */
    val poolTypes: Set<PoolType> = emptySet(),
    /** Colours a row's work may be made in; empty means any. */
    val colorIds: Set<EntityId> = emptySet(),
    /** Whether rows holding work with no colour are wanted. */
    val awaitingColor: Boolean = false,
) {
    val isNarrowed: Boolean get() = this != NONE

    /** How many choices the user has made, for the count on the filter button. */
    val chosenCount: Int get() = poolTypes.size + colorIds.size + if (awaitingColor) 1 else 0

    /**
     * Whether one game row is one of the ones being asked for.
     *
     * The search looks at the game's own name and at the names of the tasks
     * written in it, and at nothing else. Plain text in a cell is not searched:
     * PLAN 13 asks for names, and a cell's prose is where somebody keeps
     * measurements and reminders — matching on it would put a row in the results
     * for a word that names nothing.
     */
    fun matches(row: GameTableRow): Boolean = matchesQuery(row) && matchesPool(row) && matchesColor(row)

    private fun matchesQuery(row: GameTableRow): Boolean {
        if (query.isEmpty) return true
        if (query.matches(row.gameName)) return true
        return row.tasksOf().any { query.matches(it.text) }
    }

    private fun matchesPool(row: GameTableRow): Boolean = poolTypes.isEmpty() || row.tasksOf().any { it.poolType in poolTypes }

    private fun matchesColor(row: GameTableRow): Boolean {
        if (colorIds.isEmpty() && !awaitingColor) return true
        return row.tasksOf().any { task ->
            (awaitingColor && task.colors.isEmpty()) || task.colors.any { it.colorId in colorIds }
        }
    }

    /**
     * The pieces of this row that stand for a task.
     *
     * Only these carry a name, a pool and colours. A piece of plain text has no
     * colours either, so counting it would make every row with any writing in it
     * answer to `Renk seçilecek`.
     */
    private fun GameTableRow.tasksOf(): List<CellSegmentPreview> = cells.flatMap { it.segments }.filter { it.isTask }

    companion object {
        /** The ordinary table: every row the open view holds. */
        val NONE = GameTableFilter()
    }
}
