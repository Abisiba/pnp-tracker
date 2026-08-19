package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.EntityId

/**
 * A game as the list shows it.
 *
 * [isManuallyCompleted] is the user's own statement and nothing else: it is not
 * worked out from items or tasks, and finishing a game says nothing about what is
 * below it.
 */
data class GameSummary(
    val id: EntityId,
    val name: String,
    val isManuallyCompleted: Boolean = false,
)

/** An item as the game detail screen shows it. */
data class ItemSummary(
    val id: EntityId,
    val gameId: EntityId,
    val name: String,
)
