package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode

/**
 * One task of one game, together with the cell it is written in.
 *
 * A task carries no pointer back to its cell, so a screen that wants to show
 * where a task sits has to read the two together. Doing it in one query keeps
 * them from ever disagreeing, which two separate streams could.
 *
 * This is a read shape and nothing more: it holds only what a row shows, so it
 * cannot be mistaken for the task itself or written back anywhere.
 */
data class GameTaskRow(
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    @ColumnInfo(name = "cell_id")
    val cellId: EntityId,
    @ColumnInfo(name = "column_type")
    val columnType: CellColumnType,
    @ColumnInfo(name = "pool_type")
    val poolType: PoolType,
    @ColumnInfo(name = "tracking_mode")
    val trackingMode: TrackingMode,
    @ColumnInfo(name = "task_name")
    val taskName: String,
    @ColumnInfo(name = "required_quantity")
    val requiredQuantity: Int?,
    @ColumnInfo(name = "notes")
    val notes: String?,
    @ColumnInfo(name = "source_raw_import_block_id")
    val sourceRawImportBlockId: EntityId?,
)
