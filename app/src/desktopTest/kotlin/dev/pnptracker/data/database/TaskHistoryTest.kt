package dev.pnptracker.data.database

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.StageSnapshot
import dev.pnptracker.domain.tasks.TaskProgressException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What gets written down when a task moves.
 *
 * PLAN 12.15 asks a history screen for finished tasks, pipeline movements and
 * deleted records, and until version 7 none of those left anything behind: each
 * one only wrote over a field. These check that every real state change now
 * records itself, that nothing else does, and that the record and the change are
 * one transaction — a task that finished with its history missing, or a history
 * line about a finish that never happened, are both worse than no screen at all.
 */
class TaskHistoryTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    /** Hands out fixed times, and says how often it was asked. */
    private class CountingClock(
        private val start: Instant,
    ) : Clock {
        var reads: Int = 0
            private set

        override fun now(): Instant {
            reads++
            return start.plus(kotlin.time.Duration.parse("${reads}s"))
        }
    }

    /** Hands out identities until it is asked once too often. */
    private class LimitedIds(
        private val limit: Int,
    ) : IdGenerator {
        var reads: Int = 0
            private set

        override fun newId(): EntityId {
            reads++
            check(reads <= limit) { "no more identities" }
            return IdGenerator.Random.newId()
        }
    }

    private lateinit var clock: CountingClock
    private val ids = IdGenerator.Random

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        clock = CountingClock(createdAt)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val progress get() = database.taskProgressDao()

    private val history get() = database.historyDao()

    private var gamesMade = 0

    /** A task of one pool, written into a cell of the matching column. */
    private suspend fun aTaskIn(
        poolType: PoolType,
        requiredQuantity: Int? = 40,
        name: String = "Gri token",
        gameName: String = "Harmonies ${gamesMade++}",
    ): EntityId {
        val cell = insertGameAndCell(database, columnType = CellColumnType.of(poolType), gameName = gameName)
        val task =
            aTask(
                name = name,
                poolType = poolType,
                trackingMode =
                    when (poolType) {
                        PoolType.THREE_D -> TrackingMode.THREE_D_BATCH
                        PoolType.CARD, PoolType.BOARD -> TrackingMode.PIPELINE
                        PoolType.SPECIAL -> TrackingMode.COUNTED
                    },
                requiredQuantity = requiredQuantity,
            )
        database.taskDao().addTaskToCell(task, cell.id, ids.newId(), createdAt)
        return task.id
    }

    private suspend fun gameOf(taskId: EntityId): EntityId = assertNotNull(database.taskDao().gameIdOfTask(taskId))

    private suspend fun kindsOf(taskId: EntityId) = history.eventsOfTask(taskId).map { it.kind }

    // ------------------------------------------------------- pipeline movements

    @Test
    fun `raising a card step records the move it made`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 60)

            progress.setStageQuantity(taskId, ProductionStage.PRINT, 12, clock)

            val event = history.eventsOfTask(taskId).single()
            assertEquals(HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED, event.kind)
            assertEquals(ProductionStage.PRINT, event.stage)
            assertEquals(0, event.previousQuantity)
            assertEquals(12, event.newQuantity)
            assertEquals(gameOf(taskId), event.gameId)
        }

    @Test
    fun `lowering a card step records the move it made`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 60)
            progress.setStageQuantity(taskId, ProductionStage.PRINT, 12, clock)

            progress.setStageQuantity(taskId, ProductionStage.PRINT, 5, clock)

            val event = history.eventsOfTask(taskId).last()
            assertEquals(12, event.previousQuantity, "the move was recorded from the wrong place")
            assertEquals(5, event.newQuantity)
        }

    @Test
    fun `a board step records itself the same way`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.BOARD, requiredQuantity = 16)

            progress.setStageQuantity(taskId, ProductionStage.PRINT, 16, clock)

            val event = history.eventsOfTask(taskId).first()
            assertEquals(ProductionStage.PRINT, event.stage)
            assertEquals(0 to 16, event.previousQuantity to event.newQuantity)
        }

    @Test
    fun `several steps moved at once are several movements and not one`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 60)

            progress.setStageQuantities(
                taskId,
                mapOf(ProductionStage.PRINT to 60, ProductionStage.LAMINATE to 30),
                clock,
            )

            // Read as a set of movements rather than a sequence: one save is one
            // moment, so the lines it writes are not in any order with respect to
            // each other — there is none to record and none to show.
            val moves = history.eventsOfTask(taskId).filter { it.kind == HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED }
            assertEquals(
                mapOf<ProductionStage?, Pair<Int?, Int?>>(
                    ProductionStage.PRINT to (0 to 60),
                    ProductionStage.LAMINATE to (0 to 30),
                ),
                moves.associate { it.stage to (it.previousQuantity to it.newQuantity) },
            )
        }

    @Test
    fun `a step set to what it already stands at records nothing and reads no clock`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 60)
            progress.setStageQuantity(taskId, ProductionStage.PRINT, 12, clock)
            val readsBefore = clock.reads
            val before = history.eventsOfTask(taskId)

            assertTrue(!progress.setStageQuantity(taskId, ProductionStage.PRINT, 12, clock), "a no-op claimed to change something")

            assertEquals(before, history.eventsOfTask(taskId), "a no-op wrote history")
            assertEquals(readsBefore, clock.reads, "a no-op read the clock")
        }

    @Test
    fun `a step that is refused records nothing`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 60)

            assertFailsWith<TaskProgressException> {
                progress.setStageQuantity(taskId, ProductionStage.PRINT, -1, clock)
            }
            assertFailsWith<TaskProgressException> {
                progress.setStageQuantity(taskId, ProductionStage.PRINT, 61, clock)
            }
            // A later step may not stand above an earlier one.
            assertFailsWith<TaskProgressException> {
                progress.setStageQuantity(taskId, ProductionStage.CUT, 5, clock)
            }

            assertEquals(emptyList(), history.eventsOfTask(taskId), "a refused move was recorded")
        }

    @Test
    fun `a pipeline whose history cannot be written is not moved either`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 60)
            val taken = ids.newId()
            progress.setStageQuantity(taskId, ProductionStage.PRINT, 5, clock, IdGenerator { taken })

            // The same identity again: the insert aborts, and with it everything
            // the transaction had done to the pipeline.
            assertFailsWith<Exception> {
                progress.setStageQuantity(taskId, ProductionStage.PRINT, 40, clock, IdGenerator { taken })
            }

            assertEquals(
                5,
                progress.stagesOfTask(taskId).first { it.stage == ProductionStage.PRINT }.completedQuantity,
                "the pipeline moved without its history",
            )
            assertEquals(1, history.eventsOfTask(taskId).size)
        }

    @Test
    fun `a pipeline counted all the way up records the movements and the finish`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 4)

            progress.setStageQuantities(
                taskId,
                mapOf(ProductionStage.PRINT to 4, ProductionStage.LAMINATE to 4, ProductionStage.CUT to 4),
                clock,
            )

            // Counted rather than listed: everything one transaction writes shares
            // its moment, so within a single save there is no order between the
            // lines to assert — and none to show the user either.
            assertEquals(
                mapOf(
                    HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED to 3,
                    HistoryEventKind.TASK_COMPLETED to 1,
                ),
                kindsOf(taskId).groupingBy { it }.eachCount(),
            )
        }

    @Test
    fun `a step dropping back below the total reopens the task and says so`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 4)
            progress.setStageQuantities(
                taskId,
                mapOf(ProductionStage.PRINT to 4, ProductionStage.LAMINATE to 4, ProductionStage.CUT to 4),
                clock,
            )

            progress.setStageQuantity(taskId, ProductionStage.CUT, 1, clock)

            assertEquals(
                setOf(HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED, HistoryEventKind.TASK_REOPENED),
                kindsOf(taskId).takeLast(2).toSet(),
            )
        }

    // ------------------------------------------------------ finishing and reopening

    @Test
    fun `finishing a task by hand records it`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.SPECIAL, requiredQuantity = null)

            progress.completeTask(taskId, clock, ids)

            val event = history.eventsOfTask(taskId).single()
            assertEquals(HistoryEventKind.TASK_COMPLETED, event.kind)
            assertEquals(gameOf(taskId), event.gameId)
            assertNull(event.stage, "a finish was given pipeline detail")
        }

    @Test
    fun `the finish is recorded at the moment the task says it finished`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.SPECIAL, requiredQuantity = null)

            progress.completeTask(taskId, clock, ids)

            val task = assertNotNull(progress.taskById(taskId))
            assertEquals(task.completedAt, history.eventsOfTask(taskId).single().occurredAt)
        }

    @Test
    fun `reopening a task records it`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.SPECIAL, requiredQuantity = null)
            progress.completeTask(taskId, clock, ids)

            progress.reopenTask(taskId, clock)

            assertEquals(listOf(HistoryEventKind.TASK_COMPLETED, HistoryEventKind.TASK_REOPENED), kindsOf(taskId))
        }

    @Test
    fun `finishing a task that is already finished records nothing more`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.SPECIAL, requiredQuantity = null)
            progress.completeTask(taskId, clock, ids)
            val readsBefore = clock.reads

            assertTrue(!progress.completeTask(taskId, clock, ids))

            assertEquals(1, history.eventsOfTask(taskId).size, "a second finish was recorded")
            assertEquals(readsBefore, clock.reads, "a no-op read the clock")
        }

    @Test
    fun `reopening a task that is already open records nothing`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            val readsBefore = clock.reads

            assertTrue(!progress.reopenTask(taskId, clock))

            assertEquals(emptyList(), history.eventsOfTask(taskId))
            assertEquals(readsBefore, clock.reads)
        }

    @Test
    fun `a print run that finishes the task records the finish`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)

            progress.completePrimaryBatch(taskId, clock)

            assertEquals(listOf(HistoryEventKind.TASK_COMPLETED), kindsOf(taskId))
        }

    @Test
    fun `a print run on a task that still owes something records no finish`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.reportFailure(ids.newId(), taskId, 4, clock)
            val before = history.eventsOfTask(taskId)

            progress.completePrimaryBatch(taskId, clock)

            assertEquals(before, history.eventsOfTask(taskId), "an unfinished task was recorded as finished")
        }

    @Test
    fun `settling the last of a shortage records both the shortage and the finish`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.reportFailure(ids.newId(), taskId, 4, clock)

            progress.resolveShortage(ids.newId(), taskId, 4, clock)

            // The shortage arithmetic stays where it was, and the finish is the
            // new line: two tables, two identities, one transaction.
            assertEquals(2, progress.progressEventsOfTask(taskId).size)
            assertEquals(listOf(HistoryEventKind.TASK_COMPLETED), kindsOf(taskId))
            assertTrue(
                progress.progressEventsOfTask(taskId).none { event ->
                    event.id in history.eventsOfTask(taskId).map { it.id }
                },
                "a shortage and a history line were given the same identity",
            )
        }

    @Test
    fun `settling only part of a shortage records nothing`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.reportFailure(ids.newId(), taskId, 4, clock)

            progress.resolveShortage(ids.newId(), taskId, 1, clock)

            assertEquals(emptyList(), history.eventsOfTask(taskId))
        }

    @Test
    fun `a shortage on a finished task records the reopening`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.completePrimaryBatch(taskId, clock)

            progress.reportFailure(ids.newId(), taskId, 2, clock)

            assertEquals(listOf(HistoryEventKind.TASK_COMPLETED, HistoryEventKind.TASK_REOPENED), kindsOf(taskId))
        }

    @Test
    fun `a shortage on a task that was already open records nothing`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)

            progress.reportFailure(ids.newId(), taskId, 2, clock)

            assertEquals(emptyList(), history.eventsOfTask(taskId), "an open task was recorded as reopened")
        }

    @Test
    fun `reporting the same shortage twice records one reopening`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            progress.completePrimaryBatch(taskId, clock)
            val eventId = ids.newId()

            progress.reportFailure(eventId, taskId, 2, clock)
            assertTrue(!progress.reportFailure(eventId, taskId, 2, clock), "a retry was taken for a new report")

            assertEquals(1, history.eventsOfTask(taskId).count { it.kind == HistoryEventKind.TASK_REOPENED })
        }

    @Test
    fun `a card task finishing records the same kind as a 3D one`() =
        runBlocking<Unit> {
            val card = aTaskIn(PoolType.CARD, requiredQuantity = 2)
            val board = aTaskIn(PoolType.BOARD, requiredQuantity = 2)
            val special = aTaskIn(PoolType.SPECIAL, requiredQuantity = null)

            listOf(card, board).forEach { taskId ->
                progress.setStageQuantities(
                    taskId,
                    ProductionStage.entries.associateWith { 2 }.filterKeys { it in stagesForTask(taskId) },
                    clock,
                )
            }
            progress.completeTask(special, clock, ids)

            listOf(card, board, special).forEach { taskId ->
                assertEquals(
                    1,
                    kindsOf(taskId).count { it == HistoryEventKind.TASK_COMPLETED },
                    "a task of one pool recorded its finish differently",
                )
            }
        }

    // ----------------------------------------------------- finishing a whole game

    @Test
    fun `finishing a game records only the tasks that really finished`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database, gameName = "Wingspan")
            val first = aTask(name = "Bir", requiredQuantity = 4)
            val second = aTask(name = "İki", requiredQuantity = 4)
            val already = aTask(name = "Zaten", requiredQuantity = 4)
            listOf(first, second, already).forEach { database.taskDao().addTaskToCell(it, cell.id, ids.newId(), createdAt) }
            progress.completeTask(already.id, clock, ids)

            progress.completeGame(cell.gameId, clock, ids)

            assertEquals(listOf(HistoryEventKind.TASK_COMPLETED), kindsOf(first.id))
            assertEquals(listOf(HistoryEventKind.TASK_COMPLETED), kindsOf(second.id))
            assertEquals(1, kindsOf(already.id).size, "a task that was already finished was recorded again")
        }

    @Test
    fun `finishing a game records every task against that game`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database, gameName = "Wingspan")
            val task = aTask(name = "Bir", requiredQuantity = 4)
            database.taskDao().addTaskToCell(task, cell.id, ids.newId(), createdAt)

            progress.completeGame(cell.gameId, clock, ids)

            assertEquals(cell.gameId, history.eventsOfGame(cell.gameId).single().gameId)
        }

    @Test
    fun `a bulk finish that runs out of identities changes nothing at all`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database, gameName = "Wingspan")
            val tasks = (0 until 3).map { aTask(name = "Görev $it", requiredQuantity = 4) }
            tasks.forEach { database.taskDao().addTaskToCell(it, cell.id, ids.newId(), createdAt) }
            tasks.forEach { progress.reportFailure(ids.newId(), it.id, 1, clock) }
            val eventsBefore = tasks.associate { it.id to progress.progressEventsOfTask(it.id).size }

            // Three settlings and three finishes are six names; four is not enough.
            assertFailsWith<IllegalStateException> { progress.completeGame(cell.gameId, clock, LimitedIds(4)) }

            assertEquals(emptyList(), history.allEvents(), "a half finished game left history behind")
            assertEquals(eventsBefore, tasks.associate { it.id to progress.progressEventsOfTask(it.id).size })
            tasks.forEach { assertTrue(!assertNotNull(progress.taskById(it.id)).isCompleted, "a task was finished anyway") }
            assertTrue(!assertNotNull(database.gameDao().gameByIdIncludingDeleted(cell.gameId)).isManuallyCompleted)
        }

    @Test
    fun `a bulk finish whose history will not write leaves the game untouched`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database, gameName = "Wingspan")
            val tasks = (0 until 2).map { aTask(name = "Görev $it", requiredQuantity = 4) }
            tasks.forEach { database.taskDao().addTaskToCell(it, cell.id, ids.newId(), createdAt) }
            val taken = ids.newId()

            // Both tasks are handed the same name, so the second insert aborts.
            assertFailsWith<Exception> { progress.completeGame(cell.gameId, clock, IdGenerator { taken }) }

            assertEquals(emptyList(), history.allEvents())
            tasks.forEach { assertTrue(!assertNotNull(progress.taskById(it.id)).isCompleted) }
            assertTrue(!assertNotNull(database.gameDao().gameByIdIncludingDeleted(cell.gameId)).isManuallyCompleted)
        }

    // ----------------------------------------------------------- deleting a record

    @Test
    fun `deleting a task records it against its game at the same moment`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            val gameId = gameOf(taskId)

            database.taskDao().softDelete(taskId, deletedAt)

            val event = history.eventsOfTask(taskId).single()
            assertEquals(HistoryEventKind.TASK_DELETED, event.kind)
            assertEquals(gameId, event.gameId)
            assertEquals(deletedAt, event.occurredAt, "the tombstone and its record disagree about when")
            assertEquals(deletedAt, assertNotNull(database.taskDao().taskByIdIncludingDeleted(taskId)).deletedAt)
        }

    @Test
    fun `deleting a game records it against itself`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database, gameName = "Wingspan")

            database.gameDao().softDelete(cell.gameId, deletedAt)

            val event = history.eventsOfGame(cell.gameId).single()
            assertEquals(HistoryEventKind.GAME_DELETED, event.kind)
            assertNull(event.taskId, "a game's own removal named a task")
            assertEquals(deletedAt, event.occurredAt)
        }

    @Test
    fun `deleting a game does not pretend to delete its tasks`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database, gameName = "Wingspan")
            val task = aTask(name = "Bir")
            database.taskDao().addTaskToCell(task, cell.id, ids.newId(), createdAt)

            database.gameDao().softDelete(cell.gameId, deletedAt)

            assertEquals(emptyList(), history.eventsOfTask(task.id), "a task nobody deleted was recorded as deleted")
        }

    @Test
    fun `deleting twice records once`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.THREE_D)
            val cell = insertGameAndCell(database, gameName = "Yalnız")
            database.taskDao().softDelete(taskId, deletedAt)
            database.gameDao().softDelete(cell.gameId, deletedAt)

            assertEquals(0, database.taskDao().softDelete(taskId, updatedAt))
            assertEquals(0, database.gameDao().softDelete(cell.gameId, updatedAt))

            assertEquals(1, history.eventsOfTask(taskId).size)
            assertEquals(1, history.eventsOfGame(cell.gameId).size)
        }

    @Test
    fun `a deletion whose record will not write does not happen at all`() =
        runBlocking<Unit> {
            val first = aTaskIn(PoolType.THREE_D, name = "Bir")
            val second = aTaskIn(PoolType.THREE_D, name = "İki")
            val taken = ids.newId()
            database.taskDao().softDelete(first, deletedAt, taken)

            assertFailsWith<Exception> { database.taskDao().softDelete(second, deletedAt, taken) }

            assertNull(
                assertNotNull(database.taskDao().taskByIdIncludingDeleted(second)).deletedAt,
                "the tombstone was written without its record",
            )
        }

    // ------------------------------------------------------ what may not be done

    @Test
    fun `no dao anywhere can rewrite or remove a line once written`() {
        // Appending is the whole of what this table supports (PLAN 5.12), and the
        // way that is kept true is that no statement to do anything else exists.
        // Read off the sources because it is a claim about what the application
        // *can* do, not about what one call happened to do; the behaviour that a
        // real write only ever inserts is counted at the driver in
        // HistoryQueryCountTest.
        val offenders =
            daoSources()
                .filter { (_, text) ->
                    val statements = text.uppercase()
                    "UPDATE HISTORY_EVENTS" in statements || "DELETE FROM HISTORY_EVENTS" in statements
                }.map { (path, _) -> path.fileName.toString() }

        assertEquals(emptyList(), offenders, "a dao grew a way to rewrite the past")
    }

    @Test
    fun `a written line stays exactly as it was written`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.SPECIAL, requiredQuantity = null)
            progress.completeTask(taskId, clock, ids)
            val before = history.allEvents()

            progress.reopenTask(taskId, clock)
            progress.completeTask(taskId, clock, ids)

            assertEquals(before, history.allEvents().take(before.size), "an earlier line was rewritten")
            assertEquals(3, history.allEvents().size)
        }

    @Test
    fun `renaming a task leaves its history alone and still reaches it`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.SPECIAL, requiredQuantity = null, name = "Eski ad")
            progress.completeTask(taskId, clock, ids)
            val before = history.eventsOfTask(taskId)

            database.taskEditDao().editTask(
                taskId = taskId,
                name = "Yeni ad",
                colorIds = emptyList(),
                requiredQuantity = null,
                notes = null,
                trackingMode = TrackingMode.COUNTED,
                flags = null,
                clock = clock,
            )

            assertEquals(before, history.eventsOfTask(taskId), "renaming rewrote the past")
            // The screen reads the name off the task the line points at, so the
            // new name is what it will show. Nothing is snapshotted.
            assertEquals("Yeni ad", assertNotNull(database.taskDao().activeTaskById(taskId)).name)
        }

    @Test
    fun `two identical submissions make one change and one line`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 60)
            val snapshot = progress.stagesOfTask(taskId).associate { it.stage to it.completedQuantity }

            progress.setStageQuantities(
                taskId,
                mapOf(ProductionStage.PRINT to 20),
                clock,
                expected = StageSnapshot(60, snapshot),
            )
            // The second submission carries the picture the panel was opened with,
            // which is no longer the picture in the database.
            assertFailsWith<TaskProgressException> {
                progress.setStageQuantities(
                    taskId,
                    mapOf(ProductionStage.PRINT to 20),
                    clock,
                    expected = StageSnapshot(60, snapshot),
                )
            }

            assertEquals(1, history.eventsOfTask(taskId).size)
        }

    @Test
    fun `the whole history of one game comes back in the order it happened`() =
        runBlocking<Unit> {
            val cell = insertGameAndCell(database, columnType = CellColumnType.CARD, gameName = "Wingspan")
            val task = aTask(name = "Kart", poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE, requiredQuantity = 2)
            database.taskDao().addTaskToCell(task, cell.id, ids.newId(), createdAt)

            progress.setStageQuantity(task.id, ProductionStage.PRINT, 1, clock)
            progress.setStageQuantity(task.id, ProductionStage.PRINT, 2, clock)
            progress.completeTask(task.id, clock, ids)
            progress.reopenTask(task.id, clock)

            assertEquals(
                listOf(
                    HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED,
                    HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED,
                    HistoryEventKind.TASK_COMPLETED,
                    HistoryEventKind.TASK_REOPENED,
                ),
                history.eventsOfGame(cell.gameId).map { it.kind },
            )
        }

    @Test
    fun `every line of a task's history names that task's game`() =
        runBlocking<Unit> {
            val taskId = aTaskIn(PoolType.CARD, requiredQuantity = 2)
            progress.setStageQuantities(
                taskId,
                mapOf(ProductionStage.PRINT to 2, ProductionStage.LAMINATE to 2, ProductionStage.CUT to 2),
                clock,
            )
            progress.reopenTask(taskId, clock)

            val gameId = gameOf(taskId)
            assertTrue(history.eventsOfTask(taskId).isNotEmpty())
            assertTrue(
                history.eventsOfTask(taskId).all { it.gameId == gameId },
                "a line was filed under a game the task is not in",
            )
        }

    private suspend fun stagesForTask(taskId: EntityId): Set<ProductionStage> = progress.stagesOfTask(taskId).map { it.stage }.toSet()

    /** Every dao source file, so a claim about the whole surface can be checked. */
    private fun daoSources(): List<Pair<Path, String>> {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .map { it.resolve("src/commonMain/kotlin/dev/pnptracker/data/database/dao") }
                .firstOrNull { Files.isDirectory(it) }
                ?.let { directory ->
                    return Files.list(directory).use { paths ->
                        paths.toList().filter { it.toString().endsWith(".kt") }.map { it to Files.readString(it) }
                    }
                }
            candidate = candidate.parent
        }
        fail("Could not locate the dao sources from ${Path.of("").toAbsolutePath()}")
    }
}
