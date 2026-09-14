package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aDraftImport
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What removing a draft costs, counted at the driver.
 *
 * PLAN 16 rules out the shape where a draft of forty-two raw cells costs forty-two
 * round trips, and a removal invites it twice over: "read each cell, delete each
 * cell". Here a draft with no cells, one with one, and one with forty-two cells
 * of two drafts and three colours each run the very same statements, the same
 * number of times — the rows go by the schema's cascades inside one delete.
 *
 * Counted with a frequency map: a set would collapse repeated runs of one
 * statement into one and report a cost nobody paid.
 */
class DraftRemovalQueryCountTest {
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
        runBlocking { fillWithEverything(database) }
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val store get() = ImportDraftRemovalStore(database.importDao())

    /** Every statement run, in order, named by what it is. */
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
                    statement.startsWith("ROLLBACK") || statement.startsWith("SAVEPOINT") || statement.startsWith("RELEASE") -> statement
                    "CHANGES()" in statement -> "write result"
                    statement == "SELECT * FROM IMPORT_BATCHES WHERE ID = ?" -> "SELECT the batch"
                    "AS HOLDING_GAME_COUNT" in statement -> "SELECT the draft's own rows and what holds it"
                    "AS IMPORT_BATCH_CELLS" in statement -> "SELECT the fifteen table counts"
                    statement == "DELETE FROM IMPORT_BATCHES WHERE ID = ? AND STATUS = 'DRAFT'" -> "DELETE the batch"
                    else -> "unexpected: $statement"
                }
            }

    /**
     * What the removal itself ran, with how many times.
     *
     * The top-level `BEGIN` and `COMMIT` are left out of the tally. Room's
     * invalidation tracker opens a short transaction of its own after a write,
     * on its own schedule, and whether it lands inside the recording window is a
     * question about the machine rather than about the removal. The removal's own
     * transaction is checked by order instead, in [beginsImmediate].
     */
    private fun ran(recorded: List<String>): Map<String, Int> =
        named(recorded)
            .filterNot { it.startsWith("BEGIN") || it == "COMMIT" }
            .groupingBy { it }
            .eachCount()

    /** The write lock is taken before the removal's first read. */
    private fun beginsImmediate(recorded: List<String>): Boolean {
        val statements = named(recorded)
        val firstRead = statements.indexOf("SELECT the batch")
        // The last transaction marker before that read — a tracker transaction
        // that ran earlier has its own COMMIT, so it cannot be mistaken for this one.
        return firstRead > 0 &&
            statements.subList(0, firstRead).lastOrNull { it.startsWith("BEGIN") || it == "COMMIT" } == "BEGIN IMMEDIATE TRANSACTION"
    }

    private var lastRecording: List<String> = emptyList()

    private suspend fun removing(batchId: EntityId): Map<String, Int> {
        driver.start()
        val outcome = store.remove(batchId)
        lastRecording = driver.stop()
        assertIs<DraftRemovalOutcome>(outcome)
        return ran(lastRecording)
    }

    private suspend fun aDraft(
        blocks: Int,
        draftsPerBlock: Int = 1,
        coloursPerDraft: Int = 1,
    ) = aDraftImport(database, blocks, "%064x".format(fingerprints++), draftsPerBlock, coloursPerDraft).batchId

    @Test
    fun `a draft of no cells, one cell and forty-two cells runs the same six statements`() =
        runBlocking<Unit> {
            val empty = removing(aDraft(blocks = 0))
            val one = removing(aDraft(blocks = 1))
            // The transaction takes the write lock before its first read, so the
            // decision and the delete see the same database (measured, not assumed).
            assertEquals(true, beginsImmediate(lastRecording), "the removal did not begin immediate: ${named(lastRecording)}")
            val many = removing(aDraft(blocks = 42, draftsPerBlock = 2, coloursPerDraft = 3))

            val expected =
                mapOf(
                    "SELECT the batch" to 1,
                    "SELECT the draft's own rows and what holds it" to 2,
                    "SELECT the fifteen table counts" to 2,
                    "SAVEPOINT '1'" to 1,
                    "DELETE the batch" to 1,
                    // Room asks SQLite how many rows the delete took, for the count it returns.
                    "write result" to 1,
                    "RELEASE SAVEPOINT '1'" to 1,
                )
            assertEquals(expected, one)
            assertEquals(one, empty)
            assertEquals(one, many, "the cost of removing a draft grew with the draft")
        }

    @Test
    fun `a refusal asks only what it needs and writes nothing`() =
        runBlocking<Unit> {
            val removed = aDraft(blocks = 3)
            removing(removed)

            assertEquals(mapOf("SELECT the batch" to 1), removing(removed))
        }
}
