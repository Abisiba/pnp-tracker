package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.HistoryEventEntity
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.insertSegmentDirectly
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.history.HistoryChange
import dev.pnptracker.domain.history.HistoryEntry
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the history screen reads back out of a real database.
 *
 * Everything here goes through the writes the application really makes and comes
 * back through the reads the screen really uses: the point is not that the DAO
 * can be called but that the two records of the past — `history_events` and the
 * shortage events that predate it — arrive as one list, in one order, with the
 * names a person can read.
 *
 * The order is the part worth pinning. Everything one transaction writes carries
 * the same moment, so a screen that ordered by moment alone would be free to
 * shuffle those lines between one reading and the next.
 */
class HistoryReadingTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: HistoryStore
    private var realDatabaseExistedBefore = false

    private val ids = IdGenerator.Random

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = HistoryStore(database.historyDao())
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun read(): List<HistoryEntry> = store.observeHistory().first().entries

    // ------------------------------------------------------------- fixtures

    /** A game with one cell, one stretch of words and one task cut out of them. */
    private suspend fun aTaskIn(
        gameName: String = "Harmonies",
        taskName: String = "token",
        poolType: PoolType = PoolType.THREE_D,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        quantity: Int? = 14,
    ): Triple<EntityId, EntityId, EntityId> {
        val game = aGame(name = gameName)
        val cell = aCell(gameId = game.id, columnType = CellColumnType.of(poolType))
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        val task =
            aTask(
                name = taskName,
                poolType = poolType,
                trackingMode = trackingMode,
                requiredQuantity = quantity,
            )
        database.taskDao().addTaskToCell(task, cell.id, ids.newId(), createdAt)
        return Triple(game.id, cell.id, task.id)
    }

    private fun appended(
        kind: HistoryEventKind,
        occurredAt: Instant,
        gameId: EntityId,
        taskId: EntityId?,
        id: EntityId = ids.newId(),
    ) = HistoryEventEntity(id = id, kind = kind, occurredAt = occurredAt, gameId = gameId, taskId = taskId)

    /** Puts a line straight into the table, for the orderings a fixture cannot stage. */
    private suspend fun append(event: HistoryEventEntity) {
        database.taskDao().appendHistoryEvent(event)
    }

    // ---------------------------------------------------------------- order

    @Test
    fun `the newest thing that happened is first`() =
        runBlocking<Unit> {
            val (gameId, _, taskId) = aTaskIn()
            val early = Instant.fromEpochMilliseconds(1_000)
            val late = Instant.fromEpochMilliseconds(9_000)
            append(appended(HistoryEventKind.TASK_COMPLETED, early, gameId, taskId))
            append(appended(HistoryEventKind.TASK_REOPENED, late, gameId, taskId))

            assertContentEquals(
                listOf(HistoryChange.TaskReopened, HistoryChange.TaskCompleted),
                read().map { it.change },
            )
        }

    @Test
    fun `lines written at the same moment are ordered by identity, the same way every time`() =
        runBlocking<Unit> {
            val (gameId, _, taskId) = aTaskIn()
            val moment = Instant.fromEpochMilliseconds(4_000)
            // Everything one transaction writes carries its moment, so the tie
            // is the ordinary case rather than the odd one. Identity settles it,
            // and identity is compared as the text SQLite stores and orders it by.
            val names = (0 until 12).map { ids.newId() }
            names.forEach { append(appended(HistoryEventKind.TASK_COMPLETED, moment, gameId, taskId, id = it)) }

            val first = read().map { it.id.toString() }
            val second = read().map { it.id.toString() }

            assertEquals(names.map { it.toString() }.sortedDescending(), first)
            assertContentEquals(first, second, "two readings of the same rows came back in different orders")
        }

    @Test
    fun `the two records of the past arrive as one ordered list`() =
        runBlocking<Unit> {
            val (gameId, _, taskId) = aTaskIn()
            val progress = TaskProgressStore(database.taskProgressDao(), StoppedClock(Instant.fromEpochMilliseconds(5_000)))
            append(appended(HistoryEventKind.TASK_COMPLETED, Instant.fromEpochMilliseconds(1_000), gameId, taskId))
            progress.reportFailure(eventId = ids.newId(), taskId = taskId, quantity = 3)
            append(appended(HistoryEventKind.TASK_REOPENED, Instant.fromEpochMilliseconds(9_000), gameId, taskId))

            val changes = read().map { it.change }

            assertEquals(3, changes.size)
            assertIs<HistoryChange.TaskReopened>(changes[0])
            assertIs<HistoryChange.ShortageReported>(changes[1])
            assertIs<HistoryChange.TaskCompleted>(changes[2])
        }

    // ----------------------------------------------------------- no doubling

    @Test
    fun `a task in several colours with a whole pipeline is still one line per event`() =
        runBlocking<Unit> {
            val (gameId, _, taskId) =
                aTaskIn(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE, quantity = 10)
            // The shapes a join would multiply by: three colours, three stages,
            // and two shortages, all hanging off the one task.
            database.colorDao().allColors().take(3).forEachIndexed { slot, color ->
                database.taskColorDao().insert(
                    TaskColorEntity(taskId = taskId, colorId = color.id, slotIndex = slot),
                )
            }
            val progress = TaskProgressStore(database.taskProgressDao(), StoppedClock(updatedAt))
            progress.reportFailure(eventId = ids.newId(), taskId = taskId, quantity = 2)
            progress.reportFailure(eventId = ids.newId(), taskId = taskId, quantity = 1)
            progress.setStageQuantities(taskId, mapOf(ProductionStage.PRINT to 4))
            append(appended(HistoryEventKind.TASK_COMPLETED, Instant.fromEpochMilliseconds(9_000), gameId, taskId))

            val lines = read()

            // Three stage rows and three colour rows are exactly what a single
            // join over both would have turned one event into nine.
            assertEquals(3, database.taskProgressDao().stagesOfTask(taskId).size)
            assertEquals(1, lines.count { it.change is HistoryChange.StageMoved })
            assertEquals(2, lines.count { it.change is HistoryChange.ShortageReported })
            assertEquals(1, lines.count { it.change is HistoryChange.TaskCompleted })
            assertEquals(lines.size, lines.map { it.id }.toSet().size, "an event came back more than once")
        }

    // ---------------------------------------------------------- what it says

    @Test
    fun `a moved step carries the step and both counts`() =
        runBlocking<Unit> {
            val (_, _, taskId) =
                aTaskIn(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE, quantity = 10)
            val progress = TaskProgressStore(database.taskProgressDao(), StoppedClock(updatedAt))
            progress.setStageQuantities(taskId, mapOf(ProductionStage.PRINT to 6))

            val moved = assertIs<HistoryChange.StageMoved>(read().single().change)

            assertEquals(ProductionStage.PRINT, moved.stage)
            assertEquals(0, moved.previousQuantity)
            assertEquals(6, moved.newQuantity)
        }

    @Test
    fun `a shortage carries what the user wrote with it and nothing they did not`() =
        runBlocking<Unit> {
            val (_, _, taskId) =
                aTaskIn(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE, quantity = 10)
            val progress = TaskProgressStore(database.taskProgressDao(), StoppedClock(updatedAt))
            progress.reportFailure(
                eventId = ids.newId(),
                taskId = taskId,
                quantity = 2,
                note = "Kenarları kıvrıldı",
                cardReference = "12-13",
                stage = ProductionStage.LAMINATE,
            )
            progress.resolveShortage(eventId = ids.newId(), taskId = taskId, quantity = 1)

            // Both were recorded at the one moment the stopped clock reads, so
            // they are picked out by what they are rather than by where they
            // sit: lines that share a moment have no order between them.
            val changes = read().map { it.change }
            val detailed = changes.filterIsInstance<HistoryChange.ShortageReported>().single()
            val bare = changes.filterIsInstance<HistoryChange.ShortageResolved>().single()

            assertEquals(2, detailed.quantity)
            assertEquals("Kenarları kıvrıldı", detailed.note)
            assertEquals("12-13", detailed.cardReference)
            assertEquals(ProductionStage.LAMINATE, detailed.stage)
            assertEquals(1, bare.quantity)
            assertNull(bare.note, "a shortage recorded as a bare number was given words")
            assertNull(bare.cardReference)
        }

    @Test
    fun `every line names its game and every task line names its task`() =
        runBlocking<Unit> {
            val (gameId, _, taskId) = aTaskIn(gameName = "Harmonies", taskName = "Gri token")
            append(appended(HistoryEventKind.TASK_COMPLETED, updatedAt, gameId, taskId))
            append(appended(HistoryEventKind.GAME_DELETED, updatedAt, gameId, taskId = null))

            val byChange = read().associateBy { it.change }
            val finished = assertNotNull(byChange[HistoryChange.TaskCompleted])
            val removed = assertNotNull(byChange[HistoryChange.GameDeleted])

            assertEquals("Harmonies", finished.gameName)
            assertEquals("Gri token", finished.taskName)
            assertEquals("Harmonies", removed.gameName)
            assertNull(removed.taskName, "an event about the game itself named a task")
        }

    @Test
    fun `a renamed task is shown under the name it has now`() =
        runBlocking<Unit> {
            // The names are joined rather than snapshotted, so a task the user
            // renames afterwards is shown as the thing they would go looking
            // for. PLAN writes no historical names to show instead.
            val (gameId, _, taskId) = aTaskIn(taskName = "token")
            append(appended(HistoryEventKind.TASK_COMPLETED, updatedAt, gameId, taskId))
            val editing = TaskEditStore(database.taskEditDao(), ids, StoppedClock(updatedAt))
            assertTrue(
                editing.editTask(
                    taskId = taskId,
                    name = "gri token",
                    colorId = null,
                    requiredQuantity = 14,
                    notes = null,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                ),
            )

            assertEquals("gri token", read().single().taskName)
        }

    // ------------------------------------------- records that have gone away

    @Test
    fun `a deleted task still has its history, under its own name`() =
        runBlocking<Unit> {
            val (_, _, taskId) = aTaskIn(taskName = "Gri token")
            val progress = TaskProgressStore(database.taskProgressDao(), StoppedClock(updatedAt))
            progress.reportFailure(eventId = ids.newId(), taskId = taskId, quantity = 2)
            assertEquals(1, database.taskDao().softDelete(taskId, updatedAt))

            val lines = read()

            assertNull(database.taskDao().activeTaskById(taskId), "the task is not deleted")
            assertEquals(2, lines.size)
            assertTrue(lines.all { it.taskName == "Gri token" }, "a deleted task lost its name")
            assertTrue(lines.all { it.gameName == "Harmonies" }, "a deleted task lost its game")
            assertTrue(lines.any { it.change is HistoryChange.TaskDeleted })
            assertTrue(lines.any { it.change is HistoryChange.ShortageReported })
        }

    @Test
    fun `a task turned back into words keeps its shortages, and they keep their game`() =
        runBlocking<Unit> {
            // The one case where the ordinary way of placing a shortage stops
            // working: the conversion takes the task's piece of the cell away, so
            // there is no cell and no game to reach through. PLAN 12.15 asks for
            // the line anyway, and the conversion itself recorded the game.
            val game = aGame(name = "Root")
            val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
            database.gameDao().insert(game)
            database.gameCellDao().insert(cell)
            insertSegmentDirectly(
                database,
                CellSegmentEntity.plainText(ids.newId(), cell.id, orderIndex = 0, text = "Basılacak: token", moment = createdAt),
            )
            val segment = database.cellSegmentDao().segmentsOfCell(cell.id).single()
            val creation = TaskFromTextStore(database.taskFromTextDao(), ids, StoppedClock(updatedAt))
            val anyColor = database.colorDao().allColors().first()
            val words = assertNotNull(segment.text)
            val taskId =
                creation.createSingleColorTask(
                    selection =
                        CellTextSelection(
                            gameId = game.id,
                            cellId = cell.id,
                            segmentId = segment.id,
                            expectedText = words,
                            startOffset = words.indexOf("token"),
                            endOffset = words.length,
                        ),
                    colorId = anyColor.id,
                    requiredQuantity = 14,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    notes = null,
                )
            val progress = TaskProgressStore(database.taskProgressDao(), StoppedClock(updatedAt))
            progress.reportFailure(eventId = ids.newId(), taskId = taskId, quantity = 4)
            val editing = TaskEditStore(database.taskEditDao(), ids, StoppedClock(updatedAt))
            assertTrue(editing.convertTaskToText(taskId))

            val lines = read()

            assertNull(
                database.cellSegmentDao().segmentOfTaskIncludingDeleted(taskId),
                "the task still has a piece of the cell",
            )
            assertEquals(2, lines.size, "a line was lost with the segment")
            assertTrue(lines.any { it.change is HistoryChange.TaskConvertedToText })
            val shortage = assertNotNull(lines.firstOrNull { it.change is HistoryChange.ShortageReported })
            assertEquals("Root", shortage.gameName, "the shortage lost the game it happened in")
            assertEquals("token", shortage.taskName)
        }

    @Test
    fun `an event in a deleted game is still part of the history`() =
        runBlocking<Unit> {
            // PLAN 5.2 tombstones a game rather than erasing it, and PLAN 12.15
            // asks the screen for deleted records: a history that hid everything
            // in a removed game would hide the removal itself.
            val (gameId, _, taskId) = aTaskIn(gameName = "Harmonies")
            append(appended(HistoryEventKind.TASK_COMPLETED, updatedAt, gameId, taskId))
            assertEquals(1, database.gameDao().softDelete(gameId, updatedAt))

            val lines = read()

            assertEquals(2, lines.size)
            assertTrue(lines.all { it.gameName == "Harmonies" })
            assertTrue(lines.any { it.change is HistoryChange.GameDeleted })
        }

    @Test
    fun `a history nobody has written to is empty rather than broken`() =
        runBlocking<Unit> {
            aTaskIn()

            assertTrue(store.observeHistory().first().isEmpty)
        }
}
