package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.rules.normalizeColorTerm
import dev.pnptracker.domain.rules.requireValidColorHex

/**
 * A color in the global catalogue.
 *
 * [canonicalName] is what the user sees and types; [normalizedName] is what the
 * application matches on. A color that is still used by a task is archived rather
 * than deleted, which is why there is no `deletedAt` here.
 */
@Entity(
    tableName = "colors",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
data class ColorEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: EntityId,
    @ColumnInfo(name = "canonical_name")
    val canonicalName: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "hex")
    val hex: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    @ColumnInfo(name = "is_archived", defaultValue = "0")
    val isArchived: Boolean = false,
) {
    init {
        require(canonicalName.isNotBlank()) { "A color needs a name." }
        require(normalizedName == normalizeColorTerm(canonicalName)) {
            "normalizedName must be the normalised form of '$canonicalName', was: '$normalizedName'"
        }
        requireValidColorHex(hex)
    }

    companion object {
        /** Builds a color, deriving [normalizedName] so the two can never drift apart. */
        fun of(
            id: EntityId,
            canonicalName: String,
            hex: String,
            sortOrder: Int,
            isArchived: Boolean = false,
        ): ColorEntity =
            ColorEntity(
                id = id,
                canonicalName = canonicalName,
                normalizedName = normalizeColorTerm(canonicalName),
                hex = hex,
                sortOrder = sortOrder,
                isArchived = isArchived,
            )
    }
}
