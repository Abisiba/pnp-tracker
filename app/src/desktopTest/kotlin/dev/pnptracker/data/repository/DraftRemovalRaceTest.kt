package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.DraftImport
import dev.pnptracker.data.database.PausingSqliteDriver
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aDraftImport
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.data.database.insertGameAndCell
import dev.pnptracker.data.database.soundnessProblemsOf
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.data.database.wholeDatabase
import dev.pnptracker.data.database.withoutDraft
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A removal meeting an edit, a confirmation, or another removal of the same draft.
 *
 * No sleeps and no hoping. The second operation is started from **inside** the
 * first one's transaction, at a chosen statement, on the database's own thread
 * ([PausingSqliteDriver]) — the pattern the restore's race test uses. Room keeps
 * one writer connection, so the second cannot begin its own transaction until
 * the first has committed, and every decision the second makes is made inside
 * that transaction from what the first left. Whatever the order, the batch ends
 * either wholly removed or wholly what the other operation made of it, and
 * SQLite's own checks find nothing wrong.
 */
class DraftRemovalRaceTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = PausingSqliteDriver()
    private var realDatabaseExistedBefore = false
    private lateinit var draft: DraftImport
    private var cellId: EntityId = IdGenerator.Random.newId()

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = driver).open(directory.databaseFile)
        runBlocking {
            fillWithEverything(database)
            cellId = insertGameAndCell(database, gameName = "Yarış").id
            draft = aDraftImport(database, blocks = 3, fingerprint = "%064x".format(3), coloursPerDraft = 1)
            // Ready to be confirmed: every draft aimed at a cell and given an amount.
            val importDao = database.importDao()
            draft.draftIds.forEach { id ->
                importDao.setDraftTargetUnderReview(id, cellId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH, updatedAt)
                importDao.editDraftTask(assertNotNull(importDao.draftTaskById(id)).copy(requiredQuantity = 6))
            }
        }
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val removal get() = ImportDraftRemovalStore(database.importDao())

    private val review get() = ImportReviewStore(database.importDao(), database.gameDao(), database.colorDao())

    private fun plain(sql: String) =
        sql
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase()

    private suspend fun anEdit(): DraftEdit {
        val colours = database.colorDao().allColors().map { it.id }
        return DraftEdit(
            draftTaskId = draft.draftIds.first(),
            name = "Yarışta düzenlendi",
            targetCellId = cellId,
            poolType = PoolType.THREE_D,
            trackingMode = TrackingMode.THREE_D_BATCH,
            requiredQuantity = 9,
            notes = null,
            isMissing = false,
            isBorrowed = false,
            needsInfo = false,
            needsClassification = false,
            completionHint = HintDecision.NONE,
            colorIds = listOf(colours[4], colours[5]),
        )
    }

    private suspend fun assertSound() = assertEquals(emptyList(), soundnessProblemsOf(database))

    @Test
    fun `an edit started while the removal holds the transaction finds the draft gone and writes nothing`() =
        runBlocking<Unit> {
            val before = wholeDatabase(database)
            val edit = anEdit()
            val edited = CompletableDeferred<Result<Boolean>>()

            coroutineScope {
                driver.interruptOnce(matches = { plain(it).startsWith("DELETE FROM IMPORT_BATCHES") }) {
                    async { edited.complete(runCatching { review.saveDraft(edit) }) }
                }
                assertEquals(DraftRemovalOutcome.Removed(draft.batchId, 3, 3, 3, 0), removal.remove(draft.batchId))
            }

            val refusal = assertIs<ImportReviewException>(edited.await().exceptionOrNull())
            assertEquals(ImportReviewFailure.DRAFT_TASK_NOT_FOUND, refusal.failure)
            assertEquals(before.withoutDraft(draft.batchId), wholeDatabase(database))
            assertSound()
        }

    @Test
    fun `a removal started while an edit holds the transaction removes the edited draft whole`() =
        runBlocking<Unit> {
            val before = wholeDatabase(database)
            val edit = anEdit()
            val removed = CompletableDeferred<DraftRemovalOutcome>()

            coroutineScope {
                driver.interruptOnce(matches = { "INTO DRAFT_TASK_COLORS" in plain(it) }) {
                    async { removed.complete(removal.remove(draft.batchId)) }
                }
                assertEquals(true, review.saveDraft(edit))
            }

            // The removal counted four colours: the edited draft's two new ones
            // and one each for the other two. So it read the edit whole, after
            // its commit — neither the old single colour nor half of the new list.
            assertEquals(DraftRemovalOutcome.Removed(draft.batchId, 3, 3, 4, 0), removed.await())
            // An edit touches only the draft's own rows, so once the draft is
            // gone the database is what it was before either of them, less the draft.
            assertEquals(before.withoutDraft(draft.batchId), wholeDatabase(database))
            assertSound()
        }

    @Test
    fun `a confirmation that already holds the transaction wins, and the removal is refused as no longer a draft`() =
        runBlocking<Unit> {
            val removed = CompletableDeferred<DraftRemovalOutcome>()

            coroutineScope {
                driver.interruptOnce(matches = { "INTO HISTORY_EVENTS" in plain(it) }) {
                    async { removed.complete(removal.remove(draft.batchId)) }
                }
                val result = confirmationStore(database).confirm(draft.batchId, acknowledgeUnprocessedBlocks = true)
                assertEquals(3, result.createdTaskCount)
            }
            val confirmed = wholeDatabase(database)

            assertEquals(DraftRemovalOutcome.Refused(draft.batchId, DraftRemovalRefusal.NOT_A_DRAFT), removed.await())
            assertEquals(ImportBatchStatus.CONFIRMED.name, confirmed.importBatches.single { it.id == draft.batchId.toString() }.status)
            // Confirmed completely: every draft became a task and nothing of the batch went.
            assertEquals(3, confirmed.draftTasks.count { it.id in draft.draftIds.map(EntityId::toString) && it.materializedTaskId != null })
            assertEquals(3, confirmed.rawImportBlocks.count { it.importBatchId == draft.batchId.toString() })
            assertSound()
        }

    @Test
    fun `a confirmation started while the removal holds the transaction is refused and writes nothing`() =
        runBlocking<Unit> {
            val before = wholeDatabase(database)
            // The snapshot is taken now, before the removal, the way the screen
            // takes it before it asks for the transaction. So the confirmation
            // is holding a picture of a database that is about to change.
            val snapshots = LiveSnapshotTaker(database, stale = true).also { it.takeBeforeImport() }
            val confirmed = CompletableDeferred<Result<*>>()

            coroutineScope {
                driver.interruptOnce(matches = { plain(it).startsWith("DELETE FROM IMPORT_BATCHES") }) {
                    async {
                        confirmed.complete(
                            runCatching {
                                confirmationStore(
                                    database,
                                    snapshots = snapshots,
                                ).confirm(draft.batchId, acknowledgeUnprocessedBlocks = true)
                            },
                        )
                    }
                }
                assertIs<DraftRemovalOutcome.Removed>(removal.remove(draft.batchId))
            }

            val refusal = assertIs<ImportConfirmationException>(confirmed.await().exceptionOrNull())
            assertEquals(ImportConfirmationFailure.DATA_CHANGED_MEANWHILE, refusal.failure)
            assertEquals(before.withoutDraft(draft.batchId), wholeDatabase(database), "the refused confirmation wrote something")
            assertSound()
        }

    @Test
    fun `a confirmation after the removal has committed finds no batch`() =
        runBlocking<Unit> {
            val before = wholeDatabase(database)
            assertIs<DraftRemovalOutcome.Removed>(removal.remove(draft.batchId))

            val refusal =
                runCatching { confirmationStore(database).confirm(draft.batchId, acknowledgeUnprocessedBlocks = true) }
                    .exceptionOrNull()

            assertEquals(ImportConfirmationFailure.BATCH_NOT_FOUND, assertIs<ImportConfirmationException>(refusal).failure)
            assertEquals(before.withoutDraft(draft.batchId), wholeDatabase(database))
            assertSound()
        }

    @Test
    fun `a second removal started inside the first gets the typed already removed`() =
        runBlocking<Unit> {
            val before = wholeDatabase(database)
            val second = CompletableDeferred<DraftRemovalOutcome>()

            coroutineScope {
                driver.interruptOnce(matches = { plain(it).startsWith("DELETE FROM IMPORT_BATCHES") }) {
                    async { second.complete(removal.remove(draft.batchId)) }
                }
                assertIs<DraftRemovalOutcome.Removed>(removal.remove(draft.batchId))
            }

            assertEquals(DraftRemovalOutcome.Refused(draft.batchId, DraftRemovalRefusal.ALREADY_REMOVED), second.await())
            assertEquals(before.withoutDraft(draft.batchId), wholeDatabase(database))
            assertSound()
        }

    @Test
    fun `many removals of one draft let loose together give exactly one removal`() =
        runBlocking<Unit> {
            // Nothing is arranged here at all: eight callers on eight threads.
            // Whichever order the scheduler picks, the answers are the same set.
            val before = wholeDatabase(database)

            val outcomes =
                (1..8)
                    .map { async(Dispatchers.IO) { removal.remove(draft.batchId) } }
                    .awaitAll()

            assertEquals(1, outcomes.count { it is DraftRemovalOutcome.Removed })
            assertEquals(
                List(7) { DraftRemovalOutcome.Refused(draft.batchId, DraftRemovalRefusal.ALREADY_REMOVED) },
                outcomes.filterIsInstance<DraftRemovalOutcome.Refused>(),
            )
            assertEquals(before.withoutDraft(draft.batchId), wholeDatabase(database))
            assertNull(database.importDao().batchById(draft.batchId))
            assertSound()
        }
}
