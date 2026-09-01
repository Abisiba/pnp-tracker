package dev.pnptracker.data.database

import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.domain.games.GameCompletionSnapshot
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.ProgressEventKind
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskProgressException
import dev.pnptracker.domain.tasks.TaskProgressFailure
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
 * Finishing a whole game, against a real database (PLAN 12.9).
 *
 * The question these answer is not "was the game marked" but "what happened to
 * everything under it, and did any of it happen without the rest". So each one
 * finishes a game holding real work and reads back the tasks, the stages, the
 * history and the game itself — because a bulk completion that wrote four of
 * those five would look, from the game row alone, exactly like one that wrote
 * all of them.
 */
class GameCompletionTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    /** Fixed, and counting, so a write nobody made is a write nobody timed. */
    private class CountingClock(
        private val fixed: Instant,
    ) : Clock {
        var reads: Int = 0
            private set

        override fun now(): Instant {
            reads++
            return fixed
        }
    }

    /** Hands out names, and refuses after the [afterwards]-th one. */
    private class LimitedIdGenerator(
        private val afterwards: Int,
    ) : IdGenerator {
        var handed: Int = 0
            private set

        override fun newId(): EntityId {
            if (handed >= afterwards) throw IllegalStateException("no more names")
            handed++
            return IdGenerator.Random.newId()
        }
    }

    /** A clock that will not say what time it is. */
    private class BrokenClock : Clock {
        override fun now(): Instant = throw IllegalStateException("the clock refused to answer")
    }

    private lateinit var clock: CountingClock

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        clock = CountingClock(updatedAt)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val progress get() = database.taskProgressDao()

    // ------------------------------------------------------------- fixtures

    /** A game with one cell per column it needs, ready to be written into. */
    private class Fixture(
        val gameId: EntityId,
        val cells: MutableMap<CellColumnType, EntityId> = mutableMapOf(),
    )

    private suspend fun aGameCalled(name: String = "Harmonies"): Fixture {
        val game = aGame(name = "$name ${IdGenerator.Random.newId()}")
        database.gameDao().insert(game)
        return Fixture(game.id)
    }

    /**
     * Writes one task into the game, opening its column's cell the first time.
     *
     * A game has at most one cell per column (PLAN 5.4), so the cell is reused;
     * asking for a second would be a shape the database itself refuses.
     */
    private suspend fun Fixture.writing(
        poolType: PoolType = PoolType.THREE_D,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        name: String = "Gri token",
        requiredQuantity: Int? = 20,
    ): TaskEntity {
        val columnType = CellColumnType.of(poolType)
        val cellId =
            cells.getOrPut(columnType) {
                val cell = aCell(gameId = gameId, columnType = columnType)
                database.gameCellDao().insert(cell)
                cell.id
            }
        val task =
            aTask(
                poolType = poolType,
                trackingMode = trackingMode,
                name = name,
                requiredQuantity = requiredQuantity,
            )
        database.taskDao().addTaskToCell(task, cellId, IdGenerator.Random.newId(), createdAt)
        return task
    }

    /** One of the base colours the database is seeded with. */
    private suspend fun colorId(name: String): EntityId = assertNotNull(database.colorDao().resolve(name)).id

    private suspend fun taskById(taskId: EntityId): TaskEntity = assertNotNull(progress.taskById(taskId))

    private suspend fun pipelineOf(taskId: EntityId): List<Int> = progress.stagesOfTask(taskId).map { it.completedQuantity }

    private suspend fun gameById(gameId: EntityId) = assertNotNull(database.gameDao().gameByIdIncludingDeleted(gameId))

    /**
     * The game as it stands, which is the picture a confirmation would carry.
     *
     * The application's own read, so the tests here answer with exactly what the
     * screen would have been given rather than with a second idea of it.
     */
    private suspend fun snapshotOf(gameId: EntityId): GameCompletionSnapshot =
        assertNotNull(progress.gameCompletionSnapshot(gameId), "there is no such game to ask about")

    private suspend fun finish(
        gameId: EntityId,
        clock: Clock = this.clock,
        idGenerator: IdGenerator = IdGenerator.Random,
        expected: GameCompletionSnapshot? = null,
    ): Boolean = progress.completeGame(gameId = gameId, clock = clock, idGenerator = idGenerator, expected = expected)

    // ------------------------------------------------- what finishing means

    @Test
    fun `a game with no tasks in it is finished on its own`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()

            assertTrue(finish(fixture.gameId))

            val game = gameById(fixture.gameId)
            assertTrue(game.isManuallyCompleted)
            assertEquals(updatedAt, game.completedAt)
        }

    @Test
    fun `a game whose tasks are all finished already is finished without touching them`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val first = fixture.writing(name = "Gri token")
            val second = fixture.writing(name = "Mavi token")
            progress.completeTask(first.id, clock, IdGenerator.Random)
            progress.completeTask(second.id, clock, IdGenerator.Random)
            val before = listOf(taskById(first.id), taskById(second.id))

            assertTrue(finish(fixture.gameId))

            assertTrue(gameById(fixture.gameId).isManuallyCompleted)
            assertEquals(before, listOf(taskById(first.id), taskById(second.id)), "a finished task was written again")
        }

    @Test
    fun `finishing a game finishes every open task in it`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val open = fixture.writing(name = "Gri token")
            val alsoOpen = fixture.writing(name = "Mavi token")

            assertTrue(finish(fixture.gameId))

            assertTrue(taskById(open.id).isCompleted)
            assertTrue(taskById(alsoOpen.id).isCompleted)
            assertEquals(updatedAt, taskById(open.id).completedAt)
        }

    @Test
    fun `a task already finished keeps the moment it was finished at`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val done = fixture.writing(name = "Gri token")
            val open = fixture.writing(name = "Mavi token")
            progress.completeTask(done.id, StoppedClock(createdAt), IdGenerator.Random)

            assertTrue(finish(fixture.gameId))

            assertEquals(createdAt, taskById(done.id).completedAt, "an untouched task was given a new date")
            assertEquals(updatedAt, taskById(open.id).completedAt)
        }

    @Test
    fun `a 3D task comes out of it with its print run recorded`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing(poolType = PoolType.THREE_D)

            finish(fixture.gameId)

            val finished = taskById(task.id)
            assertTrue(finished.isCompleted)
            assertTrue(finished.primaryBatchCompleted, "PLAN 6.2's one print run was never recorded")
            assertEquals(0, finished.currentMissingQuantity)
        }

    @Test
    fun `a card pipeline is counted all the way up`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            progress.setStageQuantities(
                task.id,
                mapOf(ProductionStage.PRINT to 15, ProductionStage.LAMINATE to 10, ProductionStage.CUT to 5),
                clock,
            )

            finish(fixture.gameId)

            assertEquals(listOf(20, 20, 20), pipelineOf(task.id))
            assertTrue(taskById(task.id).isCompleted)
        }

    @Test
    fun `a board pipeline is counted all the way up`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing(poolType = PoolType.BOARD, trackingMode = TrackingMode.PIPELINE)
            progress.setStageQuantities(task.id, mapOf(ProductionStage.PRINT to 12), clock)

            finish(fixture.gameId)

            assertEquals(listOf(20, 20, 20), pipelineOf(task.id))
            assertTrue(taskById(task.id).isCompleted)
        }

    @Test
    fun `a pipeline nobody has started is counted all the way up too`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            assertEquals(listOf(0, 0, 0), pipelineOf(task.id))

            finish(fixture.gameId)

            assertEquals(listOf(20, 20, 20), pipelineOf(task.id))
        }

    @Test
    fun `a special task counted by a checklist is finished`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task =
                fixture.writing(
                    poolType = PoolType.SPECIAL,
                    trackingMode = TrackingMode.CHECKLIST,
                    requiredQuantity = null,
                )

            finish(fixture.gameId)

            assertTrue(taskById(task.id).isCompleted)
            assertEquals(emptyList(), pipelineOf(task.id), "a special task was given a pipeline")
        }

    @Test
    fun `a special task counted by a number is finished`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing(poolType = PoolType.SPECIAL, trackingMode = TrackingMode.COUNTED)

            finish(fixture.gameId)

            assertTrue(taskById(task.id).isCompleted)
        }

    @Test
    fun `the independent tasks of a batch are finished one by one and share nothing`() =
        runBlocking<Unit> {
            // PLAN 12.7: making several at once writes no bond between them, so
            // finishing the game finishes each on its own terms.
            val fixture = aGameCalled()
            val made = (1..3).map { fixture.writing(name = "Token $it") }

            finish(fixture.gameId)

            made.forEach { task ->
                val finished = taskById(task.id)
                assertTrue(finished.isCompleted, "${task.name} was left open")
                assertEquals(updatedAt, finished.completedAt)
            }
        }

    @Test
    fun `a task made in several colours is finished once and keeps its colours`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing(name = "Ev")
            val colors = listOf("Gri", "Mavi", "Yeşil").map { colorId(it) }
            colors.forEach { database.taskColorDao().addColorToTask(task.id, it) }

            finish(fixture.gameId)

            assertTrue(taskById(task.id).isCompleted)
            assertEquals(colors, database.taskColorDao().colorsOfTask(task.id).map { it.colorId }, "the colours moved")
        }

    @Test
    fun `a debt is settled by exactly one event per task`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val owing = fixture.writing(name = "Gri token")
            val alsoOwing = fixture.writing(name = "Mavi token")
            progress.reportFailure(IdGenerator.Random.newId(), owing.id, quantity = 3, clock = clock)
            progress.reportFailure(IdGenerator.Random.newId(), alsoOwing.id, quantity = 2, clock = clock)

            finish(fixture.gameId)

            listOf(owing.id to 3, alsoOwing.id to 2).forEach { (taskId, owed) ->
                assertEquals(0, taskById(taskId).currentMissingQuantity)
                val settlings = progress.progressEventsOfTask(taskId).filter { it.kind == ProgressEventKind.SHORTAGE_RESOLVED }
                assertEquals(1, settlings.size, "the debt was settled more than once or not at all")
                assertEquals(owed, settlings.single().quantity)
            }
        }

    @Test
    fun `what was ever reported failed survives the game being finished`() =
        runBlocking<Unit> {
            // PLAN 6.2: the failure total is a sum over the history, and settling
            // is a new event rather than the removal of an old one.
            val fixture = aGameCalled()
            val task = fixture.writing()
            progress.reportFailure(IdGenerator.Random.newId(), task.id, quantity = 4, clock = clock)

            finish(fixture.gameId)

            assertEquals(4L, progress.failureTotalOf(task.id))
            assertEquals(4L, progress.resolvedTotalOf(task.id))
        }

    @Test
    fun `the game, its tasks and its events are all written at the one moment`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val owing = fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            progress.reportFailure(IdGenerator.Random.newId(), owing.id, quantity = 2, clock = StoppedClock(createdAt))

            finish(fixture.gameId)

            val moments =
                buildList {
                    add(gameById(fixture.gameId).completedAt)
                    add(gameById(fixture.gameId).updatedAt)
                    add(taskById(owing.id).completedAt)
                    add(taskById(owing.id).updatedAt)
                    addAll(progress.stagesOfTask(owing.id).map { it.updatedAt })
                    add(progress.progressEventsOfTask(owing.id).last { it.kind == ProgressEventKind.SHORTAGE_RESOLVED }.recordedAt)
                }
            assertEquals(setOf(updatedAt), moments.toSet(), "one transaction wrote several times: $moments")
        }

    // ------------------------------------------------------ what it will not do

    @Test
    fun `a game already finished is left exactly as it was`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val open = fixture.writing()
            database.gameDao().setManuallyCompleted(fixture.gameId, isCompleted = true, completedAt = createdAt, updatedAt = createdAt)
            val before = gameById(fixture.gameId)

            assertFalse(finish(fixture.gameId), "finishing a finished game claimed to have done something")

            assertEquals(before, gameById(fixture.gameId), "the date the user set was moved")
            assertFalse(taskById(open.id).isCompleted, "a no-op finished the game's work")
        }

    @Test
    fun `a game already finished never asks what time it is, or for a name`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            fixture.writing()
            progress.reportFailure(IdGenerator.Random.newId(), fixture.writing(name = "Mavi").id, quantity = 2, clock = clock)
            database.gameDao().setManuallyCompleted(fixture.gameId, isCompleted = true, completedAt = createdAt, updatedAt = createdAt)
            val counting = CountingClock(updatedAt)
            val names = LimitedIdGenerator(afterwards = 0)

            assertFalse(finish(fixture.gameId, clock = counting, idGenerator = names))

            assertEquals(0, counting.reads, "a save that changed nothing moved a timestamp")
            assertEquals(0, names.handed, "a save that changed nothing spent a name")
        }

    @Test
    fun `a deleted task is neither counted nor written`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val kept = fixture.writing(name = "Gri token")
            val gone = fixture.writing(name = "Mavi token")
            database.taskDao().softDelete(gone.id, deletedAt)

            assertEquals(listOf(kept.id), progress.workableTasksOfGame(fixture.gameId).map { it.id })
            finish(fixture.gameId)

            assertTrue(taskById(kept.id).isCompleted)
            assertFalse(taskById(gone.id).isCompleted, "a deleted task was finished with the game")
        }

    @Test
    fun `a deleted game is refused and nothing is written`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing()
            database.gameDao().softDelete(fixture.gameId, deletedAt)
            val before = taskById(task.id)

            val refusal = assertFailsWith<TaskProgressException> { finish(fixture.gameId) }

            assertEquals(TaskProgressFailure.GAME_NOT_AVAILABLE, refusal.failure)
            assertEquals(before, taskById(task.id))
            assertFalse(gameById(fixture.gameId).isManuallyCompleted)
        }

    @Test
    fun `a game that never existed is refused`() =
        runBlocking<Unit> {
            val refusal = assertFailsWith<TaskProgressException> { finish(IdGenerator.Random.newId()) }

            assertEquals(TaskProgressFailure.GAME_NOT_AVAILABLE, refusal.failure)
        }

    @Test
    fun `plain text in a game's cells decides nothing about finishing it`() =
        runBlocking<Unit> {
            // PLAN 5.5: a cell is text with tasks written among it. The words are
            // not work, so a game holding nothing but words is a game with
            // nothing unfinished in it.
            val fixture = aGameCalled()
            database.cellSegmentDao().saveDocumentText(
                gameId = fixture.gameId,
                columnType = CellColumnType.NOTES,
                expectedDocumentText = "",
                newDocumentText = "Yarın devam",
                clock = StoppedClock(createdAt),
                idGenerator = IdGenerator.Random,
            )

            assertEquals(emptyList(), progress.workableTasksOfGame(fixture.gameId))
            assertTrue(finish(fixture.gameId))
            assertEquals(0, snapshotOf(fixture.gameId).unfinishedCount)
        }

    // --------------------------------------------------- the picture it was asked about

    @Test
    fun `an answer given about the game as it stands is applied`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            fixture.writing()
            val asked = snapshotOf(fixture.gameId)

            assertTrue(finish(fixture.gameId, expected = asked))
        }

    @Test
    fun `an answer about a game whose work has since been finished elsewhere is refused`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val first = fixture.writing(name = "Gri token")
            val second = fixture.writing(name = "Mavi token")
            val asked = snapshotOf(fixture.gameId)
            assertEquals(2, asked.unfinishedCount)
            progress.completeTask(first.id, StoppedClock(createdAt), IdGenerator.Random)

            val refusal = assertFailsWith<TaskProgressException> { finish(fixture.gameId, expected = asked) }

            assertEquals(TaskProgressFailure.STALE_GAME_COMPLETION, refusal.failure)
            assertFalse(gameById(fixture.gameId).isManuallyCompleted)
            assertFalse(taskById(second.id).isCompleted, "a refused answer finished work anyway")
        }

    @Test
    fun `an answer about a game that has since gained a task is refused`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            fixture.writing(name = "Gri token")
            val asked = snapshotOf(fixture.gameId)
            val added = fixture.writing(name = "Mavi token")

            val refusal = assertFailsWith<TaskProgressException> { finish(fixture.gameId, expected = asked) }

            assertEquals(TaskProgressFailure.STALE_GAME_COMPLETION, refusal.failure)
            assertFalse(taskById(added.id).isCompleted)
        }

    @Test
    fun `an answer about a game that owed less than it does now is refused`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing()
            val asked = snapshotOf(fixture.gameId)
            progress.reportFailure(IdGenerator.Random.newId(), task.id, quantity = 5, clock = clock)

            val refusal = assertFailsWith<TaskProgressException> { finish(fixture.gameId, expected = asked) }

            assertEquals(TaskProgressFailure.STALE_GAME_COMPLETION, refusal.failure)
            assertEquals(5, taskById(task.id).currentMissingQuantity, "a refused answer settled a debt")
        }

    @Test
    fun `an answer about a game somebody else has since finished is refused`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            fixture.writing()
            val asked = snapshotOf(fixture.gameId)
            database.gameDao().setManuallyCompleted(fixture.gameId, isCompleted = true, completedAt = createdAt, updatedAt = createdAt)

            val refusal = assertFailsWith<TaskProgressException> { finish(fixture.gameId, expected = asked) }

            // Told what happened, rather than answered with the silence a plain
            // no-op would give: the user asked to finish work and it did not.
            assertEquals(TaskProgressFailure.STALE_GAME_COMPLETION, refusal.failure)
            assertEquals(createdAt, gameById(fixture.gameId).completedAt)
        }

    @Test
    fun `a card whose pipeline moved under the question is refused`() =
        runBlocking<Unit> {
            // The stages are what a bulk completion writes, so a pipeline that
            // moved while the question stood open is a different amount of work
            // being agreed to — and the answer was given about the other one.
            val fixture = aGameCalled()
            val card = fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            val asked = snapshotOf(fixture.gameId)
            progress.setStageQuantities(card.id, mapOf(ProductionStage.PRINT to 9), StoppedClock(createdAt))

            val refusal = assertFailsWith<TaskProgressException> { finish(fixture.gameId, expected = asked) }

            assertEquals(TaskProgressFailure.STALE_GAME_COMPLETION, refusal.failure)
            assertFalse(gameById(fixture.gameId).isManuallyCompleted, "a refused answer finished the game")
            assertEquals(listOf(9, 0, 0), pipelineOf(card.id), "a refused answer wrote the pipeline")
        }

    @Test
    fun `a board whose pipeline moved under the question is refused`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val board = fixture.writing(poolType = PoolType.BOARD, trackingMode = TrackingMode.PIPELINE)
            val asked = snapshotOf(fixture.gameId)
            progress.setStageQuantities(board.id, mapOf(ProductionStage.PRINT to 7), StoppedClock(createdAt))

            val refusal = assertFailsWith<TaskProgressException> { finish(fixture.gameId, expected = asked) }

            assertEquals(TaskProgressFailure.STALE_GAME_COMPLETION, refusal.failure)
            assertEquals(listOf(7, 0, 0), pipelineOf(board.id))
        }

    @Test
    fun `a pipeline that moved leaves nothing written, no time read and no name spent`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val card = fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            val other = fixture.writing(name = "Gri token")
            progress.reportFailure(IdGenerator.Random.newId(), other.id, quantity = 3, clock = StoppedClock(createdAt))
            val asked = snapshotOf(fixture.gameId)
            progress.setStageQuantities(card.id, mapOf(ProductionStage.PRINT to 6), StoppedClock(createdAt))
            val before = listOf(gameById(fixture.gameId), taskById(card.id), taskById(other.id))
            val stagesBefore = progress.stagesOfTask(card.id)
            val eventsBefore = progress.progressEventsOfTask(other.id)
            val counting = CountingClock(updatedAt)
            val names = LimitedIdGenerator(afterwards = 0)

            assertFailsWith<TaskProgressException> {
                finish(fixture.gameId, clock = counting, idGenerator = names, expected = asked)
            }

            assertEquals(before, listOf(gameById(fixture.gameId), taskById(card.id), taskById(other.id)))
            assertEquals(stagesBefore, progress.stagesOfTask(card.id))
            assertEquals(eventsBefore, progress.progressEventsOfTask(other.id))
            assertEquals(0, counting.reads, "a refused answer asked what time it was")
            assertEquals(0, names.handed, "a refused answer spent a name")
        }

    @Test
    fun `a stage that has gone, or one that has appeared, is a different game`() =
        runBlocking<Unit> {
            // A pipeline is the rows it has as well as the counts on them. The
            // model gives no way to add or drop one, so the picture is edited
            // instead — which is exactly what a snapshot must be able to notice
            // if it is ever to notice a real one.
            val fixture = aGameCalled()
            fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            val asked = snapshotOf(fixture.gameId)
            val task = asked.tasks.single()

            val missingOne = asked.copy(tasks = listOf(task.copy(stages = task.stages.drop(1))))
            val extraOne =
                asked.copy(
                    tasks = listOf(task.copy(stages = task.stages + task.stages.first().copy(orderIndex = 9))),
                )

            listOf(missingOne, extraOne).forEach { stale ->
                val refusal = assertFailsWith<TaskProgressException> { finish(fixture.gameId, expected = stale) }
                assertEquals(TaskProgressFailure.STALE_GAME_COMPLETION, refusal.failure)
            }
            assertFalse(gameById(fixture.gameId).isManuallyCompleted)
        }

    @Test
    fun `a pipeline reordered under the question is refused`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            val asked = snapshotOf(fixture.gameId)
            val task = asked.tasks.single()
            val reordered =
                asked.copy(
                    tasks = listOf(task.copy(stages = task.stages.map { it.copy(orderIndex = it.orderIndex + 1) })),
                )

            val refusal = assertFailsWith<TaskProgressException> { finish(fixture.gameId, expected = reordered) }

            assertEquals(TaskProgressFailure.STALE_GAME_COMPLETION, refusal.failure)
        }

    @Test
    fun `the same pipeline read back in another order is still the same pipeline`() =
        runBlocking<Unit> {
            // SQLite hands rows over in whatever order it likes. A snapshot that
            // depended on it would refuse a game nobody had touched.
            val fixture = aGameCalled()
            fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            fixture.writing(poolType = PoolType.BOARD, trackingMode = TrackingMode.PIPELINE, name = "Tahta")
            val asked = snapshotOf(fixture.gameId)
            val shuffled =
                asked.copy(tasks = asked.tasks.reversed().map { it.copy(stages = it.stages.reversed()) })

            assertTrue(finish(fixture.gameId, expected = shuffled), "the same game read another way looked changed")
        }

    @Test
    fun `asking again after the pipeline moved finishes the game`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val card = fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            val stale = snapshotOf(fixture.gameId)
            progress.setStageQuantities(card.id, mapOf(ProductionStage.PRINT to 11), StoppedClock(createdAt))
            assertFailsWith<TaskProgressException> { finish(fixture.gameId, expected = stale) }

            // The question is asked again, and this time it is about the game
            // that is really there.
            assertTrue(finish(fixture.gameId, expected = snapshotOf(fixture.gameId)))

            assertTrue(gameById(fixture.gameId).isManuallyCompleted)
            assertEquals(listOf(20, 20, 20), pipelineOf(card.id))
        }

    @Test
    fun `the picture a question is given carries the pipeline it will write`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val card = fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            progress.setStageQuantities(
                card.id,
                mapOf(ProductionStage.PRINT to 15, ProductionStage.LAMINATE to 10, ProductionStage.CUT to 5),
                StoppedClock(createdAt),
            )

            val asked = snapshotOf(fixture.gameId)

            val stages = asked.tasks.single().stages
            assertEquals(
                listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT),
                stages.sortedBy { it.orderIndex }.map { it.stage },
            )
            assertEquals(listOf(15, 10, 5), stages.sortedBy { it.orderIndex }.map { it.completedQuantity })
        }

    @Test
    fun `a game with nothing to ask about still answers the question`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()

            val asked = snapshotOf(fixture.gameId)

            assertEquals(0, asked.unfinishedCount)
            assertFalse(asked.needsConfirmation)
        }

    @Test
    fun `there is nothing to ask about a game that is gone`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            fixture.writing()
            database.gameDao().softDelete(fixture.gameId, deletedAt)

            assertNull(progress.gameCompletionSnapshot(fixture.gameId))
        }

    @Test
    fun `the order two readings of one game arrive in does not make them different`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            (1..4).forEach { fixture.writing(name = "Token $it") }
            val asked = snapshotOf(fixture.gameId)

            assertTrue(finish(fixture.gameId, expected = asked.copy(tasks = asked.tasks.reversed())))
        }

    // ------------------------------------------------------------- names and clocks

    @Test
    fun `a name generator that runs out partway writes nothing at all`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val first = fixture.writing(name = "Gri token")
            val second = fixture.writing(name = "Mavi token")
            listOf(first, second).forEach {
                progress.reportFailure(IdGenerator.Random.newId(), it.id, quantity = 2, clock = clock)
            }
            val names = LimitedIdGenerator(afterwards = 1)

            assertFailsWith<IllegalStateException> { finish(fixture.gameId, idGenerator = names) }

            assertFalse(gameById(fixture.gameId).isManuallyCompleted)
            listOf(first, second).forEach { task ->
                assertFalse(taskById(task.id).isCompleted, "${task.name} was finished by a save that fell over")
                assertEquals(2, taskById(task.id).currentMissingQuantity)
                assertTrue(
                    progress.progressEventsOfTask(task.id).none { it.kind == ProgressEventKind.SHORTAGE_RESOLVED },
                    "a settling event outlived the save that wrote it",
                )
            }
        }

    @Test
    fun `a clock that will not answer leaves the whole game as it was`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            val broken = BrokenClock()

            assertFailsWith<IllegalStateException> { finish(fixture.gameId, clock = broken) }

            assertFalse(gameById(fixture.gameId).isManuallyCompleted)
            assertFalse(taskById(task.id).isCompleted)
            assertEquals(listOf(0, 0, 0), pipelineOf(task.id))
        }

    // --------------------------------------------------- shortage reopens the game

    @Test
    fun `a shortage on a task in a finished game reopens both`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val reported = fixture.writing(name = "Gri token")
            val other = fixture.writing(name = "Mavi token")
            finish(fixture.gameId)
            val otherBefore = taskById(other.id)

            val recorded =
                progress.reportFailure(
                    eventId = IdGenerator.Random.newId(),
                    taskId = reported.id,
                    quantity = 2,
                    clock = StoppedClock(deletedAt),
                )

            assertTrue(recorded)
            val task = taskById(reported.id)
            assertFalse(task.isCompleted, "the task stayed finished")
            assertNull(task.completedAt)
            assertEquals(2, task.currentMissingQuantity)
            val game = gameById(fixture.gameId)
            assertFalse(game.isManuallyCompleted, "PLAN 6.3 reopens the game as well")
            assertNull(game.completedAt)
            assertEquals(deletedAt, game.updatedAt)
            assertEquals(otherBefore, taskById(other.id), "PLAN 6.3 leaves the other finished tasks alone")
        }

    @Test
    fun `the shortage is written exactly once and explains the debt`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing()
            finish(fixture.gameId)

            progress.reportFailure(IdGenerator.Random.newId(), task.id, quantity = 3, clock = clock)

            val reports = progress.progressEventsOfTask(task.id).filter { it.kind == ProgressEventKind.FAILURE_REPORTED }
            assertEquals(1, reports.size)
            assertEquals(3, reports.single().quantity)
        }

    @Test
    fun `a shortage leaves the pipeline and the colours of the task it reopens`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            val gray = colorId("Gri")
            database.taskColorDao().addColorToTask(task.id, gray)
            finish(fixture.gameId)
            val stagesBefore = progress.stagesOfTask(task.id)

            progress.reportFailure(IdGenerator.Random.newId(), task.id, quantity = 2, clock = StoppedClock(deletedAt))

            assertEquals(stagesBefore, progress.stagesOfTask(task.id), "a shortage moved the pipeline")
            assertEquals(listOf(gray), database.taskColorDao().colorsOfTask(task.id).map { it.colorId })
        }

    @Test
    fun `a shortage on a task in a game that is not finished writes no game row`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing()
            val before = gameById(fixture.gameId)

            progress.reportFailure(IdGenerator.Random.newId(), task.id, quantity = 2, clock = StoppedClock(deletedAt))

            assertEquals(before, gameById(fixture.gameId), "a game that was already open was written to anyway")
        }

    @Test
    fun `reopening a task by hand leaves its game finished`() =
        runBlocking<Unit> {
            // PLAN 5.3 changes a game's mark by the user's own action or by a
            // shortage, and PLAN 3.5 lets a finished game hold active tasks. So
            // this is an ordinary state and not something to tidy up.
            val fixture = aGameCalled()
            val task = fixture.writing()
            finish(fixture.gameId)

            assertTrue(progress.reopenTask(task.id, StoppedClock(deletedAt)))

            assertFalse(taskById(task.id).isCompleted)
            assertTrue(gameById(fixture.gameId).isManuallyCompleted, "reopening one task reopened the game")
        }

    @Test
    fun `a retry of the same shortage neither reports twice nor reopens twice`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing()
            finish(fixture.gameId)
            val eventId = IdGenerator.Random.newId()
            progress.reportFailure(eventId, task.id, quantity = 2, clock = StoppedClock(deletedAt))
            val after = gameById(fixture.gameId)

            assertFalse(progress.reportFailure(eventId, task.id, quantity = 2, clock = clock))

            assertEquals(2, taskById(task.id).currentMissingQuantity, "a retry counted twice")
            assertEquals(after, gameById(fixture.gameId), "a retry wrote the game again")
        }

    @Test
    fun `the same name given to a different shortage is refused`() =
        runBlocking<Unit> {
            val fixture = aGameCalled()
            val task = fixture.writing()
            finish(fixture.gameId)
            val eventId = IdGenerator.Random.newId()
            progress.reportFailure(eventId, task.id, quantity = 2, clock = StoppedClock(deletedAt))

            val refusal =
                assertFailsWith<TaskProgressException> {
                    progress.reportFailure(eventId, task.id, quantity = 5, clock = clock)
                }

            assertEquals(TaskProgressFailure.EVENT_ID_ALREADY_USED, refusal.failure)
            assertEquals(2, taskById(task.id).currentMissingQuantity)
        }
}
