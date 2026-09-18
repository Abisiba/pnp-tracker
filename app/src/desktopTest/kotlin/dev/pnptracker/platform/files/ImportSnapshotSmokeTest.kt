package dev.pnptracker.platform.files

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.data.repository.DraftEdit
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.data.repository.ImportReviewStore
import dev.pnptracker.data.repository.confirmationStore
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.automatic.VerifiedSnapshotTaker
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.IMPORT_SNAPSHOT_PREFIX
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.backupfiles.DesktopImportSnapshotWriter
import dev.pnptracker.platform.backupfiles.PathBackupInput
import dev.pnptracker.platform.importfiles.DesktopImportFileGateway
import dev.pnptracker.platform.importfiles.ImportFilePicker
import dev.pnptracker.platform.settings.DesktopSettingsStore
import dev.pnptracker.platform.xlsx.copyFixtureInto
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreenState
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationController
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** The file dialog, standing in for the one a person would click through. */
private class FixedPicker(
    private val file: Path,
) : ImportFilePicker {
    override suspend fun chooseImportFile(): Path = file
}

/** A clock that moves a second on every reading, so drafts keep their order. */
private class SteppingClock(
    private val start: Instant,
) : Clock {
    private var step = 0L

    override fun now(): Instant = start + Duration.parse("${step++}s")
}

/**
 * A spreadsheet and a CSV, imported for real, each backed up first.
 *
 * The companion of the other smokes in this package, and the one that joins the
 * import side to the backup side. Everything is production: the paths from
 * [XdgAppPathsResolver], the folders from [AppDirectoryInitializer], the database
 * from [DatabaseFactory], the real workbook reader over the committed fixture,
 * the real CSV reader, the real draft and review stores, the real confirming
 * transaction, and the backup taken in front of it by the real exporter, the
 * real atomic writer and the real untrusted reader.
 *
 * One thing is substituted and only one: the system file dialog, which cannot be
 * clicked through by a test.
 *
 * What it is really here for is PLAN 14.4.8's "no distinction": the two formats
 * arrive by different readers and meet at the same draft pipeline, and this says
 * that from the backup's point of view they are the same import. The two tests
 * below are deliberately the same test twice.
 */
class ImportSnapshotSmokeTest {
    private lateinit var home: Path
    private lateinit var paths: XdgAppPaths
    private lateinit var database: AppDatabase
    private var realDatabaseExisted = false
    private val probeRoots = mutableListOf<Path>()

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-import-snapshot-smoke")
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
    fun deleteHome() {
        database.close()
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the smoke changed whether the real application database exists",
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

    @Test
    fun `a spreadsheet is backed up before it becomes tasks`() =
        runBlocking<Unit> {
            val file = copyFixtureInto(home)
            val batchId = importFile(file, expect = ImportSourceFormat.XLSX)
            rejectEveryGreenHint(batchId)

            theWholeJourney(batchId)
        }

    @Test
    fun `a CSV is backed up in exactly the same way`() =
        runBlocking<Unit> {
            val text =
                """
                game,source_type,raw_text
                Harmonies,3D,12 KIRMIZI
                Harmonies,3D,8 MAVİ
                """.trimIndent()
            val file = home.resolve("liste.csv")
            Files.write(file, text.toByteArray(StandardCharsets.UTF_8))
            val batchId = importFile(file, expect = ImportSourceFormat.CSV)

            theWholeJourney(batchId)
        }

    @Test
    fun `an import reviewed on a clock that went back a day is still backed up and confirmed`() =
        runBlocking<Unit> {
            // PLAN 14.7.3: the review writes rows a day "before" the draft was
            // saved. The snapshot must still verify and the import still go
            // through, with every moment as it was written.
            reviewClock = SteppingClock(Clock.System.now() - Duration.parse("1d"))
            val text =
                """
                game,source_type,raw_text
                Harmonies,3D,12 KIRMIZI
                """.trimIndent()
            val file = home.resolve("geri.csv")
            Files.write(file, text.toByteArray(StandardCharsets.UTF_8))
            val batchId = importFile(file, expect = ImportSourceFormat.CSV)
            val cellId = aGameWithACell()
            aimEveryDraft(batchId, cellId)
            val before = everything()
            val importedAt = before.importBatches.single().importedAt
            assertTrue(before.draftTasks.all { it.createdAt < importedAt }, "nothing ran backwards; the test proves nothing")

            theWholeJourney(batchId, alreadyAimed = true)
        }

    /**
     * Aim every draft, confirm through the controller a button press drives, and
     * hold the file that appeared against the database that was there.
     */
    private suspend fun theWholeJourney(
        batchId: EntityId,
        alreadyAimed: Boolean = false,
    ) {
        if (!alreadyAimed) aimEveryDraft(batchId, aGameWithACell())
        val before = everything()
        assertEquals(emptyList(), snapshots(), "something was in the backups folder before the import")

        val controller = ImportConfirmationController(confirmations())
        controller.refresh(batchId)
        // The cells nobody reviewed are a warning rather than a block, and a
        // smoke has to say what it means about them (PLAN 11.4.2).
        controller.acknowledgeUnprocessed(true)
        controller.confirm(batchId)

        val done =
            controller.state as? ImportConfirmationState.Confirmed
                ?: error("the confirmation was refused: ${(controller.state as ImportConfirmationState.Ready).failure}")
        assertTrue(done.result.createdTaskCount > 0, "the import created nothing")
        assertEquals(ImportBatchStatus.CONFIRMED, database.importDao().batchById(batchId)?.status)

        // One file, and it holds the database as it stood before the first task
        // was written — read back by the reader a restore would use, not by
        // anything this test knows how to do.
        val written = snapshots().single()
        assertEquals(before, readBack(written))
        assertNotEquals(before, everything(), "the confirmation wrote nothing")
        // A person could put it back: the same folder, the same format, the same
        // reader as their own backups (PLAN 14.4.7).
        assertTrue(written.endsWith(".json"), written)
        // And the one thing no backup ever does: write the settings file.
        assertTrue(Files.notExists(paths.settingsFile), "the import created the settings file by itself")
    }

    // ------------------------------------------------------- what is wired up

    private val importDao get() = database.importDao()

    /**
     * The review store's clock: one that moves a second on every reading, from
     * now unless a test sets it earlier before the first review.
     *
     * The draft store writes with the system clock, so a review clock set in the
     * past leaves rows whose `updated_at` precedes their `created_at`. That once
     * stopped the import outright — the backup reader refused such rows, so the
     * snapshot never verified. It is the application's own data and is accepted
     * now (PLAN 14.7.3); the test below walks exactly that road.
     */
    private var reviewClock: Clock = SteppingClock(Clock.System.now())

    /** One review store for the whole smoke. */
    private val review: ImportReviewStore by lazy {
        ImportReviewStore(importDao, database.gameDao(), database.colorDao(), clock = reviewClock)
    }

    private fun confirmations() =
        confirmationStore(
            database = database,
            snapshots =
                VerifiedSnapshotTaker(
                    exporter = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System),
                    writer = DesktopImportSnapshotWriter(paths.backupsDirectory),
                    reader = reader(),
                    clock = Clock.System,
                ),
            housekeeping =
                SettingsDrivenHousekeeping(
                    settings = DesktopSettingsStore(paths.settingsFile),
                    rotation = AutomaticBackupRotation(DesktopBackupDirectory(paths.backupsDirectory)),
                ),
            clock = Clock.System,
        )

    private fun reader() =
        UntrustedBackupReader(
            TemporaryBackupProbe(
                temporaryDirectory = { Files.createTempDirectory("pnp-tracker-import-smoke-probe").also(probeRoots::add) },
            ),
        )

    private suspend fun importFile(
        file: Path,
        expect: ImportSourceFormat,
    ): EntityId {
        val controller = ImportController(DesktopImportFileGateway(FixedPicker(file)), ImportDraftStore(importDao))
        controller.chooseFile()
        // A spreadsheet has pages to choose between; a CSV is one page and
        // never asks. The two paths meet again on the next line.
        when (val state = controller.state) {
            is ImportScreenState.SheetSelection ->
                controller.selectSheet(assertNotNull(state.session.selectedSheetName))

            is ImportScreenState.PreviewReady -> assertEquals(expect, state.session.sourceFormat)
            else -> error("the import stopped at $state")
        }
        controller.saveDraft()
        return assertIs<ImportScreenState.Saved>(controller.state).summary.batchId
    }

    private suspend fun aGameWithACell(): EntityId {
        val game = aGame(name = "Harmonies")
        val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        return cell.id
    }

    /** One draft out of every raw block, each aimed at [cellId] and ready. */
    private suspend fun aimEveryDraft(
        batchId: EntityId,
        cellId: EntityId,
    ) {
        val workspace = assertNotNull(review.observeWorkspace(batchId).first())
        workspace.rawBlocks
            .filter { it.sourceColumnType == SourceColumnType.THREE_D && it.rawText.isNotBlank() }
            .take(2)
            .forEach { block ->
                // The first line of the cell, because a selection may not span
                // a line break (PLAN 11.6) and a spreadsheet cell often holds
                // several lines.
                val line = block.rawText.lineSequence().first { it.isNotBlank() }
                val start = block.rawText.indexOf(line)
                val draftId = review.createDraftFromSelection(block.id, start, start + line.length)
                review.saveDraft(
                    DraftEdit(
                        draftTaskId = draftId,
                        name = line.trim().take(20),
                        targetCellId = cellId,
                        poolType = PoolType.THREE_D,
                        trackingMode = TrackingMode.THREE_D_BATCH,
                        requiredQuantity = 3,
                        notes = null,
                        isMissing = false,
                        isBorrowed = false,
                        needsInfo = false,
                        needsClassification = false,
                        // A `**` in the text is a question PLAN 11.5 will not
                        // let through unanswered, and answering one that was
                        // never asked is refused just as firmly.
                        completionHint = if ("**" in line) HintDecision.REJECTED else HintDecision.NONE,
                        colorIds = emptyList(),
                    ),
                )
            }
    }

    /**
     * Answers every green game cell with a no.
     *
     * The workbook fixture has some, and PLAN 11.5 will not let an unanswered
     * one through: a smoke about something else has to say what it means about
     * them rather than leave them to be guessed at.
     */
    private suspend fun rejectEveryGreenHint(batchId: EntityId) {
        importDao
            .rawBlocksOfBatch(batchId)
            .filter { it.gameCompletionHint == HintDecision.PENDING }
            .forEach { review.setGameCompletionDecision(it.id, HintDecision.REJECTED, null) }
    }

    private suspend fun everything(): BackupData = BackupStore(database).snapshot().data

    private fun snapshots(): List<String> =
        Files
            .list(paths.backupsDirectory)
            .use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }
            .filter { it.startsWith(IMPORT_SNAPSHOT_PREFIX) }

    private suspend fun readBack(fileName: String): BackupData {
        val read = reader().read(PathBackupInput(paths.backupsDirectory.resolve(fileName)))
        return (read as BackupReadResult.Valid).backup.data
    }
}
