package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.insertSegmentDirectly
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Writing in a cell that has tasks in it.
 *
 * The whole-cell lock of the previous step is gone: the plain text around a task
 * is the user's to edit, and the task is protected by the change itself being
 * refused rather than by the cell being closed. What every one of these checks in
 * the end is the same sentence — the tasks came through with their identities,
 * and the document says what it should to the character.
 */
class CellDocumentStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: CellTextStore
    private lateinit var creation: TaskFromTextStore
    private var realDatabaseExistedBefore = false

    /** Hands out identities, and can be told to give up after a few. */
    private class LimitedIds(
        private val limit: Int = Int.MAX_VALUE,
    ) : IdGenerator {
        private var reads = 0

        override fun newId(): EntityId {
            reads++
            check(reads <= limit) { "no more identities" }
            return IdGenerator.Random.newId()
        }
    }

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = CellTextStore(database.cellSegmentDao(), IdGenerator.Random, StoppedClock(updatedAt))
        creation = TaskFromTextStore(database.taskFromTextDao(), IdGenerator.Random, StoppedClock(updatedAt))
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun addGame(): GameEntity {
        val game = aGame(name = "Harmonies")
        database.gameDao().insert(game)
        return game
    }

    private suspend fun addCell(gameId: EntityId): GameCellEntity {
        val cell = aCell(gameId = gameId, columnType = CellColumnType.THREE_D)
        database.gameCellDao().insert(cell)
        return cell
    }

    private suspend fun addText(
        cellId: EntityId,
        text: String,
        orderIndex: Int = 0,
    ): CellSegmentEntity {
        val segment =
            CellSegmentEntity.plainText(
                id = IdGenerator.Random.newId(),
                cellId = cellId,
                orderIndex = orderIndex,
                text = text,
                moment = createdAt,
            )
        insertSegmentDirectly(database, segment)
        return segment
    }

    private suspend fun colorNamed(name: String) =
        assertNotNull(database.colorDao().allColors().firstOrNull { it.canonicalName == name }).id

    /** Cuts [word] out of the stretch of text holding it and makes it a task. */
    private suspend fun makeTask(
        game: GameEntity,
        cell: GameCellEntity,
        word: String,
        color: String = "Siyah",
    ): EntityId {
        val segment =
            database
                .cellSegmentDao()
                .segmentsOfCell(cell.id)
                .first { it.kind == SegmentKind.PLAIN_TEXT && it.text!!.contains(word) }
        val text = segment.text!!
        return creation.createSingleColorTask(
            selection =
                CellTextSelection(
                    gameId = game.id,
                    cellId = cell.id,
                    segmentId = segment.id,
                    expectedText = text,
                    startOffset = text.indexOf(word),
                    endOffset = text.indexOf(word) + word.length,
                ),
            colorId = colorNamed(color),
            requiredQuantity = 15,
            trackingMode = TrackingMode.THREE_D_BATCH,
            notes = null,
        )
    }

    private suspend fun documentTextOf(cellId: EntityId): String {
        val pieces =
            database.cellSegmentDao().runsOfCell(cellId).map { run ->
                if (run.kind == SegmentKind.TASK) run.taskName.orEmpty() else run.text.orEmpty()
            }
        return pieces.joinToString(separator = "")
    }

    private suspend fun kindsOf(cellId: EntityId) = database.cellSegmentDao().segmentsOfCell(cellId).map { it.kind }

    private suspend fun textsOf(cellId: EntityId) = database.cellSegmentDao().segmentsOfCell(cellId).map { it.text }

    private suspend fun ordersOf(cellId: EntityId) = database.cellSegmentDao().segmentsOfCell(cellId).map { it.orderIndex }

    private suspend fun write(
        game: GameEntity,
        cell: GameCellEntity,
        newText: String,
    ) = store.saveDocumentText(game.id, CellColumnType.THREE_D, documentTextOf(cell.id), newText)

    // -------------------------------------------- editing around the tasks

    @Test
    fun `the text before a task can be edited`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak: Knight, token")
            val taskId = makeTask(game, cell, "Knight")

            assertTrue(write(game, cell, "Kesilecek ve basılacak: Knight, token"))

            assertEquals("Kesilecek ve basılacak: Knight, token", documentTextOf(cell.id))
            assertEquals(listOf("Kesilecek ve basılacak: ", null, ", token"), textsOf(cell.id))
            assertNotNull(database.taskDao().activeTaskById(taskId), "the task did not survive an edit beside it")
        }

    @Test
    fun `the text after a task can be edited`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak: Knight, token")
            makeTask(game, cell, "Knight")

            assertTrue(write(game, cell, "Basılacak: Knight, token ve kutu bandı"))

            assertEquals("Basılacak: Knight, token ve kutu bandı", documentTextOf(cell.id))
            assertEquals(listOf("Basılacak: ", null, ", token ve kutu bandı"), textsOf(cell.id))
        }

    @Test
    fun `text can be added after a task that ends the cell`() =
        runBlocking<Unit> {
            // There is no piece there yet, and there does not need to be: the gap
            // after the last task is where those words belong.
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak: Knight")
            makeTask(game, cell, "Knight")
            assertEquals(listOf(SegmentKind.PLAIN_TEXT, SegmentKind.TASK), kindsOf(cell.id))

            assertTrue(write(game, cell, "Basılacak: Knight ve token"))

            assertEquals("Basılacak: Knight ve token", documentTextOf(cell.id))
            assertEquals(listOf(SegmentKind.PLAIN_TEXT, SegmentKind.TASK, SegmentKind.PLAIN_TEXT), kindsOf(cell.id))
            assertEquals(listOf(0, 1, 2), ordersOf(cell.id))
        }

    @Test
    fun `a change that reaches into a task is refused and changes nothing`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak: Knight, token")
            val taskId = makeTask(game, cell, "Knight")
            val before = assertNotNull(database.taskDao().activeTaskById(taskId))
            val colorsBefore = database.taskColorDao().colorsOfTask(taskId)

            val refusal =
                assertFailsWith<CellTextException> { write(game, cell, "Basılacak: Knigh, token") }

            assertEquals(CellTextFailure.CHANGE_CROSSES_A_TASK, refusal.failure)
            assertEquals("Basılacak: Knight, token", documentTextOf(cell.id))
            assertEquals(before, database.taskDao().activeTaskById(taskId))
            assertEquals(colorsBefore, database.taskColorDao().colorsOfTask(taskId))
        }

    @Test
    fun `emptying a stretch of text leaves no piece behind and closes the order up`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak: Knight, token")
            makeTask(game, cell, "Knight")

            assertTrue(write(game, cell, "Knight, token"))

            assertEquals(listOf(SegmentKind.TASK, SegmentKind.PLAIN_TEXT), kindsOf(cell.id))
            assertEquals(listOf(0, 1), ordersOf(cell.id))
            assertEquals("Knight, token", documentTextOf(cell.id))
        }

    @Test
    fun `neighbouring stretches of text are left as one piece`() =
        runBlocking<Unit> {
            // A cell can arrive with several adjacent pieces from an import;
            // PLAN 5.5 and 16 both say it never keeps needless ones.
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak: ", orderIndex = 0)
            addText(cell.id, "40 gri ", orderIndex = 1)
            addText(cell.id, "token", orderIndex = 2)

            assertTrue(write(game, cell, "Basılacak: 40 gri tokenlar"))

            assertEquals(listOf("Basılacak: 40 gri tokenlar"), textsOf(cell.id))
            assertEquals(listOf(0), ordersOf(cell.id))
        }

    @Test
    fun `text is stored exactly as it was typed`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak: Knight")
            makeTask(game, cell, "Knight")
            val exact = "  Basılacak:\r\n  40  gri;\n\n"

            assertTrue(write(game, cell, exact + "Knight"))

            assertEquals(exact + "Knight", documentTextOf(cell.id))
            assertEquals(exact, textsOf(cell.id).first())
        }

    // ------------------------------------------------- several tasks in one cell

    @Test
    fun `a second and a third task can be made in the same cell`() =
        runBlocking<Unit> {
            // PLAN 12.7's independence, seen from the cell: three tasks, no
            // shared identity, no shared counter, and the words unchanged.
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak: Knight, token ve ağaç."
            addText(cell.id, text)

            val knight = makeTask(game, cell, "Knight", color = "Siyah")
            val token = makeTask(game, cell, "token", color = "Sarı")
            val agac = makeTask(game, cell, "ağaç", color = "Yeşil")

            assertEquals(text, documentTextOf(cell.id), "three cuts changed what the cell says")
            assertEquals(3, database.taskDao().tasksOfCellIncludingCompleted(cell.id).size)
            assertEquals(setOf(knight, token, agac).size, 3)
            assertEquals(
                listOf("Siyah", "Sarı", "Yeşil"),
                listOf(knight, token, agac).map { taskId ->
                    val colorId =
                        database
                            .taskColorDao()
                            .colorsOfTask(taskId)
                            .single()
                            .colorId
                    assertNotNull(database.colorDao().colorById(colorId)).canonicalName
                },
            )
            assertEquals(
                listOf(
                    SegmentKind.PLAIN_TEXT,
                    SegmentKind.TASK,
                    SegmentKind.PLAIN_TEXT,
                    SegmentKind.TASK,
                    SegmentKind.PLAIN_TEXT,
                    SegmentKind.TASK,
                    SegmentKind.PLAIN_TEXT,
                ),
                kindsOf(cell.id),
            )
            assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), ordersOf(cell.id))
        }

    @Test
    fun `tasks in one cell share no identity and no counter`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Knight ve token")
            val knight = makeTask(game, cell, "Knight")
            val token = makeTask(game, cell, "token", color = "Sarı")

            val first = assertNotNull(database.taskDao().activeTaskById(knight))
            val second = assertNotNull(database.taskDao().activeTaskById(token))
            assertTrue(first.id != second.id)
            assertEquals(
                emptyList(),
                database
                    .taskColorDao()
                    .colorsOfTask(knight)
                    .map { it.colorId }
                    .intersect(
                        database
                            .taskColorDao()
                            .colorsOfTask(token)
                            .map { it.colorId }
                            .toSet(),
                    ).toList(),
            )
        }

    @Test
    fun `text between two tasks can still be edited`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Knight ve token")
            makeTask(game, cell, "Knight")
            makeTask(game, cell, "token", color = "Sarı")

            assertTrue(write(game, cell, "Knight ile token"))

            assertEquals("Knight ile token", documentTextOf(cell.id))
            assertEquals(listOf(SegmentKind.TASK, SegmentKind.PLAIN_TEXT, SegmentKind.TASK), kindsOf(cell.id))
        }

    // -------------------------------------------------------- what is refused

    @Test
    fun `a document that changed underneath the editor is not overwritten`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "40 gri token")

            val refusal =
                assertFailsWith<CellTextException> {
                    store.saveDocumentText(game.id, CellColumnType.THREE_D, "başka bir şey", "48 gri token")
                }

            assertEquals(CellTextFailure.STALE_DOCUMENT, refusal.failure)
            assertEquals("40 gri token", documentTextOf(cell.id))
        }

    @Test
    fun `a cell that never existed cannot be claimed to have said something`() =
        runBlocking<Unit> {
            val game = addGame()

            val refusal =
                assertFailsWith<CellTextException> {
                    store.saveDocumentText(game.id, CellColumnType.THREE_D, "eskiden bir şey", "yeni metin")
                }

            assertEquals(CellTextFailure.STALE_DOCUMENT, refusal.failure)
        }

    @Test
    fun `a write that runs out of identities leaves the document exactly as it was`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak: Knight")
            makeTask(game, cell, "Knight")
            val before = documentTextOf(cell.id)
            val piecesBefore = database.cellSegmentDao().segmentsOfCell(cell.id)
            // Enough for nothing: the new stretch of text after the task needs one.
            val breaking = CellTextStore(database.cellSegmentDao(), LimitedIds(limit = 0), StoppedClock(updatedAt))

            assertFailsWith<IllegalStateException> {
                breaking.saveDocumentText(game.id, CellColumnType.THREE_D, before, "$before ve token")
            }

            assertEquals(before, documentTextOf(cell.id))
            assertEquals(piecesBefore, database.cellSegmentDao().segmentsOfCell(cell.id))
        }

    @Test
    fun `saving what the cell already says does nothing at all`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak: Knight")
            makeTask(game, cell, "Knight")
            val before = database.cellSegmentDao().segmentsOfCell(cell.id)
            val cellBefore = assertNotNull(database.gameCellDao().cellById(cell.id))

            assertEquals(false, write(game, cell, documentTextOf(cell.id)))

            assertEquals(before, database.cellSegmentDao().segmentsOfCell(cell.id))
            assertEquals(cellBefore, database.gameCellDao().cellById(cell.id))
        }
}
