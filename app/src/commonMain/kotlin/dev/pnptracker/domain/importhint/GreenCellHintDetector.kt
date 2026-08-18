package dev.pnptracker.domain.importhint

import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.CellSnapshot

/**
 * Reads a green game cell as "you may have finished this game".
 *
 * Three things are deliberately not evidence:
 *
 * - A cell in any column other than the game name. A green task cell says
 *   something about that task, and this detector has nothing to say about it.
 * - The font colour, and the colours inside a rich text run. The source file
 *   uses those to make colour names easier to spot by eye, so treating them as
 *   status would misread nearly every row.
 * - A cell with no fill at all. Absence is not a "no".
 *
 * The answer is always [dev.pnptracker.domain.model.HintDecision.PENDING], and
 * there is no list of games that skip the question. The file was kept by hand,
 * so some finished games were never coloured and some coloured rows mean
 * something else; only the person who kept it can say which.
 */
fun detectGameCompletionHint(
    cell: CellSnapshot,
    sourceColumnType: SourceColumnType,
): ImportHint.GameCompletion? {
    if (sourceColumnType != SourceColumnType.GAME) return null
    val fill = cell.fillColorArgb ?: return null
    val confidence = greenConfidenceOf(fill) ?: return null
    return ImportHint.GameCompletion(fillColorArgb = fill, confidence = confidence)
}
