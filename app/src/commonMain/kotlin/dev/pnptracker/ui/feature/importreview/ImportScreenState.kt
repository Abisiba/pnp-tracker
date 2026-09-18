package dev.pnptracker.ui.feature.importreview

import dev.pnptracker.data.repository.EarlierImport
import dev.pnptracker.data.repository.SavedImportSummary
import dev.pnptracker.domain.csv.CsvDelimiter
import dev.pnptracker.domain.importprep.CsvErrorLocation
import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.importprep.PreparedImportDraft
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.spreadsheet.SheetVisibility
import dev.pnptracker.domain.spreadsheet.WorkbookSnapshot

/** One sheet the user can choose, described well enough to choose between them. */
data class SheetChoice(
    val name: String,
    val visibility: SheetVisibility,
    val contentCellCount: Int,
) {
    val isEmpty: Boolean get() = contentCellCount == 0
}

/** What came of trying to make an import out of the chosen sheet. */
sealed interface SheetPreparation {
    data class Ready(
        val draft: PreparedImportDraft,
    ) : SheetPreparation

    data class Rejected(
        val failure: ImportFailure,
        val columnIndex: Int? = null,
        val csvLocation: CsvErrorLocation? = null,
    ) : SheetPreparation
}

/**
 * A file the user is part way through importing.
 *
 * There is no path here and there cannot be one: the screen is given a file name
 * and nothing else, so no absolute path can reach the interface, a log or the
 * database through this route.
 */
data class ImportSession(
    val fileName: String,
    val sha256: String,
    val workbook: WorkbookSnapshot,
    val sourceFormat: ImportSourceFormat,
    /**
     * The separator a CSV turned out to use, for the preview to say out loud.
     *
     * Kept in the session and nowhere else. It describes one reading of one
     * file, so it belongs to the screen for as long as that file is on it and
     * has no business in the database.
     */
    val csvDelimiter: CsvDelimiter? = null,
    val sheets: List<SheetChoice>,
    val selectedSheetName: String?,
    val preparation: SheetPreparation?,
    val earlierImports: List<EarlierImport>,
    val duplicateAcknowledged: Boolean = false,
) {
    val hasEarlierImports: Boolean get() = earlierImports.isNotEmpty()

    val draft: PreparedImportDraft?
        get() = (preparation as? SheetPreparation.Ready)?.draft

    val canSave: Boolean get() = draft != null
}

/**
 * Where the import screen is.
 *
 * Each step the user can be waiting on is its own case, so the screen never has
 * to work out from a handful of booleans whether a save is in flight or whether
 * a duplicate has been acknowledged.
 */
sealed interface ImportScreenState {
    /** Nothing chosen yet. */
    data object Idle : ImportScreenState

    /** The file dialog is open. */
    data object ChoosingFile : ImportScreenState

    /** The file is being fingerprinted and read. */
    data object ReadingFile : ImportScreenState

    /** More than one sheet, so the user has to say which. */
    data class SheetSelection(
        val session: ImportSession,
    ) : ImportScreenState

    /** A sheet is chosen and its summary is on screen. */
    data class PreviewReady(
        val session: ImportSession,
    ) : ImportScreenState

    /** This file has been imported before and the user has to say whether to go on. */
    data class DuplicateWarning(
        val session: ImportSession,
    ) : ImportScreenState

    /** The transaction is running. */
    data class Saving(
        val session: ImportSession,
    ) : ImportScreenState

    /** The draft is in the database. */
    data class Saved(
        val summary: SavedImportSummary,
    ) : ImportScreenState

    /**
     * Storage would not take the draft, and everything read is still in hand.
     *
     * Its own case rather than [Failed], because the two ask different things of
     * the user. A file that could not be read is over: there is nothing to go
     * back to and the only way on is another file. A write that did not land
     * leaves the file, the sheet and the whole preview exactly as they were, so
     * the screen keeps them and the same button tries again (PLAN 14.7.6).
     */
    data class NotSaved(
        val session: ImportSession,
    ) : ImportScreenState

    data class Failed(
        val failure: ImportFailure,
        val columnIndex: Int? = null,
        val csvLocation: CsvErrorLocation? = null,
    ) : ImportScreenState
}
