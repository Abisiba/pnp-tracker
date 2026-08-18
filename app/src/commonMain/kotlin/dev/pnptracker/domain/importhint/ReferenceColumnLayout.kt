package dev.pnptracker.domain.importhint

import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType

/**
 * The seven columns of the reference sheet, by position.
 *
 * The layout is fixed, so the position of a cell is enough to say what kind of
 * work it describes. Nothing here reads the text of a cell: a column says where
 * a value came from, and the text says what it is.
 *
 * Three of the seven suggest no pool. A game name is not work at all, and
 * missing and borrowed parts are notes about work whose pool the user still has
 * to choose — they are not a fifth and sixth pool, and must never be swept into
 * the special pool to make them fit.
 */
object ReferenceColumnLayout {
    const val GAME_COLUMN_INDEX = 0
    const val LAST_COLUMN_INDEX = 6

    /** Null for any column outside the reference layout: a guess would be worse. */
    fun suggestionFor(columnIndex: Int): ReferenceColumnSuggestion? =
        when (columnIndex) {
            0 -> suggestion(columnIndex, SourceColumnType.GAME, null)
            1 -> suggestion(columnIndex, SourceColumnType.THREE_D, PoolType.THREE_D)
            2 -> suggestion(columnIndex, SourceColumnType.CARD, PoolType.CARD)
            3 -> suggestion(columnIndex, SourceColumnType.BOARD, PoolType.BOARD)
            4 -> suggestion(columnIndex, SourceColumnType.SPECIAL, PoolType.SPECIAL)
            5 ->
                suggestion(
                    columnIndex,
                    SourceColumnType.MISSING,
                    null,
                    isMissing = true,
                    needsClassification = true,
                )

            6 ->
                suggestion(
                    columnIndex,
                    SourceColumnType.BORROWED,
                    null,
                    isBorrowed = true,
                    needsClassification = true,
                )

            else -> null
        }

    private fun suggestion(
        columnIndex: Int,
        sourceColumnType: SourceColumnType,
        suggestedPoolType: PoolType?,
        isMissing: Boolean = false,
        isBorrowed: Boolean = false,
        needsClassification: Boolean = false,
    ) = ReferenceColumnSuggestion(
        columnIndex = columnIndex,
        sourceColumnType = sourceColumnType,
        suggestedPoolType = suggestedPoolType,
        isMissing = isMissing,
        isBorrowed = isBorrowed,
        needsClassification = needsClassification,
    )
}
