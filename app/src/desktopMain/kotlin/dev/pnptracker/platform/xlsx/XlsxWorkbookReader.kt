package dev.pnptracker.platform.xlsx

import dev.pnptracker.domain.spreadsheet.CellSnapshot
import dev.pnptracker.domain.spreadsheet.RichTextRunSnapshot
import dev.pnptracker.domain.spreadsheet.SheetSnapshot
import dev.pnptracker.domain.spreadsheet.SheetVisibility
import dev.pnptracker.domain.spreadsheet.SpreadsheetCellKind
import dev.pnptracker.domain.spreadsheet.WorkbookSnapshot
import org.apache.poi.EmptyFileException
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.OldFileFormatException
import org.apache.poi.UnsupportedFileFormatException
import org.apache.poi.ooxml.POIXMLException
import org.apache.poi.openxml4j.exceptions.InvalidFormatException
import org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException
import org.apache.poi.openxml4j.exceptions.OpenXML4JRuntimeException
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.util.RecordFormatException
import org.apache.poi.xssf.model.ThemesTable
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Locale
import org.apache.poi.ss.usermodel.SheetVisibility as PoiSheetVisibility

/**
 * Reads an `.xlsx` file into a snapshot that no longer depends on the file or on
 * the library that parsed it.
 *
 * The file is opened read only and is never written to, moved or locked beyond
 * the call: everything the reader opens is closed again before it returns, so
 * the file can be renamed or deleted straight afterwards.
 *
 * Display text is produced the way the spreadsheet itself would show it, using a
 * fixed [locale] so that the same file reads the same on every machine.
 */
class XlsxWorkbookReader(
    locale: Locale = Locale.ROOT,
) {
    private val formatter = DataFormatter(locale)

    /**
     * @throws XlsxReadException if the file is missing, unreadable, or is not a
     *   spreadsheet this application can open. Nothing is written in any case.
     */
    fun read(file: Path): WorkbookSnapshot {
        val fileName = file.fileName?.toString() ?: throw XlsxReadException(XlsxReadFailure.FILE_NOT_FOUND, "?")
        requireReadableFile(file, fileName)

        // Closing the workbook reverts the read only package, so exactly one of
        // the two is closed and the file is released either way.
        var pkg: OPCPackage? = null
        var workbook: XSSFWorkbook? = null
        try {
            pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)
            workbook = XSSFWorkbook(pkg)
            return snapshotOf(workbook, fileName)
            // Each of these is a way the library reports a file it cannot use.
            // Nothing broader is caught: turning the snapshot together happens
            // inside this block too, so a blanket `RuntimeException` would dress
            // up a bug of ours as a complaint about the user's file.
        } catch (cause: IOException) {
            reportUnusable(cause, fileName)
        } catch (cause: InvalidFormatException) {
            reportUnusable(cause, fileName)
        } catch (cause: EncryptedDocumentException) {
            reportUnusable(cause, fileName)
        } catch (cause: EmptyFileException) {
            reportUnusable(cause, fileName)
        } catch (cause: UnsupportedFileFormatException) {
            reportUnusable(cause, fileName)
        } catch (cause: OpenXML4JRuntimeException) {
            reportUnusable(cause, fileName)
        } catch (cause: POIXMLException) {
            reportUnusable(cause, fileName)
        } catch (cause: RecordFormatException) {
            reportUnusable(cause, fileName)
        } finally {
            if (workbook != null) workbook.close() else pkg?.revert()
        }
    }

    private fun requireReadableFile(
        file: Path,
        fileName: String,
    ) {
        if (!Files.exists(file)) throw XlsxReadException(XlsxReadFailure.FILE_NOT_FOUND, fileName)
        if (Files.isDirectory(file)) throw XlsxReadException(XlsxReadFailure.NOT_AN_XLSX_FILE, fileName)
        if (!Files.isReadable(file)) throw XlsxReadException(XlsxReadFailure.NOT_READABLE, fileName)
    }

    private fun snapshotOf(
        workbook: XSSFWorkbook,
        fileName: String,
    ): WorkbookSnapshot {
        val themes = workbook.theme
        val sheets =
            (0 until workbook.numberOfSheets).map { index ->
                SheetSnapshot(
                    name = workbook.getSheetName(index),
                    visibility = visibilityOf(workbook.getSheetVisibility(index)),
                    cells = cellsOf(workbook.getSheetAt(index), themes),
                )
            }
        return WorkbookSnapshot(fileName = fileName, sheets = sheets)
    }

    private fun cellsOf(
        sheet: XSSFSheet,
        themes: ThemesTable?,
    ): List<CellSnapshot> =
        sheet
            .flatMap { row -> row.filterNotNull() }
            .filterIsInstance<XSSFCell>()
            .mapNotNull { cell -> snapshotOf(cell, themes) }
            .sortedWith(compareBy({ it.rowIndex }, { it.columnIndex }))

    /** Returns null for a cell that only ever received formatting. */
    private fun snapshotOf(
        cell: XSSFCell,
        themes: ThemesTable?,
    ): CellSnapshot? {
        val isFormula = cell.cellType == CellType.FORMULA
        val rawText = displayTextOf(cell)
        // A cell holding nothing but a fill colour is not content and must not
        // stretch the sheet range. Whitespace, on the other hand, was typed by
        // someone and is kept exactly as it is.
        if (rawText.isEmpty() && !isFormula) return null

        val style = cell.cellStyle
        val font = style.font
        return CellSnapshot(
            rowIndex = cell.rowIndex,
            columnIndex = cell.columnIndex,
            rawText = rawText,
            kind = kindOf(cell),
            formula = if (isFormula) cell.cellFormula else null,
            fillColorArgb = fillOf(style, themes),
            fontColorArgb = resolveArgb(font.xssfColor, themes),
            isBold = font.bold,
            richTextRuns = runsOf(cell, rawText, themes),
        )
    }

    /** Only a solid fill says anything; a pattern fill has no single colour. */
    private fun fillOf(
        style: XSSFCellStyle,
        themes: ThemesTable?,
    ): String? {
        if (style.fillPattern != FillPatternType.SOLID_FOREGROUND) return null
        return resolveArgb(style.fillForegroundColorColor, themes)
    }

    private fun kindOf(cell: XSSFCell): SpreadsheetCellKind =
        when (cell.cellType) {
            CellType.FORMULA -> SpreadsheetCellKind.FORMULA
            CellType.NUMERIC -> SpreadsheetCellKind.NUMBER
            CellType.BOOLEAN -> SpreadsheetCellKind.BOOLEAN
            CellType.ERROR -> SpreadsheetCellKind.ERROR
            else -> SpreadsheetCellKind.TEXT
        }

    /**
     * The text the spreadsheet would show.
     *
     * A whole number under the general format reads as `15` rather than `15.0`,
     * and a cell formatted to one decimal keeps its `15.0`, because the number
     * format is part of what the user wrote down.
     *
     * A formula is never recalculated: the result stored in the file is the one
     * the user last saw, and evaluating would mean changing the workbook.
     */
    private fun displayTextOf(cell: XSSFCell): String =
        if (cell.cellType == CellType.FORMULA) cachedTextOf(cell) else formatter.formatCellValue(cell)

    private fun cachedTextOf(cell: XSSFCell): String =
        when (cell.cachedFormulaResultType) {
            CellType.NUMERIC ->
                formatter.formatRawCellContents(
                    cell.numericCellValue,
                    cell.cellStyle.dataFormat.toInt(),
                    cell.cellStyle.dataFormatString,
                )

            CellType.STRING -> cell.richStringCellValue.string
            CellType.BOOLEAN -> cell.booleanCellValue.toString().uppercase()
            CellType.ERROR -> FormulaError.forInt(cell.errorCellValue).string
            // The file carries a formula but no usable result. Saying so beats
            // inventing one, and the formula itself is kept either way.
            else -> ""
        }

    private fun runsOf(
        cell: XSSFCell,
        rawText: String,
        themes: ThemesTable?,
    ): List<RichTextRunSnapshot> {
        if (cell.cellType != CellType.STRING) return emptyList()
        val rich = cell.richStringCellValue
        if (!rich.hasFormatting() || rich.numFormattingRuns() == 0) return emptyList()

        return (0 until rich.numFormattingRuns()).mapNotNull { index ->
            val start = rich.getIndexOfFormattingRun(index)
            val end = start + rich.getLengthOfFormattingRun(index)
            if (start < 0 || end > rawText.length || end <= start) return@mapNotNull null
            // A run without its own font keeps whatever the cell's font says,
            // rather than pretending the formatting is unknown.
            val font = rich.getFontOfFormattingRun(index) ?: cell.cellStyle.font
            RichTextRunSnapshot(
                startIndex = start,
                endIndex = end,
                text = rawText.substring(start, end),
                fontColorArgb = resolveArgb(font?.xssfColor, themes),
                isBold = font?.bold ?: false,
            )
        }
    }

    private fun visibilityOf(visibility: PoiSheetVisibility): SheetVisibility =
        when (visibility) {
            PoiSheetVisibility.VISIBLE -> SheetVisibility.VISIBLE
            PoiSheetVisibility.HIDDEN -> SheetVisibility.HIDDEN
            PoiSheetVisibility.VERY_HIDDEN -> SheetVisibility.VERY_HIDDEN
        }
}

/**
 * Reports [cause] as a file the application cannot use — but only if it really
 * is one.
 *
 * Anything the classifier does not recognise is thrown on untouched, as the very
 * same object, so a null dereference or a broken invariant of ours surfaces as
 * the bug it is instead of as "this file is damaged".
 */
internal fun reportUnusable(
    cause: Throwable,
    fileName: String,
): Nothing {
    val failure = failureOf(cause) ?: throw cause
    throw XlsxReadException(failure, fileName, cause)
}

/**
 * Maps a library failure onto the narrow set of reasons the application knows,
 * or to null when the failure is not about the file at all.
 *
 * Only the exception type decides, because the type is the library's contract.
 * The one exception is the safety limits below, which the library reports as a
 * plain [IOException] with no type of their own.
 *
 * Fatal virtual machine problems are not failures of a file: nothing here
 * catches `Error` or a bare `Throwable`, and an unrecognised cause is refused
 * rather than guessed at.
 */
internal fun failureOf(cause: Throwable): XlsxReadFailure? =
    when (cause) {
        // Both of these are subclasses of the more general format failure below.
        is OLE2NotOfficeXmlFileException -> XlsxReadFailure.LEGACY_XLS_FILE
        is OldFileFormatException -> XlsxReadFailure.LEGACY_XLS_FILE
        is EncryptedDocumentException -> XlsxReadFailure.ENCRYPTED
        is UnsupportedFileFormatException -> XlsxReadFailure.NOT_AN_XLSX_FILE
        is EmptyFileException -> XlsxReadFailure.NOT_AN_XLSX_FILE
        is InvalidFormatException -> XlsxReadFailure.NOT_AN_XLSX_FILE
        // Both of these are subclasses of IOException.
        is NoSuchFileException -> XlsxReadFailure.FILE_NOT_FOUND
        is AccessDeniedException -> XlsxReadFailure.NOT_READABLE
        is OpenXML4JRuntimeException -> XlsxReadFailure.DAMAGED_FILE
        is POIXMLException -> XlsxReadFailure.DAMAGED_FILE
        is RecordFormatException -> XlsxReadFailure.DAMAGED_FILE
        is IOException ->
            if (isSafetyLimit(cause)) XlsxReadFailure.REJECTED_BY_SAFETY_LIMIT else XlsxReadFailure.DAMAGED_FILE

        else -> null
    }

/**
 * The zip bomb and file count guards raise a bare [IOException], so their own
 * wording is the only thing that sets them apart from an ordinary broken
 * archive. These two stems are the ones the library actually formats its
 * messages from.
 */
private fun isSafetyLimit(cause: IOException): Boolean {
    val message = cause.message ?: return false
    return message.startsWith("Zip bomb detected!") ||
        message.startsWith("The file appears to be potentially malicious")
}
