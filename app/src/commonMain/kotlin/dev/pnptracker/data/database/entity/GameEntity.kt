package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.pnptracker.domain.model.EntityId
import kotlin.time.Instant

/**
 * A game row. Completion is the user's own decision and is independent of the
 * state of the tasks belonging to the game.
 */
@Entity(
    tableName = "games",
    indices = [Index(value = ["deleted_at"])],
)
data class GameEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: EntityId,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
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
)
