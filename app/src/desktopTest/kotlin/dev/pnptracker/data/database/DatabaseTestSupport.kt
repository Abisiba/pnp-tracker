package dev.pnptracker.data.database

import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.entity.ItemEntity
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A temporary database location owned by a single test, plus the small fixtures
 * the database tests build their rows from. None of this belongs in production
 * code.
 */
class TemporaryDatabaseDirectory {
    val root: Path = Files.createTempDirectory("pnp-tracker-db-test")

    val databaseFile: Path get() = root.resolve("pnp.db")

    /** Deletes only this directory, after proving it is the one this test created. */
    fun delete() {
        val systemTemporaryDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val absoluteRoot = root.toAbsolutePath().normalize()
        check(absoluteRoot.startsWith(systemTemporaryDirectory) && absoluteRoot != systemTemporaryDirectory) {
            "Refusing to delete $absoluteRoot: it is outside $systemTemporaryDirectory"
        }
        val realUserHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        check(!absoluteRoot.startsWith(realUserHome)) {
            "Refusing to delete $absoluteRoot: it is below the real user home"
        }
        Files.walk(absoluteRoot).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    /** Proves the test never wrote to the real application database. */
    fun assertRealApplicationDatabaseUntouched(existedBefore: Boolean) {
        assertTrue(
            Files.exists(realApplicationDatabaseFile()) == existedBefore,
            "the test changed whether ${realApplicationDatabaseFile()} exists",
        )
    }

    companion object {
        fun realApplicationDatabaseFile(): Path =
            Path
                .of(System.getProperty("user.home"))
                .resolve(".local/share/pnp-tracker/pnp.db")
    }
}

const val EPOCH_MILLISECONDS_CREATED = 1_700_000_000_000L
const val EPOCH_MILLISECONDS_UPDATED = 1_700_000_600_000L
const val EPOCH_MILLISECONDS_DELETED = 1_700_001_200_000L

val createdAt: Instant = Instant.fromEpochMilliseconds(EPOCH_MILLISECONDS_CREATED)
val updatedAt: Instant = Instant.fromEpochMilliseconds(EPOCH_MILLISECONDS_UPDATED)
val deletedAt: Instant = Instant.fromEpochMilliseconds(EPOCH_MILLISECONDS_DELETED)

fun aGame(
    id: EntityId = IdGenerator.Random.newId(),
    name: String = "Harmonies",
    notes: String? = null,
    isManuallyCompleted: Boolean = false,
    completedAt: Instant? = null,
): GameEntity =
    GameEntity(
        id = id,
        name = name,
        notes = notes,
        isManuallyCompleted = isManuallyCompleted,
        completedAt = completedAt,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

fun anItem(
    gameId: EntityId,
    id: EntityId = IdGenerator.Random.newId(),
    name: String = "Token",
    notes: String? = null,
): ItemEntity =
    ItemEntity(
        id = id,
        gameId = gameId,
        name = name,
        notes = notes,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
