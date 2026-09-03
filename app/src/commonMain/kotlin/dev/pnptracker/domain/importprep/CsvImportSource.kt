package dev.pnptracker.domain.importprep

import dev.pnptracker.domain.csv.CsvDelimiter
import dev.pnptracker.domain.csv.CsvParseException
import dev.pnptracker.domain.csv.CsvParseFailure
import dev.pnptracker.domain.csv.CsvReader
import dev.pnptracker.domain.csv.CsvRecord
import dev.pnptracker.domain.csv.parseCsvRecords
import dev.pnptracker.domain.importhint.ReferenceColumnLayout
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.CellSnapshot
import dev.pnptracker.domain.spreadsheet.SheetSnapshot
import dev.pnptracker.domain.spreadsheet.SheetVisibility
import dev.pnptracker.domain.spreadsheet.SpreadsheetCellKind
import dev.pnptracker.domain.spreadsheet.WorkbookSnapshot

/** The three headings PLAN 11.8 names, in the spelling the file has to use. */
object CsvImportColumns {
    const val GAME = "game"
    const val SOURCE_TYPE = "source_type"
    const val RAW_TEXT = "raw_text"

    val required = listOf(GAME, SOURCE_TYPE, RAW_TEXT)
}

/** A CSV file read into the same shape a spreadsheet is read into. */
data class CsvReading(
    val workbook: WorkbookSnapshot,
    val delimiter: CsvDelimiter,
)

/**
 * Reads a CSV file into the workbook the rest of the import already knows.
 *
 * This is the whole of what is special about CSV. Everything past this point —
 * working out raw blocks, the review screen, drafts, colours, quantities and the
 * confirmation transaction — is the code the spreadsheet import has always used,
 * because a second import architecture would be a second place for the rules to
 * drift.
 *
 * The translation is deliberately literal. Each data record becomes one row of a
 * sheet laid out like the reference file: the game name in the game column, and
 * the raw text in the column its `source_type` names. The headings of that sheet
 * are written here rather than read from the file, so the layout check that
 * follows is measuring the same thing for both formats.
 *
 * Nothing about a cell's appearance is invented on the way through. A CSV has no
 * fill colour, no rich text and no formulas, so every cell comes out plain text
 * with no fill — which is exactly why `=1+1` stays four characters and a green
 * completion hint can never appear on a CSV import.
 *
 * The whole file is read and checked before this returns, so a file that is
 * wrong on its last line writes nothing at all: there is nothing to write yet.
 *
 * @throws ImportPreparationException with a CSV failure and, where there is one,
 *   the line and column the user has to go and look at.
 */
fun readCsvWorkbook(
    fileName: String,
    text: String,
): CsvReading {
    val header = detectHeader(text)
    val records = parseRecords(text, header.delimiter)

    val dataRecords =
        records
            .drop(1)
            // A blank line is not a record about anything. Dropping it is the one
            // liberty taken with the file, and it takes nothing away.
            .filterNot { it.isEmpty }

    val cells = mutableListOf<CellSnapshot>()
    cells += headerCells()
    dataRecords.forEachIndexed { position, record ->
        cells += cellsOf(record, header, rowIndex = position + 1)
    }

    val sheet = SheetSnapshot(name = fileName, visibility = SheetVisibility.VISIBLE, cells = cells)
    return CsvReading(
        workbook = WorkbookSnapshot(fileName = fileName, sheets = listOf(sheet)),
        delimiter = header.delimiter,
    )
}

/** Where each required heading sits, once the separator is settled. */
internal data class CsvHeader(
    val delimiter: CsvDelimiter,
    val fieldCount: Int,
    val gameIndex: Int,
    val sourceTypeIndex: Int,
    val rawTextIndex: Int,
)

/** What one candidate separator made of the heading record. */
internal sealed interface HeaderCandidate {
    data class Read(
        val fields: List<String>,
    ) : HeaderCandidate {
        val headings: List<String> = fields.map(::normalizeHeader)

        fun positionsOf(column: String): List<Int> = headings.indices.filter { headings[it] == column }

        val duplicated: Boolean get() = CsvImportColumns.required.any { positionsOf(it).size > 1 }

        val missing: List<String> get() = CsvImportColumns.required.filter { positionsOf(it).isEmpty() }

        val isComplete: Boolean get() = !duplicated && missing.isEmpty()

        /**
         * Whether this separator looks like it was the one the file used at all.
         *
         * One field means the separator never appeared; that is not a file with
         * a missing column, it is the wrong separator. A single field that does
         * name one of the headings is the exception, because then the file really
         * is a CSV that is missing the other two.
         */
        val isPlausible: Boolean
            get() = fields.size > 1 || headings.any { it in CsvImportColumns.required }
    }

    data class Broken(
        val failure: CsvParseFailure,
        val lineNumber: Int,
    ) : HeaderCandidate
}

private fun detectHeader(text: String): CsvHeader =
    chooseHeader(
        CsvDelimiter.entries.associateWith { delimiter ->
            try {
                val record = CsvReader(text, delimiter).readRecord()
                HeaderCandidate.Read(record?.fields ?: emptyList())
            } catch (broken: CsvParseException) {
                HeaderCandidate.Broken(broken.failure, broken.lineNumber)
            }
        },
    )

/**
 * Which separator the heading row settles on, given what each one made of it.
 *
 * Kept apart from the reading so the decision can be examined on its own. The
 * "both work" case is the reason it is a decision at all: it cannot be reached
 * through any real file — three whole headings cannot survive being cut two
 * different ways — but a rule that quietly picks one of two readings is exactly
 * the kind of rule that goes unnoticed until it has filed a hundred rows under
 * the wrong pool, so it is written down and refuses instead.
 */
internal fun chooseHeader(candidates: Map<CsvDelimiter, HeaderCandidate>): CsvHeader {
    val complete = candidates.filterValues { it is HeaderCandidate.Read && it.isComplete }
    // Two separators that both produce the three headings would mean the file
    // says two different things, and choosing one for the user would file half
    // the rows under the wrong pool. Neither is chosen.
    if (complete.size > 1) throw ImportPreparationException(ImportFailure.CSV_AMBIGUOUS_DELIMITER)
    complete.entries.singleOrNull()?.let { (delimiter, candidate) ->
        val read = candidate as HeaderCandidate.Read
        return CsvHeader(
            delimiter = delimiter,
            fieldCount = read.fields.size,
            gameIndex = read.positionsOf(CsvImportColumns.GAME).single(),
            sourceTypeIndex = read.positionsOf(CsvImportColumns.SOURCE_TYPE).single(),
            rawTextIndex = read.positionsOf(CsvImportColumns.RAW_TEXT).single(),
        )
    }

    throw headerFailureOf(candidates)
}

/**
 * Says what is wrong with a heading row no separator could read.
 *
 * The most informative candidate answers: the one that at least looks like the
 * separator the file used. Reporting a missing column against a separator that
 * never appears in the file would send the user to fix the wrong thing.
 */
private fun headerFailureOf(candidates: Map<CsvDelimiter, HeaderCandidate>): ImportPreparationException {
    val plausible =
        candidates.values
            .filterIsInstance<HeaderCandidate.Read>()
            .filter { it.isPlausible }
            .maxByOrNull { it.fields.size }
    if (plausible != null) {
        val failure =
            if (plausible.duplicated) {
                ImportFailure.CSV_DUPLICATE_HEADER_COLUMN
            } else {
                ImportFailure.CSV_MISSING_HEADER_COLUMN
            }
        return ImportPreparationException(failure, csvLocation = CsvErrorLocation(lineNumber = 1))
    }

    // A heading row nobody could even parse is a broken file, not a wrong
    // separator, and the parser already knows which line to name.
    candidates.values.filterIsInstance<HeaderCandidate.Broken>().minByOrNull { it.lineNumber }?.let { broken ->
        return csvParseException(broken.failure, broken.lineNumber)
    }
    return ImportPreparationException(ImportFailure.CSV_UNDETECTABLE_DELIMITER)
}

private fun parseRecords(
    text: String,
    delimiter: CsvDelimiter,
): List<CsvRecord> =
    try {
        parseCsvRecords(text, delimiter)
    } catch (broken: CsvParseException) {
        throw csvParseException(broken.failure, broken.lineNumber)
    }

private fun csvParseException(
    failure: CsvParseFailure,
    lineNumber: Int,
): ImportPreparationException =
    ImportPreparationException(
        when (failure) {
            CsvParseFailure.UNCLOSED_QUOTED_FIELD -> ImportFailure.CSV_UNCLOSED_QUOTE
            CsvParseFailure.TEXT_AFTER_QUOTED_FIELD -> ImportFailure.CSV_TEXT_AFTER_QUOTE
            CsvParseFailure.QUOTE_IN_PLAIN_FIELD -> ImportFailure.CSV_QUOTE_IN_PLAIN_FIELD
        },
        csvLocation = CsvErrorLocation(lineNumber = lineNumber),
    )

/**
 * The reference layout's own headings, written out.
 *
 * A CSV names its columns by `source_type` on every row, so the sheet it becomes
 * needs a heading row of its own for the layout check to recognise. These are the
 * canonical spellings of the seven reference columns; nothing from the CSV file
 * reaches this row.
 */
private fun headerCells(): List<CellSnapshot> =
    listOf("Oyun", "3D Print", "Laminasyon", "Mukavva", "Özel", "Eksik", "Ödünç Parçalar")
        .mapIndexed { columnIndex, heading -> textCell(rowIndex = 0, columnIndex = columnIndex, text = heading) }

private fun cellsOf(
    record: CsvRecord,
    header: CsvHeader,
    rowIndex: Int,
): List<CellSnapshot> {
    // A row with more or fewer fields than the heading is not read by position:
    // shifting the values along would put one game's work under another's name.
    if (record.fields.size != header.fieldCount) {
        throw ImportPreparationException(
            ImportFailure.CSV_RAGGED_ROW,
            csvLocation = CsvErrorLocation(lineNumber = record.lineNumber),
        )
    }

    val game = requiredValue(record, header.gameIndex, CsvImportColumns.GAME)
    val sourceTypeText = requiredValue(record, header.sourceTypeIndex, CsvImportColumns.SOURCE_TYPE)
    val rawText = requiredValue(record, header.rawTextIndex, CsvImportColumns.RAW_TEXT)

    val sourceColumnType =
        sourceColumnTypeOf(sourceTypeText)
            ?: throw ImportPreparationException(
                // Never guessed at and never sent to a default pool: a wrong pool
                // is worse than a file the user has to correct (PLAN 10).
                ImportFailure.CSV_UNKNOWN_SOURCE_TYPE,
                csvLocation = CsvErrorLocation(record.lineNumber, CsvImportColumns.SOURCE_TYPE),
            )

    // A row that is about the game name itself has only the game column to fill,
    // and `raw_text` is what the file wrote there — `**` markers and all.
    if (sourceColumnType == SourceColumnType.GAME) {
        return listOf(textCell(rowIndex, ReferenceColumnLayout.GAME_COLUMN_INDEX, rawText))
    }
    return listOf(
        textCell(rowIndex, ReferenceColumnLayout.GAME_COLUMN_INDEX, game),
        textCell(rowIndex, ReferenceColumnLayout.columnIndexOf(sourceColumnType), rawText),
    )
}

/**
 * One required value, exactly as the file wrote it.
 *
 * Only emptiness is judged. The value itself is never trimmed: a leading space
 * in a raw cell is part of what somebody typed, and PLAN 11.3 keeps the original
 * cell text.
 */
private fun requiredValue(
    record: CsvRecord,
    fieldIndex: Int,
    columnName: String,
): String {
    val value = record.fields[fieldIndex]
    if (value.isBlank()) {
        throw ImportPreparationException(
            ImportFailure.CSV_BLANK_REQUIRED_VALUE,
            csvLocation = CsvErrorLocation(record.lineNumber, columnName),
        )
    }
    return value
}

private fun textCell(
    rowIndex: Int,
    columnIndex: Int,
    text: String,
): CellSnapshot =
    CellSnapshot(
        rowIndex = rowIndex,
        columnIndex = columnIndex,
        rawText = text,
        // Plain text, always. A CSV cell that begins with `=` is four characters
        // somebody typed, not a formula, and calling it one here is what would
        // later let something evaluate it.
        kind = SpreadsheetCellKind.TEXT,
    )

/**
 * Which source column a `source_type` value names, or null when nothing does.
 *
 * Two vocabularies are accepted and no third one is invented: the names of the
 * source columns themselves, so a file written against this application reads
 * plainly, and the Turkish headings the spreadsheet import already recognises,
 * so a file exported from the reference workbook needs no translation. Both are
 * compared through the same heading normalisation, which is what makes `3D` and
 * ` three_d ` and `Laminasyon (Kart vb.)` all answer.
 */
fun sourceColumnTypeOf(value: String): SourceColumnType? {
    val normalized = normalizeHeader(value)
    if (normalized.isEmpty()) return null

    SourceColumnType.entries.firstOrNull { normalizeHeader(it.name) == normalized }?.let { return it }
    val columnIndex = ReferenceSheetLayout.columnIndexOfHeader(value) ?: return null
    return ReferenceColumnLayout.suggestionFor(columnIndex)?.sourceColumnType
}
