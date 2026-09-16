package dev.pnptracker.platform.diagnostics

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.executeRawSql
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.data.repository.TaskExportStore
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.BackupFileHandle
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.backupDocumentOf
import dev.pnptracker.domain.backup.restore.anEmptyBackup
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.BACKUP_HEADER_BYTES
import dev.pnptracker.domain.backup.retention.BackupDirectory
import dev.pnptracker.domain.backup.retention.InspectedBackupFile
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.backup.retention.automaticBackupNameOf
import dev.pnptracker.domain.backup.retention.importSnapshotFileName
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.export.ExportFailure
import dev.pnptracker.domain.export.ExportInvariant
import dev.pnptracker.domain.export.TaskExportException
import dev.pnptracker.domain.settings.SettingsNotSaved
import dev.pnptracker.domain.settings.SettingsProblem
import dev.pnptracker.domain.settings.SettingsWriteFailure
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.backupfiles.DesktopBackupFileGateway
import dev.pnptracker.platform.exportfiles.DesktopExportFileGateway
import dev.pnptracker.platform.settings.DesktopSettingsStore
import dev.pnptracker.ui.feature.settings.BackupController
import dev.pnptracker.ui.feature.settings.BackupScreenState
import dev.pnptracker.ui.feature.settings.FakeBackupSource
import dev.pnptracker.ui.feature.settings.StoppedRestoreClock
import dev.pnptracker.ui.feature.settings.storageRefusal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

private val WRITTEN_AT = Instant.fromEpochMilliseconds(1_757_320_364_031)

/**
 * What is recorded when a file this application writes will not be written, and
 * when a setting or an old backup will not either (PLAN 14.7.2).
 *
 * Every writing failure is made on a real disk: a folder the user cannot write
 * to, a settings file that is not the document this reads, an old backup that
 * will not go away. The records hold the failure's fixed name and the classes of
 * what refused, and never the name of a file or the folder it is in.
 */
class BackupFailureRecordsTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private val diagnostics = RecordingDiagnostics()
    private var realDatabaseExistedBefore = false
    private val opened = mutableListOf<AppDatabase>()
    private val unwritable = mutableListOf<Path>()

    @BeforeTest
    fun createDirectory() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
    }

    @AfterTest
    fun deleteDirectory() {
        opened.forEach { it.close() }
        unwritable.forEach { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    /** A folder this user may not write in, put back before the test's own cleanup. */
    private fun aFolderNobodyCanWriteIn(): Path {
        val folder = Files.createDirectory(directory.root.resolve("kilitli"))
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"))
        unwritable.add(folder)
        return folder
    }

    private fun assertNothingLeaked() =
        assertLinesCarryNothingOfTheUsers(
            diagnostics,
            "kilitli",
            "Harmonies",
            "Gri token",
            directory.root.toString(),
            System.getProperty("user.name"),
        )

    @Test
    fun `a backup the folder will not take`() =
        runBlocking {
            val target = aFolderNobodyCanWriteIn().resolve("pnp-yedek-2026-09-16.json")
            val gateway = DesktopBackupFileGateway(FixedBackupPicker(target), diagnostics = diagnostics)
            val handle = assertIs<BackupFileHandle>(gateway.chooseDestination("pnp-yedek-2026-09-16.json"))

            val refused = assertFailsWith<BackupException> { handle.write("{}".encodeToByteArray()) }

            assertEquals(BackupFailure.NOT_WRITABLE, refused.failure)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.BACKUP_WRITE_FAILED,
                    reason = BackupFailure.NOT_WRITABLE,
                    exception = "dev.pnptracker.platform.files.AtomicWriteException",
                    cause = "java.nio.file.AccessDeniedException",
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `a backup whose database will not be read, and one that will not be written out`() =
        runBlocking {
            val unreadable =
                BackupController(
                    gateway = DesktopBackupFileGateway(FixedBackupPicker(directory.root.resolve("yedek.json")), diagnostics = diagnostics),
                    exporter = DatabaseBackupExporter(FakeBackupSource(refusal = ::storageRefusal), AppInfo.Current, StoppedRestoreClock()),
                    clock = StoppedRestoreClock(),
                    diagnostics = diagnostics,
                )

            unreadable.saveBackup()

            assertEquals(BackupScreenState.Failed(BackupFailure.COULD_NOT_READ_DATABASE), unreadable.state)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_READ_FAILED,
                    area = DiagnosticArea.BACKUP,
                    reason = BackupFailure.COULD_NOT_READ_DATABASE,
                    exception = "androidx.sqlite.SQLiteException",
                ),
                diagnostics.only(),
            )
            diagnostics.forget()

            val unwritableDocument =
                BackupController(
                    gateway = DesktopBackupFileGateway(FixedBackupPicker(directory.root.resolve("yedek.json")), diagnostics = diagnostics),
                    exporter =
                        DatabaseBackupExporter(
                            FakeBackupSource(refusal = { SerializationException("this document cannot be written") }),
                            AppInfo.Current,
                            StoppedRestoreClock(),
                        ),
                    clock = StoppedRestoreClock(),
                    diagnostics = diagnostics,
                )

            unwritableDocument.saveBackup()

            assertEquals(BackupScreenState.Failed(BackupFailure.COULD_NOT_BUILD_DOCUMENT), unwritableDocument.state)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.BACKUP_WRITE_FAILED,
                    reason = BackupFailure.COULD_NOT_BUILD_DOCUMENT,
                    exception = "kotlinx.serialization.SerializationException",
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `old automatic backups that will not go away, counted by kind`() =
        runBlocking {
            val folder = RefusingBackupDirectory()
            (1..4).forEach { folder.put(importSnapshotFileName(LocalMoment(2026, 9, 9, 14, it, 0))) }
            val newest = importSnapshotFileName(LocalMoment(2026, 9, 9, 14, 4, 0))
            val settings = DesktopSettingsStore(directory.root.resolve("settings.json"), diagnostics = diagnostics)
            settings.write(1)

            SettingsDrivenHousekeeping(settings, AutomaticBackupRotation(folder), diagnostics)
                .afterWriting(newest.removeSuffix(".json"))

            assertEquals(3, folder.refusals, "the rotation did not even try")
            assertRecordedAsPlanned(
                ExpectedRecord(DiagnosticEvent.BACKUP_ROTATION_INCOMPLETE, area = DiagnosticArea.IMPORT_SNAPSHOTS, count = 3),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `a settings file that cannot be used, said once however often it is read`() =
        runBlocking {
            val file = directory.root.resolve("settings.json")
            Files.writeString(file, "{ bu bir ayar dosyası değil")
            val store = DesktopSettingsStore(file, diagnostics = diagnostics)

            repeat(3) { store.read() }

            assertEquals(7, store.read().automaticBackupCount, "the default is still what the application runs with")
            assertRecordedAsPlanned(
                ExpectedRecord(DiagnosticEvent.SETTINGS_READ_PROBLEM, reason = SettingsProblem.NOT_THE_EXPECTED_SHAPE),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `a setting that cannot be saved`() =
        runBlocking {
            val store = DesktopSettingsStore(aFolderNobodyCanWriteIn().resolve("settings.json"), diagnostics = diagnostics)

            val refused = assertFailsWith<SettingsNotSaved> { store.write(9) }

            assertEquals(SettingsWriteFailure.NOT_WRITABLE, refused.failure)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.SETTINGS_WRITE_FAILED,
                    reason = SettingsWriteFailure.NOT_WRITABLE,
                    exception = "dev.pnptracker.platform.files.AtomicWriteException",
                    cause = "java.nio.file.AccessDeniedException",
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `a csv the folder will not take`() =
        runBlocking {
            val target = aFolderNobodyCanWriteIn().resolve("gorevler.csv")
            val gateway = DesktopExportFileGateway(FixedExportPicker(target), diagnostics = diagnostics)
            val handle = gateway.chooseDestination("gorevler.csv")

            val refused = assertFailsWith<TaskExportException> { handle?.write("game,column\r\n") }

            assertEquals(ExportFailure.NOT_WRITABLE, refused.failure)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.EXPORT_WRITE_FAILED,
                    reason = ExportFailure.NOT_WRITABLE,
                    exception = "dev.pnptracker.platform.files.AtomicWriteException",
                    cause = "java.nio.file.AccessDeniedException",
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `a task whose colours do not make sense to export`() =
        runBlocking {
            val database = DatabaseFactory().open(directory.databaseFile).also { opened += it }
            val task = insertGameCellAndTask(database)
            val colorId =
                database
                    .colorDao()
                    .allColors()
                    .first()
                    .id
            executeRawSql(
                database,
                "INSERT INTO task_colors (task_id, color_id, slot_index) VALUES (?, ?, 1)",
                task.id.toString(),
                colorId.toString(),
            )

            val refused = assertFailsWith<TaskExportException> { TaskExportStore(database.taskExportDao(), diagnostics).exportedTasks() }

            assertEquals(ExportFailure.BROKEN_DATA, refused.failure)
            assertRecordedAsPlanned(
                ExpectedRecord(DiagnosticEvent.EXPORT_BROKEN_DATA, reason = ExportInvariant.BROKEN_COLOR_SLOTS),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }
}

/** A folder of automatic backups where nothing may be removed. */
private class RefusingBackupDirectory : BackupDirectory {
    private val files = mutableListOf<InspectedBackupFile>()

    var refusals: Int = 0
        private set

    fun put(fileName: String) {
        val name = checkNotNull(automaticBackupNameOf(fileName)) { "$fileName is not a name rotation would ever see" }
        files +=
            InspectedBackupFile(
                name = name,
                ordinaryFile = true,
                header =
                    backupDocumentOf(anEmptyBackup(), appVersion = "0.1.0", sourceSchemaVersion = 8, createdAt = WRITTEN_AT)
                        .json
                        .encodeToByteArray()
                        .copyOf(BACKUP_HEADER_BYTES),
            )
    }

    override suspend fun inspect(): List<InspectedBackupFile> = files.toList()

    override suspend fun remove(fileName: String): Boolean {
        refusals++
        return false
    }
}
