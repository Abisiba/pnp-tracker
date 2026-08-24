package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.ProgressEventEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.entity.TaskStageEntity
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.ProgressEventKind
import dev.pnptracker.domain.model.hasStages
import dev.pnptracker.domain.model.stagesOf
import dev.pnptracker.domain.tasks.TaskProgressException
import dev.pnptracker.domain.tasks.TaskProgressFailure
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Working a task: finishing it, reopening it, and recording what went wrong.
 *
 * Every change here happens in one transaction, and each one holds the same
 * invariant at the end of it:
 *
 * * what is still owed is never below nothing and never above the task's total;
 * * what is still owed never exceeds what has been reported failed less what has
 *   been made good, so the cached counter can always be justified by the history;
 * * a task is finished exactly when it has a time it was finished at;
 * * a finished task owes nothing and, when its total is known, has every stage
 *   counted up to it.
 *
 * That last pair is PLAN 6.4's rule that `isCompleted` may never contradict the
 * counters. It is why finishing a task is not simply a flag being set: the
 * counters are settled in the same transaction, and the settling is recorded as
 * an event rather than done quietly, so the history still adds up afterwards.
 *
 * The clock is passed in rather than read at the top of each method. Repeating
 * an operation that has already happened is a no-op, and a no-op must not move a
 * timestamp — so the clock is only read once the transaction knows it is going
 * to write something.
 */
@Dao
abstract class TaskProgressDao {
    // ------------------------------------------------------------- reading

    /** The task, whether or not it is deleted or finished. */
    @Query("SELECT * FROM tasks WHERE id = :taskId")
    abstract suspend fun taskById(taskId: EntityId): TaskEntity?

    /**
     * The task, if it can still be worked on.
     *
     * A task whose game has been deleted is not workable either, so the chain
     * back through the cell is walked rather than only the task's own tombstone.
     */
    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.id = :taskId
          AND tasks.deleted_at IS NULL AND games.deleted_at IS NULL
        """,
    )
    abstract suspend fun workableTaskById(taskId: EntityId): TaskEntity?

    /** One task's stages, in the order they are worked in. */
    @Query("SELECT * FROM task_stages WHERE task_id = :taskId ORDER BY order_index")
    abstract suspend fun stagesOfTask(taskId: EntityId): List<TaskStageEntity>

    /**
     * One task's whole history, oldest first.
     *
     * Two events recorded in the same millisecond are separated by `id`, so the
     * order never changes between reads. Nothing is filtered out: a deleted
     * task's history is still its history, and PLAN 6.3 keeps it.
     */
    @Query("SELECT * FROM progress_events WHERE task_id = :taskId ORDER BY recorded_at, id")
    abstract suspend fun progressEventsOfTask(taskId: EntityId): List<ProgressEventEntity>

    /**
     * Everything ever reported failed on this task.
     *
     * Summed from the events and stored nowhere, which is what PLAN 6.2 means by
     * it being derived, and why making a shortage good does not reduce it.
     */
    @Query(
        """
        SELECT COALESCE(SUM(quantity), 0) FROM progress_events
        WHERE task_id = :taskId AND kind = 'FAILURE_REPORTED'
        """,
    )
    abstract suspend fun failureTotalOf(taskId: EntityId): Int

    /** Everything ever reported made good on this task. */
    @Query(
        """
        SELECT COALESCE(SUM(quantity), 0) FROM progress_events
        WHERE task_id = :taskId AND kind = 'SHORTAGE_RESOLVED'
        """,
    )
    abstract suspend fun resolvedTotalOf(taskId: EntityId): Int

    /**
     * The shortages still open on a task that name a card, newest last.
     *
     * PLAN 7.4 lets a user say which card was short as well as how many, and
     * leaves the naming optional; a shortage recorded as a bare number simply
     * does not appear here. They are called open because the task still owes
     * something: which particular card was made good is a judgement PLAN leaves
     * to the stage work of a later step, so nothing here claims to know it.
     */
    @Query(
        """
        SELECT progress_events.* FROM progress_events
        INNER JOIN tasks ON tasks.id = progress_events.task_id
        WHERE progress_events.task_id = :taskId
          AND progress_events.kind = 'FAILURE_REPORTED'
          AND progress_events.card_reference IS NOT NULL
          AND tasks.current_missing_quantity > 0
        ORDER BY progress_events.recorded_at, progress_events.id
        """,
    )
    abstract suspend fun openShortageDetailsOf(taskId: EntityId): List<ProgressEventEntity>

    @Query("SELECT COUNT(*) FROM progress_events WHERE id = :eventId")
    abstract suspend fun countOfEvent(eventId: EntityId): Int

    // ------------------------------------------------------------- writing

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertStage(stage: TaskStageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEvent(event: ProgressEventEntity)

    @Query(
        """
        UPDATE tasks SET is_completed = :isCompleted, completed_at = :completedAt,
                         primary_batch_completed = :primaryBatchCompleted,
                         current_missing_quantity = :currentMissingQuantity,
                         updated_at = :updatedAt
        WHERE id = :taskId
        """,
    )
    protected abstract suspend fun writeProgress(
        taskId: EntityId,
        isCompleted: Boolean,
        completedAt: Instant?,
        primaryBatchCompleted: Boolean,
        currentMissingQuantity: Int,
        updatedAt: Instant,
    ): Int

    @Query(
        """
        UPDATE task_stages SET completed_quantity = :completedQuantity, updated_at = :updatedAt
        WHERE task_id = :taskId AND stage = :stage
        """,
    )
    protected abstract suspend fun writeStage(
        taskId: EntityId,
        stage: ProductionStage,
        completedQuantity: Int,
        updatedAt: Instant,
    ): Int

    // -------------------------------------------------------- transactions

    /**
     * Writes the stage rows a pool's pipeline needs, all of them or none.
     *
     * Called from the same transaction that writes the task, so a card task
     * never exists with half a pipeline under it. A pool with no pipeline gets
     * nothing rather than a row saying so.
     */
    suspend fun openStagesFor(
        taskId: EntityId,
        poolType: PoolType,
        moment: Instant,
    ) {
        stagesOf(poolType).forEachIndexed { index, stage ->
            insertStage(
                TaskStageEntity(
                    taskId = taskId,
                    stage = stage,
                    orderIndex = index,
                    createdAt = moment,
                    updatedAt = moment,
                ),
            )
        }
    }

    /**
     * Marks a task finished, settling what it owes in the same breath.
     *
     * PLAN 6.4 forbids the finished flag from contradicting the counters, and
     * PLAN 12.9 has finishing a game finish its tasks *and their stages*. So an
     * outstanding shortage is made good here rather than left standing next to a
     * task claiming to be done — and it is made good by recording it, so the
     * failure total is untouched and the history still explains the counter.
     *
     * Doing this twice changes nothing and, in particular, does not move the time
     * it was finished at: the clock is not even read on the second call.
     *
     * @return true when this call was the one that finished the task.
     * @throws TaskProgressException if there is no task to finish.
     */
    @Transaction
    open suspend fun completeTask(
        taskId: EntityId,
        clock: Clock,
        idGenerator: IdGenerator,
    ): Boolean {
        val task = workableTaskById(taskId) ?: refuse(TaskProgressFailure.TASK_NOT_AVAILABLE)
        if (task.isCompleted) return false
        val moment = clock.now()

        if (task.currentMissingQuantity > 0) {
            insertEvent(
                ProgressEventEntity(
                    id = idGenerator.newId(),
                    taskId = taskId,
                    kind = ProgressEventKind.SHORTAGE_RESOLVED,
                    quantity = task.currentMissingQuantity,
                    recordedAt = moment,
                ),
            )
        }
        task.requiredQuantity?.let { total ->
            stagesOfTask(taskId).forEach { stage ->
                if (stage.completedQuantity != total) writeStage(taskId, stage.stage, total, moment)
            }
        }
        writeProgress(
            taskId = taskId,
            isCompleted = true,
            completedAt = moment,
            // Only the 3D pool counts a print run; for the others the flag is
            // not part of what being finished means.
            primaryBatchCompleted = task.primaryBatchCompleted || task.poolType == PoolType.THREE_D,
            currentMissingQuantity = 0,
            updatedAt = moment,
        )
        requireProgressHolds(taskId)
        return true
    }

    /**
     * Takes back a finished mark, putting the task back in its active pool.
     *
     * The counters are left alone: a finished task owes nothing, and owing
     * nothing is a perfectly ordinary state for an unfinished one to be in too.
     * Its history is untouched.
     *
     * @return true when this call was the one that reopened the task.
     * @throws TaskProgressException if there is no task to reopen.
     */
    @Transaction
    open suspend fun reopenTask(
        taskId: EntityId,
        clock: Clock,
    ): Boolean {
        val task = workableTaskById(taskId) ?: refuse(TaskProgressFailure.TASK_NOT_AVAILABLE)
        if (!task.isCompleted) return false
        val moment = clock.now()
        writeProgress(
            taskId = taskId,
            isCompleted = false,
            completedAt = null,
            primaryBatchCompleted = task.primaryBatchCompleted,
            currentMissingQuantity = task.currentMissingQuantity,
            updatedAt = moment,
        )
        requireProgressHolds(taskId)
        return true
    }

    /**
     * Records that the one print run a 3D task is counted by has been made.
     *
     * PLAN 6.2: with the run made and nothing owed, the task is finished. If
     * something is owed it stays active and waits to be made good.
     *
     * @return true when this call was the one that recorded the run.
     * @throws TaskProgressException if there is no task to record it on.
     */
    @Transaction
    open suspend fun completePrimaryBatch(
        taskId: EntityId,
        clock: Clock,
    ): Boolean {
        val task = workableTaskById(taskId) ?: refuse(TaskProgressFailure.TASK_NOT_AVAILABLE)
        if (task.primaryBatchCompleted) return false
        val moment = clock.now()
        val finished = task.currentMissingQuantity == 0
        writeProgress(
            taskId = taskId,
            isCompleted = finished,
            completedAt = moment.takeIf { finished },
            primaryBatchCompleted = true,
            currentMissingQuantity = task.currentMissingQuantity,
            updatedAt = moment,
        )
        requireProgressHolds(taskId)
        return true
    }

    /**
     * Records pieces that came out missing or spoiled.
     *
     * The amount is added to what is still owed and the event is written in the
     * same transaction, so the counter and the history can never be left saying
     * different things. PLAN 6.3 has a report imply that a print run was made,
     * and a report on a finished task bring it back into the active pool; both
     * happen here rather than being left for a caller to remember.
     *
     * PLAN 6.4 keeps what is owed from rising above what the task needs in total,
     * while the failure total is free to go past it — a piece can be spoiled more
     * than once. That is why the two are different numbers.
     *
     * Handing the same [eventId] again does nothing at all. It is how a retry
     * after an uncertain failure stays a retry rather than becoming a second
     * report of the same shortage.
     *
     * @return true when this call was the one that recorded the shortage.
     * @throws TaskProgressException if there is no task to record it on.
     * @throws IllegalArgumentException if the amount is not at least one piece.
     */
    @Transaction
    open suspend fun reportFailure(
        eventId: EntityId,
        taskId: EntityId,
        quantity: Int,
        clock: Clock,
        note: String? = null,
        cardReference: String? = null,
        stage: ProductionStage? = null,
    ): Boolean {
        require(quantity > 0) { "A shortage has to be about at least one piece, was: $quantity" }
        val task = workableTaskById(taskId) ?: refuse(TaskProgressFailure.TASK_NOT_AVAILABLE)
        if (countOfEvent(eventId) > 0) return false
        val moment = clock.now()

        insertEvent(
            ProgressEventEntity(
                id = eventId,
                taskId = taskId,
                kind = ProgressEventKind.FAILURE_REPORTED,
                quantity = quantity,
                note = note,
                cardReference = cardReference,
                stage = stage,
                recordedAt = moment,
            ),
        )
        val owed = task.currentMissingQuantity + quantity
        writeProgress(
            taskId = taskId,
            isCompleted = false,
            completedAt = null,
            primaryBatchCompleted = task.primaryBatchCompleted || task.poolType == PoolType.THREE_D,
            currentMissingQuantity = task.requiredQuantity?.let { minOf(owed, it) } ?: owed,
            updatedAt = moment,
        )
        requireProgressHolds(taskId)
        return true
    }

    /**
     * Records that some of what was owed has been made again.
     *
     * PLAN 6.3: what is owed comes down, never below nothing, and reaching
     * nothing finishes the task — but only once the print run has been made,
     * because a task that owes nothing and has never been printed has not been
     * done, it has not been started.
     *
     * The failure total is untouched, which is the point of keeping it as a sum
     * over the events: making good is a new event, not the deletion of an old one.
     *
     * @return true when this call was the one that recorded it.
     * @throws TaskProgressException if there is no such task, or more was made
     *   good than was owed.
     * @throws IllegalArgumentException if the amount is not at least one piece.
     */
    @Transaction
    open suspend fun resolveShortage(
        eventId: EntityId,
        taskId: EntityId,
        quantity: Int,
        clock: Clock,
        note: String? = null,
        cardReference: String? = null,
    ): Boolean {
        require(quantity > 0) { "Making good has to be about at least one piece, was: $quantity" }
        val task = workableTaskById(taskId) ?: refuse(TaskProgressFailure.TASK_NOT_AVAILABLE)
        if (countOfEvent(eventId) > 0) return false
        if (quantity > task.currentMissingQuantity) {
            refuse(TaskProgressFailure.MORE_RESOLVED_THAN_OUTSTANDING)
        }
        val moment = clock.now()

        insertEvent(
            ProgressEventEntity(
                id = eventId,
                taskId = taskId,
                kind = ProgressEventKind.SHORTAGE_RESOLVED,
                quantity = quantity,
                note = note,
                cardReference = cardReference,
                recordedAt = moment,
            ),
        )
        val owed = task.currentMissingQuantity - quantity
        val finished = owed == 0 && task.primaryBatchCompleted
        writeProgress(
            taskId = taskId,
            isCompleted = finished,
            completedAt = moment.takeIf { finished },
            primaryBatchCompleted = task.primaryBatchCompleted,
            currentMissingQuantity = owed,
            updatedAt = moment,
        )
        requireProgressHolds(taskId)
        return true
    }

    /**
     * Sets how many pieces have been through one step of a pipeline.
     *
     * The bound PLAN 7.2 writes as `0 <= cut <= laminated <= printed <= total` is
     * checked in both directions: a step cannot pass the one before it, and it
     * cannot be pulled back below the one after it. Either would leave the
     * pipeline describing an order of work that cannot have happened.
     *
     * A total that nobody has given is refused rather than guessed. PLAN 7.2 asks
     * the user for it first, since without it there is nothing for a step to
     * count up to and no way to say whether the task is done.
     *
     * Every step reaching the total finishes the task; a step dropping back below
     * it reopens the task, because PLAN 6.4 does not let the finished mark stand
     * against counters that disagree with it.
     *
     * @return true when the count changed.
     * @throws TaskProgressException if the task, the pool, the stage or the
     *   amount will not have it.
     */
    @Transaction
    open suspend fun setStageQuantity(
        taskId: EntityId,
        stage: ProductionStage,
        completedQuantity: Int,
        clock: Clock,
    ): Boolean {
        val task = workableTaskById(taskId) ?: refuse(TaskProgressFailure.TASK_NOT_AVAILABLE)
        if (!task.poolType.hasStages) refuse(TaskProgressFailure.TASK_HAS_NO_STAGES)
        val pipeline = stagesOf(task.poolType)
        val position = pipeline.indexOf(stage)
        if (position < 0) refuse(TaskProgressFailure.STAGE_NOT_IN_PIPELINE)
        val total = task.requiredQuantity ?: refuse(TaskProgressFailure.REQUIRED_QUANTITY_UNKNOWN)
        if (completedQuantity < 0 || completedQuantity > total) {
            refuse(TaskProgressFailure.STAGE_ORDER_VIOLATED)
        }

        val stages = stagesOfTask(taskId)
        val current = stages.firstOrNull { it.stage == stage } ?: refuse(TaskProgressFailure.STAGE_NOT_IN_PIPELINE)
        val before = stages.firstOrNull { it.orderIndex == current.orderIndex - 1 }
        val after = stages.firstOrNull { it.orderIndex == current.orderIndex + 1 }
        if (before != null && completedQuantity > before.completedQuantity) {
            refuse(TaskProgressFailure.STAGE_ORDER_VIOLATED)
        }
        if (after != null && completedQuantity < after.completedQuantity) {
            refuse(TaskProgressFailure.STAGE_ORDER_VIOLATED)
        }
        if (current.completedQuantity == completedQuantity) return false

        val moment = clock.now()
        writeStage(taskId, stage, completedQuantity, moment)
        val finished = stages.all { if (it.stage == stage) completedQuantity == total else it.completedQuantity == total }
        writeProgress(
            taskId = taskId,
            isCompleted = finished,
            completedAt = if (finished) task.completedAt ?: moment else null,
            primaryBatchCompleted = task.primaryBatchCompleted,
            currentMissingQuantity = task.currentMissingQuantity,
            updatedAt = moment,
        )
        requireProgressHolds(taskId)
        return true
    }

    /**
     * Reads back what the transaction has just written and refuses to let it
     * stand unless the task still adds up. Still inside the transaction, so a
     * broken invariant takes every write with it.
     */
    private suspend fun requireProgressHolds(taskId: EntityId) {
        val task = checkNotNull(taskById(taskId)) { "The task $taskId disappeared while it was being worked on." }
        check(task.currentMissingQuantity >= 0) {
            "The task $taskId owes ${task.currentMissingQuantity}, which is less than nothing."
        }
        check(task.requiredQuantity == null || task.currentMissingQuantity <= task.requiredQuantity) {
            "The task $taskId owes ${task.currentMissingQuantity} of ${task.requiredQuantity}."
        }
        check(task.isCompleted == (task.completedAt != null)) {
            "The task $taskId is ${task.isCompleted} but finished at ${task.completedAt}."
        }
        check(!task.isCompleted || task.currentMissingQuantity == 0) {
            "The task $taskId is finished and still owes ${task.currentMissingQuantity}."
        }
        // The cached counter has to be something the history can account for.
        // It may be lower — PLAN 6.4 caps it at the total while the failure
        // total is free to go past — but never higher.
        val outstanding = failureTotalOf(taskId) - resolvedTotalOf(taskId)
        check(task.currentMissingQuantity <= outstanding) {
            "The task $taskId owes ${task.currentMissingQuantity} but its history accounts for $outstanding."
        }
        stagesOfTask(taskId).zipWithNext { earlier, later ->
            check(later.completedQuantity <= earlier.completedQuantity) {
                "The task $taskId has ${later.stage} ahead of ${earlier.stage}."
            }
        }
    }

    private fun refuse(failure: TaskProgressFailure): Nothing = throw TaskProgressException(failure)
}
