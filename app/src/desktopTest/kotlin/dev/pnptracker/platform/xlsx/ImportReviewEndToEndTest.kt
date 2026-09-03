package dev.pnptracker.platform.xlsx

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.repository.CellTextStore
import dev.pnptracker.data.repository.DraftEdit
import dev.pnptracker.data.repository.ImportConfirmationStore
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.data.repository.ImportReviewStore
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importreview.ImportReviewWorkspace
import dev.pnptracker.domain.importreview.ReviewRawBlock
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.platform.importfiles.DesktopImportFileGateway
import dev.pnptracker.platform.importfiles.ImportFilePicker
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreenState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * One of the reference file's awkward cells, carried the whole way: read from
 * the committed workbook, cut into tasks by hand, decided about, and confirmed
 * into real work.
 *
 * `12 KIRMIZI**\n8 MAVİ` is the case PLAN 11.7 calls several tasks and a note in
 * one cell, and it is the one that has to come out as two independent tasks in
 * the colours the user chose — not as one task, and not as the cell's whole text
 * copied into somebody's game.
 */
class ImportReviewEndToEndTest {
    private lateinit var fileDirectory: Path
    private lateinit var file: Path
    private lateinit var databaseDirectory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_780_000_000_000)

    private class FixedPicker(
        private val file: Path,
    ) : ImportFilePicker {
        override suspend fun chooseImportFile(): Path = file
    }

    /**
     * A clock that moves a second on every reading.
     *
     * Two drafts cut out of one cell are read back in the order they were made,
     * and that is the order their tasks are written into the cell in. A stopped
     * clock would make both claim the same instant and leave the order to the
     * identifiers, which is not what the user did.
     */
    private class SteppingClock(
        private val start: Instant,
    ) : kotlin.time.Clock {
        private var step = 0L

        override fun now(): Instant = start + kotlin.time.Duration.parse("${step++}s")
    }

    @BeforeTest
    fun setUp() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        fileDirectory = Files.createTempDirectory("pnp-review-e2e")
        file = copyFixtureInto(fileDirectory)
        databaseDirectory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(databaseDirectory.databaseFile)
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        database.close()
        databaseDirectory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        databaseDirectory.delete()
        check(fileDirectory.startsWith(Path.of(System.getProperty("java.io.tmpdir")))) {
            "refusing to delete $fileDirectory, which is not under the temporary directory"
        }
        fileDirectory.deleteRecursively()
    }

    private val importDao get() = database.importDao()

    // A clock that moves, so two drafts cut out of one cell really are made one
    // after the other: `draftTasksOfBatch` reads them in that order, and that is
    // the order their tasks are written into the cell in.
    private fun review() = ImportReviewStore(importDao, database.gameDao(), database.colorDao(), clock = SteppingClock(moment))

    private fun confirmation() =
        ImportConfirmationStore(importDao, database.gameCellDao(), database.gameDao(), clock = StoppedClock(moment))

    /** The whole real import, through the real reader, into this database. */
    private suspend fun importTheFixture(): EntityId {
        val controller =
            ImportController(DesktopImportFileGateway(FixedPicker(file)), ImportDraftStore(importDao))
        controller.chooseFile()
        val chooser = assertIs<ImportScreenState.SheetSelection>(controller.state)
        controller.selectSheet(assertNotNull(chooser.session.selectedSheetName))
        controller.saveDraft()
        return assertIs<ImportScreenState.Saved>(controller.state).summary.batchId
    }

    private suspend fun workspace(batchId: EntityId): ImportReviewWorkspace = assertNotNull(review().observeWorkspace(batchId).first())

    private fun cellSaying(
        workspace: ImportReviewWorkspace,
        text: String,
    ): ReviewRawBlock = assertNotNull(workspace.rawBlocks.firstOrNull { it.rawText == text }, "the fixture no longer holds `$text`")

    /**
     * Answers every green game cell with a no.
     *
     * The fixture has two of them, and PLAN 11.5 will not let an unanswered one
     * through: a test about something else has to say what it means about these
     * rather than leave them to be guessed at.
     */
    private suspend fun rejectEveryGreenHint(batchId: EntityId) {
        val review = review()
        importDao
            .rawBlocksOfBatch(batchId)
            .filter { it.gameCompletionHint == HintDecision.PENDING }
            .forEach { review.setGameCompletionDecision(it.id, HintDecision.REJECTED, null) }
    }

    private suspend fun colorNamed(name: String): EntityId =
        assertNotNull(
            database
                .colorDao()
                .allColors()
                .firstOrNull { it.canonicalName == name },
            "the seed catalogue has no `$name`",
        ).id

    private suspend fun documentOf(cellId: EntityId): String =
        buildString {
            database.cellSegmentDao().segmentsOfCell(cellId).forEach { piece ->
                if (piece.kind == SegmentKind.TASK) {
                    append(assertNotNull(database.taskDao().activeTaskById(assertNotNull(piece.taskId))).name)
                } else {
                    append(piece.text.orEmpty())
                }
            }
        }

    @Test
    fun `one awkward cell becomes two independent tasks, and the cell keeps its own words`() =
        runBlocking<Unit> {
            val batchId = importTheFixture()
            val review = review()

            // The user makes the game and the cell themselves; the import makes
            // neither (PLAN 11.4.1).
            val game = aGame(name = "Örnek Oyun A")
            val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
            database.gameDao().insert(game)
            database.gameCellDao().insert(cell)
            // Something already written in the cell, to be left exactly alone.
            CellTextStore(database.cellSegmentDao(), clock = StoppedClock(moment))
                .saveDocumentText(game.id, CellColumnType.THREE_D, "", "elimde kalanlar: ")

            val awkward = cellSaying(workspace(batchId), "12 KIRMIZI**\n8 MAVİ")
            assertEquals(SourceColumnType.THREE_D, awkward.sourceColumnType)

            // Two cuts out of one cell, one per line.
            val red = review.createDraftFromSelection(awkward.id, 0, 12)
            val blue = review.createDraftFromSelection(awkward.id, 13, 19)

            val drafts = workspace(batchId).draftsOf(awkward.id).associateBy { it.id }
            assertEquals("12 KIRMIZI", assertNotNull(drafts[red]).name, "the marker is cleared from the name")
            assertEquals("8 MAVİ", assertNotNull(drafts[blue]).name)
            assertEquals(12, assertNotNull(drafts[red]).requiredQuantity)
            assertEquals(8, assertNotNull(drafts[blue]).requiredQuantity)
            assertEquals(HintDecision.PENDING, assertNotNull(drafts[red]).completionHint)
            assertEquals(HintDecision.NONE, assertNotNull(drafts[blue]).completionHint)

            // The user answers what the file only hinted at, and says what each
            // task is: the red one is finished, the blue one is still to make.
            review.saveDraft(
                DraftEdit(
                    draftTaskId = red,
                    name = "Kırmızı ev",
                    targetCellId = cell.id,
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = 12,
                    notes = null,
                    isMissing = false,
                    isBorrowed = false,
                    needsInfo = false,
                    needsClassification = false,
                    completionHint = HintDecision.ACCEPTED,
                    colorIds = listOf(colorNamed("Kırmızı")),
                ),
            )
            review.saveDraft(
                DraftEdit(
                    draftTaskId = blue,
                    name = "Mavi ev",
                    targetCellId = cell.id,
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = 8,
                    notes = "sonra bakılacak",
                    isMissing = false,
                    isBorrowed = false,
                    needsInfo = false,
                    needsClassification = false,
                    completionHint = HintDecision.NONE,
                    colorIds = listOf(colorNamed("Mavi")),
                ),
            )

            rejectEveryGreenHint(batchId)
            val result = confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            assertEquals(2, result.createdTaskCount)
            assertEquals(0, result.createdGameCount, "the import creates no game")

            val confirmed = workspace(batchId).draftsOf(awkward.id).associateBy { it.name }
            val redTask =
                assertNotNull(
                    database.taskDao().activeTaskById(assertNotNull(assertNotNull(confirmed["Kırmızı ev"]).materializedTaskId)),
                )
            val blueTask =
                assertNotNull(
                    database.taskDao().activeTaskById(assertNotNull(assertNotNull(confirmed["Mavi ev"]).materializedTaskId)),
                )

            // Two tasks, nothing shared between them (PLAN 12.7).
            assertTrue(redTask.id != blueTask.id)
            assertTrue(redTask.isCompleted, "the accepted marker did not finish the task")
            assertTrue(redTask.primaryBatchCompleted)
            assertFalse(blueTask.isCompleted)
            assertEquals(12, redTask.requiredQuantity)
            assertEquals(8, blueTask.requiredQuantity)
            assertEquals("sonra bakılacak", blueTask.notes)
            assertEquals(awkward.id, redTask.sourceRawImportBlockId, "the trail back to the cell is kept")

            assertEquals(
                listOf(colorNamed("Kırmızı")),
                database.taskColorDao().colorsOfTask(redTask.id).map { it.colorId },
            )
            assertEquals(
                listOf(colorNamed("Mavi")),
                database.taskColorDao().colorsOfTask(blueTask.id).map { it.colorId },
            )
            // A 3D task has no pipeline; both are counted as one print run.
            assertEquals(emptyList(), database.taskProgressDao().stagesOfTask(redTask.id))
            assertEquals(emptyList(), database.taskProgressDao().stagesOfTask(blueTask.id))
            assertEquals(emptyList(), database.taskProgressDao().progressEventsOfTask(redTask.id))

            // The cell reads as what the user wrote plus the two task names, and
            // nothing of the raw import text was copied into it.
            assertEquals("elimde kalanlar: Kırmızı ev Mavi ev", documentOf(cell.id))
            assertTrue(
                "12 KIRMIZI**" !in documentOf(cell.id),
                "the cell's whole raw text was copied into the game",
            )

            // The source cell is untouched and still says what the file said.
            val storedBlock = assertNotNull(importDao.rawBlockById(awkward.id))
            assertEquals("12 KIRMIZI**\n8 MAVİ", storedBlock.rawText)
            assertEquals(SourceColumnType.THREE_D, storedBlock.sourceColumnType)
            assertEquals(ImportBatchStatus.CONFIRMED, assertNotNull(importDao.batchById(batchId)).status)
        }

    @Test
    fun `a card cell arrives with its whole pipeline`() =
        runBlocking<Unit> {
            val batchId = importTheFixture()
            val review = review()
            val game = aGame(name = "Örnek Oyun B")
            val cell = aCell(gameId = game.id, columnType = CellColumnType.CARD)
            database.gameDao().insert(game)
            database.gameCellDao().insert(cell)

            val cardCell = cellSaying(workspace(batchId), "Kırmızı kalın ve mavi normal")
            val draft = review.createDraftFromSelection(cardCell.id, 0, 28)
            review.saveDraft(
                DraftEdit(
                    draftTaskId = draft,
                    name = "Kuş kartları",
                    targetCellId = cell.id,
                    poolType = PoolType.CARD,
                    trackingMode = TrackingMode.PIPELINE,
                    requiredQuantity = 40,
                    notes = null,
                    isMissing = false,
                    isBorrowed = false,
                    needsInfo = false,
                    needsClassification = false,
                    completionHint = HintDecision.NONE,
                    colorIds = emptyList(),
                ),
            )

            rejectEveryGreenHint(batchId)
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            val taskId =
                assertNotNull(assertNotNull(importDao.draftTaskById(draft)).materializedTaskId)
            assertEquals(
                listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT),
                database.taskProgressDao().stagesOfTask(taskId).map { it.stage },
            )
            assertTrue(
                database.taskProgressDao().stagesOfTask(taskId).all { it.completedQuantity == 0 },
                "an open card task arrived with work already done",
            )
        }

    @Test
    fun `an unanswered marker stops the whole import and leaves the cell alone`() =
        runBlocking<Unit> {
            val batchId = importTheFixture()
            val review = review()
            val game = aGame(name = "Örnek Oyun A")
            val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
            database.gameDao().insert(game)
            database.gameCellDao().insert(cell)
            CellTextStore(database.cellSegmentDao(), clock = StoppedClock(moment))
                .saveDocumentText(game.id, CellColumnType.THREE_D, "", "elimde kalanlar")

            val awkward = cellSaying(workspace(batchId), "12 KIRMIZI**\n8 MAVİ")
            val red = review.createDraftFromSelection(awkward.id, 0, 12)
            review.saveDraft(
                DraftEdit(
                    draftTaskId = red,
                    name = "Kırmızı ev",
                    targetCellId = cell.id,
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = 12,
                    notes = null,
                    isMissing = false,
                    isBorrowed = false,
                    needsInfo = false,
                    needsClassification = false,
                    // Still PENDING: the user has not answered the `**`.
                    completionHint = HintDecision.PENDING,
                    colorIds = emptyList(),
                ),
            )

            rejectEveryGreenHint(batchId)
            val refusal =
                assertFailsWith<ImportConfirmationException> {
                    confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)
                }

            assertEquals(ImportConfirmationFailure.COMPLETION_HINT_UNDECIDED, refusal.failure)
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
            assertEquals("elimde kalanlar", documentOf(cell.id), "the cell was touched by a refused import")
            assertEquals(ImportBatchStatus.DRAFT, assertNotNull(importDao.batchById(batchId)).status)
            assertNull(assertNotNull(importDao.draftTaskById(red)).materializedTaskId)
        }

    @Test
    fun `every cell of the file survives the confirmation exactly as it was read`() =
        runBlocking<Unit> {
            val batchId = importTheFixture()
            val review = review()
            val game = aGame(name = "Örnek Oyun C")
            val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
            database.gameDao().insert(game)
            database.gameCellDao().insert(cell)

            val before = importDao.rawBlocksOfBatch(batchId)
            val green = cellSaying(workspace(batchId), "3 YEŞİL**")
            val draft = review.createDraftFromSelection(green.id, 0, 9)
            review.setCompletionDecision(draft, HintDecision.REJECTED)
            review.saveDraft(
                DraftEdit(
                    draftTaskId = draft,
                    name = "Yeşil token",
                    targetCellId = cell.id,
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = 3,
                    notes = null,
                    isMissing = false,
                    isBorrowed = false,
                    needsInfo = false,
                    needsClassification = false,
                    completionHint = HintDecision.REJECTED,
                    colorIds = emptyList(),
                ),
            )

            rejectEveryGreenHint(batchId)
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            val after = importDao.rawBlocksOfBatch(batchId)
            assertEquals(before.size, after.size, "the import lost or gained a cell")
            assertEquals(
                before.map { it.id to it.rawText },
                after.map { it.id to it.rawText },
                "a cell's text changed while the import was confirmed",
            )
        }
}
