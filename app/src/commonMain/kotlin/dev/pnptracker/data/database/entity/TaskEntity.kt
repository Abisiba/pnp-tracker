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
 * A unit of production work below an item.
 *
 * Completion is not stored here as a flag: it will be derived from the counters
 * and events that later phases add. Archiving takes a task out of the active
 * pools without deleting it.
 */
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["item_id"]),
        Index(value = ["pool_type"]),
        Index(value = ["deleted_at"]),
    ],
)
data class TaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: EntityId,
    @ColumnInfo(name = "item_id")
    val itemId: EntityId,
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
    @ColumnInfo(name = "is_archived", defaultValue = "0")
    val isArchived: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant? = null,
) {
    init {
        require(name.isNotBlank()) { "A task needs a name." }
        require(requiredQuantity == null || requiredQuantity > 0) {
            "A required quantity is either unknown (null) or greater than zero, was: $requiredQuantity"
        }
        requireAllowedTrackingMode(poolType, trackingMode)
    }
}
