package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aDraftImport
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.data.database.executeRawSql
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * What classifying a draft costs, counted at the driver.
 *
 * PLAN 16 rules out a cost per raw cell or per draft, and a classifier invites
 * one twice: "for each draft, read its cell" for the selections, and "for each
 * cell, check its column" for the hints. Here a draft of one cell and one of
 * forty-two cells with two drafts each — sound, and then with every selection
 * running past its text so the one query that returns rows returns all of them
 * — run the very same three reads, the same number of times, and write nothing.
 */
class DraftHealthQueryCountTest {
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
                    statement == "SELECT * FROM IMPORT_BATCHES WHERE ID = ?" -> "SELECT the batch"
                    "AS HINT_OUTSIDE_GAME_COUNT" in statement -> "SELECT the six counts"
                    "AS SELECTION_END_INDEX" in statement -> "SELECT the selections past SQLite's length"
                    else -> "other: $statement"
                }
            }

    /**
     * The classification's own statements and how often each ran.
     *
     * The top-level `BEGIN` and `COMMIT` are left out, as in the removal's count:
     * Room's invalidation tracker runs a short transaction of its own on its own
     * schedule. The classification's transaction is checked by order instead.
     */
    private fun ran(recorded: List<String>): Map<String, Int> =
        named(recorded)
            .filterNot { it.startsWith("BEGIN") || it == "COMMIT" }
            .groupingBy { it }
            .eachCount()

    private var lastRecording: List<String> = emptyList()

    private suspend fun classifying(batchId: EntityId): Pair<DraftHealth, Map<String, Int>> {
        driver.start()
        val health = database.importDao().draftHealthOf(batchId)
        lastRecording = driver.stop()
        return health to ran(lastRecording)
    }

    private suspend fun aDraft(blocks: Int) =
        aDraftImport(database, blocks, "%064x".format(fingerprints++), draftsPerBlock = 2, coloursPerDraft = 1).batchId

    private suspend fun everySelectionPastItsText(batchId: EntityId) =
        executeRawSql(
            database,
            """
            UPDATE draft_tasks SET selection_start_index = 0, selection_end_index = 500
            WHERE raw_import_block_id IN (SELECT id FROM raw_import_blocks WHERE import_batch_id = ?)
            """.trimIndent(),
            batchId.toString(),
        )

    private val expected =
        mapOf(
            "SELECT the batch" to 1,
            "SELECT the six counts" to 1,
            "SELECT the selections past SQLite's length" to 1,
        )

    @Test
    fun `a draft of one cell and one of forty-two cells run the same three reads`() =
        runBlocking<Unit> {
            val one = aDraft(blocks = 1)
            val many = aDraft(blocks = 42)

            val (oneHealth, oneCost) = classifying(one)
            val statements = named(lastRecording)
            val firstRead = statements.indexOf("SELECT the batch")
            assertEquals(
                "BEGIN IMMEDIATE TRANSACTION",
                statements.subList(0, firstRead).lastOrNull { it.startsWith("BEGIN") || it == "COMMIT" },
                "the three reads were not taken in one transaction: $statements",
            )
            val (manyHealth, manyCost) = classifying(many)

            assertIs<DraftHealth.Sound>(oneHealth)
            assertIs<DraftHealth.Sound>(manyHealth)
            assertEquals(expected, oneCost)
            assertEquals(oneCost, manyCost, "the cost of classifying a draft grew with the draft")
        }

    @Test
    fun `eighty-four selections past their text cost what one does`() =
        runBlocking<Unit> {
            val one = aDraft(blocks = 1)
            val many = aDraft(blocks = 42)
            everySelectionPastItsText(one)
            everySelectionPastItsText(many)

            val (oneHealth, oneCost) = classifying(one)
            val (manyHealth, manyCost) = classifying(many)

            assertIs<DraftHealth.Contradicting>(oneHealth)
            assertIs<DraftHealth.Contradicting>(manyHealth)
            assertEquals(expected, oneCost)
            assertEquals(oneCost, manyCost)
        }

    @Test
    fun `an import that is not a draft, or not there, is not classified at all`() =
        runBlocking<Unit> {
            val confirmed = anImportBatch(status = ImportBatchStatus.CONFIRMED, rawBlockCount = 3)
            database.importDao().insertBatch(confirmed)

            val (notADraft, confirmedCost) = classifying(confirmed.id)
            val (notFound, missingCost) = classifying(IdGenerator.Random.newId())

            assertIs<DraftHealth.NotADraft>(notADraft)
            assertIs<DraftHealth.NotFound>(notFound)
            assertEquals(mapOf("SELECT the batch" to 1), confirmedCost)
            assertEquals(mapOf("SELECT the batch" to 1), missingCost)
        }

    @Test
    fun `a confirmation looks twice at a draft of one cell and at one of forty-two`() =
        runBlocking<Unit> {
            // Two looks and never more, whatever the draft holds. These drafts are
            // not aimed, so the confirmation is refused after both looks — which
            // is exactly when the second look's cost can be counted alone.
            suspend fun looksIn(batchId: EntityId): Int {
                driver.start()
                assertFailsWith<ImportConfirmationException> { confirmationStore(database).confirm(batchId, true) }
                return driver.stop().count { "AS HINT_OUTSIDE_GAME_COUNT" in it.uppercase() }
            }

            assertEquals(2, looksIn(aDraft(blocks = 1)))
            assertEquals(2, looksIn(aDraft(blocks = 42)))
        }
}
