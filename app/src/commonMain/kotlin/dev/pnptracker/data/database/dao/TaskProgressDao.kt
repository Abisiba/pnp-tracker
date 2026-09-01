package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.entity.ProgressEventEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.entity.TaskStageEntity
import dev.pnptracker.domain.games.GameCompletionSnapshot
import dev.pnptracker.domain.games.GameStageSnapshot
import dev.pnptracker.domain.games.GameTaskSnapshot
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.ProgressEventKind
import dev.pnptracker.domain.model.hasStages
import dev.pnptracker.domain.model.stagesOf
import dev.pnptracker.domain.tasks.StageSnapshot
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
     * The game a task is written in, if the game is still there.
     *
     * The same chain [workableTaskById] walks, read the other way round. PLAN
     * 6.3 has a shortage reported on a task inside a finished game reopen the
     * game as well, so the transaction that records the shortage has to know
     * which game that is — and know it from the database rather than from
     * whatever the screen believed when the form was opened.
     */
    @Query(
        """
        SELECT games.* FROM games
        INNER JOIN game_cells ON game_cells.game_id = games.id
        INNER JOIN cell_segments ON cell_segments.cell_id = game_cells.id
        WHERE cell_segments.task_id = :taskId AND games.deleted_at IS NULL
        """,
    )
    abstract suspend fun gameOfTask(taskId: EntityId): GameEntity?

    /** The game, if it is still there. */
    @Query("SELECT * FROM games WHERE id = :gameId AND deleted_at IS NULL")
    abstract suspend fun activeGameById(gameId: EntityId): GameEntity?

    /**
     * Every task of one game that can still be worked on, finished ones included.
     *
     * One query for the whole game, ordered by identity. PLAN 12.9 finishes a
     * game's work as one act, so the work is read as one thing: asking task by
     * task would put a decision query in front of every row and make the cost of
     * finishing a game grow with how much is in it.
     *
     * Ordered by `tasks.id` rather than by where the tasks are drawn, because
     * nothing here is drawing them. It is the order that makes two reads of an
     * unchanged game the same list.
     */
    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE game_cells.game_id = :gameId
          AND tasks.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY tasks.id
        """,
    )
    abstract suspend fun workableTasksOfGame(gameId: EntityId): List<TaskEntity>

    /**
     * Every stage of every task of one game, in pipeline order within each task.
     *
     * A join on the game rather than a list of task identifiers, so the query has
     * one shape whatever the game holds. A generated `IN (?, ?, ...)` would grow
     * a new statement for every different number of tasks — nothing SQLite could
     * keep prepared — and would meet its parameter ceiling on a large game.
     */
    @Query(
        """
        SELECT task_stages.* FROM task_stages
        INNER JOIN tasks ON tasks.id = task_stages.task_id
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE game_cells.game_id = :gameId
          AND tasks.deleted_at IS NULL AND games.deleted_at IS NULL
        ORDER BY task_stages.task_id, task_stages.order_index
        """,
    )
    abstract suspend fun stagesOfGame(gameId: EntityId): List<TaskStageEntity>

    /**
     * What each of a game's tasks has had reported against it, in one read.
     *
     * The pair [failureTotalOf] and [resolvedTotalOf] answer for one task, which
     * is two queries per task and the reason the whole-game check does not use
     * them. Summed wide for the same reason they are: PLAN 6.4 lets what has been
     * reported failed climb past what the task needs.
     */
    @Query(
        """
        SELECT progress_events.task_id AS taskId,
               COALESCE(SUM(CASE WHEN progress_events.kind = 'FAILURE_REPORTED' THEN progress_events.quantity ELSE 0 END), 0) AS reported,
               COALESCE(SUM(CASE WHEN progress_events.kind = 'SHORTAGE_RESOLVED' THEN progress_events.quantity ELSE 0 END), 0) AS settled
        FROM progress_events
        INNER JOIN tasks ON tasks.id = progress_events.task_id
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        WHERE game_cells.game_id = :gameId AND tasks.deleted_at IS NULL
        GROUP BY progress_events.task_id
        """,
    )
    abstract suspend fun reportedTotalsOfGame(gameId: EntityId): List<TaskFailureTotals>

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
     *
     * Counted wide. PLAN 6.4 lets this total pass what the task needs — a piece
     * can be spoiled again and again — so unlike the counter it has no ceiling
     * to lean on, and a sum of enough reports would not fit in the width one of
     * them does.
     */
    @Query(
        """
        SELECT COALESCE(SUM(quantity), 0) FROM progress_events
        WHERE task_id = :taskId AND kind = 'FAILURE_REPORTED'
        """,
    )
    abstract suspend fun failureTotalOf(taskId: EntityId): Long

    /** Everything ever reported made good on this task. */
    @Query(
        """
        SELECT COALESCE(SUM(quantity), 0) FROM progress_events
        WHERE task_id = :taskId AND kind = 'SHORTAGE_RESOLVED'
        """,
    )
    abstract suspend fun resolvedTotalOf(taskId: EntityId): Long

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

    /**
     * Marks a game finished or takes the mark back, from inside a transaction.
     *
     * The same row [GameDao.setManuallyCompleted] writes, and written from here
     * because PLAN 16 has the game's own mark and the completions below it land
     * together or not at all. A transaction that could only reach the tasks would
     * have to ask somebody else to write the game afterwards, which is exactly
     * the half-applied game those rules forbid.
     *
     * @return 1 when the game was there and active, 0 otherwise.
     */
    @Query(
        """
        UPDATE games
        SET is_manually_completed = :isCompleted, completed_at = :completedAt, updated_at = :updatedAt
        WHERE id = :gameId AND deleted_at IS NULL
        """,
    )
    protected abstract suspend fun writeGameCompletion(
        gameId: EntityId,
        isCompleted: Boolean,
        completedAt: Instant?,
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
        val plan = completionOf(task)

        if (plan.settles > 0) {
            val settled = insertEventIfNew(settlingEvent(taskId, idGenerator.newId(), plan.settles, moment))
            // A generated name that was already taken is not a retry of anything;
            // it is an identifier collision, and settling silently without the
            // event would leave the counter unexplained.
            check(settled != -1L) { "The settling event for $taskId was given a name that was already taken." }
        }
        writeCompletion(task, plan, stagesOfTask(taskId), moment)
        requireProgressHolds(taskId)
        return true
    }

    /**
     * Finishes a whole game: its work first, then the game's own mark.
     *
     * PLAN 12.9 in one transaction. The user is asked once, about the game, and
     * what they agree to is that everything unfinished in it is finished — 3D
     * tasks, the independent tasks a batch made, a task made in several colours,
     * a card or board pipeline counted all the way up, a special task. Each one
     * is finished by exactly what [completeTask] means by finishing it, because
     * both go through [completionOf] and [writeCompletion] rather than through
     * two accounts of the same rule that could drift apart.
     *
     * Everything is read before anything is written, and the whole game is read
     * in three queries however much is in it: the game, its tasks, and their
     * stages. Finishing a game of forty-two tasks writes forty-two rows, which is
     * the work; it must not also ask forty-two questions to decide to.
     *
     * [expected] is the game as the confirmation was answered against. PLAN 12.9
     * asks about unfinished work and then finishes it, and those are two moments;
     * an answer given about three unfinished tasks is refused rather than applied
     * to five. The check is made before the game's own mark is looked at, so a
     * game somebody else finished in between is reported as the change it was
     * rather than as nothing having happened.
     *
     * A game already finished is a no-op down to the clock: PLAN 5.3 has the mark
     * mean the user's decision and the moment they made it, and rewriting
     * `completedAt` would move a date they set to one they did not.
     *
     * @return true when this call was the one that finished the game.
     * @throws TaskProgressException if the game is gone, or has moved since the
     *   question was answered.
     */
    @Transaction
    open suspend fun completeGame(
        gameId: EntityId,
        clock: Clock,
        idGenerator: IdGenerator,
        expected: GameCompletionSnapshot? = null,
    ): Boolean {
        val game = activeGameById(gameId) ?: refuse(TaskProgressFailure.GAME_NOT_AVAILABLE)
        val tasks = workableTasksOfGame(gameId)
        val stages = stagesOfGame(gameId).groupBy { it.taskId }
        expected?.let { snapshot ->
            if (!snapshot.matches(snapshotOf(game, tasks, stages))) refuse(TaskProgressFailure.STALE_GAME_COMPLETION)
        }
        if (game.isManuallyCompleted) return false

        val unfinished = tasks.filterNot { it.isCompleted }
        val plans = unfinished.map { it to completionOf(it) }
        // Every name a write will need, made before the first of them lands. A
        // generator that runs out on the second identifier would otherwise leave
        // the first settling event written against work that never finished.
        val settlings = plans.filter { (_, plan) -> plan.settles > 0 }.map { (task, plan) -> Triple(task, plan, idGenerator.newId()) }

        val moment = clock.now()
        settlings.forEach { (task, plan, eventId) ->
            val settled = insertEventIfNew(settlingEvent(task.id, eventId, plan.settles, moment))
            check(settled != -1L) { "The settling event for ${task.id} was given a name that was already taken." }
        }
        plans.forEach { (task, plan) -> writeCompletion(task, plan, stages[task.id].orEmpty(), moment) }
        // Last, so that a game is never marked finished over work that did not
        // finish: anything above taking the transaction down takes this with it.
        writeGameCompletion(gameId = gameId, isCompleted = true, completedAt = moment, updatedAt = moment)
        requireGameProgressHolds(gameId)
        return true
    }

    /**
     * The game exactly as a question about finishing it would find it.
     *
     * The same three reads a bulk completion makes, and no more: PLAN 12.9 asks
     * the user about work the transaction is then going to do, so the picture
     * they answer and the picture it writes against have to be the same picture
     * taken the same way.
     *
     * @return null when there is no such game to ask about.
     */
    @Transaction
    open suspend fun gameCompletionSnapshot(gameId: EntityId): GameCompletionSnapshot? {
        val game = activeGameById(gameId) ?: return null
        return snapshotOf(game, workableTasksOfGame(gameId), stagesOfGame(gameId).groupBy { it.taskId })
    }

    /** The game, its tasks and their pipelines as this read found them. */
    private fun snapshotOf(
        game: GameEntity,
        tasks: List<TaskEntity>,
        stages: Map<EntityId, List<TaskStageEntity>>,
    ): GameCompletionSnapshot =
        GameCompletionSnapshot(
            isGameCompleted = game.isManuallyCompleted,
            tasks =
                tasks.map { task ->
                    GameTaskSnapshot(
                        taskId = task.id,
                        isCompleted = task.isCompleted,
                        currentMissingQuantity = task.currentMissingQuantity,
                        requiredQuantity = task.requiredQuantity,
                        // From the one whole-game read the transaction already
                        // makes; never a query of its own per task.
                        stages =
                            stages[task.id].orEmpty().map { row ->
                                GameStageSnapshot(
                                    stage = row.stage,
                                    orderIndex = row.orderIndex,
                                    completedQuantity = row.completedQuantity,
                                )
                            },
                    )
                },
        )

    /** What finishing one task comes to, worked out before anything is written. */
    private data class Completion(
        /** How much it owes, and so how much one settling event has to record. */
        val settles: Int,
        /** What its pipeline is counted up to, or null when it has no total. */
        val stageTarget: Int?,
        /** Whether it comes out of this with its print run recorded. */
        val primaryBatchCompleted: Boolean,
    )

    /**
     * What finishing this task means, for whichever path is finishing it.
     *
     * The one account of it. PLAN 12.9 has finishing a game finish its tasks, and
     * "finish" there is the same word as on a task's own tick — so a second
     * account of what that costs is a second thing to keep in step, and the two
     * would come apart at the first change to either.
     */
    private fun completionOf(task: TaskEntity): Completion =
        Completion(
            settles = task.currentMissingQuantity,
            // With no total there is nothing for a stage to be counted up to; a
            // task like that is finished by hand and its pipeline left alone.
            stageTarget = task.requiredQuantity,
            // Only the 3D pool counts a print run; for the others the flag is
            // not part of what being finished means.
            primaryBatchCompleted = task.primaryBatchCompleted || task.poolType == PoolType.THREE_D,
        )

    /** The event that explains a settled debt, so the history still adds up. */
    private fun settlingEvent(
        taskId: EntityId,
        eventId: EntityId,
        quantity: Int,
        moment: Instant,
    ): ProgressEventEntity =
        ProgressEventEntity(
            id = eventId,
            taskId = taskId,
            kind = ProgressEventKind.SHORTAGE_RESOLVED,
            quantity = quantity,
            recordedAt = moment,
        )

    /** Writes one task's pipeline and then the task, as finishing it means. */
    private suspend fun writeCompletion(
        task: TaskEntity,
        plan: Completion,
        stages: List<TaskStageEntity>,
        moment: Instant,
    ) {
        plan.stageTarget?.let { total ->
            stages.forEach { stage ->
                if (stage.completedQuantity != total) writeStage(task.id, stage.stage, total, moment)
            }
        }
        writeProgress(
            taskId = task.id,
            isCompleted = true,
            completedAt = moment,
            primaryBatchCompleted = plan.primaryBatchCompleted,
            currentMissingQuantity = 0,
            updatedAt = moment,
        )
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
     * PLAN 6.3 goes one further: a report against a task inside a *finished game*
     * reopens the game as well, in this transaction. The game is read here rather
     * than taken from the caller, so a game finished while a form stood open is
     * still reopened — the state the screen was showing is not what the write is
     * decided from. Only the task reported on is reopened; PLAN 6.3 leaves the
     * game's other finished tasks exactly as they are.
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
     *   amount is not a number of pieces, the detail does not belong to it, or
     *   the event name is already spent on a different event.
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
        requireCountableQuantity(quantity)
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
        // Read here rather than at the top, so a retry that has nothing to do
        // does not go looking for a game it is not going to write to.
        val game = gameOfTask(taskId)

        // Added as a Long and only then brought back. PLAN 6.4 caps what a task
        // owes at what it needs, and the failure total is free to go past it —
        // so the event keeps the amount the user really reported while the
        // counter saturates. Adding as Int first would wrap around before the
        // cap ever applied, and the wrapped number is the one that would be
        // capped: a large report would come back as a negative debt.
        val owed = task.currentMissingQuantity.toLong() + quantity.toLong()
        val settled =
            task.requiredQuantity?.let { total -> minOf(owed, total.toLong()) }
                // With no total there is nothing to saturate against, so an
                // amount that will not fit is refused rather than truncated.
                ?: owed.takeIf { it <= Int.MAX_VALUE } ?: refuse(TaskProgressFailure.INVALID_QUANTITY)
        writeProgress(
            taskId = taskId,
            isCompleted = false,
            completedAt = null,
            primaryBatchCompleted = task.primaryBatchCompleted || task.poolType == PoolType.THREE_D,
            currentMissingQuantity = settled.toInt(),
            updatedAt = moment,
        )
        // PLAN 6.3 and PLAN 16: a shortage on a task inside a finished game
        // brings the game back to `Devam Eden` too, and does it here rather than
        // in a second write a caller has to remember — the state where the task
        // is open inside a game still claiming to be finished is exactly what
        // one transaction exists to make unreachable. A game that is already
        // open is left alone: there is nothing to take back, and writing anyway
        // would move a row nobody changed.
        if (game != null && game.isManuallyCompleted) {
            writeGameCompletion(gameId = game.id, isCompleted = false, completedAt = null, updatedAt = moment)
        }
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
     * @throws TaskProgressException if there is no such task, the amount is not
     *   a number of pieces, more was made good than was owed, the detail does
     *   not belong to the task, or the event name is already spent on a
     *   different event.
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
        requireCountableQuantity(quantity)
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
     * The one-step way of asking for [setStageQuantities], and nothing more: the
     * rules live there, so there is a single account of what a pipeline may look
     * like rather than two that could drift.
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
    ): Boolean = setStageQuantities(taskId, mapOf(stage to completedQuantity), clock)

    /**
     * Sets how far several steps of a pipeline have got, all at once.
     *
     * PLAN 7.3 puts the whole pipeline in front of the user at once, so the whole
     * pipeline is what is checked and written. Saving a step at a time would
     * refuse orderings that are perfectly good on the way to a state the user
     * described — raising the print run before the cut has been lowered — and
     * would leave the first steps written when a later one turned out not to fit.
     *
     * Nothing is moved that the user did not move. PLAN 7.2's rule is checked
     * against the state they asked for as a whole; a target that breaks it is
     * refused entirely rather than repaired by pulling the steps after it down,
     * which would throw away counts they never touched.
     *
     * [expected] is the pipeline as it stood when the panel was opened, total
     * and all. When it is given and the database no longer agrees, the save is
     * refused rather than applied: a panel left open while the work moved on
     * would otherwise put back the numbers it was opened with. The total is part
     * of it because a target means nothing without one — `15/10/5` is most of a
     * task of twenty and impossible for a task of twelve — and it is checked
     * before the total is put to any other use, so a task whose total shrank is
     * answered with what actually happened rather than with a complaint about a
     * number the user typed against the old one.
     *
     * Every step reaching the total finishes the task, and a step dropping back
     * below it reopens the task, because PLAN 6.4 does not let the finished mark
     * stand against counters that disagree with it.
     *
     * @param targets what each named step should stand at; steps left out keep
     *   what they have.
     * @param expected the pipeline and total the target was described against,
     *   or null to write against whatever is there.
     * @return true when anything changed.
     * @throws TaskProgressException if the task, the pool, the steps or the
     *   amounts will not have it.
     */
    @Transaction
    open suspend fun setStageQuantities(
        taskId: EntityId,
        targets: Map<ProductionStage, Int>,
        clock: Clock,
        expected: StageSnapshot? = null,
    ): Boolean {
        val task = workableTaskById(taskId) ?: refuse(TaskProgressFailure.TASK_NOT_AVAILABLE)
        if (!task.poolType.hasStages) refuse(TaskProgressFailure.TASK_HAS_NO_STAGES)
        val pipeline = stagesOf(task.poolType)
        targets.keys.forEach { stage ->
            if (stage !in pipeline) refuse(TaskProgressFailure.STAGE_NOT_IN_PIPELINE)
        }

        // Read once, in the order the steps are worked in, and check that what
        // came back really is this pool's pipeline. Everything below counts on
        // position, so a row missing or doubled would make the ordering rule
        // check something other than what it says it checks.
        val stages = stagesOfTask(taskId)
        if (stages.map { it.stage } != pipeline) refuse(TaskProgressFailure.STAGE_PIPELINE_BROKEN)

        // Before the total is read for anything else, so a task whose total moved
        // under an open panel is answered with the move rather than with a
        // complaint about a number that was perfectly good when it was typed.
        expected?.let { snapshot ->
            if (snapshot.requiredQuantity != task.requiredQuantity) {
                refuse(TaskProgressFailure.STALE_STAGE_PROGRESS)
            }
            val standing = stages.associate { it.stage to it.completedQuantity }
            if (snapshot.stages != standing) refuse(TaskProgressFailure.STALE_STAGE_PROGRESS)
        }

        val total = task.requiredQuantity ?: refuse(TaskProgressFailure.REQUIRED_QUANTITY_UNKNOWN)

        // What each amount is on its own, before any of them is put into a row:
        // a stage row will not hold less than nothing, so building one first
        // would answer a negative amount with a programming error rather than
        // with something the screen can say.
        targets.values.forEach { amount ->
            if (amount < 0) refuse(TaskProgressFailure.INVALID_QUANTITY)
            if (amount > total) refuse(TaskProgressFailure.STAGE_QUANTITY_EXCEEDS_REQUIRED)
        }

        // The whole pipeline as the user asked for it, built before anything is
        // written so the rule is read off one picture rather than off a sequence
        // of half-applied ones.
        val wanted =
            stages.map { row -> targets[row.stage]?.let { row.copy(completedQuantity = it) } ?: row }
        wanted.zipWithNext { earlier, later ->
            if (later.completedQuantity > earlier.completedQuantity) {
                refuse(TaskProgressFailure.STAGE_ORDER_VIOLATED)
            }
        }

        val changed = wanted.filterIndexed { index, row -> row.completedQuantity != stages[index].completedQuantity }
        if (changed.isEmpty()) return false

        val moment = clock.now()
        changed.forEach { row -> writeStage(taskId, row.stage, row.completedQuantity, moment) }
        // A pipeline counted all the way up still does not finish a task that
        // owes a reprint: PLAN 6.4 will not have the finished mark stand against
        // a counter saying work is left.
        val finished = task.currentMissingQuantity == 0 && readyToFinish(task, wanted)
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
     * Refuses an amount that is not a number of pieces.
     *
     * A typed outcome rather than a thrown argument error: the amount comes from
     * a box the user typed in, so what is wrong with it is something a screen
     * has to be able to say. PLAN 5.12 rules out nothing and less than nothing;
     * the upper bound is here because a task's debt is an `Int` and an amount
     * that could not be added to it without wrapping is not the amount typed.
     */
    private fun requireCountableQuantity(quantity: Int) {
        if (quantity <= 0) refuse(TaskProgressFailure.INVALID_QUANTITY)
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
        requireTaskHolds(
            task = task,
            stages = stagesOfTask(taskId),
            outstanding = failureTotalOf(taskId) - resolvedTotalOf(taskId),
        )
    }

    /**
     * The same check over a whole game, in a fixed number of reads.
     *
     * What [requireProgressHolds] asks of one task, asked of every task a bulk
     * completion touched — and asked the same way, because both hand their rows
     * to [requireTaskHolds]. Doing it task by task would put four reads behind
     * every row of a game, which is the cost this transaction exists to avoid;
     * doing it with a second set of rules would be a second thing to keep true.
     *
     * The game's own mark is checked too. PLAN 12.9 finishes the work and then
     * the game, so a game left marked finished over work that is not is the one
     * outcome this transaction must not be able to commit.
     */
    private suspend fun requireGameProgressHolds(gameId: EntityId) {
        val game = checkNotNull(activeGameById(gameId)) { "The game $gameId disappeared while it was being finished." }
        val tasks = workableTasksOfGame(gameId)
        val stages = stagesOfGame(gameId).groupBy { it.taskId }
        val reported = reportedTotalsOfGame(gameId).associateBy { it.taskId }
        tasks.forEach { task ->
            requireTaskHolds(
                task = task,
                stages = stages[task.id].orEmpty(),
                outstanding = reported[task.id]?.let { it.reported - it.settled } ?: 0L,
            )
        }
        check(game.isManuallyCompleted == (game.completedAt != null)) {
            "The game $gameId is ${game.isManuallyCompleted} but finished at ${game.completedAt}."
        }
        check(!game.isManuallyCompleted || tasks.all { it.isCompleted }) {
            "The game $gameId is finished with ${tasks.count { !it.isCompleted }} of its tasks unfinished."
        }
    }

    /**
     * Everything one task has to be able to say about itself, checked at once.
     *
     * Handed its rows rather than reading them, so the caller decides whether
     * they came from one task's own queries or from a game-wide read. The rules
     * are here and only here.
     */
    private fun requireTaskHolds(
        task: TaskEntity,
        stages: List<TaskStageEntity>,
        outstanding: Long,
    ) {
        val taskId = task.id
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
        check(task.currentMissingQuantity <= outstanding) {
            "The task $taskId owes ${task.currentMissingQuantity} but its history accounts for $outstanding."
        }
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

/**
 * What one task's history adds up to, read for a whole game at once.
 *
 * Both sums are wide, for the reason [TaskProgressDao.failureTotalOf] gives:
 * PLAN 6.4 lets what has been reported failed climb past what the task needs, so
 * a long enough history would not fit the width one report does.
 */
data class TaskFailureTotals(
    val taskId: EntityId,
    val reported: Long,
    val settled: Long,
)
