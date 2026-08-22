package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.pnptracker.domain.model.EntityId
import kotlin.time.Instant

/**
 * A game row of the table.
 *
 * Completion is the user's own decision and is independent of the state of the
 * tasks belonging to the game. The game's free text is not here: notes are a
 * column of the table like any other, so they live in the game's `NOTES` cell.
 */
@Entity(
    tableName = "games",
    foreignKeys = [
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_import_batch_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["deleted_at"]), Index(value = ["source_import_batch_id"])],
)
data class GameEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: EntityId,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "is_manually_completed", defaultValue = "0")
    val isManuallyCompleted: Boolean = false,
    @ColumnInfo(name = "completed_at")
    val completedAt: Instant? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant? = null,
    /** The import this game came from, or null when the user created it by hand. */
    @ColumnInfo(name = "source_import_batch_id")
    val sourceImportBatchId: EntityId? = null,
)
