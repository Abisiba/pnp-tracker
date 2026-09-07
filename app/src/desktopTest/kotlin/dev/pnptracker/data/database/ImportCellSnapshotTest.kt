package dev.pnptracker.data.database

import dev.pnptracker.domain.games.TASK_SEPARATOR
import dev.pnptracker.domain.games.taskNeedsSeparatorAfter
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
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
 * What a confirmation keeps of the cells it is about to write into.
 *
 * PLAN 11.4.4 will have a rollback put a cell back to the words it held before
 * the import, and refuses to work that out afterwards from what is left: the
 * user may have written in the cell since, and subtracting task names from
 * today's document cannot tell their writing from the import's. So the
 * confirmation records the document it actually read.
 *
 * These ask two different things. The narrow one is that the record is there,
 * once per cell, saying what the cell said — every character of it, in Turkish,
 * across line breaks, with the spaces at either end that a `trim` would eat.
 *
 * The wider one is the property the rollback will stand on: **what is stored,
 * plus the names this import wrote, is exactly what the cell reads now.** That
 * is checked here with the same `taskNeedsSeparatorAfter` the confirmation used,
 * because the alternative — storing the "after" text too — would be two accounts
 * of one thing, free to drift.
 */
class ImportCellSnapshotTest {
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

    // ------------------------------------------------------------------ fixtures

    private var batchId: EntityId = IdGenerator.Random.newId()
    private var rows = 0
    private val cells = mutableMapOf<String, EntityId>()
    private val games = mutableMapOf<String, EntityId>()

    /** One import to confirm, with no games yet. */
    private suspend fun given() {
        val batch = anImportBatch(rawBlockCount = 0)
        importDao.insertBatch(batch)
        batchId = batch.id
        rows = 0
        cells.clear()
        games.clear()
    }

    /** A game with one 3D cell in it, named so the tests can talk about it. */
    private suspend fun aCellCalled(name: String): EntityId {
        val game = aGame(name = name)
        val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        games[name] = game.id
        cells[name] = cell.id
        return cell.id
    }

    /** Whatever the user had already written in that cell, through the real editor. */
    private suspend fun cellAlreadySays(
        name: String,
        text: String,
    ) {
        database.cellSegmentDao().saveDocumentText(
            assertNotNull(games[name]),
            CellColumnType.THREE_D,
            "",
            text,
            StoppedClock(updatedAt),
            IdGenerator.Random,
        )
    }

    /** One draft of this import, aimed at a named cell. */
    private suspend fun draft(
        taskName: String,
        cell: String,
    ): EntityId {
        rows++
        val block =
            aRawImportBlock(
                batchId,
                rowIndex = rows,
                columnIndex = 1,
                rawText = "15 KIRMIZI $taskName",
                sourceColumnType = SourceColumnType.THREE_D,
            )
        importDao.insertRawBlock(block)
        importDao.setRawBlockProcessed(block.id, true, updatedAt)
        // A moment of its own for each draft, so the order they are written into
        // the cell in is the order the user made them in rather than the order
        // their identifiers happen to sort in.
        val draft =
            aDraftTask(block.id, name = taskName).copy(
                requiredQuantity = 20,
                createdAt = createdAt + rows.seconds,
            )
        importDao.addDraftTask(draft)
        importDao.setDraftTargetUnderReview(
            draft.id,
            assertNotNull(cells[cell]),
            PoolType.THREE_D,
            TrackingMode.THREE_D_BATCH,
            updatedAt,
        )
        return draft.id
    }

    private suspend fun confirm(): Int =
        importDao.confirmDraftBatch(
            batchId,
            acknowledgeUnprocessedBlocks = true,
            clock = StoppedClock(moment),
            idGenerator = IdGenerator.Random,
        )

    /** What was kept, as `cell name to document`, so a test never quotes an identifier. */
    private suspend fun kept(): Map<String, String> {
        val byId = cells.entries.associate { (name, id) -> id to name }
        return importDao.cellSnapshotsOfBatch(batchId).associate {
            assertNotNull(byId[it.cellId], "a cell nobody in this test made was recorded") to it.documentBefore
        }
    }

    /** What a cell reads today: its pieces end to end, a task piece read as its name. */
    private suspend fun documentOf(cell: String): String =
        buildString {
            database.cellSegmentDao().segmentsOfCell(assertNotNull(cells[cell])).forEach { piece ->
                append(
                    piece.text
                        ?: assertNotNull(database.taskDao().taskByIdIncludingDeleted(assertNotNull(piece.taskId))).name,
                )
            }
        }

    /** The names this import wrote into a cell, in the order it wrote them. */
    private suspend fun namesWrittenInto(cell: String): List<String> {
        val cellId = assertNotNull(cells[cell])
        val written = importDao.segmentsOfConfirmedBatch(batchId).filter { it.cellId == cellId }.map { it.taskId }
        return database.cellSegmentDao().segmentsOfCell(cellId).filter { it.taskId in written }.map { piece ->
            assertNotNull(database.taskDao().taskByIdIncludingDeleted(assertNotNull(piece.taskId))).name
        }
    }

    /**
     * The document the stored one grows into, worked out the way the import
     * worked it out: each name appended, with a space only where the writing
     * before it did not already end in one.
     */
    private fun grownFrom(
        before: String,
        names: List<String>,
    ): String =
        names.fold(before) { text, name ->
            text + (if (taskNeedsSeparatorAfter(text)) TASK_SEPARATOR else "") + name
        }

    // ------------------------------------------------------------- one cell, once

    @Test
    fun `one task in one cell is one record of that cell`() =
        runBlocking<Unit> {
            given()
            aCellCalled("Harmonies")
            cellAlreadySays("Harmonies", "Kullanıcının notu")
            draft("Kırmızı ev", "Harmonies")

            assertEquals(1, confirm())

            assertEquals(mapOf("Harmonies" to "Kullanıcının notu"), kept())
        }

    @Test
    fun `several tasks aimed at one cell are still one record, of the cell before any of them`() =
        runBlocking<Unit> {
            given()
            aCellCalled("Harmonies")
            cellAlreadySays("Harmonies", "Önceki metin")
            listOf("Kırmızı ev", "Mavi ev", "Yeşil ev").forEach { draft(it, "Harmonies") }

            assertEquals(3, confirm())

            // Not "Önceki metin Kırmızı ev": what is kept is the cell as it was
            // before the import, not as it was before the last of its tasks.
            assertEquals(mapOf("Harmonies" to "Önceki metin"), kept())
            assertEquals("Önceki metin Kırmızı ev Mavi ev Yeşil ev", documentOf("Harmonies"))
        }

    @Test
    fun `an empty cell is recorded as the empty string rather than left out`() =
        runBlocking<Unit> {
            given()
            aCellCalled("Harmonies")
            draft("Kırmızı ev", "Harmonies")

            assertEquals(1, confirm())

            // The difference matters: no row at all is what an older import
            // leaves behind, and PLAN 11.4.4 refuses to roll that back. An empty
            // cell is a cell that was known and was empty.
            assertEquals(mapOf("Harmonies" to ""), kept())
            assertEquals(1, importDao.cellSnapshotsOfBatch(batchId).size)
        }

    @Test
    fun `a batch writing into three cells records each of them once`() =
        runBlocking<Unit> {
            given()
            listOf("Harmonies", "Wingspan", "Splendor").forEach { aCellCalled(it) }
            cellAlreadySays("Harmonies", "İlk")
            cellAlreadySays("Splendor", "Üçüncü ")
            draft("Kırmızı ev", "Harmonies")
            draft("Mavi kuş", "Wingspan")
            draft("Sarı pul", "Splendor")
            draft("Yeşil ev", "Harmonies")

            assertEquals(4, confirm())

            assertEquals(
                mapOf("Harmonies" to "İlk", "Wingspan" to "", "Splendor" to "Üçüncü "),
                kept(),
                "a cell was recorded twice, missed, or given another cell's words",
            )
        }

    // ---------------------------------------------------- character for character

    @Test
    fun `what is kept is the cell character for character`() =
        runBlocking<Unit> {
            given()
            aCellCalled("Harmonies")
            // Turkish letters that change under a careless case fold, a line
            // break, a double space, and whitespace at both ends — every one of
            // them something a trim or a normalise would quietly eat.
            val written = "  Iğdır çöp ŞIŞE\nikinci  satır\tsekme  "
            cellAlreadySays("Harmonies", written)
            draft("Kırmızı ev", "Harmonies")

            assertEquals(1, confirm())

            val stored = assertNotNull(kept()["Harmonies"])
            assertEquals(written, stored)
            assertEquals(written.length, stored.length, "the record is a different length from what was written")
            assertEquals(written.toList(), stored.toList(), "a character came back as a different character")
        }

    @Test
    fun `a cell holding only spaces is kept as those spaces`() =
        runBlocking<Unit> {
            given()
            aCellCalled("Harmonies")
            cellAlreadySays("Harmonies", "   ")
            draft("Kırmızı ev", "Harmonies")

            assertEquals(1, confirm())

            assertEquals(mapOf("Harmonies" to "   "), kept())
            // And the import added no space of its own, because the cell already
            // ended in one — so the record and the document agree about why.
            assertEquals("   Kırmızı ev", documentOf("Harmonies"))
        }

    // ------------------------------------------- the property the rollback stands on

    @Test
    fun `what was kept plus what was written is exactly what the cell reads now`() =
        runBlocking<Unit> {
            given()
            listOf("Harmonies", "Wingspan", "Splendor").forEach { aCellCalled(it) }
            cellAlreadySays("Harmonies", "Zaten yazılmış")
            cellAlreadySays("Wingspan", "Boşlukla biten ")
            listOf("Kırmızı ev", "Mavi ev").forEach { draft(it, "Harmonies") }
            draft("Sarı kuş", "Wingspan")
            listOf("Yeşil pul", "Mor pul", "Turuncu pul").forEach { draft(it, "Splendor") }

            assertEquals(6, confirm())

            val stored = kept()
            listOf("Harmonies", "Wingspan", "Splendor").forEach { cell ->
                assertEquals(
                    documentOf(cell),
                    grownFrom(assertNotNull(stored[cell]), namesWrittenInto(cell)),
                    "the cell $cell cannot be accounted for by what was kept plus what was written",
                )
            }
        }

    @Test
    fun `the record does not change what the import produces`() =
        runBlocking<Unit> {
            given()
            aCellCalled("Harmonies")
            cellAlreadySays("Harmonies", "Not")
            listOf("Kırmızı ev", "Mavi ev").forEach { draft(it, "Harmonies") }

            assertEquals(2, confirm())

            // The tasks, their order, the spaces between them and the batch's own
            // counters are what they were before there was a record at all.
            assertEquals("Not Kırmızı ev Mavi ev", documentOf("Harmonies"))
            assertEquals(listOf("Kırmızı ev", "Mavi ev"), namesWrittenInto("Harmonies"))
            assertEquals(2, importDao.tasksOfConfirmedBatch(batchId).size)
            assertEquals(2, assertNotNull(importDao.batchById(batchId)).createdTaskCount)
            assertTrue(importDao.draftTasksOfBatch(batchId).none { it.materializedTaskId == null })
        }

    // -------------------------------------------------------- the guarded refusal

    @Test
    fun `a second confirmation is refused and adds no second record`() =
        runBlocking<Unit> {
            given()
            aCellCalled("Harmonies")
            cellAlreadySays("Harmonies", "Not")
            draft("Kırmızı ev", "Harmonies")
            assertEquals(1, confirm())
            val once = importDao.cellSnapshotsOfBatch(batchId)

            val refused = assertFailsWith<ImportConfirmationException> { confirm() }

            assertEquals(ImportConfirmationFailure.ALREADY_CONFIRMED, refused.failure)
            assertEquals(once, importDao.cellSnapshotsOfBatch(batchId), "a refused repeat changed what was kept")
            assertEquals(mapOf("Harmonies" to "Not"), kept())
        }

    @Test
    fun `a second import into the same cell keeps its own view of it`() =
        runBlocking<Unit> {
            given()
            aCellCalled("Harmonies")
            cellAlreadySays("Harmonies", "Not")
            draft("Kırmızı ev", "Harmonies")
            assertEquals(1, confirm())
            val firstBatch = batchId
            val cellId = assertNotNull(cells["Harmonies"])

            // A second import, months later, into the cell the first one filled.
            val second = anImportBatch(sha256 = SHA_256_TWO)
            importDao.insertBatch(second)
            batchId = second.id
            rows = 0
            draft("Mavi ev", "Harmonies")

            assertEquals(1, confirm())

            assertEquals(
                "Not",
                assertNotNull(importDao.cellSnapshotsOfBatch(firstBatch).single { it.cellId == cellId }).documentBefore,
                "the first import's record was overwritten by the second",
            )
            assertEquals(
                "Not Kırmızı ev",
                assertNotNull(importDao.cellSnapshotsOfBatch(second.id).single { it.cellId == cellId }).documentBefore,
                "the second import recorded the cell as it was before the first",
            )
        }
}
