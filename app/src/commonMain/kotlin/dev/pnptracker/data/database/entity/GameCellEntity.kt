package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import kotlin.time.Instant

/**
 * One cell of a game row: the surface the user types into.
 *
 * The cell holds no text of its own. What it contains is the ordered list of
 * [CellSegmentEntity] rows below it, which is what keeps a task from being a
 * range of characters that editing can slide out from under.
 *
 * A game has at most one cell per column, which the unique index enforces rather
 * than trusting every caller to check first.
 */
@Entity(
    tableName = "game_cells",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["game_id", "column_type"], unique = true)],
)
data class GameCellEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: EntityId,
    @ColumnInfo(name = "game_id")
    val gameId: EntityId,
    @ColumnInfo(name = "column_type")
    val columnType: CellColumnType,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
