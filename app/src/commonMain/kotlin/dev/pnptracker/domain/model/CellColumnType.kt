package dev.pnptracker.domain.model

/**
 * A column of the game table, which is to say one cell of a game row.
 *
 * Four of these carry production work and map one to one onto a [PoolType]; the
 * fifth is the notes column, which carries free text and nothing else. The four
 * share their names with the pools they feed so the two can be read together
 * without a translation table in between.
 */
enum class CellColumnType {
    THREE_D,
    CARD,
    BOARD,
    SPECIAL,

    /** Free text. PLAN 5.4 keeps tasks out of it entirely. */
    NOTES,
    ;

    /** The pool a task in this column belongs to, or null for [NOTES]. */
    val poolType: PoolType?
        get() =
            when (this) {
                THREE_D -> PoolType.THREE_D
                CARD -> PoolType.CARD
                BOARD -> PoolType.BOARD
                SPECIAL -> PoolType.SPECIAL
                NOTES -> null
            }

    /** True when a task may live in this column at all. */
    val holdsTasks: Boolean get() = poolType != null

    companion object {
        /** The column a task of [poolType] belongs in. */
        fun of(poolType: PoolType): CellColumnType =
            when (poolType) {
                PoolType.THREE_D -> THREE_D
                PoolType.CARD -> CARD
                PoolType.BOARD -> BOARD
                PoolType.SPECIAL -> SPECIAL
            }
    }
}
