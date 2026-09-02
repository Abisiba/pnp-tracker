package dev.pnptracker.domain.importhint

import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.CellSnapshot

/**
 * Everything the detectors noticed about one cell.
 *
 * [rawText] is the cell exactly as the file had it and [displayText] is the same
 * text with the detected `**` removed, so a screen can show one while the record
 * keeps the other. When nothing was found, [hints] is empty — there is no
 * stand-in "no hint" entry, because absence is recorded on the stored row as
 * [dev.pnptracker.domain.model.HintDecision.NONE] and does not need a second
 * spelling here.
 */
data class CellHintAnalysis(
    val rawText: String,
    val displayText: String,
    val columnSuggestion: ReferenceColumnSuggestion?,
    val hints: List<ImportHint>,
) {
    val completionMarkers: List<ImportHint.CompletionMarker>
        get() = hints.filterIsInstance<ImportHint.CompletionMarker>()

    val alternativeColors: List<ImportHint.AlternativeColors>
        get() = hints.filterIsInstance<ImportHint.AlternativeColors>()

    val gameCompletion: ImportHint.GameCompletion?
        get() = hints.filterIsInstance<ImportHint.GameCompletion>().firstOrNull()

    /** The count the cell opens with, if it opens with one. */
    val quantity: ImportHint.Quantity?
        get() = hints.filterIsInstance<ImportHint.Quantity>().firstOrNull()
}

/**
 * Runs every detector over a cell and collects what they found.
 *
 * The same cell always gives an equal result: nothing here reads a clock, makes
 * an identifier, touches a file or keeps state between calls. Hints come back in
 * a fixed order — the column suggestion, then the green game cell, then the
 * leading count, then the `**` markers left to right, then the colour choices
 * left to right — so two runs can be compared directly.
 *
 * Nothing is applied. No task, colour relation, game or draft is created, and no
 * hint arrives already agreed to.
 */
class ImportHintAnalyzer(
    private val vocabulary: ColorVocabulary,
) {
    fun analyze(
        cell: CellSnapshot,
        sourceColumnType: SourceColumnType,
    ): CellHintAnalysis {
        val markerScan = detectCompletionMarkers(cell.rawText)
        val columnSuggestion = ReferenceColumnLayout.suggestionFor(cell.columnIndex)

        val hints =
            buildList {
                columnSuggestion?.let { add(ImportHint.ColumnSuggestion(it)) }
                detectGameCompletionHint(cell, sourceColumnType)?.let { add(it) }
                detectLeadingQuantity(cell.rawText)?.let { add(it) }
                addAll(markerScan.markers)
                addAll(detectAlternativeColorExpressions(cell.rawText, vocabulary))
            }

        return CellHintAnalysis(
            rawText = cell.rawText,
            displayText = markerScan.displayText,
            columnSuggestion = columnSuggestion,
            hints = hints,
        )
    }
}
