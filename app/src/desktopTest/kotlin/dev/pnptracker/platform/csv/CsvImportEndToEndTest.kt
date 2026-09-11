package dev.pnptracker.platform.csv

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.repository.DraftEdit
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.data.repository.ImportReviewStore
import dev.pnptracker.data.repository.confirmationStore
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importreview.ImportReviewWorkspace
import dev.pnptracker.domain.importreview.ReviewRawBlock
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.ImportSourceFormat
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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A real CSV file, off a real disk, all the way to real tasks.
 *
 * The point of every test here is that nothing along the way is CSV's own. The
 * file is read by the CSV reader and then handed to the same preparation, the
 * same review store and the same confirmation transaction the spreadsheet uses,
 * so what is being checked is that the join holds: the rows arrive in order, the
 * flags and the pipelines come out the way PLAN describes them, and the cell
 * ends up reading what the user meant rather than what the file said.
 */
class CsvImportEndToEndTest {
    private lateinit var fileDirectory: Path
    private lateinit var databaseDirectory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_780_000_000_000)

    private class FixedPicker(
        private val file: Path?,
    ) : ImportFilePicker {
        override suspend fun chooseImportFile(): Path? = file
    }

    /** A clock that moves a second on every reading, so drafts keep their order. */
    private class SteppingClock(
        private val start: Instant,
    ) : kotlin.time.Clock {
        private var step = 0L

        override fun now(): Instant = start + Duration.parse("${step++}s")
    }

    @BeforeTest
    fun setUp() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        fileDirectory = Files.createTempDirectory("pnp-csv-e2e")
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

    private fun csvFile(
        name: String,
        text: String,
    ): Path = fileDirectory.resolve(name).also { Files.write(it, text.toByteArray(StandardCharsets.UTF_8)) }

    /**
     * One review store for the whole test, as the application has one.
     *
     * Its clock has to keep moving across every call: two drafts made one after
     * the other are read back in that order, and that is the order their tasks
     * are written into the cell in. A fresh store per call would restart the
     * clock and leave the order to the identifiers.
     */
    private val review: ImportReviewStore by lazy {
        ImportReviewStore(importDao, database.gameDao(), database.colorDao(), clock = SteppingClock(moment))
    }

    private fun confirmation() = confirmationStore(database, importDao, clock = StoppedClock(moment))

    private suspend fun importFile(file: Path): EntityId {
        val controller = ImportController(DesktopImportFileGateway(FixedPicker(file)), ImportDraftStore(importDao))
        controller.chooseFile()
        // A CSV is one logical page, so there is never a sheet to choose.
        val ready = assertIs<ImportScreenState.PreviewReady>(controller.state)
        assertEquals(ImportSourceFormat.CSV, ready.session.sourceFormat)
        controller.saveDraft()
        return assertIs<ImportScreenState.Saved>(controller.state).summary.batchId
    }

    private suspend fun workspace(batchId: EntityId): ImportReviewWorkspace = assertNotNull(review.observeWorkspace(batchId).first())

    private fun blockSaying(
        workspace: ImportReviewWorkspace,
        text: String,
    ): ReviewRawBlock = assertNotNull(workspace.rawBlocks.firstOrNull { it.rawText == text }, "no raw block reads `$text`")

    private suspend fun colorNamed(name: String): EntityId =
        assertNotNull(
            database.colorDao().allColors().firstOrNull { it.canonicalName == name },
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

    private suspend fun aGameWithCell(
        name: String,
        columnType: CellColumnType,
    ): Pair<EntityId, EntityId> {
        val game = aGame(name = name)
        val cell = aCell(gameId = game.id, columnType = columnType)
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        return game.id to cell.id
    }

    private suspend fun draftFromWholeBlock(block: ReviewRawBlock): EntityId =
        review.createDraftFromSelection(block.id, 0, block.rawText.length)

    private suspend fun decide(
        draftId: EntityId,
        name: String,
        cellId: EntityId,
        poolType: PoolType,
        trackingMode: TrackingMode,
        requiredQuantity: Int? = null,
        colorIds: List<EntityId> = emptyList(),
        isMissing: Boolean = false,
        isBorrowed: Boolean = false,
        needsClassification: Boolean = false,
    ) {
        review.saveDraft(
            DraftEdit(
                draftTaskId = draftId,
                name = name,
                targetCellId = cellId,
                poolType = poolType,
                trackingMode = trackingMode,
                requiredQuantity = requiredQuantity,
                notes = null,
                isMissing = isMissing,
                isBorrowed = isBorrowed,
                needsInfo = false,
                needsClassification = needsClassification,
                completionHint = HintDecision.NONE,
                colorIds = colorIds,
            ),
        )
    }

    private suspend fun taskNamed(
        batchId: EntityId,
        name: String,
    ) = assertNotNull(
        database.taskDao().activeTaskById(
            assertNotNull(
                importDao.draftTasksOfBatch(batchId).firstOrNull { it.name == name }?.materializedTaskId,
                "`$name` was never turned into a task",
            ),
        ),
    )

    // ------------------------------------------------------- the whole journey

    @Test
    fun `a comma file becomes raw blocks, drafts and finally a task`() =
        runBlocking<Unit> {
            val file = csvFile("liste.csv", "game,source_type,raw_text\nHarmonies,3d,12 Kırmızı ev\n")
            val batchId = importFile(file)

            val batch = assertNotNull(importDao.batchById(batchId))
            assertEquals(ImportSourceFormat.CSV, batch.sourceFormat)
            assertEquals("liste.csv", batch.fileName)
            assertEquals(ImportBatchStatus.DRAFT, batch.status)

            val (_, cellId) = aGameWithCell("Harmonies", CellColumnType.THREE_D)
            val block = blockSaying(workspace(batchId), "12 Kırmızı ev")
            assertEquals(SourceColumnType.THREE_D, block.sourceColumnType)

            val draft = draftFromWholeBlock(block)
            decide(draft, "Kırmızı ev", cellId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH, 12, listOf(colorNamed("Kırmızı")))
            val result = confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            assertEquals(1, result.createdTaskCount)
            assertEquals(0, result.createdGameCount, "the import created a game of its own")
            val task = taskNamed(batchId, "Kırmızı ev")
            assertEquals(12, task.requiredQuantity)
            assertEquals(block.id, task.sourceRawImportBlockId, "the trail back to the CSV row was lost")
            assertEquals("Kırmızı ev", documentOf(cellId))
        }

    @Test
    fun `a Turkish semicolon file travels the same road`() =
        runBlocking<Unit> {
            val file =
                csvFile(
                    "türkçe.csv",
                    "game;source_type;raw_text\nIŞIKLI ŞEHİR;laminasyon;8 IŞIKLI DİREK\n",
                )
            val batchId = importFile(file)

            val (_, cellId) = aGameWithCell("IŞIKLI ŞEHİR", CellColumnType.CARD)
            val block = blockSaying(workspace(batchId), "8 IŞIKLI DİREK")
            assertEquals(SourceColumnType.CARD, block.sourceColumnType, "the Turkish column heading was not understood")

            val draft = draftFromWholeBlock(block)
            decide(draft, "IŞIKLI DİREK", cellId, PoolType.CARD, TrackingMode.PIPELINE, 8)
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            val task = taskNamed(batchId, "IŞIKLI DİREK")
            assertEquals("IŞIKLI DİREK", task.name, "the Turkish letters were changed on the way through")
            assertEquals("IŞIKLI ŞEHİR", assertNotNull(importDao.rawBlocksOfBatch(batchId).firstOrNull()).rawText)
        }

    @Test
    fun `four source types in one file land in four different pools`() =
        runBlocking<Unit> {
            val file =
                csvFile(
                    "dört.csv",
                    """
                    game,source_type,raw_text
                    Harmonies,3d,Ev
                    Harmonies,card,Deste
                    Harmonies,board,Jeton
                    Harmonies,special,Kutu içi
                    """.trimIndent(),
                )
            val batchId = importFile(file)

            val cells =
                listOf(CellColumnType.THREE_D, CellColumnType.CARD, CellColumnType.BOARD, CellColumnType.SPECIAL)
            val game = aGame(name = "Harmonies")
            database.gameDao().insert(game)
            val cellIds =
                cells.associateWith { column ->
                    val cell = aCell(gameId = game.id, columnType = column)
                    database.gameCellDao().insert(cell)
                    cell.id
                }

            val plan =
                listOf(
                    Triple("Ev", PoolType.THREE_D, TrackingMode.THREE_D_BATCH),
                    Triple("Deste", PoolType.CARD, TrackingMode.PIPELINE),
                    Triple("Jeton", PoolType.BOARD, TrackingMode.PIPELINE),
                    Triple("Kutu içi", PoolType.SPECIAL, TrackingMode.CHECKLIST),
                )
            plan.forEach { (text, poolType, trackingMode) ->
                val block = blockSaying(workspace(batchId), text)
                decide(
                    draftFromWholeBlock(block),
                    text,
                    assertNotNull(cellIds[CellColumnType.of(poolType)]),
                    poolType,
                    trackingMode,
                )
            }
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            plan.forEach { (text, poolType, _) ->
                assertEquals(poolType, taskNamed(batchId, text).poolType, "`$text` went to the wrong pool")
            }
        }

    @Test
    fun `several games in one file keep their rows apart`() =
        runBlocking<Unit> {
            val file =
                csvFile(
                    "iki-oyun.csv",
                    """
                    game,source_type,raw_text
                    Harmonies,3d,Ev
                    Wingspan,3d,Yuva
                    """.trimIndent(),
                )
            val batchId = importFile(file)

            val (_, harmonies) = aGameWithCell("Harmonies", CellColumnType.THREE_D)
            val (_, wingspan) = aGameWithCell("Wingspan", CellColumnType.THREE_D)
            decide(
                draftFromWholeBlock(blockSaying(workspace(batchId), "Ev")),
                "Ev",
                harmonies,
                PoolType.THREE_D,
                TrackingMode.THREE_D_BATCH,
            )
            decide(
                draftFromWholeBlock(blockSaying(workspace(batchId), "Yuva")),
                "Yuva",
                wingspan,
                PoolType.THREE_D,
                TrackingMode.THREE_D_BATCH,
            )
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            assertEquals("Ev", documentOf(harmonies))
            assertEquals("Yuva", documentOf(wingspan))
            // Each row named its own game, so both game names were kept.
            assertEquals(
                listOf("Harmonies", "Wingspan"),
                importDao
                    .rawBlocksOfBatch(batchId)
                    .filter { it.sourceColumnType == SourceColumnType.GAME }
                    .map { it.rawText },
            )
        }

    @Test
    fun `two rows for one game become two tasks kept apart by a written space`() =
        runBlocking<Unit> {
            val file =
                csvFile(
                    "aynı-oyun.csv",
                    """
                    game,source_type,raw_text
                    Harmonies,3d,Kırmızı ev
                    Harmonies,3d,Mavi ev
                    """.trimIndent(),
                )
            val batchId = importFile(file)
            val (_, cellId) = aGameWithCell("Harmonies", CellColumnType.THREE_D)

            listOf("Kırmızı ev", "Mavi ev").forEach { text ->
                decide(
                    draftFromWholeBlock(blockSaying(workspace(batchId), text)),
                    text,
                    cellId,
                    PoolType.THREE_D,
                    TrackingMode.THREE_D_BATCH,
                )
            }
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            // Adım 18C: one ordinary space between them, kept as a real piece of
            // the document rather than drawn in by the screen.
            assertEquals("Kırmızı ev Mavi ev", documentOf(cellId))
            val pieces = database.cellSegmentDao().segmentsOfCell(cellId)
            assertEquals(listOf(SegmentKind.TASK, SegmentKind.PLAIN_TEXT, SegmentKind.TASK), pieces.map { it.kind })
            assertEquals(" ", pieces[1].text)
        }

    @Test
    fun `writing already in the cell keeps its own prefix and suffix`() =
        runBlocking<Unit> {
            val file = csvFile("tek.csv", "game,source_type,raw_text\nHarmonies,3d,Kırmızı ev\n")
            val batchId = importFile(file)
            val (gameId, cellId) = aGameWithCell("Harmonies", CellColumnType.THREE_D)
            dev.pnptracker.data.repository
                .CellTextStore(database.cellSegmentDao(), clock = StoppedClock(moment))
                .saveDocumentText(gameId, CellColumnType.THREE_D, "", "elimde kalanlar: ")

            decide(
                draftFromWholeBlock(blockSaying(workspace(batchId), "Kırmızı ev")),
                "Kırmızı ev",
                cellId,
                PoolType.THREE_D,
                TrackingMode.THREE_D_BATCH,
            )
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            // The existing text ends in a space already, so no second one is added.
            assertEquals("elimde kalanlar: Kırmızı ev", documentOf(cellId))
        }

    // ------------------------------------------------------- colours and shape

    @Test
    fun `a task the user gave one colour has exactly that colour`() =
        runBlocking<Unit> {
            val batchId = importFile(csvFile("renk.csv", "game,source_type,raw_text\nHarmonies,3d,Ev\n"))
            val (_, cellId) = aGameWithCell("Harmonies", CellColumnType.THREE_D)

            decide(
                draftFromWholeBlock(blockSaying(workspace(batchId), "Ev")),
                "Ev",
                cellId,
                PoolType.THREE_D,
                TrackingMode.THREE_D_BATCH,
                colorIds = listOf(colorNamed("Kırmızı")),
            )
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            val task = taskNamed(batchId, "Ev")
            assertEquals(listOf(colorNamed("Kırmızı")), database.taskColorDao().colorsOfTask(task.id).map { it.colorId })
        }

    @Test
    fun `one task in three colours keeps the user's own order`() =
        runBlocking<Unit> {
            val batchId = importFile(csvFile("çok-renk.csv", "game,source_type,raw_text\nHarmonies,3d,Bayrak\n"))
            val (_, cellId) = aGameWithCell("Harmonies", CellColumnType.THREE_D)
            val chosen = listOf(colorNamed("Beyaz"), colorNamed("Kırmızı"), colorNamed("Siyah"))

            decide(
                draftFromWholeBlock(blockSaying(workspace(batchId), "Bayrak")),
                "Bayrak",
                cellId,
                PoolType.THREE_D,
                TrackingMode.THREE_D_BATCH,
                colorIds = chosen,
            )
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            val task = taskNamed(batchId, "Bayrak")
            assertEquals(chosen, database.taskColorDao().colorsOfTask(task.id).map { it.colorId })
            // One task, one piece in the cell, however many colours it is made in.
            assertEquals(1, database.cellSegmentDao().segmentsOfCell(cellId).count { it.kind == SegmentKind.TASK })
        }

    @Test
    fun `how many are needed is written on the task and nowhere else`() =
        runBlocking<Unit> {
            val batchId = importFile(csvFile("adet.csv", "game,source_type,raw_text\nHarmonies,card,Deste\n"))
            val (_, cellId) = aGameWithCell("Harmonies", CellColumnType.CARD)

            decide(
                draftFromWholeBlock(blockSaying(workspace(batchId), "Deste")),
                "Deste",
                cellId,
                PoolType.CARD,
                TrackingMode.PIPELINE,
                requiredQuantity = 55,
            )
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            val task = taskNamed(batchId, "Deste")
            assertEquals(55, task.requiredQuantity)
            // The pipeline records what has been finished, never a second copy of
            // the total (PLAN 6.4).
            assertTrue(database.taskProgressDao().stagesOfTask(task.id).all { it.completedQuantity == 0 })
        }

    @Test
    fun `a card task arrives with one print, laminate and cut, in that order`() =
        runBlocking<Unit> {
            val batchId = importFile(csvFile("kart.csv", "game,source_type,raw_text\nHarmonies,card,Deste\n"))
            val (_, cellId) = aGameWithCell("Harmonies", CellColumnType.CARD)

            decide(
                draftFromWholeBlock(blockSaying(workspace(batchId), "Deste")),
                "Deste",
                cellId,
                PoolType.CARD,
                TrackingMode.PIPELINE,
            )
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            val stages = database.taskProgressDao().stagesOfTask(taskNamed(batchId, "Deste").id)
            assertEquals(
                listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT),
                stages.sortedBy { it.orderIndex }.map { it.stage },
            )
            assertEquals(listOf(0, 1, 2), stages.sortedBy { it.orderIndex }.map { it.orderIndex })
        }

    @Test
    fun `a board task arrives with one print, glue and cut, in that order`() =
        runBlocking<Unit> {
            val batchId = importFile(csvFile("mukavva.csv", "game,source_type,raw_text\nHarmonies,mukavva,Jeton\n"))
            val (_, cellId) = aGameWithCell("Harmonies", CellColumnType.BOARD)

            decide(
                draftFromWholeBlock(blockSaying(workspace(batchId), "Jeton")),
                "Jeton",
                cellId,
                PoolType.BOARD,
                TrackingMode.PIPELINE,
            )
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            val stages = database.taskProgressDao().stagesOfTask(taskNamed(batchId, "Jeton").id)
            assertEquals(
                listOf(ProductionStage.PRINT, ProductionStage.GLUE, ProductionStage.CUT),
                stages.sortedBy { it.orderIndex }.map { it.stage },
            )
        }

    @Test
    fun `the missing and borrowed columns arrive as flags, not as pools`() =
        runBlocking<Unit> {
            val file =
                csvFile(
                    "işaret.csv",
                    """
                    game,source_type,raw_text
                    Harmonies,eksik,Eksik zar
                    Harmonies,ödünç parçalar,Ödünç kum saati
                    """.trimIndent(),
                )
            val batchId = importFile(file)
            val (_, cellId) = aGameWithCell("Harmonies", CellColumnType.SPECIAL)

            val eksik = blockSaying(workspace(batchId), "Eksik zar")
            val odunc = blockSaying(workspace(batchId), "Ödünç kum saati")
            assertEquals(SourceColumnType.MISSING, eksik.sourceColumnType)
            assertEquals(SourceColumnType.BORROWED, odunc.sourceColumnType)

            decide(
                draftFromWholeBlock(eksik),
                "Eksik zar",
                cellId,
                PoolType.SPECIAL,
                TrackingMode.CHECKLIST,
                isMissing = true,
                needsClassification = true,
            )
            decide(
                draftFromWholeBlock(odunc),
                "Ödünç kum saati",
                cellId,
                PoolType.SPECIAL,
                TrackingMode.CHECKLIST,
                isBorrowed = true,
                needsClassification = true,
            )
            confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)

            val missing = taskNamed(batchId, "Eksik zar")
            val borrowed = taskNamed(batchId, "Ödünç kum saati")
            assertTrue(missing.isMissing && !missing.isBorrowed)
            assertTrue(borrowed.isBorrowed && !borrowed.isMissing)
            assertEquals(PoolType.SPECIAL, missing.poolType, "a flagged row was made into a pool of its own")
        }

    // ------------------------------------------------------------ the second time

    @Test
    fun `confirming a second time creates nothing and says so`() =
        runBlocking<Unit> {
            val batchId = importFile(csvFile("tekrar.csv", "game,source_type,raw_text\nHarmonies,3d,Ev\n"))
            val (_, cellId) = aGameWithCell("Harmonies", CellColumnType.THREE_D)
            decide(
                draftFromWholeBlock(blockSaying(workspace(batchId), "Ev")),
                "Ev",
                cellId,
                PoolType.THREE_D,
                TrackingMode.THREE_D_BATCH,
            )
            assertEquals(1, confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true).createdTaskCount)

            // A second call is a guarded refusal, not a repeat that happens to
            // produce nothing (PLAN 11.4.2).
            val refused =
                assertFailsWith<ImportConfirmationException> {
                    confirmation().confirm(batchId, acknowledgeUnprocessedBlocks = true)
                }

            assertEquals(ImportConfirmationFailure.ALREADY_CONFIRMED, refused.failure)
            assertEquals(1, database.taskDao().activeTasks().size)
        }
}
