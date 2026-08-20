package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.TaskDao
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskSetupException
import dev.pnptracker.domain.tasks.TaskSetupFailure
import dev.pnptracker.domain.tasks.TaskSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * Creating the tasks the user types, and reading a game's tasks back.
 *
 * Nothing here goes near an import. A task made on this path carries no source
 * cell, which is exactly what keeps `sourceRawImportBlockId` meaningful as a
 * record of where a task came from, and the import tables are never read or
 * written from here at all.
 */
interface TaskSetup {
    /** The tasks of one game, and never another game's. */
    fun observeTasks(gameId: EntityId): Flow<List<TaskSummary>>

    /**
     * Creates a task the user typed, under an item that is really there.
     *
     * The name is trimmed at both ends, because trailing spaces are a slip rather
     * than a decision, and everything inside it is left alone. A note that is
     * only spaces is the same as no note.
     *
     * @param requiredQuantity how many are needed, or null when that is not
     *   known; a number that is given has to be greater than zero.
     * @throws IllegalArgumentException if the name says nothing, the quantity is
     *   not a usable one, or the pool does not allow the tracking mode.
     * @throws TaskSetupException if the item is gone, or the task did not save.
     */
    suspend fun createTask(
        itemId: EntityId,
        name: String,
        poolType: PoolType,
        trackingMode: TrackingMode,
        requiredQuantity: Int? = null,
        notes: String? = null,
    ): EntityId
}

class TaskSetupStore(
    private val taskDao: TaskDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
) : TaskSetup {
    override fun observeTasks(gameId: EntityId): Flow<List<TaskSummary>> =
        taskDao.observeActiveTasksOfGame(gameId).map { rows ->
            rows.map { row ->
                TaskSummary(
                    id = row.taskId,
                    itemId = row.itemId,
                    itemName = row.itemName,
                    poolType = row.poolType,
                    trackingMode = row.trackingMode,
                    name = row.taskName,
                    requiredQuantity = row.requiredQuantity,
                    notes = row.notes,
                    isFromImport = row.sourceRawImportBlockId != null,
                )
            }
        }

    override suspend fun createTask(
        itemId: EntityId,
        name: String,
        poolType: PoolType,
        trackingMode: TrackingMode,
        requiredQuantity: Int?,
        notes: String?,
    ): EntityId {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "A task needs a name." }
        // One reading of the clock, so a row cannot be created and updated at two
        // different moments of the same act.
        val moment = clock.now()
        // Built before anything is attempted: the entity refuses a quantity that
        // is not positive and a tracking mode the pool does not allow, and those
        // must not be caught below and reported as a saving problem.
        val task =
            TaskEntity(
                id = idGenerator.newId(),
                itemId = itemId,
                poolType = poolType,
                trackingMode = trackingMode,
                name = cleanName,
                requiredQuantity = requiredQuantity,
                notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = moment,
                updatedAt = moment,
                // Typed by hand, so there is no imported cell behind it.
                sourceRawImportBlockId = null,
            )
        try {
            taskDao.addTaskToActiveItem(task)
        } catch (cause: IllegalArgumentException) {
            // The one thing that check reports is a parent that is not there any
            // more, which is something the user can see and act on.
            throw TaskSetupException(TaskSetupFailure.ITEM_NOT_AVAILABLE, cause)
        } catch (cause: SQLiteException) {
            throw TaskSetupException(TaskSetupFailure.COULD_NOT_SAVE, cause)
        }
        return task.id
    }
}
