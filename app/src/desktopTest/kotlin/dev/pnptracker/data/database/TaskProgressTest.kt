package dev.pnptracker.data.database

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.ProgressEventKind
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskProgressException
import dev.pnptracker.domain.tasks.TaskProgressFailure
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
            val cellId = assertNotNull(database.cellSegmentDao().segmentOfTask(taskId)).cellId
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
            assertNotNull(database.cellSegmentDao().segmentOfTask(taskId))
            val cellId = assertNotNull(database.cellSegmentDao().segmentOfTask(taskId)).cellId
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
            assertEquals(3, progress.failureTotalOf(taskId))
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
            assertEquals(3, progress.failureTotalOf(taskId), "making good erased the history of having failed")
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
            assertEquals(5, progress.failureTotalOf(taskId))
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

            listOf(0, -1, -40).forEach { quantity ->
                assertFailsWith<IllegalArgumentException> {
                    progress.reportFailure(ids.newId(), taskId, quantity, clock)
                }
                assertFailsWith<IllegalArgumentException> {
                    progress.resolveShortage(ids.newId(), taskId, quantity, clock)
                }
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
            assertEquals(3, progress.failureTotalOf(taskId))
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
            assertEquals(8, progress.failureTotalOf(taskId))
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
            assertEquals(3, progress.failureTotalOf(taskId), "settling erased the history")
            assertEquals(3, progress.resolvedTotalOf(taskId))
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
            assertEquals(3, progress.failureTotalOf(taskId))
            assertEquals("Kırık çıktı", progress.progressEventsOfTask(taskId).single().note)
        }

    @Test
    fun `a note and a card reference are kept exactly as they were given`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")
            progress.completePrimaryBatch(taskId, clock)

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

            // Only the shortages that name a card show up in the detail list.
            assertEquals(listOf("Bird #142"), progress.openShortageDetailsOf(taskId).map { it.cardReference })
        }

    @Test
    fun `shortage details close when the task owes nothing again`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 170, name = "Bird Cards")
            progress.reportFailure(ids.newId(), taskId, 1, clock, cardReference = "Bird #142")
            assertEquals(1, progress.openShortageDetailsOf(taskId).size)

            progress.resolveShortage(ids.newId(), taskId, 1, clock)

            assertEquals(emptyList(), progress.openShortageDetailsOf(taskId))
            // Closed, not deleted: the history still has it.
            assertEquals(1, progress.progressEventsOfTask(taskId).count { it.cardReference == "Bird #142" })
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
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 10, name = "Bird Cards")

            listOf(11, -1).forEach { quantity ->
                assertEquals(
                    TaskProgressFailure.STAGE_ORDER_VIOLATED,
                    assertFailsWith<TaskProgressException> {
                        progress.setStageQuantity(taskId, ProductionStage.PRINT, quantity, clock)
                    }.failure,
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
}
