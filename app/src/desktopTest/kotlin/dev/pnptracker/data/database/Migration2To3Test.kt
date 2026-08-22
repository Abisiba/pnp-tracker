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
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `every version 2 row survives the upgrade unchanged`() =
        runBlocking<Unit> {
            createVersion2DatabaseWithData()
            assertEquals(2L, CommittedSchema.readVersion(directory.databaseFile))

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                // Room validates the version 3 schema while opening; a mismatch throws here.
                val game = database.gameDao().allGamesIncludingDeleted().single()
                val item = database.itemDao().allItemsIncludingDeleted().single()
                val task = assertNotNull(database.taskDao().taskByIdIncludingArchivedAndDeleted(taskId))
                val taskColors = database.taskColorDao().colorsOfTask(taskId)
                val colors = database.colorDao().allColors()

                assertEquals(gameId, game.id)
                assertEquals("Harmonies", game.name)
                assertEquals("eski not", game.notes)
                assertTrue(game.isManuallyCompleted)
                assertEquals(EPOCH_MILLISECONDS_UPDATED, game.completedAt?.toEpochMilliseconds())
                assertEquals(EPOCH_MILLISECONDS_CREATED, game.createdAt.toEpochMilliseconds())

                assertEquals(itemId, item.id)
                assertEquals(gameId, item.gameId)
                assertEquals("Token", item.name)

                assertEquals(itemId, task.itemId)
                assertEquals("Gri token", task.name)
                assertEquals(14, task.requiredQuantity)
                assertEquals(EPOCH_MILLISECONDS_CREATED, task.createdAt.toEpochMilliseconds())

                assertEquals(1, taskColors.size)
                assertEquals(greyColorId, taskColors.single().colorId)
                assertTrue(taskColors.single().isSelected)

                assertEquals(12, colors.size, "the colour catalogue must not grow during the upgrade")
                assertEquals(seedColors.map { it.id }, colors.map { it.id })
                assertEquals(greyColorId, assertNotNull(database.colorDao().resolve("GRI TON")).id)
            } finally {
                database.close()
            }

            assertEquals(3L, CommittedSchema.readVersion(directory.databaseFile))
        }

    @Test
    fun `existing rows get a null import source`() =
        runBlocking<Unit> {
            createVersion2DatabaseWithData()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                val game = database.gameDao().allGamesIncludingDeleted().single()
                val task = assertNotNull(database.taskDao().taskByIdIncludingArchivedAndDeleted(taskId))

                assertNull(game.sourceImportBatchId, "a game that predates importing has no source batch")
                assertNull(task.sourceRawImportBlockId, "a task that predates importing has no source cell")
            } finally {
                database.close()
            }
        }

    @Test
    fun `the upgrade adds the import tables with their indices and foreign keys`() =
        runBlocking<Unit> {
            createVersion2DatabaseWithData()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                database.gameDao().activeCount()
                val tables = queryTexts(database, "SELECT name FROM sqlite_master WHERE type = 'table'")
                val indices =
                    queryTexts(
                        database,
                        "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%'",
                    )

                listOf("import_batches", "raw_import_blocks", "draft_tasks").forEach { assertContains(tables, it) }
                listOf(
                    "index_import_batches_sha256",
                    "index_import_batches_status",
                    "index_raw_import_blocks_import_batch_id",
                    "index_raw_import_blocks_is_processed",
                    "index_raw_import_blocks_import_batch_id_sheet_name_row_index_column_index",
                    "index_draft_tasks_raw_import_block_id",
                    "index_draft_tasks_target_item_id",
                    "index_draft_tasks_materialized_task_id",
                    "index_games_source_import_batch_id",
                    "index_tasks_source_raw_import_block_id",
                ).forEach { index -> assertContains(indices, index) }

                assertEquals(listOf("import_batches"), foreignKeyTargets(database, "raw_import_blocks"))
                assertEquals(
                    listOf("items", "raw_import_blocks", "tasks"),
                    foreignKeyTargets(database, "draft_tasks"),
                )
                assertEquals(listOf("import_batches"), foreignKeyTargets(database, "games"))
                assertEquals(listOf("items", "raw_import_blocks"), foreignKeyTargets(database, "tasks"))
                assertEquals(emptyList(), database.importDao().allBatches())
            } finally {
                database.close()
            }
        }

    @Test
    fun `a version 1 database can be walked all the way to version 3`() =
        runBlocking<Unit> {
            CommittedSchema.createDatabase(directory.databaseFile, version = 1) { connection ->
                insertVersion1Game(connection, gameId)
                insertVersion1Item(connection, itemId = itemId, gameId = gameId)
            }
            assertEquals(1L, CommittedSchema.readVersion(directory.databaseFile))

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                val game = database.gameDao().allGamesIncludingDeleted().single()
                val item = database.itemDao().allItemsIncludingDeleted().single()
                val tables = queryTexts(database, "SELECT name FROM sqlite_master WHERE type = 'table'")

                assertEquals(gameId, game.id)
                assertEquals("Harmonies", game.name)
                assertNull(game.sourceImportBatchId)
                assertEquals(itemId, item.id)
                assertEquals(gameId, item.gameId)
                assertEquals(12, database.colorDao().allColors().size)
                listOf("colors", "color_aliases", "tasks", "task_colors", "import_batches", "raw_import_blocks", "draft_tasks")
                    .forEach { assertContains(tables, it) }
            } finally {
                database.close()
            }

            assertEquals(3L, CommittedSchema.readVersion(directory.databaseFile))
        }
}
