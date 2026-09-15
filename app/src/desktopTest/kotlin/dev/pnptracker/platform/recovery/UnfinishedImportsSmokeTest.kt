package dev.pnptracker.platform.recovery

import dev.pnptracker.data.database.executeRawSql
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.data.repository.ImportReviewStore
import dev.pnptracker.data.repository.UnfinishedImportsStore
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.platform.importfiles.DesktopImportFileGateway
import dev.pnptracker.platform.importfiles.ImportFilePicker
import dev.pnptracker.platform.xlsx.copyFixtureInto
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreenState
import dev.pnptracker.ui.feature.importworkspace.ImportReviewController
import dev.pnptracker.ui.feature.importworkspace.ImportReviewState
import dev.pnptracker.ui.feature.importworkspace.OpenAttempt
import dev.pnptracker.ui.feature.importworkspace.OpenRefusal
import dev.pnptracker.ui.feature.importworkspace.RemovalFlowState
import dev.pnptracker.ui.feature.importworkspace.UnfinishedImportsController
import dev.pnptracker.ui.feature.importworkspace.UnfinishedImportsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Continuing and removing unfinished imports, in a temporary home, the way the
 * application is built.
 *
 * Every collaborator is real: files read by the real gateway, drafts saved by the
 * real store, the database opened through the start-up gate after every step,
 * the list read by the real store, the review by the real review store, and the
 * removal by the one removal engine. Only the click is not a click — the
 * controller the section's buttons call is called directly. What the screen
 * draws of it is [dev.pnptracker.ui.feature.importworkspace.UnfinishedImportsSectionTest]'s
 * business; what is checked here is what happens to the files and the rows.
 * [RecoveryHome] proves on close that the real user's files were not touched.
 */
class UnfinishedImportsSmokeTest {
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

    private fun sourceFolder(): Path = Files.createDirectories(home.root.resolve("kaynak"))

    private fun aCsvSource(): Path =
        Files.write(
            sourceFolder().resolve("liste.csv"),
            "game,source_type,raw_text\nHarmonies,3D,12 KIRMIZI\nWingspan,CARD,40 kuş kartı\n".toByteArray(StandardCharsets.UTF_8),
        )

    /** Reads [file] through the real gateway and saves it as a draft, choosing a sheet if it has to. */
    private fun aSavedDraft(file: Path): EntityId =
        home.withDatabase { database ->
            val controller = ImportController(DesktopImportFileGateway(FixedPicker(file)), ImportDraftStore(database.importDao()))
            controller.chooseFile()
            (controller.state as? ImportScreenState.SheetSelection)?.session?.sheets?.firstOrNull { !it.isEmpty }?.let { sheet ->
                controller.selectSheet(sheet.name)
            }
            controller.saveDraft()
            assertIs<ImportScreenState.Saved>(controller.state, "the draft was not saved: ${controller.state}").summary.batchId
        }

    private fun backupsOnDisk(): List<String> =
        Files.list(home.paths.backupsDirectory).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }

    /** Opens the database through the gate and drives the list the way the section does. */
    private fun <T> withTheList(
        work: suspend CoroutineScope.(UnfinishedImportsController, dev.pnptracker.data.database.AppDatabase) -> T,
    ): T =
        home.withDatabase { database ->
            val controller = UnfinishedImportsController(UnfinishedImportsStore(database, database.importDao()))
            kotlinx.coroutines.coroutineScope {
                val collecting = launch(Dispatchers.Default) { controller.observeUnfinishedImports() }
                try {
                    work(controller, database)
                } finally {
                    collecting.cancelAndJoin()
                }
            }
        }

    /** Waits for the list to satisfy [what]; the wait is bounded, not measured. */
    private suspend fun UnfinishedImportsController.awaitList(
        what: (UnfinishedImportsState.Ready) -> Boolean,
    ): UnfinishedImportsState.Ready =
        withTimeout(10.seconds) {
            while (true) {
                val ready = list as? UnfinishedImportsState.Ready
                if (ready != null && what(ready)) return@withTimeout ready
                delay(5)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    /** Continues and then removes a draft whose source has gone or changed, and proves what that left. */
    private fun continuedAndRemoved(
        batchId: EntityId,
        empty: String,
    ) {
        val filesBefore = home.filesOnDisk()
        withTheList { controller, database ->
            val ready = controller.awaitList { it.sound.any { row -> row.batchId == batchId } }
            assertEquals(emptyList(), ready.contradicting)

            // Continue: the fresh check passes and the review reads the saved raw cells.
            controller.open(batchId)
            assertEquals(batchId, controller.readyToOpen, "the draft was not let into the review: ${controller.opening}")
            controller.openHonoured()
            val review = ImportReviewController(ImportReviewStore(database.importDao(), database.gameDao(), database.colorDao()))
            val observing = launch(Dispatchers.Default) { review.observe(batchId) }
            val content =
                withTimeout(10.seconds) {
                    while (review.state !is ImportReviewState.Content) delay(5)
                    review.state as ImportReviewState.Content
                }
            observing.cancelAndJoin()
            assertTrue(content.workspace.rawBlocks.isNotEmpty(), "the review had no cells to show")
            assertTrue(content.canEdit)

            // Remove, with the user's say-so.
            controller.askToRemove(batchId)
            assertIs<RemovalFlowState.Offered>(controller.removal)
            controller.remove()
            assertIs<RemovalFlowState.Removed>(controller.removal)
            controller.close()
            controller.awaitList { it.isEmpty }
        }
        assertEquals(emptyList(), backupsOnDisk(), "the removal took an automatic backup")
        assertEquals(filesBefore, home.filesOnDisk(), "continuing or removing left a file behind")
        assertTrue(Files.notExists(home.paths.settingsFile))
        val after = home.reopen()
        after.assertWhole()
        assertEquals(emptyList(), after.data.historyEvents, "the removal wrote history")
        assertEquals(empty, after.fingerprint, "the database is not what it was before the import")
    }

    @Test
    fun `a CSV draft whose source was deleted is continued and removed from what the database holds`() {
        val empty = home.reopen().fingerprint
        val file = aCsvSource()
        val batchId = aSavedDraft(file)
        Files.delete(file)

        continuedAndRemoved(batchId, empty)
    }

    @Test
    fun `an XLSX draft whose source was rewritten is continued and removed from what the database holds`() {
        val empty = home.reopen().fingerprint
        val file = copyFixtureInto(sourceFolder())
        val batchId = aSavedDraft(file)
        Files.write(file, "artık bir çalışma kitabı değil".toByteArray(StandardCharsets.UTF_8))

        continuedAndRemoved(batchId, empty)
    }

    @Test
    fun `a CSV draft whose source was deleted is continued and confirmed behind a real backup`() {
        val file = aCsvSource()
        val batchId = aSavedDraft(file)
        home.withDatabase { database ->
            val cellId = aGameWithACell(database)
            val review = ImportReviewStore(database.importDao(), database.gameDao(), database.colorDao())
            val block = database.importDao().rawBlocksOfBatch(batchId).first { it.rawText == "12 KIRMIZI" }
            val draftId = review.createDraftFromSelection(block.id, 0, block.rawText.length)
            review.saveDraft(aDraftEdit(draftId, cellId, "Kırmızı figür", quantity = 12, colours = twoColours(database).take(1)))
        }
        Files.delete(file)

        withTheList { controller, database ->
            controller.awaitList { it.sound.any { row -> row.batchId == batchId } }
            controller.open(batchId)
            assertEquals(batchId, controller.readyToOpen, "the draft was not let into the review: ${controller.opening}")
            val result =
                realConfirmationStore(
                    database,
                    home.paths,
                    home::probeDirectory,
                ).confirm(batchId, acknowledgeUnprocessedBlocks = true)
            assertEquals(1, result.createdTaskCount)
            controller.awaitList { it.isEmpty }
        }
        assertEquals(1, backupsOnDisk().size, "the confirmation was not behind its automatic backup")
        val after = home.reopen()
        after.assertWhole()
        assertEquals(listOf("CONFIRMED"), after.data.importBatches.map { it.status })
    }

    @Test
    fun `a draft saved by a process that was then killed is listed, continued and removed`() {
        home.withDatabase { }
        val empty = home.reopen().fingerprint
        val child = home.start(InterruptedWrite.SAVING_A_DRAFT, Ending.COMMITTED)
        child.awaitLine("DURABILITY")
        child.awaitLine("BEFORE")
        child.awaitLine("COMMITTED")
        child.kill()
        assertTrue(child.exitedBySignal, "the child was not ended by SIGKILL")

        val batchId =
            home
                .reopen()
                .also { it.assertWhole() }
                .data.importBatches
                .single()
                .id
                .let(EntityId::parse)
        continuedAndRemoved(batchId, empty)
    }

    @Test
    fun `contradicting drafts are listed apart, cannot be opened, and are removed or refused by the engine`() {
        val damaged = home.withDatabase { database -> aReadyDraftImport(database) }
        val held =
            home.withDatabase { database ->
                val batchId = ImportDraftStore(database.importDao()).save(aPreparedImport().copy(sha256 = "b".repeat(64))).batchId
                insertGameCellAndTask(database, gameName = "Wingspan")
                val block =
                    database
                        .importDao()
                        .rawBlocksOfBatch(batchId)
                        .first()
                        .id
                        .toString()
                executeRawSql(database, "UPDATE tasks SET source_raw_import_block_id = ?", block)
                executeRawSql(database, "UPDATE import_batches SET created_task_count = 1 WHERE id = ?", damaged.toString())
                batchId
            }

        withTheList { controller, _ ->
            val ready = controller.awaitList { it.contradicting.size == 2 }
            assertEquals(emptyList(), ready.sound)
            assertEquals(
                setOf(held),
                ready.contradicting
                    .filter { it.mayBeHeldByRecords }
                    .map { it.batchId }
                    .toSet(),
            )

            // The section offers no way in, and the controller would refuse one anyway.
            controller.open(damaged)
            assertEquals(null, controller.readyToOpen)
            assertEquals(OpenRefusal.RECORDS_CONTRADICT, assertIs<OpenAttempt.Refused>(controller.opening).refusal)
        }

        val beforeRefusal = home.reopen().fingerprint
        withTheList { controller, _ ->
            controller.awaitList { it.contradicting.size == 2 }
            controller.askToRemove(held)
            controller.remove()
            assertEquals(DraftRemovalRefusal.HELD_BY_RECORDS, assertIs<RemovalFlowState.Refused>(controller.removal).refusal)
            controller.close()
        }
        assertEquals(beforeRefusal, home.reopen().also { it.assertWhole() }.fingerprint, "a refused removal changed the database")

        withTheList { controller, _ ->
            controller.awaitList { it.contradicting.size == 2 }
            controller.askToRemove(damaged)
            controller.remove()
            assertIs<RemovalFlowState.Removed>(controller.removal)
            controller.close()
            val left = controller.awaitList { it.contradicting.size == 1 }
            assertEquals(listOf(held), left.contradicting.map { it.batchId })
        }
        assertEquals(emptyList(), backupsOnDisk())
        val after = home.reopen()
        after.assertWhole()
        assertEquals(emptyList(), after.data.historyEvents)
        assertEquals(1, after.data.importBatches.size, "the held draft went with the other one")
    }
}
