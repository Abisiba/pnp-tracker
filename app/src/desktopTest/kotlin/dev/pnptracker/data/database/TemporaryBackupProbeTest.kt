package dev.pnptracker.data.database

import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.aSegment
import dev.pnptracker.domain.backup.restore.aTaskRow
import dev.pnptracker.domain.backup.restore.aWholeBackup
import dev.pnptracker.domain.backup.restore.anEmptyBackup
import dev.pnptracker.domain.backup.sha256Of
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What happens when a backup meets a real database of the current schema.
 *
 * The probe is the last thing a backup goes through and the only part of reading
 * one that touches storage, so most of what is worth testing about it is what it
 * does *not* do: it does not keep the database it built, it does not write
 * anywhere but its own temporary directory, and it does not accept a directory
 * that could be the user's.
 *
 * There is no looking inside the temporary database afterwards, because there is
 * no afterwards — it is deleted before the probe answers, which is the property
 * being relied on. What takes the place of looking inside is the probe's own
 * postcondition: it reads the whole database back out through the same reader a
 * real backup is taken with and compares it to the file. So a probe that says yes
 * has already said "these fifteen tables hold exactly these rows and nothing
 * else", and a test can lean on that rather than on a second reading of its own.
 *
 * Every test checks the real application database was left alone — the one file
 * this whole feature exists to protect.
 */
class TemporaryBackupProbeTest {
    private var realDatabaseExisted = false
    private val made = mutableListOf<Path>()

    @BeforeTest
    fun rememberTheRealDatabase() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
    }

    @AfterTest
    fun checkTheRealDatabaseAndTidyUp() {
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the probe changed whether the real application database exists",
        )
        made.forEach { root ->
            if (Files.exists(root)) {
                Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }

    @Test
    fun `a whole backup goes in and comes back out saying the same thing`() =
        runBlocking<Unit> {
            val data = aWholeBackup()
            assertNull(probe().probe(data, checksumOf(data)))
        }

    @Test
    fun `the colours a new database starts with are replaced and not joined`() =
        runBlocking<Unit> {
            // Room seeds twelve colours into a database it has just made, and a
            // restore replaces the data rather than merging it (PLAN 14.4.3).
            // This backup carries none, so a probe that emptied nothing would read
            // twelve colours back out, disagree with the file and refuse. It
            // saying yes is the seed being gone.
            val data = anEmptyBackup()
            assertNull(probe().probe(data, checksumOf(data)))
        }

    @Test
    fun `a checksum that does not describe what came back is refused`() =
        runBlocking<Unit> {
            val data = aWholeBackup()
            val problem = probe().probe(data, sha256Of("başka bir şey".encodeToByteArray()))
            assertEquals(BackupProblem.TEMP_VALIDATION_FAILED, problem?.problem)
        }

    @Test
    fun `data that is not the data that went in is refused`() =
        runBlocking<Unit> {
            // The rows go in and are read back through Room, so the comparison is
            // against what storage actually holds. A file whose rows are not in
            // the order the tables are read in is a file the writer did not
            // produce, and the probe notices.
            val whole = aWholeBackup()
            val shuffled = whole.copy(tasks = whole.tasks.reversed())

            assertEquals(BackupProblem.TEMP_VALIDATION_FAILED, probe().probe(shuffled, checksumOf(shuffled))?.problem)
        }

    @Test
    fun `a reference the file does not satisfy is refused by the database itself`() =
        runBlocking<Unit> {
            // The graph was already checked in memory, so getting this far means
            // that check has a hole in it. The database is the second opinion.
            val whole = aWholeBackup()
            val dangling = whole.copy(gameCells = whole.gameCells.map { it.copy(gameId = MISSING_ID) })

            val problem = probe().probe(dangling, checksumOf(dangling))

            assertTrue(problem != null, "a dangling reference was accepted")
            assertTrue(
                problem.problem == BackupProblem.BROKEN_REFERENCE || problem.problem == BackupProblem.TEMP_VALIDATION_FAILED,
                "refused for something other than the reference: $problem",
            )
        }

    @Test
    fun `nothing is left behind when it worked`() =
        runBlocking<Unit> {
            val data = aWholeBackup()
            val root = directory()

            assertNull(TemporaryBackupProbe(temporaryDirectory = { root }).probe(data, checksumOf(data)))

            assertTrue(Files.notExists(root), "the temporary directory outlived the probe")
        }

    @Test
    fun `nothing is left behind when the writing failed half way`() =
        runBlocking<Unit> {
            val driver = FailingSqliteDriver()
            val root = directory()
            val data = aWholeBackup()
            driver.failOn { it.startsWith("INSERT INTO tasks") }

            val problem =
                TemporaryBackupProbe(
                    databases = DatabaseFactory(driver = driver),
                    temporaryDirectory = { root },
                ).probe(data, checksumOf(data))

            assertEquals(BackupProblem.TEMP_VALIDATION_FAILED, problem?.problem)
            assertTrue(Files.notExists(root), "a failed probe left its database behind")
        }

    @Test
    fun `nothing is left behind when reading it back failed`() =
        runBlocking<Unit> {
            val driver = FailingSqliteDriver()
            val root = directory()
            val data = aWholeBackup()
            driver.failOn { it.startsWith("SELECT * FROM history_events") }

            val problem =
                TemporaryBackupProbe(
                    databases = DatabaseFactory(driver = driver),
                    temporaryDirectory = { root },
                ).probe(data, checksumOf(data))

            assertEquals(BackupProblem.TEMP_VALIDATION_FAILED, problem?.problem)
            assertTrue(Files.notExists(root), "a failed reading left the database behind")
        }

    @Test
    fun `no database file, and no companion of one, survives the probe`() =
        runBlocking<Unit> {
            val root = directory()
            val data = aWholeBackup()

            assertNull(TemporaryBackupProbe(temporaryDirectory = { root }).probe(data, checksumOf(data)))

            listOf("backup-probe.db", "backup-probe.db-wal", "backup-probe.db-shm").forEach { name ->
                assertTrue(Files.notExists(root.resolve(name)), "$name was left on disk")
            }
        }

    @Test
    fun `a directory inside the application's own data is refused outright`() =
        runBlocking<Unit> {
            // Not a backup that is wrong — a caller that is. It says so as itself
            // rather than as a refused backup, because a refused backup is
            // something a user gets told about and this is not.
            val data = aWholeBackup()
            val pretendDataDirectory = directory()
            val probe =
                TemporaryBackupProbe(
                    temporaryDirectory = { pretendDataDirectory.resolve("pnp-tracker") },
                    applicationDataDirectory = { pretendDataDirectory },
                )

            assertFailsWith<IllegalStateException> { probe.probe(data, checksumOf(data)) }
        }

    @Test
    fun `a directory that is not a temporary one is refused outright`() =
        runBlocking<Unit> {
            val data = aWholeBackup()
            val probe = TemporaryBackupProbe(temporaryDirectory = { Path.of(System.getProperty("user.home")) })

            assertFailsWith<IllegalStateException> { probe.probe(data, checksumOf(data)) }
        }

    @Test
    fun `a large backup runs the same kinds of statement as a small one, only more inserts`() =
        runBlocking<Unit> {
            // What must not grow is the shape of the work: one statement per table
            // however many rows there are, and no reading back row by row. The
            // number of inserts grows with the rows, and that is what an insert is.
            val small = shapeOf(aWholeBackup())
            val large = shapeOf(oneThousandTasks())

            assertEquals(small.keys, large.keys, "a bigger backup ran a different kind of statement")
            assertEquals(
                small.filterKeys { !it.startsWith("INSERT") },
                large.filterKeys { !it.startsWith("INSERT") },
                "something other than the inserts grew with the data",
            )
            val insertedTasks = large.entries.single { it.key.startsWith("INSERT INTO tasks") }.value
            assertTrue(insertedTasks > 1_000, "the large backup did not write its tasks: $insertedTasks")
            // Room asks its own questions when it opens a database — the schema
            // hash, the modification log — and those are counted above by the
            // equality; what matters here is that reading the data back is one
            // query per table and never one per row.
            val reads = large.keys.filter { it.startsWith("SELECT * FROM") && "room_" !in it }
            assertEquals(15, reads.size, "reading it back is meant to be one query per table: $reads")
            val clears = large.keys.filter { it.startsWith("DELETE") }
            assertEquals(15, clears.size, "emptying it is meant to be one statement per table: $clears")
        }

    /** What was run against the database, and how often, while the probe worked. */
    private suspend fun shapeOf(data: BackupData): Map<String, Int> {
        val driver = CountingSqliteDriver()
        val root = directory()
        driver.start()
        assertNull(
            TemporaryBackupProbe(databases = DatabaseFactory(driver = driver), temporaryDirectory = { root })
                .probe(data, checksumOf(data)),
        )
        return driver
            .stop()
            .filter { it.startsWith("SELECT") || it.startsWith("INSERT") || it.startsWith("DELETE") }
            .groupingBy { statement -> statement.take(40) }
            .eachCount()
    }

    /**
     * A backup the size PLAN 20 asks the application to cope with.
     *
     * A thousand more tasks, each written into the one cell, so the cell document
     * is a thousand pieces longer. Rows are put in the order the tables are read
     * in, because that is the order the writer would have produced them in and the
     * probe compares what comes back with what went in.
     */
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

    private fun probe(): TemporaryBackupProbe = TemporaryBackupProbe(temporaryDirectory = ::directory)

    private fun directory(): Path = Files.createTempDirectory("pnp-tracker-probe-test").also { made.add(it) }

    private fun checksumOf(data: BackupData): String = sha256Of(canonicalBackupDataJson(data).encodeToByteArray())

    private companion object {
        const val MISSING_ID = "99999999-9999-4999-8999-999999999999"
    }
}
