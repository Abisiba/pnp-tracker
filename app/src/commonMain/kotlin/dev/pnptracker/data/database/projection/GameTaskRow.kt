package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode

/**
 * One task of one game, together with the name of the item it hangs from.
 *
 * The game detail screen groups tasks by pool rather than by item, so every row
 * has to carry its item's name. Reading it in the same query as the task keeps
 * the two from ever disagreeing, which two separate streams could.
 *
 * This is a read shape and nothing more: it holds only what a row shows, so it
 * cannot be mistaken for the task itself or written back anywhere.
 */
data class GameTaskRow(
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    @ColumnInfo(name = "item_id")
    val itemId: EntityId,
    @ColumnInfo(name = "item_name")
    val itemName: String,
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
