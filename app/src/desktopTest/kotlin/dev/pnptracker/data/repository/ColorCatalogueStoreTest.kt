package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aRawImportBlock
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.seedColors
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The colour catalogue against a real database in a temporary directory.
 *
 * What cannot be shown against a stand in is exactly what matters here: that the
 * seeded catalogue really is what a fresh database starts with, that a name is
 * refused whichever table already carries it, and that what the user added is
 * still there after the application closes.
 */
class ColorCatalogueStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: ColorCatalogueStore
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openTemporaryDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = ColorCatalogueStore(database.colorDao())
    }

    @AfterTest
    fun closeAndDeleteTemporaryDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun catalogue(): List<ColorSummary> = store.observeColors().first()

    @Test
    fun `a fresh catalogue is the twelve colours the plan starts with, in order`() =
        runBlocking {
            assertEquals(seedColors.map { it.canonicalName }, catalogue().map { it.canonicalName })
            assertEquals((0..11).toList(), catalogue().map { it.sortOrder })
        }

    @Test
    fun `every listed colour carries the value its swatch is drawn from`() =
        runBlocking {
            assertEquals(seedColors.map { it.hex }, catalogue().map { it.hex })
        }

    @Test
    fun `a colour the user added goes to the end of the catalogue`() =
        runBlocking {
            val id = store.createColor("Lacivert", "#1A237E")

            val added = catalogue().last()
            assertEquals(id, added.id)
            assertEquals("Lacivert", added.canonicalName)
            assertEquals("#1A237E", added.hex)
            assertEquals(12, added.sortOrder, "the new colour did not take the next place")
            assertEquals(13, catalogue().size)
        }

    @Test
    fun `two colours added one after the other take two different places`() =
        runBlocking {
            store.createColor("Lacivert", "#1A237E")
            store.createColor("Bordo", "#800020")

            val places = catalogue().map { it.sortOrder }
            assertEquals(places.size, places.toSet().size, "two colours share a place: $places")
            assertEquals(listOf(12, 13), places.takeLast(2))
        }

    @Test
    fun `the name is trimmed at the ends and its normalised form is derived`() =
        runBlocking {
            val id = store.createColor("  Lacivert  ", "#1A237E")

            val row = assertNotNull(database.colorDao().colorById(id))
            assertEquals("Lacivert", row.canonicalName)
            assertEquals("lacivert", row.normalizedName)
        }

    @Test
    fun `the four ways of typing an existing name are all refused`() =
        runBlocking {
            val before = database.colorDao().allColors()

            listOf("GRİ", "Gri", "gri", "GRI").forEach { attempt ->
                val refusal = assertFailsWith<ColorSetupException> { store.createColor(attempt, "#123456") }
                assertEquals(ColorSetupFailure.NAME_ALREADY_USED, refusal.failure, "'$attempt' was not seen as Gri")
            }

            assertEquals(before, database.colorDao().allColors(), "a refused name still changed a row")
        }

    @Test
    fun `a name that is already another colour's alias is refused`() =
        runBlocking {
            val gri = assertNotNull(database.colorDao().colorByNormalizedName("gri"))
            database.colorDao().addAlias(gri.id, "Gri Ton")
            val before = database.colorDao().allColors()

            val refusal = assertFailsWith<ColorSetupException> { store.createColor("gri ton", "#123456") }

            assertEquals(ColorSetupFailure.NAME_IS_ANOTHER_COLORS_ALIAS, refusal.failure)
            assertEquals(before, database.colorDao().allColors())
            assertEquals(gri.id, assertNotNull(database.colorDao().resolve("Gri Ton")).id, "the alias was disturbed")
        }

    @Test
    fun `a blank name is refused as a broken rule rather than a saving problem`() =
        runBlocking {
            val before = database.colorDao().allColors()

            assertFailsWith<IllegalArgumentException> { store.createColor("   ", "#1A237E") }

            assertEquals(before, database.colorDao().allColors())
        }

    @Test
    fun `a value that is not written as RRGGBB is refused and nothing is written`() =
        runBlocking {
            val before = database.colorDao().allColors()

            listOf("1A237E", "#1A237", "#1A237EE", "#GGGGGG", "").forEach { attempt ->
                assertFailsWith<IllegalArgumentException>("'$attempt' was accepted") {
                    store.createColor("Lacivert", attempt)
                }
            }

            assertEquals(before, database.colorDao().allColors())
        }

    @Test
    fun `a value another colour already uses is reported but does not stop the save`() =
        runBlocking {
            val griHex = assertNotNull(database.colorDao().colorByNormalizedName("gri")).hex

            val sharing = store.colorsUsingHex(griHex)
            assertEquals(listOf("Gri"), sharing.map { it.canonicalName })

            val id = store.createColor("Duman", griHex)

            assertEquals(griHex, assertNotNull(database.colorDao().colorById(id)).hex)
            assertEquals(listOf("Gri", "Duman"), store.colorsUsingHex(griHex).map { it.canonicalName })
        }

    @Test
    fun `a value nobody uses yet is reported as free`() =
        runBlocking {
            assertEquals(emptyList(), store.colorsUsingHex("#1A237E"))
        }

    @Test
    fun `the same value written in another case is still recognised as taken`() =
        runBlocking {
            val griHex = assertNotNull(database.colorDao().colorByNormalizedName("gri")).hex

            assertEquals(listOf("Gri"), store.colorsUsingHex(griHex.lowercase()).map { it.canonicalName })
        }

    @Test
    fun `a value that is not a colour at all is asked about nowhere`() =
        runBlocking {
            assertEquals(emptyList(), store.colorsUsingHex("#1A2"))
        }

    @Test
    fun `every seeded colour is offered, because nothing hides one`() =
        runBlocking {
            val gri = assertNotNull(database.colorDao().colorByNormalizedName("gri"))

            assertEquals(12, catalogue().size)
            assertTrue(catalogue().any { it.id == gri.id }, "a seeded colour was left out of the catalogue")
            assertEquals(catalogue().map { it.id }, database.colorDao().allColors().map { it.id })
        }

    @Test
    fun `what the user added is still there after the database is closed and opened`() =
        runBlocking {
            store.createColor("Lacivert", "#1A237E")

            database.close()
            database = DatabaseFactory().open(directory.databaseFile)
            store = ColorCatalogueStore(database.colorDao())

            val added = catalogue().last()
            assertEquals("Lacivert", added.canonicalName)
            assertEquals("#1A237E", added.hex)
            assertEquals(13, catalogue().size)
        }

    @Test
    fun `reopening a catalogue the user has added to does not run the seed again`() =
        runBlocking {
            val seeded = database.colorDao().allColors()
            store.createColor("Lacivert", "#1A237E")

            database.close()
            database = DatabaseFactory().open(directory.databaseFile)
            store = ColorCatalogueStore(database.colorDao())

            val reopened = database.colorDao().allColors()
            assertEquals(13, reopened.size, "the seed ran a second time")
            assertEquals(seeded, reopened.take(seeded.size), "reopening rewrote a seeded colour")
        }

    @Test
    fun `adding a colour leaves the task and import tables exactly as they were`() =
        runBlocking {
            val game = aGame()
            val cell = aCell(gameId = game.id)
            val batch = anImportBatch(rawBlockCount = 1)
            val block = aRawImportBlock(importBatchId = batch.id)
            val task = aTask()
            database.gameDao().insert(game)
            database.gameCellDao().insert(cell)
            database.taskDao().addTaskToCell(task, cell.id, IdGenerator.Random.newId(), createdAt)
            database.importDao().insertBatch(batch)
            database.importDao().insertRawBlock(block)
            val batchesBefore = database.importDao().allBatches()
            val blocksBefore = database.importDao().rawBlocksOfBatch(batch.id)
            val draftsBefore = database.importDao().draftTasksOfBatch(batch.id)
            val tasksBefore = database.taskDao().allTasksIncludingDeleted()

            store.createColor("Lacivert", "#1A237E")

            assertEquals(batchesBefore, database.importDao().allBatches())
            assertEquals(blocksBefore, database.importDao().rawBlocksOfBatch(batch.id))
            assertEquals(draftsBefore, database.importDao().draftTasksOfBatch(batch.id))
            assertEquals(tasksBefore, database.taskDao().allTasksIncludingDeleted())
            assertEquals(0, taskColorCount(), "the catalogue wrote a colour onto a task")
        }

    @Test
    fun `adding a colour writes no alias of its own`() =
        runBlocking {
            val id = store.createColor("Lacivert", "#1A237E")

            assertEquals(emptyList(), database.colorDao().aliasesOf(id))
        }

    private suspend fun taskColorCount(): Int {
        val tasks = database.taskDao().allTasksIncludingDeleted()
        return tasks.sumOf { database.taskColorDao().colorsOfTask(it.id).size }
    }
}
