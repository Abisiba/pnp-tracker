package dev.pnptracker.domain.importprep

import dev.pnptracker.domain.importhint.ReferenceColumnLayout
import dev.pnptracker.domain.importhint.detectGameCompletionHint
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.CellSnapshot
import dev.pnptracker.domain.spreadsheet.SheetSnapshot
import dev.pnptracker.domain.spreadsheet.SheetVisibility

/**
 * Turns one worksheet into the import that would be written for it.
 *
 * Pure and total in both directions: the same sheet always gives an equal draft,
 * the sheet is never modified, and every cell the reader recognised as content
 * becomes exactly one raw block. Failing to find a meaning for a cell is not an
 * error — a cell nobody can classify still has its text kept, because the text is
 * the record and the meaning is the user's to give later.
 *
 * A layout this version does not read is a different matter. That stops the whole
 * import before anything is written, rather than quietly filing cells under the
 * wrong column.
 *
 * @throws ImportPreparationException if the sheet is empty, has no heading row
 *   this version recognises, or holds content outside the seven columns.
 */
fun prepareImportDraft(
    fileName: String,
    sha256: String,
    sheet: SheetSnapshot,
    sourceFormat: ImportSourceFormat = ImportSourceFormat.XLSX,
): PreparedImportDraft {
    if (sheet.isEmpty) throw ImportPreparationException(ImportFailure.EMPTY_SHEET)

    ReferenceSheetLayout.unsupportedColumnOf(sheet)?.let { column ->
        throw ImportPreparationException(ImportFailure.UNSUPPORTED_COLUMN, columnIndex = column)
    }
    val headerRowIndex =
        ReferenceSheetLayout.headerRowIndexOf(sheet)
            ?: throw ImportPreparationException(ImportFailure.UNSUPPORTED_SHEET_LAYOUT)

    // The heading row describes the sheet; it is not something the user wrote
    // down about a game, so it never becomes a raw block.
    val dataCells = sheet.cells.filter { it.rowIndex > headerRowIndex }
    if (dataCells.isEmpty()) throw ImportPreparationException(ImportFailure.EMPTY_SHEET)

    val blocks = dataCells.map(::preparedBlockOf)

    return PreparedImportDraft(
        fileName = fileName,
        sha256 = sha256,
        sourceFormat = sourceFormat,
        sheetName = sheet.name,
        sheetVisibility = sheet.visibility,
        startRowIndex = blocks.minOf { it.rowIndex },
        endRowIndex = blocks.maxOf { it.rowIndex },
        startColumnIndex = blocks.minOf { it.columnIndex },
        endColumnIndex = blocks.maxOf { it.columnIndex },
        blocks = blocks,
        warnings = warningsFor(sheet, blocks),
    )
}

private fun preparedBlockOf(cell: CellSnapshot): PreparedRawBlock {
    // The column says where the cell came from. Within the seven reference
    // columns this always answers, which is why the sheet was checked first.
    val sourceColumnType =
        checkNotNull(ReferenceColumnLayout.suggestionFor(cell.columnIndex)) {
            "column ${cell.columnIndex} passed validation but has no source type"
        }.sourceColumnType

    return PreparedRawBlock(
        rowIndex = cell.rowIndex,
        columnIndex = cell.columnIndex,
        sourceColumnType = sourceColumnType,
        // Taken exactly as it was read: no trimming, no collapsing of line
        // breaks, no stripping of `**`.
        rawText = cell.rawText,
        fillColorArgb = packArgb(cell.fillColorArgb),
        gameCompletionHint =
            if (detectGameCompletionHint(cell, sourceColumnType) != null) {
                HintDecision.PENDING
            } else {
                HintDecision.NONE
            },
    )
}

/**
 * Things worth telling the user before they save.
 *
 * A row with work but no game name is kept as it is: the name is not carried
 * down from the row above and rows are not merged by name, because both would be
 * a guess written into the record. The user is told instead.
 */
private fun warningsFor(
    sheet: SheetSnapshot,
    blocks: List<PreparedRawBlock>,
): List<ImportWarning> {
    val warnings = mutableListOf<ImportWarning>()

    val rowsWithWork =
        blocks.filter { it.sourceColumnType != SourceColumnType.GAME }.map { it.rowIndex }.toSortedSet()
    val rowsWithAGameName =
        blocks.filter { it.sourceColumnType == SourceColumnType.GAME }.map { it.rowIndex }.toSet()
    val orphanRows = rowsWithWork.filterNot { it in rowsWithAGameName }
    if (orphanRows.isNotEmpty()) {
        warnings += ImportWarning(ImportWarningKind.ROW_WITHOUT_GAME_NAME, orphanRows)
    }

    if (sheet.visibility != SheetVisibility.VISIBLE) {
        warnings += ImportWarning(ImportWarningKind.HIDDEN_SHEET)
    }
    return warnings
}
