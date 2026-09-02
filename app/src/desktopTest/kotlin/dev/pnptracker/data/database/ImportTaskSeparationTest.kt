package dev.pnptracker.data.database

import dev.pnptracker.data.repository.TaskFromTextStore
import dev.pnptracker.domain.games.DocumentRun
import dev.pnptracker.domain.games.documentText
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskDraft
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What a cell reads once an import has written its tasks into it.
 *
 * A cell document is its pieces laid end to end (PLAN 5.5), which is right for
 * text somebody typed and wrong for tasks appended one after another: two of
 * them came out as `Kırmızı evMavi ev` — one word on the screen, one word to a
 * screen reader, one word in the clipboard, and one word again when the cell was
 * opened for editing. The boundary has to be a piece of the document, not a gap
 * drawn between two chips, or it is only true of the screen it was drawn on.
 *
 * So everything here is asked of a real database and read back as the document
 * itself: the rows, their order, and the string they spell. The raw import text
 * is still never copied into the cell — only the names the user cut out of it,
 * with a single space where one is needed and nowhere else.
 */
class ImportTaskSeparationTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_780_000_000_000)

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val importDao get() = database.importDao()

    // ------------------------------------------------------------- fixtures

    private var gameId: EntityId = IdGenerator.Random.newId()
    private var cellId: EntityId = IdGenerator.Random.newId()
    private var batchId: EntityId = IdGenerator.Random.newId()
    private var rows = 0

    /** One game, one 3D cell, one draft import — the shape every case below starts from. */
    private suspend fun given() {
        val game = aGame(name = "Harmonies")
        val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        val batch = anImportBatch(rawBlockCount = 1)
        importDao.insertBatch(batch)
        gameId = game.id
        cellId = cell.id
        batchId = batch.id
        rows = 0
    }

    /** Whatever the user had already written in the cell, through the real editor. */
    private suspend fun cellAlreadySays(text: String) {
        database.cellSegmentDao().saveDocumentText(
            gameId,
            CellColumnType.THREE_D,
            "",
            text,
            StoppedClock(updatedAt),
            IdGenerator.Random,
        )
    }

    /** One draft cut out of one cell of the file, ready to be confirmed. */
    private suspend fun draft(
        name: String,
        colorIds: List<EntityId> = emptyList(),
    ): EntityId {
        rows++
        val block =
            aRawImportBlock(
                batchId,
                rowIndex = rows,
                columnIndex = 1,
                rawText = "15 KIRMIZI $name",
                sourceColumnType = SourceColumnType.THREE_D,
            )
        importDao.insertRawBlock(block)
        importDao.setRawBlockProcessed(block.id, true, updatedAt)
        // A moment of its own for each draft: `draftTasksOfBatch` reads them in
        // the order they were made, and that is the order they are written into
        // the cell in. Drafts sharing an instant would leave the reading order to
        // the identifiers, which is not what the user did.
        val draft =
            aDraftTask(block.id, name = name).copy(
                requiredQuantity = 20,
                createdAt = createdAt + rows.seconds,
            )
        importDao.addDraftTask(draft)
        importDao.setDraftTargetUnderReview(draft.id, cellId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH, updatedAt)
        if (colorIds.isNotEmpty()) importDao.setDraftColorsUnderReview(draft.id, colorIds, StoppedClock(updatedAt))
        return draft.id
    }

    private suspend fun confirm(): Int =
        importDao.confirmDraftBatch(
            batchId,
            acknowledgeUnprocessedBlocks = true,
            clock = StoppedClock(moment),
            idGenerator = IdGenerator.Random,
        )

    private suspend fun segments() = database.cellSegmentDao().segmentsOfCell(cellId)

    /** What the cell reads: its pieces end to end, a task piece read as its name. */
    private suspend fun documentOf(): String =
        buildString {
            segments().forEach { piece ->
                append(
                    piece.text
                        ?: assertNotNull(database.taskDao().taskByIdIncludingDeleted(assertNotNull(piece.taskId))).name,
                )
            }
        }

    /** How many pieces of the document are a separator this import wrote. */
    private suspend fun separators(): Int = segments().count { it.kind == SegmentKind.PLAIN_TEXT && it.text == " " }

    private suspend fun colorId(name: String): EntityId = assertNotNull(database.colorDao().resolve(name)).id

    // ------------------------------------------------- an empty cell to start with

    @Test
    fun `one task in an empty cell stands on its own, with no space at either end`() =
        runBlocking<Unit> {
            given()
            draft("Kırmızı ev")

            assertEquals(1, confirm())

            assertEquals("Kırmızı ev", documentOf())
            assertEquals(0, separators(), "a task with nothing beside it was given a space anyway")
            assertEquals(listOf(SegmentKind.TASK), segments().map { it.kind })
        }

    @Test
    fun `two tasks in an empty cell are parted by exactly one space`() =
        runBlocking<Unit> {
            given()
            draft("Kırmızı ev")
            draft("Mavi ev")

            assertEquals(2, confirm())

            assertEquals("Kırmızı ev Mavi ev", documentOf())
            assertEquals(1, separators())
            assertEquals(
                listOf(SegmentKind.TASK, SegmentKind.PLAIN_TEXT, SegmentKind.TASK),
                segments().map { it.kind },
            )
        }

    @Test
    fun `forty-two tasks in one cell are parted by forty-one spaces and no more`() =
        runBlocking<Unit> {
            given()
            (0..<42).forEach { draft("Token $it") }

            assertEquals(42, confirm())

            val names =
                importDao.draftTasksOfBatch(batchId).map {
                    assertNotNull(database.taskDao().taskByIdIncludingDeleted(assertNotNull(it.materializedTaskId))).name
                }
            assertEquals(names.joinToString(" "), documentOf())
            assertEquals(41, separators())
            assertEquals(83, segments().size)
            assertTrue("  " !in documentOf(), "two tasks were parted by more than one space")
        }

    // --------------------------------------------- writing that was already there

    @Test
    fun `text that does not end in a space gets one before the task that follows it`() =
        runBlocking<Unit> {
            given()
            cellAlreadySays("Kutu ölçüsü 30×30")
            draft("Kırmızı ev")
            draft("Mavi ev")

            assertEquals(2, confirm())

            assertEquals("Kutu ölçüsü 30×30 Kırmızı ev Mavi ev", documentOf())
            assertEquals(2, separators())
        }

    @Test
    fun `text that already ends in a space is not given a second one`() =
        runBlocking<Unit> {
            given()
            cellAlreadySays("Kutu ölçüsü 30×30 ")
            draft("Kırmızı ev")

            assertEquals(1, confirm())

            assertEquals("Kutu ölçüsü 30×30 Kırmızı ev", documentOf())
            assertEquals(0, separators(), "a space was added where the user had already left one")
        }

    @Test
    fun `text that ends in a tab is left as it is`() =
        runBlocking<Unit> {
            given()
            cellAlreadySays("Not:\t")
            draft("Ev")

            assertEquals(1, confirm())

            assertEquals("Not:\tEv", documentOf())
            assertEquals(0, separators())
        }

    @Test
    fun `text that ends in a line ending is left as it is`() =
        runBlocking<Unit> {
            given()
            cellAlreadySays("Not:\n")
            draft("Ev")

            assertEquals(1, confirm())

            assertEquals("Not:\nEv", documentOf())
            assertEquals(0, separators(), "a line ending is a boundary already")
        }

    @Test
    fun `the punctuation and spacing the user typed comes through character for character`() =
        runBlocking<Unit> {
            given()
            val written = "Kutu:  30×30 cm — (kapaklı), bkz. şablon…"
            cellAlreadySays(written)
            draft("Kırmızı ev")

            confirm()

            assertEquals(written, assertNotNull(segments().first().text))
            assertTrue(documentOf().startsWith("$written "))
        }

    // -------------------------------------------------- a task already in the cell

    /** Turns the whole of the cell's only piece of text into a task, as the user does. */
    private suspend fun taskAlreadyInTheCell(
        word: String,
        colorNames: List<String>,
    ): EntityId {
        val piece = assertNotNull(segments().firstOrNull())
        return TaskFromTextStore(database.taskFromTextDao(), IdGenerator.Random, StoppedClock(updatedAt))
            .createTasks(
                CellTextSelection(gameId, cellId, piece.id, word, 0, word.length),
                listOf(
                    TaskDraft(
                        colorIds = colorNames.map { colorId(it) },
                        requiredQuantity = 6,
                        trackingMode = TrackingMode.THREE_D_BATCH,
                        notes = null,
                    ),
                ),
            ).single()
    }

    @Test
    fun `a task already in the cell is parted from the one an import adds after it`() =
        runBlocking<Unit> {
            given()
            cellAlreadySays("Zar")
            val existing = taskAlreadyInTheCell("Zar", listOf("Gri"))
            draft("Ev")

            assertEquals(1, confirm())

            assertEquals("Zar Ev", documentOf())
            assertEquals(1, separators())
            // The task that was already there kept its identity and its place.
            assertEquals(existing, assertNotNull(segments().first().taskId))
        }

    @Test
    fun `a task of several colours already in the cell is parted the same way`() =
        runBlocking<Unit> {
            given()
            cellAlreadySays("Zar")
            val existing = taskAlreadyInTheCell("Zar", listOf("Gri", "Mavi"))
            draft("Ev", colorIds = listOf(colorId("Kırmızı")))

            assertEquals(1, confirm())

            assertEquals("Zar Ev", documentOf())
            assertEquals(1, separators())
            assertEquals(
                listOf(colorId("Gri"), colorId("Mavi")),
                database.taskColorDao().colorsOfTask(existing).map { it.colorId },
                "the colours of the task that was already there were disturbed",
            )
        }

    // ------------------------------------------------------- what was not touched

    @Test
    fun `the names the user chose are written exactly as they chose them`() =
        runBlocking<Unit> {
            given()
            draft("Kırmızı ev")
            draft("Mavi ev")

            confirm()

            assertEquals(
                setOf("Kırmızı ev", "Mavi ev"),
                database
                    .taskDao()
                    .activeTasks()
                    .map { it.name }
                    .toSet(),
                "a space was worked into a task's own name",
            )
        }

    @Test
    fun `the cells of the file are not touched at all`() =
        runBlocking<Unit> {
            given()
            draft("Kırmızı ev")
            draft("Mavi ev")
            val before = importDao.rawBlocksOfBatch(batchId)

            confirm()

            assertEquals(before, importDao.rawBlocksOfBatch(batchId))
        }

    @Test
    fun `every draft is still one task and one task piece, separators aside`() =
        runBlocking<Unit> {
            given()
            (0..<5).forEach { draft("Token $it") }

            assertEquals(5, confirm())

            assertEquals(5, database.taskDao().activeTasks().size)
            assertEquals(5, segments().count { it.kind == SegmentKind.TASK })
            assertEquals(4, segments().count { it.kind == SegmentKind.PLAIN_TEXT })
            assertEquals((0..<9).toList(), segments().map { it.orderIndex })
        }

    // ------------------------------------------ what everything else reads afterwards

    @Test
    fun `the runs the editor and the clipboard read spell the same words`() =
        runBlocking<Unit> {
            given()
            cellAlreadySays("Kutu ölçüsü 30×30")
            draft("Kırmızı ev")
            draft("Mavi ev")

            confirm()

            var at = 0
            val runs =
                database.cellSegmentDao().runsOfCell(cellId).map { row ->
                    DocumentRun(
                        segmentId = row.segmentId,
                        taskId = row.taskId,
                        text = if (row.kind == SegmentKind.TASK) row.taskName.orEmpty() else row.text.orEmpty(),
                        start = at,
                    ).also { at = it.end }
                }
            assertEquals("Kutu ölçüsü 30×30 Kırmızı ev Mavi ev", runs.documentText())
            // The two tasks are two runs with writing between them, which is what
            // keeps them two things to everything downstream.
            assertEquals(2, runs.count { it.isTask })
            assertTrue(runs.zipWithNext().none { (left, right) -> left.isTask && right.isTask })
        }

    @Test
    fun `the separators are still there when the database is opened again`() =
        runBlocking<Unit> {
            given()
            draft("Kırmızı ev")
            draft("Mavi ev")
            confirm()
            val before = segments()

            database.close()
            database = DatabaseFactory().open(directory.databaseFile)

            assertEquals(before, segments())
            assertEquals("Kırmızı ev Mavi ev", documentOf())
        }

    @Test
    fun `a second confirmation is refused and adds no space of its own`() =
        runBlocking<Unit> {
            given()
            draft("Kırmızı ev")
            draft("Mavi ev")
            confirm()
            val before = segments()

            val failure = assertFailsWith<ImportConfirmationException> { confirm() }

            assertEquals(ImportConfirmationFailure.ALREADY_CONFIRMED, failure.failure)
            assertEquals(before, segments())
            assertEquals("Kırmızı ev Mavi ev", documentOf())
        }
}
