package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.CellSegmentDao
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.diagnostics.storageWriteFailed
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlin.time.Clock

/** Writing what a cell's document says. */
interface CellTextEditing {
    /**
     * Makes one cell of one game read as exactly [newDocumentText].
     *
     * The document is the cell's pieces laid end to end, tasks included as their
     * names. Only the plain text in it is the caller's to change: a change that
     * reaches into a task is refused, because PLAN 5.5 makes a task piece atomic
     * and it keeps its colours, pipeline and history by keeping its identity.
     *
     * The text is stored character for character. A finished game is edited like
     * any other — PLAN 5.3 keeps a finished game editable and PLAN 12.4 lets the
     * user work in whichever view they are in — and a deleted one is not edited
     * at all.
     *
     * @param expectedDocumentText what the cell said when the editor opened, so
     *   a change someone else made in between is caught rather than overwritten.
     * @return true when this call changed something.
     * @throws CellTextException with the case that stopped it.
     */
    suspend fun saveDocumentText(
        gameId: EntityId,
        columnType: CellColumnType,
        expectedDocumentText: String,
        newDocumentText: String,
    ): Boolean
}

/**
 * The user writing in a cell.
 *
 * Thin on purpose: the rules about what a cell may look like afterwards belong
 * to the one transaction that enforces them, so all this adds is the clock, the
 * source of identities, and turning a storage refusal into something a screen
 * can say.
 */
class CellTextStore(
    private val cellSegmentDao: CellSegmentDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
    private val diagnostics: Diagnostics = Diagnostics.None,
) : CellTextEditing {
    override suspend fun saveDocumentText(
        gameId: EntityId,
        columnType: CellColumnType,
        expectedDocumentText: String,
        newDocumentText: String,
    ): Boolean =
        try {
            cellSegmentDao.saveDocumentText(
                gameId = gameId,
                columnType = columnType,
                expectedDocumentText = expectedDocumentText,
                newDocumentText = newDocumentText,
                clock = clock,
                idGenerator = idGenerator,
            )
        } catch (cause: SQLiteException) {
            // Only a recognised storage refusal becomes something the user is
            // told about; a broken invariant travels out untouched.
            diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.CELL_TEXT, CellTextFailure.COULD_NOT_SAVE, cause) }
            throw CellTextException(CellTextFailure.COULD_NOT_SAVE, cause)
        }
}
