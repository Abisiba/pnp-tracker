package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.deletedAt
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.insertSegmentDirectly
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning words in a cell into a task, against a real database.
 *
 * The invariant behind almost all of these is one sentence: the cell says the
 * same thing afterwards as it did before. Its shape changes — one piece of text
 * becomes text, a task and text — but put back together the document is the same
 * string, and PLAN 5.5 and 16 are what that is checked against.
 */
class TaskFromTextStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: TaskFromTextStore
    private var realDatabaseExistedBefore = false

    /** Hands out identities, and can be told to give up after a few. */
    private class LimitedIds(
        private val limit: Int = Int.MAX_VALUE,
    ) : IdGenerator {
        var reads: Int = 0
            private set

        override fun newId(): EntityId {
            reads++
            check(reads <= limit) { "no more identities" }
            return IdGenerator.Random.newId()
        }
    }

    private lateinit var ids: LimitedIds

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        ids = LimitedIds()
        store = TaskFromTextStore(database.taskFromTextDao(), idGenerator = ids, clock = StoppedClock(updatedAt))
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    // ------------------------------------------------------------- fixtures

    private suspend fun addGame(
        name: String = "Harmonies",
        isCompleted: Boolean = false,
        deleted: Boolean = false,
    ): GameEntity {
        val game =
            aGame(name = name).copy(
                isManuallyCompleted = isCompleted,
                completedAt = createdAt.takeIf { isCompleted },
                deletedAt = deletedAt.takeIf { deleted },
            )
        database.gameDao().insert(game)
        return game
    }

    private suspend fun addCell(
        gameId: EntityId,
        columnType: CellColumnType = CellColumnType.THREE_D,
    ): GameCellEntity {
        val cell = aCell(gameId = gameId, columnType = columnType)
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

    /** A colour out of the catalogue the database is created with. */
    private suspend fun addColor(name: String = "Siyah"): ColorEntity =
        assertNotNull(
            database.colorDao().allColors().firstOrNull { it.canonicalName == name },
            "the catalogue has no colour called $name",
        )

    private fun selectionOf(
        game: GameEntity,
        cell: GameCellEntity,
        segment: CellSegmentEntity,
        word: String,
        text: String = segment.text.orEmpty(),
    ) = CellTextSelection(
        gameId = game.id,
        cellId = cell.id,
        segmentId = segment.id,
        expectedText = text,
        startOffset = text.indexOf(word),
        endOffset = text.indexOf(word) + word.length,
    )

    /** What the cell reads as: its pieces in order, joined by nothing at all. */
    private suspend fun documentTextOf(cellId: EntityId): String {
        val pieces =
            database.cellSegmentDao().segmentsOfCell(cellId).map { piece ->
                piece.text ?: database.taskDao().taskByIdIncludingDeleted(piece.taskId!!)!!.name
            }
        return pieces.joinToString(separator = "")
    }

    private suspend fun kindsOf(cellId: EntityId): List<SegmentKind> = database.cellSegmentDao().segmentsOfCell(cellId).map { it.kind }

    private suspend fun textsOf(cellId: EntityId): List<String?> = database.cellSegmentDao().segmentsOfCell(cellId).map { it.text }

    private suspend fun ordersOf(cellId: EntityId): List<Int> = database.cellSegmentDao().segmentsOfCell(cellId).map { it.orderIndex }

    private suspend fun create(
        selection: CellTextSelection,
        colorId: EntityId,
        requiredQuantity: Int = 15,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        notes: String? = null,
    ) = store.createSingleColorTask(
        selection = selection,
        colorId = colorId,
        requiredQuantity = requiredQuantity,
        trackingMode = trackingMode,
        notes = notes,
    )

    // ------------------------------------------------ cutting a word out

    @Test
    fun `a word in the middle becomes text, a task and text`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak: Knight, token ×14"
            val segment = addText(cell.id, text)
            val color = addColor()

            val taskId = create(selectionOf(game, cell, segment, "Knight"), color.id)

            assertEquals(
                listOf(SegmentKind.PLAIN_TEXT, SegmentKind.TASK, SegmentKind.PLAIN_TEXT),
                kindsOf(cell.id),
            )
            assertEquals(listOf("Basılacak: ", null, ", token ×14"), textsOf(cell.id))
            assertEquals("Knight", database.taskDao().activeTaskById(taskId)?.name)
            assertEquals(text, documentTextOf(cell.id), "the cell no longer reads as it was written")
        }

    @Test
    fun `a word at the start writes no empty piece before it`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Knight, token"
            val segment = addText(cell.id, text)
            val color = addColor()

            create(selectionOf(game, cell, segment, "Knight"), color.id)

            assertEquals(listOf(SegmentKind.TASK, SegmentKind.PLAIN_TEXT), kindsOf(cell.id))
            assertEquals(text, documentTextOf(cell.id))
        }

    @Test
    fun `a word at the end writes no empty piece after it`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "40 gri token"
            val segment = addText(cell.id, text)
            val color = addColor()

            create(selectionOf(game, cell, segment, "token"), color.id)

            assertEquals(listOf(SegmentKind.PLAIN_TEXT, SegmentKind.TASK), kindsOf(cell.id))
            assertEquals(text, documentTextOf(cell.id))
        }

    @Test
    fun `selecting the whole piece leaves the task alone in the cell`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "Knight")
            val color = addColor()

            create(selectionOf(game, cell, segment, "Knight"), color.id)

            assertEquals(listOf(SegmentKind.TASK), kindsOf(cell.id))
            assertEquals("Knight", documentTextOf(cell.id))
            assertNull(
                database.cellSegmentDao().segmentsOfCell(cell.id).firstOrNull { it.id == segment.id },
                "the emptied text row was left behind",
            )
        }

    @Test
    fun `whitespace at the edges of the selection stays in the document`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "40   token   ×14"
            val segment = addText(cell.id, text)
            val color = addColor()

            val taskId =
                create(
                    selectionOf(game, cell, segment, "", text).copy(
                        startOffset = 2,
                        endOffset = text.indexOf("×"),
                    ),
                    color.id,
                )

            assertEquals("token", database.taskDao().activeTaskById(taskId)?.name)
            assertEquals(listOf("40   ", null, "   ×14"), textsOf(cell.id))
            assertEquals(text, documentTextOf(cell.id))
        }

    @Test
    fun `punctuation and line endings survive the cut exactly`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak:\r\n  40 gri token;\n  26 ağaç."
            val segment = addText(cell.id, text)
            val color = addColor()

            create(selectionOf(game, cell, segment, "token"), color.id)

            assertEquals(text, documentTextOf(cell.id))
            assertEquals(listOf("Basılacak:\r\n  40 gri ", null, ";\n  26 ağaç."), textsOf(cell.id))
        }

    @Test
    fun `the pieces are left numbered from zero with no gaps`() =
        runBlocking<Unit> {
            // Two cuts, so the second one has to move pieces that already sit
            // after it rather than simply appending. The unique index on
            // (cell, position) is what would break if they were renumbered in
            // place, which is why they are lifted out of the way first.
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Knight, token ×14"
            val first = addText(cell.id, text)
            val color = addColor()

            create(selectionOf(game, cell, first, "Knight"), color.id)
            val remaining = database.cellSegmentDao().segmentsOfCell(cell.id).last()
            create(
                CellTextSelection(
                    gameId = game.id,
                    cellId = cell.id,
                    segmentId = remaining.id,
                    expectedText = ", token ×14",
                    startOffset = ", ".length,
                    endOffset = ", token".length,
                ),
                color.id,
            )

            assertEquals(
                listOf(SegmentKind.TASK, SegmentKind.PLAIN_TEXT, SegmentKind.TASK, SegmentKind.PLAIN_TEXT),
                kindsOf(cell.id),
            )
            assertEquals(listOf(0, 1, 2, 3), ordersOf(cell.id))
            assertEquals(text, documentTextOf(cell.id), "two cuts changed what the cell says")
        }

    @Test
    fun `neighbouring stretches of text are left as one piece`() =
        runBlocking<Unit> {
            // A cell can arrive with several adjacent pieces from an import. PLAN
            // 5.5 and 16 both say a cell never accumulates needless pieces, so it
            // leaves this transaction in its canonical shape.
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Baskı: ", orderIndex = 0)
            addText(cell.id, "40 ", orderIndex = 1)
            val segment = addText(cell.id, "gri token", orderIndex = 2)
            val color = addColor()

            create(selectionOf(game, cell, segment, "token"), color.id)

            assertEquals(listOf(SegmentKind.PLAIN_TEXT, SegmentKind.TASK), kindsOf(cell.id))
            assertEquals(listOf("Baskı: 40 gri ", null), textsOf(cell.id))
            assertEquals("Baskı: 40 gri token", documentTextOf(cell.id))
        }

    // ----------------------------------------------- what the task comes out as

    @Test
    fun `the task carries the colour, the count and the note that were chosen`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "40 gri token")
            val color = addColor(name = "Gri")

            val taskId =
                create(
                    selectionOf(game, cell, segment, "token"),
                    color.id,
                    requiredQuantity = 14,
                    notes = "  ikisi yedek  ",
                )

            val task = assertNotNull(database.taskDao().activeTaskById(taskId))
            assertEquals("token", task.name)
            assertEquals(14, task.requiredQuantity)
            // Stored as typed: PLAN 5.6 keeps a note the user's own words.
            assertEquals("  ikisi yedek  ", task.notes)
            assertEquals(PoolType.THREE_D, task.poolType)
            assertEquals(TrackingMode.THREE_D_BATCH, task.trackingMode)
            assertEquals(false, task.isCompleted)
            assertNull(task.completedAt)
            assertEquals(false, task.primaryBatchCompleted)
            assertEquals(0, task.currentMissingQuantity)
            assertNull(task.deletedAt)
            // Chosen out of the user's own words, so nothing imported is behind it.
            assertNull(task.sourceRawImportBlockId)
        }

    @Test
    fun `one colour is written in the first slot`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "40 gri token")
            val color = addColor()

            val taskId = create(selectionOf(game, cell, segment, "token"), color.id)

            val colors = database.taskColorDao().colorsOfTask(taskId)
            assertEquals(1, colors.size)
            assertEquals(color.id, colors.single().colorId)
            assertEquals(0, colors.single().slotIndex)
        }

    @Test
    fun `a card task gets its whole pipeline in the same write`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id, columnType = CellColumnType.CARD)
            val segment = addText(cell.id, "60 kart")
            val color = addColor()

            val taskId =
                create(
                    selectionOf(game, cell, segment, "kart"),
                    color.id,
                    trackingMode = TrackingMode.PIPELINE,
                )

            val stages = database.taskProgressDao().stagesOfTask(taskId)
            assertEquals(
                listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT),
                stages.map { it.stage },
            )
            assertEquals(listOf(0, 0, 0), stages.map { it.completedQuantity })
            assertEquals(listOf(0, 1, 2), stages.map { it.orderIndex })
        }

    @Test
    fun `a three dimensional or special task gets no stage rows at all`() =
        runBlocking<Unit> {
            val game = addGame()
            val color = addColor()

            val threeDCell = addCell(game.id, columnType = CellColumnType.THREE_D)
            val threeD = addText(threeDCell.id, "40 gri token")
            val threeDTask = create(selectionOf(game, threeDCell, threeD, "token"), color.id)

            val specialCell = addCell(game.id, columnType = CellColumnType.SPECIAL)
            val special = addText(specialCell.id, "kutu bandı")
            val specialTask =
                create(
                    selectionOf(game, specialCell, special, "bandı"),
                    color.id,
                    trackingMode = TrackingMode.COUNTED,
                )

            assertTrue(database.taskProgressDao().stagesOfTask(threeDTask).isEmpty())
            assertTrue(database.taskProgressDao().stagesOfTask(specialTask).isEmpty())
        }

    @Test
    fun `the count is metadata and is never written into the cell's text`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "40 gri token"
            val segment = addText(cell.id, text)
            val color = addColor()

            create(selectionOf(game, cell, segment, "token"), color.id, requiredQuantity = 150)

            assertEquals(text, documentTextOf(cell.id))
            textsOf(cell.id).filterNotNull().forEach { piece ->
                assertTrue("×" !in piece, "the count was written into the document: $piece")
                assertTrue("150" !in piece, "the count was written into the document: $piece")
            }
        }

    @Test
    fun `a task can be made in a game the user has finished, and the game stays finished`() =
        runBlocking<Unit> {
            // PLAN 5.3 keeps a finished game editable. Reopening it is a decision
            // of its own and belongs to the transaction that owns the game's state.
            val game = addGame(isCompleted = true)
            val cell = addCell(game.id)
            val segment = addText(cell.id, "40 gri token")
            val color = addColor()

            create(selectionOf(game, cell, segment, "token"), color.id)

            val after = assertNotNull(database.gameDao().activeGameById(game.id))
            assertTrue(after.isManuallyCompleted, "creating a task reopened the game")
            assertEquals(game.completedAt, after.completedAt)
        }

    @Test
    fun `the task and its piece of the cell are stamped with one moment`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "40 gri token")
            val color = addColor()

            val taskId = create(selectionOf(game, cell, segment, "token"), color.id)

            val task = assertNotNull(database.taskDao().activeTaskById(taskId))
            val piece = assertNotNull(database.cellSegmentDao().activeSegmentOfTask(taskId))
            assertEquals(updatedAt, task.createdAt)
            assertEquals(updatedAt, task.updatedAt)
            assertEquals(updatedAt, piece.createdAt)
            assertEquals(updatedAt, database.gameCellDao().cellById(cell.id)?.updatedAt)
        }

    // ------------------------------------------------------- what is refused

    private suspend fun assertRefuses(
        expected: TaskFromTextFailure,
        cellId: EntityId,
        documentBefore: String,
        block: suspend () -> Unit,
    ) {
        val failure = assertFailsWith<TaskFromTextException> { block() }.failure

        assertEquals(expected, failure)
        assertEquals(documentBefore, documentTextOf(cellId), "the text was changed by a refused attempt")
        assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty(), "a task was left behind")
        assertTrue(kindsOf(cellId).none { it == SegmentKind.TASK }, "a task piece was left behind")
    }

    @Test
    fun `a deleted game refuses the whole thing`() =
        runBlocking<Unit> {
            val game = addGame(deleted = true)
            val cell = addCell(game.id)
            val segment = addText(cell.id, "40 gri token")
            val color = addColor()

            assertRefuses(TaskFromTextFailure.GAME_NOT_AVAILABLE, cell.id, "40 gri token") {
                create(selectionOf(game, cell, segment, "token"), color.id)
            }
        }

    @Test
    fun `a cell that is not there refuses the whole thing`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "40 gri token")
            val color = addColor()
            val absent = cell.copy(id = IdGenerator.Random.newId())

            assertRefuses(TaskFromTextFailure.CELL_NOT_AVAILABLE, cell.id, "40 gri token") {
                create(selectionOf(game, absent, segment, "token"), color.id)
            }
        }

    @Test
    fun `the notes column refuses a task`() =
        runBlocking<Unit> {
            // PLAN 5.4 keeps tasks out of the notes column entirely.
            val game = addGame()
            val cell = addCell(game.id, columnType = CellColumnType.NOTES)
            val segment = addText(cell.id, "kutu 30x30")
            val color = addColor()

            assertRefuses(TaskFromTextFailure.CELL_DOES_NOT_HOLD_TASKS, cell.id, "kutu 30x30") {
                create(selectionOf(game, cell, segment, "kutu"), color.id)
            }
        }

    @Test
    fun `a piece that is no longer there refuses the whole thing`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "40 gri token")
            val color = addColor()
            val absent = segment.copy(id = IdGenerator.Random.newId())

            assertRefuses(TaskFromTextFailure.SEGMENT_NOT_AVAILABLE, cell.id, "40 gri token") {
                create(selectionOf(game, cell, absent, "token"), color.id)
            }
        }

    @Test
    fun `a piece that is already a task is not cut again`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "40 gri token")
            val color = addColor()
            create(selectionOf(game, cell, segment, "token"), color.id)
            val taskPiece = database.cellSegmentDao().segmentsOfCell(cell.id).first { it.kind == SegmentKind.TASK }

            val failure =
                assertFailsWith<TaskFromTextException> {
                    create(
                        CellTextSelection(
                            gameId = game.id,
                            cellId = cell.id,
                            segmentId = taskPiece.id,
                            expectedText = "token",
                            startOffset = 0,
                            endOffset = 5,
                        ),
                        color.id,
                    )
                }.failure

            assertEquals(TaskFromTextFailure.SEGMENT_IS_NOT_PLAIN_TEXT, failure)
            assertEquals("40 gri token", documentTextOf(cell.id))
            assertEquals(1, database.taskDao().allTasksIncludingDeleted().size)
        }

    @Test
    fun `text that has changed since the selection was made refuses the cut`() =
        runBlocking<Unit> {
            // The offsets would land somewhere the user never pointed at.
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "40 gri token")
            val color = addColor()

            assertRefuses(TaskFromTextFailure.STALE_TEXT_SELECTION, cell.id, "40 gri token") {
                create(
                    selectionOf(game, cell, segment, "token").copy(expectedText = "40 gri ağaç"),
                    color.id,
                )
            }
        }

    @Test
    fun `a colour that has been deleted refuses the whole thing`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "40 gri token")

            assertRefuses(TaskFromTextFailure.COLOR_NOT_AVAILABLE, cell.id, "40 gri token") {
                create(selectionOf(game, cell, segment, "token"), IdGenerator.Random.newId())
            }
        }

    @Test
    fun `a quantity that is not greater than zero refuses the whole thing`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "40 gri token")
            val color = addColor()

            listOf(0, -3).forEach { quantity ->
                assertRefuses(TaskFromTextFailure.INVALID_REQUIRED_QUANTITY, cell.id, "40 gri token") {
                    create(selectionOf(game, cell, segment, "token"), color.id, requiredQuantity = quantity)
                }
            }
        }

    @Test
    fun `a selection running across a line ending refuses the whole thing`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "40 gri token\n26 kahverengi ağaç"
            val segment = addText(cell.id, text)
            val color = addColor()

            assertRefuses(TaskFromTextFailure.SELECTION_CONTAINS_LINE_BREAK, cell.id, text) {
                create(
                    selectionOf(game, cell, segment, "", text).copy(startOffset = 3, endOffset = text.length),
                    color.id,
                )
            }
        }

    @Test
    fun `a selection of nothing but whitespace refuses the whole thing`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "40   token"
            val segment = addText(cell.id, text)
            val color = addColor()

            assertRefuses(TaskFromTextFailure.TASK_NAME_EMPTY, cell.id, text) {
                create(
                    selectionOf(game, cell, segment, "", text).copy(startOffset = 2, endOffset = 5),
                    color.id,
                )
            }
        }

    // ------------------------------------------------------------- rollback

    @Test
    fun `an attempt that runs out of identities leaves nothing behind`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak: Knight, token ×14"
            val segment = addText(cell.id, text)
            val color = addColor()
            // Enough for the task itself, and not enough for the pieces after it.
            val limited = TaskFromTextStore(database.taskFromTextDao(), LimitedIds(limit = 1), StoppedClock(updatedAt))

            assertFailsWith<IllegalStateException> {
                limited.createSingleColorTask(
                    selection = selectionOf(game, cell, segment, "Knight"),
                    colorId = color.id,
                    requiredQuantity = 15,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    notes = null,
                )
            }

            assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty(), "a task survived the rollback")
            assertEquals(text, documentTextOf(cell.id), "the text did not come back")
            assertEquals(listOf(SegmentKind.PLAIN_TEXT), kindsOf(cell.id))
            assertEquals(listOf(0), ordersOf(cell.id))
        }

    @Test
    fun `no task, colour or stage is left behind when a card task is refused`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id, columnType = CellColumnType.CARD)
            val segment = addText(cell.id, "60 kart")

            assertFailsWith<TaskFromTextException> {
                create(
                    selectionOf(game, cell, segment, "kart"),
                    IdGenerator.Random.newId(),
                    trackingMode = TrackingMode.PIPELINE,
                )
            }

            assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty())
            assertTrue(database.colorDao().colorsOfTask(IdGenerator.Random.newId()).isEmpty())
            assertEquals("60 kart", documentTextOf(cell.id))
        }
}
