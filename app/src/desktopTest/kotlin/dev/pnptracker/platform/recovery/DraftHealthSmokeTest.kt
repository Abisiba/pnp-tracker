package dev.pnptracker.platform.recovery

import dev.pnptracker.data.database.executeRawSql
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importhealth.DraftContradiction
import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.model.EntityId
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Recognising a damaged draft, in a temporary home, the way the application is built.
 *
 * Two things PLAN 11.4.5 says are looked at on disk here rather than taken on
 * trust. A saved draft whose source file has since been deleted or rewritten is
 * still sound — the classifier never opens that file — and asking writes
 * nothing. And a draft whose records contradict each other is refused by the
 * real confirmation store, with its real verified snapshot taker, before any
 * backup file is written: the backups folder stays empty and the database
 * reads, after a fresh start, exactly as it did. [RecoveryHome] proves on close
 * that the real user's database, lock, backups and settings were not touched.
 */
class DraftHealthSmokeTest {
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
            Wingspan,CARD,40 kuş kartı
            """.trimIndent()
        return Files.write(folder.resolve("liste.csv"), text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun aSavedDraft(file: Path): EntityId =
        home.withDatabase { database ->
            val controller = ImportController(DesktopImportFileGateway(FixedPicker(file)), ImportDraftStore(database.importDao()))
            controller.chooseFile()
            controller.saveDraft()
            assertIs<ImportScreenState.Saved>(controller.state).summary.batchId
        }

    private fun backupsOnDisk(): List<String> =
        Files.list(home.paths.backupsDirectory).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }

    private fun soundAfterTheSourceWent(whatHappensToTheFile: (Path) -> Unit) {
        val file = aSourceFile()
        val batchId = aSavedDraft(file)
        whatHappensToTheFile(file)

        val before = home.reopen()
        val filesBefore = home.filesOnDisk()
        val health = home.withDatabase { database -> database.importDao().draftHealthOf(batchId) }

        assertEquals(DraftHealth.Sound(batchId), health)
        val after = home.reopen()
        after.assertWhole()
        assertEquals(before.fingerprint, after.fingerprint, "classifying the draft changed the database")
        assertEquals(filesBefore, home.filesOnDisk(), "classifying the draft left a file behind")
        assertEquals(emptyList(), backupsOnDisk())
    }

    @Test
    fun `a draft whose source file was deleted is still sound`() =
        soundAfterTheSourceWent { file ->
            Files.delete(file)
            Files.delete(file.parent)
        }

    @Test
    fun `a draft whose source file was rewritten is still sound`() =
        soundAfterTheSourceWent { file ->
            Files.write(file, "bu artık bir CSV değil".toByteArray(StandardCharsets.UTF_8))
        }

    @Test
    fun `a draft whose records contradict each other is refused with no backup on disk`() {
        val batchId = home.withDatabase { database -> aReadyDraftImport(database) }
        home.withDatabase { database ->
            executeRawSql(database, "UPDATE import_batches SET created_task_count = 1 WHERE id = ?", batchId.toString())
        }
        val before = home.reopen()
        val filesBefore = home.filesOnDisk()

        val refusal =
            home.withDatabase { database ->
                assertFailsWith<ImportConfirmationException> {
                    realConfirmationStore(database, home.paths, home::probeDirectory).confirm(batchId, acknowledgeUnprocessedBlocks = true)
                }
            }

        assertEquals(ImportConfirmationFailure.RECORDS_CONTRADICT_EACH_OTHER, refusal.failure)
        assertEquals(setOf(DraftContradiction.TASKS_COUNTED_BEFORE_CONFIRMATION), refusal.contradictions)
        assertEquals(emptyList(), backupsOnDisk(), "a backup was written for a draft that cannot be confirmed")
        assertEquals(filesBefore, home.filesOnDisk())
        val after = home.reopen()
        after.assertWhole()
        assertEquals(before.fingerprint, after.fingerprint, "the refused confirmation changed the database")
        assertEquals(listOf("DRAFT"), after.data.importBatches.map { it.status })
        assertTrue(Files.notExists(home.paths.settingsFile), "the refusal wrote the settings file")
    }

    @Test
    fun `the same draft with records that agree is confirmed behind a real backup`() {
        val batchId = home.withDatabase { database -> aReadyDraftImport(database) }

        val result =
            home.withDatabase { database ->
                realConfirmationStore(database, home.paths, home::probeDirectory).confirm(batchId, acknowledgeUnprocessedBlocks = true)
            }

        assertEquals(1, result.createdTaskCount)
        assertEquals(1, backupsOnDisk().size, "the control took no backup, so the refusal above proves nothing")
        val after = home.reopen()
        after.assertWhole()
        assertEquals(listOf("CONFIRMED"), after.data.importBatches.map { it.status })
    }
}
