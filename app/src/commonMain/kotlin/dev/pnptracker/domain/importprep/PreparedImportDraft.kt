package dev.pnptracker.domain.importprep

import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.SheetVisibility

/** Something about the sheet the user should see before saving. */
enum class ImportWarningKind {
    /** A row has work in it but no game name of its own. */
    ROW_WITHOUT_GAME_NAME,

    /** The chosen sheet was hidden in the source file. */
    HIDDEN_SHEET,
}

data class ImportWarning(
    val kind: ImportWarningKind,
    /** The rows the warning is about, in the sheet's own numbering. */
    val rowIndexes: List<Int> = emptyList(),
)

/**
 * One raw cell, worked out but not yet given an identity.
 *
 * There is deliberately no id and no timestamp here: those are made when the
 * user actually says to save, so that looking at a preview twice cannot leave
 * two different sets of identifiers behind.
 */
data class PreparedRawBlock(
    val rowIndex: Int,
    val columnIndex: Int,
    val sourceColumnType: SourceColumnType,
    val rawText: String,
    val fillColorArgb: Int?,
    val gameCompletionHint: HintDecision,
) {
    init {
        require(rowIndex >= 0 && columnIndex >= 0) {
            "Cell coordinates are zero based: row $rowIndex, column $columnIndex"
        }
        require(gameCompletionHint != HintDecision.ACCEPTED && gameCompletionHint != HintDecision.REJECTED) {
            "An import can only find a hint, never answer it, but got $gameCompletionHint"
        }
        require(sourceColumnType == SourceColumnType.GAME || gameCompletionHint == HintDecision.NONE) {
            "Only a game cell carries a game completion hint, but a $sourceColumnType cell did"
        }
    }
}

/**
 * Everything needed to write one import, worked out from a snapshot and nothing
 * else.
 *
 * The same snapshot and sheet always give an equal draft. It carries no
 * identifier, no clock reading, no database type and nothing from the file
 * system, so it can be shown, compared and thrown away freely; only saving turns
 * it into rows.
 */
data class PreparedImportDraft(
    val fileName: String,
    val sha256: String,
    /** Which kind of file this was read from; the batch records it (PLAN 11.2). */
    val sourceFormat: ImportSourceFormat,
    val sheetName: String,
    val sheetVisibility: SheetVisibility,
    val startRowIndex: Int?,
    val endRowIndex: Int?,
    val startColumnIndex: Int?,
    val endColumnIndex: Int?,
    val blocks: List<PreparedRawBlock>,
    val warnings: List<ImportWarning> = emptyList(),
) {
    init {
        require(fileName.isNotBlank()) { "A draft records the name of the file it came from" }
        require(!fileName.contains('/') && !fileName.contains('\\')) {
            "Only the file name is kept, never a path, but got: $fileName"
        }
        require(blocks.isNotEmpty()) { "There is nothing to import from an empty sheet" }
    }

    val rawBlockCount: Int get() = blocks.size

    /**
     * How many source rows this import covers.
     *
     * For a CSV that is the number of data records the file held, because one
     * record becomes one row. Counted from the blocks rather than kept alongside
     * them, so the two can never disagree.
     */
    val sourceRowCount: Int get() = blocks.map { it.rowIndex }.distinct().size

    /** How many game names the sheet holds; not the number of games this import creates, which is none. */
    val detectedGameCellCount: Int
        get() = blocks.count { it.sourceColumnType == SourceColumnType.GAME }

    val pendingGameCompletionHintCount: Int
        get() = blocks.count { it.gameCompletionHint == HintDecision.PENDING }

    val multiLineCellCount: Int get() = blocks.count { it.rawText.contains('\n') }

    val blockCountsByColumnType: Map<SourceColumnType, Int>
        get() = blocks.groupingBy { it.sourceColumnType }.eachCount()
}
