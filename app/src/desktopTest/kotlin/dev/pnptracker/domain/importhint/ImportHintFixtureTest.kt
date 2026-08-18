package dev.pnptracker.domain.importhint

import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.SheetSnapshot
import dev.pnptracker.platform.xlsx.XlsxWorkbookReader
import dev.pnptracker.platform.xlsx.copyFixtureInto
import dev.pnptracker.platform.xlsx.sha256Of
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the whole chain the import will use: the committed anonymised workbook,
 * through the reader, into the hint detectors.
 *
 * Nothing is written anywhere. The point is that the detectors work on what the
 * reader really produces, rather than on cells written by hand in a test.
 */
class ImportHintFixtureTest {
    private lateinit var directory: Path
    private lateinit var sheet: SheetSnapshot

    private val vocabulary =
        ColorVocabulary.of(
            listOf(
                ColorVocabularyEntry("Kırmızı"),
                ColorVocabularyEntry("Mavi"),
                ColorVocabularyEntry("Açık Mavi"),
                ColorVocabularyEntry("Yeşil"),
            ),
        )
    private val analyzer = ImportHintAnalyzer(vocabulary)

    @BeforeTest
    fun readTheFixture() {
        directory = Files.createTempDirectory("pnp-hint-fixture")
        val workbook = XlsxWorkbookReader().read(copyFixtureInto(directory))
        sheet = assertNotNull(workbook.sheetNamed("Sayfa1"))
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun removeTemporaryDirectory() {
        check(directory.startsWith(Path.of(System.getProperty("java.io.tmpdir")))) {
            "refusing to delete $directory, which is not under the temporary directory"
        }
        directory.deleteRecursively()
    }

    private fun analyze(
        rowIndex: Int,
        columnIndex: Int,
    ): CellHintAnalysis {
        val cell = assertNotNull(sheet.cellAt(rowIndex, columnIndex), "no cell at $rowIndex,$columnIndex")
        val column = assertNotNull(ReferenceColumnLayout.suggestionFor(columnIndex))
        return analyzer.analyze(cell, column.sourceColumnType)
    }

    @Test
    fun `the theme green game cell asks whether the game is finished`() {
        val analysis = analyze(rowIndex = 1, columnIndex = 0)

        val completion = assertNotNull(analysis.gameCompletion, "the green fill should be noticed")
        assertEquals("FF4EA72E", completion.fillColorArgb)
        assertEquals(HintConfidence.HIGH, completion.confidence)
        assertEquals(HintDecision.PENDING, completion.decision)
    }

    @Test
    fun `a game cell with no fill is not asked about`() {
        assertNull(analyze(rowIndex = 2, columnIndex = 0).gameCompletion)
    }

    @Test
    fun `the tinted green game cell is noticed as well`() {
        val completion = assertNotNull(analyze(rowIndex = 3, columnIndex = 0).gameCompletion)

        assertEquals("FF3A7D22", completion.fillColorArgb, "the darkened theme green")
        assertEquals(HintConfidence.HIGH, completion.confidence)
    }

    @Test
    fun `markers are found in the multi line cell and the raw text keeps them`() {
        val analysis = analyze(rowIndex = 1, columnIndex = 1)

        assertEquals("12 KIRMIZI**\n8 MAVİ", analysis.rawText)
        assertEquals(1, analysis.completionMarkers.size)
        assertEquals(
            "**",
            analysis.completionMarkers
                .single()
                .evidence
                .textIn(analysis.rawText),
        )
        assertEquals("12 KIRMIZI\n8 MAVİ", analysis.displayText)
        assertTrue(analysis.rawText.contains("**"), "the record of the file must not lose the marker")
    }

    @Test
    fun `the three d column suggests the three d pool`() {
        val suggestion = assertNotNull(analyze(rowIndex = 3, columnIndex = 1).columnSuggestion)

        assertEquals(SourceColumnType.THREE_D, suggestion.sourceColumnType)
        assertEquals(PoolType.THREE_D, suggestion.suggestedPoolType)
    }

    @Test
    fun `the missing column suggests no pool and asks to be classified`() {
        val suggestion = assertNotNull(analyze(rowIndex = 3, columnIndex = 5).columnSuggestion)

        assertEquals(SourceColumnType.MISSING, suggestion.sourceColumnType)
        assertNull(suggestion.suggestedPoolType)
        assertTrue(suggestion.needsClassification)
    }

    @Test
    fun `the rich text cell yields no colour choice from its lettering`() {
        val cell = assertNotNull(sheet.cellAt(2, 4))
        val analysis = analyze(rowIndex = 2, columnIndex = 4)

        assertEquals(2, cell.richTextRuns.size, "the fixture cell really is two coloured runs")
        assertEquals(listOf("FFFF0000", "FF0000FF"), cell.richTextRuns.map { it.fontColorArgb })
        assertEquals(emptyList(), analysis.alternativeColors, "red and blue lettering must create nothing")
        assertEquals(emptyList(), analysis.completionMarkers)
    }

    @Test
    fun `every hint the fixture produces is still waiting for the user`() {
        val everyHint =
            sheet.cells.flatMap { cell ->
                val column = ReferenceColumnLayout.suggestionFor(cell.columnIndex) ?: return@flatMap emptyList()
                analyzer.analyze(cell, column.sourceColumnType).hints
            }

        assertTrue(everyHint.isNotEmpty(), "the fixture should produce something to ask about")
        everyHint.forEach { hint ->
            assertEquals(HintDecision.PENDING, hint.decision, "$hint arrived already agreed to")
        }
    }

    @Test
    fun `analysing the fixture touches neither the workbook nor the real database`() {
        val realDatabase = TemporaryDatabaseDirectory.realApplicationDatabaseFile()
        val databaseExisted = Files.exists(realDatabase)
        val databaseBefore = if (databaseExisted) sha256Of(realDatabase) else null
        val workbookFile = directory.resolve("sample-import.xlsx")
        val workbookBefore = sha256Of(workbookFile)

        sheet.cells.forEach { cell ->
            ReferenceColumnLayout.suggestionFor(cell.columnIndex)?.let {
                analyzer.analyze(cell, it.sourceColumnType)
            }
        }

        assertEquals(workbookBefore, sha256Of(workbookFile))
        assertEquals(databaseExisted, Files.exists(realDatabase))
        assertEquals(databaseBefore, if (databaseExisted) sha256Of(realDatabase) else null)
    }
}
