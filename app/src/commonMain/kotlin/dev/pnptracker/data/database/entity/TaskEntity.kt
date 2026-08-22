package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.rules.requireAllowedTrackingMode
import kotlin.time.Instant

/**
 * A unit of production work.
 *
 * A task carries no pointer to where it is written. The link runs the other way:
 * exactly one [CellSegmentEntity] names the task, and the cell that segment
 * belongs to says which game and which column the task is in. Keeping it one
 * directional means a task cannot claim to be in a cell that does not have it.
 *
 * Completion is not stored here: it will be derived from the counters and events
 * the v5 schema adds. There is no archive flag either, because the product
 * archives nothing.
 */
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = RawImportBlockEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_raw_import_block_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["pool_type"]),
        Index(value = ["deleted_at"]),
        Index(value = ["source_raw_import_block_id"]),
    ],
)
data class TaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: EntityId,
    @ColumnInfo(name = "pool_type")
    val poolType: PoolType,
    @ColumnInfo(name = "tracking_mode")
    val trackingMode: TrackingMode,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "required_quantity")
    val requiredQuantity: Int? = null,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant? = null,
    /** The imported cell this task was built from, or null when it was typed by hand. */
    @ColumnInfo(name = "source_raw_import_block_id")
    val sourceRawImportBlockId: EntityId? = null,
) {
    init {
        require(name.isNotBlank()) { "A task needs a name." }
        require(requiredQuantity == null || requiredQuantity > 0) {
            "A required quantity is either unknown (null) or greater than zero, was: $requiredQuantity"
        }
        requireAllowedTrackingMode(poolType, trackingMode)
    }
}
