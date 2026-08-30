package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.domain.colors.BaseColorRestore
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.baseColors
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a refused write leaves behind, on a real database.
 *
 * The trap refuses one statement in the middle of a transaction that has already
 * written others, which is the only way to find out whether the rest really goes
 * back. PLAN 5.9 and 5.8 both say the same thing in different words: a run that
 * stops halfway changes nothing, and there is no such thing as half a restore.
 *
 * The lifted slot range gets its own attention here. It exists between two
 * statements of one transaction, and a rollback that left one behind would leave
 * a task holding a colour at a slot below zero.
 */
class ColorChangeRollbackTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: ColorCatalogueStore
    private val driver = FailingSqliteDriver()
    private var realDatabaseExistedBefore = false

    private val white = baseColors[0]
    private val black = baseColors[1]

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = driver).open(directory.databaseFile)
        store = ColorCatalogueStore(database.colorDao())
    }

    @AfterTest
    fun closeDatabase() {
        driver.disarm()
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun colorNamed(name: String) = assertNotNull(database.colorDao().resolve(name))

    private suspend fun aTaskIn(
        colorIds: List<EntityId>,
        gameName: String = "Harmonies",
    ): EntityId {
        val task =
            insertGameCellAndTask(database, gameName = gameName, columnType = CellColumnType.THREE_D) {
                aTask(name = "Token")
            }
        colorIds.forEachIndexed { slot, colorId ->
            database.taskColorDao().insert(TaskColorEntity(taskId = task.id, colorId = colorId, slotIndex = slot))
        }
        return task.id
    }

    /** Everything a removal could have disturbed, as it stands right now. */
    private suspend fun snapshot(taskIds: List<EntityId>) =
        Triple(
            database.colorDao().allColors(),
            database.colorDao().allAliases(),
            taskIds.flatMap { database.taskColorDao().colorsOfTask(it) },
        )

    private fun refuse(
        occurrence: Int = 1,
        matches: (String) -> Boolean,
    ) = driver.failOn(occurrence) { matches(it.trimStart().uppercase()) }

    // ------------------------------------------------------------- deleting

    @Test
    fun `a removal refused while parking the slots changes nothing`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val white = colorNamed("Beyaz")
            val task = aTaskIn(listOf(white.id, red.id))
            val before = snapshot(listOf(task))

            refuse { it.startsWith("UPDATE TASK_COLORS SET SLOT_INDEX =") && "CASE WHEN" !in it }
            assertFailsWith<ColorSetupException> { store.deleteColor(red.id) }
            driver.disarm()

            assertEquals(before, snapshot(listOf(task)))
        }

    @Test
    fun `a removal refused while closing the gaps changes nothing`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val white = colorNamed("Beyaz")
            val blue = colorNamed("Mavi")
            val task = aTaskIn(listOf(white.id, red.id, blue.id))
            val before = snapshot(listOf(task))

            refuse { it.startsWith("UPDATE TASK_COLORS SET SLOT_INDEX =") && "CASE WHEN" in it }
            assertFailsWith<ColorSetupException> { store.deleteColor(red.id) }
            driver.disarm()

            assertEquals(before, snapshot(listOf(task)))
        }

    @Test
    fun `a removal refused while taking the relations changes nothing`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val task = aTaskIn(listOf(red.id))
            val before = snapshot(listOf(task))

            refuse { it.startsWith("DELETE FROM TASK_COLORS") }
            assertFailsWith<ColorSetupException> { store.deleteColor(red.id) }
            driver.disarm()

            assertEquals(before, snapshot(listOf(task)))
        }

    @Test
    fun `a removal refused while taking the aliases changes nothing`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            database.colorDao().addAlias(red.id, "Al")
            val task = aTaskIn(listOf(red.id))
            val before = snapshot(listOf(task))

            refuse { it.startsWith("DELETE FROM COLOR_ALIASES") }
            assertFailsWith<ColorSetupException> { store.deleteColor(red.id) }
            driver.disarm()

            assertEquals(before, snapshot(listOf(task)))
        }

    @Test
    fun `a removal refused on the colour row itself changes nothing`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val task = aTaskIn(listOf(red.id))
            val before = snapshot(listOf(task))

            refuse { it.startsWith("DELETE FROM COLORS") }
            assertFailsWith<ColorSetupException> { store.deleteColor(red.id) }
            driver.disarm()

            assertEquals(before, snapshot(listOf(task)))
        }

    @Test
    fun `no task is left holding a parked slot after a refused removal`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val white = colorNamed("Beyaz")
            val blue = colorNamed("Mavi")
            val first = aTaskIn(listOf(white.id, red.id, blue.id), gameName = "Bir")
            val second = aTaskIn(listOf(red.id, blue.id), gameName = "Iki")

            refuse { it.startsWith("DELETE FROM COLORS") }
            assertFailsWith<ColorSetupException> { store.deleteColor(red.id) }
            driver.disarm()

            listOf(first, second).forEach { task ->
                val slots = database.taskColorDao().colorsOfTask(task).map { it.slotIndex }
                assertTrue(slots.all { it >= 0 }, "a slot was left parked below zero: $slots")
                assertEquals((0 until slots.size).toList(), slots.sorted(), "the order was left with a hole: $slots")
            }
        }

    @Test
    fun `a refused removal leaves the colour usable and removable afterwards`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val white = colorNamed("Beyaz")
            val task = aTaskIn(listOf(white.id, red.id))

            refuse { it.startsWith("DELETE FROM COLORS") }
            assertFailsWith<ColorSetupException> { store.deleteColor(red.id) }
            driver.disarm()

            val removal = store.deleteColor(red.id)
            assertEquals(1, removal.removedRelationCount)
            assertEquals(listOf(white.id to 0), database.taskColorDao().colorsOfTask(task).map { it.colorId to it.slotIndex })
        }

    // -------------------------------------------------------------- editing

    @Test
    fun `a refused change leaves the name and the value as they were`() =
        runBlocking<Unit> {
            val made = database.colorDao().addColorToEndOfCatalogue(IdGenerator.Random.newId(), "Bordo", "#7B1F2B")

            refuse { it.startsWith("UPDATE COLORS") }
            val failure =
                assertFailsWith<ColorSetupException> {
                    store.editColor(made.id, made.canonicalName, made.hex, "Vişne", "#8E1B2F")
                }
            driver.disarm()

            assertEquals(ColorSetupFailure.COULD_NOT_SAVE, failure.failure)
            val now = assertNotNull(database.colorDao().colorById(made.id))
            assertEquals("Bordo", now.canonicalName)
            assertEquals("#7B1F2B", now.hex)
            assertEquals("bordo", now.normalizedName, "the normalised name drifted from the name")
        }

    // ------------------------------------------------------------ restoring

    @Test
    fun `a restore refused on its second colour puts the first one back too`() =
        runBlocking<Unit> {
            database.colorDao().deleteColorTheUserHasConfirmed(white.id)
            database.colorDao().deleteColorTheUserHasConfirmed(black.id)
            val before = database.colorDao().allColors()

            refuse(occurrence = 2) { it.startsWith("INSERT") && "COLORS" in it }
            assertFailsWith<ColorSetupException> { store.restoreMissingBaseColors() }
            driver.disarm()

            assertEquals(before, database.colorDao().allColors(), "half a restore was left behind")
            assertNull(database.colorDao().colorById(white.id), "the first colour stayed in")
        }

    @Test
    fun `a write that will not land is not dressed up as a colour that turned up`() =
        runBlocking<Unit> {
            // The trap refuses the insert without anything being in the way: the
            // identity is free, the name is free, nothing is known by it. Calling
            // that a colour appearing underneath would send the user off to
            // rename a colour that does not exist.
            database.colorDao().deleteColorTheUserHasConfirmed(white.id)

            refuse { it.startsWith("INSERT") && "COLORS" in it }
            val failure = assertFailsWith<ColorSetupException> { store.restoreMissingBaseColors() }
            driver.disarm()

            assertEquals(ColorSetupFailure.COULD_NOT_SAVE, failure.failure)
            assertNull(database.colorDao().colorById(white.id))
        }

    @Test
    fun `a refused restore leaves the catalogue able to try again`() =
        runBlocking<Unit> {
            database.colorDao().deleteColorTheUserHasConfirmed(white.id)

            refuse { it.startsWith("INSERT") && "COLORS" in it }
            assertFailsWith<ColorSetupException> { store.restoreMissingBaseColors() }
            driver.disarm()

            assertEquals(BaseColorRestore.Restored(listOf("Beyaz")), store.restoreMissingBaseColors())
            assertNotNull(database.colorDao().colorById(white.id))
        }

    @Test
    fun `a removal costs the same number of reads whether one task uses it or forty two`() =
        runBlocking<Unit> {
            assertEquals(readsToRemoveAColourUsedBy(1), readsToRemoveAColourUsedBy(42))
        }

    /**
     * The statements a removal runs, on its own database so the fixture is not
     * being counted with it.
     */
    private fun readsToRemoveAColourUsedBy(taskCount: Int): List<String> {
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
