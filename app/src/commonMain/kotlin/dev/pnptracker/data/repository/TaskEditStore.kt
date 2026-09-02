package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.TaskEditDao
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskEditFailure
import dev.pnptracker.domain.tasks.TaskFlags
import kotlin.time.Clock

/** Changing a task that already exists, and turning one back into text. */
interface TaskEditing {
    /**
     * Saves everything the user changed about a task at once.
     *
     * The name is the task's part of the cell's document, so changing it changes
     * what the cell reads as, by exactly the name and nothing else.
     *
     * @param colorIds every colour the task is to be made in, in the user's own
     *   order; PLAN 5.10 numbers them from there. An empty list leaves the task
     *   with none, which PLAN 5.10 allows. How many colours there are may not
     *   cross between one and several — see [TaskEditFailure.COLOR_COUNT_NOT_CHANGEABLE].
     * @param requiredQuantity how many are needed, or null when unknown.
     * @param notes the user's own words, stored as typed, or null for no note.
     * @return true when something actually changed.
     * @throws TaskEditException with the case that stopped it; nothing written.
     */
    suspend fun editTask(
        taskId: EntityId,
        name: String,
        colorIds: List<EntityId>,
        requiredQuantity: Int?,
        notes: String?,
        trackingMode: TrackingMode,
        /** The four marks to store, or null to leave whatever the task carries. */
        flags: TaskFlags? = null,
    ): Boolean

    /**
     * The same, for a task made in one colour or in none.
     *
     * Named for what most tasks are, the way [TaskCreationFromText.createSingleColorTask]
     * is: a single colour is a list of one, and null is a list of none.
     */
    suspend fun editTask(
        taskId: EntityId,
        name: String,
        colorId: EntityId?,
        requiredQuantity: Int?,
        notes: String?,
        trackingMode: TrackingMode,
        flags: TaskFlags? = null,
    ): Boolean =
        editTask(
            taskId = taskId,
            name = name,
            colorIds = listOfNotNull(colorId),
            requiredQuantity = requiredQuantity,
            notes = notes,
            trackingMode = trackingMode,
            flags = flags,
        )

    /**
     * Turns a task back into the words it was made from.
     *
     * PLAN 12.8: not a deletion. The task record and everything hanging off it
     * go, and its name stays exactly where it was as ordinary text, so the cell
     * reads the same afterwards as it did before.
     *
     * @return true when there was a task here to convert.
     * @throws TaskEditException if the task is gone; nothing is written.
     */
    suspend fun convertTaskToText(taskId: EntityId): Boolean
}

/**
 * The user changing a piece of work they already wrote down.
 *
 * Thin on purpose, like the other stores over a transaction: the rules about
 * what a task may look like afterwards belong to the one transaction that
 * enforces them, so all this adds is the clock, the source of identities, and
 * turning a storage refusal into something a screen can say.
 */
class TaskEditStore(
    private val taskEditDao: TaskEditDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
) : TaskEditing {
    override suspend fun editTask(
        taskId: EntityId,
        name: String,
        colorIds: List<EntityId>,
        requiredQuantity: Int?,
        notes: String?,
        trackingMode: TrackingMode,
        flags: TaskFlags?,
    ): Boolean =
        try {
            taskEditDao.editTask(
                taskId = taskId,
                name = name,
                colorIds = colorIds,
                requiredQuantity = requiredQuantity,
                notes = notes,
                trackingMode = trackingMode,
                flags = flags,
                clock = clock,
            )
        } catch (cause: SQLiteException) {
            // Only a recognised storage refusal becomes something the user is
            // told about; a broken invariant travels out untouched.
            throw TaskEditException(TaskEditFailure.COULD_NOT_SAVE, cause = cause)
        }

    override suspend fun convertTaskToText(taskId: EntityId): Boolean =
        try {
            taskEditDao.convertTaskToText(taskId = taskId, clock = clock, idGenerator = idGenerator)
        } catch (cause: SQLiteException) {
            throw TaskEditException(TaskEditFailure.COULD_NOT_SAVE, cause = cause)
        }
}
