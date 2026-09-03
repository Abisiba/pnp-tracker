package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType

/**
 * One task as the export reads it, with its game and its cell already joined.
 *
 * The join is deliberately outer on everything to the left of the task. A task
 * that is in no cell, or in a cell of a game that has been deleted, is a real
 * possibility that has to be told apart from a task that simply is not there —
 * one is a broken record and the other is data the user chose to hide — and an
 * inner join would make both of them invisible in the same way.
 */
data class ExportTaskRow(
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    @ColumnInfo(name = "task_name")
    val taskName: String,
    @ColumnInfo(name = "pool_type")
    val poolType: PoolType,
    @ColumnInfo(name = "required_quantity")
    val requiredQuantity: Int?,
    @ColumnInfo(name = "notes")
    val notes: String?,
    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean,
    @ColumnInfo(name = "needs_info")
    val needsInfo: Boolean,
    @ColumnInfo(name = "game_id")
    val gameId: EntityId?,
    @ColumnInfo(name = "game_name")
    val gameName: String?,
    /** Non-null for a game the user has deleted; such tasks are not written out. */
    @ColumnInfo(name = "game_is_deleted")
    val gameIsDeleted: Boolean,
    @ColumnInfo(name = "column_type")
    val columnType: CellColumnType?,
    @ColumnInfo(name = "segment_id")
    val segmentId: EntityId?,
)

/** One colour of one task, in the slot the user gave it. */
data class ExportColorRow(
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    @ColumnInfo(name = "color_id")
    val colorId: EntityId,
    @ColumnInfo(name = "slot_index")
    val slotIndex: Int,
    @ColumnInfo(name = "canonical_name")
    val canonicalName: String,
)
