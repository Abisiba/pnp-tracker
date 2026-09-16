package dev.pnptracker.platform.diagnostics

import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Who records what, written down (PLAN 14.7.2: a failure is recorded where the
 * raw cause becomes the answer, once).
 *
 * The behaviour is proved by the matrix tests beside this one, each of which
 * insists on exactly one record. This is the other half: a list of the places
 * that may record at all, so a second one cannot appear quietly — a controller
 * recording what its store already recorded would double every line in the file
 * and no single test about one failure would notice.
 *
 * Two events have two owners on purpose, and both are named below.
 */
class DiagnosticOwnershipTest {
    /** Every event, and the production files allowed to build one. */
    private val owners: Map<DiagnosticEvent, Set<String>> =
        mapOf(
            // Built by Main out of the gate's refusal; the record itself lives here.
            DiagnosticEvent.STARTUP_REFUSED to setOf("ApplicationFailureRecords.kt"),
            DiagnosticEvent.MIGRATION_COMPLETED to setOf("StartupGate.kt"),
            DiagnosticEvent.SETTINGS_READ_PROBLEM to setOf("DesktopSettingsStore.kt"),
            DiagnosticEvent.SETTINGS_WRITE_FAILED to setOf("DesktopSettingsStore.kt"),
            // The storage pair is built by the two shared factories; who calls them
            // is the list below.
            DiagnosticEvent.STORAGE_READ_FAILED to setOf("RecordSafely.kt"),
            DiagnosticEvent.STORAGE_WRITE_FAILED to setOf("RecordSafely.kt"),
            DiagnosticEvent.IMPORT_FILE_UNREADABLE to setOf("ImportController.kt"),
            DiagnosticEvent.IMPORT_SNAPSHOT_FAILED to setOf("VerifiedSnapshotTaker.kt"),
            DiagnosticEvent.IMPORT_CHANGED_MEANWHILE to setOf("ImportConfirmationStore.kt"),
            DiagnosticEvent.IMPORT_RECORDS_CONTRADICT to setOf("ImportConfirmationStore.kt"),
            DiagnosticEvent.IMPORT_ROLLBACK_PROVENANCE_BROKEN to setOf("ImportRollbackStore.kt"),
            DiagnosticEvent.IMPORT_DRAFT_HELD_BY_RECORDS to setOf("ImportDraftRemovalStore.kt"),
            // Two owners: the disk refusing the file, and the document that could
            // not be made out of the rows. Neither can see the other's failure.
            DiagnosticEvent.BACKUP_WRITE_FAILED to setOf("DesktopBackupFileGateway.kt", "BackupController.kt"),
            DiagnosticEvent.BACKUP_ROTATION_INCOMPLETE to setOf("AutomaticBackupHousekeeping.kt"),
            DiagnosticEvent.RESTORE_FILE_REFUSED to setOf("RestoreController.kt"),
            // Two owners again: the way back that was never written, and the
            // replacement itself, whose reasons are only known inside it.
            DiagnosticEvent.RESTORE_NOT_COMPLETED to setOf("RestoreController.kt", "LiveBackupRestorer.kt"),
            DiagnosticEvent.RESTORE_COMPLETED to setOf("LiveBackupRestorer.kt"),
            DiagnosticEvent.EXPORT_WRITE_FAILED to setOf("DesktopExportFileGateway.kt"),
            DiagnosticEvent.EXPORT_BROKEN_DATA to setOf("TaskExportStore.kt"),
            DiagnosticEvent.UNEXPECTED_FAILURE to setOf("RecordSafely.kt", "ApplicationFailureRecords.kt"),
            DiagnosticEvent.RECORDS_DROPPED to setOf("QueuedDiagnostics.kt"),
        )

    /** Every boundary that turns a storage refusal into an answer, and records it. */
    private val storageOwners =
        setOf(
            "CellTextStore.kt",
            "ColorCatalogueStore.kt",
            "GameSetupStore.kt",
            "ImportConfirmationStore.kt",
            "ImportDraftRemovalStore.kt",
            "ImportReviewStore.kt",
            "ImportRollbackStore.kt",
            "TaskEditStore.kt",
            "TaskExportStore.kt",
            "TaskFromTextStore.kt",
            "TaskProgressStore.kt",
            "TaskSetupStore.kt",
            "UnfinishedImportsStore.kt",
            "BackupController.kt",
            "HistoryController.kt",
            "PoolController.kt",
            "RecordSafely.kt",
        )

    @Test
    fun `each event is built where the plan puts it and nowhere else`() {
        assertEquals(DiagnosticEvent.entries.toSet(), owners.keys, "an event has no owner written down")
        owners.forEach { (event, expected) ->
            assertEquals(expected, filesMentioning("DiagnosticEvent.${event.name}"), "the owners of ${event.code}")
        }
    }

    @Test
    fun `a storage refusal is recorded by the boundary that names it, and by no screen above it`() {
        // `readShownAsFailed` is the same record for a screen that cannot tell a
        // refusal from a defect, so its callers belong to the same list.
        assertEquals(
            storageOwners,
            filesMentioning("storageWriteFailed") + filesMentioning("storageReadFailed") + filesMentioning("readShownAsFailed"),
        )
    }

    @Test
    fun `nothing records without going through the one safe way of doing it`() {
        val recordingByHand =
            productionSources().filter { file ->
                val text = Files.readString(file)
                ".record(" in text && file.fileName.toString() !in setOf("QueuedDiagnostics.kt", "RecordSafely.kt")
            }

        assertEquals(emptyList(), recordingByHand.map { it.fileName.toString() })
    }

    @Test
    fun `the application hands one writer to every boundary and closes it`() {
        val main = Files.readString(moduleRoot().resolve("src/desktopMain/kotlin/dev/pnptracker/Main.kt"))

        assertTrue("QueuedDiagnostics.inDirectory(paths.logsDirectory" in main, "Main does not build the writer")
        assertTrue("diagnostics.close()" in main, "Main does not close the writer")
        assertTrue("startupRefusalRecord(refused)" in main, "Main does not record the gate's refusal")
        assertTrue(
            "RecordingWindowExceptionHandlerFactory(diagnostics)" in main,
            "Main does not put the recording handler in front of the windows",
        )
        // One writer, given to the boundaries rather than reached for.
        assertEquals(1, Regex("QueuedDiagnostics\\.inDirectory").findAll(main).count())
        assertTrue("diagnostics = diagnostics" in main, "the boundaries are not given the writer")
    }

    private fun filesMentioning(text: String): Set<String> =
        productionSources()
            .filter { text in Files.readString(it) }
            .map { it.fileName.toString() }
            .toSet()

    private fun productionSources(): List<Path> =
        listOf("src/commonMain/kotlin", "src/desktopMain/kotlin")
            .map { moduleRoot().resolve(it) }
            .flatMap { root -> Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() } }

    private fun moduleRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .firstOrNull { Files.isDirectory(it.resolve("src/commonMain/kotlin")) }
                ?.let { return it }
            candidate = candidate.parent
        }
        fail("could not find the module root")
    }
}
