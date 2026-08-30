package dev.pnptracker.data.database

import androidx.sqlite.SQLiteConnection
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.domain.colors.baseColors

/**
 * The colors the catalogue starts with, as rows of the `colors` table.
 *
 * What those colours are is decided once, in [baseColors]; this only puts them
 * in the shape storage wants. Both paths that can create the table — a brand new
 * database and one migrated from version 1 — write these same rows, and the
 * colour picker offers the same twelve, so none of the three can drift.
 */
val seedColors: List<ColorEntity> =
    baseColors.map { base ->
        ColorEntity.of(
            id = base.id,
            canonicalName = base.canonicalName,
            hex = base.hex,
            sortOrder = base.sortOrder,
        )
    }

/**
 * Writes the seed colors on [connection]. Running it again inserts nothing, so
 * opening the application repeatedly cannot multiply the catalogue.
 *
 * The caller supplies the transaction: Room already wraps both creation and
 * migration in one.
 */
internal fun insertSeedColors(connection: SQLiteConnection) {
    seedColors.forEach { color ->
        connection.prepare(INSERT_SEED_COLOR).use { statement ->
            statement.bindText(1, color.id.toString())
            statement.bindText(2, color.canonicalName)
            statement.bindText(3, color.normalizedName)
            statement.bindText(4, color.hex)
            statement.bindInt(5, color.sortOrder)
            statement.step()
        }
    }
}

private const val INSERT_SEED_COLOR =
    "INSERT OR IGNORE INTO colors (id, canonical_name, normalized_name, hex, sort_order) " +
        "VALUES (?, ?, ?, ?, ?)"
