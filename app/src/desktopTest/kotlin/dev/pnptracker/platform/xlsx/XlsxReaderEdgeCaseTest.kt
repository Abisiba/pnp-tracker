package dev.pnptracker.platform.xlsx

import dev.pnptracker.domain.spreadsheet.SheetVisibility
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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
 * Cases that are easier to state as a workbook built on the spot than as another
 * corner of the committed fixture. The workbooks are written into a temporary
 * directory and thrown away with it.
 */
class XlsxReaderEdgeCaseTest {
    private lateinit var directory: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("pnp-xlsx-edge")
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun removeTemporaryDirectory() {
        check(directory.startsWith(Path.of(System.getProperty("java.io.tmpdir")))) {
            "refusing to delete $directory, which is not under the temporary directory"
        }
        directory.deleteRecursively()
    }

    private fun write(
        name: String,
        build: (XSSFWorkbook) -> Unit,
    ): Path {
        val file = directory.resolve(name)
        XSSFWorkbook().use { workbook ->
            build(workbook)
            Files.newOutputStream(file).use { out -> workbook.write(out) }
        }
        return file
    }

    @Test
    fun `a workbook with no sheet content at all reports empty sheets`() {
        val file =
            write("empty.xlsx") { workbook ->
                workbook.createSheet("Bir")
                workbook.createSheet("Iki")
            }

        val snapshot = XlsxWorkbookReader().read(file)

        assertEquals(listOf("Bir", "Iki"), snapshot.sheets.map { it.name })
        snapshot.sheets.forEach { sheet ->
            assertTrue(sheet.isEmpty)
            assertNull(sheet.startRowIndex)
            assertNull(sheet.endColumnIndex)
        }
    }

    @Test
    fun `sheets keep the order they have in the file, not alphabetical order`() {
        val file =
            write("order.xlsx") { workbook ->
                listOf("Zeta", "Alfa", "Beta").forEach { name ->
                    workbook
                        .createSheet(name)
                        .createRow(0)
                        .createCell(0)
                        .setCellValue(name)
                }
            }

        assertEquals(listOf("Zeta", "Alfa", "Beta"), XlsxWorkbookReader().read(file).sheets.map { it.name })
    }

    @Test
    fun `a hidden sheet is read rather than skipped`() {
        val file =
            write("hidden.xlsx") { workbook ->
                workbook
                    .createSheet("Gorunur")
                    .createRow(0)
                    .createCell(0)
                    .setCellValue("a")
                workbook
                    .createSheet("Gizli")
                    .createRow(0)
                    .createCell(0)
                    .setCellValue("b")
                workbook.setSheetHidden(1, true)
            }

        val snapshot = XlsxWorkbookReader().read(file)

        assertEquals(SheetVisibility.HIDDEN, assertNotNull(snapshot.sheetNamed("Gizli")).visibility)
        assertEquals("b", assertNotNull(snapshot.sheetNamed("Gizli")).cellAt(0, 0)?.rawText)
    }

    @Test
    fun `formatting a whole row without writing anything does not widen the range`() {
        val file =
            write("formatted.xlsx") { workbook ->
                val sheet = workbook.createSheet("S")
                val fill = workbook.createCellStyle()
                fill.setFillForegroundColor(XSSFColor(byteArrayOf(0x11, 0x22, 0x33), null))
                fill.fillPattern = FillPatternType.SOLID_FOREGROUND
                sheet.createRow(0).createCell(0).setCellValue("tek")
                val styledOnly = sheet.createRow(9)
                (0..4).forEach { column -> styledOnly.createCell(column).cellStyle = fill }
            }

        val sheet = assertNotNull(XlsxWorkbookReader().read(file).sheetNamed("S"))

        assertEquals(1, sheet.cells.size)
        assertEquals(0, sheet.startRowIndex)
        assertEquals(0, sheet.endRowIndex)
        assertEquals(0, sheet.endColumnIndex)
    }

    @Test
    fun `a colour the file never states does not become black`() {
        val file =
            write("nocolour.xlsx") { workbook ->
                val sheet = workbook.createSheet("S")
                val pattern = workbook.createCellStyle()
                // A pattern fill has no single effective colour to report.
                pattern.fillPattern = FillPatternType.THIN_HORZ_BANDS
                val cell = sheet.createRow(0).createCell(0)
                cell.setCellValue("desenli")
                cell.cellStyle = pattern
                sheet.createRow(1).createCell(0).setCellValue("sade")
            }

        val sheet = assertNotNull(XlsxWorkbookReader().read(file).sheetNamed("S"))

        assertNull(assertNotNull(sheet.cellAt(0, 0)).fillColorArgb, "a banded fill has no one colour")
        assertNull(assertNotNull(sheet.cellAt(1, 0)).fillColorArgb, "an unfilled cell has no colour")
    }

    @Test
    fun `an empty text cell is not content but a formula with no result still is`() {
        val file =
            write("emptyish.xlsx") { workbook ->
                val sheet = workbook.createSheet("S")
                val row = sheet.createRow(0)
                row.createCell(0).setCellValue("")
                row.createCell(1).setCellFormula("IF(TRUE,\"\",\"\")")
                row.createCell(2).setCellValue("son")
            }

        val sheet = assertNotNull(XlsxWorkbookReader().read(file).sheetNamed("S"))

        assertNull(sheet.cellAt(0, 0), "an empty string is not something the user wrote down")
        val formula = assertNotNull(sheet.cellAt(0, 1), "a formula is content even when its result is empty")
        assertEquals("IF(TRUE,\"\",\"\")", formula.formula)
        assertEquals("son", assertNotNull(sheet.cellAt(0, 2)).rawText)
    }

    @Test
    fun `the reader never recalculates a formula`() {
        // The stored result deliberately disagrees with the formula. A reader
        // that evaluated would "fix" it and quietly replace what the user saw.
        val file =
            write("stale.xlsx") { workbook ->
                val cell = workbook.createSheet("S").createRow(0).createCell(0)
                cell.setCellFormula("1+1")
                cell.setCellValue(99.0)
            }
        val before = sha256Of(file)

        val cell = assertNotNull(XlsxWorkbookReader().read(file).sheetNamed("S")?.cellAt(0, 0))

        assertEquals("1+1", cell.formula)
        assertEquals("99", cell.rawText)
        assertEquals(before, sha256Of(file), "reading must not write the recalculated value back")
    }
}
