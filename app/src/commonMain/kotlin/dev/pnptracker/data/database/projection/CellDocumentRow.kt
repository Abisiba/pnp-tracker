package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.EntityId

/**
 * One piece of a cell's document, read as the words it actually contributes.
 *
 * A cell document is its pieces laid end to end (PLAN 5.5), and the two kinds of
 * piece keep their words in different places: a text piece in its own column, a
 * task piece in the name of the task it stands for. This resolves that
 * difference in the query, so a caller that only wants to know what the cell
 * *reads* does not have to join tasks itself or hold two lists side by side.
 *
 * Used by the import to see where a cell's writing ends before it adds to it,
 * and to read the finished document back afterwards.
 */
data class CellDocumentRow(
    @ColumnInfo(name = "cell_id")
    val cellId: EntityId,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "text")
    val text: String,
)
