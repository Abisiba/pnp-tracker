package dev.pnptracker.ui.feature.importreview

import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.SheetVisibility
import dev.pnptracker.ui.Strings
import org.jetbrains.compose.resources.StringResource

/**
 * The wording for the things the import screen has to name.
 *
 * Every one of these is a lookup into the shared catalogue, so a Turkish word
 * never appears inside a layout and a second language means a second values
 * folder and nothing else.
 */
internal fun messageFor(failure: ImportFailure): StringResource =
    when (failure) {
        ImportFailure.FILE_NOT_FOUND -> Strings.ImportErrors.fileNotFound
        ImportFailure.NOT_READABLE -> Strings.ImportErrors.notReadable
        ImportFailure.NOT_AN_XLSX_FILE -> Strings.ImportErrors.notAnXlsxFile
        ImportFailure.LEGACY_XLS_FILE -> Strings.ImportErrors.legacyXls
        ImportFailure.DAMAGED_FILE -> Strings.ImportErrors.damaged
        ImportFailure.ENCRYPTED -> Strings.ImportErrors.encrypted
        ImportFailure.REJECTED_BY_SAFETY_LIMIT -> Strings.ImportErrors.safetyLimit
        ImportFailure.FILE_CHANGED_WHILE_READING -> Strings.ImportErrors.fileChanged
        ImportFailure.UNSUPPORTED_SHEET_LAYOUT -> Strings.ImportErrors.unsupportedLayout
        ImportFailure.UNSUPPORTED_COLUMN -> Strings.ImportErrors.unsupportedColumn
        ImportFailure.EMPTY_SHEET -> Strings.ImportErrors.emptySheet
    }

internal fun nameOf(columnType: SourceColumnType): StringResource =
    when (columnType) {
        SourceColumnType.GAME -> Strings.SourceColumnNames.game
        SourceColumnType.THREE_D -> Strings.SourceColumnNames.threeD
        SourceColumnType.CARD -> Strings.SourceColumnNames.card
        SourceColumnType.BOARD -> Strings.SourceColumnNames.board
        SourceColumnType.SPECIAL -> Strings.SourceColumnNames.special
        SourceColumnType.MISSING -> Strings.SourceColumnNames.missing
        SourceColumnType.BORROWED -> Strings.SourceColumnNames.borrowed
    }

internal fun nameOf(visibility: SheetVisibility): StringResource =
    when (visibility) {
        SheetVisibility.VISIBLE -> Strings.SheetVisibilityNames.visible
        SheetVisibility.HIDDEN -> Strings.SheetVisibilityNames.hidden
        SheetVisibility.VERY_HIDDEN -> Strings.SheetVisibilityNames.veryHidden
    }
