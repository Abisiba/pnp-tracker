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
 * Completion is stored, not worked out on the way to the screen. PLAN 6.4 is
 * explicit that [isCompleted] is the real state the user and bulk completion
 * write, and that nothing may let it contradict the counters — so the same
 * transaction that changes one changes the other.
 *
 * [currentMissingQuantity] is a cached total: it always equals what has been
 * reported failed less what has been made good, over this task's progress
 * events. It is kept as a column because every pool query reads it and no query
 * should have to sum a history to draw a row, and every write path that touches
 * it writes the matching event in the same transaction.
 *
 * The failure total PLAN 6.2 also names is deliberately **not** here. It is a
 * sum over the events and nothing else, so there is no second copy of it to fall
 * out of step.
 *
 * There is no archive flag, because the product archives nothing.
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
        Index(value = ["is_completed"]),
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
    @ColumnInfo(name = "is_completed", defaultValue = "0")
    val isCompleted: Boolean = false,
    @ColumnInfo(name = "completed_at")
    val completedAt: Instant? = null,
    /** Whether the one print run PLAN 6.2 counts a 3D task by has been made. */
    @ColumnInfo(name = "primary_batch_completed", defaultValue = "0")
    val primaryBatchCompleted: Boolean = false,
    /** How much still has to be made again; never negative, never above the total. */
    @ColumnInfo(name = "current_missing_quantity", defaultValue = "0")
    val currentMissingQuantity: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant? = null,
    /** The imported cell this task was built from, or null when it was typed by hand. */
    @ColumnInfo(name = "source_raw_import_block_id")
    val sourceRawImportBlockId: EntityId? = null,
    /** PLAN 10: came from the `Eksik` column. A note about the work, not a shortage. */
    @ColumnInfo(name = "is_missing", defaultValue = "0")
    val isMissing: Boolean = false,
    /** PLAN 10: came from the `Ödünç Parçalar` column. */
    @ColumnInfo(name = "is_borrowed", defaultValue = "0")
    val isBorrowed: Boolean = false,
    /** PLAN 11.7: something the work needs is still unknown. */
    @ColumnInfo(name = "needs_info", defaultValue = "0")
    val needsInfo: Boolean = false,
    /** PLAN 10: the pool was the user's own decision rather than the column's. */
    @ColumnInfo(name = "needs_classification", defaultValue = "0")
    val needsClassification: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "A task needs a name." }
        require(requiredQuantity == null || requiredQuantity > 0) {
            "A required quantity is either unknown (null) or greater than zero, was: $requiredQuantity"
        }
        requireAllowedTrackingMode(poolType, trackingMode)
        require(currentMissingQuantity >= 0) {
            "What is still owed cannot be less than nothing, was: $currentMissingQuantity"
        }
        require(requiredQuantity == null || currentMissingQuantity <= requiredQuantity) {
            "More is owed ($currentMissingQuantity) than the task needs in total ($requiredQuantity)."
        }
        // PLAN 6.4: the two say the same thing or the row is not a valid one.
        require(isCompleted == (completedAt != null)) {
            "A task is finished exactly when it has a time it was finished at, was: $isCompleted / $completedAt"
        }
        // PLAN 10 gives `Eksik` and `Ödünç Parçalar` a column each, and a cell
        // is in one of them. The same rule already stands on the column
        // suggestion the flags come from and on the draft that carries them
        // here, so a task holding both could only be a defect.
        require(!(isMissing && isBorrowed)) { "A task is either missing or borrowed, never both." }
    }
}
