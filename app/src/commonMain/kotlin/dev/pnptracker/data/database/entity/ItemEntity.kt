package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.pnptracker.domain.model.EntityId
import kotlin.time.Instant

/**
 * A logical item inside a game, such as a token or a card group.
 *
 * The foreign key restricts hard deletes rather than cascading them: removing a
 * game is a soft delete, which leaves these rows untouched so that restoring the
 * game later brings its items back.
 */
@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["game_id"]), Index(value = ["deleted_at"])],
)
data class ItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: EntityId,
    @ColumnInfo(name = "game_id")
    val gameId: EntityId,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant? = null,
)
