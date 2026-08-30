package dev.pnptracker.data.database

import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.repository.ColorCatalogueStore
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
import kotlin.test.assertTrue

/**
 * Changing what a colour is called and what it looks like.
 *
 * PLAN 5.7 makes the twelve ordinary records, so every one of these is asked of
 * a base colour as readily as of one the user made. The subject throughout is
 * what stays the same: the identity, the place in the catalogue, and every task
 * that was ever produced in the colour.
 */
class ColorEditTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: ColorCatalogueStore
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = ColorCatalogueStore(database.colorDao())
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun colorNamed(name: String) = assertNotNull(database.colorDao().resolve(name))

    private suspend fun aCustomColor(
        name: String = "Bordo",
        hex: String = "#7B1F2B",
    ) = database.colorDao().addColorToEndOfCatalogue(IdGenerator.Random.newId(), name, hex)

    private suspend fun aTaskIn(
        colorIds: List<EntityId>,
        gameName: String = "Harmonies",
    ): EntityId {
        val task = insertGameCellAndTask(database, gameName = gameName, columnType = CellColumnType.THREE_D)
        colorIds.forEachIndexed { slot, colorId ->
            database.taskColorDao().insert(TaskColorEntity(taskId = task.id, colorId = colorId, slotIndex = slot))
        }
        return task.id
    }

    @Test
    fun `a custom colour takes a new name and a new value together`() =
        runBlocking<Unit> {
            val made = aCustomColor()

            store.editColor(made.id, made.canonicalName, made.hex, "Vişne", "#8E1B2F")

            val now = assertNotNull(database.colorDao().colorById(made.id))
            assertEquals("Vişne", now.canonicalName)
            assertEquals("#8E1B2F", now.hex)
            assertEquals("vişne", now.normalizedName)
        }

    @Test
    fun `a base colour is changed like any other record`() =
        runBlocking<Unit> {
            val black = colorNamed("Siyah")

            store.editColor(black.id, black.canonicalName, black.hex, "Koyu", "#101820")

            val now = assertNotNull(database.colorDao().colorById(black.id))
            assertEquals("Koyu", now.canonicalName)
            assertEquals("#101820", now.hex)
            // Still the same base colour: PLAN 5.7 lets the twelve be renamed,
            // and the identity is what says which one this is.
            assertTrue(baseColors.any { it.id == now.id })
        }

    @Test
    fun `the identity and the place in the catalogue survive a change`() =
        runBlocking<Unit> {
            val grey = colorNamed("Gri")

            store.editColor(grey.id, grey.canonicalName, grey.hex, "Duman", "#9E9E9E")

            val now = assertNotNull(database.colorDao().colorById(grey.id))
            assertEquals(grey.id, now.id)
            assertEquals(grey.sortOrder, now.sortOrder)
            assertEquals(
                listOf("Beyaz", "Siyah", "Duman", "Kahverengi"),
                database
                    .colorDao()
                    .allColors()
                    .take(4)
                    .map { it.canonicalName },
                "the changed colour moved in the catalogue",
            )
        }

    @Test
    fun `the tasks made in a colour keep their relations exactly`() =
        runBlocking<Unit> {
            val grey = colorNamed("Gri")
            val black = colorNamed("Siyah")
            val single = aTaskIn(listOf(grey.id), gameName = "Bir")
            val several = aTaskIn(listOf(black.id, grey.id), gameName = "Iki")
            val before = database.taskColorDao().colorsOfTask(several)

            store.editColor(grey.id, grey.canonicalName, grey.hex, "Duman", "#9E9E9E")

            assertEquals(listOf(grey.id), database.taskColorDao().colorsOfTask(single).map { it.colorId })
            assertEquals(before, database.taskColorDao().colorsOfTask(several), "the colour list was disturbed")
        }

    @Test
    fun `a single colour task shows the new name and value at once`() =
        runBlocking<Unit> {
            val grey = colorNamed("Gri")
            val task = aTaskIn(listOf(grey.id))

            store.editColor(grey.id, grey.canonicalName, grey.hex, "Duman", "#9E9E9E")

            // Nothing was copied onto the task, so there is nothing to update:
            // the task points at the colour and the colour is what changed.
            val held = database.taskColorDao().colorsOfTask(task).single()
            val now = assertNotNull(database.colorDao().colorById(held.colorId))
            assertEquals("Duman", now.canonicalName)
            assertEquals("#9E9E9E", now.hex)
        }

    @Test
    fun `every piece of a several colour task follows the change`() =
        runBlocking<Unit> {
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val blue = colorNamed("Mavi")
            val task = aTaskIn(listOf(white.id, red.id, blue.id))

            store.editColor(red.id, red.canonicalName, red.hex, "Gül", "#C2185B")

            val drawn =
                database.taskColorDao().colorsOfTask(task).map { row ->
                    assertNotNull(database.colorDao().colorById(row.colorId)).let { it.canonicalName to it.hex }
                }
            assertEquals(
                listOf("Beyaz" to "#FFFFFF", "Gül" to "#C2185B", "Mavi" to "#1E88E5"),
                drawn,
                "the split name would not be drawn in the new colour",
            )
        }

    @Test
    fun `a name another colour already carries is refused`() =
        runBlocking<Unit> {
            val made = aCustomColor()

            val failure =
                assertFailsWith<ColorSetupException> {
                    store.editColor(made.id, made.canonicalName, made.hex, "Mavi", made.hex)
                }

            assertEquals(ColorSetupFailure.NAME_ALREADY_USED, failure.failure)
            assertEquals("Bordo", assertNotNull(database.colorDao().colorById(made.id)).canonicalName)
        }

    @Test
    fun `a name that is another colour's alias is refused in its own words`() =
        runBlocking<Unit> {
            val made = aCustomColor()
            database.colorDao().addAlias(colorNamed("Mor").id, "Lila")

            val failure =
                assertFailsWith<ColorSetupException> {
                    store.editColor(made.id, made.canonicalName, made.hex, "LİLA", made.hex)
                }

            assertEquals(ColorSetupFailure.NAME_IS_ANOTHER_COLORS_ALIAS, failure.failure)
        }

    @Test
    fun `a colour is not told its own name is taken`() =
        runBlocking<Unit> {
            val made = aCustomColor()

            // The same name with a new value: the clash check has to leave this
            // colour out of the comparison or nothing could ever be recoloured.
            store.editColor(made.id, made.canonicalName, made.hex, made.canonicalName, "#5D1420")

            assertEquals("#5D1420", assertNotNull(database.colorDao().colorById(made.id)).hex)
        }

    @Test
    fun `changing only the letter case is a real change and is written`() =
        runBlocking<Unit> {
            val made = aCustomColor(name = "gri ton")

            store.editColor(made.id, made.canonicalName, made.hex, "Gri Ton", made.hex)

            val now = assertNotNull(database.colorDao().colorById(made.id))
            assertEquals("Gri Ton", now.canonicalName, "the user's own spelling was thrown away")
            assertEquals("gri ton", now.normalizedName)
        }

    @Test
    fun `the old name does not become an alias`() =
        runBlocking<Unit> {
            val made = aCustomColor()

            store.editColor(made.id, made.canonicalName, made.hex, "Vişne", made.hex)

            assertEquals(emptyList(), database.colorDao().aliasesOf(made.id))
            assertEquals(0, database.colorDao().allAliases().size)
            // The name is free again, which is the point of not keeping it.
            aCustomColor(name = "Bordo", hex = "#111213")
            assertEquals("Bordo", assertNotNull(database.colorDao().resolve("bordo")).canonicalName)
        }

    @Test
    fun `saving the very same name and value writes nothing at all`() =
        runBlocking<Unit> {
            val driver = CountingSqliteDriver()
            val ownDirectory = TemporaryDatabaseDirectory()
            val own = DatabaseFactory(driver = driver).open(ownDirectory.databaseFile)
            try {
                val ownStore = ColorCatalogueStore(own.colorDao())
                val made = own.colorDao().addColorToEndOfCatalogue(IdGenerator.Random.newId(), "Bordo", "#7B1F2B")

                driver.start()
                ownStore.editColor(made.id, made.canonicalName, made.hex, made.canonicalName, made.hex)
                val written = driver.stop().filter { it.trimStart().startsWith("UPDATE", ignoreCase = true) }

                assertEquals(emptyList(), written, "a colour that did not change was written back anyway")
            } finally {
                own.close()
                ownDirectory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
                ownDirectory.delete()
            }
        }

    @Test
    fun `a colour that has been removed cannot be changed`() =
        runBlocking<Unit> {
            val made = aCustomColor()
            database.colorDao().deleteColorTheUserHasConfirmed(made.id)

            val failure =
                assertFailsWith<ColorSetupException> {
                    store.editColor(made.id, made.canonicalName, made.hex, "Vişne", made.hex)
                }

            assertEquals(ColorSetupFailure.COLOR_NO_LONGER_EXISTS, failure.failure)
        }

    @Test
    fun `a colour somebody else changed first is refused rather than overwritten`() =
        runBlocking<Unit> {
            val made = aCustomColor()
            // Somebody else got there while the form was open.
            store.editColor(made.id, made.canonicalName, made.hex, "Vişne", "#8E1B2F")

            val failure =
                assertFailsWith<ColorSetupException> {
                    store.editColor(made.id, made.canonicalName, made.hex, "Kiraz", "#AA0022")
                }

            assertEquals(ColorSetupFailure.COLOR_CHANGED_MEANWHILE, failure.failure)
            val now = assertNotNull(database.colorDao().colorById(made.id))
            assertEquals("Vişne", now.canonicalName, "the other change was thrown away")
            assertEquals("#8E1B2F", now.hex)
        }

    @Test
    fun `a refused change leaves the name and the value both as they were`() =
        runBlocking<Unit> {
            val made = aCustomColor()

            assertFailsWith<ColorSetupException> {
                store.editColor(made.id, made.canonicalName, made.hex, "Mavi", "#000102")
            }

            val now = assertNotNull(database.colorDao().colorById(made.id))
            assertEquals("Bordo", now.canonicalName, "half of the change was written")
            assertEquals("#7B1F2B", now.hex, "half of the change was written")
        }

    @Test
    fun `sharing a value with another colour is no obstacle`() =
        runBlocking<Unit> {
            val made = aCustomColor()
            val grey = colorNamed("Gri")

            store.editColor(made.id, made.canonicalName, made.hex, made.canonicalName, grey.hex)

            assertEquals(
                listOf("Gri", "Bordo"),
                database.colorDao().colorsWithHex(grey.hex).map { it.canonicalName },
                "two colours may look the same under different names",
            )
        }

    @Test
    fun `changing a colour writes to the colour table and to nothing else`() =
        runBlocking<Unit> {
            val driver = CountingSqliteDriver()
            val ownDirectory = TemporaryDatabaseDirectory()
            val own = DatabaseFactory(driver = driver).open(ownDirectory.databaseFile)
            try {
                val ownStore = ColorCatalogueStore(own.colorDao())
                val made = own.colorDao().addColorToEndOfCatalogue(IdGenerator.Random.newId(), "Bordo", "#7B1F2B")

                driver.start()
                ownStore.editColor(made.id, made.canonicalName, made.hex, "Vişne", "#8E1B2F")
                val touched = driver.stop().map { it.lowercase() }

                listOf("tasks", "task_colors", "task_stages", "progress_events", "cell_segments", "game_cells").forEach { table ->
                    assertTrue(
                        touched.none { it.contains(table) },
                        "changing a colour reached $table",
                    )
                }
            } finally {
                own.close()
                ownDirectory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
                ownDirectory.delete()
            }
        }
}
