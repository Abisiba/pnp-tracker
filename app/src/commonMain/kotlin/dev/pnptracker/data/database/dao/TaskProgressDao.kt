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
 * * a finished task owes nothing and has done the work its own pool measures it
 *   by — a 3D task its print run, a card or board task every stage of its
 *   pipeline counted up to a total that was given.
 *
 * That last pair is PLAN 6.4's rule that `isCompleted` may never contradict the
 * counters. It is why finishing a task is not simply a flag being set: the
 * counters are settled in the same transaction, and the settling is recorded as
 * an event rather than done quietly, so the history still adds up afterwards.
 * What "done" means is asked of one function, so no path can answer it its own
 * way, and asked again after the write, so a path that answers wrongly takes
 * itself down rather than leaving the task behind.
 *
 * Events are named by their caller so a retry can be recognised. A name that
 * comes back with the same event is a retry and does nothing; a name that comes
 * back attached to a different event is refused, because treating that as a
 * retry would drop a real movement while telling the caller all was well.
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
     * Every shortage on a task that named a card, oldest first.
     *
     * PLAN 7.4 lets a user say which card was short as well as how many, and
     * leaves the naming optional; a shortage recorded as a bare number simply
     * does not appear here.
     *
     * This is the **history** and nothing more. It does not say which of these
     * records is still outstanding, and it must not be read as though it did:
     * making a shortage good is recorded against the task as a number, not
     * against the particular card that was named, so nothing stored today can
     * tell one of these apart from another. Telling them apart needs a model of
     * its own, which PLAN 7.4 leaves to a later step. Until then the honest
     * answer is the whole list, and the name says so.
     */
    @Query(
        """
        SELECT * FROM progress_events
        WHERE task_id = :taskId
          AND kind = 'FAILURE_REPORTED'
          AND card_reference IS NOT NULL
        ORDER BY recorded_at, id
        """,
    )
    abstract suspend fun cardShortageHistoryOf(taskId: EntityId): List<ProgressEventEntity>

    /** One event by the name its caller gave it, or null when that name is free. */
    @Query("SELECT * FROM progress_events WHERE id = :eventId")
    abstract suspend fun eventById(eventId: EntityId): ProgressEventEntity?

    // ------------------------------------------------------------- writing

    /**
     * Writes an event unless its name is taken, and says which happened.
     *
     * Ignoring the clash rather than aborting is what makes the race safe: two
     * callers handing in the same event at once both reach here, exactly one
     * inserts, and the other is told so by the return value instead of by an
     * exception it would have to interpret.
     *
     * @return the new row, or -1 when an event of that name was already there.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEventIfNew(event: ProgressEventEntity): Long

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
            val settled =
                insertEventIfNew(
                    ProgressEventEntity(
                        id = idGenerator.newId(),
                        taskId = taskId,
                        kind = ProgressEventKind.SHORTAGE_RESOLVED,
                        quantity = task.currentMissingQuantity,
                        recordedAt = moment,
                    ),
                )
            // A generated name that was already taken is not a retry of anything;
            // it is an identifier collision, and settling silently without the
            // event would leave the counter unexplained.
            check(settled != -1L) { "The settling event for $taskId was given a name that was already taken." }
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
     * **Only a 3D task has a run to record.** The whole of PLAN 6 is the 3D
     * model, and the flag belongs to it: a card or board task is counted by its
     * pipeline, a special one by whatever the user chose. Allowing the run to be
     * recorded on those would finish a card task with nothing printed, which is
     * exactly the contradiction PLAN 6.4 forbids. So the pool is named rather
     * than asked whether it has stages — a special task has no pipeline either,
     * and it still has no print run.
     *
     * @return true when this call was the one that recorded the run.
     * @throws TaskProgressException if there is no task to record it on, or the
     *   task is not counted by a print run.
     */
    @Transaction
    open suspend fun completePrimaryBatch(
        taskId: EntityId,
        clock: Clock,
    ): Boolean {
        val task = workableTaskById(taskId) ?: refuse(TaskProgressFailure.TASK_NOT_AVAILABLE)
        if (task.poolType != PoolType.THREE_D) refuse(TaskProgressFailure.PRIMARY_BATCH_ONLY_FOR_THREE_D)
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
     * Handing the same [eventId] back with the same details does nothing at all.
     * It is how a retry after an uncertain failure stays a retry rather than
     * becoming a second report of the same shortage. Handing it back with
     * *different* details is refused instead: that is not a retry, and taking it
     * for one would drop a real shortage without telling anybody.
     *
     * The optional detail is checked against the task rather than stored
     * blindly. [stage] has to be one this task's pool actually works through —
     * the same template the pipeline itself is built from — and [cardReference]
     * only means something on a card task, per PLAN 7.4. A [note] fits any pool.
     *
     * @return true when this call was the one that recorded the shortage.
     * @throws TaskProgressException if there is no task to record it on, the
     *   detail does not belong to it, or the event name is already spent on a
     *   different event.
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
        requireDetailBelongsTo(task, cardReference, stage)
        val event =
            ProgressEventEntity(
                id = eventId,
                taskId = taskId,
                kind = ProgressEventKind.FAILURE_REPORTED,
                quantity = quantity,
                note = note,
                cardReference = cardReference,
                stage = stage,
                // Filled in below, once we know this is not a retry. Never read.
                recordedAt = task.updatedAt,
            )
        if (alreadyRecorded(event)) return false
        val moment = clock.now()
        if (!recordOnce(event.copy(recordedAt = moment))) return false

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
     * nothing finishes the task — but only if the rest of the work says it is
     * done too. What that means is the pool's business, so the question is asked
     * of [readyToFinish] rather than answered here: a 3D task needs its print
     * run made, a card or board task needs its pipeline counted all the way up,
     * and a special task is never finished behind the user's back.
     *
     * That is what keeps this from finishing a card task with nothing printed.
     * Owing nothing is not the same as having done the work; it only means
     * nothing is outstanding from what *was* done.
     *
     * The failure total is untouched, which is the point of keeping it as a sum
     * over the events: making good is a new event, not the deletion of an old one.
     *
     * @return true when this call was the one that recorded it.
     * @throws TaskProgressException if there is no such task, more was made good
     *   than was owed, the detail does not belong to the task, or the event name
     *   is already spent on a different event.
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
        requireDetailBelongsTo(task, cardReference, stage = null)
        val event =
            ProgressEventEntity(
                id = eventId,
                taskId = taskId,
                kind = ProgressEventKind.SHORTAGE_RESOLVED,
                quantity = quantity,
                note = note,
                cardReference = cardReference,
                recordedAt = task.updatedAt,
            )
        if (alreadyRecorded(event)) return false
        if (quantity > task.currentMissingQuantity) {
            refuse(TaskProgressFailure.MORE_RESOLVED_THAN_OUTSTANDING)
        }
        val moment = clock.now()
        if (!recordOnce(event.copy(recordedAt = moment))) return false

        val owed = task.currentMissingQuantity - quantity
        val finished = owed == 0 && readyToFinish(task, stagesOfTask(taskId))
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
        val worked = stages.map { if (it.stage == stage) it.copy(completedQuantity = completedQuantity) else it }
        // A pipeline counted all the way up still does not finish a task that
        // owes a reprint: PLAN 6.4 will not have the finished mark stand against
        // a counter saying work is left.
        val finished = task.currentMissingQuantity == 0 && readyToFinish(task, worked)
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
     * Whether the work a task is measured by has all been done.
     *
     * The one place the question is answered, so every path that can finish a
     * task agrees about what finished means. It says nothing about what is owed
     * — that is a separate condition each caller adds — only about whether the
     * work itself is complete.
     *
     * * A 3D task is measured by its one print run (PLAN 6.2).
     * * A card or board task is measured by its pipeline: every stage counted up
     *   to the total (PLAN 7.2, 8). With no total given there is nothing to
     *   count up to, so it cannot finish on its own — PLAN 6.4 leaves that case
     *   to the user finishing it by hand, which [completeTask] is.
     * * A special task is measured by nothing the database knows (PLAN 9), so it
     *   is never finished except by being finished deliberately.
     */
    private fun readyToFinish(
        task: TaskEntity,
        stages: List<TaskStageEntity>,
    ): Boolean =
        when (task.poolType) {
            PoolType.THREE_D -> task.primaryBatchCompleted
            PoolType.CARD, PoolType.BOARD ->
                task.requiredQuantity?.let { total -> stages.all { it.completedQuantity == total } } == true
            PoolType.SPECIAL -> false
        }

    /**
     * Refuses detail that does not belong to the task it is being recorded on.
     *
     * The stage is checked against [stagesOf] — the same template the pipeline
     * was built from — rather than against a second list written out here, so
     * there is no way for the two to drift apart.
     */
    private fun requireDetailBelongsTo(
        task: TaskEntity,
        cardReference: String?,
        stage: ProductionStage?,
    ) {
        if (cardReference != null && task.poolType != PoolType.CARD) {
            refuse(TaskProgressFailure.CARD_REFERENCE_ONLY_FOR_CARDS)
        }
        if (stage != null) {
            val pipeline = stagesOf(task.poolType)
            if (pipeline.isEmpty()) refuse(TaskProgressFailure.TASK_HAS_NO_STAGES)
            if (stage !in pipeline) refuse(TaskProgressFailure.STAGE_NOT_IN_PIPELINE)
        }
    }

    /**
     * Whether this exact event is already in the history.
     *
     * Everything the caller decided is compared; only [ProgressEventEntity.recordedAt]
     * is left out, because a genuine retry arrives later than the attempt it is
     * repeating and the first attempt's time is the one that stands.
     *
     * A name already spent on a *different* event is refused rather than
     * reported as a duplicate. Returning false there would look to the caller
     * exactly like a successful retry while the movement it asked for was never
     * recorded.
     */
    private suspend fun alreadyRecorded(event: ProgressEventEntity): Boolean {
        val existing = eventById(event.id) ?: return false
        if (existing.copy(recordedAt = event.recordedAt) != event) {
            refuse(TaskProgressFailure.EVENT_ID_ALREADY_USED)
        }
        return true
    }

    /**
     * Writes the event unless another writer got there first.
     *
     * [alreadyRecorded] has already answered for the ordinary case; this covers
     * the one where two callers hand in the same event at the same moment. The
     * insert itself decides which of them wins, and the loser checks that what
     * landed really is its own event before treating the clash as a retry.
     *
     * @return true when this call was the one that wrote the event.
     */
    private suspend fun recordOnce(event: ProgressEventEntity): Boolean {
        if (insertEventIfNew(event) != -1L) return true
        check(alreadyRecorded(event)) { "The event ${event.id} vanished between two reads of it." }
        return false
    }

    /**
     * Reads back what the transaction has just written and refuses to let it
     * stand unless the task still adds up. Still inside the transaction, so a
     * broken invariant takes every write with it.
     *
     * This is the second line rather than the first. Each transaction already
     * decides carefully what to write; this is here so that a path which decides
     * wrongly — today's or a later step's — fails loudly instead of leaving a
     * task the rest of the application would have to be taught to distrust.
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
        val stages = stagesOfTask(taskId)
        stages.zipWithNext { earlier, later ->
            check(later.completedQuantity <= earlier.completedQuantity) {
                "The task $taskId has ${later.stage} ahead of ${earlier.stage}."
            }
        }
        // PLAN 6.4: the finished mark is the real state and may not contradict
        // the counters. So a finished task has to have done the work its own
        // pool measures it by — and only that. Asking a special task for a
        // pipeline it never had, or a print run that is not its idea, would
        // invent a rule rather than enforce one.
        if (task.isCompleted) {
            when (task.poolType) {
                PoolType.THREE_D ->
                    check(task.primaryBatchCompleted) {
                        "The finished 3D task $taskId never had its print run made."
                    }
                PoolType.CARD, PoolType.BOARD ->
                    // With no total given there is nothing for a stage to reach,
                    // and PLAN 6.4 leaves that task to be finished by hand.
                    task.requiredQuantity?.let { total ->
                        check(stages.all { it.completedQuantity == total }) {
                            "The finished task $taskId has a pipeline at " +
                                "${stages.map { it.completedQuantity }} of $total."
                        }
                    }
                // Measured by nothing the database keeps, so nothing to check.
                PoolType.SPECIAL -> Unit
            }
        }
    }

    private fun refuse(failure: TaskProgressFailure): Nothing = throw TaskProgressException(failure)
}
