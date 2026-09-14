package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.PausingSqliteDriver
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.executeRawSql
import dev.pnptracker.data.database.soundnessProblemsOf
import dev.pnptracker.data.database.wholeDatabase
import dev.pnptracker.domain.backup.automatic.AutomaticSnapshot
import dev.pnptracker.domain.backup.automatic.AutomaticSnapshotTaker
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importhealth.DraftContradiction
import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.platform.recovery.aReadyDraftImport
import dev.pnptracker.ui.feature.settings.RecordingHousekeeping
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The two looks a confirmation takes at whether a draft's records agree.
 *
 * PLAN 11.4.5: a damaged draft cannot be confirmed, the confirmation checks
 * before the automatic backup and again inside its transaction, and a
 * confirmation with no chance of succeeding takes no backup (PLAN 14.4.8). Each
 * test drives the real store against a real database, and every refusal is held
 * to the same three facts: the typed failure, whether a backup was taken, and
 * the whole database reading as it did.
 *
 * The damage is written with SQL. That the nine contradictions really reach a
 * live database is measured through the restore in `DraftContradictionReachTest`;
 * here the only question is what the gates do once one is there.
 */
class ImportConfirmationGateTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val failing = FailingSqliteDriver()
    private val pausing = PausingSqliteDriver(delegate = failing)
    private var realDatabaseExistedBefore = false
    private var batchId: EntityId = IdGenerator.Random.newId()

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = pausing).open(directory.databaseFile)
        runBlocking { batchId = aReadyDraftImport(database) }
    }

    @AfterTest
    fun closeDatabase() {
        failing.disarm()
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val housekeeping = RecordingHousekeeping()

    private fun plain(sql: String) =
        sql
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase()

    /** The classifier's count query: one per look. */
    private fun isTheLook(sql: String) = "AS HINT_OUTSIDE_GAME_COUNT" in plain(sql)

    private suspend fun countTheTasks() =
        executeRawSql(database, "UPDATE import_batches SET created_task_count = 2 WHERE id = ?", batchId.toString())

    private suspend fun confirming(snapshots: AutomaticSnapshotTaker): ImportConfirmationException =
        assertFailsWith<ImportConfirmationException> {
            confirmationStore(database, snapshots = snapshots, housekeeping = housekeeping)
                .confirm(batchId, acknowledgeUnprocessedBlocks = true)
        }

    private suspend fun assertStillADraft() {
        assertEquals(ImportBatchStatus.DRAFT, assertNotNull(database.importDao().batchById(batchId)).status)
        assertEquals(emptyList(), soundnessProblemsOf(database))
    }

    /** A snapshot taker that does something to the database around a real snapshot. */
    private class Around(
        private val real: LiveSnapshotTaker,
        private val before: suspend () -> Unit = {},
        private val after: suspend () -> Unit = {},
    ) : AutomaticSnapshotTaker {
        override suspend fun takeBeforeImport(): AutomaticSnapshot {
            before()
            return real.takeBeforeImport().also { after() }
        }
    }

    @Test
    fun `a sound draft is looked at twice and confirmed as before`() =
        runBlocking<Unit> {
            var looks = 0
            pausing.interruptOnce(matches = { isTheLook(it) && ++looks == 3 }) { error("a third look") }
            val snapshots = LiveSnapshotTaker(database)

            val result = confirmationStore(database, snapshots = snapshots, housekeeping = housekeeping).confirm(batchId, true)

            assertEquals(1, result.createdTaskCount)
            assertEquals(2, looks, "the draft was not looked at once before the backup and once inside the transaction")
            assertEquals(1, snapshots.taken)
            assertEquals(ImportBatchStatus.CONFIRMED, database.importDao().batchById(batchId)?.status)
        }

    @Test
    fun `a contradiction found before the backup takes no backup and writes nothing`() =
        runBlocking<Unit> {
            countTheTasks()
            val before = wholeDatabase(database)
            val snapshots = LiveSnapshotTaker(database)

            val refusal = confirming(snapshots)

            assertEquals(ImportConfirmationFailure.RECORDS_CONTRADICT_EACH_OTHER, refusal.failure)
            assertEquals(setOf(DraftContradiction.TASKS_COUNTED_BEFORE_CONFIRMATION), refusal.contradictions)
            assertEquals(null, refusal.draftTaskId)
            assertEquals(0, snapshots.taken, "a backup was taken for a draft that cannot be confirmed")
            assertEquals(emptyList(), housekeeping.askedAbout, "rotation ran")
            assertEquals(before, wholeDatabase(database))
            assertStillADraft()
        }

    @Test
    fun `asking again is refused again, the same way, and still takes no backup`() =
        runBlocking<Unit> {
            countTheTasks()
            val before = wholeDatabase(database)
            val snapshots = LiveSnapshotTaker(database)

            repeat(3) {
                assertEquals(ImportConfirmationFailure.RECORDS_CONTRADICT_EACH_OTHER, confirming(snapshots).failure)
            }

            assertEquals(0, snapshots.taken)
            assertEquals(before, wholeDatabase(database))
        }

    @Test
    fun `records that come to contradict each other after the first look are caught inside the transaction`() =
        runBlocking<Unit> {
            // Between the first look and the backup: the backup then describes
            // the damaged database, so the fifteen-table guard passes, and only
            // the second look can stop it.
            val snapshots = LiveSnapshotTaker(database)
            val damagedBetween = Around(snapshots, before = { countTheTasks() })

            val refusal = confirming(damagedBetween)

            assertEquals(ImportConfirmationFailure.RECORDS_CONTRADICT_EACH_OTHER, refusal.failure)
            assertEquals(setOf(DraftContradiction.TASKS_COUNTED_BEFORE_CONFIRMATION), refusal.contradictions)
            assertNotNull(refusal.cause, "the refusal did not come from inside the transaction")
            assertEquals(1, snapshots.taken)
            // Nothing written: the database is exactly the one that was backed up.
            val backedUp = snapshots.takeBeforeImport().data
            assertEquals(backedUp, wholeDatabase(database), "the refused transaction wrote something")
            assertStillADraft()
        }

    @Test
    fun `records damaged after the backup are still stopped by the guard that was there before`() =
        runBlocking<Unit> {
            val snapshots = LiveSnapshotTaker(database, stale = true)
            val damagedAfter = Around(snapshots, after = { countTheTasks() })

            val refusal = confirming(damagedAfter)

            assertEquals(ImportConfirmationFailure.DATA_CHANGED_MEANWHILE, refusal.failure)
            assertEquals(emptySet(), refusal.contradictions)
            assertStillADraft()
        }

    @Test
    fun `every contradiction is refused the same way, together`() =
        runBlocking<Unit> {
            val block =
                database
                    .importDao()
                    .rawBlocksOfBatch(batchId)
                    .last()
                    .id
                    .toString()
            executeRawSql(
                database,
                "UPDATE import_batches SET created_game_count = 1, raw_block_count = 99 WHERE id = ?",
                batchId.toString(),
            )
            executeRawSql(database, "UPDATE raw_import_blocks SET game_completion_hint = 'PENDING' WHERE id = ?", block)
            val before = wholeDatabase(database)
            val snapshots = LiveSnapshotTaker(database)

            val refusal = confirming(snapshots)

            assertEquals(
                setOf(
                    DraftContradiction.GAMES_COUNTED_BEFORE_CONFIRMATION,
                    DraftContradiction.RAW_CELL_COUNT_DISAGREES,
                    DraftContradiction.COMPLETION_HINT_OUTSIDE_GAME_COLUMN,
                ),
                refusal.contradictions,
            )
            assertEquals(0, snapshots.taken)
            assertEquals(before, wholeDatabase(database))
        }

    @Test
    fun `a storage failure at the first look is could not save, before any backup`() =
        runBlocking<Unit> {
            val before = wholeDatabase(database)
            val snapshots = LiveSnapshotTaker(database)
            failing.failOn(1) { isTheLook(it) }

            val refusal = confirming(snapshots)

            failing.disarm()
            assertEquals(ImportConfirmationFailure.COULD_NOT_SAVE, refusal.failure)
            assertEquals(0, snapshots.taken)
            assertEquals(before, wholeDatabase(database))
            assertEquals(DraftHealth.Sound(batchId), database.importDao().draftHealthOf(batchId))
        }

    @Test
    fun `a storage failure at the second look is could not save, with nothing written`() =
        runBlocking<Unit> {
            val before = wholeDatabase(database)
            val snapshots = LiveSnapshotTaker(database)
            failing.failOn(2) { isTheLook(it) }

            val refusal = confirming(snapshots)

            failing.disarm()
            assertEquals(ImportConfirmationFailure.COULD_NOT_SAVE, refusal.failure)
            assertEquals(1, snapshots.taken)
            assertEquals(before, wholeDatabase(database))
            assertStillADraft()
        }

    @Test
    fun `a programming error at the first look travels out as it is`() =
        runBlocking<Unit> {
            val before = wholeDatabase(database)
            val snapshots = LiveSnapshotTaker(database)
            pausing.interruptOnce(matches = { isTheLook(it) }) { throw IllegalStateException("a defect at the first look") }

            val defect =
                assertFailsWith<IllegalStateException> {
                    confirmationStore(database, snapshots = snapshots, housekeeping = housekeeping).confirm(batchId, true)
                }

            assertEquals("a defect at the first look", defect.message)
            assertEquals(0, snapshots.taken)
            assertEquals(before, wholeDatabase(database))
        }

    @Test
    fun `a programming error at the second look travels out as it is, with nothing written`() =
        runBlocking<Unit> {
            val before = wholeDatabase(database)
            val snapshots = LiveSnapshotTaker(database)
            var looks = 0
            pausing.interruptOnce(matches = { isTheLook(it) && ++looks == 2 }) {
                throw IllegalArgumentException("a defect at the second look")
            }

            val defect =
                assertFailsWith<IllegalArgumentException> {
                    confirmationStore(database, snapshots = snapshots, housekeeping = housekeeping).confirm(batchId, true)
                }

            assertEquals("a defect at the second look", defect.message)
            assertEquals(1, snapshots.taken)
            assertEquals(before, wholeDatabase(database))
            assertStillADraft()
        }

    @Test
    fun `the other refusals are left to the confirmation's own checks, as before`() =
        runBlocking<Unit> {
            val missing = IdGenerator.Random.newId()
            val gone =
                assertFailsWith<ImportConfirmationException> {
                    confirmationStore(database, housekeeping = housekeeping).confirm(missing, true)
                }
            assertEquals(ImportConfirmationFailure.BATCH_NOT_FOUND, gone.failure)

            confirmationStore(database, housekeeping = housekeeping).confirm(batchId, true)
            val twice =
                assertFailsWith<ImportConfirmationException> {
                    confirmationStore(database, housekeeping = housekeeping).confirm(batchId, true)
                }
            assertEquals(ImportConfirmationFailure.ALREADY_CONFIRMED, twice.failure)
            assertIs<DraftHealth.NotADraft>(database.importDao().draftHealthOf(batchId))
        }
}
