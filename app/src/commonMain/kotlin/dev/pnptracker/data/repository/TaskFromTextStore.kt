package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.TaskFromTextDao
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import kotlin.time.Clock

/** Turning words the user selected in a cell into a task. */
interface TaskCreationFromText {
    /**
     * Makes the selected words a task of one colour, in the cell they were
     * written in.
     *
     * The cell's text is not changed by this, only its shape: what it reads as
     * before and after is the same string, character for character.
     *
     * @param requiredQuantity how many are needed; has to be greater than zero.
     * @param notes the user's own words, stored exactly as they typed them, or
     *   null when they wrote none.
     * @return the identity of the task that was created.
     * @throws TaskFromTextException for a refusal the user can act on; which one
     *   it was is on the exception, and nothing is written in any of those cases.
     */
    suspend fun createSingleColorTask(
        selection: CellTextSelection,
        colorId: EntityId,
        requiredQuantity: Int,
        trackingMode: TrackingMode,
        notes: String?,
    ): EntityId
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
) : TaskCreationFromText {
    override suspend fun createSingleColorTask(
        selection: CellTextSelection,
        colorId: EntityId,
        requiredQuantity: Int,
        trackingMode: TrackingMode,
        notes: String?,
    ): EntityId =
        try {
            taskFromTextDao.createSingleColorTaskFromSelection(
                selection = selection,
                colorId = colorId,
                requiredQuantity = requiredQuantity,
                trackingMode = trackingMode,
                notes = notes,
                clock = clock,
                idGenerator = idGenerator,
            )
        } catch (cause: SQLiteException) {
            // Only a recognised storage refusal becomes something the user is
            // told about. A broken invariant travels out untouched: catching it
            // here would file a programming mistake under a problem they are
            // asked to fix.
            throw TaskFromTextException(TaskFromTextFailure.COULD_NOT_SAVE, cause)
        }
}
