package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.ColorAliasEntity
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.rules.normalizeColorTerm
import kotlinx.coroutines.flow.Flow

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

    /**
     * The catalogue as the colour section lists it.
     *
     * Archived colours stay out: they are still resolvable for rows written
     * earlier, but they are no longer offered. `id` last keeps the list from
     * reshuffling when two colours were given the same place.
     */
    @Query("SELECT * FROM colors WHERE is_archived = 0 ORDER BY sort_order, id")
    fun observeActiveColors(): Flow<List<ColorEntity>>

    /**
     * Where a new colour goes: after everything already in the catalogue.
     *
     * Archived colours count too, so unarchiving one can never collide with a
     * place that has since been handed out.
     */
    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM colors")
    suspend fun nextSortOrder(): Int

    /**
     * The colours already written in [hex], so the form can say who has it.
     *
     * Compared without regard to case, because `#ffffff` and `#FFFFFF` are the
     * same colour to everyone but a string comparison. What was typed is still
     * stored as it was typed.
     */
    @Query("SELECT * FROM colors WHERE UPPER(hex) = UPPER(:hex) ORDER BY sort_order, id")
    suspend fun colorsWithHex(hex: String): List<ColorEntity>

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
     * Adds a colour to the end of the catalogue.
     *
     * Everything the decision rests on happens here, in one transaction: the
     * place is handed out, the name is checked against the other colours and
     * against every alias, and the row is written. Two colours saved at once
     * therefore cannot be given the same place, and no check can go stale
     * between being made and being acted on.
     *
     * The name is checked against the aliases as well as the names because
     * `addAlias` refuses the mirror of this. Without it a colour could be called
     * what another colour is already known as, and a term would resolve to two
     * different colours depending on which table was read first.
     *
     * The two refusals are told apart rather than lumped together, because the
     * user's next step is not the same: one name belongs to a colour they can
     * see, the other to a spelling of a colour they cannot.
     *
     * @return the colour as it was written, with the place it was given.
     * @throws ColorSetupException if the name is already a colour's name or
     *   another colour's alias; nothing is written in that case.
     */
    @Transaction
    suspend fun addColorToEndOfCatalogue(
        id: EntityId,
        canonicalName: String,
        hex: String,
    ): ColorEntity {
        val normalized = normalizeColorTerm(canonicalName)
        if (colorByNormalizedName(normalized) != null) {
            throw ColorSetupException(ColorSetupFailure.NAME_ALREADY_USED)
        }
        if (colorByNormalizedAlias(normalized) != null) {
            throw ColorSetupException(ColorSetupFailure.NAME_IS_ANOTHER_COLORS_ALIAS)
        }
        val color =
            ColorEntity.of(
                id = id,
                canonicalName = canonicalName.trim(),
                hex = hex,
                sortOrder = nextSortOrder(),
            )
        insert(color)
        return color
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
