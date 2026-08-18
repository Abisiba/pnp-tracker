package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.ColorAliasEntity
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.rules.normalizeColorTerm

/**
 * Reads and writes the color catalogue.
 *
 * Colors are never removed: one that is still referenced by a task is archived,
 * which takes it out of the pick list while leaving existing relations alone.
 */
@Dao
interface ColorDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(color: ColorEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAlias(alias: ColorAliasEntity)

    @Query("SELECT * FROM colors WHERE is_archived = 0 ORDER BY sort_order")
    suspend fun activeColors(): List<ColorEntity>

    @Query("SELECT * FROM colors ORDER BY sort_order")
    suspend fun allColorsIncludingArchived(): List<ColorEntity>

    @Query("SELECT * FROM colors WHERE id = :id")
    suspend fun colorById(id: EntityId): ColorEntity?

    @Query("SELECT * FROM colors WHERE normalized_name = :normalizedName")
    suspend fun colorByNormalizedName(normalizedName: String): ColorEntity?

    @Query(
        """
        SELECT colors.* FROM colors
        INNER JOIN color_aliases ON color_aliases.color_id = colors.id
        WHERE color_aliases.normalized_alias = :normalizedAlias
        """,
    )
    suspend fun colorByNormalizedAlias(normalizedAlias: String): ColorEntity?

    @Query("SELECT * FROM color_aliases WHERE color_id = :colorId ORDER BY normalized_alias")
    suspend fun aliasesOf(colorId: EntityId): List<ColorAliasEntity>

    @Query("UPDATE colors SET is_archived = 1 WHERE id = :id")
    suspend fun archive(id: EntityId): Int

    @Query("UPDATE colors SET is_archived = 0 WHERE id = :id")
    suspend fun unarchive(id: EntityId): Int

    /**
     * Resolves what the user typed to a color, matching the canonical name first
     * and then the aliases. Archived colors still resolve, because rows written
     * earlier must keep making sense.
     */
    @Transaction
    suspend fun resolve(term: String): ColorEntity? {
        val normalized = normalizeColorTerm(term)
        return colorByNormalizedName(normalized) ?: colorByNormalizedAlias(normalized)
    }

    /**
     * Adds an alias after checking that the term is not already taken by a color
     * name or by another color's alias.
     *
     * @throws IllegalArgumentException if the term already resolves elsewhere.
     */
    @Transaction
    suspend fun addAlias(
        colorId: EntityId,
        alias: String,
    ) {
        val entity = ColorAliasEntity.of(colorId = colorId, alias = alias)
        val clashingColor = colorByNormalizedName(entity.normalizedAlias)
        require(clashingColor == null) {
            "'$alias' is already the name of the color '${clashingColor?.canonicalName}'."
        }
        val clashingAliasOwner = colorByNormalizedAlias(entity.normalizedAlias)
        require(clashingAliasOwner == null || clashingAliasOwner.id == colorId) {
            "'$alias' is already an alias of the color '${clashingAliasOwner?.canonicalName}'."
        }
        insertAlias(entity)
    }
}
