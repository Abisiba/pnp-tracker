package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.ColorDao
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
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

    private fun summaryOf(color: ColorEntity): ColorSummary =
        ColorSummary(
            id = color.id,
            canonicalName = color.canonicalName,
            hex = color.hex,
            sortOrder = color.sortOrder,
        )
}
