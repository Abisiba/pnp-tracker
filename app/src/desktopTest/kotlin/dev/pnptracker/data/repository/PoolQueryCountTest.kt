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
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a pool costs to read, counted at the driver.
 *
 * The number that matters is not how large it is but whether it grows. A pool
 * with forty two tasks in it must cost the same reads as one with a single task,
 * and a task in three colours must not cost three of anything — PLAN 16 asks the
 * application to stay usable with a thousand tasks, and a read per task is the
 * one shape that cannot.
 *
 * Counted through the driver the database is really opened with, so what is
 * measured is what SQLite was actually asked, not what a DAO was called with.
 */
class PoolQueryCountTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false

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

    /** The tables a pool reads. */
    private val poolTables = listOf("tasks", "task_colors", "task_stages", "progress_events", "colors")

    private fun touchesAPoolTable(sql: String): Boolean {
        val text = sql.lowercase()
        // Room keeps its own bookkeeping in the same connection: a trigger per
        // watched table when a Flow is first collected, and a poll of its change
        // log afterwards. Neither is the pool asking anything, and both would
        // otherwise be counted because they name the tables they watch.
        if ("room_table_modification" in text) return false
        return poolTables.any { it in text }
    }

    /**
     * The queries a pool really ran, with Room's bookkeeping left out.
     *
     * Sorted, because the reads are separate streams gathered together and the
     * order they answer in is the coroutines' business rather than the pool's.
     * What is being asserted is which queries ran and how many, and neither of
     * those depends on which arrived first.
     */
    private fun statementsOf(recorded: List<String>): List<String> =
        recorded
            .filter { touchesAPoolTable(it) && it.trimStart().uppercase().startsWith("SELECT") }
            .sorted()

    /**
     * Which query one statement is.
     *
     * Named rather than matched on its whole text, so what a count means is
     * readable in the assertion instead of in a wall of SQL. The summary is
     * asked for first because it too reads `tasks`, and would otherwise answer
     * to the name of the query that lists a pool.
     */
    private fun nameOf(sql: String): String {
        val text = sql.lowercase()
        return when {
            "sum(case when tasks.is_completed" in text -> "navigation_summary"
            "sum(progress_events.quantity)" in text -> "failure_totals"
            "from task_colors" in text -> "pool_colors"
            "from task_stages" in text -> "pool_stages"
            "from tasks" in text -> "active_pool_tasks"
            else -> "unexpected(" + sql.trim().take(40) + ")"
        }
    }

    /**
     * How many times each query really ran.
     *
     * A count and not a set. Two runs of one query and one run of it are the
     * same set and different maps, and the difference between them is exactly
     * what an N+1 looks like: the same SQL, over and over. Order is left out
     * because the reads are separate streams gathered together and which of
     * them answers first is the coroutines' business, but multiplicity is not.
     */
    private fun frequenciesOf(recorded: List<String>): Map<String, Int> = statementsOf(recorded).groupingBy { nameOf(it) }.eachCount()

    private suspend fun fill(
        poolType: PoolType,
        size: Int,
        colorsEach: Int,
    ) {
        val tracking =
            when (poolType) {
                PoolType.THREE_D -> TrackingMode.THREE_D_BATCH
                PoolType.CARD, PoolType.BOARD -> TrackingMode.PIPELINE
                PoolType.SPECIAL -> TrackingMode.COUNTED
            }
        val palette =
            database
                .colorDao()
                .allColors()
                .take(colorsEach)
                .map { it.id }
        repeat(size) { index ->
            val created =
                insertGameCellAndTask(database, gameName = "Oyun $index", columnType = CellColumnType.of(poolType)) {
                    aTask(poolType = poolType, trackingMode = tracking, name = "Görev $index")
                }
            palette.forEachIndexed { slot, colorId ->
                database.taskColorDao().insert(TaskColorEntity(created.id, colorId, slot))
            }
        }
    }

    /**
     * The statements one pool read runs, on its own database.
     *
     * Room creates and seeds a database the first time it is used, so a warm-up
     * read comes first and the measurement starts after it. Otherwise the twelve
     * base colours going in would be counted as the cost of opening a pool.
     */
    private fun readsToOpen(
        poolType: PoolType,
        size: Int,
        colorsEach: Int = 1,
    ): List<String> =
        runBlocking {
            fill(poolType, size, colorsEach)
            database.colorDao().allColors()

            driver.start()
            PoolStore(database.poolDao()).observePool(poolType).first()
            statementsOf(driver.stop())
        }

    private fun frequenciesToOpen(
        poolType: PoolType,
        size: Int,
        colorsEach: Int = 1,
    ): Map<String, Int> =
        runBlocking {
            fill(poolType, size, colorsEach)
            database.colorDao().allColors()

            driver.start()
            PoolStore(database.poolDao()).observePool(poolType).first()
            frequenciesOf(driver.stop())
        }

    private fun freshDatabase(): PoolQueryCountTest = this

    // -------------------------------------------- every query, counted by name

    @Test
    fun `the 3D pool runs each of its three queries exactly once at either size`() {
        val expected = mapOf("active_pool_tasks" to 1, "pool_colors" to 1, "failure_totals" to 1)

        assertEquals(expected, frequenciesToOpen(PoolType.THREE_D, 1))

        closeDatabase()
        openDatabase()
        assertEquals(expected, frequenciesToOpen(PoolType.THREE_D, 42), "a query ran again for every task")

        closeDatabase()
        openDatabase()
        assertEquals(expected, frequenciesToOpen(PoolType.THREE_D, 42, colorsEach = 3), "a query ran per colour")

        closeDatabase()
        openDatabase()
        assertEquals(expected, frequenciesToOpen(PoolType.THREE_D, 42, colorsEach = 0), "a colourless pool asked more")
    }

    @Test
    fun `the card pool runs each of its three queries exactly once at either size`() {
        val expected = mapOf("active_pool_tasks" to 1, "pool_stages" to 1, "failure_totals" to 1)

        assertEquals(expected, frequenciesToOpen(PoolType.CARD, 1, colorsEach = 0))

        closeDatabase()
        openDatabase()
        assertEquals(expected, frequenciesToOpen(PoolType.CARD, 42, colorsEach = 0), "a query ran per task or stage")
    }

    @Test
    fun `the board pool runs each of its three queries exactly once at either size`() {
        val expected = mapOf("active_pool_tasks" to 1, "pool_stages" to 1, "failure_totals" to 1)

        assertEquals(expected, frequenciesToOpen(PoolType.BOARD, 1, colorsEach = 0))

        closeDatabase()
        openDatabase()
        assertEquals(expected, frequenciesToOpen(PoolType.BOARD, 42, colorsEach = 0))
    }

    @Test
    fun `the special pool runs each of its two queries exactly once at either size`() {
        val expected = mapOf("active_pool_tasks" to 1, "failure_totals" to 1)

        assertEquals(expected, frequenciesToOpen(PoolType.SPECIAL, 1, colorsEach = 0))

        closeDatabase()
        openDatabase()
        assertEquals(expected, frequenciesToOpen(PoolType.SPECIAL, 42, colorsEach = 0))
    }

    @Test
    fun `the sidebar summary runs its one query exactly once at either size`() {
        val expected = mapOf("navigation_summary" to 1)
        val summaryOnly: () -> Map<String, Int> = {
            runBlocking {
                database.colorDao().allColors()
                driver.start()
                PoolStore(database.poolDao()).observeNavigationSummary().first()
                frequenciesOf(driver.stop())
            }
        }

        runBlocking { fill(PoolType.THREE_D, 1, 1) }
        assertEquals(expected, summaryOnly())

        closeDatabase()
        openDatabase()
        runBlocking {
            fill(PoolType.THREE_D, 42, 3)
            fill(PoolType.SPECIAL, 42, 0)
        }
        assertEquals(expected, summaryOnly(), "the summary asked once per pool or per task")
    }

    @Test
    fun `opening one pool runs no query for the other three`() =
        runBlocking<Unit> {
            fill(PoolType.THREE_D, 5, 1)
            fill(PoolType.CARD, 5, 0)
            fill(PoolType.BOARD, 5, 0)
            fill(PoolType.SPECIAL, 5, 0)
            database.colorDao().allColors()

            driver.start()
            PoolStore(database.poolDao()).observePool(PoolType.THREE_D).first()
            val frequencies = frequenciesOf(driver.stop())

            // The detail queries of the pool on screen and nothing else. The
            // sidebar's own count is a separate read and is not among these:
            // opening a pool must not pay for the three nobody is looking at.
            assertEquals(mapOf("active_pool_tasks" to 1, "pool_colors" to 1, "failure_totals" to 1), frequencies)
            assertEquals(0, frequencies["pool_stages"] ?: 0, "a pipeline pool was read while the 3D one was open")
            assertEquals(0, frequencies["navigation_summary"] ?: 0, "the sidebar was read again by the pool")
        }

    // ------------------------------------------------------ the same, at scale

    @Test
    fun `opening the 3D pool costs three reads whether it holds one task or forty two`() {
        val forOne = readsToOpen(PoolType.THREE_D, 1)
        assertEquals(3, forOne.size, "the 3D pool is meant to be three reads: $forOne")

        closeDatabase()
        openDatabase()
        val forFortyTwo = readsToOpen(PoolType.THREE_D, 42)

        assertEquals(forOne, forFortyTwo, "the pool read grew with the number of tasks")
    }

    @Test
    fun `forty two tasks in three colours still cost three reads`() {
        val forOne = readsToOpen(PoolType.THREE_D, 1, colorsEach = 3)

        closeDatabase()
        openDatabase()
        val forFortyTwo = readsToOpen(PoolType.THREE_D, 42, colorsEach = 3)

        assertEquals(3, forOne.size)
        assertEquals(forOne, forFortyTwo, "the colours of a task were read one task at a time")
    }

    @Test
    fun `forty two tasks with no colour at all still cost three reads`() {
        val reads = readsToOpen(PoolType.THREE_D, 42, colorsEach = 0)

        assertEquals(3, reads.size, "a pool of colourless tasks asked for something extra: $reads")
    }

    @Test
    fun `opening the card pool costs three reads at either size`() {
        val forOne = readsToOpen(PoolType.CARD, 1)
        assertEquals(3, forOne.size, "the card pool is meant to be three reads: $forOne")

        closeDatabase()
        openDatabase()
        assertEquals(forOne, readsToOpen(PoolType.CARD, 42))
    }

    @Test
    fun `opening the board pool costs three reads at either size`() {
        val forOne = readsToOpen(PoolType.BOARD, 1)
        assertEquals(3, forOne.size)

        closeDatabase()
        openDatabase()
        assertEquals(forOne, readsToOpen(PoolType.BOARD, 42))
    }

    @Test
    fun `opening the special pool costs two reads at either size`() {
        // No colours and no stages, so neither is asked for.
        val forOne = readsToOpen(PoolType.SPECIAL, 1)
        assertEquals(2, forOne.size, "the special pool is meant to be two reads: $forOne")

        closeDatabase()
        openDatabase()
        assertEquals(forOne, readsToOpen(PoolType.SPECIAL, 42))
    }

    @Test
    fun `the sidebar's summary is one read whatever the pools hold`() {
        val forOne =
            runBlocking {
                fill(PoolType.THREE_D, 1, 1)
                database.colorDao().allColors()
                driver.start()
                PoolStore(database.poolDao()).observeNavigationSummary().first()
                statementsOf(driver.stop())
            }
        assertEquals(1, forOne.size, "the summary is meant to be one read: $forOne")

        closeDatabase()
        openDatabase()
        val forFortyTwo =
            runBlocking {
                fill(PoolType.THREE_D, 42, 3)
                fill(PoolType.CARD, 42, 0)
                database.colorDao().allColors()
                driver.start()
                PoolStore(database.poolDao()).observeNavigationSummary().first()
                statementsOf(driver.stop())
            }

        assertEquals(forOne, forFortyTwo, "the summary grew with the work")
    }

    // ---------------------------------------------------- nothing per anything

    @Test
    fun `nothing is asked one task at a time`() {
        val reads = readsToOpen(PoolType.THREE_D, 42, colorsEach = 3)

        // A read bound to one task is what an N+1 looks like from here.
        reads.forEach { sql ->
            assertTrue(
                "tasks.id = ?" !in sql && "task_id = ?" !in sql,
                "a read was bound to a single task: $sql",
            )
        }
    }

    @Test
    fun `nothing is asked one colour group at a time`() {
        val reads = readsToOpen(PoolType.THREE_D, 42, colorsEach = 3)

        assertEquals(1, reads.count { "task_colors" in it }, "the colours were read more than once: $reads")
        reads.forEach { sql -> assertTrue("color_id = ?" !in sql, "a read was bound to one colour: $sql") }
    }

    @Test
    fun `nothing is asked one stage or one event at a time`() {
        val reads = readsToOpen(PoolType.CARD, 42)

        assertEquals(1, reads.count { "task_stages" in it }, "the stages were read more than once")
        assertEquals(1, reads.count { "progress_events" in it }, "the events were read more than once")
    }

    @Test
    fun `a pool that is not being looked at is not being read`() =
        runBlocking<Unit> {
            fill(PoolType.THREE_D, 3, 1)
            fill(PoolType.CARD, 3, 0)
            database.colorDao().allColors()

            driver.start()
            PoolStore(database.poolDao()).observePool(PoolType.THREE_D).first()
            val reads = statementsOf(driver.stop())

            // Opening one pool reads that pool. The other three are not touched:
            // a screen shows one at a time, and paying for four would be paying
            // for three nobody is looking at.
            assertEquals(3, reads.size)
            assertTrue(reads.all { "'CARD'" !in it && "'BOARD'" !in it }, "another pool was read as well: $reads")
        }

    @Test
    fun `opening a pool writes nothing at all`() =
        runBlocking<Unit> {
            fill(PoolType.THREE_D, 5, 2)
            database.colorDao().allColors()

            driver.start()
            PoolStore(database.poolDao()).observePool(PoolType.THREE_D).first()
            PoolStore(database.poolDao()).observeNavigationSummary().first()
            val statements = driver.stop()

            // PLAN 12.10: a pool is a reflection and never writes a record. What
            // is asserted is that nothing it ran changes a row of the tables it
            // reads; Room's own invalidation bookkeeping is not one of those.
            statements.filter { touchesAPoolTable(it) }.forEach { sql ->
                val head = sql.trimStart().uppercase()
                assertTrue(
                    !head.startsWith("INSERT") && !head.startsWith("UPDATE") && !head.startsWith("DELETE"),
                    "opening a pool wrote something: $sql",
                )
            }
        }

    @Test
    fun `the tasks of a pool are read once and not once per game`() =
        runBlocking<Unit> {
            fill(PoolType.THREE_D, 42, 1)
            database.colorDao().allColors()

            driver.start()
            val snapshot = PoolStore(database.poolDao()).observePool(PoolType.THREE_D).first()
            val reads = statementsOf(driver.stop())

            assertEquals(42, snapshot.tasks.size)
            assertEquals(1, reads.count { "FROM tasks" in it }, "the tasks were read more than once: $reads")
        }

    @Test
    fun `a colour renamed elsewhere reaches the pool without a second read`() =
        runBlocking<Unit> {
            fill(PoolType.THREE_D, 1, 1)
            val color = assertNotNull(database.colorDao().allColors().firstOrNull())
            ColorCatalogueStore(database.colorDao())
                .editColor(color.id, color.canonicalName, color.hex, "Vişne", color.hex)

            val named: EntityId =
                PoolStore(database.poolDao())
                    .observePool(PoolType.THREE_D)
                    .first()
                    .tasks
                    .single()
                    .colors
                    .single()
                    .colorId

            assertEquals(color.id, named)
            assertEquals(
                "Vişne",
                PoolStore(database.poolDao())
                    .observePool(PoolType.THREE_D)
                    .first()
                    .tasks
                    .single()
                    .colors
                    .single()
                    .canonicalName,
            )
            freshDatabase()
        }
}
