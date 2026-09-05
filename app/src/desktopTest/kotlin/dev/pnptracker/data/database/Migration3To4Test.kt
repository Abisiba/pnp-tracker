package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import dev.pnptracker.data.database.migration.UnconvertibleLegacyDataException
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The step that turns the item model into the table model.
 *
 * Two outcomes and no third: a version 3 database that holds nothing but colours
 * is carried across, and one that holds production records is refused whole. The
 * refusal is the point of most of what is below, because a migration that
 * silently dropped a restored backup would look exactly like a successful one.
 */
class Migration3To4Test {
    private lateinit var directory: TemporaryDatabaseDirectory
    private var realDatabaseExistedBefore = false

    private val gameId = IdGenerator.Random.newId()
    private val itemId = IdGenerator.Random.newId()
    private val taskId = IdGenerator.Random.newId()
    private val greyColorId = seedColors[2].id
    private val batchId = IdGenerator.Random.newId()
    private val blockId = IdGenerator.Random.newId()
    private val draftId = IdGenerator.Random.newId()

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

    /** A version 3 database as a fresh install leaves it: seeded, and nothing else. */
    private fun createSeededVersion3Database() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 3) { connection ->
            insertSeedColors(connection)
            insertVersion2ColorAlias(
                connection,
                colorId = greyColorId,
                alias = "GRI TON",
                normalizedAlias = "gri ton",
            )
        }
    }

    /** A version 3 database that someone actually used. */
    private fun createUsedVersion3Database() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 3) { connection ->
            insertVersion1Game(connection, gameId)
            insertVersion1Item(connection, itemId = itemId, gameId = gameId)
            insertSeedColors(connection)
            insertVersion2Task(connection, taskId = taskId, itemId = itemId)
            insertVersion2TaskColor(connection, taskId = taskId, colorId = greyColorId)
        }
    }

    /**
     * A version 3 database with an import in it and nothing else.
     *
     * This is the only shape that both passes the refusal check and has rows for
     * the migration to carry: reviewing an import creates no game and no task, so
     * a user who imported a file and closed the application before confirming
     * leaves exactly this behind. It is also the only `INSERT ... SELECT` in the
     * migration that a row can reach — `games` is copied too, but a database with
     * a game in it is refused before the copy runs.
     */
    private fun createVersion3DatabaseWithAnUnconfirmedImport() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 3) { connection ->
            insertSeedColors(connection)
            insertVersion3ImportBatch(connection, batchId)
            insertVersion3RawImportBlock(connection, blockId, batchId)
            insertVersion3DraftTask(connection, draftId, blockId)
        }
    }

    private fun version() = CommittedSchema.readVersion(directory.databaseFile)

    private fun rows(table: String) = CommittedSchema.countRowsOf(directory.databaseFile, table)

    @Test
    fun `a seeded version 3 database reaches the current version`() =
        runBlocking<Unit> {
            createSeededVersion3Database()
            assertEquals(3L, version(), "the fixture is not a version 3 database")

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                // Room opens the file on first use, so the migration has not run
                // until something is actually read.
                assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
                assertEquals(CURRENT_SCHEMA_VERSION, version())
            } finally {
                database.close()
            }
        }

    @Test
    fun `the twelve seed colours keep their identity and their order`() =
        runBlocking<Unit> {
            createSeededVersion3Database()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                val colours = database.colorDao().allColors()
                assertEquals(12, colours.size)
                assertEquals(seedColors.map { it.id }, colours.map { it.id })
                assertEquals(seedColors.map { it.canonicalName }, colours.map { it.canonicalName })
                assertEquals(seedColors.map { it.normalizedName }, colours.map { it.normalizedName })
                assertEquals(seedColors.map { it.hex }, colours.map { it.hex })
                assertEquals(seedColors.map { it.sortOrder }, colours.map { it.sortOrder })
            } finally {
                database.close()
            }
        }

    @Test
    fun `colour aliases are carried across`() =
        runBlocking<Unit> {
            createSeededVersion3Database()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                assertEquals(greyColorId, assertNotNull(database.colorDao().resolve("GRI TON")).id)
            } finally {
                database.close()
            }
        }

    @Test
    fun `a version 3 database holding production records is refused`() =
        runBlocking<Unit> {
            createUsedVersion3Database()

            val refusal =
                assertFailsWith<UnconvertibleLegacyDataException> {
                    val database = DatabaseFactory().open(directory.databaseFile)
                    try {
                        database.gameDao().activeCount()
                    } finally {
                        database.close()
                    }
                }

            assertEquals(1, refusal.gameCount)
            assertEquals(1, refusal.itemCount)
            assertEquals(1, refusal.taskCount)
            assertEquals(1, refusal.taskColorCount)
        }

    @Test
    fun `a refused database keeps its version, its tables and every row`() =
        runBlocking<Unit> {
            createUsedVersion3Database()

            assertFailsWith<UnconvertibleLegacyDataException> {
                val database = DatabaseFactory().open(directory.databaseFile)
                try {
                    database.gameDao().activeCount()
                } finally {
                    database.close()
                }
            }

            assertEquals(3L, version(), "a refused migration moved the version anyway")
            assertEquals(1, rows("games"))
            assertEquals(1, rows("items"), "a refused migration dropped the item table")
            assertEquals(1, rows("tasks"))
            assertEquals(1, rows("task_colors"))
            assertEquals(12, rows("colors"))
        }

    @Test
    fun `an unconfirmed import is carried across whole`() =
        runBlocking<Unit> {
            createVersion3DatabaseWithAnUnconfirmedImport()

            val database = DatabaseFactory().open(directory.databaseFile)
            val draft =
                try {
                    val batch = assertNotNull(database.importDao().batchById(batchId))
                    assertEquals("Kitap1(1).xlsx", batch.fileName)
                    assertEquals(ImportBatchStatus.DRAFT, batch.status)

                    val block = assertNotNull(database.importDao().rawBlockById(blockId))
                    // The raw text is the record of what the file said; PLAN 11.3
                    // has it never quietly changed or lost.
                    assertEquals("15 KIRMIZI**\nBıçak ve kabza ayrı", block.rawText)
                    assertEquals("Sayfa1", block.sheetName)
                    assertEquals(3, block.rowIndex)
                    assertEquals(2, block.columnIndex)

                    assertNotNull(database.importDao().draftTaskById(draftId))
                } finally {
                    database.close()
                }

            // Every field of the draft, one by one. A copy that dropped a column
            // or transposed two would still leave the right number of rows.
            assertEquals(draftId, draft.id)
            assertEquals(blockId, draft.rawImportBlockId)
            assertEquals("Kırmızı token", draft.name)
            assertEquals(PoolType.CARD, draft.suggestedPoolType)
            assertEquals(PoolType.CARD, draft.selectedPoolType)
            assertEquals(TrackingMode.PIPELINE, draft.selectedTrackingMode)
            assertEquals(15, draft.requiredQuantity)
            assertEquals("Sayısına bakılacak", draft.notes)
            assertEquals(3, draft.selectionStartIndex)
            assertEquals(10, draft.selectionEndIndex)
            assertEquals(HintDecision.ACCEPTED, draft.completionHint)
            assertTrue(draft.isMissing)
            assertFalse(draft.isBorrowed)
            assertTrue(draft.needsInfo)
            assertFalse(draft.needsClassification)

            // The two that are deliberately let go of: an item can no longer be
            // meant, and the task a draft was turned into cannot exist here.
            assertNull(draft.targetCellId, "a target survived that can no longer be meant")
            assertNull(draft.materializedTaskId)

            assertEquals(CURRENT_SCHEMA_VERSION, version())
            assertEquals(12, rows("colors"))
            assertEquals(0, rows("game_cells"))
            assertEquals(0, rows("cell_segments"))
        }

    @Test
    fun `an unconfirmed import leaves no foreign key dangling after the walk`() =
        runBlocking<Unit> {
            createVersion3DatabaseWithAnUnconfirmedImport()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                val violations =
                    database.useReaderConnection { transactor ->
                        transactor.usePrepared("PRAGMA foreign_key_check") { statement ->
                            buildList { while (statement.step()) add(statement.getText(0)) }
                        }
                    }
                assertEquals(emptyList(), violations)
            } finally {
                database.close()
            }
        }

    @Test
    fun `the item table is gone and the cell tables are there`() =
        runBlocking<Unit> {
            createSeededVersion3Database()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                val tables =
                    database.useReaderConnection { transactor ->
                        transactor.usePrepared("SELECT name FROM sqlite_master WHERE type = 'table'") { statement ->
                            buildList { while (statement.step()) add(statement.getText(0)) }
                        }
                    }
                assertFalse(tables.contains("items"), "the item table survived the migration")
                assertTrue(tables.contains("game_cells"))
                assertTrue(tables.contains("cell_segments"))
            } finally {
                database.close()
            }
        }

    @Test
    fun `the migrated database has no foreign key left dangling`() =
        runBlocking<Unit> {
            createSeededVersion3Database()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                val violations =
                    database.useReaderConnection { transactor ->
                        transactor.usePrepared("PRAGMA foreign_key_check") { statement ->
                            buildList { while (statement.step()) add(statement.getText(0)) }
                        }
                    }
                assertEquals(emptyList(), violations)
            } finally {
                database.close()
            }
        }

    @Test
    fun `a database created straight at the current version opens and reopens`() =
        runBlocking<Unit> {
            val first = DatabaseFactory().open(directory.databaseFile)
            val seeded =
                try {
                    first.colorDao().allColors()
                } finally {
                    first.close()
                }
            assertEquals(CURRENT_SCHEMA_VERSION, version())

            val second = DatabaseFactory().open(directory.databaseFile)
            try {
                assertEquals(seeded, second.colorDao().allColors(), "reopening changed the catalogue")
                assertEquals(emptyList(), second.taskDao().allTasksIncludingDeleted())
            } finally {
                second.close()
            }
        }
}
