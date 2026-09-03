package dev.pnptracker.platform.csv

import dev.pnptracker.data.repository.EarlierImport
import dev.pnptracker.data.repository.ImportDrafts
import dev.pnptracker.data.repository.SavedImportSummary
import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.importprep.PreparedImportDraft
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.platform.importfiles.DesktopImportFileGateway
import dev.pnptracker.platform.importfiles.ImportFilePicker
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreenState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Which reader a chosen file goes to, and what happens when it should go to
 * neither.
 *
 * One action on the screen, two formats behind it. The decision is made from the
 * name of the file and nowhere else, which is why the case of the extension has
 * to be beneath notice: a file saved as `LISTE.CSV` is a CSV, and telling
 * somebody otherwise would be the application being pedantic about something no
 * operating system cares about.
 *
 * Nothing here needs a database: choosing a file writes nothing, and the store
 * below records what it was asked to save so a test can see that it was asked
 * once or not at all.
 */
class CsvFileChoiceTest {
    private lateinit var directory: Path

    private class FixedPicker(
        private val file: Path?,
    ) : ImportFilePicker {
        var calls = 0
            private set

        override suspend fun chooseImportFile(): Path? {
            calls++
            return file
        }
    }

    /** A picker that waits, so a second click can arrive while the first is out. */
    private class WaitingPicker(
        private val file: Path,
        private val released: CompletableDeferred<Unit>,
        private val arrived: CompletableDeferred<Unit>,
    ) : ImportFilePicker {
        var calls = 0
            private set

        override suspend fun chooseImportFile(): Path {
            calls++
            arrived.complete(Unit)
            released.await()
            return file
        }
    }

    private class RecordingStore : ImportDrafts {
        val saved = mutableListOf<PreparedImportDraft>()

        override suspend fun earlierImportsOf(sha256: String): List<EarlierImport> = emptyList()

        override suspend fun save(draft: PreparedImportDraft): SavedImportSummary {
            saved += draft
            return SavedImportSummary(IdGenerator.Random.newId(), draft.fileName, draft.sheetName, draft.rawBlockCount)
        }
    }

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("pnp-csv-choice")
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        check(directory.startsWith(Path.of(System.getProperty("java.io.tmpdir")))) {
            "refusing to delete $directory, which is not under the temporary directory"
        }
        directory.deleteRecursively()
    }

    private val goodCsv = "game,source_type,raw_text\nHarmonies,3d,Kırmızı ev\n"

    private fun fileNamed(
        name: String,
        text: String = goodCsv,
    ): Path = directory.resolve(name).also { Files.write(it, text.toByteArray(StandardCharsets.UTF_8)) }

    private fun controllerFor(picker: ImportFilePicker): Pair<ImportController, RecordingStore> {
        val store = RecordingStore()
        return ImportController(DesktopImportFileGateway(picker), store) to store
    }

    @Test
    fun `a csv file is read by the CSV reader`() =
        runBlocking<Unit> {
            val (controller, _) = controllerFor(FixedPicker(fileNamed("liste.csv")))

            controller.chooseFile()

            val ready = assertIs<ImportScreenState.PreviewReady>(controller.state)
            assertEquals(ImportSourceFormat.CSV, ready.session.sourceFormat)
            assertEquals("liste.csv", ready.session.selectedSheetName, "a CSV is one page, named after the file")
        }

    @Test
    fun `an extension in capitals is the same extension`() =
        runBlocking<Unit> {
            val (controller, _) = controllerFor(FixedPicker(fileNamed("LISTE.CSV")))

            controller.chooseFile()

            val ready = assertIs<ImportScreenState.PreviewReady>(controller.state)
            assertEquals(ImportSourceFormat.CSV, ready.session.sourceFormat)
        }

    @Test
    fun `a file that is neither is refused in words that name both`() =
        runBlocking<Unit> {
            val (controller, store) = controllerFor(FixedPicker(fileNamed("notlar.txt")))

            controller.chooseFile()

            assertEquals(ImportFailure.UNSUPPORTED_FILE_TYPE, assertIs<ImportScreenState.Failed>(controller.state).failure)
            assertEquals(emptyList(), store.saved)
        }

    @Test
    fun `changing one's mind puts the screen back and saves nothing`() =
        runBlocking<Unit> {
            val picker = FixedPicker(file = null)
            val (controller, store) = controllerFor(picker)

            controller.chooseFile()

            assertEquals(ImportScreenState.Idle, controller.state)
            assertEquals(1, picker.calls)
            assertEquals(emptyList(), store.saved)
        }

    @Test
    fun `a file that will not parse says so plainly and keeps the screen open`() =
        runBlocking<Unit> {
            val (controller, store) =
                controllerFor(
                    FixedPicker(fileNamed("bozuk.csv", "game,source_type,raw_text\nHarmonies,3d,\"açık kaldı\n")),
                )

            controller.chooseFile()

            val failed = assertIs<ImportScreenState.Failed>(controller.state)
            assertEquals(ImportFailure.CSV_UNCLOSED_QUOTE, failed.failure)
            assertEquals(2, failed.csvLocation?.lineNumber)
            assertEquals(emptyList(), store.saved)
        }

    @Test
    fun `a file that is not UTF-8 says which encoding to save it in`() =
        runBlocking<Unit> {
            val file = directory.resolve("eski.csv")
            Files.write(
                file,
                "game,source_type,raw_text\nHarmonies,3d,".toByteArray(StandardCharsets.UTF_8) +
                    byteArrayOf(0x4B, 0xFD.toByte(), 0x72),
            )
            val (controller, _) = controllerFor(FixedPicker(file))

            controller.chooseFile()

            assertEquals(ImportFailure.NOT_UTF8, assertIs<ImportScreenState.Failed>(controller.state).failure)
        }

    @Test
    fun `after a refusal the user can pick another file and get on with it`() =
        runBlocking<Unit> {
            val store = RecordingStore()
            val broken = ImportController(DesktopImportFileGateway(FixedPicker(fileNamed("notlar.txt"))), store)
            broken.chooseFile()
            assertIs<ImportScreenState.Failed>(broken.state)

            // The same screen, the same controller state machine: the next choice
            // simply replaces the failure.
            val good = ImportController(DesktopImportFileGateway(FixedPicker(fileNamed("iyi.csv"))), store)
            good.chooseFile()
            good.saveDraft()

            assertIs<ImportScreenState.Saved>(good.state)
            assertEquals(1, store.saved.size)
            assertEquals(ImportSourceFormat.CSV, store.saved.single().sourceFormat)
        }

    @Test
    fun `a second choice while the dialog is still open is refused`() =
        runBlocking<Unit> {
            val released = CompletableDeferred<Unit>()
            val arrived = CompletableDeferred<Unit>()
            val picker = WaitingPicker(fileNamed("liste.csv"), released, arrived)
            val (controller, store) = controllerFor(picker)

            val first = launch(Dispatchers.Default) { controller.chooseFile() }
            withTimeout(10_000) { arrived.await() }
            assertTrue(controller.isBusy)

            // The second click lands while the first is still out at the dialog.
            controller.chooseFile()
            assertEquals(1, picker.calls, "a second click opened a second file dialog")

            released.complete(Unit)
            first.join()
            assertIs<ImportScreenState.PreviewReady>(controller.state)
            assertEquals(emptyList(), store.saved)
        }
}
