package dev.pnptracker.platform.startup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.insertVersion1Game
import dev.pnptracker.data.database.insertVersion3ImportBatch
import dev.pnptracker.data.database.insertVersion3RawImportBlock
import dev.pnptracker.domain.model.IdGenerator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the consistent snapshot mechanism really does on this machine.
 *
 * This is R11, measured rather than assumed. PLAN 14.4.9 allows `VACUUM INTO`
 * and requires its behaviour to be proved in the application rather than taken
 * from documentation, and the reason is the awkward case: a database whose most
 * recent rows are still in an uncheckpointed write-ahead log, which is what a
 * crash leaves and exactly what a person's database will look like if the
 * application died the last time they used it.
 *
 * Everything the production code depends on is measured here, including the one
 * negative result that decided its shape — that an ordinary read-write open
 * rewrites the source file. The alternative PLAN forbids, copying the database,
 * `-wal` and `-shm` one after another, is not used as a mechanism anywhere; it
 * appears in these tests only as a way of *building* a crashed fixture, where
 * the test itself guarantees the three files agree.
 */
class ConsistentDatabaseCloneTest {
    private lateinit var home: Path
    private var realDatabaseExisted = false
    private val open = mutableListOf<SQLiteConnection>()

    private val clone = ConsistentDatabaseClone()

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-clone-measurement")
    }

    @AfterTest
    fun deleteHome() {
        open.forEach { runCatching { it.close() } }
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the test changed whether the real application database exists",
        )
        deleteTemporaryTree(home)
    }

    /** A version 3 database with a game and an import, all of it still in the log. */
    private fun aHotVersion3(name: String = "kaynak.db"): Path {
        val file = home.resolve(name)
        open +=
            openWithHotWal(file, version = 3) { connection ->
                val batchId = IdGenerator.Random.newId()
                insertVersion1Game(connection, gameId = IdGenerator.Random.newId(), name = "Harmonies")
                insertVersion3ImportBatch(connection, batchId = batchId, fileName = "liste.xlsx")
                insertVersion3RawImportBlock(connection, blockId = IdGenerator.Random.newId(), batchId = batchId)
            }
        return file
    }

    // ------------------------------------------------------- what it must do

    @Test
    fun `rows that are still in the log are in the clone`() {
        // The measurement that matters most. Before this, the database file
        // itself holds almost nothing: everything written above is in the `-wal`
        // beside it, and a mechanism that read only the database file would
        // produce a clone of an empty library.
        val source = aHotVersion3()
        val target = home.resolve("klon.db")

        clone.cloneInto(source, target)

        assertEquals(rowCountsOf(source), rowCountsOf(target), "the clone lost rows that were still in the log")
        assertTrue(rowCountsOf(target).getValue("games") > 0, "the fixture wrote nothing to clone")
        assertTrue(rowCountsOf(target).getValue("import_batches") > 0)
    }

    @Test
    fun `the clone keeps the schema version it was taken from`() {
        val source = aHotVersion3()
        val target = home.resolve("klon.db")

        clone.cloneInto(source, target)

        // Both ways of asking, because the production code asks both: the header
        // byte the ownership test reads, and the PRAGMA the gate reads.
        assertEquals(3, schemaVersionOf(target))
        assertEquals(3, clone.schemaVersionOf(target))
        val header = Files.readAllBytes(target)
        assertEquals(3, (header[60].toInt() shl 24) or (header[61].toInt() shl 16) or (header[62].toInt() shl 8) or header[63].toInt())
    }

    @Test
    fun `the clone is whole and holds together`() {
        val source = aHotVersion3()
        val target = home.resolve("klon.db")

        clone.cloneInto(source, target)

        assertTrue(clone.isWholeAndConsistent(target), "the clone failed its own integrity check")
    }

    @Test
    fun `nothing of the source changes, byte for byte`() {
        val source = aHotVersion3()
        val before = sidecarsOf(source).associateWith(::digestOf)

        clone.cloneInto(source, home.resolve("klon.db"))
        clone.schemaVersionOf(source)
        clone.tableRowCounts(source)
        clone.isWholeAndConsistent(source)

        val after = sidecarsOf(source).associateWith(::digestOf)
        // The database and the log are what hold the data, and neither moves.
        assertEquals(before.keys.first(), after.keys.first())
        assertEquals(before.getValue(source), after.getValue(source), "the database file was rewritten")
        assertEquals(
            before.getValue(Path.of("$source-wal")),
            after.getValue(Path.of("$source-wal")),
            "the write-ahead log was rewritten",
        )
        // The shared-memory index is the one file a reader may touch: SQLite
        // builds it to read a log and it holds no data of its own. Measured and
        // stated rather than claimed not to happen.
        assertTrue(Files.exists(Path.of("$source-shm")))
    }

    @Test
    fun `no migration runs while a clone is taken`() {
        // The whole value of the raw half is that the code it protects against
        // never touched it. A migration having run would show as a changed
        // version and as the current schema's tables appearing.
        val source = aHotVersion3()
        val target = home.resolve("klon.db")

        clone.cloneInto(source, target)

        assertEquals(3, schemaVersionOf(target))
        assertEquals(3, schemaVersionOf(source))
        val tables = rowCountsOf(target).keys
        assertTrue("items" in tables, "the version 3 shape is gone, so something migrated it")
        assertFalse("history_events" in tables, "a table only version 7 has appeared in a version 3 clone")
        assertFalse("import_batch_cells" in tables, "a table only version 8 has appeared in a version 3 clone")
    }

    // ------------------------------------------------- the crashed database

    @Test
    fun `a database left behind by a crash can still be cloned`() {
        val source = aHotVersion3()
        val counts = rowCountsOf(source)
        val crashed = home.resolve("kaza.db")
        crashedCopyOf(source, crashed)
        val withoutSharedMemory = home.resolve("kaza-shmsiz.db")
        crashedCopyOf(source, withoutSharedMemory, keepSharedMemory = false)

        listOf(crashed, withoutSharedMemory).forEach { file ->
            val target = home.resolve("klon-${file.fileName}")
            clone.cloneInto(file, target)

            assertEquals(counts, rowCountsOf(target), "$file: the crashed log was not read")
            assertEquals(3, schemaVersionOf(target), "$file")
            assertTrue(clone.isWholeAndConsistent(target), "$file")
        }
    }

    @Test
    fun `the version of a crashed database is read without opening it for writing`() {
        val source = aHotVersion3()
        val crashed = home.resolve("kaza.db")
        crashedCopyOf(source, crashed, keepSharedMemory = false)
        val before = digestOf(crashed) to digestOf(Path.of("$crashed-wal"))

        assertEquals(3, clone.schemaVersionOf(crashed))

        assertEquals(before, digestOf(crashed) to digestOf(Path.of("$crashed-wal")))
    }

    // ------------------------------------------ why the connection is read-only

    @Test
    fun `an ordinary read-write open rewrites the source, which is why it is not used`() {
        // The negative result that decided the design. Nothing in production
        // opens the user's database this way before the snapshot, and this is
        // what would happen if it did: the log is checkpointed away and the
        // database file is rewritten before a single byte has been backed up.
        val source = aHotVersion3("karsilastirma.db")
        val crashed = home.resolve("karsilastirma-kaza.db")
        crashedCopyOf(source, crashed, keepSharedMemory = false)
        val before = digestOf(crashed)

        BundledSQLiteDriver().open(crashed.toAbsolutePath().toString()).use { connection ->
            connection.execSQL("VACUUM INTO '${home.resolve("klon-rw.db")}'")
        }

        assertFalse(before == digestOf(crashed), "a read-write open left the database untouched, so this test is stale")
        assertFalse(Files.exists(Path.of("$crashed-wal")), "the log survived a read-write open and close")
    }

    // --------------------------------------------------------------- refusals

    @Test
    fun `a file that is not a database is refused rather than cloned`() {
        val notADatabase = home.resolve("notlarim.txt")
        Files.write(notADatabase, "bu benim dosyam".encodeToByteArray())

        assertFailsWith<SQLiteException> { clone.schemaVersionOf(notADatabase) }
        assertFailsWith<SQLiteException> { clone.cloneInto(notADatabase, home.resolve("klon.db")) }
    }

    @Test
    fun `a database that is not there has no version and nothing to clone`() {
        assertEquals(NO_SCHEMA_YET, clone.schemaVersionOf(home.resolve("yok.db")))
        val empty = home.resolve("bos.db")
        Files.createFile(empty)
        assertEquals(NO_SCHEMA_YET, clone.schemaVersionOf(empty))
    }

    @Test
    fun `a target that already exists is refused, so nothing is ever written over`() {
        val source = aHotVersion3()
        val target = home.resolve("klon.db")
        Files.write(target, "önemli bir şey".encodeToByteArray())

        assertFailsWith<SQLiteException> { clone.cloneInto(source, target) }

        assertEquals("önemli bir şey", Files.readString(target))
    }
}
