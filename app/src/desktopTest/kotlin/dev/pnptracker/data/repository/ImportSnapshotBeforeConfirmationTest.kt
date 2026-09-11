package dev.pnptracker.data.repository

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aDraftTask
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aRawImportBlock
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.automatic.AutomaticSnapshotWriter
import dev.pnptracker.domain.backup.automatic.VerifiedSnapshotTaker
import dev.pnptracker.domain.backup.backupDocumentOf
import dev.pnptracker.domain.backup.restore.BackupInput
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.SAFETY_BACKUP_PREFIX
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.restore.anEmptyBackup
import dev.pnptracker.domain.backup.restore.safetyBackupFileName
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.BackupDirectory
import dev.pnptracker.domain.backup.retention.IMPORT_SNAPSHOT_PREFIX
import dev.pnptracker.domain.backup.retention.InspectedBackupFile
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.backup.retention.automaticBackupNameOf
import dev.pnptracker.domain.backup.retention.importSnapshotFileName
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.backupfiles.DesktopImportSnapshotWriter
import dev.pnptracker.platform.backupfiles.PathBackupInput
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.XdgAppPaths
import dev.pnptracker.platform.files.XdgAppPathsResolver
import dev.pnptracker.platform.settings.DesktopSettingsStore
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private val MOMENT = Instant.fromEpochMilliseconds(1_780_000_000_000)

/** A writer that will not write, for every reason a disk has for refusing. */
private class RefusingWriter(
    private val failure: BackupFailure,
) : AutomaticSnapshotWriter {
    override suspend fun writeImportSnapshot(
        bytes: ByteArray,
        moment: LocalMoment,
    ): BackupInput = throw BackupException(failure)
}

/**
 * A writer that really writes, and then ruins what it wrote.
 *
 * The one case a fake cannot reach and a real disk will not produce on demand: a
 * file that was written and is not, when read back, the backup it was written
 * as.
 */
private class RuiningWriter(
    private val real: AutomaticSnapshotWriter,
    private val folder: Path,
) : AutomaticSnapshotWriter {
    override suspend fun writeImportSnapshot(
        bytes: ByteArray,
        moment: LocalMoment,
    ): BackupInput {
        val written = real.writeImportSnapshot(bytes, moment)
        Files.write(folder.resolve(written.fileName), "yarıda kalmış".encodeToByteArray())
        return written
    }
}

/** A folder whose deletions all fail, to prove what that does and does not cost. */
private class NeverDeletes(
    private val real: BackupDirectory,
) : BackupDirectory {
    var refusals = 0
        private set

    override suspend fun inspect(): List<InspectedBackupFile> = real.inspect()

    override suspend fun remove(fileName: String): Boolean {
        refusals++
        return false
    }
}

/**
 * A writer that can be held open, so a second press really does arrive while the
 * first is still in flight.
 */
private class GatedWriter(
    private val real: AutomaticSnapshotWriter,
    val gate: CompletableDeferred<Unit>,
) : AutomaticSnapshotWriter {
    override suspend fun writeImportSnapshot(
        bytes: ByteArray,
        moment: LocalMoment,
    ): BackupInput {
        gate.await()
        return real.writeImportSnapshot(bytes, moment)
    }
}

/**
 * The backup that stands in front of every import, on a real disk.
 *
 * PLAN 14.4.8 asks for one thing and this is where it is proved end to end: that
 * no confirmation ever reaches the user's tables without a verified copy of what
 * those tables held a moment earlier. Everything here is production — the real
 * exporter, the real atomic writer with its claimed name, the real untrusted
 * reader with its throwaway database, the real rotation, the real settings
 * store, and the real confirming transaction.
 *
 * The database is one this test makes in a home of its own. The real one is never
 * opened and the assertion in [checkNothingReal] is the one every database test
 * carries.
 */
class ImportSnapshotBeforeConfirmationTest {
    private lateinit var home: Path
    private lateinit var paths: XdgAppPaths
    private lateinit var database: AppDatabase
    private var realDatabaseExisted = false
    private val probeRoots = mutableListOf<Path>()

    @BeforeTest
    fun openInAHomeOfItsOwn() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-import-snapshot")
        paths =
            XdgAppPathsResolver(
                environment = { name ->
                    when (name) {
                        "XDG_DATA_HOME" -> home.resolve("data").toString()
                        "XDG_CONFIG_HOME" -> home.resolve("config").toString()
                        else -> null
                    }
                },
            ).resolve()
        AppDirectoryInitializer().ensureDirectories(paths)
        database = DatabaseFactory().open(paths.databaseFile)
        runBlocking { database.gameDao().activeCount() }
    }

    @AfterTest
    fun checkNothingReal() {
        database.close()
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the test changed whether the real application database exists",
        )
        probeRoots.forEach(::deleteTree)
        deleteTree(home)
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        val absolute = root.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
        Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    // ------------------------------------------------------- what is wired up

    private val backups get() = paths.backupsDirectory

    private fun reader() =
        UntrustedBackupReader(
            TemporaryBackupProbe(
                temporaryDirectory = { Files.createTempDirectory("pnp-tracker-import-snapshot-probe").also(probeRoots::add) },
            ),
        )

    private fun taker(writer: AutomaticSnapshotWriter = DesktopImportSnapshotWriter(backups)) =
        VerifiedSnapshotTaker(
            exporter = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, StoppedClock(MOMENT)),
            writer = writer,
            reader = reader(),
            clock = StoppedClock(MOMENT),
        )

    private fun housekeeping(directory: BackupDirectory = DesktopBackupDirectory(backups)) =
        SettingsDrivenHousekeeping(
            settings = DesktopSettingsStore(paths.settingsFile),
            rotation = AutomaticBackupRotation(directory),
        )

    private fun store(
        writer: AutomaticSnapshotWriter = DesktopImportSnapshotWriter(backups),
        directory: BackupDirectory = DesktopBackupDirectory(backups),
    ) = confirmationStore(
        database = database,
        snapshots = taker(writer),
        housekeeping = housekeeping(directory),
        clock = StoppedClock(MOMENT),
    )

    // -------------------------------------------------------------- a fixture

    private var madeBatches = 0

    /** One import of [drafts] drafts, aimed and ready to confirm. */
    private suspend fun aReadyImport(drafts: Int = 2): EntityId {
        val game = aGame(name = "Harmonies ${madeBatches++}")
        val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)

        val batch = anImportBatch(rawBlockCount = drafts, sha256 = "%064x".format(madeBatches))
        val blocks =
            (1..drafts).map { at ->
                aRawImportBlock(batch.id, rowIndex = at, columnIndex = 1, rawText = "$at KIRMIZI")
            }
        database.importDao().saveDraftBatch(batch, blocks)
        val confirmation = store()
        blocks.forEach { block ->
            database.importDao().setRawBlockProcessed(block.id, true, MOMENT)
            val draft = aDraftTask(block.id, name = "Token ${block.rowIndex}")
            database.importDao().addDraftTaskUnderReview(draft)
            confirmation.aimDraft(draft.id, cell.id, PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
        }
        return batch.id
    }

    private suspend fun everything(): BackupData = BackupStore(database).snapshot().data

    private fun namesIn(folder: Path): List<String> =
        Files.list(folder).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }

    private fun snapshotsIn(folder: Path): List<String> = namesIn(folder).filter { it.startsWith(IMPORT_SNAPSHOT_PREFIX) }

    /** What the file on disk really holds, read by the reader a restore would use. */
    private suspend fun readBack(fileName: String): BackupData {
        val read = reader().read(PathBackupInput(backups.resolve(fileName)))
        return (read as BackupReadResult.Valid).backup.data
    }

    // ------------------------------------------------------------ the promise

    @Test
    fun `a confirmation is preceded by a backup of what was there a moment earlier`() =
        runBlocking<Unit> {
            val batchId = aReadyImport()
            val before = everything()
            assertEquals(emptyList(), snapshotsIn(backups))

            val result = store().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            assertEquals(2, result.createdTaskCount)
            val snapshot = snapshotsIn(backups).single()
            // The file is the state the import was about to change, not the
            // state it left behind: PLAN 14.4.8 puts it before the first domain
            // write, and a backup taken afterwards would be no way back at all.
            assertEquals(before, readBack(snapshot))
            assertNotEquals(before, everything(), "the confirmation wrote nothing")
        }

    @Test
    fun `the smallest import there is gets the same backup as the largest`() =
        runBlocking<Unit> {
            // PLAN 14.4.8: no threshold, no number, and nothing about the size
            // of an import decides whether it is protected.
            val one = aReadyImport(drafts = 1)

            store().confirm(one, acknowledgeUnprocessedBlocks = true)

            assertEquals(1, snapshotsIn(backups).size)
        }

    @Test
    fun `two confirmations leave two backups, each of its own moment`() =
        runBlocking<Unit> {
            val first = aReadyImport(drafts = 1)
            val second = aReadyImport(drafts = 1)

            store().confirm(first, acknowledgeUnprocessedBlocks = true)
            val between = everything()
            store().confirm(second, acknowledgeUnprocessedBlocks = true)

            val snapshots = snapshotsIn(backups)
            assertEquals(2, snapshots.size, snapshots.toString())
            // Which is which comes from the ordinal in the name rather than
            // from sorting the strings: `-2.json` sorts *before* `.json`,
            // because a hyphen comes before a full stop.
            val later = snapshots.single { automaticBackupNameOf(it)?.attempt == 2 }
            // It carries what the first one's import left behind, which is what
            // makes a series of imports a series of ways back.
            assertEquals(between, readBack(later))
        }

    // ------------------------------------------------------------ fail closed

    @Test
    fun `a backup that cannot be written stops the import before it starts`() =
        runBlocking<Unit> {
            val batchId = aReadyImport()
            val before = everything()

            val refused =
                assertFailsWith<ImportConfirmationException> {
                    store(writer = RefusingWriter(BackupFailure.NOT_WRITABLE))
                        .confirm(batchId, acknowledgeUnprocessedBlocks = true)
                }

            assertEquals(ImportConfirmationFailure.SNAPSHOT_NOT_WRITTEN, refused.failure)
            // Every table, not just the ones an import writes to: PLAN 14.4.13
            // has nothing at all happen, and the batch is still a draft.
            assertEquals(before, everything())
            assertEquals(ImportBatchStatus.DRAFT, database.importDao().batchById(batchId)?.status)
        }

    @Test
    fun `a backup that cannot be read back stops the import before it starts`() =
        runBlocking<Unit> {
            val batchId = aReadyImport()
            val before = everything()

            val refused =
                assertFailsWith<ImportConfirmationException> {
                    store(writer = RuiningWriter(DesktopImportSnapshotWriter(backups), backups))
                        .confirm(batchId, acknowledgeUnprocessedBlocks = true)
                }

            assertEquals(ImportConfirmationFailure.SNAPSHOT_NOT_VERIFIED, refused.failure)
            assertEquals(before, everything())
            assertEquals(ImportBatchStatus.DRAFT, database.importDao().batchById(batchId)?.status)
            // The ruined file is left where it is rather than deleted. It was
            // written from the live database and may be perfectly good; nothing
            // calls it a backup, and nothing throws it away either.
            assertEquals(1, snapshotsIn(backups).size)
        }

    @Test
    fun `a database that changed after the backup is refused without a single write`() =
        runBlocking<Unit> {
            val batchId = aReadyImport()
            val stale = LiveSnapshotTaker(database, stale = true)
            // Read the database once and hold it, then let the application be
            // used: this is the race PLAN 14.4.8 names, with the window held
            // open instead of hoped for.
            stale.takeBeforeImport()
            database.gameDao().insert(aGame(name = "Araya giren oyun"))
            val before = everything()

            val refused =
                assertFailsWith<ImportConfirmationException> {
                    confirmationStore(database, snapshots = stale, clock = StoppedClock(MOMENT))
                        .confirm(batchId, acknowledgeUnprocessedBlocks = true)
                }

            assertEquals(ImportConfirmationFailure.DATA_CHANGED_MEANWHILE, refused.failure)
            assertEquals(before, everything(), "the transaction wrote something before refusing")
            assertEquals(ImportBatchStatus.DRAFT, database.importDao().batchById(batchId)?.status)
        }

    @Test
    fun `a confirmation that fails for its own reasons keeps the backup it was given`() =
        runBlocking<Unit> {
            // PLAN 14.4.8: a written and verified backup is never taken away.
            // Deleting one is worse than not having taken it, because the user
            // is left with neither the import nor the way back.
            val batchId = aReadyImport()
            store().confirm(batchId, acknowledgeUnprocessedBlocks = true)
            val afterFirst = snapshotsIn(backups)

            val refused =
                assertFailsWith<ImportConfirmationException> {
                    store().confirm(batchId, acknowledgeUnprocessedBlocks = true)
                }

            assertEquals(ImportConfirmationFailure.ALREADY_CONFIRMED, refused.failure)
            val afterSecond = snapshotsIn(backups)
            assertEquals(2, afterSecond.size, afterSecond.toString())
            assertTrue(afterSecond.containsAll(afterFirst), "a refused confirmation removed an earlier backup")
        }

    @Test
    fun `pressing confirm twice writes one backup and creates one set of tasks`() =
        runBlocking<Unit> {
            // PLAN 14.4.8's double submission rule, end to end: the screen's own
            // guard is what stops the second press, and because the backup is
            // taken inside the confirmation there is no way for a press that
            // never reached the store to have written a file.
            val batchId = aReadyImport()
            val gate = CompletableDeferred<Unit>()
            val controller =
                ImportConfirmationController(store(writer = GatedWriter(DesktopImportSnapshotWriter(backups), gate)))
            controller.refresh(batchId)

            val first = CoroutineScope(Job() + Dispatchers.Unconfined).launch { controller.confirm(batchId) }
            yield()
            assertTrue(controller.isBusy, "the first press was over before the second arrived")

            controller.confirm(batchId)
            controller.confirm(batchId)

            gate.complete(Unit)
            first.join()

            assertEquals(1, snapshotsIn(backups).size, "a press that wrote no tasks still wrote a backup")
            assertEquals(ImportBatchStatus.CONFIRMED, database.importDao().batchById(batchId)?.status)
            assertEquals(2, database.taskDao().allTasksIncludingDeleted().size, "the import ran more than once")
        }

    // ------------------------------------------------------------- the folder

    @Test
    fun `the number in the settings file is the number of backups kept`() =
        runBlocking<Unit> {
            // Two, chosen the way the screen would have written it, and applied
            // to the imports alone (PLAN 14.4.11's three separate quotas).
            Files.write(paths.settingsFile, """{"formatVersion":1,"automaticBackupCount":2}""".encodeToByteArray())
            val standing = fillTheFolder()

            store().confirm(aReadyImport(drafts = 1), acknowledgeUnprocessedBlocks = true)

            val left = namesIn(backups)
            // Six import snapshots — five standing and the one just written —
            // come down to two. So do the five safety backups, because PLAN
            // 14.4.12 has a lowered number take effect at the next automatic
            // backup and this is that moment for every kind.
            assertEquals(2, left.count { it.startsWith(IMPORT_SNAPSHOT_PREFIX) }, left.toString())
            assertEquals(2, left.count { it.startsWith(SAFETY_BACKUP_PREFIX) }, left.toString())
            // Two each rather than two between them: the quotas are separate,
            // so a burst of imports can never evict somebody's way back from a
            // restore (PLAN 14.4.11).
            assertTrue("pnp-yedek-2026-09-08.json" in left, "a manual backup was removed")
            assertTrue("okubeni.txt" in left, "somebody's own file was removed")
            assertTrue(standing.containsAll(left.filterNot { it.startsWith(IMPORT_SNAPSHOT_PREFIX) }))
        }

    @Test
    fun `a folder that will not give up its old files costs the import nothing`() =
        runBlocking<Unit> {
            // PLAN 14.4.13 is fail open about tidying up: the backup is written,
            // the user's data is not at risk, and a deletion that will not go
            // through is not a reason to refuse somebody their import.
            Files.write(paths.settingsFile, """{"formatVersion":1,"automaticBackupCount":1}""".encodeToByteArray())
            fillTheFolder()
            val stubborn = NeverDeletes(DesktopBackupDirectory(backups))
            val batchId = aReadyImport(drafts = 1)

            val result = store(directory = stubborn).confirm(batchId, acknowledgeUnprocessedBlocks = true)

            assertEquals(1, result.createdTaskCount)
            assertTrue(stubborn.refusals > 0, "nothing was even tried, so this proves nothing")
            assertEquals(6, snapshotsIn(backups).size, "a refused deletion took the import with it")
        }

    @Test
    fun `the automatic backup writes no line in the history`() =
        runBlocking<Unit> {
            // PLAN 14.4.7: an automatic backup is not one of the things PLAN
            // 12.15 lists, and `history_events.game_id` could not hold one
            // anyway. The import's own lines are still written.
            val batchId = aReadyImport(drafts = 1)
            val before = everything().historyEvents

            store().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            val after = everything().historyEvents
            assertEquals(before.size + 1, after.size, "the backup wrote a line of its own")
            assertEquals(listOf("IMPORT_CONFIRMED"), after.drop(before.size).map { it.kind })
        }

    /**
     * Five safety backups, five import snapshots, a manual backup and a note.
     *
     * @return every name in the folder afterwards.
     */
    private fun fillTheFolder(): List<String> {
        val writer = AtomicFileWriter(temporarySuffix = ".json.part")
        val document =
            backupDocumentOf(anEmptyBackup(), AppInfo.Current.version, sourceSchemaVersion = 8, createdAt = MOMENT)
                .json
                .encodeToByteArray()
        (1..5).forEach { minute ->
            val moment = LocalMoment(2026, 9, 9, 10, minute, 0)
            writer.write(backups.resolve(safetyBackupFileName(moment)), document)
            writer.write(backups.resolve(importSnapshotFileName(moment)), document)
        }
        writer.write(backups.resolve("pnp-yedek-2026-09-08.json"), document)
        Files.write(backups.resolve("okubeni.txt"), "bunlar benim".encodeToByteArray())
        return namesIn(backups)
    }
}
