package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import dev.pnptracker.data.database.migration.UnconvertibleLegacyDataException
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

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

    /** A version 1 database with the tables but none of the rows. */
    private fun createEmptyVersion1Database() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 1) { }
    }

    private fun readVersion(): Long = CommittedSchema.readVersion(directory.databaseFile)

    private fun countRowsOf(table: String): Int = CommittedSchema.countRowsOf(directory.databaseFile, table)

    @Test
    fun `a version 1 database holding games is refused, and keeps every row`() =
        runBlocking<Unit> {
            createVersion1DatabaseWithData()
            assertEquals(1L, readVersion(), "the fixture is not a version 1 database")

            // The item model is withdrawn and a version 1 task carries no place in
            // a cell, so the chain stops at 3 to 4 rather than guessing one.
            assertFailsWith<UnconvertibleLegacyDataException> {
                val database = DatabaseFactory().open(directory.databaseFile)
                try {
                    database.gameDao().activeCount()
                } finally {
                    database.close()
                }
            }

            assertEquals(1L, readVersion(), "a refused upgrade moved the version anyway")
            assertEquals(1, countRowsOf("games"), "a refused upgrade dropped a game")
            assertEquals(1, countRowsOf("items"), "a refused upgrade dropped an item")
        }

    @Test
    fun `walking an empty version 1 database up creates the v4 tables, indices and keys`() =
        runBlocking<Unit> {
            createEmptyVersion1Database()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                database.gameDao().activeCount()
                val tables = queryTexts(database, "SELECT name FROM sqlite_master WHERE type = 'table'")
                val indices =
                    queryTexts(database, "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%'")

                listOf(
                    "games",
                    "game_cells",
                    "cell_segments",
                    "colors",
                    "color_aliases",
                    "tasks",
                    "task_colors",
                ).forEach { table -> assertContains(tables, table) }
                assertFalse(tables.contains("items"), "the item table survived the upgrade")
                listOf(
                    "index_colors_normalized_name",
                    "index_color_aliases_normalized_alias",
                    "index_game_cells_game_id_column_type",
                    "index_cell_segments_cell_id_order_index",
                    "index_cell_segments_task_id",
                    "index_tasks_pool_type",
                    "index_tasks_deleted_at",
                    "index_task_colors_color_id",
                    "index_task_colors_task_id_slot_index",
                ).forEach { index -> assertContains(indices, index) }

                assertEquals(listOf("colors"), foreignKeyTargets(database, "color_aliases"))
                assertEquals(listOf("games"), foreignKeyTargets(database, "game_cells"))
                assertEquals(listOf("game_cells", "tasks"), foreignKeyTargets(database, "cell_segments").sorted())
                assertEquals(listOf("raw_import_blocks"), foreignKeyTargets(database, "tasks").sorted())
                assertEquals(listOf("colors", "tasks"), foreignKeyTargets(database, "task_colors").sorted())
            } finally {
                database.close()
            }
        }

    @Test
    fun `the upgrade seeds exactly the twelve colors and reopening does not repeat them`() =
        runBlocking<Unit> {
            createEmptyVersion1Database()

            val first = DatabaseFactory().open(directory.databaseFile)
            val seeded =
                try {
                    first.colorDao().allColors()
                } finally {
                    first.close()
                }

            assertEquals(12, seeded.size)
            assertEquals(seedColors.map { it.id }, seeded.map { it.id })
            assertEquals(seedColors.map { it.canonicalName }, seeded.map { it.canonicalName })
            assertEquals(seedColors.map { it.normalizedName }, seeded.map { it.normalizedName })
            assertEquals(seedColors.map { it.hex }, seeded.map { it.hex })
            assertEquals((0..11).toList(), seeded.map { it.sortOrder })

            val second = DatabaseFactory().open(directory.databaseFile)
            try {
                assertEquals(12, second.colorDao().allColors().size)
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
