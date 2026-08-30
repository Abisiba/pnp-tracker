package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a refused colour leaves behind, against a real database.
 *
 * A colour is one row, so "did it roll back" sounds like it answers itself. It
 * does not: the transaction hands out a place in the catalogue before it writes,
 * and a run that stopped between the two would leave the next colour landing on
 * a number that is already spoken for. These make a real statement fail against
 * a real SQLite file — a store handed a pretend exception would prove only that
 * the test can throw.
 */
class ColorCreationRollbackTest {
    /** The whole catalogue, as one comparable snapshot. */
    private data class Snapshot(
        val colors: List<ColorEntity>,
        val tasks: Int,
        val taskColors: Int,
    )

    private class Trapped(
        val database: AppDatabase,
        val store: ColorCatalogueStore,
        val driver: FailingSqliteDriver,
    )

    private fun withDatabase(
        idGenerator: IdGenerator = IdGenerator.Random,
        work: suspend Trapped.() -> Unit,
    ) {
        val existedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        val directory = TemporaryDatabaseDirectory()
        val driver = FailingSqliteDriver()
        val database = DatabaseFactory(driver = driver).open(directory.databaseFile)
        try {
            runBlocking {
                Trapped(
                    database = database,
                    store = ColorCatalogueStore(database.colorDao(), idGenerator),
                    driver = driver,
                ).work()
            }
        } finally {
            driver.disarm()
            database.close()
            directory.assertRealApplicationDatabaseUntouched(existedBefore)
            directory.delete()
        }
    }

    private suspend fun AppDatabase.snapshot(): Snapshot =
        Snapshot(
            colors = colorDao().allColors(),
            tasks = taskDao().allTasksIncludingDeleted().size,
            taskColors = colorDao().allColors().sumOf { colorDao().tasksUsingColor(it.id).size },
        )

    private fun assertNothingChanged(
        before: Snapshot,
        after: Snapshot,
    ) {
        assertEquals(before.colors, after.colors, "the catalogue was left changed")
        assertEquals(before.tasks, after.tasks, "a task was written by a colour")
        assertEquals(before.taskColors, after.taskColors, "a colour relation was written by a colour")
    }

    @Test
    fun `a write the database refuses leaves the catalogue exactly as it was`() =
        withDatabase {
            val before = database.snapshot()
            driver.failOn { sql -> "colors" in sql && sql.trimStart().startsWith("INSERT", ignoreCase = true) }

            val refusal = assertFailsWith<ColorSetupException> { store.createColor("Lacivert", "#1A237E") }

            // The trap and not a guard: a guard would have said the name is taken.
            assertEquals(ColorSetupFailure.COULD_NOT_SAVE, refusal.failure)
            driver.disarm()
            assertNothingChanged(before, database.snapshot())
        }

    @Test
    fun `the place the refused colour was given goes back with it`() =
        withDatabase {
            driver.failOn { sql -> "colors" in sql && sql.trimStart().startsWith("INSERT", ignoreCase = true) }
            assertFailsWith<ColorSetupException> { store.createColor("Lacivert", "#1A237E") }
            driver.disarm()

            val next = store.createColor("Bordo", "#880E4F")

            // Twelve seeded colours, so the next free place is twelve — not
            // thirteen, which is what a half-run transaction would have left.
            assertEquals(
                12,
                database
                    .colorDao()
                    .allColors()
                    .first { it.id == next }
                    .sortOrder,
            )
            assertEquals(13, database.colorDao().allColors().size)
        }

    @Test
    fun `an identity that cannot be produced leaves nothing behind`() =
        withDatabase(idGenerator = IdGenerator { throw IllegalStateException("no identity could be made") }) {
            val before = database.snapshot()

            // Not dressed up as a saving problem: nothing was attempted, and a
            // broken rule travels out as itself.
            assertFailsWith<IllegalStateException> { store.createColor("Lacivert", "#1A237E") }

            assertNothingChanged(before, database.snapshot())
        }

    @Test
    fun `a colour writes to the colour table and to nothing else`() =
        withDatabase {
            val counting = CountingSqliteDriver()
            val existedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
            val directory = TemporaryDatabaseDirectory()
            val watched = DatabaseFactory(driver = counting).open(directory.databaseFile)
            try {
                // The file is opened and seeded on first use, so that has to
                // happen before the recording starts or it would be measured.
                watched.colorDao().allColors()
                counting.start()
                ColorCatalogueStore(watched.colorDao()).createColor("Lacivert", "#1A237E")
                val run = counting.stop()

                val written = run.filter { it.trimStart().startsWith("INSERT", ignoreCase = true) }
                assertEquals(1, written.size, "a colour wrote more than one row: $written")
                listOf("tasks", "task_colors", "task_stages", "progress_events", "cell_segments", "game_cells").forEach { table ->
                    assertTrue(run.none { table in it }, "a colour went near $table")
                }
            } finally {
                watched.close()
                directory.assertRealApplicationDatabaseUntouched(existedBefore)
                directory.delete()
            }
        }

    @Test
    fun `a colour costs the same statements however large the catalogue is`() {
        fun catalogueStatementsWith(extra: Int): List<String> {
            val existedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
            val directory = TemporaryDatabaseDirectory()
            val counting = CountingSqliteDriver()
            val database = DatabaseFactory(driver = counting).open(directory.databaseFile)
            return try {
                runBlocking {
                    val store = ColorCatalogueStore(database.colorDao())
                    // Creating the file, seeding it and running this path for the
                    // first time all happen once; they are out of the way before
                    // anything is counted, so what is measured is one more colour
                    // on a catalogue of one size against a catalogue of another.
                    store.createColor("Isıtma", "#0A0A0A")
                    repeat(extra) { index -> store.createColor("Ara $index", "#10${index.toString().padStart(4, '0')}") }
                    counting.start()
                    store.createColor("Lacivert", "#1A237E")
                    // Room polls its own invalidation log on a timer of its own,
                    // so whether that lands inside the window is a matter of when
                    // the test ran. What is being asked here is what the colour
                    // itself costs, and that is the statements about colours.
                    counting.stop().filter { "colors" in it || "color_aliases" in it }
                }
            } finally {
                database.close()
                directory.assertRealApplicationDatabaseUntouched(existedBefore)
                directory.delete()
            }
        }

        // Each measurement gets its own fresh database, because Room keeps its
        // prepared statements and a second run on the same one would be counting
        // something else.
        val small = catalogueStatementsWith(extra = 0)
        val large = catalogueStatementsWith(extra = 60)

        assertEquals(small, large, "the cost of a colour grew with the catalogue")
        assertEquals(4, small.size, "a colour is a name check, an alias check, a place and a row: $small")
        assertEquals(1, small.count { it.trimStart().startsWith("INSERT", ignoreCase = true) })
    }
}
