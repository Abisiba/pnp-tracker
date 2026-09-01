package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.TaskProgressDao
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.tasks.TaskProgressException
import dev.pnptracker.domain.tasks.TaskProgressFailure
import kotlin.time.Clock

/** Working a task that has already been written down: finishing it, and what it owes. */
interface TaskProgressing {
    /**
     * Marks a task finished.
     *
     * PLAN 6.4 will not have the finished mark contradict the counters, so an
     * outstanding shortage is settled through the history in the same
     * transaction. [eventId] names that settling event: handing the same one
     * back after an uncertain failure is a retry rather than a second settling.
     *
     * @return true when this call was the one that finished it.
     */
    suspend fun completeTask(
        taskId: EntityId,
        eventId: EntityId,
    ): TaskProgressOutcome

    /**
     * Takes the finished mark back, returning the task to its active pool.
     *
     * @return true when this call was the one that reopened it.
     */
    suspend fun reopenTask(taskId: EntityId): TaskProgressOutcome

    /**
     * Records pieces that came out missing or spoiled (PLAN 6.3).
     *
     * One action for both, because PLAN 6.3 has one: what was spoiled has to be
     * made again, so it is owed exactly as what never arrived is.
     *
     * @param eventId the name of this report, chosen before it is sent, so a
     *   repeat of the same intent stays one report.
     */
    suspend fun reportFailure(
        eventId: EntityId,
        taskId: EntityId,
        quantity: Int,
        note: String? = null,
        cardReference: String? = null,
        stage: ProductionStage? = null,
    ): TaskProgressOutcome

    /**
     * Records that some of what was owed has been made again (PLAN 6.3).
     *
     * The failure total is untouched: making good is a new event, never the
     * removal of an old one.
     */
    suspend fun resolveShortage(
        eventId: EntityId,
        taskId: EntityId,
        quantity: Int,
        note: String? = null,
        cardReference: String? = null,
    ): TaskProgressOutcome

    /**
     * Sets how far the steps of a card or board pipeline have got (PLAN 7.2, 8).
     *
     * The whole pipeline at once, because PLAN 7.3 puts the whole pipeline in
     * front of the user at once: a target that breaks the ordering rule is
     * refused entirely rather than reached through states that break it.
     *
     * @param expectedStages the counts the panel was opened on. A save is
     *   refused when the database no longer agrees, so a panel left open while
     *   the work moved on cannot put back what it was opened with.
     */
    suspend fun setStageQuantities(
        taskId: EntityId,
        targets: Map<ProductionStage, Int>,
        expectedStages: Map<ProductionStage, Int>? = null,
    ): TaskProgressOutcome
}

/**
 * What came of asking to change a task's progress.
 *
 * A result rather than an exception for the refusals, because every one of them
 * is something the screen that asked has to say out loud. Only a broken
 * invariant still travels as a throw, since there is nothing to tell the user
 * about it that would be true.
 */
sealed interface TaskProgressOutcome {
    /** It happened. */
    data object Done : TaskProgressOutcome

    /**
     * Nothing was left to do.
     *
     * A finished task asked to finish, a retry of a report already recorded. Not
     * a failure: what the user wanted is already true, and saying otherwise
     * would turn a double click into an error message.
     */
    data object AlreadySo : TaskProgressOutcome

    /** It was refused, for a reason worth showing. */
    data class Refused(
        val failure: TaskProgressFailure,
    ) : TaskProgressOutcome
}

/**
 * The user finishing a piece of work, or saying what went wrong with it.
 *
 * Thin over the transaction, like the other stores: the rules about what a task
 * may look like afterwards live in the one transaction that enforces them. What
 * is added here is the clock, and turning a refusal into something a screen can
 * show without a caller having to catch anything.
 *
 * The event identity is *not* added here. It comes in from the caller, because
 * the whole point of naming an event before sending it is that the name outlives
 * a failed attempt: a store that generated one per call would make every retry a
 * new report. [IdGenerator] is kept only for the settling event a completion may
 * have to write, which no form asks the user about.
 */
class TaskProgressStore(
    private val taskProgressDao: TaskProgressDao,
    private val clock: Clock = Clock.System,
) : TaskProgressing {
    override suspend fun completeTask(
        taskId: EntityId,
        eventId: EntityId,
    ): TaskProgressOutcome =
        outcomeOf {
            // The settling event is the only identity a completion needs, and it
            // is named by the caller for the same reason a report is: a retry
            // after an uncertain failure has to be the same event, not a second
            // one. The generator is asked at most once, so handing it a constant
            // is exact rather than merely convenient.
            taskProgressDao.completeTask(taskId = taskId, clock = clock, idGenerator = IdGenerator { eventId })
        }

    override suspend fun reopenTask(taskId: EntityId): TaskProgressOutcome =
        outcomeOf { taskProgressDao.reopenTask(taskId = taskId, clock = clock) }

    override suspend fun reportFailure(
        eventId: EntityId,
        taskId: EntityId,
        quantity: Int,
        note: String?,
        cardReference: String?,
        stage: ProductionStage?,
    ): TaskProgressOutcome =
        outcomeOf {
            taskProgressDao.reportFailure(
                eventId = eventId,
                taskId = taskId,
                quantity = quantity,
                clock = clock,
                note = note,
                cardReference = cardReference,
                stage = stage,
            )
        }

    override suspend fun resolveShortage(
        eventId: EntityId,
        taskId: EntityId,
        quantity: Int,
        note: String?,
        cardReference: String?,
    ): TaskProgressOutcome =
        outcomeOf {
            taskProgressDao.resolveShortage(
                eventId = eventId,
                taskId = taskId,
                quantity = quantity,
                clock = clock,
                note = note,
                cardReference = cardReference,
            )
        }

    override suspend fun setStageQuantities(
        taskId: EntityId,
        targets: Map<ProductionStage, Int>,
        expectedStages: Map<ProductionStage, Int>?,
    ): TaskProgressOutcome =
        outcomeOf {
            taskProgressDao.setStageQuantities(
                taskId = taskId,
                targets = targets,
                clock = clock,
                expectedStages = expectedStages,
            )
        }

    /**
     * Runs one progress change and says what came of it.
     *
     * A storage refusal becomes [TaskProgressFailure.TASK_NOT_AVAILABLE] rather
     * than a message of its own: from the user's side the difference between a
     * task that has gone and a row the database would not write is nothing they
     * can act on, and the SQL that explains it is not theirs to read.
     */
    private inline fun outcomeOf(block: () -> Boolean): TaskProgressOutcome =
        try {
            if (block()) TaskProgressOutcome.Done else TaskProgressOutcome.AlreadySo
        } catch (refusal: TaskProgressException) {
            TaskProgressOutcome.Refused(refusal.failure)
        } catch (cause: SQLiteException) {
            TaskProgressOutcome.Refused(TaskProgressFailure.TASK_NOT_AVAILABLE)
        }
}
