package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.rules.requireAllowedTrackingMode
import kotlin.time.Instant

/**
 * A task the user is building out of a raw cell, before anything is written to
 * the production tables. One raw cell can produce as many drafts as the user
 * wants, and a draft can also be typed from scratch.
 *
 * [selectionStartIndex] and [selectionEndIndex] point into the raw text the draft
 * came from. They are UTF-16 `String` indexes — the same indexes Kotlin and
 * Compose text selection use — with the start included and the end excluded. A
 * draft the user typed by hand has neither.
 *
 * [suggestedPoolType] is what the source column implies; [selectedPoolType] is
 * what the user chose. They are kept apart so a suggestion can never pass for a
 * decision.
 *
 * `isMissing` and `isBorrowed` are flags, not pools: a cell from the missing or
 * borrowed column keeps its flag and still has to be placed in a real pool.
 *
 * [materializedTaskId] stays null until a later phase turns the draft into a real
 * task; it then makes confirming an import repeatable without producing the task
 * twice, and keeps the trail from a task back to the cell it came from.
 */
@Entity(
    tableName = "draft_tasks",
    foreignKeys = [
        ForeignKey(
            entity = RawImportBlockEntity::class,
            parentColumns = ["id"],
            childColumns = ["raw_import_block_id"],
            // Drafts of a discarded draft batch go with their raw cell.
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = GameCellEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_cell_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["materialized_task_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["raw_import_block_id"]),
        Index(value = ["target_cell_id"]),
        Index(value = ["materialized_task_id"], unique = true),
    ],
)
data class DraftTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: EntityId,
    @ColumnInfo(name = "raw_import_block_id")
    val rawImportBlockId: EntityId,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "suggested_pool_type")
    val suggestedPoolType: PoolType? = null,
    @ColumnInfo(name = "selected_pool_type")
    val selectedPoolType: PoolType? = null,
    @ColumnInfo(name = "selected_tracking_mode")
    val selectedTrackingMode: TrackingMode? = null,
    @ColumnInfo(name = "required_quantity")
    val requiredQuantity: Int? = null,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
    @ColumnInfo(name = "target_cell_id")
    val targetCellId: EntityId? = null,
    @ColumnInfo(name = "selection_start_index")
    val selectionStartIndex: Int? = null,
    @ColumnInfo(name = "selection_end_index")
    val selectionEndIndex: Int? = null,
    @ColumnInfo(name = "completion_hint", defaultValue = "'NONE'")
    val completionHint: HintDecision = HintDecision.NONE,
    @ColumnInfo(name = "is_missing", defaultValue = "0")
    val isMissing: Boolean = false,
    @ColumnInfo(name = "is_borrowed", defaultValue = "0")
    val isBorrowed: Boolean = false,
    @ColumnInfo(name = "needs_info", defaultValue = "0")
    val needsInfo: Boolean = false,
    @ColumnInfo(name = "needs_classification", defaultValue = "0")
    val needsClassification: Boolean = false,
    @ColumnInfo(name = "materialized_task_id")
    val materializedTaskId: EntityId? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
) {
    init {
        require(name.isNotBlank()) { "A draft task needs a name." }
        require(requiredQuantity == null || requiredQuantity > 0) {
            "A required quantity is either unknown (null) or greater than zero, was: $requiredQuantity"
        }
        // The same rule the column suggestion these flags come from already
        // holds: a cell is in the missing column or the borrowed one, never in
        // both, so a draft claiming both could not have come from a file.
        require(!(isMissing && isBorrowed)) { "A draft task is either missing or borrowed, never both." }
        require((selectionStartIndex == null) == (selectionEndIndex == null)) {
            "A text selection has both ends or neither, was: $selectionStartIndex..$selectionEndIndex"
        }
        if (selectionStartIndex != null && selectionEndIndex != null) {
            require(selectionStartIndex >= 0) {
                "A text selection cannot start before the text: $selectionStartIndex"
            }
            require(selectionEndIndex > selectionStartIndex) {
                "A text selection cannot be empty or reversed: $selectionStartIndex..$selectionEndIndex"
            }
        }
        if (selectedPoolType != null && selectedTrackingMode != null) {
            requireAllowedTrackingMode(selectedPoolType, selectedTrackingMode)
        }
    }
}
