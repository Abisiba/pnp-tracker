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
import dev.pnptracker.domain.pools.PoolModel
import dev.pnptracker.domain.pools.poolModelOf
import dev.pnptracker.domain.tasks.StageSnapshot
import dev.pnptracker.domain.tasks.TaskProgressException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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

    // ---------------------------------------- what a finish does, and what it costs

    /**
     * Runs one action against a task and says what statements it really took.
     *
     * [setUp] happens before the count starts, so what is measured is the action
     * itself and never the fixture it needed.
     */
    private suspend fun costOf(
        taskId: EntityId,
        setUp: suspend (EntityId) -> Unit = {},
        action: suspend (EntityId) -> Unit,
    ): Map<String, Int> {
        setUp(taskId)
        driver.start()
        action(taskId)
        return tally(driver.stop())
    }

    @Test
    fun `a finish with nothing owed writes no event, whatever colours the task is made in`() =
        runBlocking<Unit> {
            val plain = task("Tek")
            val coloured = task("Üç", colors = palette(3))

            val forPlain = costOf(plain) { progress.completeTask(it, clock, ids) }
            val forColoured = costOf(coloured) { progress.completeTask(it, clock, ids) }

            assertEquals(forPlain, forColoured, "a finish was paid for one colour at a time")
            listOf(plain to forPlain, coloured to forColoured).forEach { (id, counted) ->
                assertEquals(1, counted["UPDATE tasks"], "a finish wrote the task more than once: $counted")
                assertEquals(null, counted["INSERT progress_events"], "a finish with nothing owed wrote an event")
                assertEquals(0, counted.filterKeys { "task_colors" in it }.values.sum(), "a finish touched the colours")
                assertTrue(progress.progressEventsOfTask(id).isEmpty(), "an untroubled task was given a history")
                val finished = checkNotNull(progress.taskById(id))
                assertTrue(finished.isCompleted, "the task was not finished")
                assertEquals(0, finished.currentMissingQuantity)
                assertTrue(finished.primaryBatchCompleted, "a finished 3D task was left without its print run")
            }
        }

    @Test
    fun `a finish with pieces owed settles them in one event, whatever colours the task is made in`() =
        runBlocking<Unit> {
            val plain = task("Tek")
            val coloured = task("Üç", colors = palette(3))

            val forPlain =
                costOf(plain, setUp = { progress.reportFailure(ids.newId(), it, 3, clock) }) {
                    progress.completeTask(it, clock, ids)
                }
            val forColoured =
                costOf(coloured, setUp = { progress.reportFailure(ids.newId(), it, 3, clock) }) {
                    progress.completeTask(it, clock, ids)
                }

            assertEquals(forPlain, forColoured, "settling was paid for one colour at a time")
            listOf(plain to forPlain, coloured to forColoured).forEach { (id, counted) ->
                assertEquals(1, counted["UPDATE tasks"], "a finish wrote the task more than once: $counted")
                assertEquals(1, counted["INSERT progress_events"], "settling was not one event: $counted")
                assertEquals(0, counted.filterKeys { "task_colors" in it }.values.sum(), "a finish touched the colours")
                val settling = progress.progressEventsOfTask(id).last()
                // What was owed is what is made good, exactly: PLAN 6.4 will not
                // have the finished mark stand over a counter that still says work
                // is left, and settling for anything else would leave one of the two
                // wrong.
                assertEquals(3, settling.quantity, "the settling event was not for what was owed")
                assertEquals(3L, progress.failureTotalOf(id), "finishing erased the history of having failed")
                assertEquals(3L, progress.resolvedTotalOf(id))
                val finished = checkNotNull(progress.taskById(id))
                assertTrue(finished.isCompleted)
                assertEquals(0, finished.currentMissingQuantity)
            }
        }

    // ------------------------------------------------- what a pool card is told

    @Test
    fun `a pool card carries a failure total the database counted past what an Int holds`() =
        runBlocking<Unit> {
            val taskId = task("Çok", quantity = 40)
            // Three of the largest amount the form will take. PLAN 6.4 caps what
            // a task owes at what it needs and leaves the failure total uncapped,
            // so this is a number the application can really reach.
            repeat(3) { progress.reportFailure(ids.newId(), taskId, 999_999_999, clock) }
            val counted = 3L * 999_999_999L
            assertTrue(counted > Int.MAX_VALUE, "the fixture never left what an Int holds")

            assertEquals(counted, progress.failureTotalOf(taskId), "the database did not add it up as asked")
            val projected =
                database
                    .poolDao()
                    .observeFailureTotalsOfPool(PoolType.THREE_D)
                    .first()
                    .single()
            assertEquals(counted, projected.failureTotal, "the projection reported a different number")
            val shown =
                pools
                    .observePool(PoolType.THREE_D)
                    .first()
                    .tasks
                    .single()
            assertEquals(counted, shown.failureTotal, "the pool card reported a different number")
            assertTrue(shown.failureTotal > 0, "a card with everything wrong showed nothing wrong")
            // The debt is a different number and stays inside the task's total.
            assertEquals(40, shown.currentMissingQuantity, "what is owed went past what the task needs")
        }

    // ------------------------------------------------------- giving up half way

    @Test
    fun `giving up on a coroutine is not turned into something to show the user`() =
        runBlocking<Unit> {
            val taskId = task("Vazgeçilen")
            val store = TaskProgressStore(progress, clock)
            var returned: TaskProgressOutcome? = null
            val apart = CoroutineScope(Job())
            val attempt =
                apart.launch {
                    // Cancelled before it asks, so the first place the write
                    // suspends is a place it must give up at.
                    coroutineContext.job.cancel()
                    returned = store.reportFailure(ids.newId(), taskId, 1)
                }
            attempt.join()

            assertNull(returned, "giving up came back as an ordinary answer instead of travelling on")
            assertTrue(attempt.isCancelled, "the coroutine was left looking as though it had finished")
            assertTrue(progress.progressEventsOfTask(taskId).isEmpty(), "a report was written after giving up")
        }

    // ------------------------------------- what an action costs, in every setting

    /**
     * One progress action, named, with whatever fixture it needs first.
     *
     * Kept as a list rather than measured one by one so the same seven actions
     * go through every setting — a crowd of other tasks, a long history, a
     * handful of colours — and none of them is quietly left out of one of them.
     */
    private class ProgressAction(
        val name: String,
        val setUp: suspend (EntityId) -> Unit = {},
        val run: suspend (EntityId) -> Unit,
    )

    private val progressActions: List<ProgressAction> =
        listOf(
            ProgressAction("complete owing nothing") { progress.completeTask(it, clock, ids) },
            ProgressAction(
                name = "complete owing something",
                setUp = { progress.reportFailure(ids.newId(), it, 2, clock) },
                run = { progress.completeTask(it, clock, ids) },
            ),
            ProgressAction(
                name = "reopen",
                setUp = { progress.completeTask(it, clock, ids) },
                run = { progress.reopenTask(it, clock) },
            ),
            ProgressAction("report") { progress.reportFailure(ids.newId(), it, 1, clock) },
            ProgressAction(
                name = "resolve",
                setUp = { progress.reportFailure(ids.newId(), it, 2, clock) },
                run = { progress.resolveShortage(ids.newId(), it, 1, clock) },
            ),
            ProgressAction(
                name = "a finish with nothing to do",
                setUp = { progress.completeTask(it, clock, ids) },
                run = { progress.completeTask(it, clock, ids) },
            ),
            ProgressAction("a reopen with nothing to do") { progress.reopenTask(it, clock) },
        )

    /** What every one of [progressActions] costs in one setting, named by action. */
    private suspend fun costOfEachAction(
        label: String,
        colours: List<EntityId> = emptyList(),
        history: Int = 0,
    ): Map<String, Map<String, Int>> =
        progressActions.associate { action ->
            val id = task("$label ${action.name}", colors = colours, quantity = 1000)
            if (history > 0) {
                // A long history and an outstanding debt are two different
                // things, and only the first is being varied here: the reports
                // are all made good again and the task put back to work, so what
                // is left is the length of the history alone.
                repeat(history) { progress.reportFailure(ids.newId(), id, 1, clock) }
                progress.resolveShortage(ids.newId(), id, history, clock)
                progress.reopenTask(id, clock)
            }
            action.name to costOf(id, action.setUp, action.run)
        }

    @Test
    fun `no progress action grows with the number of tasks around it`() =
        runBlocking<Unit> {
            val alone = costOfEachAction("Yalnız")
            repeat(42) { index -> task("Kalabalık $index") }
            val crowded = costOfEachAction("Kalabalıkta")

            assertEquals(alone, crowded, "an action grew with the tasks around it")
            // Named rather than only compared, so a change that made both sides
            // cost more equally would still be seen.
            assertEquals(
                mapOf(
                    "SELECT tasks" to 2,
                    "SELECT task_stages" to 2,
                    "SELECT progress_events" to 2,
                    "SELECT other" to 1,
                    "UPDATE tasks" to 1,
                ),
                alone.getValue("complete owing nothing"),
            )
            assertEquals(mapOf("SELECT tasks" to 1), alone.getValue("a finish with nothing to do"))
            assertEquals(mapOf("SELECT tasks" to 1), alone.getValue("a reopen with nothing to do"))
        }

    @Test
    fun `no progress action grows with the history already behind the task`() =
        runBlocking<Unit> {
            // The totals are added up in the database, so a long history is one
            // read however long it is. A read per event would make an old task
            // slower to work on than a new one, which PLAN 16 will not have.
            val fresh = costOfEachAction("Yeni")
            val busy = costOfEachAction("Geçmişli", history = 42)

            assertEquals(fresh, busy, "an action grew with the history behind it")
        }

    @Test
    fun `no progress action grows with the colours the task is made in`() =
        runBlocking<Unit> {
            val plain = costOfEachAction("Tek")
            val coloured = costOfEachAction("Üç", colours = palette(3))

            assertEquals(plain, coloured, "an action grew with the colours of the task")
            coloured.forEach { (name, counted) ->
                assertEquals(0, counted.filterKeys { "task_colors" in it }.values.sum(), "$name touched the colours")
            }
        }

    // ------------------------------------------- what a pipeline change costs

    private suspend fun cardTask(
        name: String,
        colors: List<EntityId> = emptyList(),
        history: Int = 0,
    ): EntityId {
        val id = task(name, pool = PoolType.CARD, quantity = 1000, colors = colors)
        repeat(history) {
            progress.reportFailure(ids.newId(), id, 1, clock)
            progress.resolveShortage(ids.newId(), id, 1, clock)
        }
        return id
    }

    private suspend fun pipelineCost(
        taskId: EntityId,
        targets: Map<ProductionStage, Int>,
    ): Map<String, Int> {
        driver.start()
        progress.setStageQuantities(taskId, targets, clock)
        return tally(driver.stop())
    }

    private val threeSteps
        get() =
            mapOf(
                ProductionStage.PRINT to 30,
                ProductionStage.LAMINATE to 20,
                ProductionStage.CUT to 10,
            )

    @Test
    fun `changing a pipeline costs the same however many tasks are around it`() =
        runBlocking<Unit> {
            val alone = pipelineCost(cardTask("Yalnız"), threeSteps)
            repeat(42) { index -> cardTask("Kalabalık $index") }
            val crowded = pipelineCost(cardTask("Kalabalıkta"), threeSteps)

            assertEquals(alone, crowded, "a pipeline grew with the tasks around it")
            assertEquals(3, alone["UPDATE task_stages"], "three steps moved and were not three writes")
            assertEquals(1, alone["UPDATE tasks"], "the task was written more than once")
            assertEquals(null, alone["INSERT progress_events"], "counting a step wrote an event")
            assertEquals(0, alone.filterKeys { "task_colors" in it }.values.sum(), "a pipeline touched the colours")
        }

    @Test
    fun `changing a pipeline costs the same however long the history behind it`() =
        runBlocking<Unit> {
            val fresh = pipelineCost(cardTask("Yeni"), threeSteps)
            val busy = pipelineCost(cardTask("Geçmişli", history = 42), threeSteps)

            assertEquals(fresh, busy, "a pipeline grew with the history behind it")
        }

    @Test
    fun `changing a pipeline costs the same however many colours the task is made in`() =
        runBlocking<Unit> {
            val plain = pipelineCost(cardTask("Tek"), threeSteps)
            val coloured = pipelineCost(cardTask("Üç", colors = palette(3)), threeSteps)

            assertEquals(plain, coloured, "a pipeline grew with the colours of the task")
            assertEquals(0, coloured.filterKeys { "task_colors" in it }.values.sum())
        }

    @Test
    fun `a board pipeline costs what a card pipeline does`() =
        runBlocking<Unit> {
            val card = pipelineCost(cardTask("Kart"), threeSteps)
            val boardId = task("Tahta", pool = PoolType.BOARD, quantity = 1000)
            val board =
                pipelineCost(
                    boardId,
                    mapOf(
                        ProductionStage.PRINT to 30,
                        ProductionStage.GLUE to 20,
                        ProductionStage.CUT to 10,
                    ),
                )

            assertEquals(card, board, "one pool's pipeline costs more than the other's")
        }

    @Test
    fun `only the steps that moved are written, and a pipeline that moved none writes nothing`() =
        runBlocking<Unit> {
            val taskId = cardTask("Token")
            progress.setStageQuantities(taskId, threeSteps, clock)

            val one = pipelineCost(taskId, mapOf(ProductionStage.CUT to 15))
            assertEquals(1, one["UPDATE task_stages"], "moving one step wrote more than one: $one")

            val none = pipelineCost(taskId, threeSteps + (ProductionStage.CUT to 15))
            assertEquals(0, none.filterKeys { it.startsWith("UPDATE") }.values.sum(), "a no-op wrote: $none")
            assertEquals(0, none.filterKeys { it.startsWith("INSERT") }.values.sum())
        }

    /** The whole pipeline as it stands, plus what it counts up to. */
    private suspend fun snapshotOf(taskId: EntityId): StageSnapshot =
        StageSnapshot(
            requiredQuantity = checkNotNull(progress.taskById(taskId)).requiredQuantity,
            stages = progress.stagesOfTask(taskId).associate { it.stage to it.completedQuantity },
        )

    /** What one refused save costs, and what it leaves behind. */
    private suspend fun refusedCost(
        taskId: EntityId,
        targets: Map<ProductionStage, Int>,
        expected: StageSnapshot,
    ): Map<String, Int> {
        driver.start()
        assertFailsWith<TaskProgressException> {
            progress.setStageQuantities(taskId, targets, clock, expected = expected)
        }
        return tally(driver.stop())
    }

    @Test
    fun `a save refused for a pipeline that moved writes nothing at all`() =
        runBlocking<Unit> {
            val taskId = cardTask("Kayan")
            progress.setStageQuantities(taskId, threeSteps, clock)
            val opened = snapshotOf(taskId)
            progress.setStageQuantities(taskId, mapOf(ProductionStage.CUT to 15), clock)

            val cost = refusedCost(taskId, mapOf(ProductionStage.PRINT to 40), opened)

            assertEquals(0, cost.filterKeys { it.startsWith("UPDATE") }.values.sum(), "a refusal wrote: $cost")
            assertEquals(0, cost.filterKeys { it.startsWith("INSERT") }.values.sum(), "a refusal wrote: $cost")
            assertEquals(0, cost.filterKeys { "task_colors" in it }.values.sum(), "a refusal read the colours")
        }

    @Test
    fun `a save refused for a total that moved writes nothing and does not grow with the pool`() =
        runBlocking<Unit> {
            val alone = cardTask("Yalnız")
            progress.setStageQuantities(alone, threeSteps, clock)
            val openedAlone = snapshotOf(alone)
            retotal(alone, 2000)
            val single = refusedCost(alone, mapOf(ProductionStage.PRINT to 40), openedAlone)

            repeat(42) { index -> cardTask("Kalabalık $index") }
            val three = palette(3)
            val crowded = cardTask("Kalabalıkta", colors = three, history = 42)
            progress.setStageQuantities(crowded, threeSteps, clock)
            val openedCrowded = snapshotOf(crowded)
            retotal(crowded, 2000, three)
            val many = refusedCost(crowded, mapOf(ProductionStage.PRINT to 40), openedCrowded)

            assertEquals(single, many, "noticing the total had moved grew with the tasks, history or colours")
            assertEquals(0, single.filterKeys { it.startsWith("UPDATE") }.values.sum(), "a refusal wrote: $single")
            assertEquals(0, single.filterKeys { "task_colors" in it }.values.sum(), "a refusal read the colours")
        }

    /** Gives a task a different total, through the form that really does it. */
    private suspend fun retotal(
        taskId: EntityId,
        total: Int,
        colorIds: List<EntityId> = emptyList(),
    ) {
        val task = checkNotNull(progress.taskById(taskId))
        database.taskEditDao().editTask(
            taskId = taskId,
            name = task.name,
            colorIds = colorIds,
            requiredQuantity = total,
            notes = task.notes,
            trackingMode = task.trackingMode,
            flags = null,
            clock = clock,
        )
    }

    @Test
    fun `a task of three colours is one card with one pipeline`() =
        runBlocking<Unit> {
            val three = palette(3)
            val taskId = task("Üç renkli", pool = PoolType.CARD, quantity = 20, colors = three)
            progress.setStageQuantities(
                taskId,
                mapOf(
                    ProductionStage.PRINT to 20,
                    ProductionStage.LAMINATE to 20,
                    ProductionStage.CUT to 20,
                ),
                clock,
            )

            // The card pool is a flat list (PLAN 12.11), so a task made in three
            // colours is one card and not three, and finishing it takes that one
            // card away.
            assertTrue(idsIn(PoolType.CARD).isEmpty(), "a finished card task stayed in its pool")
            assertEquals(3, progress.stagesOfTask(taskId).size, "the pipeline was multiplied by the colours")
            assertEquals(20, checkNotNull(progress.taskById(taskId)).requiredQuantity, "the total was multiplied")
            assertTrue(progress.progressEventsOfTask(taskId).isEmpty())

            progress.reopenTask(taskId, clock)

            assertEquals(listOf(taskId), idsIn(PoolType.CARD), "a reopened card task did not come back once")
        }

    // ---------------------------- what the pools add up to, read from Room

    /** Two thirds of the way to what an Int holds, so two of them pass it. */
    private val huge = 1_500_000_000

    @Test
    fun `a colour group read out of the database counts past what an Int holds`() =
        runBlocking<Unit> {
            val grey = palette(1).single()
            task("Büyük bir", quantity = huge, colors = listOf(grey))
            task("Büyük iki", quantity = huge, colors = listOf(grey))

            val sections = threeDSections()

            // Measured before the fix this was -1294967296: two real rows, a
            // real query and a real projection, and a group of three billion
            // shown to the user as a negative number.
            assertEquals(3_000_000_000L, sections.singleColorGroups.single().requiredTotal)
        }

    @Test
    fun `the section with no colour counts out of the database just as wide`() =
        runBlocking<Unit> {
            val a = task("Renksiz bir", quantity = huge)
            val b = task("Renksiz iki", quantity = huge)
            progress.reportFailure(ids.newId(), a, huge, clock)
            progress.reportFailure(ids.newId(), b, huge, clock)

            val awaiting = threeDSections().awaitingColor

            assertEquals(3_000_000_000L, awaiting.requiredTotal)
            assertEquals(3_000_000_000L, awaiting.missingTotal)
            assertEquals(3_000_000_000L, awaiting.failureTotal)
            assertTrue(awaiting.missingTotal <= awaiting.requiredTotal, "a section owed more than it needs")
        }

    @Test
    fun `forty two large tasks read out of the database add up to what they are`() =
        runBlocking<Unit> {
            val grey = palette(1).single()
            repeat(42) { index -> task("Büyük $index", quantity = huge, colors = listOf(grey)) }

            assertEquals(63_000_000_000L, threeDSections().singleColorGroups.single().requiredTotal)
        }

    @Test
    fun `what the history holds and what the task owes stay different numbers`() =
        runBlocking<Unit> {
            val grey = palette(1).single()
            val taskId = task("Çok hatalı", quantity = 10, colors = listOf(grey))
            repeat(3) { progress.reportFailure(ids.newId(), taskId, 2_000_000_000, clock) }

            val group = threeDSections().singleColorGroups.single()
            assertEquals(10L, group.requiredTotal)
            assertEquals(10L, group.missingTotal, "the counter went past the total")
            assertEquals(6_000_000_000L, group.failureTotal, "the history was narrowed to the counter")
        }

    @Test
    fun `a large amount costs a report no more queries than a small one does`() =
        runBlocking<Unit> {
            val small = task("Küçük", quantity = Int.MAX_VALUE)
            driver.start()
            progress.reportFailure(ids.newId(), small, 1, clock)
            val cheap = tally(driver.stop())

            val large = task("Büyük", quantity = Int.MAX_VALUE)
            driver.start()
            progress.reportFailure(ids.newId(), large, Int.MAX_VALUE, clock)
            val dear = tally(driver.stop())

            assertEquals(cheap, dear, "how big the amount is changed what the transaction did")
            assertEquals(1, dear["INSERT progress_events"], "one report was written as several events")
            assertEquals(0, dear.filterKeys { "task_colors" in it }.values.sum(), "a report touched the colours")
        }

    @Test
    fun `reading a pool costs the same whether its totals are large or small`() =
        runBlocking<Unit> {
            val grey = palette(1).single()
            repeat(3) { index -> task("Küçük $index", quantity = 10, colors = listOf(grey)) }
            driver.start()
            threeDSections()
            val cheap = queriesIn(driver.stop())

            repeat(3) { index -> task("Büyük $index", quantity = huge, colors = listOf(grey)) }
            driver.start()
            val sections = threeDSections()
            val dear = queriesIn(driver.stop())

            assertEquals(cheap, dear, "counting wide cost the pool an extra query")
            assertEquals(mapOf("SELECT tasks" to 1, "SELECT task_colors" to 1, "SELECT progress_events" to 1), dear)
            // Three small tasks and three large ones, added exactly and well past
            // what an Int holds.
            assertEquals(4_500_000_030L, sections.singleColorGroups.single().requiredTotal)
        }

    /**
     * What a read really asked the database, without the settings a connection
     * puts to it when it is opened — a pool read opens its own, so those say
     * nothing about the reading itself.
     */
    private fun queriesIn(recorded: List<String>): Map<String, Int> = tally(recorded).filterKeys { !it.startsWith("PRAGMA") }

    /** The 3D pool as the screen lays it out, read through the real projection. */
    private suspend fun threeDSections() = (poolModelOf(pools.observePool(PoolType.THREE_D).first()) as PoolModel.ThreeD).sections
}
