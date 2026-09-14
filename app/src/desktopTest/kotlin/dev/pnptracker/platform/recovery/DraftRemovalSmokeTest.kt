package dev.pnptracker.platform.recovery

import dev.pnptracker.data.repository.ImportDraftRemovalStore
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.platform.importfiles.DesktopImportFileGateway
import dev.pnptracker.platform.importfiles.ImportFilePicker
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreenState
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Removing a real CSV draft, in a temporary home, the way the application is built.
 *
 * The file is read by the real gateway and saved by the real store; the
 * application is closed and opened again through the start-up gate; the source
 * file is then deleted or rewritten; and the draft is removed. PLAN 11.4.5 asks
 * three things of that, and each is looked at on disk rather than taken on trust:
 * the source file is never read again, no automatic backup is taken, and no
 * history line is written. Nothing here touches the real user's files —
 * [RecoveryHome] compares their footprint when it closes.
 */
class DraftRemovalSmokeTest {
    private lateinit var home: RecoveryHome

    @BeforeTest
    fun makeAHome() {
        home = RecoveryHome()
    }

    @AfterTest
    fun sweepTheHome() {
        home.close()
    }

    private class FixedPicker(
        private val file: Path,
    ) : ImportFilePicker {
        override suspend fun chooseImportFile(): Path = file
    }

    private fun aSourceFile(): Path {
        val folder = Files.createDirectories(home.root.resolve("kaynak"))
        val text =
            """
            game,source_type,raw_text
            Harmonies,3D,12 KIRMIZI
            Harmonies,3D,8 MAVİ
            Wingspan,CARD,40 kuş kartı
            """.trimIndent()
        return Files.write(folder.resolve("liste.csv"), text.toByteArray(StandardCharsets.UTF_8))
    }

    /** Reads [file] through the real gateway and saves it as a draft, then closes the application. */
    private fun aSavedDraft(file: Path): EntityId =
        home.withDatabase { database ->
            val controller = ImportController(DesktopImportFileGateway(FixedPicker(file)), ImportDraftStore(database.importDao()))
            controller.chooseFile()
            assertEquals(ImportSourceFormat.CSV, assertIs<ImportScreenState.PreviewReady>(controller.state).session.sourceFormat)
            controller.saveDraft()
            assertIs<ImportScreenState.Saved>(controller.state).summary.batchId
        }

    private fun backupsOnDisk(): List<String> =
        Files.list(home.paths.backupsDirectory).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }

    private fun removedAfterTheSourceWent(whatHappensToTheFile: (Path) -> Unit) {
        val empty = home.reopen().fingerprint
        val file = aSourceFile()
        val batchId = aSavedDraft(file)
        whatHappensToTheFile(file)

        // A new start: the draft is still there to be continued or removed.
        val filesBefore = home.filesOnDisk()
        val outcome =
            home.withDatabase { database ->
                assertEquals(listOf(batchId), database.importDao().draftBatches().map { it.id })
                // Three records, each a game-name cell and a raw-text cell.
                assertEquals(6, database.importDao().rawBlocksOfBatch(batchId).size)
                ImportDraftRemovalStore(database.importDao()).remove(batchId)
            }

        assertEquals(DraftRemovalOutcome.Removed(batchId, 6, 0, 0, 0), outcome)
        assertEquals(emptyList(), backupsOnDisk(), "the removal took an automatic backup")
        assertEquals(filesBefore, home.filesOnDisk(), "the removal left a file behind")
        assertTrue(Files.notExists(home.paths.settingsFile), "the removal wrote the settings file")

        // The next start finds a sound database that is, row for row, the one
        // there was before the file was imported: no history line, no draft, nothing.
        val reopened = home.reopen()
        reopened.assertWhole()
        assertEquals(emptyList(), reopened.data.historyEvents)
        assertEquals(empty, reopened.fingerprint, "removing the draft did not put the database back as it was")

        // And asking again, after another start, is the typed answer.
        val again = home.withDatabase { database -> ImportDraftRemovalStore(database.importDao()).remove(batchId) }
        assertEquals(DraftRemovalOutcome.Refused(batchId, DraftRemovalRefusal.ALREADY_REMOVED), again)
    }

    @Test
    fun `a draft whose source file was deleted is removed without it`() =
        removedAfterTheSourceWent { file ->
            Files.delete(file)
            Files.delete(file.parent)
        }

    @Test
    fun `a draft whose source file was rewritten is removed from what the database holds`() =
        removedAfterTheSourceWent { file ->
            Files.write(file, "bu artık bir CSV değil".toByteArray(StandardCharsets.UTF_8))
        }
}
