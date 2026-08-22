package dev.pnptracker.domain.colors

/**
 * What can go wrong while adding a colour, in words a screen can show.
 *
 * A blank name and a value that is not `#RRGGBB` are missing on purpose: the form
 * refuses them before anything is attempted, so they are states of the form
 * rather than outcomes of one.
 */
enum class ColorSetupFailure {
    /** The storage refused the change, so nothing was written. */
    COULD_NOT_SAVE,

    /** Another colour already carries this name. */
    NAME_ALREADY_USED,

    /** Another colour is already known by this name, as one of its aliases. */
    NAME_IS_ANOTHER_COLORS_ALIAS,
}

/**
 * Thrown when a colour the user typed did not reach the catalogue.
 *
 * Deliberately narrow, the way the game and task setup exceptions are: only a
 * recognised storage refusal or a name that is already taken becomes one of
 * these. A broken invariant travels out untouched rather than being shown to the
 * user as a saving problem they could act on.
 */
class ColorSetupException(
    val failure: ColorSetupFailure,
    cause: Throwable? = null,
) : Exception("The colour could not be saved: $failure", cause)
