package dev.pnptracker.domain.history

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.search.searchFold

/** One game the history has something to say about, for the filter to offer. */
data class HistoryGame(
    val id: EntityId,
    val name: String,
)

/**
 * The whole history as one reading, newest first.
 *
 * The games it names are worked out from the lines themselves rather than read
 * separately. A game nothing has ever happened in is not offered as a filter,
 * which is the right answer twice over: it costs no second query, and choosing
 * it could only ever produce an empty screen.
 */
data class HistoryLog(
    val entries: List<HistoryEntry>,
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Every game named by a line, once each, in Turkish alphabetical order.
     *
     * Ordered by name because that is how the user looks for one, folded the
     * Turkish way so `İzmir` and `ısı` sort where a Turkish reader expects them
     * rather than where the root locale would put them. Identity settles two
     * games that happen to be called the same thing, so the list is stable
     * rather than reordering itself when the reading is refreshed.
     */
    val games: List<HistoryGame> =
        entries
            .mapNotNull { entry ->
                val id = entry.gameId ?: return@mapNotNull null
                val name = entry.gameName ?: return@mapNotNull null
                HistoryGame(id, name)
            }.distinct()
            .sortedWith(compareBy({ searchFold(it.name) }, { it.id.toString() }))

    companion object {
        val EMPTY = HistoryLog(emptyList())
    }
}
