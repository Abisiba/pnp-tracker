package dev.pnptracker.platform.startup

import androidx.sqlite.SQLiteConnection
import dev.pnptracker.data.database.CommittedSchema
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.insertVersion1Game
import dev.pnptracker.data.database.insertVersion3ImportBatch
import dev.pnptracker.data.database.insertVersion3RawImportBlock
import dev.pnptracker.data.database.insertVersion4Game
import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.domain.backup.automatic.StartupRefused
import dev.pnptracker.domain.backup.restore.BackupPlace
import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.BackupRejection
import dev.pnptracker.domain.backup.restore.CountingProbe
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.retention.AutomaticBackupKind
import dev.pnptracker.domain.backup.retention.automaticBackupNameOf
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.backupfiles.PathBackupInput
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.AtomicWriteException
import dev.pnptracker.platform.files.AtomicWriteFailure
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val MOMENT = LocalMoment(2026, 9, 11, 14, 33, 55)

/**
 * The two matched artefacts, built from real old databases.
 *
 * PLAN 14.4.9 asks for a set that is worth having: a raw half that the migration
 * code never ran over, a document half that the migration chain really produced,
 * and both proved before either counts. Every one of those claims is measured
 * here against databases built from the **committed** schema files, so what is
 * migrated is the shape that shipped rather than a hand-written imitation of it.
 *
 * Nothing about the user's own database is used or opened; each test makes its
 * own in a temporary home and deletes it.
 */
class MigrationSnapshotSetWriterTest {
    private lateinit var home: Path
    private lateinit var backups: Path
    private var realDatabaseExisted = false
    private val open = mutableListOf<SQLiteConnection>()
    private val workingRoots = mutableListOf<Path>()
    private val probeRoots = mutableListOf<Path>()

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-migration-set")
        backups = Files.createDirectories(home.resolve("data").resolve("backups"))
    }

    @AfterTest
    fun deleteHome() {
        open.forEach { runCatching { it.close() } }
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the test changed whether the real application database exists",
        )
        (workingRoots + probeRoots).forEach(::deleteTemporaryTree)
        deleteTemporaryTree(home)
    }

    // ------------------------------------------------------- what is wired up

    private fun realReader() =
        UntrustedBackupReader(
            TemporaryBackupProbe(
                temporaryDirectory = { Files.createTempDirectory("pnp-tracker-migration-set-probe").also(probeRoots::add) },
            ),
        )

    private fun writer(
        clone: ConsistentDatabaseClone = ConsistentDatabaseClone(),
        databases: DatabaseFactory = DatabaseFactory(),
        documents: AtomicFileWriter = AtomicFileWriter(temporarySuffix = ".part"),
        reader: UntrustedBackupReader = realReader(),
        moment: LocalMoment = MOMENT,
    ) = MigrationSnapshotSetWriter(
        backupsDirectory = backups,
        clone = clone,
        databases = databases,
        reader = reader,
        documents = documents,
        workingDirectory = { Files.createTempDirectory("pnp-tracker-migration-copy").also(workingRoots::add) },
        moment = { moment },
    )

    /**
     * An old database with something of the user's in it, still in the log.
     *
     * What goes in depends on the version, and not for convenience: PLAN 18 lets
     * a migration refuse rather than invent, and `Migration3To4` refuses a
     * version 1 to 3 database that still holds games, items, tasks or task
     * colours — there is no honest place to put them in the table model. So
     * those versions are given the records that really do cross the line, which
     * is the import trail, and games appear from version 4 on, where they have
     * a cell to live in. A fixture that ignored that would be measuring a
     * database this application deliberately will not migrate; that case has a
     * test of its own below.
     */
    private fun anOldDatabase(
        version: Int,
        name: String = "pnp.db",
    ): Path {
        val file = home.resolve(name)
        open +=
            openWithHotWal(file, version) { connection ->
                if (version in 3..7) {
                    val batchId = IdGenerator.Random.newId()
                    if (version == 3) {
                        insertVersion3ImportBatch(connection, batchId = batchId, fileName = "liste.xlsx")
                        insertVersion3RawImportBlock(connection, blockId = IdGenerator.Random.newId(), batchId = batchId)
                    }
                }
                if (version >= 4) {
                    insertVersion4Game(connection, gameId = IdGenerator.Random.newId(), name = "Harmonies")
                }
            }
        return file
    }

    /** The same, but holding what `Migration3To4` will not convert. */
    private fun anOldDatabaseWithLegacyGames(name: String = "eski.db"): Path {
        val file = home.resolve(name)
        open +=
            openWithHotWal(file, version = 3) { connection ->
                insertVersion1Game(connection, gameId = IdGenerator.Random.newId(), name = "Harmonies")
            }
        return file
    }

    private fun namesIn(folder: Path): List<String> =
        Files.list(folder).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }

    // ----------------------------------------------------------- the happy set

    @Test
    fun `a version three database produces a matched pair`() {
        val source = anOldDatabase(3)
        val counts = rowCountsOf(source)

        val set = writer().writeSetFor(source, fromSchemaVersion = 3)

        assertEquals(3, set.fromSchemaVersion)
        assertEquals(8, set.toSchemaVersion)
        val left = namesIn(backups)
        assertEquals(listOf("${set.setName}.db", "${set.setName}.json").sorted(), left)
        // The two halves are one set to the name reader, which is what lets
        // rotation count them together (PLAN 14.4.11).
        left.forEach { name ->
            val read = assertNotNull(automaticBackupNameOf(name), name)
            assertEquals(AutomaticBackupKind.MIGRATION, read.kind)
            assertEquals(set.setName, read.setName)
            assertEquals(3, read.fromSchemaVersion)
            assertEquals(8, read.toSchemaVersion)
        }
        // The raw half is the old database, untouched by any migration.
        val raw = backups.resolve("${set.setName}.db")
        assertEquals(3, schemaVersionOf(raw))
        assertEquals(counts, rowCountsOf(raw))
        assertTrue("items" in rowCountsOf(raw).keys, "the raw half was migrated, which is the one thing it must not be")
        assertEquals(1, rowCountsOf(raw).getValue("import_batches"), "the raw half lost what was in the log")
    }

    @Test
    fun `the document half holds the migrated data and reads back as a backup`() {
        val source = anOldDatabase(3)

        val set = writer().writeSetFor(source, fromSchemaVersion = 3)

        val read = runBlocking { realReader().read(PathBackupInput(backups.resolve("${set.setName}.json"))) }
        val valid = read as? BackupReadResult.Valid ?: error("the document did not read back as a backup: $read")
        assertEquals(8, valid.backup.sourceSchemaVersion)
        // The import trail the version 3 database held is in it, having come
        // through the whole chain rather than been copied across — and it is in
        // the shape version 8 keeps it in.
        assertEquals(1, valid.backup.data.importBatches.size)
        assertEquals(
            "liste.xlsx",
            valid.backup.data.importBatches
                .single()
                .fileName,
        )
        assertEquals(1, valid.backup.data.rawImportBlocks.size)
    }

    @Test
    fun `the source is not touched, in any of the ways it could be`() {
        val source = anOldDatabase(3)
        val before = sidecarsOf(source).take(2).associateWith(::digestOf)
        val counts = rowCountsOf(source)

        writer().writeSetFor(source, fromSchemaVersion = 3)

        assertEquals(before, sidecarsOf(source).take(2).associateWith(::digestOf), "the source was rewritten")
        assertEquals(3, schemaVersionOf(source), "the source was migrated")
        assertEquals(counts, rowCountsOf(source))
    }

    @Test
    fun `every old version this build knows how to migrate produces a set`() {
        // PLAN 14.4.10 step 5 is "1..7", not "3". Each of them is built from its
        // own committed schema and walked all the way to this build's.
        (1..7).forEach { version ->
            val source = anOldDatabase(version, name = "v$version.db")

            val set = writer(moment = LocalMoment(2026, 9, 11, 14, 33, version)).writeSetFor(source, version)

            assertEquals(version, set.fromSchemaVersion, "v$version")
            assertEquals(8, set.toSchemaVersion, "v$version")
            assertEquals(version, schemaVersionOf(backups.resolve("${set.setName}.db")), "v$version")
            val read = runBlocking { realReader().read(PathBackupInput(backups.resolve("${set.setName}.json"))) }
            val valid = read as? BackupReadResult.Valid ?: error("v$version: $read")
            assertEquals(8, valid.backup.sourceSchemaVersion, "v$version")
        }
        assertEquals(14, namesIn(backups).size, "seven versions did not leave seven pairs")
    }

    @Test
    fun `a version seven database whose rows run backwards in time still produces a set, every moment kept`() {
        // PLAN 14.7.3: a clock that went back left a game changed "before" it was
        // created. The migration's document half must still read back as a
        // backup, or the gate would refuse to migrate the user's own data.
        val gameId = IdGenerator.Random.newId()
        val file = home.resolve("geri.db")
        open +=
            openWithHotWal(file, version = 7) { connection ->
                insertVersion4Game(connection, gameId = gameId, name = "Harmonies")
                connection.prepare("UPDATE games SET created_at = ?, updated_at = ? WHERE id = ?").use { statement ->
                    statement.bindLong(1, 1_700_000_600_000)
                    statement.bindLong(2, 1_700_000_000_000)
                    statement.bindText(3, gameId.toString())
                    statement.step()
                }
            }

        val set = writer().writeSetFor(file, fromSchemaVersion = 7)

        val read = runBlocking { realReader().read(PathBackupInput(backups.resolve("${set.setName}.json"))) }
        val valid = read as? BackupReadResult.Valid ?: error("the document half was refused: $read")
        val game =
            valid.backup.data.games
                .single()
        assertEquals(1_700_000_600_000, game.createdAt)
        assertEquals(1_700_000_000_000, game.updatedAt)
    }

    @Test
    fun `a second set of the same second takes the next ordinal, on both halves`() {
        val first = anOldDatabase(3, name = "bir.db")
        val second = anOldDatabase(3, name = "iki.db")

        val one = writer().writeSetFor(first, 3)
        val two = writer().writeSetFor(second, 3)

        assertTrue(one.setName != two.setName, "the second set took the first one's name")
        // Both halves of the second carry the same ordinal, which is what makes
        // them findable as a pair (PLAN 14.4.11).
        val readDb = assertNotNull(automaticBackupNameOf("${two.setName}.db"))
        val readJson = assertNotNull(automaticBackupNameOf("${two.setName}.json"))
        assertEquals(2, readDb.attempt)
        assertEquals(2, readJson.attempt)
        assertEquals(4, namesIn(backups).size)
    }

    @Test
    fun `a name already taken is never written over`() {
        val source = anOldDatabase(3)
        // Somebody else's file, under the name this second would otherwise use.
        val taken = backups.resolve("pnp-otomatik-migration-v3-v8-2026-09-11-143355.json")
        Files.write(taken, "bu benim dosyam".encodeToByteArray())

        val set = writer().writeSetFor(source, 3)

        assertEquals("bu benim dosyam", Files.readString(taken), "an existing file was written over")
        assertTrue(set.setName.endsWith("-2"), set.setName)
    }

    // ------------------------------------------------------------ fail closed

    @Test
    fun `a source that cannot be cloned stops the set and leaves no empty halves`() {
        val notADatabase = home.resolve("bozuk.db")
        Files.write(notADatabase, "bu bir veritabanı değil".encodeToByteArray())

        val refused = assertFailsWith<StartupRefused> { writer().writeSetFor(notADatabase, 3) }

        assertEquals(StartupProblem.SNAPSHOT_NOT_CLONED, refused.problem)
        assertEquals(emptyList(), namesIn(backups), "a failed set left its name claims behind")
    }

    @Test
    fun `a clone that is not the old database is refused, and the untrue name goes with it`() {
        // The copy went through and produced something else — a different
        // version. The four checks on the raw half are what catches it.
        val source = anOldDatabase(3)
        val wrong = home.resolve("yanlis.db")
        CommittedSchema.createDatabase(wrong, version = 5)
        val cloneOfSomethingElse =
            ConsistentDatabaseClone { _, target -> Files.copy(wrong, Path.of(target)) }

        val refused =
            assertFailsWith<StartupRefused> { writer(clone = cloneOfSomethingElse).writeSetFor(source, 3) }

        assertEquals(StartupProblem.SNAPSHOT_NOT_CLONED, refused.problem)
        assertEquals(emptyList(), namesIn(backups))
    }

    @Test
    fun `a database the migration refuses to convert stops the set, safely`() {
        // The case the real user database could be in, and the one that made
        // this test class find a hole in the production code: `Migration3To4`
        // refuses rather than inventing an anchor for a version 3 game, and its
        // refusal is not a storage failure. Before this it travelled out as a
        // raw exception; now it is an answer, with nothing of theirs touched.
        val source = anOldDatabaseWithLegacyGames()
        val before = digestOf(source)

        val refused = assertFailsWith<StartupRefused> { writer().writeSetFor(source, 3) }

        assertEquals(StartupProblem.SNAPSHOT_NOT_MIGRATED, refused.problem)
        assertEquals(before, digestOf(source), "a refused migration rewrote the database")
        assertEquals(3, schemaVersionOf(source))
        // The raw half was proved before the migration was tried, so it stays.
        val left = namesIn(backups)
        assertEquals(1, left.size, left.toString())
        assertTrue(left.single().endsWith(".db"), left.toString())
    }

    @Test
    fun `a working copy that will not migrate stops the set`() {
        val source = anOldDatabase(3)
        val driver = FailingSqliteDriver()
        driver.failOn { "CREATE TABLE" in it.uppercase() || "ALTER TABLE" in it.uppercase() }
        val willNotOpen = DatabaseFactory(driver = driver)

        val refused = assertFailsWith<StartupRefused> { writer(databases = willNotOpen).writeSetFor(source, 3) }

        assertEquals(StartupProblem.SNAPSHOT_NOT_MIGRATED, refused.problem)
        // The raw half really was written before the failure, so it stays: it is
        // a consistent copy of somebody's database and nothing here throws one
        // away. The empty document claim goes.
        val left = namesIn(backups)
        assertEquals(1, left.size, left.toString())
        assertTrue(left.single().endsWith(".db"), left.toString())
        assertTrue(Files.size(backups.resolve(left.single())) > 0, "an empty half was kept")
    }

    @Test
    fun `a document that cannot be written stops the set`() {
        val source = anOldDatabase(3)
        val willNotWrite =
            AtomicFileWriter(
                temporarySuffix = ".part",
                writeBytes = { _, _ -> throw AtomicWriteException(AtomicWriteFailure.WRITE_FAILED) },
            )

        val refused = assertFailsWith<StartupRefused> { writer(documents = willNotWrite).writeSetFor(source, 3) }

        assertEquals(StartupProblem.SNAPSHOT_NOT_WRITTEN, refused.problem)
        assertTrue(namesIn(backups).none { it.endsWith(".json") }, "an empty document was left looking like a backup")
    }

    @Test
    fun `a document that will not read back is refused`() {
        val source = anOldDatabase(3)
        val refusingProbe = CountingProbe(BackupRejection(BackupProblem.TEMP_VALIDATION_FAILED, BackupPlace("data")))

        val refused =
            assertFailsWith<StartupRefused> { writer(reader = UntrustedBackupReader(refusingProbe)).writeSetFor(source, 3) }

        assertEquals(StartupProblem.SNAPSHOT_NOT_VERIFIED, refused.problem)
        // Both halves hold real content, so both stay — a set that could not be
        // proved is not the same thing as a file with nothing in it.
        assertEquals(2, namesIn(backups).size, namesIn(backups).toString())
        namesIn(backups).forEach { assertTrue(Files.size(backups.resolve(it)) > 0, it) }
    }

    // --------------------------------------------------------------- tidiness

    @Test
    fun `no working copy, log or half-written file is left anywhere`() {
        val source = anOldDatabase(3)

        val set = writer().writeSetFor(source, 3)

        assertEquals(listOf("${set.setName}.db", "${set.setName}.json").sorted(), namesIn(backups))
        assertTrue(namesIn(backups).none { it.endsWith(".part") })
        workingRoots.forEach { root -> assertTrue(Files.notExists(root), "a working directory was left at $root") }
    }

    @Test
    fun `a refused set leaves no working copy either`() {
        val source = anOldDatabase(3)
        val willNotWrite =
            AtomicFileWriter(
                temporarySuffix = ".part",
                writeBytes = { _, _ -> throw AtomicWriteException(AtomicWriteFailure.WRITE_FAILED) },
            )

        assertFailsWith<StartupRefused> { writer(documents = willNotWrite).writeSetFor(source, 3) }

        workingRoots.forEach { root -> assertTrue(Files.notExists(root), "a working directory survived a failure") }
    }

    @Test
    fun `a working directory inside the application's own data is refused outright`() {
        // Not an outcome and not a message: a defect, raised as one. A migration
        // run inside `backups/` would leave a database beside the backups and
        // could be mistaken for one.
        val source = anOldDatabase(3)
        val inside =
            MigrationSnapshotSetWriter(
                backupsDirectory = backups,
                reader = realReader(),
                workingDirectory = { backups },
                moment = { MOMENT },
            )

        assertFailsWith<IllegalStateException> { inside.writeSetFor(source, 3) }
    }
}
