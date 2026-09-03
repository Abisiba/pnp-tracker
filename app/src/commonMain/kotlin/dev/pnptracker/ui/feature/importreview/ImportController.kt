package dev.pnptracker.ui.feature.importreview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.ImportDrafts
import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.importprep.ImportFileGateway
import dev.pnptracker.domain.importprep.ImportFileHandle
import dev.pnptracker.domain.importprep.ImportFileReader
import dev.pnptracker.domain.importprep.ImportPreparationException
import dev.pnptracker.domain.importprep.ReadImportFile
import dev.pnptracker.domain.importprep.prepareImportDraft
import dev.pnptracker.domain.spreadsheet.SheetSnapshot
import dev.pnptracker.domain.spreadsheet.SheetVisibility

/**
 * Drives the import screen: choose a file, look at what is in it, save it as a
 * draft.
 *
 * Everything it does is one of the states in [ImportScreenState], so a test can
 * step through the whole flow without a composition, a window or a real file.
 * The chosen file is held here rather than in the state, so the state stays a
 * plain value the screen and the tests can compare.
 *
 * Two things it deliberately refuses. It will not save while a save is already
 * running, so a double click cannot write two imports. And it will not save a
 * file that has been imported before until the user has said so, which is a
 * decision only they can make.
 */
class ImportController(
    private val gateway: ImportFileGateway,
    private val store: ImportDrafts,
    private val reader: ImportFileReader = ImportFileReader(),
) {
    var state: ImportScreenState by mutableStateOf(ImportScreenState.Idle)
        private set

    private var handle: ImportFileHandle? = null

    /** True while an action is in flight, so the screen can disable its buttons. */
    val isBusy: Boolean
        get() =
            state is ImportScreenState.ChoosingFile ||
                state is ImportScreenState.ReadingFile ||
                state is ImportScreenState.Saving

    suspend fun chooseFile() {
        if (isBusy) return
        state = ImportScreenState.ChoosingFile

        val chosen =
            try {
                gateway.chooseFile()
            } catch (failure: ImportPreparationException) {
                state = failedFrom(failure)
                return
            }
        // Changing one's mind is not a failure; the screen goes back to where it was.
        if (chosen == null) {
            handle = null
            state = ImportScreenState.Idle
            return
        }

        handle = chosen
        state = ImportScreenState.ReadingFile
        val read =
            try {
                reader.read(chosen)
            } catch (failure: ImportPreparationException) {
                handle = null
                state = failedFrom(failure)
                return
            }
        state = sessionStateFor(read)
    }

    suspend fun selectSheet(sheetName: String) {
        val session = currentSession() ?: return
        if (isBusy) return
        if (session.sheets.none { it.name == sheetName }) return

        val prepared = session.copy(selectedSheetName = sheetName, preparation = prepare(session, sheetName))
        state = ImportScreenState.PreviewReady(prepared)
    }

    /**
     * Writes the draft, unless something first has to be settled: a repeat import
     * the user has not acknowledged, or a file that changed since the preview.
     */
    suspend fun saveDraft() {
        val session = currentSession() ?: return
        // A save already under way owns the flow; a second click does nothing.
        if (state is ImportScreenState.Saving) return
        val draft = session.draft ?: return

        if (session.hasEarlierImports && !session.duplicateAcknowledged) {
            state = ImportScreenState.DuplicateWarning(session)
            return
        }

        state = ImportScreenState.Saving(session)
        val chosen = handle
        try {
            // The file is checked one last time, so a preview of an older version
            // can never be written under the fingerprint of a newer one.
            if (chosen != null) reader.requireUnchanged(chosen, session.sha256)
            val summary = store.save(draft)
            handle = null
            state = ImportScreenState.Saved(summary)
        } catch (failure: ImportPreparationException) {
            handle = null
            state = failedFrom(failure)
        }
    }

    /** The user chose to import a file that has been imported before. */
    suspend fun confirmDuplicateImport() {
        val session = (state as? ImportScreenState.DuplicateWarning)?.session ?: return
        state = ImportScreenState.PreviewReady(session.copy(duplicateAcknowledged = true))
        saveDraft()
    }

    /** The user backed out of importing a repeat; nothing has been written. */
    fun cancelDuplicateImport() {
        val session = (state as? ImportScreenState.DuplicateWarning)?.session ?: return
        state = ImportScreenState.PreviewReady(session)
    }

    /** Puts the screen back to its starting point, keeping whatever was saved. */
    fun startOver() {
        if (state is ImportScreenState.Saving) return
        handle = null
        state = ImportScreenState.Idle
    }

    private fun currentSession(): ImportSession? =
        when (val current = state) {
            is ImportScreenState.SheetSelection -> current.session
            is ImportScreenState.PreviewReady -> current.session
            is ImportScreenState.DuplicateWarning -> current.session
            is ImportScreenState.Saving -> current.session
            else -> null
        }

    private suspend fun sessionStateFor(read: ReadImportFile): ImportScreenState {
        val sheets =
            read.workbook.sheets.map { sheet ->
                SheetChoice(name = sheet.name, visibility = sheet.visibility, contentCellCount = sheet.cells.size)
            }
        val session =
            ImportSession(
                fileName = read.fileName,
                sha256 = read.sha256,
                workbook = read.workbook,
                sourceFormat = read.sourceFormat,
                csvDelimiter = read.csvDelimiter,
                sheets = sheets,
                selectedSheetName = null,
                preparation = null,
                earlierImports = store.earlierImportsOf(read.sha256),
            )

        // One sheet needs no question. With more than one, the first visible sheet
        // that holds something is only a starting point the user can change; a
        // hidden sheet is never chosen for them.
        val automatic = sheets.singleOrNull()
        if (automatic != null) {
            val prepared = session.copy(selectedSheetName = automatic.name, preparation = prepare(session, automatic.name))
            return ImportScreenState.PreviewReady(prepared)
        }

        val suggested = sheets.firstOrNull { it.visibility == SheetVisibility.VISIBLE && !it.isEmpty }
        val withSuggestion =
            if (suggested == null) {
                session
            } else {
                session.copy(selectedSheetName = suggested.name, preparation = prepare(session, suggested.name))
            }
        return ImportScreenState.SheetSelection(withSuggestion)
    }

    private fun prepare(
        session: ImportSession,
        sheetName: String,
    ): SheetPreparation {
        val sheet: SheetSnapshot =
            session.workbook.sheetNamed(sheetName)
                ?: return SheetPreparation.Rejected(ImportFailure.EMPTY_SHEET)
        return try {
            SheetPreparation.Ready(
                prepareImportDraft(
                    fileName = session.fileName,
                    sha256 = session.sha256,
                    sheet = sheet,
                    sourceFormat = session.sourceFormat,
                ),
            )
        } catch (rejected: ImportPreparationException) {
            SheetPreparation.Rejected(rejected.failure, rejected.columnIndex, rejected.csvLocation)
        }
    }

    private fun failedFrom(failure: ImportPreparationException) =
        ImportScreenState.Failed(failure.failure, failure.columnIndex, failure.csvLocation)
}
