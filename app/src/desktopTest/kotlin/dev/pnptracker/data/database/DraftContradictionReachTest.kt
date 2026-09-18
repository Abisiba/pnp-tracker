package dev.pnptracker.data.database

import dev.pnptracker.AppInfo
import dev.pnptracker.data.repository.ImportDraftRemovalStore
import dev.pnptracker.data.repository.LiveSnapshotTaker
import dev.pnptracker.data.repository.confirmationStore
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupImportBatchCellRow
import dev.pnptracker.domain.backup.BackupImportBatchRow
import dev.pnptracker.domain.backup.backupDocumentOf
import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.BackupRejection
import dev.pnptracker.domain.backup.restore.FakeBackupInput
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importhealth.DraftContradiction
import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.importhealth.importRecordsHealthIn
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.platform.recovery.aReadyDraftImport
import dev.pnptracker.ui.feature.settings.RecordingHousekeeping
import dev.pnptracker.ui.feature.settings.aSafetySnapshot
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

private val WRITTEN_AT = Instant.fromEpochMilliseconds(1_757_320_364_031)

/**
 * Whether each of PLAN 11.4.5's nine contradictions can really reach a live
 * database — measured, one at a time, through the restore a person can run.
 *
 * The code reading behind the list said "yes, through a restored backup". That
 * is a claim about the reader, its throwaway database and the live replace, so
 * it is asked of exactly those: a sound database is read into a canonical
 * backup, one contradiction and nothing else is put into it, the checksum is
 * computed again by the format's own function, and the document goes through
 * the real [UntrustedBackupReader] with the real [TemporaryBackupProbe] and then
 * the real [LiveBackupRestorer]. Rows written straight into SQLite are not
 * evidence of anything a user can reach, and none are written here.
 *
 * The measured answer, for all nine: **the live database takes it.** Every test
 * below proves that and then what follows from it — the classifier names that
 * contradiction and no other, the confirmation refuses before any backup is
 * taken and writes nothing, and the removal does what PLAN 11.4.5 says (refused
 * where a real task or game points at the draft, otherwise removed whole).
 *
 * One control shows the measurement can see a refusal at all: a pointer to a
 * task that does not exist is turned away by the reader.
 */
class DraftContradictionReachTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var applicationData: Path
    private val probeRoots = mutableListOf<Path>()
    private var realDatabaseExistedBefore = false
    private lateinit var live: AppDatabase
    private var batchId: EntityId = IdGenerator.Random.newId()
    private lateinit var base: BackupData

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        applicationData = Files.createTempDirectory("pnp-tracker-reach-data")
        live = DatabaseFactory().open(directory.databaseFile)
        runBlocking {
            // A task of its own in a game of its own, for a draft to be wrongly
            // tied to, and one draft import built the way a person builds one:
            // saved, a draft cut out of its first cell, aimed and ready.
            insertGameCellAndTask(live, gameName = "Wingspan")
            batchId = aReadyDraftImport(live)
            base = wholeDatabase(live)
        }
    }

    @AfterTest
    fun closeDatabase() {
        live.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        probeRoots.forEach { check(Files.notExists(it)) { "the probe left $it behind" } }
        Files.delete(applicationData)
        directory.delete()
    }

    /** What the real restore line did with one document. */
    private sealed interface Reach {
        data class Refused(
            val rejection: BackupRejection,
        ) : Reach

        data object Live : Reach
    }

    /**
     * Carries [data] through the whole line and says where it stopped.
     *
     * When it is not stopped, the live database must read back as [data] row for
     * row and SQLite's own checks must find nothing — otherwise the restore
     * would have changed what it was given, and the measurement would mean
     * nothing.
     */
    private suspend fun measure(data: BackupData): Reach {
        val bytes = backupDocumentOf(data, AppInfo.Current.version, 8, WRITTEN_AT).json.encodeToByteArray()
        val reader =
            UntrustedBackupReader(
                TemporaryBackupProbe(
                    temporaryDirectory = { Files.createTempDirectory("pnp-tracker-reach-probe").also { probeRoots.add(it) } },
                    applicationDataDirectory = { applicationData },
                ),
            )
        return when (val read = reader.read(FakeBackupInput(bytes))) {
            is BackupReadResult.Refused -> Reach.Refused(read.rejection)
            is BackupReadResult.Valid -> {
                assertEquals(null, LiveBackupRestorer(live).restore(read.backup, aSafetySnapshot(wholeDatabase(live))))
                assertEquals(data, wholeDatabase(live), "the live database is not what was restored")
                assertEquals(emptyList(), soundnessProblemsOf(live))
                Reach.Live
            }
        }
    }

    private val batch get() = batchId.toString()

    private val blocks get() = base.rawImportBlocks.filter { it.importBatchId == batch }

    private fun BackupData.withBatch(change: (BackupImportBatchRow) -> BackupImportBatchRow) =
        copy(importBatches = importBatches.map { if (it.id == batch) change(it) else it })

    /**
     * The whole measurement for one contradiction.
     *
     * [mutate] must change exactly the rows the contradiction is about; the
     * classifier's answer being that one contradiction alone is what shows it did.
     */
    private fun reaches(
        expected: DraftContradiction,
        mutate: (BackupData) -> BackupData,
    ) = runBlocking<Unit> {
        val damaged = mutate(base)
        check(damaged != base) { "the mutation changed nothing" }

        // 1. The real line carries it into the live database.
        assertEquals(Reach.Live, measure(damaged))

        // 1b. The document, held against the same definitions in memory, says
        // the same as the live classifier below — the restore gate's answer.
        assertEquals(setOf(expected), importRecordsHealthIn(damaged).single { it.batchId == batch }.draft)

        // 2. The classifier names it, and only it, and says so every time.
        val importDao = live.importDao()
        val health = DraftHealth.Contradicting(batchId, setOf(expected))
        assertEquals(health, importDao.draftHealthOf(batchId))
        assertEquals(health, importDao.draftHealthOf(batchId))
        assertEquals(damaged, wholeDatabase(live), "asking wrote something")

        // 3. The confirmation refuses before the backup, and writes nothing.
        val snapshots = LiveSnapshotTaker(live)
        val housekeeping = RecordingHousekeeping()
        val refusal =
            assertFailsWith<ImportConfirmationException> {
                confirmationStore(live, snapshots = snapshots, housekeeping = housekeeping)
                    .confirm(batchId, acknowledgeUnprocessedBlocks = true)
            }
        assertEquals(ImportConfirmationFailure.RECORDS_CONTRADICT_EACH_OTHER, refusal.failure)
        assertEquals(setOf(expected), refusal.contradictions)
        assertEquals(0, snapshots.taken, "a backup was taken for a draft that could never be confirmed")
        assertEquals(emptyList(), housekeeping.askedAbout, "rotation ran for a refused confirmation")
        assertEquals(damaged, wholeDatabase(live), "the refused confirmation wrote something")
        assertEquals(ImportBatchStatus.DRAFT, importDao.batchById(batchId)?.status)

        // 4. The removal engine of Dilim 2, by PLAN 11.4.5's rule.
        val removal = ImportDraftRemovalStore(importDao).remove(batchId)
        if (expected == DraftContradiction.TASK_SOURCED_FROM_DRAFT || expected == DraftContradiction.GAME_SOURCED_FROM_DRAFT) {
            assertEquals(DraftRemovalOutcome.Refused(batchId, DraftRemovalRefusal.HELD_BY_RECORDS), removal)
            assertEquals(damaged, wholeDatabase(live))
        } else {
            assertIs<DraftRemovalOutcome.Removed>(removal)
            assertEquals(damaged.withoutDraft(batchId), wholeDatabase(live))
            assertEquals(DraftHealth.NotFound(batchId), importDao.draftHealthOf(batchId))
        }
        assertEquals(emptyList(), soundnessProblemsOf(live))
    }

    @Test
    fun `the canonical backup goes through untouched, sound, and confirmable`() =
        runBlocking<Unit> {
            assertEquals(Reach.Live, measure(base))
            assertEquals(DraftHealth.Sound(batchId), live.importDao().draftHealthOf(batchId))

            // The fixture really is ready: had it not been, every refusal below
            // could be one of the confirmation's own checks instead of the gate.
            val snapshots = LiveSnapshotTaker(live)
            val result = confirmationStore(live, snapshots = snapshots).confirm(batchId, acknowledgeUnprocessedBlocks = true)
            assertEquals(1, result.createdTaskCount)
            assertEquals(1, snapshots.taken)
            assertEquals(DraftHealth.NotADraft(batchId, ImportBatchStatus.CONFIRMED), live.importDao().draftHealthOf(batchId))
        }

    @Test
    fun `the measurement sees a refusal when the reader makes one`() =
        runBlocking<Unit> {
            val dangling = IdGenerator.Random.newId().toString()
            val broken = base.copy(draftTasks = base.draftTasks.map { it.copy(materializedTaskId = dangling) })

            val reach = assertIs<Reach.Refused>(measure(broken))

            assertEquals(BackupProblem.BROKEN_REFERENCE, reach.rejection.problem)
            assertEquals(base, wholeDatabase(live), "a refused backup reached the live database")
        }

    @Test
    fun `D1 a task count on a draft reaches the live database`() =
        reaches(DraftContradiction.TASKS_COUNTED_BEFORE_CONFIRMATION) { data -> data.withBatch { it.copy(createdTaskCount = 1) } }

    @Test
    fun `D2 a game count on a draft reaches the live database`() =
        reaches(DraftContradiction.GAMES_COUNTED_BEFORE_CONFIRMATION) { data -> data.withBatch { it.copy(createdGameCount = 1) } }

    @Test
    fun `D3 a raw cell count above the real one reaches the live database`() =
        reaches(DraftContradiction.RAW_CELL_COUNT_DISAGREES) { data -> data.withBatch { it.copy(rawBlockCount = it.rawBlockCount + 1) } }

    @Test
    fun `D3 a raw cell count below the real one reaches the live database`() =
        reaches(DraftContradiction.RAW_CELL_COUNT_DISAGREES) { data -> data.withBatch { it.copy(rawBlockCount = it.rawBlockCount - 1) } }

    @Test
    fun `D4 a draft naming the task it became reaches the live database`() =
        reaches(DraftContradiction.DRAFT_ALREADY_MATERIALIZED) { data ->
            data.copy(draftTasks = data.draftTasks.map { it.copy(materializedTaskId = data.tasks.single().id) })
        }

    @Test
    fun `D5 a cell snapshot of a draft reaches the live database`() =
        reaches(DraftContradiction.CELL_RECORDED_BEFORE_CONFIRMATION) { data ->
            data.copy(importBatchCells = listOf(BackupImportBatchCellRow(batch, data.gameCells.first().id, "")))
        }

    @Test
    fun `D6 a task sourced from a draft's raw cell reaches the live database`() =
        reaches(DraftContradiction.TASK_SOURCED_FROM_DRAFT) { data ->
            data.copy(tasks = data.tasks.map { it.copy(sourceRawImportBlockId = blocks.last().id) })
        }

    @Test
    fun `D6 holds for a deleted task too`() =
        reaches(DraftContradiction.TASK_SOURCED_FROM_DRAFT) { data ->
            data.copy(tasks = data.tasks.map { it.copy(sourceRawImportBlockId = blocks.last().id, deletedAt = it.updatedAt) })
        }

    @Test
    fun `D7 a game sourced from a draft reaches the live database`() =
        reaches(DraftContradiction.GAME_SOURCED_FROM_DRAFT) { data ->
            data.copy(games = data.games.map { if (it.name == "Wingspan") it.copy(sourceImportBatchId = batch) else it })
        }

    @Test
    fun `D7 holds for a deleted game too`() =
        reaches(DraftContradiction.GAME_SOURCED_FROM_DRAFT) { data ->
            data.copy(
                games =
                    data.games.map {
                        if (it.name ==
                            "Harmonies"
                        ) {
                            it.copy(sourceImportBatchId = batch, deletedAt = it.updatedAt)
                        } else {
                            it
                        }
                    },
            )
        }

    @Test
    fun `D8 a selection ending past its text reaches the live database`() =
        reaches(DraftContradiction.SELECTION_BEYOND_ITS_TEXT) { data ->
            data.copy(
                draftTasks =
                    data.draftTasks.map { draft ->
                        val text = data.rawImportBlocks.single { it.id == draft.rawImportBlockId }.rawText
                        draft.copy(selectionEndIndex = text.length + 1)
                    },
            )
        }

    @Test
    fun `D9 an unanswered completion hint outside the game column reaches the live database`() =
        reaches(DraftContradiction.COMPLETION_HINT_OUTSIDE_GAME_COLUMN) { data ->
            data.copy(
                rawImportBlocks =
                    data.rawImportBlocks.map {
                        if (it.id ==
                            blocks.last().id
                        ) {
                            it.copy(gameCompletionHint = HintDecision.PENDING.name)
                        } else {
                            it
                        }
                    },
            )
        }

    @Test
    fun `D9 an accepted completion hint with a game outside the game column reaches the live database`() =
        reaches(DraftContradiction.COMPLETION_HINT_OUTSIDE_GAME_COLUMN) { data ->
            val game = data.games.first().id
            data.copy(
                rawImportBlocks =
                    data.rawImportBlocks.map {
                        if (it.id ==
                            blocks.last().id
                        ) {
                            it.copy(gameCompletionHint = HintDecision.ACCEPTED.name, completionTargetGameId = game)
                        } else {
                            it
                        }
                    },
            )
        }
}
