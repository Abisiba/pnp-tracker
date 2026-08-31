package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The words a database uses to open and close a transaction, rather than to work in one. */
private val TRANSACTION_CONTROL = setOf("BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE")

/** Hands out one fixed moment, so a fixture is the same every time it is built. */
private class FlatClock(
    private val moment: Instant,
) : Clock {
    override fun now(): Instant = moment
}

/**
 * What finishing a task, and saying what it owes, does to the pools it shows in.
 *
 * The pools are a reflection (PLAN 12.10), so nothing here writes to them: what
 * is being checked is that changing the one task changes every reflection of it
 * at once, and that a task made in three colours is still one task — one write,
 * one event, and one identity in three colour groups.
 *
 * Counted at the driver as well as read back, because the cost of a single user
 * action must not follow the number of colours a task has, the number of things
 * already recorded against it, or the number of other tasks around it.
 */
class TaskProgressReflectionTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false
    private val moment = Instant.fromEpochMilliseconds(1_781_000_000_000)
    private val clock = FlatClock(moment)
    private val ids = IdGenerator.Random

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = driver).open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val progress get() = database.taskProgressDao()

    private val pools get() = PoolStore(database.poolDao())

    private suspend fun task(
        name: String,
        pool: PoolType = PoolType.THREE_D,
        quantity: Int? = 40,
        colors: List<EntityId> = emptyList(),
        game: String = "Harmonies $name",
    ): EntityId {
        val tracking =
            when (pool) {
                PoolType.THREE_D -> TrackingMode.THREE_D_BATCH
                PoolType.CARD, PoolType.BOARD -> TrackingMode.PIPELINE
                PoolType.SPECIAL -> TrackingMode.COUNTED
            }
        val created =
            insertGameCellAndTask(database, gameName = game, columnType = CellColumnType.of(pool)) {
                aTask(poolType = pool, trackingMode = tracking, name = name, requiredQuantity = quantity)
            }
        colors.forEachIndexed { slot, id -> database.taskColorDao().insert(TaskColorEntity(created.id, id, slot)) }
        return created.id
    }

    private suspend fun palette(count: Int): List<EntityId> =
        database
            .colorDao()
            .allColors()
            .take(count)
            .map { it.id }

    private suspend fun idsIn(pool: PoolType): List<EntityId> =
        pools
            .observePool(pool)
            .first()
            .tasks
            .map { it.taskId }

    /**
     * Every statement really run, named by what it touched.
     *
     * Room's own bookkeeping is left out twice over: the change log it keeps in
     * the same connection, and the transaction control around a write. Neither
     * is the application asking for anything, and the second is not even stable
     * — the pool may open a nested savepoint or lean on one already there, which
     * says nothing about the work being done.
     */
    private fun tally(recorded: List<String>): Map<String, Int> =
        recorded
            .filterNot { "room_table_modification" in it.lowercase() }
            .filterNot { statement ->
                statement.trimStart().uppercase().substringBefore(' ') in TRANSACTION_CONTROL
            }.groupingBy { statement ->
                val head = statement.trimStart().uppercase()
                val verb = head.substringBefore(' ')
                val table =
                    when {
                        "TASK_COLORS" in head -> "task_colors"
                        "TASK_STAGES" in head -> "task_stages"
                        "PROGRESS_EVENTS" in head -> "progress_events"
                        "TASKS" in head -> "tasks"
                        else -> "other"
                    }
                "$verb $table"
            }.eachCount()

    // ------------------------------------------------ leaving and coming back

    @Test
    fun `a finished task leaves its pool and a reopened one comes back`() =
        runBlocking<Unit> {
            val red = palette(1).single()
            val taskId = task("Token", colors = listOf(red))
            val other = task("Diğer", colors = listOf(red))
            progress.completePrimaryBatch(taskId, clock)

            assertTrue(taskId !in idsIn(PoolType.THREE_D), "a finished task stayed in the active pool")
            assertTrue(other in idsIn(PoolType.THREE_D), "another task went with it")

            progress.reopenTask(taskId, clock)

            assertTrue(taskId in idsIn(PoolType.THREE_D), "a reopened task did not come back")
        }

    @Test
    fun `a shortage brings a finished task back into every colour it is made in`() =
        runBlocking<Unit> {
            // PLAN 6.3: a report on a finished task returns it to the active
            // pool, and on a task of several colours it returns to all of them —
            // because it is one task, and one write is all it takes.
            val three = palette(3)
            val taskId = task("Yarasa", quantity = 10, colors = three)
            progress.completePrimaryBatch(taskId, clock)
            assertTrue(taskId !in idsIn(PoolType.THREE_D))

            assertTrue(progress.reportFailure(ids.newId(), taskId, 2, clock))

            val reflected = pools.observePool(PoolType.THREE_D).first().tasks
            assertEquals(listOf(taskId), reflected.map { it.taskId }, "one task came back more than once")
            val task = reflected.single()
            assertEquals(three, task.colors.map { it.colorId }, "it came back in fewer colours than it is made in")
            assertEquals(2, task.currentMissingQuantity)
            assertEquals(10, task.requiredQuantity, "the total was multiplied by the colours")
            assertEquals(2, task.failureTotal, "the report was counted once per colour")
        }

    @Test
    fun `one report is one event however many colours the task has`() =
        runBlocking<Unit> {
            val three = palette(3)
            val taskId = task("Yarasa", quantity = 10, colors = three)

            progress.reportFailure(ids.newId(), taskId, 2, clock)

            assertEquals(1, progress.progressEventsOfTask(taskId).size)
            assertEquals(3, database.taskColorDao().colorsOfTask(taskId).size, "the colours changed under the report")
        }

    @Test
    fun `finishing a task of three colours takes it out of all three at once`() =
        runBlocking<Unit> {
            val three = palette(3)
            val taskId = task("Yarasa", quantity = 10, colors = three)
            val single = task("Tekil", colors = listOf(three.first()))
            progress.completePrimaryBatch(taskId, clock)

            assertTrue(progress.completeTask(taskId, clock, ids) || taskId !in idsIn(PoolType.THREE_D))

            assertEquals(listOf(single), idsIn(PoolType.THREE_D))
            assertEquals(3, database.taskColorDao().colorsOfTask(taskId).size, "finishing took a colour off the task")
        }

    @Test
    fun `a card task's steps are untouched by a shortage reported against it`() =
        runBlocking<Unit> {
            val cardId = task("Bird Cards", pool = PoolType.CARD, quantity = 20)
            progress.setStageQuantity(cardId, ProductionStage.PRINT, 20, clock)

            progress.reportFailure(ids.newId(), cardId, 3, clock, stage = ProductionStage.LAMINATE)

            val reflected =
                pools
                    .observePool(PoolType.CARD)
                    .first()
                    .tasks
                    .single()
            assertEquals(listOf(20, 0, 0), reflected.stages.map { it.completedQuantity })
            assertEquals(3, reflected.currentMissingQuantity)
        }

    @Test
    fun `a special task finished by hand leaves the special pool`() =
        runBlocking<Unit> {
            val specialId = task("8 özel zar", pool = PoolType.SPECIAL, quantity = 8)

            progress.completeTask(specialId, clock, ids)

            assertEquals(emptyList(), idsIn(PoolType.SPECIAL))
        }

    // ---------------------------------------------------- what an action costs

    @Test
    fun `finishing a task costs the same whatever it is made of`() =
        runBlocking<Unit> {
            val three = palette(3)
            val plain = task("Tek", quantity = 40)
            val coloured = task("Üç", quantity = 40, colors = three)
            progress.completePrimaryBatch(plain, clock)
            progress.completePrimaryBatch(coloured, clock)
            progress.reportFailure(ids.newId(), plain, 1, clock)
            progress.reportFailure(ids.newId(), coloured, 1, clock)

            driver.start()
            progress.completeTask(plain, clock, ids)
            val forPlain = tally(driver.stop())

            driver.start()
            progress.completeTask(coloured, clock, ids)
            val forColoured = tally(driver.stop())

            assertEquals(forPlain, forColoured, "the colours of a task were paid for one at a time")
            assertEquals(0, forColoured.filterKeys { "task_colors" in it }.values.sum(), "a finish touched the colours")
            assertEquals(1, forColoured["UPDATE tasks"], "a finish wrote the task more than once")
        }

    @Test
    fun `a report costs the same with one other task about as with forty two`() =
        runBlocking<Unit> {
            val three = palette(3)
            val alone = task("Yalnız", quantity = 100, colors = three)
            driver.start()
            progress.reportFailure(ids.newId(), alone, 1, clock)
            val forOne = tally(driver.stop())

            repeat(42) { index -> task("Kalabalık $index", quantity = 100, colors = three) }
            val crowded = task("Kalabalıkta", quantity = 100, colors = three)
            driver.start()
            progress.reportFailure(ids.newId(), crowded, 1, clock)
            val forFortyTwo = tally(driver.stop())

            assertEquals(forOne, forFortyTwo, "a report grew with the number of tasks around it")
            assertEquals(1, forOne["INSERT progress_events"], "a report wrote more than one event")
            assertEquals(1, forOne["UPDATE tasks"], "a report wrote the task more than once")
            assertEquals(0, forOne.filterKeys { "task_colors" in it }.values.sum(), "a report touched the colours")
        }

    @Test
    fun `a report costs the same behind forty two of them as behind none`() =
        runBlocking<Unit> {
            // The totals are summed in the database, so a long history is one
            // read however long it is. A read per event would make an old task
            // slower to work on than a new one, which PLAN 16 will not have.
            val fresh = task("Yeni", quantity = 1000)
            driver.start()
            progress.reportFailure(ids.newId(), fresh, 1, clock)
            val onEmpty = tally(driver.stop())

            val busy = task("Geçmişli", quantity = 1000)
            repeat(42) { progress.reportFailure(ids.newId(), busy, 1, clock) }
            driver.start()
            progress.reportFailure(ids.newId(), busy, 1, clock)
            val onBusy = tally(driver.stop())

            assertEquals(onEmpty, onBusy, "a report grew with the history behind it")
        }

    @Test
    fun `making good costs no more than reporting did`() =
        runBlocking<Unit> {
            val taskId = task("Token", quantity = 40, colors = palette(3))
            progress.reportFailure(ids.newId(), taskId, 5, clock)

            driver.start()
            progress.resolveShortage(ids.newId(), taskId, 2, clock)
            val counted = tally(driver.stop())

            assertEquals(1, counted["INSERT progress_events"])
            assertEquals(1, counted["UPDATE tasks"])
            assertEquals(0, counted.filterKeys { "task_colors" in it }.values.sum())
            assertEquals(0, counted.filterKeys { it.startsWith("UPDATE task_stages") }.values.sum())
        }

    @Test
    fun `a finish or a reopen that changes nothing runs no write at all`() =
        runBlocking<Unit> {
            val taskId = task("Token", quantity = 40)
            progress.completePrimaryBatch(taskId, clock)

            driver.start()
            progress.completeTask(taskId, clock, ids)
            progress.reopenTask(taskId, clock)
            progress.reopenTask(taskId, clock)
            val counted = tally(driver.stop())

            // One reopen really happens; the finish and the second reopen have
            // nothing to do, and doing nothing costs one look and no write.
            assertEquals(1, counted["UPDATE tasks"], "a no-op wrote the task: $counted")
            assertEquals(0, counted.filterKeys { it.startsWith("INSERT") }.values.sum())
        }
}
