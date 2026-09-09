package dev.pnptracker.data.database

import androidx.room3.executeSQL
import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import dev.pnptracker.domain.backup.restore.RestoreProblem
import dev.pnptracker.domain.backup.restore.SUPPORTED_SOURCE_SCHEMA_VERSION
import dev.pnptracker.domain.backup.restore.aSegment
import dev.pnptracker.domain.backup.restore.aTaskRow
import dev.pnptracker.domain.backup.restore.aWholeBackup
import dev.pnptracker.domain.backup.restore.anEmptyBackup
import dev.pnptracker.domain.backup.sha256Of
import dev.pnptracker.ui.feature.settings.aSafetySnapshot
import dev.pnptracker.ui.feature.settings.aValidatedBackup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one transaction that replaces everything, and everything it will not do.
 *
 * These run against a real database of the current schema, filled by the fixture
 * that covers all fifteen tables, because the questions worth asking here cannot
 * be asked of a double: whether the seeded colours really go, whether a statement
 * failing halfway really leaves the earlier ones undone, whether SQLite really
 * accepts the rows in the order PLAN 14.4.2 gives.
 *
 * Every test states what the database holds afterwards as a whole reading rather
 * than as a count, because "the same number of rows" and "the same rows" are
 * different claims and only the second one is the promise.
 *
 * The real application database is checked at the end of every test. It is the
 * file this whole feature exists to protect and nothing here may go near it.
 */
class LiveBackupRestorerTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private var realDatabaseExisted = false
    private val opened = mutableListOf<AppDatabase>()

    @BeforeTest
    fun createDirectory() {
        realDatabaseExisted =
            TemporaryDatabaseDirectory.realApplicationDatabaseFile().let {
                java.nio.file.Files
                    .exists(it)
            }
        directory = TemporaryDatabaseDirectory()
    }

    @AfterTest
    fun deleteDirectory() {
        opened.forEach { it.close() }
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExisted)
        directory.delete()
    }

    @Test
    fun `a whole backup replaces everything the database held`() =
        runBlocking<Unit> {
            val database = openFilled()
            val before = snapshotOf(database)
            val backup = aValidatedBackup()
            assertNotEquals(before, backup.data, "the fixture and the backup were the same, so nothing was proved")

            val problem = LiveBackupRestorer(database).restore(backup, aSafetySnapshot(before))

            assertNull(problem)
            assertEquals(backup.data, snapshotOf(database))
            assertEquals(backup.dataSha256, checksumOf(snapshotOf(database)))
        }

    @Test
    fun `the twelve colours a new database starts with are replaced and not joined`() =
        runBlocking<Unit> {
            // Room seeds twelve colours whenever it makes a database. A restore
            // replaces rather than merges (PLAN 14.4.3), so a backup holding none
            // has to leave none — `12 + 0` would be a merge wearing a disguise.
            val database = open()
            val before = snapshotOf(database)
            assertEquals(12, before.colors.size, "the seed is not what this test thought it was")

            val problem = LiveBackupRestorer(database).restore(aValidatedBackup(anEmptyBackup()), aSafetySnapshot(before))

            assertNull(problem)
            assertEquals(anEmptyBackup(), snapshotOf(database))
        }

    @Test
    fun `every value travels exactly, down to the nulls and the tombstones`() =
        runBlocking<Unit> {
            // The fixture's own rows, taken out and put back: identifiers,
            // moments, the null in an optional column and the ones that mark a
            // deleted row all have to come back the same, because PLAN 11.4.4
            // reads `updatedAt == createdAt` as "nobody has touched this" and a
            // restore that rewrote a moment would make every confirmed import
            // impossible to take back.
            val source = openFilled()
            val taken = snapshotOf(source)
            source.close()

            val database = open("second.db")
            val problem = LiveBackupRestorer(database).restore(aValidatedBackup(taken), aSafetySnapshot(snapshotOf(database)))

            assertNull(problem)
            val back = snapshotOf(database)
            assertEquals(taken, back)
            assertTrue(back.games.any { it.deletedAt != null }, "the fixture stopped carrying a tombstone")
            assertTrue(back.tasks.any { it.notes == null }, "the fixture stopped carrying a null")
            assertTrue(back.historyEvents.isNotEmpty() && back.progressEvents.isNotEmpty())
        }

    @Test
    fun `a database that moved after the safety backup is not written over`() =
        runBlocking<Unit> {
            // The window PLAN 14.4.4 leaves open: the safety file is written, and
            // in the moment before the transaction begins somebody finishes a
            // task. Applying the backup would replace a change that exists in no
            // file anywhere, so the transaction reads first and refuses.
            val database = openFilled()
            val before = snapshotOf(database)
            val stale = before.copy(games = before.games.dropLast(1))

            val problem = LiveBackupRestorer(database).restore(aValidatedBackup(), aSafetySnapshot(stale))

            assertEquals(RestoreProblem.DATA_CHANGED_MEANWHILE, problem)
            assertEquals(before, snapshotOf(database), "a refused restore still changed something")
        }

    @Test
    fun `a statement that will not run leaves every table as it was`() =
        runBlocking<Unit> {
            // Three points along the transaction: emptying, writing, and reading
            // back to check. Each of them is a place a real SQLite failure can
            // land, and after each of them the database has to be exactly what it
            // was — PLAN 16 allows no half restore.
            val places =
                listOf<(String) -> Boolean>(
                    { it.startsWith("DELETE FROM task_colors") },
                    { it.startsWith("INSERT INTO tasks") },
                    { it.startsWith("SELECT * FROM history_events") },
                )
            places.forEach { where ->
                val driver = FailingSqliteDriver()
                val database = open("failing-${places.indexOf(where)}.db", DatabaseFactory(driver = driver))
                fillWithEverything(database)
                val before = snapshotOf(database)
                // Armed after the fixture and after the first reading, so the
                // trap is spent inside the transaction and nowhere else.
                driver.failOn(matches = where)

                val problem = LiveBackupRestorer(database).restore(aValidatedBackup(), aSafetySnapshot(before))

                driver.disarm()
                assertTrue(problem != null, "a failed statement was reported as a successful restore")
                assertEquals(before, snapshotOf(database), "a failed restore left the database changed")
            }
        }

    @Test
    fun `the committed database has whole references, the right schema and the backup's checksum`() =
        runBlocking<Unit> {
            val database = openFilled()
            val backup = aValidatedBackup()

            assertNull(LiveBackupRestorer(database).restore(backup, aSafetySnapshot(snapshotOf(database))))

            assertEquals(SUPPORTED_SOURCE_SCHEMA_VERSION, userVersionOf(database))
            assertTrue(referencesAreWhole(database), "the restore committed a reference to nothing")
            assertEquals(backup.dataSha256, checksumOf(snapshotOf(database)))
        }

    @Test
    fun `the same backup restored twice leaves the same database`() =
        runBlocking<Unit> {
            // PLAN 14.4.3: identifiers travel unchanged, so a second restore is
            // the same operation again and not a second copy of everything.
            val database = openFilled()
            val restorer = LiveBackupRestorer(database)
            val backup = aValidatedBackup()

            assertNull(restorer.restore(backup, aSafetySnapshot(snapshotOf(database))))
            val once = snapshotOf(database)
            assertNull(restorer.restore(backup, aSafetySnapshot(once)))

            assertEquals(once, snapshotOf(database))
            assertEquals(backup.data.colors.size, snapshotOf(database).colors.size, "a second restore doubled the colours")
        }

    @Test
    fun `no other write gets in between the reading and the writing`() =
        runBlocking<Unit> {
            // `BEGIN IMMEDIATE` takes the write lock before the first read, and
            // Room keeps one writer connection, so a write started while the
            // restore is running waits for it. What must never happen is the
            // other write landing between the check and the replacement, where it
            // would be silently thrown away.
            val driver = PausingSqliteDriver()
            val database = open(factory = DatabaseFactory(driver = driver))
            fillWithEverything(database)
            val before = snapshotOf(database)
            val backup = aValidatedBackup()
            val other = CompletableDeferred<Unit>()

            coroutineScope {
                driver.interruptOnce(matches = { it.startsWith("INSERT INTO colors") }) {
                    // Started from inside the transaction, on the database's own
                    // thread: it cannot run until the restore lets the connection
                    // go, which is the guarantee being shown.
                    async {
                        database.useWriterConnection<Unit> { transactor ->
                            transactor.immediateTransaction<Unit> { executeSQL("DELETE FROM progress_events") }
                        }
                        other.complete(Unit)
                    }
                }

                val problem = LiveBackupRestorer(database).restore(backup, aSafetySnapshot(before))
                assertNull(problem, "the restore was disturbed by a write that should have waited")
                other.await()
            }

            // The other write ran afterwards, on the restored database, rather
            // than in the middle of it.
            val after = snapshotOf(database)
            assertEquals(backup.data.copy(progressEvents = emptyList()), after)
        }

    @Test
    fun `a large backup runs the same kinds of statement as a small one`() =
        runBlocking<Unit> {
            // The shape of the work must not grow with the data: one emptying and
            // one prepared insert per table, and reading back is one query per
            // table and never one per row.
            val small = shapeOf(aWholeBackup(), "small.db")
            val large = shapeOf(oneThousandTasks(), "large.db")

            assertEquals(small.keys, large.keys, "a bigger backup ran a different kind of statement")
            assertEquals(
                small.filterKeys { !it.startsWith("INSERT") },
                large.filterKeys { !it.startsWith("INSERT") },
                "something other than the inserts grew with the data",
            )
            val reads = large.keys.filter { it.startsWith("SELECT * FROM") }
            assertEquals(15, reads.size, "reading back is meant to be one query per table: $reads")
            // Three readings of fifteen tables: the safety check, the
            // postcondition, and the confirmation after the commit.
            assertTrue(
                large.filterKeys { it in reads }.values.all { it == 3 },
                "the fifteen tables were not read exactly three times: $large",
            )
            assertEquals(15, large.keys.count { it.startsWith("DELETE") })
            assertTrue(large.entries.single { it.key.startsWith("INSERT INTO tasks") }.value > 1_000)
        }

    /** What was run against the database, and how often, while the restore worked. */
    private suspend fun shapeOf(
        data: BackupData,
        name: String,
    ): Map<String, Int> {
        val driver = CountingSqliteDriver()
        val database = open(name, DatabaseFactory(driver = driver))
        val before = snapshotOf(database)
        val backup = aValidatedBackup(data)
        driver.start()
        assertNull(LiveBackupRestorer(database).restore(backup, aSafetySnapshot(before)))
        return driver
            .stop()
            .filter { it.startsWith("SELECT") || it.startsWith("INSERT") || it.startsWith("DELETE") }
            // Room's own bookkeeping is left out. It asks the modification log
            // and the master table its own questions, on its own schedule — the
            // refresh after a write is started in the background — so counting
            // them would measure when this machine happened to run them rather
            // than what the restore does.
            .filter { "room_" !in it && "sqlite_master" !in it }
            .groupingBy { statement -> statement.take(40) }
            .eachCount()
    }

    /** A backup the size PLAN 20 asks the application to cope with. */
    private fun oneThousandTasks(): BackupData {
        val whole = aWholeBackup()
        val added = (0 until 1_000).map { at -> at to identifier(at) }
        return whole.copy(
            tasks = added.map { (_, id) -> aTaskRow(id, "SPECIAL", "CHECKLIST", sourceBlock = null) } + whole.tasks,
            cellSegments =
                whole.cellSegments +
                    added.map { (at, id) ->
                        aSegment(id = identifier(at + 1_000_000), order = 3 + at, kind = "TASK", text = null, taskId = id)
                    },
        )
    }

    private fun identifier(at: Int): String = "70000000-0000-4000-8000-" + at.toString().padStart(12, '0')

    private suspend fun open(
        name: String = "pnp.db",
        factory: DatabaseFactory = DatabaseFactory(),
    ): AppDatabase {
        val database = factory.open(directory.root.resolve(name))
        opened += database
        // Opens it for real, so the migrations and the seed have run before
        // anything below looks at what is in there.
        database.gameDao().activeCount()
        return database
    }

    private suspend fun openFilled(): AppDatabase = open().also { fillWithEverything(it) }

    private suspend fun snapshotOf(database: AppDatabase): BackupData = BackupStore(database).snapshot().data

    private fun checksumOf(data: BackupData): String = sha256Of(canonicalBackupDataJson(data).encodeToByteArray())

    private suspend fun userVersionOf(database: AppDatabase): Int =
        database.useReaderConnection { transactor ->
            transactor.usePrepared("PRAGMA user_version") { statement ->
                statement.step()
                statement.getInt(0)
            }
        }

    private suspend fun referencesAreWhole(database: AppDatabase): Boolean =
        database.useReaderConnection { transactor ->
            transactor.usePrepared("PRAGMA foreign_key_check") { statement -> !statement.step() }
        }
}
