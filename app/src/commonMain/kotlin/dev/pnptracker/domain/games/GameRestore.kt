package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.EntityId

/**
 * What happened to a deleted game asked to come back (PLAN 12.15).
 *
 * Game names are not unique (PLAN 12.3), so coming back never renames anything
 * and is never refused over a name. What it does is say so: [Restored.namesakes]
 * is how many other games now in the table are called the same — compared the
 * way the `A–Z` layout compares names, so the two are the ones drawn side by side
 * there — and the screen tells the user, who can rename either if they want to
 * tell them apart.
 */
sealed interface GameRestoreOutcome {
    /** The game is back, under [name]. */
    data class Restored(
        val name: String,
        val namesakes: Int,
    ) : GameRestoreOutcome

    /** It was not deleted any more — brought back already — so nothing was written. */
    data object AlreadyThere : GameRestoreOutcome
}

/** Brings a deleted game back. The history is the only place that asks. */
interface GameRestoring {
    /**
     * Lifts [gameId]'s tombstone, keeping its identity, cells and tasks.
     *
     * @throws GameSetupException if the game is not there at all, or the change
     *   did not reach the database.
     */
    suspend fun restoreGame(gameId: EntityId): GameRestoreOutcome

    companion object {
        /** For a history shown where nothing can be brought back. */
        val Unavailable: GameRestoring =
            object : GameRestoring {
                override suspend fun restoreGame(gameId: EntityId): GameRestoreOutcome =
                    throw GameSetupException(GameSetupFailure.GAME_NOT_AVAILABLE)
            }
    }
}

/** True when [first] and [second] are the same name as the `A–Z` layout reads them. */
fun sameGameName(
    first: String,
    second: String,
): Boolean = compareTurkish(first, second) == 0
