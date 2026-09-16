package dev.pnptracker.platform.diagnostics

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aDraftImport
import dev.pnptracker.data.database.executeRawSql
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.data.repository.ImportDraftRemovalStore
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.data.repository.LiveSnapshotTaker
import dev.pnptracker.data.repository.confirmationStore
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.automatic.AutomaticSnapshot
import dev.pnptracker.domain.backup.automatic.AutomaticSnapshotTaker
import dev.pnptracker.domain.backup.automatic.AutomaticSnapshotWriter
import dev.pnptracker.domain.backup.automatic.SnapshotNotTaken
import dev.pnptracker.domain.backup.automatic.SnapshotProblem
import dev.pnptracker.domain.backup.automatic.VerifiedSnapshotTaker
import dev.pnptracker.domain.backup.restore.BackupInput
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.importprep.ImportFileGateway
import dev.pnptracker.domain.importprep.ImportFileHandle
import dev.pnptracker.domain.importprep.ImportPreparationException
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.importrollback.ImportRollbackException
import dev.pnptracker.domain.importrollback.ImportRollbackFailure
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.backupfiles.PathBackupInput
import dev.pnptracker.platform.recovery.aReadyDraftImport
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreenState
import kotlinx.coroutines.runBlocking
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Clock

/**
 * Every import event of PLAN 14.7.2, made to happen for real and recorded once.
 *
 * The automatic backup that stands in front of a confirmation, the two looks at
 * whether a draft's records agree, a confirmation overtaken by a change, a
 * rollback whose batch does not say it made those tasks, a draft real records
 * hold, and a file the machine will not read. Each is driven through the
 * production boundary that decides it, and each leaves exactly one line holding
 * fixed names and numbers.
 */
class ImportFailureRecordsTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val failing = FailingSqliteDriver()
    private val diagnostics = RecordingDiagnostics()
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = failing).open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        failing.disarm()
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private fun assertNothingLeaked() =
        assertLinesCarryNothingOfTheUsers(
            diagnostics,
            "Kırmızı figür",
            "liste.csv",
            "taslak.csv",
            "Harmonies",
            directory.root.toString(),
            System.getProperty("user.name"),
        )

    private fun exporter() = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System)

    private fun taker(writer: AutomaticSnapshotWriter) =
        VerifiedSnapshotTaker(
            exporter = exporter(),
            writer = writer,
            reader = UntrustedBackupReader(TemporaryBackupProbe()),
            clock = Clock.System,
            diagnostics = diagnostics,
        )

    @Test
    fun `the automatic backup cannot be written`() =
        runBlocking {
            val refused =
                assertFailsWith<SnapshotNotTaken> {
                    taker(RefusingWriter(BackupFailure.NOT_WRITABLE)).takeBeforeImport()
                }

            assertEquals(SnapshotProblem.NOT_WRITTEN, refused.problem)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.IMPORT_SNAPSHOT_FAILED,
                    reason = SnapshotProblem.NOT_WRITTEN,
                    exception = "dev.pnptracker.domain.backup.BackupException",
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `the automatic backup cannot be read back, and says where the reading stopped`() =
        runBlocking {
            val refused =
                assertFailsWith<SnapshotNotTaken> {
                    taker(EmptyFileWriter(directory.root.resolve("pnp-otomatik-import-2026-01-01-000000.json"))).takeBeforeImport()
                }

            assertEquals(SnapshotProblem.NOT_VERIFIED, refused.problem)
            val record = diagnostics.only()
            assertRecordedAsPlanned(
                ExpectedRecord(DiagnosticEvent.IMPORT_SNAPSHOT_FAILED, reason = SnapshotProblem.NOT_VERIFIED),
                record,
            )
            assertEquals("file", record.place?.toString(), "the place the reader refused at")
            assertNothingLeaked()
        }

    @Test
    fun `the database cannot be read for the automatic backup`() =
        runBlocking {
            insertGameCellAndTask(database)
            failing.failOn { it.uppercase().contains("FROM GAMES") }
            val refused =
                assertFailsWith<SnapshotNotTaken> {
                    taker(RefusingWriter(BackupFailure.NOT_WRITABLE)).takeBeforeImport()
                }
            failing.disarm()

            assertEquals(SnapshotProblem.DATABASE_NOT_READ, refused.problem)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.IMPORT_SNAPSHOT_FAILED,
                    reason = SnapshotProblem.DATABASE_NOT_READ,
                    exception = "androidx.sqlite.SQLiteException",
                    cause = "androidx.sqlite.SQLiteException",
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `the data changed between the backup and the confirmation`() =
        runBlocking {
            val batchId = aReadyDraftImport(database)
            val snapshots = LiveSnapshotTaker(database, stale = true)
            snapshots.takeBeforeImport()
            insertGameCellAndTask(database, gameName = "Harmonies")

            val refused =
                assertFailsWith<ImportConfirmationException> {
                    confirmationStore(database, snapshots = snapshots, diagnostics = diagnostics)
                        .confirm(batchId, acknowledgeUnprocessedBlocks = true)
                }

            assertEquals(ImportConfirmationFailure.DATA_CHANGED_MEANWHILE, refused.failure)
            assertRecordedAsPlanned(ExpectedRecord(DiagnosticEvent.IMPORT_CHANGED_MEANWHILE), diagnostics.only())
            assertNothingLeaked()
        }

    @Test
    fun `the draft's own records contradict each other, at the first look`() =
        runBlocking {
            val batchId = aReadyDraftImport(database)
            executeRawSql(database, "UPDATE import_batches SET created_task_count = 1 WHERE id = ?", batchId.toString())
            val snapshots = LiveSnapshotTaker(database)

            val refused =
                assertFailsWith<ImportConfirmationException> {
                    confirmationStore(database, snapshots = snapshots, diagnostics = diagnostics)
                        .confirm(batchId, acknowledgeUnprocessedBlocks = true)
                }

            assertEquals(ImportConfirmationFailure.RECORDS_CONTRADICT_EACH_OTHER, refused.failure)
            assertEquals(0, snapshots.taken, "a draft that cannot be confirmed was backed up")
            assertRecordedAsPlanned(ExpectedRecord(DiagnosticEvent.IMPORT_RECORDS_CONTRADICT), diagnostics.only())
            assertNothingLeaked()
        }

    @Test
    fun `the draft's records come to contradict each other after the first look`() =
        runBlocking {
            val batchId = aReadyDraftImport(database)
            val snapshots =
                BreakingTaker(LiveSnapshotTaker(database)) {
                    executeRawSql(database, "UPDATE import_batches SET created_task_count = 1 WHERE id = ?", batchId.toString())
                }

            val refused =
                assertFailsWith<ImportConfirmationException> {
                    confirmationStore(database, snapshots = snapshots, diagnostics = diagnostics)
                        .confirm(batchId, acknowledgeUnprocessedBlocks = true)
                }

            assertEquals(ImportConfirmationFailure.RECORDS_CONTRADICT_EACH_OTHER, refused.failure)
            assertRecordedAsPlanned(ExpectedRecord(DiagnosticEvent.IMPORT_RECORDS_CONTRADICT), diagnostics.only())
            assertNothingLeaked()
        }

    @Test
    fun `taking an import back that its own records do not account for`() =
        runBlocking {
            val batchId = aConfirmedImport()
            executeRawSql(
                database,
                "UPDATE draft_tasks SET materialized_task_id = NULL WHERE raw_import_block_id IN " +
                    "(SELECT id FROM raw_import_blocks WHERE import_batch_id = ?)",
                batchId.toString(),
            )
            val store = ImportRollbackStore(database.importDao(), diagnostics = diagnostics)

            val preview = store.previewRollback(batchId)
            assertEquals(ImportRollbackFailure.PROVENANCE_BROKEN, preview.blockingFailure)
            assertRecordedAsPlanned(ExpectedRecord(DiagnosticEvent.IMPORT_ROLLBACK_PROVENANCE_BROKEN), diagnostics.only())
            diagnostics.forget()

            val refused = assertFailsWith<ImportRollbackException> { store.rollBack(batchId) }
            assertEquals(ImportRollbackFailure.PROVENANCE_BROKEN, refused.failure)
            assertRecordedAsPlanned(ExpectedRecord(DiagnosticEvent.IMPORT_ROLLBACK_PROVENANCE_BROKEN), diagnostics.only())
            assertNothingLeaked()
        }

    @Test
    fun `a draft real records are holding on to`() =
        runBlocking {
            val task = insertGameCellAndTask(database)
            val draft = aDraftImport(database, blocks = 1, fingerprint = "%064x".format(9))
            val blockId =
                database
                    .importDao()
                    .rawBlocksOfBatch(draft.batchId)
                    .first()
                    .id
            executeRawSql(
                database,
                "UPDATE tasks SET source_raw_import_block_id = ? WHERE id = ?",
                blockId.toString(),
                task.id.toString(),
            )

            val outcome = ImportDraftRemovalStore(database.importDao(), diagnostics).remove(draft.batchId)

            assertEquals(DraftRemovalOutcome.Refused(draft.batchId, DraftRemovalRefusal.HELD_BY_RECORDS), outcome)
            assertRecordedAsPlanned(ExpectedRecord(DiagnosticEvent.IMPORT_DRAFT_HELD_BY_RECORDS), diagnostics.only())
            assertNothingLeaked()
        }

    @Test
    fun `a file this machine will not read`() =
        runBlocking {
            val unreadable = ImportPreparationException(ImportFailure.NOT_READABLE)
            unreadable.initCause(AccessDeniedException("/home/birisi/gizli/liste.csv"))
            val controller = importController(RefusingGateway(unreadable))

            controller.chooseFile()

            assertIs<ImportScreenState.Failed>(controller.state)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.IMPORT_FILE_UNREADABLE,
                    reason = ImportFailure.NOT_READABLE,
                    exception = "java.nio.file.AccessDeniedException",
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `what the user chose wrongly, and what they cancelled, are not recorded`() =
        runBlocking {
            importController(RefusingGateway(ImportPreparationException(ImportFailure.UNSUPPORTED_FILE_TYPE))).chooseFile()
            importController(RefusingGateway(ImportPreparationException(ImportFailure.LEGACY_XLS_FILE))).chooseFile()
            importController(RefusingGateway(ImportPreparationException(ImportFailure.CSV_RAGGED_ROW))).chooseFile()
            importController(CancellingGateway()).chooseFile()

            assertEquals(emptyList(), diagnostics.records.map { it.event.code })
        }

    private fun importController(gateway: ImportFileGateway) =
        ImportController(
            gateway = gateway,
            store = ImportDraftStore(database.importDao()),
            diagnostics = diagnostics,
        )

    /** A confirmed import of this application's own making, tasks and all. */
    private suspend fun aConfirmedImport(): EntityId {
        val batchId = aReadyDraftImport(database)
        confirmationStore(database).confirm(batchId, acknowledgeUnprocessedBlocks = true)
        return batchId
    }
}

/** A writer that refuses, so the snapshot is never taken. */
private class RefusingWriter(
    private val failure: BackupFailure,
) : AutomaticSnapshotWriter {
    override suspend fun writeImportSnapshot(
        bytes: ByteArray,
        moment: LocalMoment,
    ): BackupInput = throw BackupException(failure)
}

/** A writer that writes an empty file, so what comes back is not a backup at all. */
private class EmptyFileWriter(
    private val file: Path,
) : AutomaticSnapshotWriter {
    override suspend fun writeImportSnapshot(
        bytes: ByteArray,
        moment: LocalMoment,
    ): BackupInput {
        Files.write(file, ByteArray(0))
        return PathBackupInput(file)
    }
}

/** Breaks the draft in the moment between the first look and the backup. */
private class BreakingTaker(
    private val real: AutomaticSnapshotTaker,
    private val breakIt: suspend () -> Unit,
) : AutomaticSnapshotTaker {
    override suspend fun takeBeforeImport(): AutomaticSnapshot {
        breakIt()
        return real.takeBeforeImport()
    }
}

/** A chooser that answers with one typed refusal. */
private class RefusingGateway(
    private val refusal: ImportPreparationException,
) : ImportFileGateway {
    override suspend fun chooseFile(): ImportFileHandle? = throw refusal
}

/** A chooser the user backed out of. */
private class CancellingGateway : ImportFileGateway {
    override suspend fun chooseFile(): ImportFileHandle? = null
}
