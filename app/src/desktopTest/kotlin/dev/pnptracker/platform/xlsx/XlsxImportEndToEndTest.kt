package dev.pnptracker.platform.xlsx

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.domain.importprep.ImportFileGateway
import dev.pnptracker.domain.importprep.unpackArgb
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreenState
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole import, end to end: the committed anonymised workbook, through the
 * real reader and the real gateway, into a real database in a temporary
 * directory.
 *
 * The only thing standing in for something is the file dialog, because opening
 * one would put a window on whoever is running the tests.
 */
class XlsxImportEndToEndTest {
    private lateinit var fileDirectory: Path
    private lateinit var file: Path
    private lateinit var databaseDirectory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var controller: ImportController
    private var realDatabaseExistedBefore = false

    /** Hands back the fixture instead of opening a dialog. */
    private class FixedPicker(
        private val file: Path,
    ) : XlsxFilePicker {
        override suspend fun chooseXlsxFile(): Path = file
    }

    @BeforeTest
    fun setUp() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        fileDirectory = Files.createTempDirectory("pnp-import-e2e")
        file = copyFixtureInto(fileDirectory)
        databaseDirectory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(databaseDirectory.databaseFile)
        controller = newController()
    }

    private fun newController(gateway: ImportFileGateway = XlsxImportFileGateway(FixedPicker(file))) =
        ImportController(gateway, ImportDraftStore(database.importDao()))

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        database.close()
        databaseDirectory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        databaseDirectory.delete()
        check(fileDirectory.startsWith(Path.of(System.getProperty("java.io.tmpdir")))) {
            "refusing to delete $fileDirectory, which is not under the temporary directory"
        }
        fileDirectory.deleteRecursively()
    }

    @Test
    fun `the fixture is recognised, summarised and saved as a draft`() =
        runBlocking<Unit> {
            controller.chooseFile()

            // The fixture has a second, empty sheet, so the user is asked which
            // one to import; the first visible sheet with content is suggested.
            val ready = assertIs<ImportScreenState.SheetSelection>(controller.state)
            val draft = assertNotNull(ready.session.draft)
            assertEquals(FIXTURE_NAME, ready.session.fileName)
            assertEquals(listOf("Sayfa1", "Bos Sayfa"), ready.session.sheets.map { it.name })
            assertEquals("Sayfa1", ready.session.selectedSheetName)
            assertTrue(
                ready.session.sheets
                    .first { it.name == "Bos Sayfa" }
                    .isEmpty,
            )

            // The sheet holds 23 filled cells; seven of them are the headings.
            assertEquals(16, draft.rawBlockCount)
            assertEquals(3, draft.detectedGameCellCount)
            assertEquals(2, draft.pendingGameCompletionHintCount, "two of the three games are filled green")
            assertEquals(1, draft.multiLineCellCount)
            assertEquals(1, draft.startRowIndex)
            assertEquals(3, draft.endRowIndex)
            assertEquals(0, draft.startColumnIndex)
            assertEquals(6, draft.endColumnIndex)

            controller.saveDraft()

            val saved = assertIs<ImportScreenState.Saved>(controller.state)
            assertEquals(16, saved.summary.rawBlockCount)
        }

    @Test
    fun `the stored batch records a draft that created nothing`() =
        runBlocking<Unit> {
            controller.chooseFile()
            controller.saveDraft()
            val summary = assertIs<ImportScreenState.Saved>(controller.state).summary

            val batch = assertNotNull(database.importDao().batchById(summary.batchId))
            assertEquals(ImportBatchStatus.DRAFT, batch.status)
            assertEquals(FIXTURE_NAME, batch.fileName)
            assertEquals(64, batch.sha256.length)
            assertEquals(0, batch.createdGameCount)
            assertEquals(0, batch.createdTaskCount)
            assertEquals(16, batch.rawBlockCount)

            assertEquals(0, database.gameDao().activeCount())
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
        }

    @Test
    fun `every cell of the fixture arrives in the database unchanged`() =
        runBlocking<Unit> {
            controller.chooseFile()
            controller.saveDraft()
            val summary = assertIs<ImportScreenState.Saved>(controller.state).summary

            val blocks = database.importDao().rawBlocksOfBatch(summary.batchId)
            val byCoordinate = blocks.associateBy { it.rowIndex to it.columnIndex }

            assertEquals("Örnek Oyun A", assertNotNull(byCoordinate[1 to 0]).rawText)
            assertEquals("12 KIRMIZI**\n8 MAVİ", assertNotNull(byCoordinate[1 to 1]).rawText)
            assertEquals("15", assertNotNull(byCoordinate[1 to 3]).rawText)
            assertEquals("15.0", assertNotNull(byCoordinate[1 to 4]).rawText)
            assertEquals("   ", assertNotNull(byCoordinate[2 to 1]).rawText, "whitespace is content")
            assertEquals("14.03.2026", assertNotNull(byCoordinate[2 to 3]).rawText)
            assertEquals("Kırmızı kalın ve mavi normal", assertNotNull(byCoordinate[2 to 4]).rawText)
            assertEquals("3 YEŞİL**", assertNotNull(byCoordinate[3 to 1]).rawText)
            assertEquals("6", assertNotNull(byCoordinate[3 to 2]).rawText, "the cached formula result")
            assertEquals("Eksik: 1 SİYAH", assertNotNull(byCoordinate[3 to 5]).rawText)
        }

    @Test
    fun `each column keeps the source type its position gives it`() =
        runBlocking<Unit> {
            controller.chooseFile()
            controller.saveDraft()
            val summary = assertIs<ImportScreenState.Saved>(controller.state).summary

            val blocks = database.importDao().rawBlocksOfBatch(summary.batchId)
            val byCoordinate = blocks.associateBy { it.rowIndex to it.columnIndex }

            assertEquals(SourceColumnType.GAME, assertNotNull(byCoordinate[1 to 0]).sourceColumnType)
            assertEquals(SourceColumnType.THREE_D, assertNotNull(byCoordinate[1 to 1]).sourceColumnType)
            assertEquals(SourceColumnType.CARD, assertNotNull(byCoordinate[2 to 2]).sourceColumnType)
            assertEquals(SourceColumnType.BOARD, assertNotNull(byCoordinate[2 to 3]).sourceColumnType)
            assertEquals(SourceColumnType.SPECIAL, assertNotNull(byCoordinate[2 to 4]).sourceColumnType)
            assertEquals(SourceColumnType.MISSING, assertNotNull(byCoordinate[3 to 5]).sourceColumnType)
            assertEquals(SourceColumnType.BORROWED, assertNotNull(byCoordinate[1 to 6]).sourceColumnType)
        }

    @Test
    fun `the green game cells become questions and nothing else does`() =
        runBlocking<Unit> {
            controller.chooseFile()
            controller.saveDraft()
            val summary = assertIs<ImportScreenState.Saved>(controller.state).summary

            val blocks = database.importDao().rawBlocksOfBatch(summary.batchId)
            val byCoordinate = blocks.associateBy { it.rowIndex to it.columnIndex }

            assertEquals(HintDecision.PENDING, assertNotNull(byCoordinate[1 to 0]).gameCompletionHint)
            assertEquals("FF4EA72E", unpackArgb(assertNotNull(byCoordinate[1 to 0]).fillColorArgb))
            assertEquals(HintDecision.NONE, assertNotNull(byCoordinate[2 to 0]).gameCompletionHint)
            assertEquals(HintDecision.PENDING, assertNotNull(byCoordinate[3 to 0]).gameCompletionHint)
            assertEquals("FF3A7D22", unpackArgb(assertNotNull(byCoordinate[3 to 0]).fillColorArgb))

            // The red fill on a missing part is recorded but says nothing about a game.
            val missing = assertNotNull(byCoordinate[3 to 5])
            assertEquals("FFFF0000", unpackArgb(missing.fillColorArgb))
            assertEquals(HintDecision.NONE, missing.gameCompletionHint)

            assertTrue(
                blocks.none { it.gameCompletionHint == HintDecision.ACCEPTED },
                "no hint may arrive already agreed to",
            )
        }

    @Test
    fun `importing the fixture leaves the file itself untouched`() =
        runBlocking<Unit> {
            val before = sha256Of(file)
            val sizeBefore = Files.size(file)
            val modifiedBefore = Files.getLastModifiedTime(file)

            controller.chooseFile()
            controller.saveDraft()

            assertIs<ImportScreenState.Saved>(controller.state)
            assertEquals(before, sha256Of(file))
            assertEquals(sizeBefore, Files.size(file))
            assertEquals(modifiedBefore, Files.getLastModifiedTime(file))
            assertEquals(emptyList(), openHandlesTo(file), "the import is still holding the file open")
            assertNoSiblingsCreated(fileDirectory, setOf(FIXTURE_NAME))
        }

    @Test
    fun `importing the same file again warns first and only saves when told to`() =
        runBlocking<Unit> {
            controller.chooseFile()
            controller.saveDraft()
            assertIs<ImportScreenState.Saved>(controller.state)

            val second = newController()
            second.chooseFile()
            second.saveDraft()

            val warning = assertIs<ImportScreenState.DuplicateWarning>(second.state)
            assertEquals(1, warning.session.earlierImports.size)
            assertEquals(1, database.importDao().allBatches().size, "nothing written before the user answers")

            second.confirmDuplicateImport()

            assertIs<ImportScreenState.Saved>(second.state)
            val batches = database.importDao().allBatches()
            assertEquals(2, batches.size)
            assertEquals(2, batches.map { it.id }.toSet().size, "each import is its own batch")
            assertEquals(1, batches.map { it.sha256 }.toSet().size, "both record the same file")
        }

    @Test
    fun `backing out of a repeat import leaves the database as it was`() =
        runBlocking<Unit> {
            controller.chooseFile()
            controller.saveDraft()
            val after = database.importDao().allBatches()

            val second = newController()
            second.chooseFile()
            second.saveDraft()
            second.cancelDuplicateImport()

            assertIs<ImportScreenState.PreviewReady>(second.state)
            assertEquals(after.map { it.id }, database.importDao().allBatches().map { it.id })
            assertEquals(16, database.importDao().rawBlocksOfBatch(after.single().id).size)
        }

    @Test
    fun `a saved draft is still there after the database is closed and reopened`() =
        runBlocking<Unit> {
            controller.chooseFile()
            controller.saveDraft()
            val summary = assertIs<ImportScreenState.Saved>(controller.state).summary
            database.close()

            database = DatabaseFactory().open(databaseDirectory.databaseFile)
            val batch = assertNotNull(database.importDao().batchById(summary.batchId))
            val blocks = database.importDao().rawBlocksOfBatch(summary.batchId)

            assertEquals(ImportBatchStatus.DRAFT, batch.status)
            assertEquals(16, blocks.size)
            assertEquals("12 KIRMIZI**\n8 MAVİ", blocks.first { it.rowIndex == 1 && it.columnIndex == 1 }.rawText)
        }

    @Test
    fun `a file that is not a spreadsheet is refused before anything is written`() =
        runBlocking<Unit> {
            val notASpreadsheet = fileDirectory.resolve("metin.xlsx")
            Files.writeString(notASpreadsheet, "bu bir excel dosyası değil")
            val refusing =
                ImportController(
                    XlsxImportFileGateway(FixedPicker(notASpreadsheet)),
                    ImportDraftStore(database.importDao()),
                )

            refusing.chooseFile()

            val failed = assertIs<ImportScreenState.Failed>(refusing.state)
            assertEquals(
                dev.pnptracker.domain.importprep.ImportFailure.NOT_AN_XLSX_FILE,
                failed.failure,
            )
            assertEquals(emptyList(), database.importDao().allBatches())
        }

    @Test
    fun `a file with the wrong extension never reaches the reader`() =
        runBlocking<Unit> {
            val wrongName = fileDirectory.resolve("kitap.xls")
            Files.copy(file, wrongName)
            val refusing =
                ImportController(
                    XlsxImportFileGateway(FixedPicker(wrongName)),
                    ImportDraftStore(database.importDao()),
                )

            refusing.chooseFile()

            assertEquals(
                dev.pnptracker.domain.importprep.ImportFailure.NOT_AN_XLSX_FILE,
                assertIs<ImportScreenState.Failed>(refusing.state).failure,
            )
        }

    @Test
    fun `a cancelled dialog leaves the screen where it was`() =
        runBlocking<Unit> {
            val cancelling =
                ImportController(
                    XlsxImportFileGateway(
                        object : XlsxFilePicker {
                            override suspend fun chooseXlsxFile(): Path? = null
                        },
                    ),
                    ImportDraftStore(database.importDao()),
                )

            cancelling.chooseFile()

            assertEquals(ImportScreenState.Idle, cancelling.state)
            assertEquals(emptyList(), database.importDao().allBatches())
        }
}
