package dev.pnptracker.domain.model

/**
 * Which column of the source sheet a raw cell came from.
 *
 * [MISSING] and [BORROWED] are not production pools: they are the two columns
 * whose cells still have to be classified by the user before the work they
 * describe can join a pool.
 */
enum class SourceColumnType {
    GAME,
    THREE_D,
    CARD,
    BOARD,
    SPECIAL,
    MISSING,
    BORROWED,
}
