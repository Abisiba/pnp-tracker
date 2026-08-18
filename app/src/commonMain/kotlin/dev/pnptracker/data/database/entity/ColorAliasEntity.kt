package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.rules.normalizeColorTerm

/**
 * Another spelling that resolves to a color, for example a name the user wrote in
 * a spreadsheet. Aliases have no identity of their own: the color and the
 * normalised alias together identify the row.
 */
@Entity(
    tableName = "color_aliases",
    primaryKeys = ["color_id", "normalized_alias"],
    foreignKeys = [
        ForeignKey(
            entity = ColorEntity::class,
            parentColumns = ["id"],
            childColumns = ["color_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["normalized_alias"], unique = true)],
)
data class ColorAliasEntity(
    @ColumnInfo(name = "color_id")
    val colorId: EntityId,
    @ColumnInfo(name = "alias")
    val alias: String,
    @ColumnInfo(name = "normalized_alias")
    val normalizedAlias: String,
) {
    init {
        require(alias.isNotBlank()) { "An alias cannot be blank." }
        require(normalizedAlias == normalizeColorTerm(alias)) {
            "normalizedAlias must be the normalised form of '$alias', was: '$normalizedAlias'"
        }
    }

    companion object {
        fun of(
            colorId: EntityId,
            alias: String,
        ): ColorAliasEntity =
            ColorAliasEntity(
                colorId = colorId,
                alias = alias,
                normalizedAlias = normalizeColorTerm(alias),
            )
    }
}
