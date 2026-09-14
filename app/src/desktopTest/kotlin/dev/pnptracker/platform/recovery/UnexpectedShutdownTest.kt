package dev.pnptracker.platform.recovery

import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.retention.IMPORT_SNAPSHOT_PREFIX
import dev.pnptracker.domain.backup.retention.automaticBackupNameOf
import dev.pnptracker.domain.backup.retention.importSnapshotFileName
import dev.pnptracker.domain.backup.retention.migrationSnapshotSetName
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.backupfiles.PathBackupInput
import dev.pnptracker.platform.backupfiles.claimSetNames
import dev.pnptracker.platform.settings.DesktopSettingsStore
import dev.pnptracker.platform.startup.digestOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What an unexpected shutdown leaves on disk, and what the application makes of it.
 *
 * PLAN 11.4.5 decided there is nothing to detect: no "closed cleanly" marker is
 * written, a `-wal` beside the database is not evidence of a crash, and the next
 * start goes through the same gate as every other. These tests hold the
 * application to all three, and to the fourth rule that comes with them — the
 * half-written files an interrupted automatic backup can leave are neither
 * cleaned up nor counted.
 */
class UnexpectedShutdownTest {
    private lateinit var home: RecoveryHome

    @BeforeTest
    fun makeAHome() {
        home = RecoveryHome()
    }

    @AfterTest
    fun sweepTheHome() {
        home.close()
    }

    @Test
    fun `production has no hook that runs on the way out and nothing that marks a clean exit`() {
        // A clean-shutdown marker needs something that runs when the application
        // stops. There are two ways to get that on the JVM and neither is used.
        val offending =
            productionSources()
                .filter { source ->
                    val text = Files.readString(source)
                    "addShutdownHook" in text || "deleteOnExit" in text
                }.map { it.name }

        assertEquals(emptyList(), offending)
    }

    @Test
    fun `a normal close leaves only the database, its locks and the backup it took`() {
        home.withDatabase { database -> aReadyDraftImport(database) }
        val child = home.start(InterruptedWrite.CONFIRMING, Ending.CLOSED)
        assertEquals(PRODUCTION_DURABILITY, Durability.decoded(child.awaitLine("DURABILITY")))
        child.awaitLine("BEFORE")
        val closed = child.awaitLine("CLOSED")
        assertEquals(0, child.awaitExit(), "the application did not exit normally")
        // A normal exit takes even the driver's unpacked native library with it.
        assertEquals(emptyList(), home.temporary.namesInside(), "a normal exit left temporary files behind")

        val snapshot = importSnapshots().single()
        val afterClose = home.filesOnDisk()
        assertEquals(tidyFiles(snapshot), afterClose)

        // Opening it again finds nothing to recover from and makes nothing new.
        val reopened = home.reopen()
        reopened.assertWhole()
        assertEquals(closed, reopened.fingerprint)
        assertEquals(afterClose, home.filesOnDisk(), "a start after a normal close left something new behind")
    }

    @Test
    fun `a log left by a killed process is read back, not treated as a crash`() {
        home.withDatabase { database -> aReadyDraftImport(database) }
        val child = home.start(InterruptedWrite.CONFIRMING, Ending.COMMITTED)
        assertEquals(PRODUCTION_DURABILITY, Durability.decoded(child.awaitLine("DURABILITY")))
        child.awaitLine("BEFORE")
        val committed = child.awaitLine("COMMITTED")
        child.kill()

        // The killed application's files: the log and its index are still there,
        // and the committed confirmation lives in that log.
        val snapshot = importSnapshots().single()
        val wal = walOf(home.paths.databaseFile)
        assertTrue(Files.size(wal) > 0, "there was no hot log to start from")
        assertEquals((tidyFiles(snapshot) + listOf(DATA + "pnp.db-shm", DATA + "pnp.db-wal")).sorted(), home.filesOnDisk())
        // Outside the user's data a kill leaves exactly one thing: the bundled
        // driver's unpacked native library, which its own exit handling would
        // have removed. It is in the temporary directory, it holds no user data,
        // and nothing in the application reads it as a sign of anything.
        val strays = home.temporary.namesInside()
        assertEquals(1, strays.size, "a kill left $strays in the temporary directory")
        assertTrue(strays.single().matches(Regex("androidx_sqliteJni\\d+\\.tmp")), strays.single())

        // The start after it is an ordinary start: the gate opens it (no refusal,
        // no migration set), every committed row is there, and what is left on
        // disk is exactly what a normal close leaves — no marker, no recovery
        // file, no extra backup, no settings file.
        val reopened = home.reopen()
        reopened.assertWhole()
        assertEquals(committed, reopened.fingerprint)
        assertEquals(tidyFiles(snapshot), home.filesOnDisk())

        // And a second start after that changes nothing either.
        assertEquals(committed, home.reopen().fingerprint)
        assertEquals(tidyFiles(snapshot), home.filesOnDisk())
    }

    @Test
    fun `what an interrupted automatic backup leaves behind is neither counted nor removed`() {
        home.withDatabase { database ->
            aReadyDraftImport(database)
            aReadyDraftImport(database)
        }
        // Keep one of each kind, so a rotation that counted any of the leftovers
        // would have every reason to delete them.
        runBlocking { DesktopSettingsStore(home.paths.settingsFile).write(1) }
        val leftovers = leaveWhatAKilledBackupLeaves()
        val digests = leftovers.associateWith(::digestOf)

        // Two real confirmations, each backed up and each followed by a real
        // rotation that keeps one.
        home.withDatabase { database ->
            database.importDao().draftBatches().map { it.id }.forEach { batchId ->
                realConfirmationStore(database, home.paths, home::probeDirectory)
                    .confirm(batchId, acknowledgeUnprocessedBlocks = true)
            }
        }
        val confirmed = home.reopen()
        confirmed.assertWhole()
        assertEquals(listOf("CONFIRMED", "CONFIRMED"), confirmed.data.importBatches.map { it.status })

        // The rotation really ran: of the two real backups only the newer is left.
        assertEquals(1, importSnapshots().count { it.name !in leftovers.map(Path::name) })
        // And every leftover is exactly where it was, byte for byte.
        leftovers.forEach { file ->
            assertTrue(Files.exists(file), "${file.name} was removed")
            assertEquals(digests.getValue(file), digestOf(file), "${file.name} was changed")
        }
        // The empty name is not a backup to anything that reads one…
        val emptyClaim = leftovers.first { it.name.endsWith(".json") && it.name.startsWith(IMPORT_SNAPSHOT_PREFIX) }
        val read = runBlocking { readerFor(home.paths, home::probeDirectory).read(PathBackupInput(emptyClaim)) }
        assertEquals(BackupProblem.EMPTY_FILE, assertIs<BackupReadResult.Refused>(read).rejection.problem)
        // …and a half-written file is not even a name rotation recognises.
        leftovers.filter { it.name.endsWith(".part") }.forEach { assertNull(automaticBackupNameOf(it.name), it.name) }
    }

    // --------------------------------------------------------------- helpers

    /**
     * The files a process killed half way through an automatic backup can leave,
     * made by the same code that makes them: a name claimed by creating it and a
     * `.part` beside it for an import snapshot, and both halves of a migration
     * set claimed with a `.part` for the database. All of them are dated before
     * any real backup this test takes, so they are the oldest in the folder.
     */
    private fun leaveWhatAKilledBackupLeaves(): List<Path> {
        val longAgo = LocalMoment(2026, 9, 1, 10, 0, 0)
        val backups = home.paths.backupsDirectory
        val importClaim = Files.createFile(backups.resolve(importSnapshotFileName(longAgo)))
        val importPart = Files.write(backups.resolve("${importClaim.name}.part"), """{"format":"pnp-tracker-back""".toByteArray())
        val set = claimSetNames(backups) { attempt -> migrationSnapshotSetName(3, 8, longAgo, attempt) }
        val setPart = Files.write(backups.resolve("${set.database.name}.part"), byteArrayOf(0x53, 0x51, 0x4c))
        return listOf(importClaim, importPart, set.database, set.document, setPart)
    }

    private fun importSnapshots(): List<Path> =
        Files
            .list(home.paths.backupsDirectory)
            .use { entries -> entries.filter { it.name.startsWith(IMPORT_SNAPSHOT_PREFIX) }.sorted().toList() }

    /** Everything a tidy run leaves: the folders, the database, Room's lock, the instance lock, one backup. */
    private fun tidyFiles(snapshot: Path): List<String> =
        listOf(
            DATA + "backups/",
            DATA + "backups/" + snapshot.name,
            DATA + "pnp-baslangic.lock",
            DATA + "pnp.db",
            DATA + "pnp.db.lck",
        ).sorted()

    private fun productionSources(): List<Path> {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .firstOrNull { Files.isDirectory(it.resolve("src/commonMain/kotlin")) }
                ?.let { module ->
                    return listOf("src/commonMain/kotlin", "src/desktopMain/kotlin").flatMap { root ->
                        Files.walk(module.resolve(root)).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() }
                    }
                }
            candidate = candidate.parent
        }
        fail("could not find the app module from ${Path.of("").toAbsolutePath()}")
    }

    private companion object {
        const val DATA = "data/pnp-tracker/"
    }
}
