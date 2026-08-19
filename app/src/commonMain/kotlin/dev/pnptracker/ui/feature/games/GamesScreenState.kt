package dev.pnptracker.ui.feature.games

import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.games.ItemSummary
import dev.pnptracker.domain.model.EntityId

/**
 * Where the games list is.
 *
 * "Nothing yet" is its own case rather than an empty content list, because an
 * empty library needs to invite the user to start one, and a full one does not.
 */
sealed interface GamesListState {
    data object Loading : GamesListState

    /** No games at all; the screen offers to create the first. */
    data object Empty : GamesListState

    data class Content(
        val games: List<GameSummary>,
    ) : GamesListState
}

/** Where one game's detail is. */
sealed interface GameDetailState {
    data object Loading : GameDetailState

    /** The game is there but has no items yet. */
    data class Empty(
        val game: GameSummary,
    ) : GameDetailState

    data class Content(
        val game: GameSummary,
        val items: List<ItemSummary>,
    ) : GameDetailState

    /** The game was deleted, or was never there. */
    data object Unavailable : GameDetailState
}

/** A name the user is typing, before anything is written. */
data class NameComposer(
    val name: String,
) {
    val canSave: Boolean get() = name.isNotBlank()
}

/** What the whole section is showing, and what did not save. */
data class GamesScreenState(
    val list: GamesListState = GamesListState.Loading,
    val openGameId: EntityId? = null,
    val detail: GameDetailState = GameDetailState.Loading,
    val gameComposer: NameComposer? = null,
    val itemComposer: NameComposer? = null,
    val failure: GameSetupFailure? = null,
)
