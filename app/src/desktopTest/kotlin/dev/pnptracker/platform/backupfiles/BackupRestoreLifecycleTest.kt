package dev.pnptracker.platform.backupfiles

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CommittedSchema
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.EPOCH_MILLISECONDS_CREATED
import dev.pnptracker.data.database.LiveBackupRestorer
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aDraftTask
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aRawImportBlock
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.data.repository.TaskEditStore
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.RestoreProblem
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.sha256Of
import dev.pnptracker.domain.importrollback.ImportRollbackException
import dev.pnptracker.domain.importrollback.ImportRollbackFailure
import dev.pnptracker.domain.importrollback.TaskObstacle
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.time.localMomentOf
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.ui.feature.settings.aSafetySnapshot
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private val MOMENT = Instant.fromEpochMilliseconds(1_757_320_364_031)

/** An hour, and two, before the fixture's import was read: where a clock that went back lands. */
private val HOUR_BEFORE_THE_IMPORT = Instant.fromEpochMilliseconds(EPOCH_MILLISECONDS_CREATED - 3_600_000)
private val TWO_HOURS_BEFORE_THE_IMPORT = Instant.fromEpochMilliseconds(EPOCH_MILLISECONDS_CREATED - 7_200_000)

/** What the cell reads before any import touches it, and must read again afterwards. */
private const val DOCUMENT_BEFORE = "Önce yazdıklarım"

/**
 * A backup written to a real file and put back into a real database.
 *
 * Every piece along the way is the production one: the writer that makes the
 * document, the atomic writer that puts it on disk, the gateway that offers it
 * back as bytes, the reader that trusts none of it, the throwaway database it is
 * tried in, the writer of the safety backup and the transaction that replaces
 * everything. Nothing here is a double, because the thing being asked is whether
 * the pieces fit — and a double is exactly where two pieces stop having to.
 *
 * The shape of most of these is the same: get the database into state A, take a
 * backup, get it into state B, put A back, and ask whether the database is now
 * *A* rather than merely the right size.
 */
class BackupRestoreLifecycleTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var backups: Path
    private var realDatabaseExisted = false
    private val opened = mutableListOf<AppDatabase>()
    private val probeRoots = mutableListOf<Path>()

    @BeforeTest
    fun createDirectory() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        backups = Files.createDirectory(directory.root.resolve("backups"))
    }

    @AfterTest
    fun deleteDirectory() {
        opened.forEach { it.close() }
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExisted)
        probeRoots.forEach { root ->
            assertTrue(Files.notExists(root), "the throwaway database outlived the reading")
        }
        directory.delete()
    }

    @Test
    fun `a backup of A, a database that has become B, and A put back whole`() =
        runBlocking<Unit> {
            val database = openFilled()
            val stateA = snapshotOf(database)
            val fileA = writeBackup(database, "pnp-yedek-a.json")

            becomeB(database)
            val stateB = snapshotOf(database)
            assertNotEquals(stateA, stateB, "the change to B changed nothing")

            val restored = restore(database, fileA)

            assertNull(restored.problem)
            assertEquals(stateA, snapshotOf(database))
            assertEquals(checksumOf(stateA), checksumOf(snapshotOf(database)))

            // And the automatic backup taken on the way is a real backup of B —
            // the state that was just replaced, which is the whole point of it.
            val safety = backups.resolve(restored.safetyFileName)
            assertTrue(Files.exists(safety))
            assertEquals(stateB, dataOf(readBackup(safety)))
        }

    @Test
    fun `the automatic safety backup can itself be restored, all the way back to B`() =
        runBlocking<Unit> {
            // The safety file is an ordinary backup or it is nothing: a way back
            // that only some special path could use would not be one (PLAN 14.4.4).
            val database = openFilled()
            val fileA = writeBackup(database, "pnp-yedek-a.json")
            becomeB(database)
            val stateB = snapshotOf(database)

            val toA = restore(database, fileA)
            assertNull(toA.problem)
            assertNotEquals(stateB, snapshotOf(database))

            val backToB = restore(database, backups.resolve(toA.safetyFileName))

            assertNull(backToB.problem)
            assertEquals(stateB, snapshotOf(database))
        }

    @Test
    fun `the same backup put back twice leaves the same database and no duplicates`() =
        runBlocking<Unit> {
            val database = openFilled()
            val fileA = writeBackup(database, "pnp-yedek-a.json")
            becomeB(database)

            val first = restore(database, fileA)
            val once = snapshotOf(database)
            val second = restore(database, fileA)

            assertNull(first.problem)
            assertNull(second.problem)
            assertEquals(once, snapshotOf(database))
            // Identifiers travel unchanged (PLAN 14.4.3), so the second restore
            // is the same operation again rather than a second copy of anything.
            assertEquals(
                once.colors
                    .map { it.id }
                    .toSet()
                    .size,
                once.colors.size,
            )
            // Two restores, two safety backups: the second one is a backup of the
            // first one's result and is kept.
            assertNotEquals(first.safetyFileName, second.safetyFileName)
            assertEquals(2, namesIn(backups).count { it.startsWith("pnp-oncesi-") })
        }

    @Test
    fun `a database walked up from version one can be backed up and put back`() =
        runBlocking<Unit> {
            // PLAN's Faz 3 test list asks for this: a database whose schema
            // arrived by migration rather than by being created at version eight.
            // It starts empty because Migration3To4 refuses to carry version
            // three's production rows forward at all — that refusal is its own
            // decision and its own test — so what is walked up here is the schema,
            // and the rows are written into it afterwards through today's code.
            val file = directory.root.resolve("old.db")
            CommittedSchema.createDatabase(file, version = 1) { }
            val database = open("old.db")
            assertEquals(8, BackupStore(database).snapshot().sourceSchemaVersion)
            fillWithEverything(database)
            val migrated = snapshotOf(database)
            val backup = writeBackup(database, "pnp-yedek-eski.json")
            becomeB(database)

            val restored = restore(database, backup)

            assertNull(restored.problem)
            assertEquals(migrated, snapshotOf(database))
        }

    @Test
    fun `a file that is not a good backup changes neither the database nor the backups folder`() =
        runBlocking<Unit> {
            val database = openFilled()
            val good = writeBackup(database, "pnp-yedek-a.json")
            val before = snapshotOf(database)
            val filesBefore = namesIn(backups)

            val bad =
                listOf(
                    "bozuk.json" to "{ this is not a backup",
                    "degismis.json" to Files.readString(good).replaceFirst("\"sortOrder\":0", "\"sortOrder\":7"),
                    "yeni.json" to Files.readString(good).replaceFirst("\"formatVersion\":1", "\"formatVersion\":2"),
                    "bos.json" to "",
                )
            bad.forEach { (name, text) ->
                val file = directory.root.resolve(name)
                Files.writeString(file, text)

                val result = readBackup(file)

                assertTrue(result is BackupReadResult.Refused, "$name was accepted as a backup")
                assertEquals(before, snapshotOf(database), "$name changed the database")
                assertEquals(filesBefore, namesIn(backups), "$name left something in the backups folder")
            }
        }

    @Test
    fun `a restored confirmed import can still be taken back, and does everything a rollback does`() =
        runBlocking<Unit> {
            // The strongest thing a backup can promise: not that the right number
            // of rows came back, but that what the rows *mean* came back. A
            // confirmed import is only reversible while every task it made is
            // untouched (`updatedAt == createdAt`) and the cell it wrote into
            // still reads what the stored snapshot says — so a restore that
            // rewrote one moment would pass every count and quietly make this
            // impossible (PLAN 11.4.4).
            val source = open("import.db")
            val batchId = givenAReversibleConfirmedImport(source)
            assertTrue(previewOf(source, batchId), "the fixture's import was not reversible to begin with")
            // Both names are in the cell; which of the two the confirmation put
            // first is the import's own business and not this test's.
            val withImport = documentTextOf(source, batchId)
            assertTrue(withImport.startsWith("$DOCUMENT_BEFORE "), withImport)
            assertTrue("Kırmızı ev" in withImport && "Mavi ev" in withImport, withImport)
            val backup = writeBackup(source, "pnp-yedek-import.json")
            source.close()

            val database = open("restored.db")
            assertNull(restore(database, backup).problem)

            assertTrue(previewOf(database, batchId), "a restored import could no longer be taken back")
            val result = rollbackOf(database).rollBack(batchId)

            assertEquals(2, result.removedTaskCount)
            assertEquals(1, result.restoredCellCount)
            // The cell reads what it read before that import, the tasks are
            // tombstoned rather than deleted, the batch says so, and the history
            // gained the lines PLAN 11.4.4 asks for.
            val after = snapshotOf(database)
            assertEquals(DOCUMENT_BEFORE, documentTextOf(database, batchId))
            assertEquals("ROLLED_BACK", after.importBatches.single { it.id == batchId.toString() }.status)
            assertEquals(2, after.tasks.count { it.deletedAt != null })
            assertTrue(
                after.historyEvents.any { it.kind == "IMPORT_ROLLED_BACK" },
                "taking a restored import back wrote no history",
            )
        }

    // --------------------------------------------- a clock that went backwards

    @Test
    fun `a database written on a clock that went back goes out and comes back with every moment as it was`() =
        runBlocking<Unit> {
            // PLAN 14.7.3. The import is confirmed an hour "before" it was read,
            // and one of its tasks is then changed an hour before that — which is
            // what this application writes when the system clock steps back.
            val database = open("saat.db")
            givenAReversibleConfirmedImport(database, confirmedAt = HOUR_BEFORE_THE_IMPORT)
            changeAnImportedTask(database, at = TWO_HOURS_BEFORE_THE_IMPORT)
            val written = snapshotOf(database)
            assertTrue(written.tasks.any { it.updatedAt < it.createdAt }, "no task ran backwards; the test proves nothing")
            assertTrue(written.importBatches.any { it.updatedAt < it.importedAt }, "no import ran backwards")

            // Out to a file and read by the reader every restore and every import
            // snapshot goes through: accepted, and not one moment corrected.
            val file = writeBackup(database, "pnp-yedek-saat.json")
            assertEquals(written, dataOf(readBackup(file)))

            // Back into a database that has moved on since, exactly.
            becomeB(database)
            assertNull(restore(database, file).problem)
            assertEquals(written, snapshotOf(database))
            // And out once more: the second backup says what the first said.
            assertEquals(written, dataOf(readBackup(writeBackup(database, "pnp-yedek-saat-2.json"))))
        }

    @Test
    fun `a task changed on a clock that went back still counts as touched and stops the rollback`() =
        runBlocking<Unit> {
            // PLAN 11.4.4 compares with `!=`, not `>`: a change stamped before the
            // creation is still a change, and the import can no longer be taken
            // back without undoing it (PLAN 14.7.3 keeps this as it is).
            val database = open("saat.db")
            val batchId = givenAReversibleConfirmedImport(database)
            val changed = changeAnImportedTask(database, at = HOUR_BEFORE_THE_IMPORT)
            val before = snapshotOf(database)
            assertTrue(before.tasks.single { it.id == changed.toString() }.let { it.updatedAt < it.createdAt })

            val preview = rollbackOf(database).previewRollback(batchId)
            val refused = assertFailsWith<ImportRollbackException> { rollbackOf(database).rollBack(batchId) }

            assertEquals(ImportRollbackFailure.TASKS_WERE_EDITED, preview.blockingFailure)
            assertEquals(listOf(changed to TaskObstacle.EDITED), preview.blockedTasks.map { it.taskId to it.obstacle })
            assertEquals(ImportRollbackFailure.TASKS_WERE_EDITED, refused.failure)
            assertEquals(before, snapshotOf(database), "a refused rollback changed something")
        }

    /** Renames one task the import made, through the real editing store, at [at]. */
    private suspend fun changeAnImportedTask(
        database: AppDatabase,
        at: Instant,
    ): EntityId {
        val task = snapshotOf(database).tasks.first { it.sourceRawImportBlockId != null && it.deletedAt == null }
        val id = EntityId.parse(task.id)
        val changed =
            TaskEditStore(database.taskEditDao(), clock = StoppedClock(at)).editTask(
                taskId = id,
                name = "${task.name} (düzeltildi)",
                colorId = null,
                requiredQuantity = task.requiredQuantity,
                notes = task.notes,
                trackingMode = TrackingMode.valueOf(task.trackingMode),
            )
        assertTrue(changed, "the edit changed nothing")
        return id
    }

    // --------------------------------------------------------------- the plumbing

    private class Restored(
        val problem: RestoreProblem?,
        val safetyFileName: String,
    )

    /** The whole production route, from a file on disk to the live tables. */
    private suspend fun restore(
        database: AppDatabase,
        file: Path,
    ): Restored {
        val read = readBackup(file)
        val backup = (read as BackupReadResult.Valid).backup
        val document = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, StoppedClock(MOMENT)).backupDocument()
        val safetyFileName =
            DesktopSafetyBackupWriter(backups).writeSafetyBackup(
                document.json.encodeToByteArray(),
                localMomentOf(MOMENT),
            )
        val problem =
            LiveBackupRestorer(database).restore(
                backup,
                aSafetySnapshot(document.envelope.data, safetyFileName),
            )
        return Restored(problem, safetyFileName)
    }

    private suspend fun readBackup(file: Path): BackupReadResult =
        UntrustedBackupReader(
            TemporaryBackupProbe(
                temporaryDirectory = {
                    Files.createTempDirectory("pnp-tracker-lifecycle-probe").also { probeRoots.add(it) }
                },
            ),
        ).read(PathBackupInput(file))

    private fun dataOf(result: BackupReadResult): BackupData = (result as BackupReadResult.Valid).backup.data

    private suspend fun writeBackup(
        database: AppDatabase,
        name: String,
    ): Path {
        val document = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, StoppedClock(MOMENT)).backupDocument()
        val file = backups.resolve(name)
        AtomicFileWriter(temporarySuffix = ".json.part").write(file, document.json.encodeToByteArray())
        return file
    }

    /** Turns the database into something recognisably different. */
    private suspend fun becomeB(database: AppDatabase) {
        val game = aGame(name = "Sonradan eklenen oyun")
        database.gameDao().insert(game)
        val cell = aCell(game.id, columnType = CellColumnType.THREE_D)
        database.gameCellDao().insert(cell)
        database.taskDao().addTaskToCell(aTask(name = "Sonradan eklenen görev"), cell.id, IdGenerator.Random.newId(), MOMENT)
    }

    /**
     * A game, a cell the user wrote in, and an import confirmed into it.
     *
     * The shape a rollback is allowed to undo: nothing touched since, and the
     * cell's earlier text recorded where the confirmation put it.
     */
    private suspend fun givenAReversibleConfirmedImport(
        database: AppDatabase,
        confirmedAt: Instant = MOMENT,
    ): EntityId {
        val clock = StoppedClock(MOMENT)
        val game = aGame(name = "Harmonies")
        database.gameDao().insert(game)
        database.cellSegmentDao().saveDocumentText(game.id, CellColumnType.THREE_D, "", DOCUMENT_BEFORE, clock, IdGenerator.Random)
        val cellId = database.gameCellDao().cellOfGame(game.id, CellColumnType.THREE_D)!!.id

        val batch = anImportBatch(rawBlockCount = 2, sha256 = "%064x".format(1))
        database.importDao().insertBatch(batch)
        listOf("Kırmızı ev", "Mavi ev").forEachIndexed { at, name ->
            val block =
                aRawImportBlock(
                    batch.id,
                    rowIndex = at + 1,
                    columnIndex = 1,
                    rawText = "15 KIRMIZI $name",
                    sourceColumnType = SourceColumnType.THREE_D,
                )
            database.importDao().insertRawBlock(block)
            database.importDao().setRawBlockProcessed(block.id, true, MOMENT)
            val draft = aDraftTask(block.id, name = name)
            database.importDao().addDraftTask(draft)
            database.importDao().setDraftTargetUnderReview(draft.id, cellId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH, MOMENT)
        }
        database.importDao().confirmDraftBatch(batch.id, true, StoppedClock(confirmedAt), IdGenerator.Random)
        return batch.id
    }

    private fun rollbackOf(database: AppDatabase) = ImportRollbackStore(database.importDao(), IdGenerator.Random, StoppedClock(MOMENT))

    private suspend fun confirmableBatchOf(database: AppDatabase): EntityId =
        snapshotOf(database)
            .importBatches
            .first { it.status == "CONFIRMED" }
            .let { EntityId.parse(it.id) }

    private suspend fun previewOf(
        database: AppDatabase,
        batchId: EntityId,
    ): Boolean = rollbackOf(database).previewRollback(batchId).canRollBack

    /** What the cell that import wrote into reads now. */
    private suspend fun documentTextOf(
        database: AppDatabase,
        batchId: EntityId,
    ): String {
        val data = snapshotOf(database)
        val cellId = data.importBatchCells.first { it.importBatchId == batchId.toString() }.cellId
        return data.cellSegments
            .filter { it.cellId == cellId }
            .sortedBy { it.orderIndex }
            .joinToString("") { piece ->
                piece.text ?: data.tasks.first { it.id == piece.taskId }.name
            }
    }

    private suspend fun open(
        name: String = "pnp.db",
        factory: DatabaseFactory = DatabaseFactory(),
    ): AppDatabase {
        val database = factory.open(directory.root.resolve(name))
        opened += database
        database.gameDao().activeCount()
        return database
    }

    private suspend fun openFilled(): AppDatabase = open().also { fillWithEverything(it) }

    private suspend fun snapshotOf(database: AppDatabase): BackupData = BackupStore(database).snapshot().data

    private fun checksumOf(data: BackupData): String = sha256Of(canonicalBackupDataJson(data).encodeToByteArray())

    private fun namesIn(folder: Path): List<String> =
        Files.list(folder).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }
}
