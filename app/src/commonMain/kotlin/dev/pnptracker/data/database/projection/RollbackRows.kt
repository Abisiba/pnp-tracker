package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.SegmentKind

/**
 * One cell an import wrote into, with what it recorded and where it lives.
 *
 * Read from `import_batch_cells` rather than from the drafts, because the record
 * is what a rollback stands on (PLAN 11.4.4): a cell with no row here is a cell
 * nobody knows the former text of, and that has to be visible rather than
 * silently absent.
 *
 * The game's name comes along so a blocked rollback can say which cell is in the
 * way in the words the user reads the table by.
 */
data class RollbackCellRow(
    @ColumnInfo(name = "cell_id")
    val cellId: EntityId,
    @ColumnInfo(name = "document_before")
    val documentBefore: String,
    @ColumnInfo(name = "game_id")
    val gameId: EntityId?,
    @ColumnInfo(name = "game_name")
    val gameName: String?,
    @ColumnInfo(name = "column_type")
    val columnType: CellColumnType?,
)

/**
 * One piece of one of those cells, with the words it contributes resolved.
 *
 * [documentText] is the piece's own text, or the name of the task it stands for
 * (PLAN 5.5) — the same resolution the import used when it measured the cell, so
 * the two readings cannot disagree. A tombstoned task still has a name, so a
 * piece naming one is read rather than vanishing.
 */
data class RollbackSegmentRow(
    @ColumnInfo(name = "cell_id")
    val cellId: EntityId,
    @ColumnInfo(name = "segment_id")
    val segmentId: EntityId,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "kind")
    val kind: SegmentKind,
    @ColumnInfo(name = "text")
    val text: String?,
    @ColumnInfo(name = "task_id")
    val taskId: EntityId?,
    @ColumnInfo(name = "document_text")
    val documentText: String,
)

/** One cell and the game it belongs to, for fixing the link before it is broken. */
data class CellGameRow(
    @ColumnInfo(name = "cell_id")
    val cellId: EntityId,
    @ColumnInfo(name = "game_id")
    val gameId: EntityId,
)
