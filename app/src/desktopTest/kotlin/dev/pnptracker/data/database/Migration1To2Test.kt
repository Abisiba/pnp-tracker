package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Upgrades a real version 1 database to version 2.
 *
 * The version 1 database is built from the committed `1.json` schema rather than
 * from hand written SQL, so this test upgrades exactly the shape that shipped.
 */
class Migration1To2Test {
    private lateinit var directory: TemporaryDatabaseDirectory
    private var realDatabaseExistedBefore = false

    private val gameId = IdGenerator.Random.newId()
    private val itemId = IdGenerator.Random.newId()

    @BeforeTest
    fun createTemporaryDirectory() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    /** Creates the version 1 database exactly as the committed schema describes it. */
    private fun createVersion1DatabaseWithData() {
        val schema = readSchema(version = 1)
        val driver = BundledSQLiteDriver()
        val connection = driver.open(directory.databaseFile.toAbsolutePath().toString())
        try {
            connection.execSQL("PRAGMA foreign_keys = ON")
            schema.createStatements.forEach(connection::execSQL)
            schema.setupQueries.forEach(connection::execSQL)
            connection.execSQL("PRAGMA user_version = 1")
            connection
                .prepare(
                    "INSERT INTO games (id, name, notes, is_manually_completed, completed_at, " +
                        "created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.bindText(1, gameId.toString())
                    statement.bindText(2, "Harmonies")
                    statement.bindText(3, "eski not")
                    statement.bindInt(4, 1)
                    statement.bindLong(5, EPOCH_MILLISECONDS_UPDATED)
                    statement.bindLong(6, EPOCH_MILLISECONDS_CREATED)
                    statement.bindLong(7, EPOCH_MILLISECONDS_UPDATED)
                    statement.bindNull(8)
                    statement.step()
                }
            connection
                .prepare(
                    "INSERT INTO items (id, game_id, name, notes, created_at, updated_at, deleted_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.bindText(1, itemId.toString())
                    statement.bindText(2, gameId.toString())
                    statement.bindText(3, "Token")
                    statement.bindNull(4)
                    statement.bindLong(5, EPOCH_MILLISECONDS_CREATED)
                    statement.bindLong(6, EPOCH_MILLISECONDS_CREATED)
                    statement.bindNull(7)
                    statement.step()
                }
        } finally {
            connection.close()
        }
    }

    private fun readVersion(): Long {
        val driver = BundledSQLiteDriver()
        val connection = driver.open(directory.databaseFile.toAbsolutePath().toString())
        return try {
            connection.prepare("PRAGMA user_version").use { statement ->
                statement.step()
                statement.getLong(0)
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `version 1 data survives the upgrade and the catalogue is seeded`() =
        runBlocking<Unit> {
            createVersion1DatabaseWithData()
            assertEquals(1L, readVersion(), "the fixture is not a version 1 database")

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                // Room validates the version 2 schema while opening; a mismatch throws here.
                val games = database.gameDao().allGamesIncludingDeleted()
                val items = database.itemDao().allItemsIncludingDeleted()

                assertEquals(1, games.size, "the migration lost the game row")
                val game = games.single()
                assertEquals(gameId, game.id)
                assertEquals("Harmonies", game.name)
                assertEquals("eski not", game.notes)
                assertTrue(game.isManuallyCompleted)
                assertEquals(EPOCH_MILLISECONDS_UPDATED, game.completedAt?.toEpochMilliseconds())
                assertEquals(EPOCH_MILLISECONDS_CREATED, game.createdAt.toEpochMilliseconds())
                assertEquals(EPOCH_MILLISECONDS_UPDATED, game.updatedAt.toEpochMilliseconds())

                assertEquals(1, items.size, "the migration lost the item row")
                val item = items.single()
                assertEquals(itemId, item.id)
                assertEquals(gameId, item.gameId)
                assertEquals("Token", item.name)
                assertEquals(EPOCH_MILLISECONDS_CREATED, item.createdAt.toEpochMilliseconds())
            } finally {
                database.close()
            }

            assertEquals(2L, readVersion())
        }

    @Test
    fun `the upgrade creates the four new tables with their indices and foreign keys`() =
        runBlocking<Unit> {
            createVersion1DatabaseWithData()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                database.gameDao().activeCount()
                val tables = queryTexts(database, "SELECT name FROM sqlite_master WHERE type = 'table'")
                val indices =
                    queryTexts(database, "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%'")

                listOf("games", "items", "colors", "color_aliases", "tasks", "task_colors").forEach { table ->
                    assertContains(tables, table)
                }
                listOf(
                    "index_colors_normalized_name",
                    "index_color_aliases_normalized_alias",
                    "index_tasks_item_id",
                    "index_tasks_pool_type",
                    "index_tasks_deleted_at",
                    "index_task_colors_color_id",
                ).forEach { index -> assertContains(indices, index) }

                assertEquals(
                    listOf("colors"),
                    foreignKeyTargets(database, "color_aliases"),
                )
                assertEquals(listOf("items"), foreignKeyTargets(database, "tasks"))
                assertEquals(listOf("colors", "tasks"), foreignKeyTargets(database, "task_colors").sorted())
            } finally {
                database.close()
            }
        }

    @Test
    fun `the upgrade seeds exactly the twelve colors and reopening does not repeat them`() =
        runBlocking<Unit> {
            createVersion1DatabaseWithData()

            val first = DatabaseFactory().open(directory.databaseFile)
            val seeded =
                try {
                    first.colorDao().allColorsIncludingArchived()
                } finally {
                    first.close()
                }

            assertEquals(12, seeded.size)
            assertEquals(seedColors.map { it.id }, seeded.map { it.id })
            assertEquals(seedColors.map { it.canonicalName }, seeded.map { it.canonicalName })
            assertEquals(seedColors.map { it.normalizedName }, seeded.map { it.normalizedName })
            assertEquals(seedColors.map { it.hex }, seeded.map { it.hex })
            assertEquals((0..11).toList(), seeded.map { it.sortOrder })
            assertTrue(seeded.none { it.isArchived })

            val second = DatabaseFactory().open(directory.databaseFile)
            try {
                assertEquals(12, second.colorDao().allColorsIncludingArchived().size)
                assertEquals(
                    "Açık Mavi",
                    assertNotNull(second.colorDao().colorByNormalizedName("açik mavi")).canonicalName,
                )
            } finally {
                second.close()
            }
        }

    private suspend fun queryTexts(
        database: AppDatabase,
        sql: String,
    ): List<String> =
        database.useReaderConnection { transactor ->
            transactor.usePrepared(sql) { statement ->
                buildList {
                    while (statement.step()) add(statement.getText(0))
                }
            }
        }

    private suspend fun foreignKeyTargets(
        database: AppDatabase,
        table: String,
    ): List<String> = queryTexts(database, "SELECT \"table\" FROM pragma_foreign_key_list('$table')")

    private class SchemaVersion(
        val createStatements: List<String>,
        val setupQueries: List<String>,
    )

    private fun readSchema(version: Int): SchemaVersion {
        val file = schemaDirectory().resolve("$version.json")
        val text = Files.readString(file)
        val createStatements = mutableListOf<String>()
        var currentTable = ""
        // Room writes each entity's "tableName" before the "createSql" entries that
        // belong to it, so tracking the latest name is enough to expand ${TABLE_NAME}.
        TABLE_OR_CREATE_SQL.findAll(text).forEach { match ->
            val (key, value) = match.destructured
            if (key == "tableName") {
                currentTable = value
            } else {
                createStatements += unescape(value).replace("\${TABLE_NAME}", currentTable)
            }
        }
        val setupQueries =
            SETUP_QUERIES
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.let { body -> QUOTED.findAll(body).map { unescape(it.groupValues[1]) }.toList() }
                .orEmpty()
        check(createStatements.isNotEmpty() && setupQueries.isNotEmpty()) {
            "could not read the version $version schema from $file"
        }
        return SchemaVersion(createStatements = createStatements, setupQueries = setupQueries)
    }

    private fun schemaDirectory(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .map { it.resolve("schemas/dev.pnptracker.data.database.AppDatabase") }
                .firstOrNull { Files.isDirectory(it) }
                ?.let { return it }
            candidate = candidate.parent
        }
        fail("Could not locate the committed schema directory from ${Path.of("").toAbsolutePath()}")
    }

    private fun unescape(raw: String): String = raw.replace("\\\"", "\"").replace("\\\\", "\\")

    private companion object {
        const val TABLE_NAME = "TABLE_NAME"

        val TABLE_OR_CREATE_SQL =
            Regex("\"(tableName|createSql)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val SETUP_QUERIES = Regex("\"setupQueries\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
        val QUOTED = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
