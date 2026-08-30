package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.ColorDao
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.domain.colors.BaseColorRestore
import dev.pnptracker.domain.colors.BaseColorRestoreBlock
import dev.pnptracker.domain.colors.BaseColorRestoreConflict
import dev.pnptracker.domain.colors.BaseColorRestorePlan
import dev.pnptracker.domain.colors.BlockedBaseColor
import dev.pnptracker.domain.colors.ColorRemoval
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.ColorUsage
import dev.pnptracker.domain.colors.ColorUsageSample
import dev.pnptracker.domain.colors.baseColors
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.rules.isValidColorHex
import dev.pnptracker.domain.rules.requireValidColorHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The global colour catalogue, and adding to it.
 *
 * A new database is created with the colours the plan starts everyone off with,
 * so the catalogue arrives full. It is not guaranteed to stay that way: every
 * colour, the seeded ones included, can be removed, so an empty catalogue is a
 * state this path may hand on.
 *
 * Nothing on this path touches a task. A colour exists in its own right, and
 * what a task is produced in is decided elsewhere.
 */
interface ColorCatalogue {
    /** Every colour, in the order the user put them in. */
    fun observeColors(): Flow<List<ColorSummary>>

    /**
     * The colours already written in [hex].
     *
     * For telling the user that a value is taken, which is a remark and not a
     * refusal: two colours may share a value.
     */
    suspend fun colorsUsingHex(hex: String): List<ColorSummary>

    /**
     * Adds a colour to the end of the catalogue.
     *
     * The name is trimmed at both ends, because trailing spaces are a slip rather
     * than a decision.
     *
     * @throws IllegalArgumentException if the name says nothing or [hex] is not
     *   written as `#RRGGBB`; neither is something storage could have prevented.
     * @throws ColorSetupException if the name is taken or the colour did not save.
     */
    suspend fun createColor(
        canonicalName: String,
        hex: String,
    ): EntityId

    /**
     * Changes a colour's name and value together.
     *
     * [expectedName] and [expectedHex] are what the form was opened on, so a
     * colour somebody else changed in the meantime is refused rather than
     * written over.
     *
     * @throws IllegalArgumentException if the name says nothing or [hex] is not
     *   written as `#RRGGBB`.
     * @throws ColorSetupException if the colour is gone, was changed underneath,
     *   or the name is taken; nothing is written in any of those cases.
     */
    suspend fun editColor(
        id: EntityId,
        expectedName: String,
        expectedHex: String,
        canonicalName: String,
        hex: String,
    )

    /**
     * What losing [id] would cost, for the confirmation PLAN 5.9 requires.
     *
     * Two reads whatever the colour is used by, so a colour in four hundred
     * tasks is no more expensive to ask about than one in none.
     */
    suspend fun usageOf(id: EntityId): ColorUsage

    /**
     * Removes a colour the user has confirmed, with everything pointing at it.
     *
     * @throws ColorSetupException if the colour is already gone.
     */
    suspend fun deleteColor(id: EntityId): ColorRemoval

    /** What restoring the missing base colours would do, without doing it. */
    suspend fun previewBaseColorRestore(): BaseColorRestorePlan

    /** Puts back the base colours whose fixed identities are not in the catalogue. */
    suspend fun restoreMissingBaseColors(): BaseColorRestore
}

class ColorCatalogueStore(
    private val colorDao: ColorDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
) : ColorCatalogue {
    override fun observeColors(): Flow<List<ColorSummary>> = colorDao.observeColors().map { colors -> colors.map(::summaryOf) }

    override suspend fun colorsUsingHex(hex: String): List<ColorSummary> =
        if (!isValidColorHex(hex)) emptyList() else colorDao.colorsWithHex(hex).map(::summaryOf)

    override suspend fun createColor(
        canonicalName: String,
        hex: String,
    ): EntityId {
        val cleanName = canonicalName.trim()
        require(cleanName.isNotEmpty()) { "A colour needs a name." }
        // Checked here rather than caught below, so a value the form should have
        // refused is never reported to the user as a storage problem.
        requireValidColorHex(hex)
        val id = idGenerator.newId()
        try {
            colorDao.addColorToEndOfCatalogue(id = id, canonicalName = cleanName, hex = hex)
        } catch (cause: SQLiteException) {
            throw ColorSetupException(ColorSetupFailure.COULD_NOT_SAVE, cause)
        }
        return id
    }

    override suspend fun editColor(
        id: EntityId,
        expectedName: String,
        expectedHex: String,
        canonicalName: String,
        hex: String,
    ) {
        val cleanName = canonicalName.trim()
        require(cleanName.isNotEmpty()) { "A colour needs a name." }
        requireValidColorHex(hex)
        try {
            colorDao.renameAndRecolorTheUserHasConfirmed(
                id = id,
                expectedName = expectedName,
                expectedHex = expectedHex,
                canonicalName = cleanName,
                hex = hex,
            )
        } catch (cause: SQLiteException) {
            throw ColorSetupException(ColorSetupFailure.COULD_NOT_SAVE, cause)
        }
    }

    override suspend fun usageOf(id: EntityId): ColorUsage {
        val counts = colorDao.usageCountsOf(id)
        val samples = colorDao.usageSamplesOf(id, ColorUsage.SAMPLE_LIMIT)
        return ColorUsage(
            taskCount = counts.taskCount,
            unfinishedTaskCount = counts.unfinishedTaskCount,
            gameCount = counts.gameCount,
            tasksLosingTheirLastColor = counts.tasksLosingTheirLastColor,
            samples = samples.map { ColorUsageSample(taskName = it.taskName, gameName = it.gameName) },
        )
    }

    override suspend fun deleteColor(id: EntityId): ColorRemoval =
        try {
            colorDao.deleteColorTheUserHasConfirmed(id)
        } catch (cause: SQLiteException) {
            throw ColorSetupException(ColorSetupFailure.COULD_NOT_SAVE, cause)
        }

    override suspend fun previewBaseColorRestore(): BaseColorRestorePlan = colorDao.baseColorRestorePlan()

    /**
     * A conflict is answered rather than thrown on: PLAN 5.8 wants the user told
     * which colour could not come back, and a colour that appeared underneath is
     * one of those answers. The transaction has already put everything back.
     */
    override suspend fun restoreMissingBaseColors(): BaseColorRestore =
        try {
            colorDao.restoreMissingBaseColorsTheUserHasConfirmed()
        } catch (conflict: BaseColorRestoreConflict) {
            BaseColorRestore.Blocked(
                listOf(
                    BlockedBaseColor(
                        canonicalName = conflict.canonicalName,
                        hex = baseColors.first { it.canonicalName == conflict.canonicalName }.hex,
                        reason = BaseColorRestoreBlock.APPEARED_MEANWHILE,
                    ),
                ),
            )
        } catch (cause: SQLiteException) {
            throw ColorSetupException(ColorSetupFailure.COULD_NOT_SAVE, cause)
        }

    private fun summaryOf(color: ColorEntity): ColorSummary =
        ColorSummary(
            id = color.id,
            canonicalName = color.canonicalName,
            hex = color.hex,
            sortOrder = color.sortOrder,
        )
}
