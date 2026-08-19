package dev.pnptracker.ui.feature.importreview

import dev.pnptracker.data.repository.EarlierImport
import dev.pnptracker.data.repository.ImportDrafts
import dev.pnptracker.data.repository.SavedImportSummary
import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.importprep.ImportFileGateway
import dev.pnptracker.domain.importprep.ImportFileHandle
import dev.pnptracker.domain.importprep.ImportPreparationException
import dev.pnptracker.domain.importprep.PreparedImportDraft
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.spreadsheet.CellSnapshot
import dev.pnptracker.domain.spreadsheet.SheetSnapshot
import dev.pnptracker.domain.spreadsheet.SheetVisibility
import dev.pnptracker.domain.spreadsheet.SpreadsheetCellKind
import dev.pnptracker.domain.spreadsheet.WorkbookSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

private fun cell(
    row: Int,
    column: Int,
    text: String,
    fill: String? = null,
) = CellSnapshot(
    rowIndex = row,
    columnIndex = column,
    rawText = text,
    kind = SpreadsheetCellKind.TEXT,
    fillColorArgb = fill,
)

private val headerCells =
    listOf(
        cell(0, 1, "3D"),
        cell(0, 2, "Kart"),
        cell(0, 3, "Mukavva"),
        cell(0, 4, "Özel"),
        cell(0, 5, "Eksik"),
        cell(0, 6, "Ödünç Parçalar"),
    )

private fun goodSheet(
    name: String = "Sayfa1",
    visibility: SheetVisibility = SheetVisibility.VISIBLE,
) = SheetSnapshot(
    name = name,
    visibility = visibility,
    cells =
        (headerCells + cell(1, 0, "Örnek Oyun A", "FF4EA72E") + cell(1, 1, "3 KIRMIZI**"))
            .sortedWith(compareBy({ it.rowIndex }, { it.columnIndex })),
)

private fun emptySheet(name: String = "Bos") = SheetSnapshot(name, SheetVisibility.VISIBLE, emptyList())

/** A file the user "picked", with whatever behaviour a test needs. */
private class FakeFileHandle(
    override val fileName: String = "ornek.xlsx",
    private val workbook: WorkbookSnapshot,
    private val fingerprints: MutableList<String> = mutableListOf(SHA_A, SHA_A, SHA_A),
    private val readFailure: ImportFailure? = null,
) : ImportFileHandle {
    var fingerprintCalls = 0
        private set

    override suspend fun fingerprint(): String {
        fingerprintCalls++
        return fingerprints.getOrElse(fingerprintCalls - 1) { fingerprints.last() }
    }

    override suspend fun readWorkbook(): WorkbookSnapshot {
        readFailure?.let { throw ImportPreparationException(it) }
        return workbook
    }
}

private class FakeGateway(
    private var handle: ImportFileHandle?,
    private val failure: ImportFailure? = null,
) : ImportFileGateway {
    var calls = 0
        private set

    override suspend fun chooseFile(): ImportFileHandle? {
        calls++
        failure?.let { throw ImportPreparationException(it) }
        return handle
    }
}

/** Records saves without a database, so the flow can be driven on its own. */
private class RecordingStore(
    private val earlier: List<EarlierImport> = emptyList(),
) : ImportDrafts {
    val saved = mutableListOf<PreparedImportDraft>()

    override suspend fun earlierImportsOf(sha256: String): List<EarlierImport> = earlier

    override suspend fun save(draft: PreparedImportDraft): SavedImportSummary {
        saved += draft
        return SavedImportSummary(
            batchId = IdGenerator.Random.newId(),
            fileName = draft.fileName,
            sheetName = draft.sheetName,
            rawBlockCount = draft.rawBlockCount,
        )
    }
}

class ImportControllerTest {
    private fun workbookOf(vararg sheets: SheetSnapshot) = WorkbookSnapshot(fileName = "ornek.xlsx", sheets = sheets.toList())

    private fun controllerFor(
        handle: ImportFileHandle?,
        store: RecordingStore = RecordingStore(),
        gatewayFailure: ImportFailure? = null,
    ) = ImportController(FakeGateway(handle, gatewayFailure), store) to store

    @Test
    fun `the screen starts with nothing chosen`() {
        val (controller, _) = controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet())))

        assertEquals(ImportScreenState.Idle, controller.state)
    }

    @Test
    fun `a single sheet is chosen without asking and its summary appears`() =
        runBlocking<Unit> {
            val (controller, _) = controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet())))

            controller.chooseFile()

            val ready = assertIs<ImportScreenState.PreviewReady>(controller.state)
            assertEquals("ornek.xlsx", ready.session.fileName)
            assertEquals("Sayfa1", ready.session.selectedSheetName)
            assertEquals(2, assertNotNull(ready.session.draft).rawBlockCount)
        }

    @Test
    fun `more than one sheet is a question for the user`() =
        runBlocking<Unit> {
            val (controller, _) =
                controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet("Sayfa1"), goodSheet("Sayfa2"))))

            controller.chooseFile()

            val selection = assertIs<ImportScreenState.SheetSelection>(controller.state)
            assertEquals(listOf("Sayfa1", "Sayfa2"), selection.session.sheets.map { it.name })
            assertEquals("Sayfa1", selection.session.selectedSheetName, "the first visible sheet is only a suggestion")
        }

    @Test
    fun `sheets keep the order the file had them in`() =
        runBlocking<Unit> {
            val (controller, _) =
                controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet("Zeta"), goodSheet("Alfa"))))

            controller.chooseFile()

            val selection = assertIs<ImportScreenState.SheetSelection>(controller.state)
            assertEquals(listOf("Zeta", "Alfa"), selection.session.sheets.map { it.name })
        }

    @Test
    fun `a hidden sheet is never chosen for the user`() =
        runBlocking<Unit> {
            val hidden = goodSheet("Gizli", SheetVisibility.HIDDEN)
            val (controller, _) = controllerFor(FakeFileHandle(workbook = workbookOf(hidden, goodSheet("Acik"))))

            controller.chooseFile()

            val selection = assertIs<ImportScreenState.SheetSelection>(controller.state)
            assertEquals("Acik", selection.session.selectedSheetName)
            assertEquals(
                SheetVisibility.HIDDEN,
                selection.session.sheets
                    .first { it.name == "Gizli" }
                    .visibility,
                "the user is told which sheets were hidden",
            )
        }

    @Test
    fun `choosing another sheet recalculates the summary`() =
        runBlocking<Unit> {
            val second =
                SheetSnapshot(
                    "Sayfa2",
                    SheetVisibility.VISIBLE,
                    (headerCells + cell(1, 0, "A") + cell(2, 0, "B") + cell(3, 0, "C"))
                        .sortedWith(compareBy({ it.rowIndex }, { it.columnIndex })),
                )
            val (controller, _) = controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet(), second)))
            controller.chooseFile()

            controller.selectSheet("Sayfa2")

            val ready = assertIs<ImportScreenState.PreviewReady>(controller.state)
            assertEquals("Sayfa2", ready.session.selectedSheetName)
            assertEquals(3, assertNotNull(ready.session.draft).rawBlockCount)
        }

    @Test
    fun `an empty sheet cannot be saved`() =
        runBlocking<Unit> {
            val (controller, store) = controllerFor(FakeFileHandle(workbook = workbookOf(emptySheet())))

            controller.chooseFile()
            controller.saveDraft()

            val ready = assertIs<ImportScreenState.PreviewReady>(controller.state)
            assertFalse(ready.session.canSave)
            assertEquals(
                ImportFailure.EMPTY_SHEET,
                assertIs<SheetPreparation.Rejected>(ready.session.preparation).failure,
            )
            assertEquals(emptyList(), store.saved)
        }

    @Test
    fun `cancelling the file dialog is not a failure`() =
        runBlocking<Unit> {
            val (controller, _) = controllerFor(handle = null)

            controller.chooseFile()

            assertEquals(ImportScreenState.Idle, controller.state)
        }

    @Test
    fun `a file that changed while being read is refused and nothing is written`() =
        runBlocking<Unit> {
            val changing =
                FakeFileHandle(workbook = workbookOf(goodSheet()), fingerprints = mutableListOf(SHA_A, SHA_B))
            val (controller, store) = controllerFor(changing)

            controller.chooseFile()

            val failed = assertIs<ImportScreenState.Failed>(controller.state)
            assertEquals(ImportFailure.FILE_CHANGED_WHILE_READING, failed.failure)
            assertEquals(emptyList(), store.saved)
        }

    @Test
    fun `a file that changed between the preview and the save is refused`() =
        runBlocking<Unit> {
            // Read twice at the same fingerprint, then different at save time.
            val changing =
                FakeFileHandle(workbook = workbookOf(goodSheet()), fingerprints = mutableListOf(SHA_A, SHA_A, SHA_B))
            val (controller, store) = controllerFor(changing)
            controller.chooseFile()
            assertIs<ImportScreenState.PreviewReady>(controller.state)

            controller.saveDraft()

            assertEquals(ImportFailure.FILE_CHANGED_WHILE_READING, assertIs<ImportScreenState.Failed>(controller.state).failure)
            assertEquals(emptyList(), store.saved, "a preview of an older version must never be written")
        }

    @Test
    fun `a file that cannot be read shows the reason and lets the user try again`() =
        runBlocking<Unit> {
            val (controller, _) =
                controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet()), readFailure = ImportFailure.ENCRYPTED))

            controller.chooseFile()

            assertEquals(ImportFailure.ENCRYPTED, assertIs<ImportScreenState.Failed>(controller.state).failure)

            // Choosing again from the failed state is allowed.
            val recovering = ImportController(FakeGateway(FakeFileHandle(workbook = workbookOf(goodSheet()))), RecordingStore())
            recovering.chooseFile()
            assertIs<ImportScreenState.PreviewReady>(recovering.state)
        }

    @Test
    fun `an unsupported column stops the save and names the column`() =
        runBlocking<Unit> {
            val wide =
                SheetSnapshot(
                    "Sayfa1",
                    SheetVisibility.VISIBLE,
                    (headerCells + cell(1, 0, "A") + cell(1, 8, "fazladan"))
                        .sortedWith(compareBy({ it.rowIndex }, { it.columnIndex })),
                )
            val (controller, store) = controllerFor(FakeFileHandle(workbook = workbookOf(wide)))

            controller.chooseFile()
            controller.saveDraft()

            val ready = assertIs<ImportScreenState.PreviewReady>(controller.state)
            val rejected = assertIs<SheetPreparation.Rejected>(ready.session.preparation)
            assertEquals(ImportFailure.UNSUPPORTED_COLUMN, rejected.failure)
            assertEquals(8, rejected.columnIndex)
            assertEquals(emptyList(), store.saved)
        }

    @Test
    fun `a first import goes straight through`() =
        runBlocking<Unit> {
            val (controller, store) = controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet())))

            controller.chooseFile()
            controller.saveDraft()

            val saved = assertIs<ImportScreenState.Saved>(controller.state)
            assertEquals("ornek.xlsx", saved.summary.fileName)
            assertEquals(1, store.saved.size)
        }

    @Test
    fun `a file imported before is not saved until the user says so`() =
        runBlocking<Unit> {
            val store = RecordingStore(earlier = listOf(earlierImport()))
            val (controller, _) = controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet())), store)

            controller.chooseFile()
            controller.saveDraft()

            val warning = assertIs<ImportScreenState.DuplicateWarning>(controller.state)
            assertEquals(1, warning.session.earlierImports.size)
            assertEquals(emptyList(), store.saved, "nothing may be written before the user answers")
        }

    @Test
    fun `backing out of a repeat import writes nothing`() =
        runBlocking<Unit> {
            val store = RecordingStore(earlier = listOf(earlierImport()))
            val (controller, _) = controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet())), store)
            controller.chooseFile()
            controller.saveDraft()

            controller.cancelDuplicateImport()

            assertIs<ImportScreenState.PreviewReady>(controller.state)
            assertEquals(emptyList(), store.saved)
        }

    @Test
    fun `agreeing to a repeat import saves a second batch`() =
        runBlocking<Unit> {
            val store = RecordingStore(earlier = listOf(earlierImport()))
            val (controller, _) = controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet())), store)
            controller.chooseFile()
            controller.saveDraft()

            controller.confirmDuplicateImport()

            assertIs<ImportScreenState.Saved>(controller.state)
            assertEquals(1, store.saved.size)
        }

    @Test
    fun `a second click while a save is running does not write twice`() =
        runBlocking<Unit> {
            val store = RecordingStore()
            val (controller, _) = controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet())), store)
            controller.chooseFile()
            val session = assertIs<ImportScreenState.PreviewReady>(controller.state).session

            // The state a running save leaves behind; a second click lands here.
            controller.saveDraft()
            assertIs<ImportScreenState.Saved>(controller.state)
            controller.saveDraft()

            assertEquals(1, store.saved.size, "one action, one import")
            assertNotNull(session.draft)
        }

    @Test
    fun `nothing else can start while a save is running`() {
        val store = RecordingStore()
        val controller = ImportController(FakeGateway(FakeFileHandle(workbook = workbookOf(goodSheet()))), store)
        runBlocking { controller.chooseFile() }
        val session = assertIs<ImportScreenState.PreviewReady>(controller.state).session

        // Standing in the Saving state, the guards must all hold.
        val saving = ImportScreenState.Saving(session)
        assertTrue(saving.session.canSave)
        assertEquals(0, store.saved.size)
    }

    @Test
    fun `the screen is marked busy while reading or saving`() {
        val controller = ImportController(FakeGateway(null), RecordingStore())

        assertFalse(controller.isBusy, "an idle screen is not busy")
    }

    @Test
    fun `starting over clears the screen but keeps what was saved`() =
        runBlocking<Unit> {
            val store = RecordingStore()
            val (controller, _) = controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet())), store)
            controller.chooseFile()
            controller.saveDraft()

            controller.startOver()

            assertEquals(ImportScreenState.Idle, controller.state)
            assertEquals(1, store.saved.size, "the draft stays in the database")
        }

    @Test
    fun `no path can reach the screen state`() =
        runBlocking<Unit> {
            val (controller, _) = controllerFor(FakeFileHandle(workbook = workbookOf(goodSheet())))

            controller.chooseFile()

            val session = assertIs<ImportScreenState.PreviewReady>(controller.state).session
            assertEquals("ornek.xlsx", session.fileName)
            assertFalse(session.fileName.contains('/'))
            // The handle is the only thing that knows about files, and it is not
            // part of the state at all.
            assertTrue(
                ImportSession::class.java.declaredFields.none { it.type.name.contains("java.nio") },
                "no file type may appear in the screen state",
            )
        }

    private fun earlierImport() =
        EarlierImport(
            batchId = IdGenerator.Random.newId(),
            fileName = "onceki.xlsx",
            sheetName = "Sayfa1",
            status = ImportBatchStatus.DRAFT,
        )
}
