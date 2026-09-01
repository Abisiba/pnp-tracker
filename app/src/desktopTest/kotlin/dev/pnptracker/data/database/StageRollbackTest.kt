package dev.pnptracker.data.database

import androidx.sqlite.SQLiteException
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.StageSnapshot
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
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What a half-written pipeline leaves behind, when the database itself gives up.
 *
 * Everything the earlier tests prove about a refused save is proved *before*
 * anything is written: the transaction reads the task, does not like what it is
 * asked for and stops. That is worth knowing and it is not the same question as
 * this one. Here the save is perfectly good, the first step goes in, and the
 * second will not — which is the only way to find out whether the three counters
 * PLAN 7.2 keeps in step really do move as one thing, or merely happen to when
 * nothing goes wrong.
 *
 * So the failures are put in at the statement, in a real SQLite transaction, and
 * the state afterwards is read back through a connection that knows nothing
 * about the trap.
 */
class StageRollbackTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var driver: FailingSqliteDriver
    private lateinit var counter: CountingSqliteDriver
    private var realDatabaseExistedBefore = false

    /** Fixed, and counting, so a write nobody made is a write nobody timed. */
    private class CountingClock(
        private val fixed: Instant,
    ) : Clock {
        var reads: Int = 0
            private set

        override fun now(): Instant {
            reads++
            return fixed
        }
    }

    /** A clock that will not say what time it is, once it has been let loose. */
    private class BrokenClock : Clock {
        var armed: Boolean = false

        override fun now(): Instant {
            if (armed) throw IllegalStateException("the clock refused to answer")
            return createdAt
        }
    }

    private lateinit var clock: CountingClock

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        driver = FailingSqliteDriver()
        // Counted on the outside of the trap, so what is measured is every
        // statement the transaction really got as far as running — including the
        // one that was refused.
        counter = CountingSqliteDriver(driver)
        database = DatabaseFactory(driver = counter).open(directory.databaseFile)
        clock = CountingClock(createdAt)
    }

    @AfterTest
    fun closeDatabase() {
        driver.disarm()
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val progress get() = database.taskProgressDao()

    /** A card task standing at 15/10/5 out of twenty, before any trap is set. */
    private suspend fun aCardAt(
        print: Int = 15,
        laminate: Int = 10,
        cut: Int = 5,
    ): EntityId {
        val cell =
            insertGameAndCell(
                database,
                columnType =
                    dev.pnptracker.domain.model.CellColumnType
                        .of(PoolType.CARD),
                gameName = "Harmonies ${IdGenerator.Random.newId()}",
            )
        val task =
            aTask(
                name = "Bird Cards",
                poolType = PoolType.CARD,
                trackingMode = TrackingMode.PIPELINE,
                requiredQuantity = TOTAL,
            )
        database.taskDao().addTaskToCell(task, cell.id, IdGenerator.Random.newId(), createdAt)
        progress.setStageQuantities(
            task.id,
            mapOf(ProductionStage.PRINT to print, ProductionStage.LAMINATE to laminate, ProductionStage.CUT to cut),
            clock,
        )
        return task.id
    }

    private suspend fun pipelineOf(taskId: EntityId): List<Int> = progress.stagesOfTask(taskId).map { it.completedQuantity }

    /** Everything about the task that a save is allowed to move. */
    private suspend fun stateOf(taskId: EntityId): List<Any?> {
        val task = assertNotNull(progress.taskById(taskId))
        return listOf(task.isCompleted, task.completedAt, task.currentMissingQuantity, task.updatedAt)
    }

    /** Moves all three steps at once, which is what makes the middle one reachable. */
    private suspend fun moveWholePipeline(
        taskId: EntityId,
        clock: Clock = this.clock,
    ) = progress.setStageQuantities(
        taskId,
        mapOf(ProductionStage.PRINT to 18, ProductionStage.LAMINATE to 17, ProductionStage.CUT to 16),
        clock,
        expected =
            StageSnapshot(
                TOTAL,
                mapOf(ProductionStage.PRINT to 15, ProductionStage.LAMINATE to 10, ProductionStage.CUT to 5),
            ),
    )

    @Test
    fun `a second step that will not go in takes the first back out with it`() =
        runBlocking<Unit> {
            val taskId = aCardAt()
            val before = stateOf(taskId)
            driver.failOn(occurrence = 2) { it.contains("UPDATE task_stages") }

            assertFailsWith<SQLiteException> { moveWholePipeline(taskId) }

            driver.disarm()
            assertEquals(listOf(15, 10, 5), pipelineOf(taskId), "the first step stayed written")
            assertEquals(before, stateOf(taskId), "the task moved on a save that never happened")
            assertEquals(emptyList(), progress.progressEventsOfTask(taskId), "a failed save recorded an event")
        }

    @Test
    fun `a third step that will not go in takes the first two back out with it`() =
        runBlocking<Unit> {
            val taskId = aCardAt()
            val before = stateOf(taskId)
            driver.failOn(occurrence = 3) { it.contains("UPDATE task_stages") }

            assertFailsWith<SQLiteException> { moveWholePipeline(taskId) }

            driver.disarm()
            assertEquals(listOf(15, 10, 5), pipelineOf(taskId), "two steps stayed written")
            assertEquals(before, stateOf(taskId))
            assertEquals(emptyList(), progress.progressEventsOfTask(taskId))
        }

    @Test
    fun `a task that will not take the change takes every step back out with it`() =
        runBlocking<Unit> {
            val taskId = aCardAt()
            val before = stateOf(taskId)
            // All three steps are in by now; what will not go is the row that
            // says how the task stands afterwards.
            driver.failOn { it.contains("UPDATE tasks SET") && it.contains("is_completed") }

            assertFailsWith<SQLiteException> { moveWholePipeline(taskId) }

            driver.disarm()
            assertEquals(listOf(15, 10, 5), pipelineOf(taskId), "the steps stayed written without the task")
            assertEquals(before, stateOf(taskId))
        }

    @Test
    fun `a pipeline finished by the save is undone whole when the task will not take it`() =
        runBlocking<Unit> {
            val taskId = aCardAt(print = TOTAL, laminate = TOTAL, cut = 19)
            driver.failOn { it.contains("UPDATE tasks SET") && it.contains("is_completed") }

            assertFailsWith<SQLiteException> {
                progress.setStageQuantities(taskId, mapOf(ProductionStage.CUT to TOTAL), clock)
            }

            driver.disarm()
            // The one that would have finished the task. Both halves of finishing
            // it are gone, so nothing is left claiming a pipeline that is not
            // there or a task that is not done.
            assertEquals(listOf(TOTAL, TOTAL, 19), pipelineOf(taskId))
            val task = assertNotNull(progress.taskById(taskId))
            assertFalse(task.isCompleted)
            assertNull(task.completedAt)
        }

    @Test
    fun `a save whose last look at the task fails leaves nothing of itself behind`() =
        runBlocking<Unit> {
            val taskId = aCardAt()
            val before = stateOf(taskId)
            // The history sum is read once, at the very end, by the check that
            // the transaction runs over its own work before committing.
            driver.failOn { it.contains("FROM progress_events") && it.contains("FAILURE_REPORTED") }

            assertFailsWith<SQLiteException> { moveWholePipeline(taskId) }

            driver.disarm()
            assertEquals(listOf(15, 10, 5), pipelineOf(taskId), "the save committed past its own last check")
            assertEquals(before, stateOf(taskId))
        }

    @Test
    fun `a clock that will not answer leaves the pipeline exactly as it was`() =
        runBlocking<Unit> {
            val taskId = aCardAt()
            val before = stateOf(taskId)
            val broken = BrokenClock().also { it.armed = true }

            assertFailsWith<IllegalStateException> { moveWholePipeline(taskId, broken) }

            assertEquals(listOf(15, 10, 5), pipelineOf(taskId))
            assertEquals(before, stateOf(taskId))
        }

    @Test
    fun `a save with nothing to do never asks what time it is`() =
        runBlocking<Unit> {
            val taskId = aCardAt()
            val broken = BrokenClock()
            broken.armed = true

            // The clock would throw if it were read. It is not: a save that
            // changes nothing must not move a timestamp, so it never gets that
            // far.
            val changed =
                progress.setStageQuantities(
                    taskId,
                    mapOf(ProductionStage.PRINT to 15, ProductionStage.LAMINATE to 10, ProductionStage.CUT to 5),
                    broken,
                )

            assertFalse(changed)
            assertEquals(listOf(15, 10, 5), pipelineOf(taskId))
        }

    @Test
    fun `a save that fell over ran the writes it got to and left none of them`() =
        runBlocking<Unit> {
            val taskId = aCardAt()
            driver.failOn(occurrence = 2) { it.contains("UPDATE task_stages") }

            counter.start()
            assertFailsWith<SQLiteException> { moveWholePipeline(taskId) }
            val ran =
                counter
                    .stop()
                    .filterNot { "room_table_modification" in it.lowercase() }
                    .groupingBy { statement ->
                        val head = statement.trimStart().uppercase()
                        when {
                            head.startsWith("UPDATE") && "TASK_STAGES" in head -> "UPDATE task_stages"
                            head.startsWith("UPDATE") && "TASKS" in head -> "UPDATE tasks"
                            head.startsWith("INSERT") -> "INSERT"
                            else -> "other"
                        }
                    }.eachCount()

            driver.disarm()
            // Two steps were attempted and the second is the one that would not
            // go; the task was never reached at all. And none of it is there.
            assertEquals(2, ran["UPDATE task_stages"], "the transaction ran on past the statement that failed: $ran")
            assertEquals(null, ran["UPDATE tasks"], "the task was written after a step had already failed")
            assertEquals(null, ran["INSERT"], "a failed save wrote a row of its own")
            assertEquals(listOf(15, 10, 5), pipelineOf(taskId))
        }
}

/** What the pipelines here count up to. */
private const val TOTAL = 20
