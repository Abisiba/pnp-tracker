package dev.pnptracker.data.database

import androidx.sqlite.SQLiteConnection
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.domain.model.EntityId

/**
 * The colors the catalogue starts with, taken from the spreadsheet the first
 * import will come from.
 *
 * The identifiers are fixed constants rather than generated values: a color keeps
 * the same identity on every machine and in every backup, so a task written on
 * one database still points at the same color in another.
 *
 * This list is the single definition used by both paths that can create the
 * table — a brand new database and a database migrated from version 1 — so the
 * two can never drift apart.
 */
val seedColors: List<ColorEntity> =
    listOf(
        seedColor("0f108d69-9651-4e0b-9703-35e2ea1f7b8f", "Beyaz", "#FFFFFF", 0),
        seedColor("958cfe95-8b11-4c33-8967-c2a3816e0e2e", "Siyah", "#111111", 1),
        seedColor("60caf71d-6dcb-424b-8a15-d52d3e3d097a", "Gri", "#808080", 2),
        seedColor("1891dc8a-9d0b-44e2-ad93-f652d732fa32", "Kahverengi", "#795548", 3),
        seedColor("b0e8b346-91bb-49e0-b690-0c599a363c7c", "Kırmızı", "#E53935", 4),
        seedColor("0e3eb90b-510a-4271-850b-395e0a6d73e0", "Sarı", "#FDD835", 5),
        seedColor("45cfc24e-9b01-4d07-a31e-950298a23c8c", "Yeşil", "#43A047", 6),
        seedColor("c003990d-7991-4ecd-870f-be5c854d22bb", "Mavi", "#1E88E5", 7),
        seedColor("58926e12-b3ab-4784-87d7-6c21cc0e2662", "Açık Mavi", "#4FC3F7", 8),
        seedColor("d6065f36-f89e-4980-9ac9-5d4d955a6fd0", "Turuncu", "#FB8C00", 9),
        seedColor("4d66df5f-8864-42a1-bcc1-93f636f3f0fe", "Mor", "#8E24AA", 10),
        seedColor("572a8bdd-5c61-4b63-ab53-6a50c38ad4d1", "Pembe", "#EC407A", 11),
    )

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

private fun seedColor(
    id: String,
    canonicalName: String,
    hex: String,
    sortOrder: Int,
): ColorEntity =
    ColorEntity.of(
        id = EntityId.parse(id),
        canonicalName = canonicalName,
        hex = hex,
        sortOrder = sortOrder,
    )
