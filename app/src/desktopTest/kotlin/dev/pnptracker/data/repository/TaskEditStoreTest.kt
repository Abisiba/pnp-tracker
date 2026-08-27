package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.insertSegmentDirectly
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskEditFailure
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
 * Changing a task that already exists, and turning one back into text.
 *
 * The invariant behind the second half is PLAN 12.8's: the cell says exactly the
 * same thing afterwards as it did before. What changes is only that those words
 * are no longer a piece of work.
 */
class TaskEditStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: TaskEditStore
    private lateinit var creation: TaskFromTextStore
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = TaskEditStore(database.taskEditDao(), IdGenerator.Random, StoppedClock(updatedAt))
        creation = TaskFromTextStore(database.taskFromTextDao(), IdGenerator.Random, StoppedClock(updatedAt))
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    // ------------------------------------------------------------- fixtures

    private suspend fun addGame(name: String = "Harmonies"): GameEntity {
        val game = aGame(name = name)
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

    private suspend fun colorNamed(name: String): ColorEntity =
        assertNotNull(database.colorDao().allColors().firstOrNull { it.canonicalName == name })

    /** Cuts [word] out of the cell's single stretch of text and makes it a task. */
    private suspend fun makeTask(
        game: GameEntity,
        cell: GameCellEntity,
        word: String,
        color: String = "Siyah",
        quantity: Int = 15,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
    ): EntityId {
        val segment =
            database.cellSegmentDao().segmentsOfCell(cell.id).first {
                it.kind == SegmentKind.PLAIN_TEXT &&
                    it.text!!.contains(word)
            }
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
            colorId = colorNamed(color).id,
            requiredQuantity = quantity,
            trackingMode = trackingMode,
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

    private suspend fun ordersOf(cellId: EntityId) = database.cellSegmentDao().segmentsOfCell(cellId).map { it.orderIndex }

    private suspend fun kindsOf(cellId: EntityId) = database.cellSegmentDao().segmentsOfCell(cellId).map { it.kind }

    private suspend fun textsOf(cellId: EntityId) = database.cellSegmentDao().segmentsOfCell(cellId).map { it.text }

    // ------------------------------------------------------- changing a task

    @Test
    fun `the name changes the document by exactly the name`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak: Knight, token ×14")
            val taskId = makeTask(game, cell, "Knight")

            assertTrue(
                store.editTask(taskId, "Şövalye", colorNamed("Siyah").id, 15, null, TrackingMode.THREE_D_BATCH),
            )

            assertEquals("Basılacak: Şövalye, token ×14", documentTextOf(cell.id))
            // The punctuation and spacing on either side belong to their own
            // pieces and are not touched by renaming what sits between them.
            assertEquals(listOf("Basılacak: ", null, ", token ×14"), textsOf(cell.id))
        }

    @Test
    fun `the name is trimmed at its edges and kept inside`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "40 gri token")
            val taskId = makeTask(game, cell, "token")

            store.editTask(taskId, "  gri  token  ", colorNamed("Siyah").id, 15, null, TrackingMode.THREE_D_BATCH)

            assertEquals("gri  token", assertNotNull(database.taskDao().activeTaskById(taskId)).name)
        }

    @Test
    fun `a blank name or one with a line ending is refused`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "40 gri token")
            val taskId = makeTask(game, cell, "token")
            val siyah = colorNamed("Siyah").id

            listOf("   " to TaskEditFailure.TASK_NAME_EMPTY, "gri\ntoken" to TaskEditFailure.NAME_CONTAINS_LINE_BREAK)
                .forEach { (name, expected) ->
                    val refusal =
                        assertFailsWith<TaskEditException> {
                            store.editTask(taskId, name, siyah, 15, null, TrackingMode.THREE_D_BATCH)
                        }
                    assertEquals(expected, refusal.failure)
                }
            assertEquals("40 gri token", documentTextOf(cell.id))
        }

    @Test
    fun `the colour goes into the first slot and replaces what was there`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "40 gri token")
            val taskId = makeTask(game, cell, "token")

            store.editTask(taskId, "token", colorNamed("Kırmızı").id, 15, null, TrackingMode.THREE_D_BATCH)

            val colors = database.taskColorDao().colorsOfTask(taskId)
            assertEquals(listOf(colorNamed("Kırmızı").id), colors.map { it.colorId })
            assertEquals(listOf(0), colors.map { it.slotIndex })
        }

    @Test
    fun `a task with no colour can be given one`() =
        runBlocking<Unit> {
            // PLAN 5.10 keeps a colourless task alive and waiting for one.
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "40 gri token")
            val taskId = makeTask(game, cell, "token")
            store.editTask(taskId, "token", null, 15, null, TrackingMode.THREE_D_BATCH)
            assertTrue(database.taskColorDao().colorsOfTask(taskId).isEmpty())

            store.editTask(taskId, "token", colorNamed("Sarı").id, 15, null, TrackingMode.THREE_D_BATCH)

            assertEquals(listOf(colorNamed("Sarı").id), database.taskColorDao().colorsOfTask(taskId).map { it.colorId })
        }

    @Test
    fun `a task carrying several colours is not quietly reduced to one`() =
        runBlocking<Unit> {
            // PLAN 5.10 has both kinds of task and says nothing about carrying
            // one across to the other, so what a several-colour task would keep
            // is not something to decide here.
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "40 gri kılıç")
            val taskId = makeTask(game, cell, "kılıç")
            database.taskColorDao().addColorToTask(taskId, colorNamed("Gri").id)
            val before = database.taskColorDao().colorsOfTask(taskId)

            val refusal =
                assertFailsWith<TaskEditException> {
                    store.editTask(taskId, "kılıç", colorNamed("Sarı").id, 15, null, TrackingMode.THREE_D_BATCH)
                }

            assertEquals(TaskEditFailure.COLOR_COUNT_NOT_CHANGEABLE, refusal.failure)
            assertEquals(before, database.taskColorDao().colorsOfTask(taskId))
        }

    @Test
    fun `a task carrying several colours can still have its other fields changed`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "40 gri kılıç")
            val taskId = makeTask(game, cell, "kılıç")
            database.taskColorDao().addColorToTask(taskId, colorNamed("Gri").id)
            val before = database.taskColorDao().colorsOfTask(taskId)

            store.editTask(
                taskId,
                "kılıç",
                before.map { it.colorId },
                20,
                "iki yedek",
                TrackingMode.THREE_D_BATCH,
            )

            val task = assertNotNull(database.taskDao().activeTaskById(taskId))
            assertEquals(20, task.requiredQuantity)
            assertEquals("iki yedek", task.notes)
            assertEquals(before, database.taskColorDao().colorsOfTask(taskId), "the colour list was disturbed")
        }

    @Test
    fun `a colour that has been deleted is refused`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "40 gri token")
            val taskId = makeTask(game, cell, "token")

            val refusal =
                assertFailsWith<TaskEditException> {
                    store.editTask(taskId, "token", IdGenerator.Random.newId(), 15, null, TrackingMode.THREE_D_BATCH)
                }

            assertEquals(TaskEditFailure.COLOR_NOT_AVAILABLE, refusal.failure)
        }

    @Test
    fun `the note is kept exactly as it was typed`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "40 gri token")
            val taskId = makeTask(game, cell, "token")

            store.editTask(taskId, "token", colorNamed("Siyah").id, 15, "  iki yedek  ", TrackingMode.THREE_D_BATCH)

            assertEquals("  iki yedek  ", assertNotNull(database.taskDao().activeTaskById(taskId)).notes)
        }

    @Test
    fun `saving the same answers again changes nothing`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "40 gri token")
            val taskId = makeTask(game, cell, "token")
            val before = assertNotNull(database.taskDao().activeTaskById(taskId))

            assertEquals(
                false,
                store.editTask(taskId, "token", colorNamed("Siyah").id, 15, null, TrackingMode.THREE_D_BATCH),
            )
            assertEquals(before, database.taskDao().activeTaskById(taskId))
        }

    // ------------------------------------------------- the total and progress

    @Test
    fun `the total cannot fall below what a stage has already done`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id, CellColumnType.CARD)
            addText(cell.id, "60 kart basılacak")
            val taskId = makeTask(game, cell, "kart", quantity = 60, trackingMode = TrackingMode.PIPELINE)
            database.taskProgressDao().setStageQuantity(taskId, ProductionStage.PRINT, 40, StoppedClock(updatedAt))

            val refusal =
                assertFailsWith<TaskEditException> {
                    store.editTask(taskId, "kart", colorNamed("Siyah").id, 20, null, TrackingMode.PIPELINE)
                }

            assertEquals(TaskEditFailure.QUANTITY_BELOW_PROGRESS, refusal.failure)
            assertEquals(60, assertNotNull(database.taskDao().activeTaskById(taskId)).requiredQuantity)
        }

    @Test
    fun `the total can be changed while nothing has been done yet`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id, CellColumnType.CARD)
            addText(cell.id, "60 kart basılacak")
            val taskId = makeTask(game, cell, "kart", quantity = 60, trackingMode = TrackingMode.PIPELINE)

            assertTrue(store.editTask(taskId, "kart", colorNamed("Siyah").id, 80, null, TrackingMode.PIPELINE))

            assertEquals(80, assertNotNull(database.taskDao().activeTaskById(taskId)).requiredQuantity)
        }

    @Test
    fun `the total of a finished pipeline task is refused rather than made to fit`() =
        runBlocking<Unit> {
            // PLAN 6.4 will not let the finished mark contradict the counters,
            // and PLAN describes no reopening on a change of total.
            val game = addGame()
            val cell = addCell(game.id, CellColumnType.CARD)
            addText(cell.id, "60 kart basılacak")
            val taskId = makeTask(game, cell, "kart", quantity = 60, trackingMode = TrackingMode.PIPELINE)
            val clock = StoppedClock(updatedAt)
            listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT).forEach { stage ->
                database.taskProgressDao().setStageQuantity(taskId, stage, 60, clock)
            }
            val before = assertNotNull(database.taskDao().activeTaskById(taskId))
            assertTrue(before.isCompleted)

            val refusal =
                assertFailsWith<TaskEditException> {
                    store.editTask(taskId, "kart", colorNamed("Siyah").id, 80, null, TrackingMode.PIPELINE)
                }

            assertEquals(TaskEditFailure.QUANTITY_LOCKED_BY_COMPLETION, refusal.failure)
            assertEquals(before, database.taskDao().activeTaskById(taskId))
            assertEquals(
                listOf(60, 60, 60),
                database.taskProgressDao().stagesOfTask(taskId).map { it.completedQuantity },
                "the stage counts were moved to fit a new total",
            )
        }

    @Test
    fun `a quantity that is not greater than zero is refused`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "40 gri token")
            val taskId = makeTask(game, cell, "token")

            listOf(0, -3).forEach { quantity ->
                val refusal =
                    assertFailsWith<TaskEditException> {
                        store.editTask(taskId, "token", colorNamed("Siyah").id, quantity, null, TrackingMode.THREE_D_BATCH)
                    }
                assertEquals(TaskEditFailure.INVALID_REQUIRED_QUANTITY, refusal.failure)
            }
        }

    @Test
    fun `a task that is gone is refused`() =
        runBlocking<Unit> {
            val refusal =
                assertFailsWith<TaskEditException> {
                    store.editTask(
                        IdGenerator.Random.newId(),
                        "token",
                        colorNamed("Siyah").id,
                        15,
                        null,
                        TrackingMode.THREE_D_BATCH,
                    )
                }

            assertEquals(TaskEditFailure.TASK_NOT_AVAILABLE, refusal.failure)
        }

    // ------------------------------------------------ turning it back into text

    @Test
    fun `converting a task leaves the cell saying exactly what it said`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak: Knight, token ×14"
            addText(cell.id, text)
            val taskId = makeTask(game, cell, "Knight")
            assertEquals(text, documentTextOf(cell.id))

            assertTrue(store.convertTaskToText(taskId))

            assertEquals(text, documentTextOf(cell.id))
        }

    @Test
    fun `the freed words join the text on either side into one piece`() =
        runBlocking<Unit> {
            // PLAN 12.8 and 5.5: no cell accumulates needless pieces.
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak: Knight, token ×14")
            val taskId = makeTask(game, cell, "Knight")
            assertEquals(3, kindsOf(cell.id).size)

            store.convertTaskToText(taskId)

            assertEquals(listOf(SegmentKind.PLAIN_TEXT), kindsOf(cell.id))
            assertEquals(listOf("Basılacak: Knight, token ×14"), textsOf(cell.id))
            assertEquals(listOf(0), ordersOf(cell.id))
        }

    @Test
    fun `everything that hung off the task goes with it`() =
        runBlocking<Unit> {
            // PLAN 12.8 asks for the relations to be cleaned up; leaving any of
            // them would be a history belonging to nothing.
            val game = addGame()
            val cell = addCell(game.id, CellColumnType.CARD)
            addText(cell.id, "60 kart basılacak")
            val taskId = makeTask(game, cell, "kart", quantity = 60, trackingMode = TrackingMode.PIPELINE)
            database.taskProgressDao().setStageQuantity(taskId, ProductionStage.PRINT, 10, StoppedClock(updatedAt))
            assertTrue(database.taskProgressDao().stagesOfTask(taskId).isNotEmpty())

            store.convertTaskToText(taskId)

            assertNull(database.taskDao().taskByIdIncludingDeleted(taskId), "the task record survived")
            assertTrue(database.taskProgressDao().stagesOfTask(taskId).isEmpty(), "stages were left behind")
            assertTrue(database.taskProgressDao().progressEventsOfTask(taskId).isEmpty(), "events were left behind")
            assertTrue(database.taskColorDao().colorsOfTask(taskId).isEmpty(), "colours were left behind")
            assertNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(taskId), "the piece was left behind")
            assertEquals("60 kart basılacak", documentTextOf(cell.id))
        }

    @Test
    fun `no other task in the cell is touched`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak: Knight ve token")
            val knight = makeTask(game, cell, "Knight")
            val token = makeTask(game, cell, "token", color = "Sarı", quantity = 150)
            val tokenBefore = assertNotNull(database.taskDao().activeTaskById(token))
            val tokenColors = database.taskColorDao().colorsOfTask(token)

            store.convertTaskToText(knight)

            assertEquals(tokenBefore, database.taskDao().activeTaskById(token))
            assertEquals(tokenColors, database.taskColorDao().colorsOfTask(token))
            assertEquals("Basılacak: Knight ve token", documentTextOf(cell.id))
            assertEquals(listOf(SegmentKind.PLAIN_TEXT, SegmentKind.TASK), kindsOf(cell.id))
            assertEquals(listOf(0, 1), ordersOf(cell.id))
        }

    @Test
    fun `converting a task that is gone is refused and writes nothing`() =
        runBlocking<Unit> {
            val refusal = assertFailsWith<TaskEditException> { store.convertTaskToText(IdGenerator.Random.newId()) }

            assertEquals(TaskEditFailure.TASK_NOT_AVAILABLE, refusal.failure)
            assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty())
        }

    // ------------------------------------------------------------- rollback

    /** Hands out identities until it is asked once too often. */
    private class LimitedIds(
        private val limit: Int,
    ) : IdGenerator {
        private var reads = 0

        override fun newId(): EntityId {
            reads++
            check(reads <= limit) { "no more identities" }
            return IdGenerator.Random.newId()
        }
    }

    @Test
    fun `a conversion that runs out of identities leaves the cell exactly as it was`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Knight")
            // The whole cell is the task, so the freed word has no neighbour's
            // row to join and needs one of its own — and there is none to give.
            val knight = makeTask(game, cell, "Knight")
            val document = documentTextOf(cell.id)
            val segmentsBefore = database.cellSegmentDao().segmentsOfCell(cell.id)
            val tasksBefore = database.taskDao().allTasksIncludingDeleted()
            val colorsBefore = database.taskEditDao().colorsOfTask(knight)
            val starved = TaskEditStore(database.taskEditDao(), LimitedIds(limit = 0), StoppedClock(updatedAt))

            assertFailsWith<IllegalStateException> { starved.convertTaskToText(knight) }

            assertEquals(document, documentTextOf(cell.id), "the cell did not come back")
            assertEquals(segmentsBefore, database.cellSegmentDao().segmentsOfCell(cell.id))
            assertEquals(tasksBefore, database.taskDao().allTasksIncludingDeleted())
            assertEquals(colorsBefore, database.taskEditDao().colorsOfTask(knight))
        }

    @Test
    fun `converting a task leaves another task's piece alone whatever state it is in`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            addText(cell.id, "Basılacak Knight ve token")
            val knight = makeTask(game, cell, "Knight")
            val token = makeTask(game, cell, "token")
            database.taskDao().softDelete(token, createdAt)
            val document = documentTextOf(cell.id)
            val piece = assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(token))

            assertTrue(store.convertTaskToText(knight))

            assertEquals(document, documentTextOf(cell.id), "the cell stopped saying what it said")
            // The row is the same row and still the same task's; the pieces
            // before it merged, so its place moved up with them.
            val after = assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(token))
            assertEquals(piece.id, after.id, "the piece was rewritten as a new row")
            assertEquals(piece.taskId, after.taskId, "the piece lost the task it names")
            assertEquals(piece.kind, after.kind, "the piece stopped being a task piece")
            assertNull(after.text, "text was written into a task piece")
            assertEquals(listOf(0, 1), ordersOf(cell.id))
            assertEquals(listOf(SegmentKind.PLAIN_TEXT, SegmentKind.TASK), kindsOf(cell.id))
        }
}
