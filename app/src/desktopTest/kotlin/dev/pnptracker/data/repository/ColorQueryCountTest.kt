package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.domain.colors.baseColors
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the colour section actually asks the database, counted at the driver.
 *
 * Counting calls to a DAO would only measure what a test asked for. These count
 * statements really run against SQLite, which is the only place a loop hiding
 * inside a transaction shows up.
 *
 * The shape of the answer matters more than the number: every one of these
 * compares a colour used by one task with the same colour used by forty two, and
 * the two lists have to be identical. A count that grows with the work is a
 * query per task, whatever the number happens to be.
 */
class ColorQueryCountTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: ColorCatalogueStore
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = driver).open(directory.databaseFile)
        store = ColorCatalogueStore(database.colorDao())
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    /**
     * Statements about colours, tidied so two runs can be compared as text.
     *
     * Room polls its own change log on a timer, so anything that does not name
     * one of the three colour tables is somebody else's business.
     */
    private fun colorStatements(): List<String> =
        driver
            .stop()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { sql -> listOf("colors", "task_colors", "color_aliases").any { it in sql.lowercase() } }

    private suspend fun colorNamed(name: String) = assertNotNull(database.colorDao().resolve(name))

    private suspend fun tasksUsing(
        colorId: EntityId,
        count: Int,
        alsoWith: EntityId? = null,
    ) {
        repeat(count) { index ->
            val task =
                insertGameCellAndTask(database, gameName = "Oyun $index", columnType = CellColumnType.THREE_D) {
                    aTask(name = "Token $index")
                }
            var slot = 0
            alsoWith?.let { database.taskColorDao().insert(TaskColorEntity(task.id, it, slot++)) }
            database.taskColorDao().insert(TaskColorEntity(task.id, colorId, slot))
        }
    }

    @Test
    fun `asking what a colour is used by is two reads, however much it is used`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val blue = colorNamed("Mavi")
            tasksUsing(red.id, count = 1, alsoWith = blue.id)

            driver.start()
            store.usageOf(red.id)
            val forOne = colorStatements()

            tasksUsing(red.id, count = 41, alsoWith = blue.id)

            driver.start()
            store.usageOf(red.id)
            val forFortyTwo = colorStatements()

            assertEquals(2, forOne.size, "the confirmation asked more than the summary and the samples")
            assertEquals(forOne, forFortyTwo, "the question got more expensive as the answer got bigger")
        }

    @Test
    fun `removing a colour runs the same statements for one task and for forty two`() =
        runBlocking<Unit> {
            val forOne = statementsRemovingAColourUsedBy(1)
            val forFortyTwo = statementsRemovingAColourUsedBy(42)

            assertEquals(forOne, forFortyTwo, "the removal walked the tasks")
            assertEquals(7, forOne.size, "the removal is meant to be seven statements: $forOne")
        }

    @Test
    fun `removing a colour never reads or writes one task at a time`() =
        runBlocking<Unit> {
            val statements = statementsRemovingAColourUsedBy(42)

            assertTrue(
                statements.none { it.contains("WHERE task_id = ?", ignoreCase = true) },
                "a statement was aimed at one task at a time: $statements",
            )
            assertEquals(
                1,
                statements.count { it.uppercase().startsWith("DELETE FROM TASK_COLORS") },
                "the relations were not taken in one go",
            )
            assertEquals(
                1,
                statements.count { it.uppercase().startsWith("UPDATE TASK_COLORS") && "CASE WHEN" in it },
                "the slots were closed up more than once",
            )
        }

    @Test
    fun `the old walk of forty two tasks does not come back`() =
        runBlocking<Unit> {
            // Before this step a removal ran two reads per affected task, which
            // came to eighty nine statements for forty two of them.
            val statements = statementsRemovingAColourUsedBy(42)

            assertTrue(statements.size < 10, "the removal grew back into a walk: ${statements.size} statements")
        }

    @Test
    fun `working out what a restore would do is one read of the colours and one of the aliases`() =
        runBlocking<Unit> {
            database.colorDao().deleteColorTheUserHasConfirmed(baseColors[0].id)
            database.colorDao().deleteColorTheUserHasConfirmed(baseColors[1].id)
            database.colorDao().deleteColorTheUserHasConfirmed(baseColors[2].id)

            driver.start()
            store.previewBaseColorRestore()
            val statements = colorStatements()

            assertEquals(2, statements.size, "the preview asked more than twice: $statements")
            assertEquals(1, statements.count { it.uppercase().startsWith("SELECT * FROM COLORS") })
            assertEquals(1, statements.count { it.uppercase().startsWith("SELECT * FROM COLOR_ALIASES") })
        }

    @Test
    fun `a restore reads twice and then only writes, whatever is missing`() =
        runBlocking<Unit> {
            listOf(baseColors[0], baseColors[1], baseColors[2], baseColors[3]).forEach {
                database.colorDao().deleteColorTheUserHasConfirmed(it.id)
            }

            driver.start()
            store.restoreMissingBaseColors()
            val statements = colorStatements()

            val reads = statements.filter { it.uppercase().startsWith("SELECT") }
            val writes = statements.filter { it.uppercase().startsWith("INSERT") }
            assertEquals(2, reads.size, "the restore asked about the colours one at a time: $reads")
            assertEquals(4, writes.size, "one write per missing colour is what a restore costs")
            assertEquals(statements.size, reads.size + writes.size, "the restore did something else as well")
        }

    @Test
    fun `a restore with nothing missing still only reads twice`() =
        runBlocking<Unit> {
            // The database is created and seeded on first use; that belongs to
            // opening the application, not to the question being measured.
            database.colorDao().allColors()

            driver.start()
            store.restoreMissingBaseColors()
            val statements = colorStatements()

            assertEquals(2, statements.size, "asking whether anything is missing cost more than two reads")
            assertTrue(statements.all { it.uppercase().startsWith("SELECT") }, "nothing was missing but something was written")
        }

    @Test
    fun `changing a colour asks nothing about the tasks that use it`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            tasksUsing(red.id, count = 42)

            driver.start()
            store.editColor(red.id, red.canonicalName, red.hex, "Gül", "#C2185B")
            val statements = colorStatements()

            assertTrue(
                statements.none { "task_colors" in it.lowercase() },
                "changing a colour reached into the task relations: $statements",
            )
            assertEquals(4, statements.size, "the change asked more than it needed: $statements")
        }

    /** The statements a removal runs, on a database built for this one measurement. */
    private fun statementsRemovingAColourUsedBy(taskCount: Int): List<String> {
        val counting = CountingSqliteDriver()
        val ownDirectory = TemporaryDatabaseDirectory()
        val own = DatabaseFactory(driver = counting).open(ownDirectory.databaseFile)
        return try {
            runBlocking {
                val ownStore = ColorCatalogueStore(own.colorDao())
                val red = assertNotNull(own.colorDao().resolve("Kırmızı"))
                val blue = assertNotNull(own.colorDao().resolve("Mavi"))
                repeat(taskCount) { index ->
                    val task =
                        insertGameCellAndTask(own, gameName = "Oyun $index", columnType = CellColumnType.THREE_D) {
                            aTask(name = "Token $index")
                        }
                    own.taskColorDao().insert(TaskColorEntity(task.id, blue.id, 0))
                    own.taskColorDao().insert(TaskColorEntity(task.id, red.id, 1))
                }

                counting.start()
                ownStore.deleteColor(red.id)
                counting
                    .stop()
                    .map { it.replace(Regex("\\s+"), " ").trim() }
                    .filter { sql -> listOf("colors", "task_colors", "color_aliases").any { it in sql.lowercase() } }
            }
        } finally {
            own.close()
            ownDirectory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
            ownDirectory.delete()
        }
    }
}
