package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.activePoolTasks
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.insertSegmentDirectly
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskDraft
import kotlinx.coroutines.flow.first
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
 * Turning a task back into the words it was made from, without losing the record.
 *
 * The user's half of this has not changed and is checked elsewhere: the cell says
 * exactly what it said before, and the words stop being a piece of work. What
 * these are about is the half that has: until version 7 the task row and
 * everything hanging off it — its colours, its pipeline, every shortage ever
 * reported against it — were physically deleted. PLAN 385 says that history is
 * never deleted and PLAN 1123 asks the history screen to show conversions, so
 * the task is now soft deleted and the conversion recorded as its own kind of
 * event.
 */
class TaskConversionHistoryTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var editing: TaskEditStore
    private lateinit var creation: TaskFromTextStore
    private var realDatabaseExistedBefore = false

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

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        editing = TaskEditStore(database.taskEditDao(), IdGenerator.Random, StoppedClock(updatedAt))
        creation = TaskFromTextStore(database.taskFromTextDao(), IdGenerator.Random, StoppedClock(updatedAt))
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val history get() = database.historyDao()

    private val progress get() = database.taskProgressDao()

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
    ): CellSegmentEntity {
        val segment =
            CellSegmentEntity.plainText(
                id = IdGenerator.Random.newId(),
                cellId = cellId,
                orderIndex = 0,
                text = text,
                moment = createdAt,
            )
        insertSegmentDirectly(database, segment)
        return segment
    }

    private suspend fun colorNamed(name: String): ColorEntity =
        assertNotNull(database.colorDao().allColors().firstOrNull { it.canonicalName == name })

    private fun selectionOf(
        game: GameEntity,
        cell: GameCellEntity,
        segment: CellSegmentEntity,
        word: String,
    ): CellTextSelection {
        val text = segment.text!!
        return CellTextSelection(
            gameId = game.id,
            cellId = cell.id,
            segmentId = segment.id,
            expectedText = text,
            startOffset = text.indexOf(word),
            endOffset = text.indexOf(word) + word.length,
        )
    }

    /** One task cut out of a stretch of the user's own words. */
    private suspend fun oneTask(
        word: String = "token",
        text: String = "Basılacak: $word ×14, kutu ayrı.",
        columnType: CellColumnType = CellColumnType.THREE_D,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        color: String = "Siyah",
        quantity: Int = 14,
    ): Triple<GameEntity, GameCellEntity, EntityId> {
        val game = addGame()
        val cell = addCell(game.id, columnType)
        val segment = addText(cell.id, text)
        val taskId =
            creation.createSingleColorTask(
                selection = selectionOf(game, cell, segment, word),
                colorId = colorNamed(color).id,
                requiredQuantity = quantity,
                trackingMode = trackingMode,
                notes = null,
            )
        return Triple(game, cell, taskId)
    }

    private suspend fun documentTextOf(cellId: EntityId): String =
        database
            .cellSegmentDao()
            .runsOfCell(cellId)
            .joinToString(separator = "") { run ->
                if (run.kind == SegmentKind.TASK) run.taskName.orEmpty() else run.text.orEmpty()
            }

    private suspend fun segmentsOf(cellId: EntityId) = database.cellSegmentDao().segmentsOfCell(cellId)

    // ------------------------------------------------- what the record looks like

    @Test
    fun `the task is not destroyed, it is taken out of view`() =
        runBlocking<Unit> {
            val (_, _, taskId) = oneTask()

            assertTrue(editing.convertTaskToText(taskId))

            val kept = assertNotNull(database.taskDao().taskByIdIncludingDeleted(taskId), "the record was destroyed")
            assertEquals("token", kept.name)
            assertEquals(14, kept.requiredQuantity)
            assertEquals(updatedAt, kept.deletedAt)
            assertEquals(kept.deletedAt, kept.updatedAt, "the tombstone is not the record's last change")
            assertNull(database.taskDao().activeTaskById(taskId), "the converted task is still active")
        }

    @Test
    fun `the words go back to being words, exactly where they were`() =
        runBlocking<Unit> {
            val (_, cell, taskId) = oneTask()
            val before = documentTextOf(cell.id)

            editing.convertTaskToText(taskId)

            assertEquals(before, documentTextOf(cell.id), "the cell no longer says what it said")
            assertEquals("Basılacak: token ×14, kutu ayrı.", documentTextOf(cell.id))
            assertEquals(listOf(SegmentKind.PLAIN_TEXT), segmentsOf(cell.id).map { it.kind })
            assertEquals(listOf(0), segmentsOf(cell.id).map { it.orderIndex }, "the reading order was left with a gap")
            assertEquals(1, documentTextOf(cell.id).split("token").size - 1, "the name appears more than once")
            assertNull(
                database.cellSegmentDao().segmentOfTaskIncludingDeleted(taskId),
                "the piece of the cell still points at the task",
            )
        }

    @Test
    fun `the surrounding punctuation is kept to the character`() =
        runBlocking<Unit> {
            val (game, cell, first) = oneTask(word = "Knight", text = "Basılacak: Knight ve token")
            val segment = segmentsOf(cell.id).last { it.kind == SegmentKind.PLAIN_TEXT }
            creation.createSingleColorTask(
                selection = selectionOf(game, cell, segment, "token"),
                colorId = colorNamed("Sarı").id,
                requiredQuantity = 150,
                trackingMode = TrackingMode.THREE_D_BATCH,
                notes = null,
            )

            editing.convertTaskToText(first)

            assertEquals("Basılacak: Knight ve token", documentTextOf(cell.id))
            assertEquals(listOf(SegmentKind.PLAIN_TEXT, SegmentKind.TASK), segmentsOf(cell.id).map { it.kind })
            assertEquals(listOf(0, 1), segmentsOf(cell.id).map { it.orderIndex })
        }

    @Test
    fun `the colours it was made in are kept`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "Basılacak: Token, kutu ayrı.")
            val taskIds =
                creation.createTasks(
                    selection = selectionOf(game, cell, segment, "Token"),
                    drafts =
                        listOf("Siyah", "Beyaz", "Sarı").map { name ->
                            TaskDraft(
                                colorIds = listOf(colorNamed(name).id),
                                requiredQuantity = 8,
                                trackingMode = TrackingMode.THREE_D_BATCH,
                                notes = null,
                            )
                        },
                )
            val colorsBefore = database.taskColorDao().colorsOfTask(taskIds.first())

            editing.convertTaskToText(taskIds.first())

            assertEquals(colorsBefore, database.taskColorDao().colorsOfTask(taskIds.first()), "the colours were thrown away")
            assertTrue(colorsBefore.isNotEmpty())
        }

    @Test
    fun `a pipeline and the counts it stood at are kept`() =
        runBlocking<Unit> {
            val (_, _, taskId) =
                oneTask(columnType = CellColumnType.CARD, trackingMode = TrackingMode.PIPELINE, quantity = 60)
            progress.setStageQuantity(taskId, ProductionStage.PRINT, 40, StoppedClock(updatedAt))
            val stagesBefore = progress.stagesOfTask(taskId)

            editing.convertTaskToText(taskId)

            assertEquals(stagesBefore, progress.stagesOfTask(taskId), "the pipeline was thrown away")
            assertEquals(3, stagesBefore.size)
        }

    @Test
    fun `every shortage ever reported is kept`() =
        runBlocking<Unit> {
            val (_, _, taskId) = oneTask()
            progress.reportFailure(IdGenerator.Random.newId(), taskId, 3, StoppedClock(updatedAt), note = "kenar bozuk")
            progress.reportFailure(IdGenerator.Random.newId(), taskId, 2, StoppedClock(updatedAt))
            val eventsBefore = progress.progressEventsOfTask(taskId)
            val totalBefore = progress.failureTotalOf(taskId)

            editing.convertTaskToText(taskId)

            assertEquals(eventsBefore, progress.progressEventsOfTask(taskId), "the shortages were thrown away")
            assertEquals(totalBefore, progress.failureTotalOf(taskId), "the failure total was lost")
            assertEquals(5L, totalBefore)
        }

    @Test
    fun `the conversion is recorded exactly once, and as itself`() =
        runBlocking<Unit> {
            val (game, _, taskId) = oneTask()

            editing.convertTaskToText(taskId)

            val event = history.eventsOfTask(taskId).single()
            assertEquals(HistoryEventKind.TASK_CONVERTED_TO_TEXT, event.kind)
            assertEquals(game.id, event.gameId, "the conversion was filed under the wrong game")
            assertEquals(updatedAt, event.occurredAt)
        }

    @Test
    fun `the conversion is not also recorded as a deletion`() =
        runBlocking<Unit> {
            val (_, _, taskId) = oneTask()

            editing.convertTaskToText(taskId)

            // PLAN 1123 lists deleted records and converted tasks separately, so a
            // screen showing both must not show this one twice.
            assertEquals(
                emptyList(),
                history.eventsOfTask(taskId).filter { it.kind == HistoryEventKind.TASK_DELETED },
                "the conversion was recorded as a deletion as well",
            )
        }

    @Test
    fun `the game can still be found after the piece of the cell has gone`() =
        runBlocking<Unit> {
            val (game, _, taskId) = oneTask()

            editing.convertTaskToText(taskId)

            // The task no longer reaches its game through any segment; the event
            // is the only thing that still knows, which is why it carries it.
            assertNull(database.taskDao().gameIdOfTask(taskId), "the task still has a way back to its game")
            assertEquals(game.id, history.eventsOfTask(taskId).single().gameId)
        }

    // ----------------------------------------------------------- where it shows

    @Test
    fun `the converted task leaves the pool`() =
        runBlocking<Unit> {
            val (_, _, taskId) = oneTask()
            val pools = PoolStore(database.poolDao())
            assertTrue(taskId in taskIdsIn(pools, PoolType.THREE_D))

            editing.convertTaskToText(taskId)

            assertTrue(taskId !in taskIdsIn(pools, PoolType.THREE_D), "a converted task stayed in the pool")
        }

    @Test
    fun `the converted task is not in the export`() =
        runBlocking<Unit> {
            val (_, _, taskId) = oneTask()
            assertTrue(TaskExportStore(database.taskExportDao()).exportedTasks().any { it.taskName == "token" })

            editing.convertTaskToText(taskId)

            assertFailsWith<dev.pnptracker.domain.export.TaskExportException>("the library was not empty afterwards") {
                TaskExportStore(database.taskExportDao()).exportedTasks()
            }
        }

    // ----------------------------------------------------------------- rollback

    @Test
    fun `a conversion whose record will not write does not happen at all`() =
        runBlocking<Unit> {
            val (_, cell, first) = oneTask()
            val (_, _, second) = oneTask()
            val taken = IdGenerator.Random.newId()
            database.taskEditDao().convertTaskToText(first, StoppedClock(updatedAt), IdGenerator.Random, taken)
            val before = snapshotOf(second, cell = null)

            assertFailsWith<Exception> {
                database.taskEditDao().convertTaskToText(second, StoppedClock(updatedAt), IdGenerator.Random, taken)
            }

            assertEquals(before, snapshotOf(second, cell = null), "a refused conversion changed the task")
            assertNotNull(database.taskDao().activeTaskById(second), "the task was taken out of view anyway")
            assertEquals("Basılacak: token ×14, kutu ayrı.", documentTextOf(cell.id))
        }

    @Test
    fun `a conversion that runs out of identities changes nothing`() =
        runBlocking<Unit> {
            // A cell holding nothing but the task: the freed words have no
            // neighbour's row to join, so a new one has to be made — and making
            // it needs a name, which is the identity this generator will not give.
            val (_, cell, taskId) = oneTask(text = "token")
            assertEquals(listOf(SegmentKind.TASK), segmentsOf(cell.id).map { it.kind })
            val before = snapshotOf(taskId, cell.id)

            assertFailsWith<Exception> {
                database.taskEditDao().convertTaskToText(taskId, StoppedClock(updatedAt), LimitedIds(0))
            }

            assertEquals(before, snapshotOf(taskId, cell.id), "a conversion that could not finish left something behind")
            assertEquals(emptyList(), history.allEvents(), "history was written for a conversion that did not happen")
        }

    @Test
    fun `converting a task that is gone is refused and writes nothing`() =
        runBlocking<Unit> {
            assertFailsWith<Exception> { editing.convertTaskToText(IdGenerator.Random.newId()) }

            assertEquals(emptyList(), history.allEvents())
        }

    @Test
    fun `a second conversion of the same task is refused and leaves the first alone`() =
        runBlocking<Unit> {
            val (_, cell, taskId) = oneTask()
            editing.convertTaskToText(taskId)
            val after = documentTextOf(cell.id)

            assertFailsWith<Exception> { editing.convertTaskToText(taskId) }

            assertEquals(after, documentTextOf(cell.id))
            assertEquals(1, history.eventsOfTask(taskId).size, "the conversion was recorded twice")
        }

    // ------------------------------------------------------------------ helpers

    private suspend fun taskIdsIn(
        pools: PoolStore,
        poolType: PoolType,
    ): List<EntityId> = activePoolTasks(pools.observePool(poolType).first()).map { it.taskId }

    /** Everything about a task that a conversion could destroy. */
    private suspend fun snapshotOf(
        taskId: EntityId,
        cell: EntityId?,
    ): List<Any?> =
        listOf(
            database.taskDao().taskByIdIncludingDeleted(taskId),
            database.taskColorDao().colorsOfTask(taskId),
            progress.stagesOfTask(taskId),
            progress.progressEventsOfTask(taskId),
            database.cellSegmentDao().segmentOfTaskIncludingDeleted(taskId),
            cell?.let { segmentsOf(it) },
        )
}
