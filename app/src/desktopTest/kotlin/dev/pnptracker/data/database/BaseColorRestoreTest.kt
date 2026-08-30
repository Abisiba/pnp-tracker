package dev.pnptracker.data.database

import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.domain.colors.BaseColorRestore
import dev.pnptracker.domain.colors.BaseColorRestoreBlock
import dev.pnptracker.domain.colors.baseColors
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Putting back the base colours the catalogue has lost.
 *
 * PLAN 5.8 asks for one narrow thing and forbids several wide ones: only what is
 * missing comes back, nothing that is there is touched, and one clash stops all
 * of it. Missing means the fixed identity is gone and nothing else — a base
 * colour the user renamed and recoloured is still that colour, and every test
 * here that changes one proves it is left alone.
 */
class BaseColorRestoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: ColorCatalogueStore
    private var realDatabaseExistedBefore = false

    private val white = baseColors[0]
    private val black = baseColors[1]
    private val grey = baseColors[2]

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

    private suspend fun remove(id: EntityId) = database.colorDao().deleteColorTheUserHasConfirmed(id)

    private suspend fun aCustomColor(
        name: String,
        hex: String = "#7B1F2B",
    ) = database.colorDao().addColorToEndOfCatalogue(IdGenerator.Random.newId(), name, hex)

    private suspend fun rowOf(id: EntityId) = database.colorDao().colorById(id)

    @Test
    fun `a missing base colour comes back under its own fixed identity`() =
        runBlocking<Unit> {
            remove(black.id)

            val outcome = store.restoreMissingBaseColors()

            assertEquals(BaseColorRestore.Restored(listOf("Siyah")), outcome)
            assertNotNull(rowOf(black.id), "the colour came back under a different identity")
        }

    @Test
    fun `several missing base colours come back together`() =
        runBlocking<Unit> {
            remove(white.id)
            remove(black.id)
            remove(grey.id)

            val outcome = store.restoreMissingBaseColors()

            assertEquals(BaseColorRestore.Restored(listOf("Beyaz", "Siyah", "Gri")), outcome)
            assertEquals(12, database.colorDao().allColors().size)
        }

    @Test
    fun `what comes back is the default name and the default value`() =
        runBlocking<Unit> {
            remove(grey.id)

            store.restoreMissingBaseColors()

            val back = assertNotNull(rowOf(grey.id))
            assertEquals("Gri", back.canonicalName)
            assertEquals("gri", back.normalizedName)
            assertEquals("#808080", back.hex)
        }

    @Test
    fun `a restored colour goes back to its own place, not to the end`() =
        runBlocking<Unit> {
            aCustomColor("Bordo")
            remove(grey.id)

            store.restoreMissingBaseColors()

            assertEquals(2, assertNotNull(rowOf(grey.id)).sortOrder)
            assertEquals(
                listOf("Beyaz", "Siyah", "Gri", "Kahverengi"),
                database
                    .colorDao()
                    .allColors()
                    .take(4)
                    .map { it.canonicalName },
                "the colour came back at the wrong end of the catalogue",
            )
        }

    @Test
    fun `a base colour renamed and recoloured is not missing and is not touched`() =
        runBlocking<Unit> {
            store.editColor(black.id, "Siyah", "#111111", "Koyu", "#101820")
            // Put its old place out of the way too, the way a user might.
            database.colorDao().writeColorNameAndValue(black.id, "Koyu", "koyu", "#101820")

            val plan = store.previewBaseColorRestore()
            val outcome = store.restoreMissingBaseColors()

            assertTrue(plan.isNothingMissing, "an edited base colour was offered for restoring")
            assertEquals(BaseColorRestore.NothingMissing, outcome)
            val still = assertNotNull(rowOf(black.id))
            assertEquals("Koyu", still.canonicalName, "the user's own name was overwritten")
            assertEquals("#101820", still.hex, "the user's own value was overwritten")
        }

    @Test
    fun `being missing is decided by identity and never by name`() =
        runBlocking<Unit> {
            remove(grey.id)
            // Another colour now carries a name nothing like the missing one's.
            aCustomColor("Duman", "#808080")
            // And a base colour that is present wears the missing one's default name.
            store.editColor(black.id, "Siyah", "#111111", "Gri", "#111111")

            val plan = store.previewBaseColorRestore()

            assertEquals(listOf("Gri"), plan.missing.map { it.canonicalName })
            assertEquals(
                listOf("Gri" to BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR),
                plan.blocked.map { it.canonicalName to it.reason },
                "the name being worn by a present colour has to block it",
            )
        }

    @Test
    fun `the colours the user made are left exactly as they are`() =
        runBlocking<Unit> {
            val bordo = aCustomColor("Bordo")
            val visne = aCustomColor("Vişne", "#8E1B2F")
            remove(grey.id)
            val before = database.colorDao().allColors().filter { it.id == bordo.id || it.id == visne.id }

            store.restoreMissingBaseColors()

            assertEquals(before, database.colorDao().allColors().filter { it.id == bordo.id || it.id == visne.id })
        }

    @Test
    fun `the base colours still in the catalogue are left exactly as they are`() =
        runBlocking<Unit> {
            remove(grey.id)
            val before = database.colorDao().allColors()

            store.restoreMissingBaseColors()

            assertEquals(before, database.colorDao().allColors().filterNot { it.id == grey.id })
        }

    @Test
    fun `a default name another colour carries stops the whole restore`() =
        runBlocking<Unit> {
            remove(white.id)
            remove(black.id)
            aCustomColor("BEYAZ", "#FAFAFA")

            val outcome = store.restoreMissingBaseColors()

            val blocked = assertIs<BaseColorRestore.Blocked>(outcome)
            assertEquals(
                listOf("Beyaz" to BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR),
                blocked.blocked.map { it.canonicalName to it.reason },
            )
            assertNull(rowOf(white.id))
            assertNull(rowOf(black.id), "the colour with no clash was written anyway")
        }

    @Test
    fun `a default name that is somebody's alias stops the whole restore`() =
        runBlocking<Unit> {
            remove(white.id)
            remove(black.id)
            database.colorDao().addAlias(baseColors[7].id, "Beyaz")

            val outcome = store.restoreMissingBaseColors()

            val blocked = assertIs<BaseColorRestore.Blocked>(outcome)
            assertEquals(
                listOf("Beyaz" to BaseColorRestoreBlock.NAME_TAKEN_BY_ALIAS),
                blocked.blocked.map { it.canonicalName to it.reason },
            )
            assertNull(rowOf(black.id), "the colour with no clash was written anyway")
        }

    @Test
    fun `every colour that is in the way is named, not only the first`() =
        runBlocking<Unit> {
            remove(white.id)
            remove(black.id)
            remove(grey.id)
            aCustomColor("beyaz", "#FAFAFA")
            database.colorDao().addAlias(baseColors[7].id, "SİYAH")

            val outcome = store.restoreMissingBaseColors()

            val blocked = assertIs<BaseColorRestore.Blocked>(outcome)
            assertEquals(
                listOf(
                    "Beyaz" to BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR,
                    "Siyah" to BaseColorRestoreBlock.NAME_TAKEN_BY_ALIAS,
                ),
                blocked.blocked.map { it.canonicalName to it.reason },
                "the user would have had to hit the same wall twice",
            )
        }

    @Test
    fun `a value another colour already uses is no obstacle at all`() =
        runBlocking<Unit> {
            remove(grey.id)
            aCustomColor("Duman", "#808080")

            val outcome = store.restoreMissingBaseColors()

            assertEquals(BaseColorRestore.Restored(listOf("Gri")), outcome)
            assertEquals(
                listOf("Gri", "Duman"),
                database.colorDao().colorsWithHex("#808080").map { it.canonicalName },
            )
        }

    @Test
    fun `nothing is written to the alias table`() =
        runBlocking<Unit> {
            remove(white.id)
            remove(black.id)

            store.restoreMissingBaseColors()

            assertEquals(emptyList(), database.colorDao().allAliases())
            assertEquals(emptyList(), database.colorDao().aliasesOf(white.id))
        }

    @Test
    fun `nothing is written to the task relations, and no task is invented`() =
        runBlocking<Unit> {
            val task =
                insertGameCellAndTask(database, gameName = "Harmonies", columnType = CellColumnType.THREE_D).also {
                    database.taskColorDao().insert(TaskColorEntity(it.id, baseColors[4].id, 0))
                }
            remove(white.id)
            val relationsBefore = database.taskColorDao().colorsOfTask(task.id)

            store.restoreMissingBaseColors()

            assertEquals(relationsBefore, database.taskColorDao().colorsOfTask(task.id))
            assertEquals(emptyList(), database.taskColorDao().tasksUsingColor(white.id), "a task was given a colour")
            assertEquals(emptyList(), database.taskProgressDao().stagesOfTask(task.id))
            assertEquals(emptyList(), database.taskProgressDao().progressEventsOfTask(task.id))
        }

    @Test
    fun `restoring when nothing is missing writes nothing and says so`() =
        runBlocking<Unit> {
            val driver = CountingSqliteDriver()
            val ownDirectory = TemporaryDatabaseDirectory()
            val own = DatabaseFactory(driver = driver).open(ownDirectory.databaseFile)
            try {
                val ownStore = ColorCatalogueStore(own.colorDao())
                own.colorDao().allColors()

                driver.start()
                val outcome = ownStore.restoreMissingBaseColors()
                val written =
                    driver.stop().filter {
                        val sql = it.trimStart().uppercase()
                        sql.startsWith("INSERT") || sql.startsWith("UPDATE") || sql.startsWith("DELETE")
                    }

                assertEquals(BaseColorRestore.NothingMissing, outcome)
                assertEquals(emptyList(), written)
            } finally {
                own.close()
                ownDirectory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
                ownDirectory.delete()
            }
        }

    @Test
    fun `a clash that has not been cleared is reported again rather than treated as nothing to do`() =
        runBlocking<Unit> {
            remove(white.id)
            aCustomColor("Beyaz", "#FAFAFA")

            val first = store.restoreMissingBaseColors()
            val second = store.restoreMissingBaseColors()

            assertEquals(first, second)
            assertIs<BaseColorRestore.Blocked>(second)
            assertNull(rowOf(white.id))
        }

    @Test
    fun `running it a second time after it worked does nothing`() =
        runBlocking<Unit> {
            remove(white.id)
            store.restoreMissingBaseColors()
            val after = database.colorDao().allColors()

            val second = store.restoreMissingBaseColors()

            assertEquals(BaseColorRestore.NothingMissing, second)
            assertEquals(after, database.colorDao().allColors())
        }

    @Test
    fun `two restores at once put the colour back only once`() =
        runBlocking<Unit> {
            remove(white.id)
            remove(black.id)

            val outcomes =
                listOf(
                    async { store.restoreMissingBaseColors() },
                    async { store.restoreMissingBaseColors() },
                ).awaitAll()

            assertEquals(1, outcomes.count { it is BaseColorRestore.Restored }, "the same colours were written twice")
            assertEquals(12, database.colorDao().allColors().size)
            assertEquals(
                12,
                database
                    .colorDao()
                    .allColors()
                    .map { it.id }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun `the preview says exactly what the restore would do`() =
        runBlocking<Unit> {
            remove(white.id)
            remove(black.id)
            aCustomColor("Beyaz", "#FAFAFA")

            val plan = store.previewBaseColorRestore()

            assertEquals(listOf("Beyaz", "Siyah"), plan.missing.map { it.canonicalName })
            assertEquals(listOf("Beyaz"), plan.blocked.map { it.canonicalName })
            assertTrue(!plan.canRestore, "a preview with something in the way offered to go ahead")
            assertTrue(!plan.isNothingMissing)
        }
}
