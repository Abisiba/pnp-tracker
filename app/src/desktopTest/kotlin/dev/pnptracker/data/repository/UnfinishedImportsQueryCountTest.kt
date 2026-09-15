package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aDraftImport
import dev.pnptracker.data.database.executeRawSql
import dev.pnptracker.domain.importhealth.DraftHealth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val SELECTIONS = "SELECT every selection past SQLite's length"

/**
 * What reading the list of unfinished imports costs, counted at the driver.
 *
 * The shape PLAN 16 rules out is the one a list invites most: a classification
 * per row. Here one draft and forty-two drafts of five raw cells and two drafts
 * each — every selection running past its text, so the one query that returns
 * rows returns all of them — run the very same three statements, the same
 * number of times, and write nothing.
 */
class UnfinishedImportsQueryCountTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false
    private var fingerprints = 0

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = driver).open(directory.databaseFile)
        // Opened, created and seeded now, so no recording catches the schema being made.
        runBlocking { database.importDao().draftBatches() }
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private fun named(recorded: List<String>): List<String> =
        recorded
            .filterNot { "room_table_modification" in it.lowercase() }
            .map {
                it
                    .replace("`", "")
                    .trim()
                    .uppercase()
                    .replace(Regex("\\s+"), " ")
            }.filterNot { it.startsWith("PRAGMA") }
            .map { statement ->
                when {
                    statement.startsWith("BEGIN") -> statement
                    statement.startsWith("COMMIT") || statement.startsWith("END") -> "COMMIT"
                    statement == "SELECT * FROM IMPORT_BATCHES WHERE STATUS = 'DRAFT' ORDER BY IMPORTED_AT DESC, ID" -> "SELECT the drafts"
                    "AS HINT_OUTSIDE_GAME_COUNT" in statement && "FROM IMPORT_BATCHES B" in statement -> "SELECT every draft's counts"
                    "AS SELECTION_END_INDEX" in statement && "JOIN IMPORT_BATCHES B" in statement -> SELECTIONS
                    else -> "other: $statement"
                }
            }

    /** The list's own statements; the top-level transaction markers are checked by order instead. */
    private fun ran(recorded: List<String>): Map<String, Int> =
        named(recorded)
            .filterNot { it.startsWith("BEGIN") || it == "COMMIT" }
            .groupingBy { it }
            .eachCount()

    private suspend fun drafts(count: Int) =
        repeat(count) {
            val draft =
                aDraftImport(database, blocks = 5, fingerprint = "%064x".format(fingerprints++), draftsPerBlock = 2, coloursPerDraft = 1)
            executeRawSql(
                database,
                "UPDATE draft_tasks SET selection_start_index = 0, selection_end_index = 500 WHERE raw_import_block_id IN " +
                    "(SELECT id FROM raw_import_blocks WHERE import_batch_id = ?)",
                draft.batchId.toString(),
            )
        }

    private val expected =
        mapOf(
            "SELECT the drafts" to 1,
            "SELECT every draft's counts" to 1,
            SELECTIONS to 1,
        )

    @Test
    fun `one draft and forty-two drafts are classified by the same three reads`() {
        runBlocking {
            drafts(1)
            driver.start()
            val one = database.importDao().healthOfDraftBatches()
            val oneRecording = driver.stop()

            drafts(41)
            driver.start()
            val many = database.importDao().healthOfDraftBatches()
            val manyRecording = driver.stop()

            assertEquals(1, one.size)
            assertEquals(42, many.size)
            assertEquals(true, many.all { it.health is DraftHealth.Contradicting })
            assertEquals(expected, ran(oneRecording))
            assertEquals(ran(oneRecording), ran(manyRecording), "classifying the list grew with the list")
            val statements = named(manyRecording)
            assertEquals(
                "BEGIN IMMEDIATE TRANSACTION",
                statements.subList(0, statements.indexOf("SELECT the drafts")).lastOrNull { it.startsWith("BEGIN") || it == "COMMIT" },
                "the three reads were not one transaction: $statements",
            )
        }
    }

    @Test
    fun `a reading of the list through the store costs the same three reads`() {
        runBlocking {
            drafts(42)
            val store = UnfinishedImportsStore(database, database.importDao())

            driver.start()
            val rows = store.observeUnfinishedImports().first()
            val recorded = driver.stop()

            assertEquals(42, rows.size)
            assertEquals(expected, ran(recorded))
        }
    }

    @Test
    fun `no drafts at all is one read`() {
        runBlocking {
            driver.start()
            assertEquals(emptyList(), database.importDao().healthOfDraftBatches())
            assertEquals(mapOf("SELECT the drafts" to 1), ran(driver.stop()))
        }
    }
}
