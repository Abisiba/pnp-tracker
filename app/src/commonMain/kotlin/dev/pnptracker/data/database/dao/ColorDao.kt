package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.ColorAliasEntity
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.domain.colors.ColorRemoval
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.rules.normalizeColorTerm
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the color catalogue.
 *
 * Every colour in the table is offered. There is no hidden half of the
 * catalogue and nothing here filters one out, so what a query returns is the
 * whole of what the user has.
 */
@Dao
interface ColorDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(color: ColorEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAlias(alias: ColorAliasEntity)

    /**
     * The whole catalogue, in the order the user put it in.
     *
     * `id` last keeps the list from reshuffling when two colours were given the
     * same place.
     */
    @Query("SELECT * FROM colors ORDER BY sort_order, id")
    suspend fun allColors(): List<ColorEntity>

    /**
     * The catalogue as the colour section lists it.
     *
     * The same rows [allColors] returns, as a stream.
     */
    @Query("SELECT * FROM colors ORDER BY sort_order, id")
    fun observeColors(): Flow<List<ColorEntity>>

    /** Where a new colour goes: after everything already in the catalogue. */
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

    /**
     * Resolves what the user typed to a color, matching the canonical name first
     * and then the aliases.
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

    @Query("DELETE FROM task_colors WHERE color_id = :colorId")
    suspend fun removeEveryUseOfColor(colorId: EntityId): Int

    @Query("SELECT DISTINCT task_id FROM task_colors WHERE color_id = :colorId")
    suspend fun tasksUsingColor(colorId: EntityId): List<EntityId>

    @Query("DELETE FROM color_aliases WHERE color_id = :colorId")
    suspend fun removeAliasesOfColor(colorId: EntityId): Int

    @Query("DELETE FROM colors WHERE id = :id")
    suspend fun deleteColorRow(id: EntityId): Int

    @Query("SELECT * FROM task_colors WHERE task_id = :taskId ORDER BY slot_index")
    suspend fun colorsOfTask(taskId: EntityId): List<TaskColorEntity>

    @Query("UPDATE task_colors SET slot_index = :slotIndex WHERE task_id = :taskId AND color_id = :colorId")
    suspend fun setSlotIndex(
        taskId: EntityId,
        colorId: EntityId,
        slotIndex: Int,
    ): Int

    /**
     * Removes a colour the user has agreed to lose, along with everything that
     * points at it.
     *
     * The name says the confirmation has already happened, because this cannot
     * ask: by the time it runs, the decision is made and the only question left
     * is whether the whole of it lands. It does or none of it does — the relations
     * and the aliases have to go before the colour, since the foreign keys refuse
     * to leave either dangling, and a run that stopped halfway would leave rows
     * pointing at a colour that is on its way out.
     *
     * Tasks are not touched. A task that loses its last colour is still a task;
     * PLAN 5.9 has it fall back to `Renk seçilecek` rather than disappear with
     * the colour, and nothing here writes that state because nothing needs to:
     * having no colour rows is what the state is.
     *
     * The colours a multi-colour task keeps are renumbered so their order stays
     * `0..N-1`. A gap would be a hole the name splitting would have to guess
     * about.
     *
     * @return what was removed, so a caller can tell the user what happened.
     * @throws ColorSetupException if the colour is not there any more; nothing is
     *   written in that case.
     */
    @Transaction
    suspend fun deleteColorTheUserHasConfirmed(colorId: EntityId): ColorRemoval {
        colorById(colorId) ?: throw ColorSetupException(ColorSetupFailure.COLOR_NO_LONGER_EXISTS)
        val affectedTasks = tasksUsingColor(colorId)
        val removedRelations = removeEveryUseOfColor(colorId)
        val removedAliases = removeAliasesOfColor(colorId)
        affectedTasks.forEach { taskId -> compactSlots(taskId) }
        val removed = deleteColorRow(colorId)
        check(removed == 1) { "The colour $colorId was still there after being deleted." }
        return ColorRemoval(
            taskCount = affectedTasks.size,
            removedRelationCount = removedRelations,
            removedAliasCount = removedAliases,
            tasksLeftWithoutAColor = affectedTasks.count { colorsOfTask(it).isEmpty() },
        )
    }

    /**
     * Closes the gaps one removal left in a task's colour order.
     *
     * The rows are moved out of the way first: renumbering in place would collide
     * with the unique index the moment a colour landed on a place another one
     * still held. The negative range is never a resting state — both loops are in
     * the caller's transaction.
     */
    private suspend fun compactSlots(taskId: EntityId) {
        val ordered = colorsOfTask(taskId)
        if (ordered.withIndex().all { (index, row) -> row.slotIndex == index }) return
        ordered.forEachIndexed { index, row -> setSlotIndex(taskId, row.colorId, -(index + 1)) }
        ordered.forEachIndexed { index, row -> setSlotIndex(taskId, row.colorId, index) }
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
