package dev.pnptracker.data.database

import androidx.sqlite.SQLiteConnection
import dev.pnptracker.domain.model.EntityId

/**
 * Rows written straight through a raw connection, the way an older version of the
 * application would have written them, so migration tests start from real data
 * rather than from rows Room created with today's entities.
 */
fun insertVersion1Game(
    connection: SQLiteConnection,
    gameId: EntityId,
    name: String = "Harmonies",
    notes: String? = "eski not",
) {
    connection
        .prepare(
            "INSERT INTO games (id, name, notes, is_manually_completed, completed_at, " +
                "created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, gameId.toString())
            statement.bindText(2, name)
            if (notes == null) statement.bindNull(3) else statement.bindText(3, notes)
            statement.bindInt(4, 1)
            statement.bindLong(5, EPOCH_MILLISECONDS_UPDATED)
            statement.bindLong(6, EPOCH_MILLISECONDS_CREATED)
            statement.bindLong(7, EPOCH_MILLISECONDS_UPDATED)
            statement.bindNull(8)
            statement.step()
        }
}

fun insertVersion1Item(
    connection: SQLiteConnection,
    itemId: EntityId,
    gameId: EntityId,
    name: String = "Token",
) {
    connection
        .prepare(
            "INSERT INTO items (id, game_id, name, notes, created_at, updated_at, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, itemId.toString())
            statement.bindText(2, gameId.toString())
            statement.bindText(3, name)
            statement.bindNull(4)
            statement.bindLong(5, EPOCH_MILLISECONDS_CREATED)
            statement.bindLong(6, EPOCH_MILLISECONDS_CREATED)
            statement.bindNull(7)
            statement.step()
        }
}

fun insertVersion2Task(
    connection: SQLiteConnection,
    taskId: EntityId,
    itemId: EntityId,
    name: String = "Gri token",
    requiredQuantity: Int = 14,
) {
    connection
        .prepare(
            "INSERT INTO tasks (id, item_id, pool_type, tracking_mode, name, required_quantity, notes, " +
                "is_archived, created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, taskId.toString())
            statement.bindText(2, itemId.toString())
            statement.bindText(3, "THREE_D")
            statement.bindText(4, "THREE_D_BATCH")
            statement.bindText(5, name)
            statement.bindInt(6, requiredQuantity)
            statement.bindNull(7)
            statement.bindInt(8, 0)
            statement.bindLong(9, EPOCH_MILLISECONDS_CREATED)
            statement.bindLong(10, EPOCH_MILLISECONDS_CREATED)
            statement.bindNull(11)
            statement.step()
        }
}

fun insertVersion2TaskColor(
    connection: SQLiteConnection,
    taskId: EntityId,
    colorId: EntityId,
) {
    connection
        .prepare("INSERT INTO task_colors (task_id, color_id, relation, is_selected) VALUES (?, ?, ?, ?)")
        .use { statement ->
            statement.bindText(1, taskId.toString())
            statement.bindText(2, colorId.toString())
            statement.bindText(3, "REQUIRED")
            statement.bindInt(4, 1)
            statement.step()
        }
}

fun insertVersion2ColorAlias(
    connection: SQLiteConnection,
    colorId: EntityId,
    alias: String,
    normalizedAlias: String,
) {
    connection
        .prepare("INSERT INTO color_aliases (color_id, alias, normalized_alias) VALUES (?, ?, ?)")
        .use { statement ->
            statement.bindText(1, colorId.toString())
            statement.bindText(2, alias)
            statement.bindText(3, normalizedAlias)
            statement.step()
        }
}
