package dev.pnptracker.platform.xlsx

import dev.pnptracker.domain.spreadsheet.SheetVisibility
import dev.pnptracker.domain.spreadsheet.SpreadsheetCellKind
import dev.pnptracker.domain.spreadsheet.WorkbookSnapshot
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
 * Reads the committed anonymised workbook end to end.
 *
 * Every expectation here is a property of that fixture, which is small enough to
 * describe exactly: three sample games across four rows and seven columns, plus
 * a second, empty sheet.
 */
class XlsxWorkbookReaderTest {
    private lateinit var directory: Path
    private lateinit var file: Path
    private lateinit var workbook: WorkbookSnapshot

    @BeforeTest
    fun readTheFixture() {
        directory = Files.createTempDirectory("pnp-xlsx-test")
        file = copyFixtureInto(directory)
        workbook = XlsxWorkbookReader().read(file)
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun removeTemporaryDirectory() {
        // Only ever the directory this test created, under the system temp root.
        check(directory.startsWith(Path.of(System.getProperty("java.io.tmpdir")))) {
            "refusing to delete $directory, which is not under the temporary directory"
        }
        directory.deleteRecursively()
    }

    private val sheet get() = assertNotNull(workbook.sheetNamed("Sayfa1"))

    @Test
    fun `the workbook keeps the file name and nothing about where the file lived`() {
        assertEquals(FIXTURE_NAME, workbook.fileName)
    }

    @Test
    fun `sheets keep their names, their order and their visibility`() {
        assertEquals(listOf("Sayfa1", "Bos Sayfa"), workbook.sheets.map { it.name })
        assertEquals(listOf(SheetVisibility.VISIBLE, SheetVisibility.VISIBLE), workbook.sheets.map { it.visibility })
    }

    @Test
    fun `the range is zero based, inclusive, and covers only the filled cells`() {
        // The file declares A1:I6 because a cell far below was given a fill, but
        // nothing was ever written there.
        assertEquals(0, sheet.startRowIndex)
        assertEquals(3, sheet.endRowIndex)
        assertEquals(0, sheet.startColumnIndex)
        assertEquals(6, sheet.endColumnIndex)
    }

    @Test
    fun `cells come back row by row, then column by column`() {
        val order = sheet.cells.map { it.rowIndex to it.columnIndex }

        assertEquals(order.sortedWith(compareBy({ it.first }, { it.second })), order)
        assertEquals(order.size, order.toSet().size, "the same cell was captured twice")
    }

    @Test
    fun `a cell that only carries a fill colour produces nothing`() {
        // Row 1 column 2 and row 5 column 8 are both styled and both empty.
        assertNull(sheet.cellAt(1, 2))
        assertNull(sheet.cellAt(5, 8))
    }

    @Test
    fun `a whitespace only cell is real content and is not trimmed`() {
        assertEquals("   ", assertNotNull(sheet.cellAt(2, 1)).rawText)
    }

    @Test
    fun `a multi line cell keeps its line breaks and its double star exactly`() {
        val cell = assertNotNull(sheet.cellAt(1, 1))

        assertEquals("12 KIRMIZI**\n8 MAVİ", cell.rawText)
        assertEquals(2, cell.rawText.count { it == '\n' } + 1, "the cell should still be two lines")
        assertTrue(cell.rawText.contains("**"), "the completion hint marker must survive reading")
    }

    @Test
    fun `turkish letters are read back unchanged`() {
        assertEquals("3 YEŞİL**", assertNotNull(sheet.cellAt(3, 1)).rawText)
        assertEquals("Eksik: 1 SİYAH", assertNotNull(sheet.cellAt(3, 5)).rawText)
        assertEquals("Ödünç Parçalar", assertNotNull(sheet.cellAt(0, 6)).rawText)
    }

    @Test
    fun `a whole number under the general format does not grow a decimal point`() {
        val cell = assertNotNull(sheet.cellAt(1, 3))

        assertEquals("15", cell.rawText)
        assertEquals(SpreadsheetCellKind.NUMBER, cell.kind)
    }

    @Test
    fun `a number formatted to one decimal keeps the decimal the user chose`() {
        assertEquals("15.0", assertNotNull(sheet.cellAt(1, 4)).rawText)
    }

    @Test
    fun `a fractional number and a date read the way the sheet showed them`() {
        assertEquals("12.5", assertNotNull(sheet.cellAt(2, 2)).rawText)
        assertEquals("14.03.2026", assertNotNull(sheet.cellAt(2, 3)).rawText)
    }

    @Test
    fun `a boolean cell reads as text rather than as a number`() {
        val cell = assertNotNull(sheet.cellAt(2, 5))

        assertEquals("TRUE", cell.rawText)
        assertEquals(SpreadsheetCellKind.BOOLEAN, cell.kind)
    }

    @Test
    fun `a formula keeps both its text and the result stored in the file`() {
        val cell = assertNotNull(sheet.cellAt(3, 2))

        assertEquals(SpreadsheetCellKind.FORMULA, cell.kind)
        assertEquals("2*3", cell.formula)
        assertEquals("6", cell.rawText, "the cached result should be shown, not recalculated")
    }

    @Test
    fun `a theme colour is resolved to the colour the theme defines`() {
        assertEquals("FF4EA72E", assertNotNull(sheet.cellAt(1, 0)).fillColorArgb)
    }

    @Test
    fun `a theme colour with a tint is darkened rather than reported untinted`() {
        // Accent 6 is FF4EA72E; the same colour at the standard "darker 25%"
        // tint is FF3A7D22. The library resolves the theme but drops the tint,
        // so a reader that trusted it would call this cell plain green.
        val cell = assertNotNull(sheet.cellAt(3, 0))

        assertEquals("FF3A7D22", cell.fillColorArgb)
        assertTrue(cell.fillColorArgb != assertNotNull(sheet.cellAt(1, 0)).fillColorArgb)
    }

    @Test
    fun `a directly specified colour and an indexed palette colour both resolve`() {
        assertEquals("FFFF0000", assertNotNull(sheet.cellAt(3, 5)).fillColorArgb)
        assertEquals("FFFFFF99", assertNotNull(sheet.cellAt(2, 6)).fillColorArgb)
    }

    @Test
    fun `a cell with no fill has no fill colour`() {
        val cell = assertNotNull(sheet.cellAt(2, 0))

        assertNull(cell.fillColorArgb)
        // The font colour is not null here, and should not be: this workbook
        // stores the default font as palette entry 8, so black is what the file
        // actually says rather than something the reader invented. A colour that
        // really is absent is covered in XlsxReaderEdgeCaseTest.
        assertEquals("FF000000", cell.fontColorArgb)
    }

    @Test
    fun `rich text runs carry their own colour and weight`() {
        val cell = assertNotNull(sheet.cellAt(2, 4))

        assertEquals("Kırmızı kalın ve mavi normal", cell.rawText)
        assertEquals(2, cell.richTextRuns.size)

        val (bold, plain) = cell.richTextRuns
        assertEquals(0, bold.startIndex)
        assertEquals(13, bold.endIndex)
        assertEquals("Kırmızı kalın", bold.text)
        assertEquals("FFFF0000", bold.fontColorArgb)
        assertTrue(bold.isBold)

        assertEquals(13, plain.startIndex)
        assertEquals(28, plain.endIndex)
        assertEquals(" ve mavi normal", plain.text)
        assertEquals("FF0000FF", plain.fontColorArgb)
        assertTrue(!plain.isBold)
    }

    @Test
    fun `rich text runs cover the cell text exactly and in order`() {
        val cell = assertNotNull(sheet.cellAt(2, 4))

        assertEquals(cell.rawText, cell.richTextRuns.joinToString("") { it.text })
        cell.richTextRuns.forEach { run ->
            assertEquals(cell.rawText.substring(run.startIndex, run.endIndex), run.text)
        }
    }

    @Test
    fun `a plain cell is not given invented formatting runs`() {
        assertEquals(emptyList(), assertNotNull(sheet.cellAt(0, 0)).richTextRuns)
        assertEquals(emptyList(), assertNotNull(sheet.cellAt(3, 1)).richTextRuns)
    }

    @Test
    fun `an empty sheet has no cells and no range at all`() {
        val empty = assertNotNull(workbook.sheetNamed("Bos Sayfa"))

        assertTrue(empty.isEmpty)
        assertEquals(emptyList(), empty.cells)
        assertNull(empty.startRowIndex)
        assertNull(empty.endRowIndex)
        assertNull(empty.startColumnIndex)
        assertNull(empty.endColumnIndex)
    }

    @Test
    fun `reading leaves the file byte for byte as it was`() {
        val fresh = Files.createTempDirectory("pnp-xlsx-untouched")
        try {
            val copy = copyFixtureInto(fresh)
            val before = sha256Of(copy)
            val sizeBefore = Files.size(copy)
            val modifiedBefore = Files.getLastModifiedTime(copy)

            XlsxWorkbookReader().read(copy)

            assertEquals(before, sha256Of(copy))
            assertEquals(sizeBefore, Files.size(copy))
            assertEquals(modifiedBefore, Files.getLastModifiedTime(copy))
            assertNoSiblingsCreated(fresh, setOf(FIXTURE_NAME))
        } finally {
            Files.walk(fresh).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    @Test
    fun `reading closes everything it opened`() {
        val fresh = Files.createTempDirectory("pnp-xlsx-handles")
        val copy = copyFixtureInto(fresh)

        XlsxWorkbookReader().read(copy)

        assertEquals(emptyList(), openHandlesTo(copy), "the reader is still holding the file open")
        // A file nothing holds open can be renamed and removed straight away.
        val renamed = fresh.resolve("renamed.xlsx")
        Files.move(copy, renamed)
        Files.delete(renamed)
        Files.delete(fresh)
    }
}
