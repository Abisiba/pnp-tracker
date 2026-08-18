package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        CommittedSchema.createDatabase(directory.databaseFile, version = 1) { connection ->
            insertVersion1Game(connection, gameId)
            insertVersion1Item(connection, itemId = itemId, gameId = gameId)
        }
    }

    private fun readVersion(): Long = CommittedSchema.readVersion(directory.databaseFile)

    @Test
    fun `version 1 data survives the upgrade and the catalogue is seeded`() =
        runBlocking<Unit> {
            createVersion1DatabaseWithData()
            assertEquals(1L, readVersion(), "the fixture is not a version 1 database")

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                // Room validates the current schema while opening; a mismatch throws here.
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

            // Opening through the factory applies every migration it knows, not just this one.
            assertEquals(3L, readVersion())
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
                assertEquals(listOf("items", "raw_import_blocks"), foreignKeyTargets(database, "tasks").sorted())
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
}
