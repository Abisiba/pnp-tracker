package dev.pnptracker.domain.games

/**
 * What can go wrong while setting up games and cells, in words a screen can show.
 *
 * A blank name is not here: it is refused before anything is written, so it is a
 * state of the form rather than the outcome of an attempt.
 */
enum class GameSetupFailure {
    /** The storage refused the change, so nothing was written. */
    COULD_NOT_SAVE,

    /** The game a cell was going to be opened in is gone, or has been deleted. */
    GAME_NOT_AVAILABLE,
}

/**
 * Thrown when a setup change did not happen.
 *
 * Deliberately narrow: only a recognised storage refusal or a missing parent game
 * becomes one of these. A broken invariant travels out untouched rather than being
 * presented to the user as something they can fix.
 */
class GameSetupException(
    val failure: GameSetupFailure,
    cause: Throwable? = null,
) : Exception("The game setup change could not be saved: $failure", cause)
