package dev.pnptracker.data.database

import androidx.room3.useWriterConnection
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.ProgressEventKind
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.StageSnapshot
import dev.pnptracker.domain.tasks.TaskProgressException
import dev.pnptracker.domain.tasks.TaskProgressFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Working a task: finishing it, reopening it, and the two counters PLAN 6 keeps.
 *
 * The thing most of these are really about is the rule in PLAN 6.4 that the
 * finished mark may never contradict the counters. Every transaction below reads
 * its own work back before it commits, so a test that gets an answer at all has
 * already been told the task adds up; what the assertions add is *which* answer.
 */
class TaskProgressTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    /** Hands out fixed times, and says how often it was asked. */
    private class CountingClock(
        private val start: Instant,
    ) : Clock {
        var reads: Int = 0
            private set

        override fun now(): Instant {
            reads++
            return start.plus(kotlin.time.Duration.parse("${reads}s"))
        }
    }

    private lateinit var clock: CountingClock

    private val ids = IdGenerator.Random

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        clock = CountingClock(createdAt)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val progress get() = database.taskProgressDao()

    /** A task of one pool, written into a cell of the matching column. */
    private suspend fun aTaskIn(
        poolType: PoolType,
        requiredQuantity: Int? = 40,
        name: String = "Gri token",
    ): EntityId {
        val cell =
            insertGameAndCell(
                database,
                columnType =
                    dev.pnptracker.domain.model.CellColumnType
                        .of(poolType),
                gameName = "Harmonies ${ids.newId()}",
            )
        val task =
            aTask(
                name = name,
                poolType = poolType,
                trackingMode =
                    when (poolType) {
                        PoolType.THREE_D -> TrackingMode.THREE_D_BATCH
                        PoolType.CARD, PoolType.BOARD -> TrackingMode.PIPELINE
                        PoolType.SPECIAL -> TrackingMode.COUNTED
                    },
                requiredQuantity = requiredQuantity,
            )
        database.taskDao().addTaskToCell(task, cell.id, ids.newId(), createdAt)
        return task.id
    }

    private suspend fun taskOf(taskId: EntityId) = assertNotNull(progress.taskById(taskId))

    // -------------------------------------------- amounts that are not amounts

    @Test
    fun `an amount too large to add is refused rather than wrapped around`() =
        runBlocking<Unit> {
            // Without a total there is nothing to saturate against, so an amount
            // that would not fit in what a task can owe is refused. Added as an
            // `Int` this used to wrap round to a negative debt, and the negative
            // number was the one the cap was then applied to.
            val taskId = aTaskIn(PoolType.THREE_D, requiredQuantity = null)
            progress.reportFailure(ids.newId(), taskId, 10, clock)

            assertEquals(
                TaskProgressFailure.INVALID_QUANTITY,
                assertFailsWith<TaskProgressException> {
                    progress.reportFailure(ids.newId(), taskId, Int.MAX_VALUE, clock)
                }.failure,
            )

            // Nothing of the refused attempt survives: not the counter, not the
            // event, not the failure total it would have joined.
            assertEquals(10, taskOf(taskId).currentMissingQuantity)
            assertEquals(1, progress.progressEventsOfTask(taskId).size)
            assertEquals(10L, progress.failureTotalOf(taskId))
        }

    @Test
    fun `a huge amount on a task with a total is kept whole and the counter stops at the total`() =
        runBlocking<Unit> {
            // PLAN 6.4: what is owed cannot pass what the task needs, while the
            // failure total is free to go past it — a piece can be spoiled more
            // than once. So the event keeps what was really reported.
            val taskId = aTaskIn(PoolType.THREE_D, requiredQuantity = 40)

            assertTrue(progress.reportFailure(ids.newId(), taskId, Int.MAX_VALUE, clock))

            assertEquals(40, taskOf(taskId).currentMissingQuantity, "the counter passed the total")
            assertEquals(Int.MAX_VALUE, progress.progressEventsOfTask(taskId).single().quantity)
            assertEquals(Int.MAX_VALUE.toLong(), progress.failureTotalOf(taskId))
        }

    @Test
    fun `two large reports on one task do not overflow the counter between them`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D, requiredQuantity = 40)
            progress.reportFailure(ids.newId(), taskId, Int.MAX_VALUE, clock)

            assertTrue(progress.reportFailure(ids.newId(), taskId, Int.MAX_VALUE, clock))

            assertEquals(40, taskOf(taskId).currentMissingQuantity)
            assertEquals(2, progress.progressEventsOfTask(taskId).size)
        }

    @Test
    fun `an amount with no total behind it is taken as far as it will really go`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D, requiredQuantity = null)

            assertTrue(progress.reportFailure(ids.newId(), taskId, Int.MAX_VALUE, clock))

            assertEquals(Int.MAX_VALUE, taskOf(taskId).currentMissingQuantity)
        }

    @Test
    fun `a shortage reported at a step leaves the pipeline exactly where it was`() =
        runBlocking<Unit> {
            // PLAN 7.3 gives moving a counter to the badge, which is a later
            // slice. Naming a step here says where the pieces were noticed and
            // changes nothing about how far the work has got.
            val cardId = aTaskIn(PoolType.CARD, requiredQuantity = 20)
            progress.setStageQuantity(cardId, ProductionStage.PRINT, 20, clock)
            val before = progress.stagesOfTask(cardId).map { it.stage to it.completedQuantity }

            assertTrue(
                progress.reportFailure(
                    ids.newId(),
                    cardId,
                    3,
                    clock,
                    cardReference = "Bird 12",
                    stage = ProductionStage.LAMINATE,
                ),
            )

            assertEquals(before, progress.stagesOfTask(cardId).map { it.stage to it.completedQuantity })
            assertEquals(3, taskOf(cardId).currentMissingQuantity)
            val event = progress.progressEventsOfTask(cardId).single()
            assertEquals(ProductionStage.LAMINATE, event.stage)
            assertEquals("Bird 12", event.cardReference)
        }

    @Test
    fun `a board shortage may name its own middle step and not the other pipeline's`() =
        runBlocking<Unit> {
            val boardId = aTaskIn(PoolType.BOARD, requiredQuantity = 12)

            assertTrue(progress.reportFailure(ids.newId(), boardId, 2, clock, stage = ProductionStage.GLUE))
            assertEquals(
                TaskProgressFailure.STAGE_NOT_IN_PIPELINE,
                assertFailsWith<TaskProgressException> {
                    progress.reportFailure(ids.newId(), boardId, 1, clock, stage = ProductionStage.LAMINATE)
                }.failure,
            )

            assertEquals(2, taskOf(boardId).currentMissingQuantity, "the refused report still moved the counter")
            assertEquals(listOf(0, 0, 0), progress.stagesOfTask(boardId).map { it.completedQuantity })
        }

    @Test
    fun `making good on a board task leaves its steps alone too`() =
        runBlocking<Unit> {
            val boardId = aTaskIn(PoolType.BOARD, requiredQuantity = 12)
            progress.setStageQuantity(boardId, ProductionStage.PRINT, 12, clock)
            progress.reportFailure(ids.newId(), boardId, 4, clock)

            assertTrue(progress.resolveShortage(ids.newId(), boardId, 4, clock))

            assertEquals(0, taskOf(boardId).currentMissingQuantity)
            assertEquals(listOf(12, 0, 0), progress.stagesOfTask(boardId).map { it.completedQuantity })
            // Owing nothing is not the same as having done the work: PLAN 7.2
            // finishes a card or board task when every step reaches the total.
            assertFalse(taskOf(boardId).isCompleted)
        }

    // ------------------------------------------------- finishing and reopening

    @Test
    fun `a new task is unfinished and has no time it was finished at`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)

            val task = taskOf(taskId)
            assertFalse(task.isCompleted)
            assertNull(task.completedAt)
            assertFalse(task.primaryBatchCompleted)
            assertEquals(0, task.currentMissingQuantity)
        }

    @Test
    fun `finishing a task writes the mark and the time together`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            val before = taskOf(taskId)

            assertTrue(progress.completeTask(taskId, clock, ids))

            val task = taskOf(taskId)
            assertTrue(task.isCompleted)
            assertEquals(assertNotNull(task.completedAt), task.updatedAt, "the two moments came from separate reads")
            assertEquals(before.createdAt, task.createdAt, "finishing moved the creation time")
        }

    @Test
    fun `finishing a task twice changes nothing and does not even read the clock`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            assertTrue(progress.completeTask(taskId, clock, ids))
            val afterFirst = taskOf(taskId)
            val readsAfterFirst = clock.reads

            assertFalse(progress.completeTask(taskId, clock, ids), "the second call claimed to have done something")

            assertEquals(afterFirst, taskOf(taskId))
            assertEquals(readsAfterFirst, clock.reads, "a no-op read the clock")
        }

    @Test
    fun `reopening a task clears the mark and the time`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.completeTask(taskId, clock, ids)

            assertTrue(progress.reopenTask(taskId, clock))

            val task = taskOf(taskId)
            assertFalse(task.isCompleted)
            assertNull(task.completedAt)
        }

    @Test
    fun `reopening a task that was never finished changes nothing`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            val before = taskOf(taskId)
            val readsBefore = clock.reads

            assertFalse(progress.reopenTask(taskId, clock))

            assertEquals(before, taskOf(taskId))
            assertEquals(readsBefore, clock.reads, "a no-op read the clock")
        }

    @Test
    fun `a deleted task cannot be worked on`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            database.taskDao().softDelete(taskId, deletedAt)

            val attempts =
                listOf<suspend () -> Unit>(
                    { progress.completeTask(taskId, clock, ids) },
                    { progress.reopenTask(taskId, clock) },
                    { progress.completePrimaryBatch(taskId, clock) },
                    { progress.reportFailure(ids.newId(), taskId, 2, clock) },
                    { progress.resolveShortage(ids.newId(), taskId, 1, clock) },
                )
            attempts.forEach { attempt ->
                val refusal = assertFailsWith<TaskProgressException> { attempt() }
                assertEquals(TaskProgressFailure.TASK_NOT_AVAILABLE, refusal.failure)
            }
            assertEquals(0, CommittedSchema.countRowsOf(directory.databaseFile, "progress_events"))
        }

    @Test
    fun `a task whose game was deleted cannot be worked on either`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            val cellId = assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(taskId)).cellId
            val gameId = assertNotNull(database.gameCellDao().cellById(cellId)).gameId
            database.gameDao().softDelete(gameId, deletedAt)

            val refusal = assertFailsWith<TaskProgressException> { progress.completeTask(taskId, clock, ids) }
            assertEquals(TaskProgressFailure.TASK_NOT_AVAILABLE, refusal.failure)
        }

    @Test
    fun `a task that was never there is refused by name`() =
        runBlocking<Unit> {
            val refusal = assertFailsWith<TaskProgressException> { progress.completeTask(ids.newId(), clock, ids) }
            assertEquals(TaskProgressFailure.TASK_NOT_AVAILABLE, refusal.failure)
        }

    // --------------------------------------------------------- the active pool

    @Test
    fun `a finished task leaves the active pool but stays in its cell`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            assertEquals(listOf(taskId), database.taskDao().activeUnfinishedTasksInPool(PoolType.THREE_D).map { it.id })

            progress.completeTask(taskId, clock, ids)

            assertEquals(emptyList(), database.taskDao().activeUnfinishedTasksInPool(PoolType.THREE_D))
            assertEquals(listOf(taskId), database.taskDao().completedTasksInPool(PoolType.THREE_D).map { it.id })
            // PLAN 5.6: the segment stays where it is, ticked and struck through.
            assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(taskId))
            val cellId = assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(taskId)).cellId
            assertEquals(listOf(taskId), database.taskDao().tasksOfCellIncludingCompleted(cellId).map { it.id })
        }

    @Test
    fun `a reopened task comes back to the active pool`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.completeTask(taskId, clock, ids)

            progress.reopenTask(taskId, clock)

            assertEquals(listOf(taskId), database.taskDao().activeUnfinishedTasksInPool(PoolType.THREE_D).map { it.id })
            assertEquals(emptyList(), database.taskDao().completedTasksInPool(PoolType.THREE_D))
        }

    @Test
    fun `a finished task is no longer waiting for a colour`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            assertEquals(listOf(taskId), database.taskDao().activeTasksAwaitingAColor(PoolType.THREE_D).map { it.id })

            progress.completeTask(taskId, clock, ids)

            assertEquals(emptyList(), database.taskDao().activeTasksAwaitingAColor(PoolType.THREE_D))
        }

    @Test
    fun `deleted work stays out of every active and finished view`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.completeTask(taskId, clock, ids)
            database.taskDao().softDelete(taskId, deletedAt)

            assertEquals(emptyList(), database.taskDao().activeUnfinishedTasksInPool(PoolType.THREE_D))
            assertEquals(emptyList(), database.taskDao().completedTasksInPool(PoolType.THREE_D))
            assertEquals(emptyList(), database.taskDao().tasksOfPoolIncludingCompleted(PoolType.THREE_D))
            // But the row and its history are still on disk, which is the point
            // of deleting softly.
            assertNotNull(database.taskDao().taskByIdIncludingDeleted(taskId))
        }

    // ------------------------------------------------------- the 3D counters

    @Test
    fun `completing the print run with nothing owed finishes the task`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)

            assertTrue(progress.completePrimaryBatch(taskId, clock))

            val task = taskOf(taskId)
            assertTrue(task.primaryBatchCompleted)
            assertTrue(task.isCompleted)
            assertNotNull(task.completedAt)
        }

    @Test
    fun `reporting a shortage adds to what is owed and to the failure total`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.completePrimaryBatch(taskId, clock)

            assertTrue(progress.reportFailure(ids.newId(), taskId, 3, clock))

            val task = taskOf(taskId)
            assertEquals(3, task.currentMissingQuantity)
            assertEquals(3L, progress.failureTotalOf(taskId))
            assertFalse(task.isCompleted, "a task that owes three pieces was left finished")
        }

    @Test
    fun `reporting a shortage takes it that a print run was made`() =
        runBlocking<Unit> {
            // PLAN 6.3: nothing can come out short of a run that never happened.
            val taskId = aTaskIn(PoolType.THREE_D)
            assertFalse(taskOf(taskId).primaryBatchCompleted)

            progress.reportFailure(ids.newId(), taskId, 2, clock)

            assertTrue(taskOf(taskId).primaryBatchCompleted)
        }

    @Test
    fun `making a shortage good brings the task to finished, and the history stays`() =
        runBlocking<Unit> {
            // PLAN scenario 6, end to end: 40 tokens, three come out spoiled,
            // three are made again.
            val taskId = aTaskIn(PoolType.THREE_D, requiredQuantity = 40)
            progress.completePrimaryBatch(taskId, clock)
            progress.reportFailure(ids.newId(), taskId, 3, clock)

            assertTrue(progress.resolveShortage(ids.newId(), taskId, 3, clock))

            val task = taskOf(taskId)
            assertEquals(0, task.currentMissingQuantity)
            assertTrue(task.isCompleted)
            assertEquals(3L, progress.failureTotalOf(taskId), "making good erased the history of having failed")
            assertEquals(
                listOf(ProgressEventKind.FAILURE_REPORTED, ProgressEventKind.SHORTAGE_RESOLVED),
                progress.progressEventsOfTask(taskId).map { it.kind },
            )
        }

    @Test
    fun `making good part of a shortage leaves the rest owed and the task unfinished`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.completePrimaryBatch(taskId, clock)
            progress.reportFailure(ids.newId(), taskId, 5, clock)

            progress.resolveShortage(ids.newId(), taskId, 2, clock)

            val task = taskOf(taskId)
            assertEquals(3, task.currentMissingQuantity)
            assertFalse(task.isCompleted)
            assertEquals(5L, progress.failureTotalOf(taskId))
        }

    @Test
    fun `more cannot be made good than was owed`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.reportFailure(ids.newId(), taskId, 2, clock)

            val refusal =
                assertFailsWith<TaskProgressException> { progress.resolveShortage(ids.newId(), taskId, 3, clock) }

            assertEquals(TaskProgressFailure.MORE_RESOLVED_THAN_OUTSTANDING, refusal.failure)
            assertEquals(2, taskOf(taskId).currentMissingQuantity, "a refused resolution moved the counter")
            assertEquals(1, progress.progressEventsOfTask(taskId).size, "a refused resolution was recorded")
        }

    @Test
    fun `a shortage has to be about at least one piece`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)

            // Refused as an outcome and not as an argument error: the amount
            // comes from a box the user typed in, so what is wrong with it is
            // something a screen has to be able to say.
            listOf(0, -1, -40, Int.MIN_VALUE).forEach { quantity ->
                assertEquals(
                    TaskProgressFailure.INVALID_QUANTITY,
                    assertFailsWith<TaskProgressException> {
                        progress.reportFailure(ids.newId(), taskId, quantity, clock)
                    }.failure,
                )
                assertEquals(
                    TaskProgressFailure.INVALID_QUANTITY,
                    assertFailsWith<TaskProgressException> {
                        progress.resolveShortage(ids.newId(), taskId, quantity, clock)
                    }.failure,
                )
            }
            assertEquals(0, taskOf(taskId).currentMissingQuantity)
            assertEquals(emptyList(), progress.progressEventsOfTask(taskId))
        }

    @Test
    fun `the same event handed in twice is only counted once`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            val eventId = ids.newId()
            assertTrue(progress.reportFailure(eventId, taskId, 3, clock))

            assertFalse(progress.reportFailure(eventId, taskId, 3, clock), "the retry claimed to have recorded")

            assertEquals(3, taskOf(taskId).currentMissingQuantity)
            assertEquals(3L, progress.failureTotalOf(taskId))
            assertEquals(1, progress.progressEventsOfTask(taskId).size)
        }

    @Test
    fun `what is owed never rises above what the task needs in total`() =
        runBlocking<Unit> {
            // PLAN 6.4: the outstanding amount is capped by the total, while the
            // failure total is free to go past it — a piece can be spoiled twice.
            val taskId = aTaskIn(PoolType.THREE_D, requiredQuantity = 5)
            progress.reportFailure(ids.newId(), taskId, 4, clock)

            progress.reportFailure(ids.newId(), taskId, 4, clock)

            assertEquals(5, taskOf(taskId).currentMissingQuantity)
            assertEquals(8L, progress.failureTotalOf(taskId))
        }

    @Test
    fun `a shortage on a finished task brings it back into the active pool`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.completeTask(taskId, clock, ids)
            assertEquals(emptyList(), database.taskDao().activeUnfinishedTasksInPool(PoolType.THREE_D))

            progress.reportFailure(ids.newId(), taskId, 2, clock)

            val task = taskOf(taskId)
            assertFalse(task.isCompleted)
            assertNull(task.completedAt)
            assertEquals(2, task.currentMissingQuantity)
            assertEquals(listOf(taskId), database.taskDao().activeUnfinishedTasksInPool(PoolType.THREE_D).map { it.id })
        }

    @Test
    fun `finishing a task that still owes something settles it through the history`() =
        runBlocking<Unit> {
            // PLAN 6.4 will not have a finished task standing next to a counter
            // that says work is left, and PLAN 12.9 has finishing settle the work
            // rather than refuse. So the remainder is made good, and recorded.
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.reportFailure(ids.newId(), taskId, 3, clock)

            progress.completeTask(taskId, clock, ids)

            val task = taskOf(taskId)
            assertTrue(task.isCompleted)
            assertEquals(0, task.currentMissingQuantity)
            assertEquals(3L, progress.failureTotalOf(taskId), "settling erased the history")
            assertEquals(3L, progress.resolvedTotalOf(taskId))
            assertEquals(
                listOf(ProgressEventKind.FAILURE_REPORTED, ProgressEventKind.SHORTAGE_RESOLVED),
                progress.progressEventsOfTask(taskId).map { it.kind },
            )
        }

    @Test
    fun `the history survives the task being deleted`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.reportFailure(ids.newId(), taskId, 3, clock, note = "Kırık çıktı")

            database.taskDao().softDelete(taskId, deletedAt)

            assertEquals(1, progress.progressEventsOfTask(taskId).size)
            assertEquals(3L, progress.failureTotalOf(taskId))
            assertEquals("Kırık çıktı", progress.progressEventsOfTask(taskId).single().note)
        }

    @Test
    fun `a note and a card reference are kept exactly as they were given`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")

            progress.reportFailure(
                eventId = ids.newId(),
                taskId = taskId,
                quantity = 1,
                clock = clock,
                note = "Köşesi ezilmiş",
                cardReference = "Bird #142",
                stage = ProductionStage.CUT,
            )
            progress.reportFailure(ids.newId(), taskId, 2, clock)

            val events = progress.progressEventsOfTask(taskId)
            assertEquals(2, events.size)
            val detailed = events.first()
            assertEquals("Köşesi ezilmiş", detailed.note)
            assertEquals("Bird #142", detailed.cardReference)
            assertEquals(ProductionStage.CUT, detailed.stage)
            // The second was recorded as a bare number and stays one.
            assertNull(events.last().cardReference)

            // Only the shortages that name a card show up in the card history.
            assertEquals(listOf("Bird #142"), progress.cardShortageHistoryOf(taskId).map { it.cardReference })
        }

    @Test
    fun `the card shortage history keeps a record after it has been made good`() =
        runBlocking<Unit> {
            // The query is the history and says so. Nothing stored today ties a
            // resolution to the particular card it made good, so hiding a record
            // once the task owes nothing would be claiming to know which one was
            // settled. PLAN 7.4 leaves that model to a later step.
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")
            progress.reportFailure(ids.newId(), taskId, 1, clock, cardReference = "Bird #142")
            assertEquals(1, progress.cardShortageHistoryOf(taskId).size)

            progress.resolveShortage(ids.newId(), taskId, 1, clock)

            assertEquals(0, taskOf(taskId).currentMissingQuantity)
            assertEquals(
                listOf("Bird #142"),
                progress.cardShortageHistoryOf(taskId).map { it.cardReference },
                "the record of which card came out short was dropped once the number was settled",
            )
        }

    // --------------------------------------------------------------- pipelines

    @Test
    fun `a card task is created with its three stages, in order`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")

            assertEquals(
                listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT),
                progress.stagesOfTask(taskId).map { it.stage },
            )
            assertEquals(listOf(0, 1, 2), progress.stagesOfTask(taskId).map { it.orderIndex })
            assertEquals(listOf(0, 0, 0), progress.stagesOfTask(taskId).map { it.completedQuantity })
        }

    @Test
    fun `a board task is created with print, glue and cut`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.BOARD, requiredQuantity = 16, name = "Plaj tile")

            assertEquals(
                listOf(ProductionStage.PRINT, ProductionStage.GLUE, ProductionStage.CUT),
                progress.stagesOfTask(taskId).map { it.stage },
            )
        }

    @Test
    fun `3D and special tasks are created with no stages at all`() =
        runBlocking<Unit> {
            val threeD = aTaskIn(PoolType.THREE_D)
            val special = aTaskIn(PoolType.SPECIAL, requiredQuantity = 8, name = "Özel zar")

            assertEquals(emptyList(), progress.stagesOfTask(threeD))
            assertEquals(emptyList(), progress.stagesOfTask(special))
        }

    @Test
    fun `a pool with no pipeline refuses a stage rather than inventing one`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantity(taskId, ProductionStage.PRINT, 1, clock)
                }

            assertEquals(TaskProgressFailure.TASK_HAS_NO_STAGES, refusal.failure)
        }

    @Test
    fun `a card task refuses the stage that belongs to the other pipeline`() =
        runBlocking<Unit> {
            val cardTask = aTaskIn(PoolType.CARD, requiredQuantity = 10, name = "Bird Cards")
            val boardTask = aTaskIn(PoolType.BOARD, requiredQuantity = 10, name = "Plaj tile")

            assertEquals(
                TaskProgressFailure.STAGE_NOT_IN_PIPELINE,
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantity(cardTask, ProductionStage.GLUE, 1, clock)
                }.failure,
            )
            assertEquals(
                TaskProgressFailure.STAGE_NOT_IN_PIPELINE,
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantity(boardTask, ProductionStage.LAMINATE, 1, clock)
                }.failure,
            )
        }

    @Test
    fun `a stage cannot pass the one before it`() =
        runBlocking<Unit> {
            // PLAN 7.2: 0 <= cut <= laminated <= printed <= total.
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")
            progress.setStageQuantity(taskId, ProductionStage.PRINT, 170, clock)
            progress.setStageQuantity(taskId, ProductionStage.LAMINATE, 167, clock)

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantity(taskId, ProductionStage.CUT, 168, clock)
                }

            assertEquals(TaskProgressFailure.STAGE_ORDER_VIOLATED, refusal.failure)
            assertEquals(listOf(170, 167, 0), progress.stagesOfTask(taskId).map { it.completedQuantity })
        }

    @Test
    fun `a stage cannot be pulled back below the one after it`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")
            progress.setStageQuantity(taskId, ProductionStage.PRINT, 170, clock)
            progress.setStageQuantity(taskId, ProductionStage.LAMINATE, 167, clock)
            progress.setStageQuantity(taskId, ProductionStage.CUT, 150, clock)

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantity(taskId, ProductionStage.LAMINATE, 140, clock)
                }

            assertEquals(TaskProgressFailure.STAGE_ORDER_VIOLATED, refusal.failure)
        }

    @Test
    fun `a stage cannot pass the total, or go below nothing`() =
        runBlocking<Unit> {
            // Two different things are wrong and the screen has to say which:
            // one amount is more than the task needs, the other is not a count
            // of anything. Answering both with the ordering rule would tell the
            // user to reorder steps that are in perfectly good order.
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 10, name = "Bird Cards")

            mapOf(11 to TaskProgressFailure.STAGE_QUANTITY_EXCEEDS_REQUIRED, -1 to TaskProgressFailure.INVALID_QUANTITY)
                .forEach { (quantity, expected) ->
                    assertEquals(
                        expected,
                        assertFailsWith<TaskProgressException> {
                            progress.setStageQuantity(taskId, ProductionStage.PRINT, quantity, clock)
                        }.failure,
                        "an amount of $quantity was refused for the wrong reason",
                    )
                }
            assertEquals(0, progress.stagesOfTask(taskId).first().completedQuantity)
        }

    @Test
    fun `a pipeline without a total is refused rather than run against a guess`() =
        runBlocking<Unit> {
            // PLAN 7.2 asks the user for the total first.
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = null, name = "Bird Cards")

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantity(taskId, ProductionStage.PRINT, 1, clock)
                }

            assertEquals(TaskProgressFailure.REQUIRED_QUANTITY_UNKNOWN, refusal.failure)
        }

    @Test
    fun `every stage reaching the total finishes the task`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.BOARD, requiredQuantity = 16, name = "Plaj tile")
            progress.setStageQuantity(taskId, ProductionStage.PRINT, 16, clock)
            progress.setStageQuantity(taskId, ProductionStage.GLUE, 16, clock)
            assertFalse(taskOf(taskId).isCompleted, "the task finished before it was cut")

            progress.setStageQuantity(taskId, ProductionStage.CUT, 16, clock)

            val task = taskOf(taskId)
            assertTrue(task.isCompleted)
            assertNotNull(task.completedAt)
            assertEquals(emptyList(), database.taskDao().activeUnfinishedTasksInPool(PoolType.BOARD))
        }

    @Test
    fun `pulling a finished pipeline back reopens the task`() =
        runBlocking<Unit> {
            // PLAN 6.4 does not let the finished mark stand against counters that
            // now disagree with it.
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 4, name = "Bird Cards")
            listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT).forEach {
                progress.setStageQuantity(taskId, it, 4, clock)
            }
            assertTrue(taskOf(taskId).isCompleted)

            progress.setStageQuantity(taskId, ProductionStage.CUT, 3, clock)

            val task = taskOf(taskId)
            assertFalse(task.isCompleted)
            assertNull(task.completedAt)
        }

    @Test
    fun `setting a stage to what it already is changes nothing`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 10, name = "Bird Cards")
            progress.setStageQuantity(taskId, ProductionStage.PRINT, 4, clock)
            val before = taskOf(taskId)
            val readsBefore = clock.reads

            assertFalse(progress.setStageQuantity(taskId, ProductionStage.PRINT, 4, clock))

            assertEquals(before, taskOf(taskId))
            assertEquals(readsBefore, clock.reads, "a no-op read the clock")
        }

    @Test
    fun `working a stage moves the task's update time`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 10, name = "Bird Cards")
            val before = taskOf(taskId)

            progress.setStageQuantity(taskId, ProductionStage.PRINT, 4, clock)

            assertTrue(taskOf(taskId).updatedAt > before.updatedAt)
        }

    @Test
    fun `finishing a card task fills its pipeline so nothing contradicts it`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")
            progress.setStageQuantity(taskId, ProductionStage.PRINT, 170, clock)

            progress.completeTask(taskId, clock, ids)

            assertEquals(listOf(170, 170, 170), progress.stagesOfTask(taskId).map { it.completedQuantity })
        }

    @Test
    fun `a task and its stages are written together or not at all`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database, columnType = dev.pnptracker.domain.model.CellColumnType.CARD)
            val task = aTask(name = "Bird Cards", poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)

            // The pool and the column disagree, so nothing at all should land.
            val wrongCell = insertGameAndCell(database, columnType = dev.pnptracker.domain.model.CellColumnType.BOARD)
            assertFailsWith<Exception> {
                database.taskDao().addTaskToCell(task, wrongCell.id, ids.newId(), createdAt)
            }
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
            assertEquals(0, CommittedSchema.countRowsOf(directory.databaseFile, "task_stages"))

            // And the same task into the right cell brings its whole pipeline.
            database.taskDao().addTaskToCell(task, cell.id, ids.newId(), createdAt)
            assertEquals(3, progress.stagesOfTask(task.id).size)
        }

    // ------------------------------------- the print run belongs to one pool

    @Test
    fun `a card task has no print run to record`() =
        runBlocking<Unit> {
            // PLAN 6 is the 3D model throughout. A card task is counted by its
            // pipeline, so recording a run on it would finish 170 cards with
            // nothing printed — the contradiction PLAN 6.4 forbids outright.
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")

            val refusal =
                assertFailsWith<TaskProgressException> { progress.completePrimaryBatch(taskId, clock) }

            assertEquals(TaskProgressFailure.PRIMARY_BATCH_ONLY_FOR_THREE_D, refusal.failure)
            val task = taskOf(taskId)
            assertFalse(task.isCompleted, "a card task was finished with nothing printed")
            assertFalse(task.primaryBatchCompleted)
            assertNull(task.completedAt)
            assertEquals(listOf(0, 0, 0), progress.stagesOfTask(taskId).map { it.completedQuantity })
            assertEquals(
                listOf(taskId),
                database.taskDao().activeUnfinishedTasksInPool(PoolType.CARD).map { it.id },
                "a refused print run took the task out of its pool anyway",
            )
        }

    @Test
    fun `a board task has no print run to record either`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.BOARD, requiredQuantity = 16, name = "Plaj tile")

            val refusal =
                assertFailsWith<TaskProgressException> { progress.completePrimaryBatch(taskId, clock) }

            assertEquals(TaskProgressFailure.PRIMARY_BATCH_ONLY_FOR_THREE_D, refusal.failure)
            assertFalse(taskOf(taskId).isCompleted)
            assertEquals(listOf(0, 0, 0), progress.stagesOfTask(taskId).map { it.completedQuantity })
        }

    @Test
    fun `a special task has no print run either, though it has no pipeline`() =
        runBlocking<Unit> {
            // Having no stages is not the same as being printed in one run. The
            // rule names the pool rather than asking whether stages exist,
            // because a special task would pass that question and still be wrong.
            val taskId = aTaskIn(PoolType.SPECIAL, requiredQuantity = 8, name = "Özel zar")

            val refusal =
                assertFailsWith<TaskProgressException> { progress.completePrimaryBatch(taskId, clock) }

            assertEquals(TaskProgressFailure.PRIMARY_BATCH_ONLY_FOR_THREE_D, refusal.failure)
            assertFalse(taskOf(taskId).isCompleted)
            assertFalse(taskOf(taskId).primaryBatchCompleted)
        }

    @Test
    fun `a refused print run writes nothing at all`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")
            val before = taskOf(taskId)

            assertFailsWith<TaskProgressException> { progress.completePrimaryBatch(taskId, clock) }

            assertEquals(before, taskOf(taskId), "a refused print run changed the task row")
            assertEquals(0, clock.reads, "a refused print run read the clock")
            assertEquals(0, CommittedSchema.countRowsOf(directory.databaseFile, "progress_events"))
            assertEquals(
                listOf(createdAt, createdAt, createdAt),
                progress.stagesOfTask(taskId).map { it.updatedAt },
                "a refused print run moved a stage's time",
            )
        }

    // ---------------------------------------- what finishing means, by pool

    @Test
    fun `making a card shortage good does not finish a task that never printed`() =
        runBlocking<Unit> {
            // Owing nothing is not the same as having done the work. Before this
            // rule the task went straight to finished with an empty pipeline.
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 10, name = "Bird Cards")
            progress.reportFailure(ids.newId(), taskId, 3, clock)

            assertTrue(progress.resolveShortage(ids.newId(), taskId, 3, clock))

            val task = taskOf(taskId)
            assertEquals(0, task.currentMissingQuantity)
            assertFalse(task.isCompleted, "a card task finished with nothing printed")
            assertEquals(listOf(taskId), database.taskDao().activeUnfinishedTasksInPool(PoolType.CARD).map { it.id })
        }

    @Test
    fun `making a shortage good finishes a card task whose pipeline is done`() =
        runBlocking<Unit> {
            // PLAN: a reprint reported on finished work reopens the task, and
            // making it good finishes it again. The stages were not touched by
            // either step — which of them has to be redone is a judgement PLAN
            // leaves to a later step, and guessing it here would be inventing.
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 4, name = "Bird Cards")
            listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT).forEach {
                progress.setStageQuantity(taskId, it, 4, clock)
            }
            assertTrue(taskOf(taskId).isCompleted)

            progress.reportFailure(ids.newId(), taskId, 2, clock, cardReference = "Bird #7")
            assertFalse(taskOf(taskId).isCompleted, "a reprint report left the task finished")
            assertEquals(listOf(4, 4, 4), progress.stagesOfTask(taskId).map { it.completedQuantity })

            assertTrue(progress.resolveShortage(ids.newId(), taskId, 2, clock))

            assertTrue(taskOf(taskId).isCompleted, "the task did not finish again once nothing was owed")
            assertEquals(2L, progress.failureTotalOf(taskId))
        }

    @Test
    fun `a special task is never finished behind the user's back`() =
        runBlocking<Unit> {
            // PLAN 9 measures special work by nothing the database keeps, so
            // there is no state that could mean "done" on its own.
            val taskId = aTaskIn(PoolType.SPECIAL, requiredQuantity = 8, name = "Özel zar")
            progress.reportFailure(ids.newId(), taskId, 2, clock)

            assertTrue(progress.resolveShortage(ids.newId(), taskId, 2, clock))

            assertFalse(taskOf(taskId).isCompleted, "a special task finished itself")
            assertEquals(0, taskOf(taskId).currentMissingQuantity)
            // It is still finished the moment the user says so.
            assertTrue(progress.completeTask(taskId, clock, ids))
            assertTrue(taskOf(taskId).isCompleted)
        }

    @Test
    fun `a pipeline counted up does not finish a task that owes a reprint`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 4, name = "Bird Cards")
            progress.setStageQuantity(taskId, ProductionStage.PRINT, 4, clock)
            progress.setStageQuantity(taskId, ProductionStage.LAMINATE, 4, clock)
            progress.reportFailure(ids.newId(), taskId, 1, clock)

            assertTrue(progress.setStageQuantity(taskId, ProductionStage.CUT, 4, clock))

            val task = taskOf(taskId)
            assertFalse(task.isCompleted, "a task owing a reprint was finished by its last stage")
            assertEquals(1, task.currentMissingQuantity)
            assertEquals(listOf(4, 4, 4), progress.stagesOfTask(taskId).map { it.completedQuantity })
        }

    @Test
    fun `a task with no total given is still finished by hand`() =
        runBlocking<Unit> {
            // PLAN 6.4 keeps the manual finish for exactly this case; the new
            // rule must not have taken it away.
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = null, name = "Bird Cards")

            assertTrue(progress.completeTask(taskId, clock, ids))

            assertTrue(taskOf(taskId).isCompleted)
            assertEquals(listOf(0, 0, 0), progress.stagesOfTask(taskId).map { it.completedQuantity })
        }

    // ------------------------------------------- retrying and reusing a name

    @Test
    fun `a retry with the same details is the same event`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")
            val eventId = ids.newId()
            assertTrue(
                progress.reportFailure(
                    eventId,
                    taskId,
                    3,
                    clock,
                    note = "Köşesi ezilmiş",
                    cardReference = "Bird #142",
                    stage = ProductionStage.CUT,
                ),
            )
            val after = taskOf(taskId)
            val readsAfterFirst = clock.reads

            assertFalse(
                progress.reportFailure(
                    eventId,
                    taskId,
                    3,
                    clock,
                    note = "Köşesi ezilmiş",
                    cardReference = "Bird #142",
                    stage = ProductionStage.CUT,
                ),
                "the retry claimed to have recorded",
            )

            assertEquals(after, taskOf(taskId), "the retry moved the task")
            assertEquals(readsAfterFirst, clock.reads, "the retry read the clock")
            assertEquals(1, progress.progressEventsOfTask(taskId).size)
            assertEquals(3L, progress.failureTotalOf(taskId))
        }

    @Test
    fun `the same name on a different task is refused rather than swallowed`() =
        runBlocking<Unit> {
            val first = aTaskIn(PoolType.THREE_D)
            val second = aTaskIn(PoolType.THREE_D)
            val eventId = ids.newId()
            progress.reportFailure(eventId, first, 2, clock)

            val refusal =
                assertFailsWith<TaskProgressException> { progress.reportFailure(eventId, second, 4, clock) }

            assertEquals(TaskProgressFailure.EVENT_ID_ALREADY_USED, refusal.failure)
            assertEquals(0, taskOf(second).currentMissingQuantity, "the shortage went missing quietly")
            assertEquals(emptyList(), progress.progressEventsOfTask(second))
            assertEquals(2, taskOf(first).currentMissingQuantity, "the first task was disturbed")
        }

    @Test
    fun `the same name on a different kind is refused`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            val eventId = ids.newId()
            progress.reportFailure(eventId, taskId, 5, clock)

            val refusal =
                assertFailsWith<TaskProgressException> { progress.resolveShortage(eventId, taskId, 5, clock) }

            assertEquals(TaskProgressFailure.EVENT_ID_ALREADY_USED, refusal.failure)
            assertEquals(5, taskOf(taskId).currentMissingQuantity, "the resolution was applied anyway")
            assertEquals(0L, progress.resolvedTotalOf(taskId))
        }

    @Test
    fun `the same name for a different amount is refused`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            val eventId = ids.newId()
            progress.reportFailure(eventId, taskId, 2, clock)

            val refusal =
                assertFailsWith<TaskProgressException> { progress.reportFailure(eventId, taskId, 3, clock) }

            assertEquals(TaskProgressFailure.EVENT_ID_ALREADY_USED, refusal.failure)
            assertEquals(2, taskOf(taskId).currentMissingQuantity)
            assertEquals(1, progress.progressEventsOfTask(taskId).size)
        }

    @Test
    fun `the same name with a different note is refused`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            val eventId = ids.newId()
            progress.reportFailure(eventId, taskId, 2, clock, note = "Kırık çıktı")

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.reportFailure(eventId, taskId, 2, clock, note = "Eğri çıktı")
                }

            assertEquals(TaskProgressFailure.EVENT_ID_ALREADY_USED, refusal.failure)
            assertEquals("Kırık çıktı", progress.progressEventsOfTask(taskId).single().note)
        }

    @Test
    fun `the same name with a different card is refused`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")
            val eventId = ids.newId()
            progress.reportFailure(eventId, taskId, 1, clock, cardReference = "Bird #142")

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.reportFailure(eventId, taskId, 1, clock, cardReference = "Bird #7")
                }

            assertEquals(TaskProgressFailure.EVENT_ID_ALREADY_USED, refusal.failure)
            assertEquals("Bird #142", progress.progressEventsOfTask(taskId).single().cardReference)
        }

    @Test
    fun `the same name with a different stage is refused`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")
            val eventId = ids.newId()
            progress.reportFailure(eventId, taskId, 1, clock, stage = ProductionStage.PRINT)

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.reportFailure(eventId, taskId, 1, clock, stage = ProductionStage.CUT)
                }

            assertEquals(TaskProgressFailure.EVENT_ID_ALREADY_USED, refusal.failure)
            assertEquals(ProductionStage.PRINT, progress.progressEventsOfTask(taskId).single().stage)
        }

    @Test
    fun `a retry keeps the time the first attempt was recorded at`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            val eventId = ids.newId()
            progress.reportFailure(eventId, taskId, 2, clock)
            val recordedAt = progress.progressEventsOfTask(taskId).single().recordedAt

            // A later clock must not make this a new event, nor move the old one.
            assertFalse(progress.reportFailure(eventId, taskId, 2, clock))

            assertEquals(recordedAt, progress.progressEventsOfTask(taskId).single().recordedAt)
        }

    // --------------------------------------------- detail that fits the task

    @Test
    fun `a shortage cannot be pinned to a stage of the other pipeline`() =
        runBlocking<Unit> {
            val card = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")
            val board = aTaskIn(PoolType.BOARD, requiredQuantity = 16, name = "Plaj tile")

            assertEquals(
                TaskProgressFailure.STAGE_NOT_IN_PIPELINE,
                assertFailsWith<TaskProgressException> {
                    progress.reportFailure(ids.newId(), card, 1, clock, stage = ProductionStage.GLUE)
                }.failure,
            )
            assertEquals(
                TaskProgressFailure.STAGE_NOT_IN_PIPELINE,
                assertFailsWith<TaskProgressException> {
                    progress.reportFailure(ids.newId(), board, 1, clock, stage = ProductionStage.LAMINATE)
                }.failure,
            )
            assertEquals(0, CommittedSchema.countRowsOf(directory.databaseFile, "progress_events"))
            assertEquals(0, taskOf(card).currentMissingQuantity)
            assertEquals(0, taskOf(board).currentMissingQuantity)
        }

    @Test
    fun `a pool with no pipeline takes no stage on a shortage`() =
        runBlocking<Unit> {
            val threeD = aTaskIn(PoolType.THREE_D)
            val special = aTaskIn(PoolType.SPECIAL, requiredQuantity = 8, name = "Özel zar")

            listOf(threeD, special).forEach { taskId ->
                val refusal =
                    assertFailsWith<TaskProgressException> {
                        progress.reportFailure(ids.newId(), taskId, 1, clock, stage = ProductionStage.PRINT)
                    }
                assertEquals(TaskProgressFailure.TASK_HAS_NO_STAGES, refusal.failure)
            }
            assertEquals(0, CommittedSchema.countRowsOf(directory.databaseFile, "progress_events"))
        }

    @Test
    fun `only a card task can say which card came out short`() =
        runBlocking<Unit> {
            // PLAN 7.4 gives the naming to the card pipeline. Anywhere else it
            // would be a detail nothing reads back.
            val others =
                listOf(
                    aTaskIn(PoolType.BOARD, requiredQuantity = 16, name = "Plaj tile"),
                    aTaskIn(PoolType.THREE_D),
                    aTaskIn(PoolType.SPECIAL, requiredQuantity = 8, name = "Özel zar"),
                )

            others.forEach { taskId ->
                val refusal =
                    assertFailsWith<TaskProgressException> {
                        progress.reportFailure(ids.newId(), taskId, 1, clock, cardReference = "Bird #142")
                    }
                assertEquals(TaskProgressFailure.CARD_REFERENCE_ONLY_FOR_CARDS, refusal.failure)
                assertEquals(0, taskOf(taskId).currentMissingQuantity)
            }
            assertEquals(0, CommittedSchema.countRowsOf(directory.databaseFile, "progress_events"))
        }

    @Test
    fun `making good cannot name a card on a task that is not made of cards`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.reportFailure(ids.newId(), taskId, 2, clock)

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.resolveShortage(ids.newId(), taskId, 2, clock, cardReference = "Bird #142")
                }

            assertEquals(TaskProgressFailure.CARD_REFERENCE_ONLY_FOR_CARDS, refusal.failure)
            assertEquals(2, taskOf(taskId).currentMissingQuantity)
        }

    @Test
    fun `a note is welcome on any pool`() =
        runBlocking<Unit> {
            listOf(
                aTaskIn(PoolType.THREE_D),
                aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards"),
                aTaskIn(PoolType.BOARD, requiredQuantity = 16, name = "Plaj tile"),
                aTaskIn(PoolType.SPECIAL, requiredQuantity = 8, name = "Özel zar"),
            ).forEach { taskId ->
                assertTrue(progress.reportFailure(ids.newId(), taskId, 1, clock, note = "Kırık çıktı"))
                assertEquals("Kırık çıktı", progress.progressEventsOfTask(taskId).single().note)
            }
        }

    // ------------------------------------------------- two callers at once

    @Test
    fun `eight callers handing in the same retry produce one event`() =
        runBlocking<Unit> {
            // The check-then-insert is only safe if the insert itself decides
            // the winner, so this is run against a real database rather than
            // reasoned about.
            val taskId = aTaskIn(PoolType.THREE_D, requiredQuantity = 100)
            val eventId = ids.newId()

            val outcomes =
                withContext(Dispatchers.IO) {
                    (1..8).map { async { progress.reportFailure(eventId, taskId, 1, clock) } }.awaitAll()
                }

            assertEquals(1, outcomes.count { it }, "more than one caller claimed to have recorded")
            assertEquals(7, outcomes.count { !it })
            assertEquals(1, progress.progressEventsOfTask(taskId).size)
            assertEquals(1, taskOf(taskId).currentMissingQuantity, "the counter moved more than once")
            assertEquals(1L, progress.failureTotalOf(taskId))
        }

    @Test
    fun `two callers racing with the same name and different details do not lose one`() =
        runBlocking<Unit> {
            // Whoever loses the race must be told, not quietly dropped: the two
            // are different shortages and only one of them can be recorded.
            val taskId = aTaskIn(PoolType.THREE_D, requiredQuantity = 100)
            val eventId = ids.newId()

            val outcomes =
                withContext(Dispatchers.IO) {
                    listOf(2, 5)
                        .map { quantity ->
                            async { runCatching { progress.reportFailure(eventId, taskId, quantity, clock) } }
                        }.awaitAll()
                }

            assertEquals(1, outcomes.count { it.getOrNull() == true }, "the two shortages were not told apart")
            val refused = outcomes.mapNotNull { it.exceptionOrNull() }
            assertEquals(1, refused.size, "the losing caller was not told anything")
            assertEquals(
                TaskProgressFailure.EVENT_ID_ALREADY_USED,
                assertIs<TaskProgressException>(refused.single()).failure,
            )
            assertFalse(outcomes.any { it.getOrNull() == false }, "a real shortage was reported as a retry")

            val recorded = progress.progressEventsOfTask(taskId).single()
            assertEquals(recorded.quantity, taskOf(taskId).currentMissingQuantity, "the counter lost track")
            assertEquals(recorded.quantity.toLong(), progress.failureTotalOf(taskId))
        }

    // ---------------------------------------- the whole pipeline, saved at once

    /** What the pipeline stands at, in the order it is worked in. */
    private suspend fun pipelineOf(taskId: EntityId): List<Int> = progress.stagesOfTask(taskId).map { it.completedQuantity }

    /** The whole pipeline as a snapshot of the moment it was read. */
    private suspend fun snapshotOf(taskId: EntityId): StageSnapshot =
        StageSnapshot(
            requiredQuantity = taskOf(taskId).requiredQuantity,
            stages = progress.stagesOfTask(taskId).associate { it.stage to it.completedQuantity },
        )

    /** Changes what a task is counted up to, through the form that really does it. */
    private suspend fun changeTotalOf(
        taskId: EntityId,
        total: Int?,
    ) {
        val task = taskOf(taskId)
        database.taskEditDao().editTask(
            taskId = taskId,
            name = task.name,
            colorIds = emptyList(),
            requiredQuantity = total,
            notes = task.notes,
            trackingMode = task.trackingMode,
            clock = clock,
        )
    }

    // ------------------------------------------- a pipeline that moved underneath

    @Test
    fun `a total raised under an open panel refuses the save it was opened for`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)
            val opened = snapshotOf(taskId)
            changeTotalOf(taskId, 30)

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(taskId, mapOf(ProductionStage.PRINT to 18), clock, expected = opened)
                }

            // Not "that will not fit" — 18 fits perfectly well in 30. What went
            // wrong is that the picture the user described has been replaced.
            assertEquals(TaskProgressFailure.STALE_STAGE_PROGRESS, refusal.failure)
            assertEquals(listOf(15, 10, 5), pipelineOf(taskId), "a stale save wrote anyway")
            assertEquals(30, taskOf(taskId).requiredQuantity, "the save undid the new total")
        }

    @Test
    fun `a total lowered under an open panel is refused as stale and not as too large`() =
        runBlocking<Unit> {
            // Low enough that the total may really be brought down to twelve:
            // the form will not take a total below the furthest step.
            val taskId = aCardAt(8, 8, 5)
            val opened = snapshotOf(taskId)
            changeTotalOf(taskId, 12)

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(
                        taskId,
                        mapOf(ProductionStage.PRINT to 15, ProductionStage.LAMINATE to 10, ProductionStage.CUT to 5),
                        clock,
                        expected = opened,
                    )
                }

            // Fifteen is indeed past twelve, and saying so would send the user
            // to correct a number that was right when they typed it. The reason
            // the save cannot stand is that the task is no longer the one they
            // typed it against.
            assertEquals(TaskProgressFailure.STALE_STAGE_PROGRESS, refusal.failure)
            assertEquals(listOf(8, 8, 5), pipelineOf(taskId))
            assertEquals(12, taskOf(taskId).requiredQuantity)
        }

    @Test
    fun `a total taken away under an open panel is refused as stale`() =
        runBlocking<Unit> {
            val taskId = aCardAt(8, 8, 5)
            val opened = snapshotOf(taskId)
            changeTotalOf(taskId, null)

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(taskId, mapOf(ProductionStage.CUT to 6), clock, expected = opened)
                }

            assertEquals(TaskProgressFailure.STALE_STAGE_PROGRESS, refusal.failure)
            assertEquals(listOf(8, 8, 5), pipelineOf(taskId))
        }

    @Test
    fun `renaming or noting a task does not refuse a pipeline saved against it`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)
            val opened = snapshotOf(taskId)
            val task = taskOf(taskId)
            database.taskEditDao().editTask(
                taskId = taskId,
                name = "Kuş kartları",
                colorIds = emptyList(),
                requiredQuantity = task.requiredQuantity,
                notes = "ikinci baskı",
                trackingMode = task.trackingMode,
                clock = clock,
            )

            // None of that is anything the steps are counted against, so none of
            // it may refuse a save that is still true.
            assertTrue(progress.setStageQuantities(taskId, mapOf(ProductionStage.CUT to 9), clock, expected = opened))
            assertEquals(listOf(15, 10, 9), pipelineOf(taskId))
        }

    @Test
    fun `an expected pipeline missing a step is refused`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)
            val opened =
                StageSnapshot(CARD_TOTAL, mapOf(ProductionStage.PRINT to 15, ProductionStage.LAMINATE to 10))

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(taskId, mapOf(ProductionStage.CUT to 4), clock, expected = opened)
                }

            assertEquals(TaskProgressFailure.STALE_STAGE_PROGRESS, refusal.failure)
            assertEquals(listOf(15, 10, 5), pipelineOf(taskId))
        }

    @Test
    fun `an expected pipeline with a step too many is refused`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)
            val opened =
                StageSnapshot(
                    CARD_TOTAL,
                    mapOf(
                        ProductionStage.PRINT to 15,
                        ProductionStage.LAMINATE to 10,
                        ProductionStage.CUT to 5,
                        ProductionStage.GLUE to 0,
                    ),
                )

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(taskId, mapOf(ProductionStage.CUT to 4), clock, expected = opened)
                }

            // The stored pipeline is perfectly sound, so this is not a broken
            // one: what does not add up is the picture that was handed back.
            assertEquals(TaskProgressFailure.STALE_STAGE_PROGRESS, refusal.failure)
            assertEquals(listOf(15, 10, 5), pipelineOf(taskId))
        }

    @Test
    fun `a pipeline whose rows have been reordered is refused as broken`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)
            // The steps are read in the order they are worked in, so turning the
            // order upside down is a pipeline that is no longer a card's.
            reorderStages(taskId, listOf(ProductionStage.CUT to 0, ProductionStage.LAMINATE to 1, ProductionStage.PRINT to 2))

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(taskId, mapOf(ProductionStage.CUT to 4), clock)
                }

            assertEquals(TaskProgressFailure.STAGE_PIPELINE_BROKEN, refusal.failure)
        }

    @Test
    fun `a pipeline with a row gone is refused as broken and not as stale`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)
            deleteStage(taskId, ProductionStage.LAMINATE)

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(
                        taskId,
                        mapOf(ProductionStage.CUT to 4),
                        clock,
                        expected = StageSnapshot(CARD_TOTAL, mapOf(ProductionStage.PRINT to 15, ProductionStage.CUT to 5)),
                    )
                }

            // Broken beats stale: what is wrong is the record itself, and telling
            // the user to close the panel and open it again would send them round
            // a loop that cannot end.
            assertEquals(TaskProgressFailure.STAGE_PIPELINE_BROKEN, refusal.failure)
        }

    @Test
    fun `a card's snapshot cannot be saved onto a board task`() =
        runBlocking<Unit> {
            val boardId = aTaskIn(PoolType.BOARD, requiredQuantity = CARD_TOTAL)

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(
                        boardId,
                        mapOf(ProductionStage.PRINT to 4, ProductionStage.LAMINATE to 4),
                        clock,
                        expected = StageSnapshot(CARD_TOTAL, mapOf(ProductionStage.PRINT to 0, ProductionStage.LAMINATE to 0)),
                    )
                }

            assertEquals(TaskProgressFailure.STAGE_NOT_IN_PIPELINE, refusal.failure)
            assertEquals(listOf(0, 0, 0), pipelineOf(boardId))
        }

    @Test
    fun `a save that changes nothing is still refused when the pipeline moved`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)
            val opened = snapshotOf(taskId)
            progress.setStageQuantities(taskId, mapOf(ProductionStage.PRINT to 18), clock)
            val readsBefore = clock.reads

            // Nothing has changed as far as the panel knows: it is sending back
            // exactly what it was opened with. As far as the database is
            // concerned that would undo a change somebody else made, so being
            // stale has to be noticed before being a no-op is.
            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(
                        taskId,
                        mapOf(ProductionStage.PRINT to 15, ProductionStage.LAMINATE to 10, ProductionStage.CUT to 5),
                        clock,
                        expected = opened,
                    )
                }

            assertEquals(TaskProgressFailure.STALE_STAGE_PROGRESS, refusal.failure)
            assertEquals(listOf(18, 10, 5), pipelineOf(taskId), "a stale no-op put back what it was opened with")
            assertEquals(readsBefore, clock.reads, "a refused save read the clock")
        }

    @Test
    fun `a save that changes nothing against the pipeline as it stands writes nothing`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)
            val opened = snapshotOf(taskId)
            val readsBefore = clock.reads

            val changed =
                progress.setStageQuantities(
                    taskId,
                    mapOf(ProductionStage.PRINT to 15, ProductionStage.LAMINATE to 10, ProductionStage.CUT to 5),
                    clock,
                    expected = opened,
                )

            assertFalse(changed)
            assertEquals(readsBefore, clock.reads, "a no-op read the clock")
            assertEquals(listOf(15, 10, 5), pipelineOf(taskId))
        }

    /** Rewrites the order the steps are worked in, which nothing in production does. */
    private suspend fun reorderStages(
        taskId: EntityId,
        order: List<Pair<ProductionStage, Int>>,
    ) = database.useWriterConnection { transactor ->
        // Moved out of the way first. The unique index over (task, order) will
        // not have two steps in one place even for an instant, which is itself
        // worth knowing: the order these are read in cannot be doubled up.
        listOf(true, false).forEach { parking ->
            order.forEach { (stage, index) ->
                transactor.usePrepared("UPDATE task_stages SET order_index = ? WHERE task_id = ? AND stage = ?") {
                    it.bindLong(1, if (parking) (index + PARKED_ORDER).toLong() else index.toLong())
                    it.bindText(2, taskId.toString())
                    it.bindText(3, stage.name)
                    it.step()
                }
            }
        }
    }

    /** Takes one step out of a pipeline, which nothing in production does either. */
    private suspend fun deleteStage(
        taskId: EntityId,
        stage: ProductionStage,
    ) = database.useWriterConnection { transactor ->
        transactor.usePrepared("DELETE FROM task_stages WHERE task_id = ? AND stage = ?") {
            it.bindText(1, taskId.toString())
            it.bindText(2, stage.name)
            it.step()
        }
    }

    private suspend fun aCardAt(
        print: Int,
        laminate: Int,
        cut: Int,
        total: Int = CARD_TOTAL,
    ): EntityId {
        val taskId = aTaskIn(PoolType.CARD, requiredQuantity = total, name = "Bird Cards")
        progress.setStageQuantities(
            taskId,
            mapOf(ProductionStage.PRINT to print, ProductionStage.LAMINATE to laminate, ProductionStage.CUT to cut),
            clock,
        )
        return taskId
    }

    @Test
    fun `a card pipeline is written in one go`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 20, name = "Bird Cards")

            val changed =
                progress.setStageQuantities(
                    taskId,
                    mapOf(
                        ProductionStage.PRINT to 15,
                        ProductionStage.LAMINATE to 10,
                        ProductionStage.CUT to 5,
                    ),
                    clock,
                )

            assertTrue(changed)
            assertEquals(listOf(15, 10, 5), pipelineOf(taskId))
            // One act, one moment: three saves would stamp three times.
            assertEquals(1, clock.reads, "the pipeline was written as more than one act")
            assertTrue(progress.progressEventsOfTask(taskId).isEmpty(), "counting a step wrote an event")
        }

    @Test
    fun `a board pipeline is written in one go`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.BOARD, requiredQuantity = 20, name = "Plaj tile")

            progress.setStageQuantities(
                taskId,
                mapOf(ProductionStage.PRINT to 16, ProductionStage.GLUE to 12, ProductionStage.CUT to 8),
                clock,
            )

            assertEquals(listOf(16, 12, 8), pipelineOf(taskId))
            assertEquals(1, clock.reads)
        }

    @Test
    fun `a pipeline that reaches the total by a route of its own is still allowed`() =
        runBlocking<Unit> {
            // Lowering the print run and the cut together describes a state the
            // rule allows; saving a step at a time would refuse it on the way,
            // because the cut would stand above the print run in between.
            val taskId = aCardAt(20, 20, 20)

            progress.setStageQuantities(
                taskId,
                mapOf(ProductionStage.PRINT to 8, ProductionStage.LAMINATE to 8, ProductionStage.CUT to 8),
                clock,
            )

            assertEquals(listOf(8, 8, 8), pipelineOf(taskId))
        }

    @Test
    fun `a target that breaks the order writes none of its steps`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)
            val before = clock.reads

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(
                        taskId,
                        mapOf(ProductionStage.PRINT to 8),
                        clock,
                    )
                }

            assertEquals(TaskProgressFailure.STAGE_ORDER_VIOLATED, refusal.failure)
            assertEquals(listOf(15, 10, 5), pipelineOf(taskId), "a refused save moved a counter")
            assertEquals(before, clock.reads, "a refused save read the clock")
        }

    @Test
    fun `a second step that will not do takes the first one down with it`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)

            assertFailsWith<TaskProgressException> {
                progress.setStageQuantities(
                    taskId,
                    mapOf(ProductionStage.PRINT to 18, ProductionStage.LAMINATE to 19),
                    clock,
                )
            }

            assertEquals(listOf(15, 10, 5), pipelineOf(taskId), "the first step was written before the second was read")
        }

    @Test
    fun `a third step that will not do takes the first two down with it`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)

            assertFailsWith<TaskProgressException> {
                progress.setStageQuantities(
                    taskId,
                    mapOf(
                        ProductionStage.PRINT to 18,
                        ProductionStage.LAMINATE to 16,
                        ProductionStage.CUT to 21,
                    ),
                    clock,
                )
            }

            assertEquals(listOf(15, 10, 5), pipelineOf(taskId))
        }

    @Test
    fun `only the steps that really moved are written`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)
            val stamped = progress.stagesOfTask(taskId).associate { it.stage to it.updatedAt }

            progress.setStageQuantities(taskId, mapOf(ProductionStage.CUT to 9), clock)

            val after = progress.stagesOfTask(taskId).associate { it.stage to it.updatedAt }
            assertEquals(stamped[ProductionStage.PRINT], after[ProductionStage.PRINT], "an untouched step was written")
            assertEquals(stamped[ProductionStage.LAMINATE], after[ProductionStage.LAMINATE])
            assertTrue(after.getValue(ProductionStage.CUT) > stamped.getValue(ProductionStage.CUT))
        }

    @Test
    fun `a pipeline saved at what it already says does nothing at all`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)
            val before = clock.reads

            val changed =
                progress.setStageQuantities(
                    taskId,
                    mapOf(
                        ProductionStage.PRINT to 15,
                        ProductionStage.LAMINATE to 10,
                        ProductionStage.CUT to 5,
                    ),
                    clock,
                )

            assertFalse(changed, "saving what was already there was taken for a change")
            assertEquals(before, clock.reads, "a save with nothing to do read the clock")
        }

    @Test
    fun `an amount is refused for what is really wrong with it`() =
        runBlocking<Unit> {
            val taskId = aCardAt(0, 0, 0)

            mapOf(
                -1 to TaskProgressFailure.INVALID_QUANTITY,
                21 to TaskProgressFailure.STAGE_QUANTITY_EXCEEDS_REQUIRED,
            ).forEach { (amount, expected) ->
                val refusal =
                    assertFailsWith<TaskProgressException> {
                        progress.setStageQuantities(taskId, mapOf(ProductionStage.PRINT to amount), clock)
                    }
                assertEquals(expected, refusal.failure, "an amount of $amount was refused for the wrong reason")
            }
            assertEquals(listOf(0, 0, 0), pipelineOf(taskId))
        }

    @Test
    fun `a pipeline counted all the way up finishes the task`() =
        runBlocking<Unit> {
            val taskId = aCardAt(20, 20, 20)

            val task = checkNotNull(progress.taskById(taskId))
            assertTrue(task.isCompleted)
            assertNotNull(task.completedAt)
            assertTrue(progress.progressEventsOfTask(taskId).isEmpty())
        }

    @Test
    fun `pulling a finished pipeline back opens the task again`() =
        runBlocking<Unit> {
            val taskId = aCardAt(20, 20, 20)

            progress.setStageQuantities(taskId, mapOf(ProductionStage.CUT to 19), clock)

            val task = checkNotNull(progress.taskById(taskId))
            assertFalse(task.isCompleted)
            assertNull(task.completedAt)
            assertEquals(listOf(20, 20, 19), pipelineOf(taskId))
        }

    @Test
    fun `a full pipeline does not finish a task that still owes a reprint`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 20, name = "Bird Cards")
            progress.reportFailure(ids.newId(), taskId, 4, clock)

            progress.setStageQuantities(
                taskId,
                mapOf(
                    ProductionStage.PRINT to 20,
                    ProductionStage.LAMINATE to 20,
                    ProductionStage.CUT to 20,
                ),
                clock,
            )

            assertFalse(checkNotNull(progress.taskById(taskId)).isCompleted, "a task finished while it owed a reprint")

            progress.resolveShortage(ids.newId(), taskId, 4, clock)

            assertTrue(checkNotNull(progress.taskById(taskId)).isCompleted, "making good the last of it did not finish it")
            assertEquals(listOf(20, 20, 20), pipelineOf(taskId), "settling the debt moved the pipeline")
        }

    @Test
    fun `a pipeline saved against counts that have since moved is refused`() =
        runBlocking<Unit> {
            // What a panel left open would do: it carries the counts it was
            // opened on, and putting its own numbers back would undo whatever
            // happened in between without anybody being told.
            val taskId = aCardAt(15, 10, 5)
            val opened =
                mapOf(ProductionStage.PRINT to 15, ProductionStage.LAMINATE to 10, ProductionStage.CUT to 5)
            progress.setStageQuantities(taskId, mapOf(ProductionStage.CUT to 9), clock)

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(
                        taskId,
                        mapOf(ProductionStage.PRINT to 16),
                        clock,
                        expected = StageSnapshot(CARD_TOTAL, opened),
                    )
                }

            assertEquals(TaskProgressFailure.STALE_STAGE_PROGRESS, refusal.failure)
            assertEquals(listOf(15, 10, 9), pipelineOf(taskId), "a stale save wrote anyway")
        }

    @Test
    fun `of two panels over one task the first to save wins`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)
            val opened =
                mapOf(ProductionStage.PRINT to 15, ProductionStage.LAMINATE to 10, ProductionStage.CUT to 5)

            progress.setStageQuantities(taskId, mapOf(ProductionStage.PRINT to 18), clock, expected = StageSnapshot(CARD_TOTAL, opened))

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(
                        taskId,
                        mapOf(ProductionStage.PRINT to 12),
                        clock,
                        expected = StageSnapshot(CARD_TOTAL, opened),
                    )
                }

            assertEquals(TaskProgressFailure.STALE_STAGE_PROGRESS, refusal.failure)
            assertEquals(listOf(18, 10, 5), pipelineOf(taskId), "the second panel overwrote the first")
        }

    @Test
    fun `a pipeline saved against the counts it was opened on goes through`() =
        runBlocking<Unit> {
            val taskId = aCardAt(15, 10, 5)

            val changed =
                progress.setStageQuantities(
                    taskId,
                    mapOf(ProductionStage.CUT to 9),
                    clock,
                    expected =
                        StageSnapshot(
                            CARD_TOTAL,
                            mapOf(
                                ProductionStage.PRINT to 15,
                                ProductionStage.LAMINATE to 10,
                                ProductionStage.CUT to 5,
                            ),
                        ),
                )

            assertTrue(changed)
            assertEquals(listOf(15, 10, 9), pipelineOf(taskId))
        }

    @Test
    fun `a pipeline is refused on every task that has none of its own`() =
        runBlocking<Unit> {
            listOf(PoolType.THREE_D, PoolType.SPECIAL).forEach { pool ->
                val taskId = aTaskIn(pool, requiredQuantity = 20, name = "Token")
                val refusal =
                    assertFailsWith<TaskProgressException> {
                        progress.setStageQuantities(taskId, mapOf(ProductionStage.PRINT to 1), clock)
                    }
                assertEquals(TaskProgressFailure.TASK_HAS_NO_STAGES, refusal.failure, "$pool was given a pipeline")
            }
        }

    @Test
    fun `a step belonging to the other pipeline is refused`() =
        runBlocking<Unit> {
            val card = aTaskIn(PoolType.CARD, requiredQuantity = 20, name = "Bird Cards")
            val board = aTaskIn(PoolType.BOARD, requiredQuantity = 20, name = "Plaj tile")

            listOf(card to ProductionStage.GLUE, board to ProductionStage.LAMINATE).forEach { (taskId, stage) ->
                val refusal =
                    assertFailsWith<TaskProgressException> {
                        progress.setStageQuantities(taskId, mapOf(stage to 1), clock)
                    }
                assertEquals(TaskProgressFailure.STAGE_NOT_IN_PIPELINE, refusal.failure)
            }
        }

    @Test
    fun `a pipeline with no total behind it is refused rather than guessed at`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = null, name = "Bird Cards")

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.setStageQuantities(taskId, mapOf(ProductionStage.PRINT to 1), clock)
                }

            assertEquals(TaskProgressFailure.REQUIRED_QUANTITY_UNKNOWN, refusal.failure)
        }

    // ------------------------------------ the whole range an amount may be

    @Test
    fun `everything a task can owe can be made good in one movement`() =
        runBlocking<Unit> {
            // A task owing the very most it may owe. Making that good has to be
            // one movement, not one the user has to split because the field or
            // the transaction cannot hold the number.
            val taskId = aTaskIn(PoolType.THREE_D, requiredQuantity = Int.MAX_VALUE)
            progress.reportFailure(ids.newId(), taskId, Int.MAX_VALUE, clock)
            assertEquals(Int.MAX_VALUE, taskOf(taskId).currentMissingQuantity)

            assertTrue(progress.resolveShortage(ids.newId(), taskId, Int.MAX_VALUE, clock))

            assertEquals(0, taskOf(taskId).currentMissingQuantity)
            // Made good is a new movement, never the removal of an old one, so
            // what went wrong is still on the record.
            assertEquals(Int.MAX_VALUE.toLong(), progress.failureTotalOf(taskId))
            assertEquals(Int.MAX_VALUE.toLong(), progress.resolvedTotalOf(taskId))
            assertEquals(2, progress.progressEventsOfTask(taskId).size, "one movement was written as several")
        }

    @Test
    fun `a large amount made good writes one event and no more`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D, requiredQuantity = 2_000_000_000)
            progress.reportFailure(ids.newId(), taskId, 2_000_000_000, clock)

            assertTrue(progress.resolveShortage(ids.newId(), taskId, 1_999_999_999, clock))

            assertEquals(1, taskOf(taskId).currentMissingQuantity)
            assertEquals(
                listOf(ProgressEventKind.FAILURE_REPORTED, ProgressEventKind.SHORTAGE_RESOLVED),
                progress.progressEventsOfTask(taskId).map { it.kind },
            )
        }

    @Test
    fun `more than everything owed is still refused when everything is a great deal`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D, requiredQuantity = Int.MAX_VALUE)
            progress.reportFailure(ids.newId(), taskId, 1_000_000_000, clock)

            assertEquals(
                TaskProgressFailure.MORE_RESOLVED_THAN_OUTSTANDING,
                assertFailsWith<TaskProgressException> {
                    progress.resolveShortage(ids.newId(), taskId, Int.MAX_VALUE, clock)
                }.failure,
            )

            assertEquals(1_000_000_000, taskOf(taskId).currentMissingQuantity, "a refused settling wrote anyway")
            assertEquals(1, progress.progressEventsOfTask(taskId).size)
        }

    @Test
    fun `the history holds amounts that together pass what one of them could be`() =
        runBlocking<Unit> {
            // PLAN 6.4 lets the failure total go past what the task needs: a
            // piece can be spoiled again and again. The sum is therefore wider
            // than any single report, and the counter saturates while it does not.
            val taskId = aTaskIn(PoolType.THREE_D, requiredQuantity = 10)
            repeat(3) { progress.reportFailure(ids.newId(), taskId, 2_000_000_000, clock) }

            assertEquals(6_000_000_000L, progress.failureTotalOf(taskId))
            assertEquals(10, taskOf(taskId).currentMissingQuantity, "the counter went past the total")
            assertEquals(3, progress.progressEventsOfTask(taskId).size)
        }
}

/** What the pipeline fixtures below count up to, unless one says otherwise. */
private const val CARD_TOTAL = 20

/** Far enough past any real step that a pipeline can be rewritten through it. */
private const val PARKED_ORDER = 100
