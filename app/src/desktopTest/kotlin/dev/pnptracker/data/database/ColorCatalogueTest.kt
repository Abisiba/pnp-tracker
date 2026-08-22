package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.domain.rules.normalizeColorTerm
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The colour catalogue of a database created fresh at version 2. */
class ColorCatalogueTest {
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

    @Test
    fun `a fresh database is seeded with the twelve colors in order`() =
        runBlocking<Unit> {
            val colors = database.colorDao().allColors()

            assertEquals(12, colors.size)
            assertEquals(
                listOf(
                    "Beyaz",
                    "Siyah",
                    "Gri",
                    "Kahverengi",
                    "Kırmızı",
                    "Sarı",
                    "Yeşil",
                    "Mavi",
                    "Açık Mavi",
                    "Turuncu",
                    "Mor",
                    "Pembe",
                ),
                colors.map { it.canonicalName },
            )
            assertEquals(seedColors.map { it.id }, colors.map { it.id })
            assertEquals(seedColors.map { it.hex }, colors.map { it.hex })
            assertEquals((0..11).toList(), colors.map { it.sortOrder })
        }

    @Test
    fun `reopening a fresh database does not repeat the seed`() =
        runBlocking<Unit> {
            database.colorDao().allColors()
            database.close()
            database = DatabaseFactory().open(directory.databaseFile)

            assertEquals(12, database.colorDao().allColors().size)
        }

    @Test
    fun `differently typed spellings of a color resolve to the same row`() =
        runBlocking<Unit> {
            val spellings = listOf("gri", "Gri", "GRİ", "GRI", "  gri  ")

            val resolved = spellings.map { assertNotNull(database.colorDao().resolve(it), "unresolved: $it") }

            assertEquals(1, resolved.map { it.id }.toSet().size)
            assertEquals("Gri", resolved.first().canonicalName)
        }

    @Test
    fun `a two word color name tolerates extra spaces and dotted capitals`() =
        runBlocking<Unit> {
            val spellings = listOf("Açık Mavi", "  AÇIK   MAVİ", "açik mavi", "AÇIK MAVI")

            val resolved = spellings.map { assertNotNull(database.colorDao().resolve(it), "unresolved: $it") }

            assertEquals(1, resolved.map { it.id }.toSet().size)
            assertEquals("Açık Mavi", resolved.first().canonicalName)
        }

    @Test
    fun `an alias resolves to its color`() =
        runBlocking<Unit> {
            val red = assertNotNull(database.colorDao().colorByNormalizedName(normalizeColorTerm("Kırmızı")))

            database.colorDao().addAlias(red.id, "KIRMIZI TON")

            assertEquals(red.id, assertNotNull(database.colorDao().resolve("kirmizi ton")).id)
            assertEquals(red.id, assertNotNull(database.colorDao().colorByNormalizedAlias("kirmizi ton")).id)
            assertEquals(listOf("KIRMIZI TON"), database.colorDao().aliasesOf(red.id).map { it.alias })
        }

    @Test
    fun `an alias that clashes with a color name is rejected`() =
        runBlocking<Unit> {
            val red = assertNotNull(database.colorDao().resolve("Kırmızı"))

            val failure = assertFailsWith<IllegalArgumentException> { database.colorDao().addAlias(red.id, "MAVI") }

            assertContains(failure.message.orEmpty(), "Mavi")
            assertEquals(emptyList(), database.colorDao().aliasesOf(red.id))
        }

    @Test
    fun `the same alias cannot point at two colors`() =
        runBlocking<Unit> {
            val red = assertNotNull(database.colorDao().resolve("Kırmızı"))
            val blue = assertNotNull(database.colorDao().resolve("Mavi"))
            database.colorDao().addAlias(red.id, "Ton A")

            val failure = assertFailsWith<IllegalArgumentException> { database.colorDao().addAlias(blue.id, "TON A") }

            assertContains(failure.message.orEmpty(), "Kırmızı")
            assertEquals(red.id, assertNotNull(database.colorDao().resolve("ton a")).id)
        }

    @Test
    fun `a blank alias is rejected`() =
        runBlocking<Unit> {
            val red = assertNotNull(database.colorDao().resolve("Kırmızı"))

            assertFailsWith<IllegalArgumentException> { database.colorDao().addAlias(red.id, "   ") }

            assertEquals(emptyList(), database.colorDao().aliasesOf(red.id))
        }

    // The two tests below pin what the v3 foreign keys do, not what the product
    // promises. PLAN 5.9 has the user delete a colour outright: the relations are
    // removed first and the tasks survive without one. That flow arrives with the
    // v4 slice; until it does, a bare DELETE is still refused, and these say so.
    @Test
    fun `the current schema refuses a bare delete of a color a task uses`() =
        runBlocking<Unit> {
            val grey = assertNotNull(database.colorDao().resolve("Gri"))
            val task = insertGameItemAndTask(database)
            database.taskColorDao().addRelation(TaskColorEntity.required(task.id, grey.id))

            val failure =
                assertFailsWith<SQLiteException> {
                    database.useWriterConnection { transactor ->
                        transactor.usePrepared("DELETE FROM colors WHERE id = ?") { statement ->
                            statement.bindText(1, grey.id.toString())
                            statement.step()
                        }
                    }
                }

            assertContains(failure.message.orEmpty().uppercase(), "FOREIGN KEY")
            assertNotNull(database.colorDao().colorById(grey.id))
        }

    @Test
    fun `the current schema refuses a bare delete of a color that owns an alias`() =
        runBlocking<Unit> {
            val purple = assertNotNull(database.colorDao().resolve("Mor"))
            database.colorDao().addAlias(purple.id, "Lila")

            val failure =
                assertFailsWith<SQLiteException> {
                    database.useWriterConnection { transactor ->
                        transactor.usePrepared("DELETE FROM colors WHERE id = ?") { statement ->
                            statement.bindText(1, purple.id.toString())
                            statement.step()
                        }
                    }
                }

            assertContains(failure.message.orEmpty().uppercase(), "FOREIGN KEY")
        }

    @Test
    fun `color identifiers and flags are stored as canonical text and integers`() =
        runBlocking<Unit> {
            val stored =
                database.useReaderConnection { transactor ->
                    transactor.usePrepared(
                        "SELECT typeof(id), typeof(sort_order), typeof(is_archived), id, normalized_name " +
                            "FROM colors ORDER BY sort_order LIMIT 1",
                    ) { statement ->
                        statement.step()
                        listOf(
                            statement.getText(0).uppercase(),
                            statement.getText(1).uppercase(),
                            statement.getText(2).uppercase(),
                            statement.getText(3),
                            statement.getText(4),
                        )
                    }
                }

            assertEquals(listOf("TEXT", "INTEGER", "INTEGER"), stored.take(3))
            assertEquals(seedColors.first().id.toString(), stored[3])
            assertEquals("beyaz", stored[4])
            assertNull(database.colorDao().colorByNormalizedName("Beyaz"))
        }
}
