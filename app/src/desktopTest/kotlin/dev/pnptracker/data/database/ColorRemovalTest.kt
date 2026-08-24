package dev.pnptracker.data.database

import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Deleting a colour the user has agreed to lose.
 *
 * The subject throughout is what survives: the tasks do, and the colour does
 * not. PLAN 5.9 is explicit that removing a colour removes a colour and nothing
 * else, which is easy to get wrong in a transaction that has to clear two other
 * tables first.
 */
class ColorRemovalTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private fun newId() = IdGenerator.Random.newId()

    private suspend fun colorId(name: String) = assertNotNull(database.colorDao().resolve(name)).id

    private suspend fun aTaskInACell(name: String = "Gri token") = insertGameCellAndTask(database, gameName = name) { aTask(name = name) }

    @Test
    fun `deleting a colour nobody uses removes only the colour`() =
        runBlocking<Unit> {
            val pink = colorId("Pembe")

            val removal = database.colorDao().deleteColorTheUserHasConfirmed(pink)

            assertEquals(0, removal.taskCount)
            assertEquals(0, removal.tasksLeftWithoutAColor)
            assertEquals(11, database.colorDao().allColors().size)
            assertNull(database.colorDao().colorById(pink))
        }

    @Test
    fun `deleting a colour a task uses takes the relation and leaves the task`() =
        runBlocking<Unit> {
            val task = aTaskInACell()
            val grey = colorId("Gri")
            database.taskColorDao().addColorToTask(task.id, grey)

            val removal = database.colorDao().deleteColorTheUserHasConfirmed(grey)

            assertEquals(1, removal.taskCount)
            assertEquals(1, removal.removedRelationCount)
            assertNull(database.colorDao().colorById(grey), "the colour was not removed")
            assertNotNull(database.taskDao().activeTaskById(task.id), "the task went with the colour")
            assertEquals(emptyList(), database.taskColorDao().colorsOfTask(task.id))
        }

    @Test
    fun `a task that loses its last colour turns up in the waiting list`() =
        runBlocking<Unit> {
            val task = aTaskInACell()
            val grey = colorId("Gri")
            database.taskColorDao().addColorToTask(task.id, grey)
            assertEquals(emptyList(), database.taskDao().activeTasksAwaitingAColor(PoolType.THREE_D))

            val removal = database.colorDao().deleteColorTheUserHasConfirmed(grey)

            assertEquals(1, removal.tasksLeftWithoutAColor)
            assertEquals(
                listOf(task.id),
                database.taskDao().activeTasksAwaitingAColor(PoolType.THREE_D).map { it.id },
            )
        }

    @Test
    fun `a multi colour task keeps the rest, renumbered from zero`() =
        runBlocking<Unit> {
            val task = aTaskInACell(name = "Kılıç")
            listOf("Gri", "Siyah", "Beyaz").forEach { name ->
                database.taskColorDao().addColorToTask(task.id, colorId(name))
            }
            val black = colorId("Siyah")
            val grey = colorId("Gri")
            val white = colorId("Beyaz")

            val removal = database.colorDao().deleteColorTheUserHasConfirmed(black)

            assertEquals(0, removal.tasksLeftWithoutAColor)
            val kept = database.taskColorDao().colorsOfTask(task.id)
            assertEquals(listOf(grey, white), kept.map { it.colorId }, "the order the user chose was lost")
            assertEquals(listOf(0, 1), kept.map { it.slotIndex }, "the slots were left with a gap in them")
        }

    @Test
    fun `deleting the first colour of a task still leaves the rest starting at zero`() =
        runBlocking<Unit> {
            val task = aTaskInACell(name = "Kılıç")
            listOf("Gri", "Siyah").forEach { name ->
                database.taskColorDao().addColorToTask(task.id, colorId(name))
            }

            database.colorDao().deleteColorTheUserHasConfirmed(colorId("Gri"))

            assertEquals(listOf(0), database.taskColorDao().colorsOfTask(task.id).map { it.slotIndex })
        }

    @Test
    fun `deleting a colour takes its aliases with it`() =
        runBlocking<Unit> {
            val purple = colorId("Mor")
            database.colorDao().addAlias(purple, "Lila")

            val removal = database.colorDao().deleteColorTheUserHasConfirmed(purple)

            assertEquals(1, removal.removedAliasCount)
            assertNull(database.colorDao().resolve("Lila"), "an alias outlived its colour")
        }

    @Test
    fun `only the colour that was asked about is touched`() =
        runBlocking<Unit> {
            val task = aTaskInACell(name = "Kılıç")
            val grey = colorId("Gri")
            val black = colorId("Siyah")
            database.taskColorDao().addColorToTask(task.id, grey)
            val other = insertGameCellAndTask(database, gameName = "Wingspan") { aTask(name = "Siyah token") }
            database.taskColorDao().addColorToTask(other.id, black)

            database.colorDao().deleteColorTheUserHasConfirmed(grey)

            assertNotNull(database.colorDao().colorById(black), "a colour nobody asked about was removed")
            assertEquals(listOf(black), database.taskColorDao().colorsOfTask(other.id).map { it.colorId })
        }

    @Test
    fun `a colour that is already gone is refused and nothing is written`() =
        runBlocking<Unit> {
            val before = database.colorDao().allColors()

            val refusal =
                assertFailsWith<ColorSetupException> {
                    database.colorDao().deleteColorTheUserHasConfirmed(newId())
                }

            assertEquals(ColorSetupFailure.COLOR_NO_LONGER_EXISTS, refusal.failure)
            assertEquals(before, database.colorDao().allColors())
        }

    @Test
    fun `deleting a colour leaves the cell and its segments alone`() =
        runBlocking<Unit> {
            val task = insertGameCellAndTask(database, columnType = CellColumnType.THREE_D)
            val grey = colorId("Gri")
            database.taskColorDao().addColorToTask(task.id, grey)
            val segment = assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(task.id))

            database.colorDao().deleteColorTheUserHasConfirmed(grey)

            assertEquals(segment, assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(task.id)))
        }
}
