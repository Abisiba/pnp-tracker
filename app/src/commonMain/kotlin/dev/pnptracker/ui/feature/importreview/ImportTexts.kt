package dev.pnptracker.ui.feature.importreview

import dev.pnptracker.domain.csv.CsvDelimiter
import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.model.ImportSourceFormat
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
        ImportFailure.UNSUPPORTED_FILE_TYPE -> Strings.ImportErrors.unsupportedFileType
        ImportFailure.NOT_UTF8 -> Strings.ImportErrors.notUtf8
        ImportFailure.CSV_UNCLOSED_QUOTE -> Strings.ImportErrors.csvUnclosedQuote
        ImportFailure.CSV_TEXT_AFTER_QUOTE -> Strings.ImportErrors.csvTextAfterQuote
        ImportFailure.CSV_QUOTE_IN_PLAIN_FIELD -> Strings.ImportErrors.csvQuoteInPlainField
        ImportFailure.CSV_AMBIGUOUS_DELIMITER -> Strings.ImportErrors.csvAmbiguousDelimiter
        ImportFailure.CSV_UNDETECTABLE_DELIMITER -> Strings.ImportErrors.csvUndetectableDelimiter
        ImportFailure.CSV_MISSING_HEADER_COLUMN -> Strings.ImportErrors.csvMissingHeaderColumn
        ImportFailure.CSV_DUPLICATE_HEADER_COLUMN -> Strings.ImportErrors.csvDuplicateHeaderColumn
        ImportFailure.CSV_RAGGED_ROW -> Strings.ImportErrors.csvRaggedRow
        ImportFailure.CSV_BLANK_REQUIRED_VALUE -> Strings.ImportErrors.csvBlankRequiredValue
        ImportFailure.CSV_UNKNOWN_SOURCE_TYPE -> Strings.ImportErrors.csvUnknownSourceType
        ImportFailure.COULD_NOT_SAVE -> Strings.ImportErrors.couldNotSave
    }

internal fun nameOf(sourceFormat: ImportSourceFormat): StringResource =
    when (sourceFormat) {
        ImportSourceFormat.XLSX -> Strings.Import.sourceFormatXlsx
        ImportSourceFormat.CSV -> Strings.Import.sourceFormatCsv
    }

/**
 * The separator, in words.
 *
 * Written out rather than shown as the character itself, because a lone `,` or
 * `;` on a line is easy to miss and impossible to read aloud.
 */
internal fun nameOf(delimiter: CsvDelimiter): StringResource =
    when (delimiter) {
        CsvDelimiter.COMMA -> Strings.Import.csvDelimiterComma
        CsvDelimiter.SEMICOLON -> Strings.Import.csvDelimiterSemicolon
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
