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
 * Upgrades a real version 2 database to version 3, and walks the whole chain from
 * version 1. Both fixtures are built from the committed schema files.
 */
class Migration2To3Test {
    private lateinit var directory: TemporaryDatabaseDirectory
    private var realDatabaseExistedBefore = false

    private val gameId = IdGenerator.Random.newId()
    private val itemId = IdGenerator.Random.newId()
    private val taskId = IdGenerator.Random.newId()
    private val greyColorId = seedColors.single { it.canonicalName == "Gri" }.id

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

    /** A version 2 database holding only its colours and their aliases. */
    private fun createVersion2DatabaseWithColoursOnly() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 2) { connection ->
            insertSeedColors(connection)
            insertVersion2ColorAlias(
                connection,
                colorId = greyColorId,
                alias = "GRI TON",
                normalizedAlias = "gri ton",
            )
        }
    }

    /** A version 2 database holding one of everything the version 2 schema knows. */
    private fun createVersion2DatabaseWithData() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 2) { connection ->
            insertVersion1Game(connection, gameId)
            insertVersion1Item(connection, itemId = itemId, gameId = gameId)
            insertSeedColors(connection)
            insertVersion2Task(connection, taskId = taskId, itemId = itemId)
            insertVersion2TaskColor(connection, taskId = taskId, colorId = greyColorId)
            insertVersion2ColorAlias(
                connection,
                colorId = greyColorId,
                alias = "GRI TON",
                normalizedAlias = "gri ton",
            )
        }
    }

    private suspend fun queryTexts(
        database: AppDatabase,
        sql: String,
    ): List<String> =
        database.useReaderConnection { transactor ->
            transactor.usePrepared(sql) { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
        }

    private suspend fun foreignKeyTargets(
        database: AppDatabase,
        table: String,
    ): List<String> = queryTexts(database, "SELECT \"table\" FROM pragma_foreign_key_list('$table')").sorted()

    @Test
    fun `the colours and aliases of a version 2 database survive the walk to the current version`() =
        runBlocking<Unit> {
            createVersion2DatabaseWithColoursOnly()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                val colours = database.colorDao().allColors()
                assertEquals(6L, CommittedSchema.readVersion(directory.databaseFile))
                assertEquals(seedColors.map { it.id }, colours.map { it.id })
                assertEquals(seedColors.map { it.canonicalName }, colours.map { it.canonicalName })
                assertEquals(seedColors.map { it.hex }, colours.map { it.hex })
                assertEquals(seedColors.map { it.sortOrder }, colours.map { it.sortOrder })
                assertEquals(
                    greyColorId,
                    assertNotNull(database.colorDao().resolve("GRI TON")).id,
                    "an alias did not survive the upgrade",
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun `a version 2 database holding tasks is refused and keeps every row`() =
        runBlocking<Unit> {
            createVersion2DatabaseWithData()

            assertFailsWith<UnconvertibleLegacyDataException> {
                val database = DatabaseFactory().open(directory.databaseFile)
                try {
                    database.gameDao().activeCount()
                } finally {
                    database.close()
                }
            }

            assertEquals(2L, CommittedSchema.readVersion(directory.databaseFile))
            assertEquals(1, CommittedSchema.countRowsOf(directory.databaseFile, "games"))
            assertEquals(1, CommittedSchema.countRowsOf(directory.databaseFile, "items"))
            assertEquals(1, CommittedSchema.countRowsOf(directory.databaseFile, "tasks"))
            assertEquals(1, CommittedSchema.countRowsOf(directory.databaseFile, "task_colors"))
            assertEquals(12, CommittedSchema.countRowsOf(directory.databaseFile, "colors"))
        }

    @Test
    fun `the walk adds the import tables and the cell tables with their keys`() =
        runBlocking<Unit> {
            createVersion2DatabaseWithColoursOnly()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                val tables = queryTexts(database, "SELECT name FROM sqlite_master WHERE type = 'table'")
                listOf(
                    "import_batches",
                    "raw_import_blocks",
                    "draft_tasks",
                    "game_cells",
                    "cell_segments",
                ).forEach { table -> assertContains(tables, table) }
                assertFalse(tables.contains("items"), "the item table survived the walk")

                // A raw cell points at its batch, and — since version 6 — at the game an
                // accepted green hint was said to be about.
                assertEquals(listOf("games", "import_batches"), foreignKeyTargets(database, "raw_import_blocks"))
                assertEquals(
                    listOf("game_cells", "raw_import_blocks", "tasks"),
                    foreignKeyTargets(database, "draft_tasks"),
                )
                assertEquals(listOf("games"), foreignKeyTargets(database, "game_cells"))
                assertEquals(listOf("game_cells", "tasks"), foreignKeyTargets(database, "cell_segments"))
            } finally {
                database.close()
            }
        }

    @Test
    fun `a version 1 database can be walked all the way to the current version`() =
        runBlocking<Unit> {
            CommittedSchema.createDatabase(directory.databaseFile, version = 1) { }

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                assertEquals(12, database.colorDao().allColors().size)
                assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
                assertEquals(6L, CommittedSchema.readVersion(directory.databaseFile))
            } finally {
                database.close()
            }
        }
}
