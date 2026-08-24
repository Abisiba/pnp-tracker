package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId

/**
 * A cell that is still there, and the column it belongs to.
 *
 * Answering "can a task go here, and what kind" for a whole batch of targets at
 * once. A cell that has gone, or whose game has been deleted, is absent rather
 * than marked, so the presence of a row is the whole of the availability answer.
 *
 * This is a read shape and nothing more: it holds only what the check needs, so
 * it cannot be mistaken for the cell itself or written back anywhere.
 */
data class CellColumnRow(
    @ColumnInfo(name = "cell_id")
    val cellId: EntityId,
    @ColumnInfo(name = "column_type")
    val columnType: CellColumnType,
)
