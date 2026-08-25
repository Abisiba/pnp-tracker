package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CommittedSchema
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.deletedAt
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.insertSegmentDirectly
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Writing what a cell says.
 *
 * The rule underneath nearly all of these is that a cell is the user's own note
 * and every character in it is theirs. Nothing is trimmed, nothing is collapsed,
 * nothing is spaced out and nothing is joined: what goes in comes back.
 */
class CellTextStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: CellTextStore
    private var realDatabaseExistedBefore = false

    /** Hands out fixed times, and says how often it was asked. */
    private class CountingClock(
        private val start: Instant,
    ) : Clock {
        var reads: Int = 0
            private set

        override fun now(): Instant {
            reads++
            return start.plus(kotlin.time.Duration.parse("${reads}m"))
        }
    }

    /** Hands out identities, and says how often it was asked. */
    private class CountingIds : IdGenerator {
        var reads: Int = 0
            private set

        override fun newId(): EntityId {
            reads++
            return IdGenerator.Random.newId()
        }
    }

    private lateinit var clock: CountingClock
    private lateinit var ids: CountingIds

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        clock = CountingClock(createdAt)
        ids = CountingIds()
        store = CellTextStore(database.cellSegmentDao(), idGenerator = ids, clock = clock)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    /**
     * Sets the cell's document, taking what it says now as the expectation.
     *
     * The production path is handed the text the editor was opened on, so a
     * change somebody else made in between is caught. A test that only means to
     * put text in a cell reads that value first rather than repeating it, which
     * keeps these about the writing rather than about the staleness check —
     * that has tests of its own.
     */
    private suspend fun CellTextStore.setText(
        gameId: EntityId,
        columnType: CellColumnType,
        text: String,
    ): Boolean = saveDocumentText(gameId, columnType, documentTextOf(gameId, columnType), text)

    /** What the cell reads as now: its pieces end to end, tasks as their names. */
    private suspend fun documentTextOf(
        gameId: EntityId,
        columnType: CellColumnType,
    ): String {
        val cell = database.cellSegmentDao().cellOfGame(gameId, columnType) ?: return ""
        val pieces =
            database.cellSegmentDao().runsOfCell(cell.id).map { run ->
                if (run.kind == SegmentKind.TASK) run.taskName.orEmpty() else run.text.orEmpty()
            }
        return pieces.joinToString(separator = "")
    }

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
    ): EntityId {
        val cell = aCell(gameId = gameId, columnType = columnType)
        database.gameCellDao().insert(cell)
        return cell.id
    }

    private suspend fun addText(
        cellId: EntityId,
        text: String,
        orderIndex: Int,
    ) = insertSegmentDirectly(
        database,
        CellSegmentEntity.plainText(
            id = IdGenerator.Random.newId(),
            cellId = cellId,
            orderIndex = orderIndex,
            text = text,
            moment = createdAt,
        ),
    )

    private suspend fun addTask(
        cellId: EntityId,
        name: String = "Gri token",
    ): EntityId {
        val task = aTask(name = name, poolType = PoolType.THREE_D, trackingMode = TrackingMode.THREE_D_BATCH)
        database.taskDao().addTaskToCell(task, cellId, IdGenerator.Random.newId(), createdAt)
        return task.id
    }

    private suspend fun textOf(
        gameId: EntityId,
        columnType: CellColumnType = CellColumnType.THREE_D,
    ): String? {
        val cell = database.gameCellDao().cellOfGame(gameId, columnType) ?: return null
        return database.cellSegmentDao().segmentsOfCell(cell.id).joinToString("") { it.text.orEmpty() }
    }

    private fun rows(table: String) = CommittedSchema.countRowsOf(directory.databaseFile, table)

    // ------------------------------------------------------- opening a cell

    @Test
    fun `the first text written opens the cell and the piece together`() =
        runBlocking<Unit> {
            val game = addGame()

            assertTrue(store.setText(game.id, CellColumnType.THREE_D, "40 gri token"))

            val cell = assertNotNull(database.gameCellDao().cellOfGame(game.id, CellColumnType.THREE_D))
            val segment = database.cellSegmentDao().segmentsOfCell(cell.id).single()
            assertEquals("40 gri token", segment.text)
            assertEquals(0, segment.orderIndex)
        }

    @Test
    fun `writing nothing into a cell nobody opened opens nothing`() =
        runBlocking<Unit> {
            // The table shows five columns for every game; a cell created by an
            // empty save would be a row saying something nobody said.
            val game = addGame()

            assertFalse(store.setText(game.id, CellColumnType.THREE_D, ""))

            assertEquals(0, rows("game_cells"))
            assertEquals(0, rows("cell_segments"))
            assertEquals(0, clock.reads, "an empty save read the clock")
            assertEquals(0, ids.reads, "an empty save took an identity")
        }

    @Test
    fun `all five columns take text, the notes column included`() =
        runBlocking<Unit> {
            val game = addGame()

            CellColumnType.entries.forEach { columnType ->
                assertTrue(store.setText(game.id, columnType, "metin ${columnType.ordinal}"))
                assertEquals("metin ${columnType.ordinal}", textOf(game.id, columnType))
            }
            assertEquals(5, rows("game_cells"))
        }

    @Test
    fun `a failure partway leaves no empty cell behind`() =
        runBlocking<Unit> {
            // The identity of the piece is taken after the cell is written, so a
            // generator that fails there fails between the two writes.
            val game = addGame()
            val failing =
                object : IdGenerator {
                    var handed = 0

                    override fun newId(): EntityId {
                        handed++
                        if (handed > 1) error("no more identities")
                        return IdGenerator.Random.newId()
                    }
                }
            val breaking = CellTextStore(database.cellSegmentDao(), idGenerator = failing, clock = clock)

            assertFailsWith<IllegalStateException> {
                breaking.setText(game.id, CellColumnType.THREE_D, "40 gri")
            }

            assertEquals(0, rows("game_cells"), "a cell was left behind with nothing in it")
            assertEquals(0, rows("cell_segments"))
        }

    // ------------------------------------------------------- keeping it exact

    @Test
    fun `leading, trailing and doubled spaces are all kept`() =
        runBlocking<Unit> {
            val game = addGame()
            val exact = "   40  gri   token  "

            store.setText(game.id, CellColumnType.THREE_D, exact)

            assertEquals(exact, textOf(game.id))
        }

    @Test
    fun `nothing is inserted around punctuation`() =
        runBlocking<Unit> {
            val game = addGame()
            val exact = "Knight,token;kalkan:2×,bıçak."

            store.setText(game.id, CellColumnType.THREE_D, exact)

            assertEquals(exact, textOf(game.id))
        }

    @Test
    fun `line breaks and Turkish letters come back exactly as they went in`() =
        runBlocking<Unit> {
            val game = addGame()
            val exact = "Şığ ölçüsü: 30×30\nİkinci satır\n\nÜçüncü\tsekmeli\r\nWindows satırı"

            store.setText(game.id, CellColumnType.THREE_D, exact)

            assertEquals(exact, textOf(game.id), "the text was normalised on its way in")
        }

    @Test
    fun `a cell of one space is a cell with something in it`() =
        runBlocking<Unit> {
            val game = addGame()

            assertTrue(store.setText(game.id, CellColumnType.NOTES, " "))

            assertEquals(" ", textOf(game.id, CellColumnType.NOTES))
        }

    // ------------------------------------------------------------- rewriting

    @Test
    fun `saving what the cell already says does nothing at all`() =
        runBlocking<Unit> {
            val game = addGame()
            store.setText(game.id, CellColumnType.THREE_D, "40 gri")
            val cell = assertNotNull(database.gameCellDao().cellOfGame(game.id, CellColumnType.THREE_D))
            val before = database.cellSegmentDao().segmentsOfCell(cell.id).single()
            val clockReads = clock.reads
            val idReads = ids.reads

            assertFalse(store.setText(game.id, CellColumnType.THREE_D, "40 gri"))

            assertEquals(clockReads, clock.reads, "a repeated save read the clock")
            assertEquals(idReads, ids.reads, "a repeated save took an identity")
            assertEquals(before, database.cellSegmentDao().segmentsOfCell(cell.id).single())
            assertEquals(1, rows("cell_segments"))
        }

    @Test
    fun `a real change moves the update time and leaves the creation time alone`() =
        runBlocking<Unit> {
            val game = addGame()
            store.setText(game.id, CellColumnType.THREE_D, "40 gri")
            val cell = assertNotNull(database.gameCellDao().cellOfGame(game.id, CellColumnType.THREE_D))
            val first = database.cellSegmentDao().segmentsOfCell(cell.id).single()

            store.setText(game.id, CellColumnType.THREE_D, "48 gri")

            val after = database.cellSegmentDao().segmentsOfCell(cell.id).single()
            assertEquals("48 gri", after.text)
            assertEquals(first.createdAt, after.createdAt, "the creation time moved")
            assertTrue(after.updatedAt > first.updatedAt, "the update time stood still")
            assertEquals(first.id, after.id, "the piece lost its identity for a change of words")
            val refreshed = assertNotNull(database.gameCellDao().cellById(cell.id))
            assertEquals(cell.createdAt, refreshed.createdAt)
            assertTrue(refreshed.updatedAt > cell.updatedAt, "the cell's update time stood still")
        }

    @Test
    fun `clearing a cell takes its pieces and leaves the cell`() =
        runBlocking<Unit> {
            // A draft or an import may be aiming at the cell's identity, and
            // PLAN 11.4.1 makes that identity the game's column rather than
            // whatever happens to be written in it.
            val game = addGame()
            store.setText(game.id, CellColumnType.THREE_D, "40 gri")
            val cell = assertNotNull(database.gameCellDao().cellOfGame(game.id, CellColumnType.THREE_D))

            assertTrue(store.setText(game.id, CellColumnType.THREE_D, ""))

            assertEquals(0, rows("cell_segments"), "a piece survived the cell being cleared")
            assertNotNull(database.gameCellDao().cellById(cell.id), "the cell itself was deleted")
            assertEquals("", textOf(game.id))
        }

    @Test
    fun `pieces that were adjacent are read and rewritten without a separator`() =
        runBlocking<Unit> {
            // PLAN 5.5 and 16: adjacent text is merged and no cell keeps needless
            // pieces. Nothing is put between them on the way.
            val game = addGame()
            val cellId = addCell(game.id)
            addText(cellId, "sol", orderIndex = 0)
            addText(cellId, "sağ", orderIndex = 1)
            assertEquals("solsağ", textOf(game.id))

            assertTrue(store.setText(game.id, CellColumnType.THREE_D, "solsağ ve daha fazlası"))

            val segment = database.cellSegmentDao().segmentsOfCell(cellId).single()
            assertEquals("solsağ ve daha fazlası", segment.text)
            assertEquals(0, segment.orderIndex)
        }

    @Test
    fun `merging several pieces keeps the first one's identity`() =
        runBlocking<Unit> {
            val game = addGame()
            val cellId = addCell(game.id)
            addText(cellId, "A\n", orderIndex = 0)
            addText(cellId, "B", orderIndex = 1)
            addText(cellId, "C", orderIndex = 2)
            val firstId =
                database
                    .cellSegmentDao()
                    .segmentsOfCell(cellId)
                    .first()
                    .id
            assertEquals("A\nBC", textOf(game.id))

            store.setText(game.id, CellColumnType.THREE_D, "A\nBCD")

            val segment = database.cellSegmentDao().segmentsOfCell(cellId).single()
            assertEquals(firstId, segment.id)
            assertEquals("A\nBCD", segment.text)
        }

    // ------------------------------------------------------- what is refused

    @Test
    fun `a change that reaches into a task is refused`() =
        runBlocking<Unit> {
            // PLAN 5.5 makes a task piece atomic. Typing over its characters
            // would cost it the colours, pipeline and history that hang off its
            // identity, so the whole change is refused rather than half taken.
            val game = addGame()
            val cellId = addCell(game.id)
            addText(cellId, "Basılacak: ", orderIndex = 0)
            addTask(cellId)
            val before = documentTextOf(game.id, CellColumnType.THREE_D)

            val refusal =
                assertFailsWith<CellTextException> {
                    store.setText(game.id, CellColumnType.THREE_D, "Basılacak: düz metin")
                }

            assertEquals(CellTextFailure.CHANGE_CROSSES_A_TASK, refusal.failure)
            assertEquals(before, documentTextOf(game.id, CellColumnType.THREE_D))
        }

    @Test
    fun `a refused save leaves the task's colours, stages and history alone`() =
        runBlocking<Unit> {
            val game = addGame()
            val cellId = addCell(game.id, CellColumnType.CARD)
            val task =
                aTask(name = "Bird Cards", poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
                    .copy(requiredQuantity = 170)
            database.taskDao().addTaskToCell(task, cellId, IdGenerator.Random.newId(), createdAt)
            database.taskProgressDao().reportFailure(
                IdGenerator.Random.newId(),
                task.id,
                2,
                clock,
                cardReference = "Bird #7",
            )
            val stagesBefore = database.taskProgressDao().stagesOfTask(task.id)
            val eventsBefore = database.taskProgressDao().progressEventsOfTask(task.id)
            val taskBefore = assertNotNull(database.taskProgressDao().taskById(task.id))

            assertFailsWith<CellTextException> {
                store.setText(game.id, CellColumnType.CARD, "hepsi düz metin")
            }

            assertEquals(taskBefore, database.taskProgressDao().taskById(task.id))
            assertEquals(stagesBefore, database.taskProgressDao().stagesOfTask(task.id))
            assertEquals(eventsBefore, database.taskProgressDao().progressEventsOfTask(task.id))
        }

    @Test
    fun `a cell whose only piece names a deleted task can still be written in`() =
        runBlocking<Unit> {
            // The task is gone from every active view, so as far as the user is
            // concerned the cell is empty and they may type in it. Its piece is
            // still there and is left exactly alone: nothing writes over a task
            // piece, not even one nobody can see.
            val game = addGame()
            val cellId = addCell(game.id)
            val taskId = addTask(cellId)
            database.taskDao().softDelete(taskId, deletedAt)
            val before = assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(taskId))

            assertTrue(store.setText(game.id, CellColumnType.THREE_D, "artık düz metin"))

            assertEquals("artık düz metin", documentTextOf(game.id, CellColumnType.THREE_D))
            val after = assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(taskId))
            assertEquals(before.taskId, after.taskId, "the deleted task's piece lost its task")
            assertEquals(before.kind, after.kind, "the deleted task's piece was turned into text")
            assertNull(after.text, "text was written into a task piece")
        }

    @Test
    fun `a deleted game cannot be written in`() =
        runBlocking<Unit> {
            val game = addGame(deleted = true)

            val refusal =
                assertFailsWith<CellTextException> {
                    store.setText(game.id, CellColumnType.THREE_D, "40 gri")
                }

            assertEquals(CellTextFailure.GAME_NOT_AVAILABLE, refusal.failure)
            assertEquals(0, rows("game_cells"))
            assertEquals(0, clock.reads)
        }

    @Test
    fun `a game that was never there is refused too`() =
        runBlocking<Unit> {
            val refusal =
                assertFailsWith<CellTextException> {
                    store.setText(IdGenerator.Random.newId(), CellColumnType.THREE_D, "40 gri")
                }

            assertEquals(CellTextFailure.GAME_NOT_AVAILABLE, refusal.failure)
        }

    @Test
    fun `a finished game is written in like any other`() =
        runBlocking<Unit> {
            // PLAN 5.3 keeps a finished game editable, and PLAN 12.4 lets the
            // user work in whichever view they are looking at.
            val game = addGame(isCompleted = true)

            assertTrue(store.setText(game.id, CellColumnType.NOTES, "sonradan eklenen not"))

            assertEquals("sonradan eklenen not", textOf(game.id, CellColumnType.NOTES))
            assertTrue(assertNotNull(database.gameDao().activeGameById(game.id)).isManuallyCompleted)
        }

    @Test
    fun `one game's writing never lands in another game's cell`() =
        runBlocking<Unit> {
            val first = addGame("Harmonies")
            val second = addGame("Wingspan")

            store.setText(first.id, CellColumnType.THREE_D, "gri")

            assertEquals("gri", textOf(first.id))
            assertNull(textOf(second.id))
        }

    @Test
    fun `no zero length piece is ever left in a cell`() =
        runBlocking<Unit> {
            val game = addGame()
            store.setText(game.id, CellColumnType.THREE_D, "bir şey")
            store.setText(game.id, CellColumnType.THREE_D, "")
            store.setText(game.id, CellColumnType.THREE_D, "yine bir şey")
            store.setText(game.id, CellColumnType.THREE_D, "")

            val cell = assertNotNull(database.gameCellDao().cellOfGame(game.id, CellColumnType.THREE_D))
            assertTrue(
                database.cellSegmentDao().segmentsOfCell(cell.id).none { it.text?.isEmpty() == true },
                "an empty piece was written",
            )
        }
}
