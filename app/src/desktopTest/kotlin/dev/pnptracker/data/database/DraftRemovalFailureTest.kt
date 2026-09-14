package dev.pnptracker.data.database

import dev.pnptracker.data.repository.ImportDraftRemovalStore
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * What is left when a removal cannot finish.
 *
 * PLAN 11.4.5: one transaction, no partial result. Every failure below is made
 * where the transaction really reads or writes, against a real SQLite file —
 * before it has decided, after it has counted, at the delete itself, half way
 * through the cascade the delete sets off, and in each read of the
 * postcondition — and every time the whole database reads exactly as it did,
 * the answer is the typed "could not be saved", and asking again succeeds.
 */
class DraftRemovalFailureTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val failing = FailingSqliteDriver()
    private val pausing = PausingSqliteDriver(delegate = failing)
    private var realDatabaseExistedBefore = false
    private lateinit var draft: DraftImport

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = pausing).open(directory.databaseFile)
        runBlocking {
            fillWithEverything(database)
            aDraftImport(database, blocks = 2, fingerprint = "%064x".format(7), fileName = "komşu.csv")
            draft = aDraftImport(database, blocks = 12, fingerprint = "%064x".format(8), draftsPerBlock = 2)
        }
    }

    @AfterTest
    fun closeDatabase() {
        failing.disarm()
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val store get() = ImportDraftRemovalStore(database.importDao())

    /** Room's SQL, uppercased and with its quoting and spacing taken out. */
    private fun plain(sql: String) =
        sql
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase()

    /**
     * Arms a failure, removes, and proves nothing moved; then disarms and proves
     * the same removal goes through, so the failure left nothing in its way.
     */
    private fun refusedWithNothingChanged(
        occurrence: Int = 1,
        matches: (String) -> Boolean,
    ) = runBlocking<Unit> {
        val before = wholeDatabase(database)
        failing.failOn(occurrence) { matches(plain(it)) }

        val outcome = store.remove(draft.batchId)

        failing.disarm()
        assertEquals(DraftRemovalOutcome.Refused(draft.batchId, DraftRemovalRefusal.COULD_NOT_SAVE), outcome)
        assertEquals(before, wholeDatabase(database), "a failed removal left part of its work behind")
        assertEquals(emptyList(), soundnessProblemsOf(database))

        assertIs<DraftRemovalOutcome.Removed>(store.remove(draft.batchId))
        assertEquals(before.withoutDraft(draft.batchId), wholeDatabase(database))
    }

    @Test
    fun `reading the batch fails`() = refusedWithNothingChanged { it == "SELECT * FROM IMPORT_BATCHES WHERE ID = ?" }

    @Test
    fun `looking for what holds the draft fails`() = refusedWithNothingChanged { "AS HOLDING_GAME_COUNT" in it }

    @Test
    fun `counting the tables before the delete fails`() = refusedWithNothingChanged { "AS IMPORT_BATCH_CELLS" in it }

    @Test
    fun `the delete itself fails`() = refusedWithNothingChanged { it.startsWith("DELETE FROM IMPORT_BATCHES") }

    @Test
    fun `reading back what is left fails after the delete`() = refusedWithNothingChanged(occurrence = 2) { "AS HOLDING_GAME_COUNT" in it }

    @Test
    fun `counting the tables after the delete fails`() = refusedWithNothingChanged(occurrence = 2) { "AS IMPORT_BATCH_CELLS" in it }

    @Test
    fun `the cascade is stopped half way through`() =
        runBlocking<Unit> {
            // The delete is one statement, so a driver cannot stop it in the
            // middle. SQLite can: this trigger lets the first draft colours go
            // and aborts on a later one, when rows of the draft have already
            // been removed inside the statement.
            executeRawSql(
                database,
                """
                CREATE TEMP TRIGGER stops_half_way BEFORE DELETE ON draft_task_colors
                WHEN (SELECT COUNT(*) FROM draft_task_colors) < 30
                BEGIN SELECT RAISE(ABORT, 'stopped half way'); END
                """.trimIndent(),
            )
            val before = wholeDatabase(database)
            check(before.draftTaskColors.size > 30) { "the draft is meant to have more colours than the trap lets go" }

            val outcome = store.remove(draft.batchId)

            assertEquals(DraftRemovalOutcome.Refused(draft.batchId, DraftRemovalRefusal.COULD_NOT_SAVE), outcome)
            assertEquals(before, wholeDatabase(database))
            assertEquals(emptyList(), soundnessProblemsOf(database))
        }

    @Test
    fun `a programming error inside the transaction is not turned into could not be saved`() =
        runBlocking<Unit> {
            val before = wholeDatabase(database)
            pausing.interruptOnce(matches = { plain(it).startsWith("DELETE FROM IMPORT_BATCHES") }) {
                throw IllegalStateException("a defect, not a storage refusal")
            }

            val defect = assertFailsWith<IllegalStateException> { store.remove(draft.batchId) }

            assertEquals("a defect, not a storage refusal", defect.message)
            assertEquals(before, wholeDatabase(database))
        }

    @Test
    fun `an invariant failure after the delete is not masked either`() =
        runBlocking<Unit> {
            val before = wholeDatabase(database)
            var reads = 0
            // The second count of the tables is the postcondition's, after the delete.
            pausing.interruptOnce(matches = { "AS IMPORT_BATCH_CELLS" in plain(it) && ++reads == 2 }) {
                throw IllegalArgumentException("an argument nobody should have passed")
            }

            assertFailsWith<IllegalArgumentException> { store.remove(draft.batchId) }

            assertEquals(before, wholeDatabase(database))
        }
}
