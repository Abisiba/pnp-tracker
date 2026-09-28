package dev.pnptracker.data.repository

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.projection.CellRunRow
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.diagnostics.storageWriteFailed
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.NewTaskText
import dev.pnptracker.domain.tasks.TaskCreationFromNewText
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import kotlin.time.Clock

/**
 * The cell's new text and the task made from its new name, in one transaction.
 *
 * Nothing here decides what a cell may look like: the text is saved by the same
 * transaction the cell editor's own save uses, and the task is cut out of it by
 * the same one a selection uses. This only runs the two inside one write, so a
 * refused task leaves the text unsaved as well — the words stay in the editor,
 * where the user left them — and finds where the name landed in between.
 */
class TaskFromNewTextStore(
    private val database: AppDatabase,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
    private val diagnostics: Diagnostics = Diagnostics.None,
) : TaskCreationFromNewText {
    override suspend fun createTasksInNewText(
        text: NewTaskText,
        drafts: List<TaskDraft>,
    ): List<EntityId> =
        try {
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    val cells = database.cellSegmentDao()
                    cells.saveDocumentText(text.gameId, text.columnType, text.expectedDocument, text.newDocument, clock, idGenerator)
                    val cell =
                        cells.cellOfGame(text.gameId, text.columnType)
                            ?: throw TaskFromTextException(TaskFromTextFailure.CELL_NOT_AVAILABLE)
                    database.taskFromTextDao().createTasksFromSelection(
                        selection = selectionOf(text, cell.id, cells.runsOfCell(cell.id)),
                        drafts = drafts,
                        clock = clock,
                        idGenerator = idGenerator,
                    )
                }
            }
        } catch (refused: CellTextException) {
            throw TaskFromTextException(taskFailureOf(refused.failure), cause = refused)
        } catch (cause: SQLiteException) {
            diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.TASK_FROM_TEXT, TaskFromTextFailure.COULD_NOT_SAVE, cause) }
            throw TaskFromTextException(TaskFromTextFailure.COULD_NOT_SAVE, cause = cause)
        }

    /** The piece of plain text the new name was saved into, and where in it. */
    private fun selectionOf(
        text: NewTaskText,
        cellId: EntityId,
        runs: List<CellRunRow>,
    ): CellTextSelection {
        var pieceStart = 0
        runs.forEach { run ->
            val piece = if (run.kind == SegmentKind.TASK) run.taskName.orEmpty() else run.text.orEmpty()
            val pieceEnd = pieceStart + piece.length
            if (text.startOffset >= pieceStart && text.endOffset <= pieceEnd) {
                if (run.kind != SegmentKind.PLAIN_TEXT) throw TaskFromTextException(TaskFromTextFailure.SEGMENT_IS_NOT_PLAIN_TEXT)
                return CellTextSelection(
                    gameId = text.gameId,
                    cellId = cellId,
                    segmentId = run.segmentId,
                    expectedText = piece,
                    startOffset = text.startOffset - pieceStart,
                    endOffset = text.endOffset - pieceStart,
                )
            }
            pieceStart = pieceEnd
        }
        throw TaskFromTextException(TaskFromTextFailure.INVALID_SELECTION)
    }

    private fun taskFailureOf(failure: CellTextFailure): TaskFromTextFailure =
        when (failure) {
            CellTextFailure.GAME_NOT_AVAILABLE -> TaskFromTextFailure.GAME_NOT_AVAILABLE
            CellTextFailure.CELL_NOT_AVAILABLE -> TaskFromTextFailure.CELL_NOT_AVAILABLE
            CellTextFailure.STALE_DOCUMENT -> TaskFromTextFailure.STALE_TEXT_SELECTION
            CellTextFailure.CHANGE_CROSSES_A_TASK -> TaskFromTextFailure.SEGMENT_IS_NOT_PLAIN_TEXT
            else -> TaskFromTextFailure.COULD_NOT_SAVE
        }
}
