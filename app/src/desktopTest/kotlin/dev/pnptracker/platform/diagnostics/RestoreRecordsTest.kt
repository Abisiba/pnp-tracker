package dev.pnptracker.platform.diagnostics

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.LiveBackupRestorer
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.fillWithEverythingARestoreAccepts
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.FakeBackupInput
import dev.pnptracker.domain.backup.restore.RestoreProblem
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.restore.aWholeBackup
import dev.pnptracker.domain.backup.restore.documentOf
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.ui.feature.settings.FakeBackupSource
import dev.pnptracker.ui.feature.settings.FakeRestorer
import dev.pnptracker.ui.feature.settings.FakeSafetyWriter
import dev.pnptracker.ui.feature.settings.FakeSourceGateway
import dev.pnptracker.ui.feature.settings.RecordingHousekeeping
import dev.pnptracker.ui.feature.settings.RestoreController
import dev.pnptracker.ui.feature.settings.RestoreScreenState
import dev.pnptracker.ui.feature.settings.StoppedRestoreClock
import dev.pnptracker.ui.feature.settings.aRealBackupFile
import dev.pnptracker.ui.feature.settings.aSafetySnapshot
import dev.pnptracker.ui.feature.settings.aValidatedBackup
import dev.pnptracker.ui.feature.settings.storageRefusal
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Putting a backup back: what is recorded when the file is refused, when the way
 * back cannot be written, when the replacement does not happen, and when it does.
 *
 * The file and the safety backup are the screen's to decide, so the controller
 * records those. What happens inside the one transaction is only known to the
 * restorer, so the restorer records that — including the success, which is one of
 * the two PLAN 14.7.2 allows.
 */
class RestoreRecordsTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private val diagnostics = RecordingDiagnostics()
    private val failing = FailingSqliteDriver()
    private var realDatabaseExistedBefore = false
    private val opened = mutableListOf<AppDatabase>()

    @BeforeTest
    fun createDirectory() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
    }

    @AfterTest
    fun deleteDirectory() {
        failing.disarm()
        opened.forEach { it.close() }
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private fun assertNothingLeaked() =
        assertLinesCarryNothingOfTheUsers(
            diagnostics,
            "pnp-yedek-2026-09-08",
            "pnp-oncesi",
            "Harmonies",
            directory.root.toString(),
            System.getProperty("user.name"),
        )

    private fun controller(
        chosen: FakeBackupInput? = null,
        refuseChoosing: BackupFailure? = null,
        source: FakeBackupSource = FakeBackupSource(),
        safety: FakeSafetyWriter = FakeSafetyWriter(),
        restorer: FakeRestorer = FakeRestorer(),
    ) = RestoreController(
        sources = FakeSourceGateway(chosen, refuseChoosing),
        reader = UntrustedBackupReader(TemporaryBackupProbe()),
        exporter = DatabaseBackupExporter(source, AppInfo.Current, StoppedRestoreClock()),
        safety = safety,
        restorer = restorer,
        housekeeping = RecordingHousekeeping(),
        clock = StoppedRestoreClock(),
        diagnostics = diagnostics,
    )

    @Test
    fun `a file that is not a backup, and a dialog that answered with no file at all`() =
        runBlocking {
            val emptyFile = controller(chosen = FakeBackupInput(ByteArray(0)))

            emptyFile.chooseBackup()

            assertEquals(RestoreScreenState.Rejected(BackupProblem.EMPTY_FILE), emptyFile.state)
            val refusedFile = diagnostics.only()
            assertRecordedAsPlanned(
                ExpectedRecord(DiagnosticEvent.RESTORE_FILE_REFUSED, reason = BackupProblem.EMPTY_FILE),
                refusedFile,
            )
            assertEquals("file", refusedFile.place?.toString())
            diagnostics.forget()

            val noFile = controller(refuseChoosing = BackupFailure.NO_DESTINATION)

            noFile.chooseBackup()

            assertEquals(RestoreScreenState.Rejected(BackupProblem.UNREADABLE), noFile.state)
            assertRecordedAsPlanned(
                ExpectedRecord(DiagnosticEvent.RESTORE_FILE_REFUSED, reason = BackupProblem.UNREADABLE),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `a backup whose imports contradict their own records is refused before the question, once per choice`() =
        runBlocking {
            // PLAN 14.7.5 decision 2. A raw cell count that is not the number of
            // raw cells is D3 for a draft and U3 for anything else.
            val whole = aWholeBackup()
            val bytes = documentOf(whole.copy(importBatches = whole.importBatches.map { it.copy(rawBlockCount = it.rawBlockCount + 1) }))
            // The reader alone still takes it: it also verifies import snapshots
            // and migration sets, and those must keep working (it is not tightened).
            assertIs<BackupReadResult.Valid>(UntrustedBackupReader(TemporaryBackupProbe()).read(FakeBackupInput(bytes.encodeToByteArray())))
            val source = FakeBackupSource()
            val safety = FakeSafetyWriter()
            val restorer = FakeRestorer()

            repeat(2) {
                val chosen =
                    controller(chosen = FakeBackupInput(bytes.encodeToByteArray()), source = source, safety = safety, restorer = restorer)
                chosen.chooseBackup()
                assertEquals(RestoreScreenState.Rejected(BackupProblem.IMPORT_RECORDS_CONTRADICT), chosen.state)
            }

            // No question, no database read, no way back written, nothing replaced.
            assertEquals(0, source.reads)
            assertEquals(0, safety.writes)
            assertEquals(0, restorer.applied)
            assertEquals(2, diagnostics.records.size)
            diagnostics.records.forEach { record ->
                assertRecordedAsPlanned(
                    ExpectedRecord(DiagnosticEvent.RESTORE_FILE_REFUSED, reason = BackupProblem.IMPORT_RECORDS_CONTRADICT),
                    record,
                )
                assertEquals("importBatches", record.place?.toString())
            }
            assertNothingLeaked()
        }

    @Test
    fun `the way back cannot be read, and cannot be written`() =
        runBlocking {
            val unreadable = controller(chosen = aRealBackupInput(), source = FakeBackupSource(refusal = ::storageRefusal))
            unreadable.chooseBackup()
            assertIs<RestoreScreenState.Confirming>(unreadable.state)
            diagnostics.forget()

            unreadable.confirmRestore()

            assertEquals(
                RestoreScreenState.Failed(RestoreProblem.SAFETY_BACKUP_NOT_MADE, safetyFileName = null),
                unreadable.state,
            )
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.RESTORE_NOT_COMPLETED,
                    reason = RestoreProblem.SAFETY_BACKUP_NOT_MADE,
                    exception = "androidx.sqlite.SQLiteException",
                ),
                diagnostics.only(),
            )
            diagnostics.forget()

            val unwritable = controller(chosen = aRealBackupInput(), safety = FakeSafetyWriter(refuse = BackupFailure.NOT_WRITABLE))
            unwritable.chooseBackup()
            diagnostics.forget()

            unwritable.confirmRestore()

            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.RESTORE_NOT_COMPLETED,
                    reason = RestoreProblem.SAFETY_BACKUP_NOT_WRITTEN,
                    exception = "dev.pnptracker.domain.backup.BackupException",
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `the live replacement goes through, and says so once`() =
        runBlocking {
            val database = openFilled()
            val before = BackupStore(database).snapshot().data

            val problem = LiveBackupRestorer(database, diagnostics).restore(aValidatedBackup(), aSafetySnapshot(before))

            assertNull(problem)
            assertRecordedAsPlanned(ExpectedRecord(DiagnosticEvent.RESTORE_COMPLETED), diagnostics.only())
            assertNothingLeaked()
        }

    @Test
    fun `the data moved after the way back was written`() =
        runBlocking {
            val database = openFilled()
            val somethingElse: BackupData = BackupStore(database).snapshot().data.copy(games = emptyList())

            val problem = LiveBackupRestorer(database, diagnostics).restore(aValidatedBackup(), aSafetySnapshot(somethingElse))

            assertEquals(RestoreProblem.DATA_CHANGED_MEANWHILE, problem)
            assertRecordedAsPlanned(
                ExpectedRecord(DiagnosticEvent.RESTORE_NOT_COMPLETED, reason = RestoreProblem.DATA_CHANGED_MEANWHILE),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `storage refuses in the middle of the replacement`() =
        runBlocking {
            val database = openFilled()
            val before = BackupStore(database).snapshot().data
            failing.failOn { it.uppercase().startsWith("DELETE FROM") }

            val problem = LiveBackupRestorer(database, diagnostics).restore(aValidatedBackup(), aSafetySnapshot(before))

            failing.disarm()
            assertEquals(RestoreProblem.COULD_NOT_APPLY, problem)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.RESTORE_NOT_COMPLETED,
                    reason = RestoreProblem.COULD_NOT_APPLY,
                    exception = "androidx.sqlite.SQLiteException",
                    cause = "androidx.sqlite.SQLiteException",
                ),
                diagnostics.only(),
            )
            assertEquals(before, BackupStore(database).snapshot().data, "a refused restore left part of its work behind")
            assertNothingLeaked()
        }

    private fun aRealBackupInput(): FakeBackupInput = aRealBackupFile()

    private suspend fun openFilled(): AppDatabase =
        DatabaseFactory(driver = failing)
            .open(directory.databaseFile)
            .also {
                opened += it
                fillWithEverythingARestoreAccepts(it)
            }
}
