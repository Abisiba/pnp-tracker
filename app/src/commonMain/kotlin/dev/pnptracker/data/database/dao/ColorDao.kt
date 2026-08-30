package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.entity.ColorAliasEntity
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.projection.ColorUsageCounts
import dev.pnptracker.data.database.projection.ColorUsageSampleRow
import dev.pnptracker.domain.colors.BaseColor
import dev.pnptracker.domain.colors.BaseColorRestore
import dev.pnptracker.domain.colors.BaseColorRestoreBlock
import dev.pnptracker.domain.colors.BaseColorRestoreConflict
import dev.pnptracker.domain.colors.BaseColorRestorePlan
import dev.pnptracker.domain.colors.ColorRemoval
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.planBaseColorRestore
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
     * Every alias in the catalogue, for checking a name against all of them at
     * once.
     *
     * Read whole rather than asked about one term at a time: a restore has to
     * clear twelve names, and twelve questions would be twelve reads for an
     * answer that fits in one.
     */
    @Query("SELECT * FROM color_aliases")
    suspend fun allAliases(): List<ColorAliasEntity>

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
     * How much of the catalogue one colour is holding, in a single read.
     *
     * Every number is counted over distinct tasks rather than over rows. The
     * query reaches through the segment and the cell to find the game, and a
     * task that turned up twice on the way would otherwise be counted twice —
     * which is the one thing a confirmation about something irreversible cannot
     * afford to get wrong.
     *
     * `last_color_task_count` is worked out from a grouped count rather than by
     * asking about each task, so this stays one statement whether the colour is
     * used by one task or by four hundred.
     */
    @Query(
        """
        SELECT
          COUNT(DISTINCT tc.task_id) AS task_count,
          COUNT(DISTINCT CASE WHEN t.is_completed = 0 THEN tc.task_id END) AS unfinished_task_count,
          COUNT(DISTINCT gc.game_id) AS game_count,
          COUNT(DISTINCT CASE WHEN held.color_count = 1 THEN tc.task_id END) AS last_color_task_count
        FROM task_colors tc
        JOIN tasks t ON t.id = tc.task_id AND t.deleted_at IS NULL
        LEFT JOIN (SELECT task_id, COUNT(*) AS color_count FROM task_colors GROUP BY task_id) held
          ON held.task_id = tc.task_id
        LEFT JOIN cell_segments cs ON cs.task_id = t.id
        LEFT JOIN game_cells gc ON gc.id = cs.cell_id
        WHERE tc.color_id = :colorId
        """,
    )
    suspend fun usageCountsOf(colorId: EntityId): ColorUsageCounts

    /**
     * A few of the tasks a colour is used by, named.
     *
     * Grouped by task so a task cannot appear twice, and ordered by names rather
     * than by identity so the same colour always shows the same examples.
     */
    @Query(
        """
        SELECT t.name AS task_name, g.name AS game_name
        FROM task_colors tc
        JOIN tasks t ON t.id = tc.task_id AND t.deleted_at IS NULL
        LEFT JOIN cell_segments cs ON cs.task_id = t.id
        LEFT JOIN game_cells gc ON gc.id = cs.cell_id
        LEFT JOIN games g ON g.id = gc.game_id
        WHERE tc.color_id = :colorId
        GROUP BY t.id
        ORDER BY g.name, t.name, t.id
        LIMIT :limit
        """,
    )
    suspend fun usageSamplesOf(
        colorId: EntityId,
        limit: Int,
    ): List<ColorUsageSampleRow>

    /**
     * Moves every colour slot of every task using [colorId] out of the way,
     * below zero.
     *
     * One statement for all of them. Renumbering in place would collide with the
     * unique index the moment a colour landed on a place another one still held,
     * so the whole set is lifted out of the way first.
     *
     * The move is `-1 - slot`, which is its own inverse: `0` goes to `-1`, `1`
     * to `-2`, and `Int.MAX_VALUE` to `Int.MIN_VALUE`. That matters more than it
     * looks. Subtracting a fixed number instead would only work while every slot
     * stayed below that number — a slot of exactly a million, parked by
     * subtracting a million, comes out at zero, is not below zero any more, and
     * is never brought back. A slot is an `Int` and [TaskColorEntity] asks only
     * that it not be negative, so no number is large enough to subtract and no
     * ceiling may be invented for a list the user builds. This move has no
     * ceiling: it carries the whole non-negative range onto the whole negative
     * range, one to one, and nothing it computes leaves what an `Int` holds.
     *
     * It reverses the order, which the statement that undoes it accounts for.
     */
    @Query(
        """
        UPDATE task_colors SET slot_index = -1 - slot_index
        WHERE task_id IN (SELECT task_id FROM task_colors WHERE color_id = :colorId)
        """,
    )
    suspend fun parkSlotsOfTasksUsing(colorId: EntityId): Int

    /**
     * Brings every parked slot back, closing the one gap, in one statement.
     *
     * `-1 - slot` undoes the park, and then a row steps down by one if the
     * colour on its way out was in front of it. Nothing here counts the other
     * rows, so nothing depends on how many of them have already been moved —
     * which is the whole point: SQLite gives no promise about the order an
     * `UPDATE` visits rows in, and a rank worked out by counting live rows comes
     * out differently depending on where it starts. A task has at most one row
     * per colour, so "did it go before me" is one question with a yes or a no.
     *
     * The comparison is `<` rather than `>` because the park turns the order
     * upside down: a row parked further below zero was further up the list.
     *
     * No two rows can meet on the way. Everything still parked is below zero and
     * everything already brought back is not, and the two rows that would land
     * on the same place are the one leaving and the one after it — and the one
     * leaving is not brought back at all. It is left parked and deleted next, so
     * the place it vacates is never briefly held twice.
     *
     * Only this transaction can have parked anything: Room writes through one
     * connection, so no other writer can be halfway through a park of its own,
     * and [TaskColorEntity] refuses a negative slot, so nothing else is below
     * zero to be caught up in this.
     */
    @Query(
        """
        UPDATE task_colors SET slot_index = -1 - slot_index -
          (CASE WHEN slot_index <
                  (SELECT leaving.slot_index FROM task_colors leaving
                    WHERE leaving.task_id = task_colors.task_id AND leaving.color_id = :colorId)
                THEN 1 ELSE 0 END)
        WHERE slot_index < 0 AND color_id <> :colorId
        """,
    )
    suspend fun closeParkedSlotGaps(colorId: EntityId): Int

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
     * about. Seven statements do it, and seven is what it costs whether one task
     * uses the colour or forty-two: nothing here walks the tasks.
     *
     * The counts are read before anything is removed, because afterwards there
     * is nothing left to count.
     *
     * @return what was removed, so a caller can tell the user what happened.
     * @throws ColorSetupException if the colour is not there any more; nothing is
     *   written in that case.
     */
    @Transaction
    suspend fun deleteColorTheUserHasConfirmed(colorId: EntityId): ColorRemoval {
        colorById(colorId) ?: throw ColorSetupException(ColorSetupFailure.COLOR_NO_LONGER_EXISTS)
        val counts = usageCountsOf(colorId)
        parkSlotsOfTasksUsing(colorId)
        closeParkedSlotGaps(colorId)
        val removedRelations = removeEveryUseOfColor(colorId)
        val removedAliases = removeAliasesOfColor(colorId)
        val removed = deleteColorRow(colorId)
        check(removed == 1) { "The colour $colorId was still there after being deleted." }
        return ColorRemoval(
            taskCount = counts.taskCount,
            removedRelationCount = removedRelations,
            removedAliasCount = removedAliases,
            tasksLeftWithoutAColor = counts.tasksLosingTheirLastColor,
        )
    }

    @Query(
        """
        UPDATE colors
        SET canonical_name = :canonicalName, normalized_name = :normalizedName, hex = :hex
        WHERE id = :id
        """,
    )
    suspend fun writeColorNameAndValue(
        id: EntityId,
        canonicalName: String,
        normalizedName: String,
        hex: String,
    ): Int

    /**
     * Changes what a colour is called and what it looks like, together.
     *
     * One transaction for both, because PLAN 12.14 offers them as one thing to
     * change: written apart, a refused name would leave a colour wearing a value
     * the user never agreed to keep on its own.
     *
     * [expectedName] and [expectedHex] are what the form was opened on. If the
     * row says something else now, somebody changed it in the meantime and this
     * refuses rather than writing over their work — the user is looking at an
     * answer to a question that has moved.
     *
     * A save that changes neither is not written at all. There is nothing to
     * record and an update would only be a row touched for no reason.
     *
     * The name is checked against the other colours with this colour left out,
     * so `gri` may become `Gri` — a real change the user can see, and one that
     * would otherwise be refused for clashing with itself. The aliases are
     * checked without that exemption: `addAlias` refuses a term that is already
     * a colour's name, including its own, and a rename that walked around the
     * mirror of that rule would leave a state the other path would not have
     * allowed to be built.
     *
     * Nothing else is touched. The identity and the place in the catalogue stay
     * as they are, and no task, segment, stage or event is written: a colour
     * changing its name is not a task changing anything.
     *
     * @return the colour as it now stands.
     * @throws ColorSetupException if the colour is gone, was changed underneath,
     *   or the name is taken; nothing is written in any of those cases.
     */
    @Transaction
    suspend fun renameAndRecolorTheUserHasConfirmed(
        id: EntityId,
        expectedName: String,
        expectedHex: String,
        canonicalName: String,
        hex: String,
    ): ColorEntity {
        val current = colorById(id) ?: throw ColorSetupException(ColorSetupFailure.COLOR_NO_LONGER_EXISTS)
        if (current.canonicalName != expectedName || current.hex != expectedHex) {
            throw ColorSetupException(ColorSetupFailure.COLOR_CHANGED_MEANWHILE)
        }
        if (current.canonicalName == canonicalName && current.hex == hex) return current

        val normalized = normalizeColorTerm(canonicalName)
        val named = colorByNormalizedName(normalized)
        if (named != null && named.id != id) {
            throw ColorSetupException(ColorSetupFailure.NAME_ALREADY_USED)
        }
        if (colorByNormalizedAlias(normalized) != null) {
            throw ColorSetupException(ColorSetupFailure.NAME_IS_ANOTHER_COLORS_ALIAS)
        }
        val written = writeColorNameAndValue(id = id, canonicalName = canonicalName, normalizedName = normalized, hex = hex)
        check(written == 1) { "The colour $id was not there to change." }
        return current.copy(canonicalName = canonicalName, normalizedName = normalized, hex = hex)
    }

    /**
     * What restoring the base colours would do, without doing any of it.
     *
     * Two reads whatever the catalogue holds, and the same [planBaseColorRestore]
     * the transaction itself uses, so what the user is shown and what is written
     * cannot disagree.
     */
    @Transaction
    suspend fun baseColorRestorePlan(): BaseColorRestorePlan = planOfRestore()

    /**
     * Puts back the base colours that are not in the catalogue any more.
     *
     * Missing means one thing: the fixed identity is not there. PLAN 5.7 lets a
     * base colour be renamed and recoloured, so a row that has been edited past
     * recognition is still that base colour — it is not missing, it is not a
     * clash, and not one of its fields is touched here.
     *
     * All of it or none of it, as PLAN 5.8 requires. Every clash is found before
     * the first row is written, and one clash stops the whole thing rather than
     * the others going in without it. The clashes are all returned together,
     * because being told about the first of three would send the user round the
     * same refusal twice more.
     *
     * Only `colors` is written. A colour that comes back brings no aliases —
     * there are none to bring, since nothing in the application has ever written
     * one — and it is given to no task: PLAN will not have the application guess
     * which tasks were once made in it.
     *
     * @throws BaseColorRestoreConflict if a base colour appeared between the
     *   check and the write; everything this transaction did is undone.
     */
    @Transaction
    suspend fun restoreMissingBaseColorsTheUserHasConfirmed(): BaseColorRestore {
        val plan = planOfRestore()
        if (plan.isNothingMissing) return BaseColorRestore.NothingMissing
        if (plan.blocked.isNotEmpty()) return BaseColorRestore.Blocked(plan.blocked)
        plan.missing.forEach { base ->
            try {
                insert(
                    ColorEntity.of(
                        id = base.id,
                        canonicalName = base.canonicalName,
                        hex = base.hex,
                        sortOrder = base.sortOrder,
                    ),
                )
            } catch (cause: SQLiteException) {
                // Why it would not go in is a question for the database, not for
                // the exception. Either answer undoes the whole restore rather
                // than leaving some of the twelve in.
                throw whyARestoreWouldNotTake(base, cause)
            }
        }
        return BaseColorRestore.Restored(plan.missing.map { it.canonicalName })
    }

    /**
     * What was in the way of putting [base] back, asked of the database rather
     * than guessed from the failure.
     *
     * A refused insert says only that it was refused. Three different things can
     * refuse it and the user has to do a different thing about each: a colour
     * appeared on that identity, a colour took that name, or a colour is known
     * by that name. None of them can be told from the others by reading the
     * message, and reading the message is how a classification quietly rots the
     * first time a version of SQLite words one differently — so this asks three
     * questions instead, in the order of what would have to be true.
     *
     * A fourth thing can also refuse it: the write simply did not land. A disk
     * that is full, a driver that gave out, a constraint nothing here knows
     * about. None of those is a race, and answering them with one would tell the
     * user to go and rename a colour that is perfectly fine. So when the
     * database says nothing is in the way, the original failure is handed back
     * untouched and becomes an ordinary saving failure.
     *
     * The questions themselves may fail — a connection that just refused a write
     * may refuse a read too. That is also not a race, and is answered the same
     * way.
     */
    suspend fun whyARestoreWouldNotTake(
        base: BaseColor,
        cause: SQLiteException,
    ): Throwable {
        val normalized = normalizeColorTerm(base.canonicalName)
        val reason =
            try {
                when {
                    colorById(base.id) != null -> BaseColorRestoreBlock.APPEARED_MEANWHILE
                    colorByNormalizedName(normalized) != null -> BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR
                    colorByNormalizedAlias(normalized) != null -> BaseColorRestoreBlock.NAME_TAKEN_BY_ALIAS
                    else -> null
                }
            } catch (ignored: SQLiteException) {
                null
            }
        return reason?.let { BaseColorRestoreConflict(base.canonicalName, base.hex, it, cause) } ?: cause
    }

    /** The catalogue and every alias in it, read once each and weighed in memory. */
    private suspend fun planOfRestore(): BaseColorRestorePlan =
        planBaseColorRestore(
            catalogue = allColors().map { ColorSummary(it.id, it.canonicalName, it.hex, it.sortOrder) },
            aliasTerms = allAliases().map { it.alias },
        )

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
