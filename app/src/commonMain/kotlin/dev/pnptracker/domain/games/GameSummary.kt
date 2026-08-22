package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId

/** A game as the table lists it. */
data class GameSummary(
    val id: EntityId,
    val name: String,
    val isManuallyCompleted: Boolean = false,
)

/**
 * One cell of a game row, named by the column it is in.
 *
 * The cell's contents are its segments, which are read separately; this is only
 * enough to say which cells a game has opened.
 */
data class CellSummary(
    val id: EntityId,
    val gameId: EntityId,
    val columnType: CellColumnType,
)
