package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.TaskFromTextDao
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.diagnostics.storageWriteFailed
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import kotlin.time.Clock

/** Turning words the user selected in a cell into tasks. */
interface TaskCreationFromText {
    /**
     * Makes the selected words into tasks, in the cell they were written in.
     *
     * The cell's text is not changed by this, only its shape: what it reads as
     * before and after is the same string, character for character.
     *
     * One draft makes one task. Several make several **independent** tasks (PLAN
     * 12.7) — one identity, colour, quantity, note, pipeline and place in the
     * cell each, in the order given — with nothing written that ties them
     * together and nothing about one of them able to reach another. They start
     * out sharing a name, which is where the resemblance ends: renaming one is a
     * rename of one.
     *
     * A draft naming several colours makes one task made in all of them (PLAN
     * 5.10): one identity, one piece of the cell, one total and one counter,
     * with a colour relation per colour in the order they were chosen. It is the
     * opposite of a batch in every way but the panel it is typed in — which is
     * why they are the same call, told apart by the shape of what is described
     * rather than by a flag saying which mode was open.
     *
     * @return the identities of the tasks that were created, in the order of
     *   [drafts].
     * @throws TaskFromTextException for a refusal the user can act on; which one
     *   it was is on the exception, and nothing is written in any of those cases.
     */
    suspend fun createTasks(
        selection: CellTextSelection,
        drafts: List<TaskDraft>,
    ): List<EntityId>

    /**
     * Makes the selected words a task of one colour.
     *
     * The single-draft case of [createTasks], named for what the panel's first
     * mode does.
     *
     * @param requiredQuantity how many are needed; has to be greater than zero.
     * @param notes the user's own words, stored exactly as they typed them, or
     *   null when they wrote none.
     * @return the identity of the task that was created.
     */
    suspend fun createSingleColorTask(
        selection: CellTextSelection,
        colorId: EntityId,
        requiredQuantity: Int,
        trackingMode: TrackingMode,
        notes: String?,
    ): EntityId =
        createTasks(
            selection = selection,
            drafts =
                listOf(
                    TaskDraft(
                        colorIds = listOf(colorId),
                        requiredQuantity = requiredQuantity,
                        trackingMode = trackingMode,
                        notes = notes,
                    ),
                ),
        ).single()
}

/**
 * The user turning their own note into a piece of work.
 *
 * Thin on purpose, like the other stores over a transaction: the rules about
 * what a cell may look like afterwards belong to the one transaction that
 * enforces them, so all this adds is the clock, the source of identities, and
 * turning a storage refusal into something a screen can say.
 */
class TaskFromTextStore(
    private val taskFromTextDao: TaskFromTextDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
    private val diagnostics: Diagnostics = Diagnostics.None,
) : TaskCreationFromText {
    override suspend fun createTasks(
        selection: CellTextSelection,
        drafts: List<TaskDraft>,
    ): List<EntityId> =
        try {
            taskFromTextDao.createTasksFromSelection(
                selection = selection,
                drafts = drafts,
                clock = clock,
                idGenerator = idGenerator,
            )
        } catch (cause: SQLiteException) {
            // Only a recognised storage refusal becomes something the user is
            // told about. A broken invariant travels out untouched: catching it
            // here would file a programming mistake under a problem they are
            // asked to fix.
            diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.TASK_FROM_TEXT, TaskFromTextFailure.COULD_NOT_SAVE, cause) }
            throw TaskFromTextException(TaskFromTextFailure.COULD_NOT_SAVE, cause = cause)
        }
}
