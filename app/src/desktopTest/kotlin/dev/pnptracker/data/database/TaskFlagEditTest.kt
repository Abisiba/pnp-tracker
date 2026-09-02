package dev.pnptracker.data.database

import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskEditFailure
import dev.pnptracker.domain.tasks.TaskFlags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Putting the four import marks right on a task that already exists.
 *
 * PLAN 10 and 11.7 make these statements about the work rather than about its
 * progress, so changing one moves nothing else: the task keeps its identity, the
 * piece of the cell that names it, its colours, its pipeline and its history.
 *
 * `needs_info` is the one with a visible consequence. PLAN 11.7's default is to
 * keep such a task out of its active pool, so taking the mark off has to put it
 * back — and the game table has to go on showing it either way, because the
 * cell's document is what the user wrote and not a list of outstanding work.
 */
class TaskFlagEditTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_780_000_000_000)

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

    private val editDao get() = database.taskEditDao()

    private suspend fun aCardTask(): TaskEntity =
        insertGameCellAndTask(database, columnType = CellColumnType.CARD) {
            aTask(poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE, name = "Kuş kartları")
        }

    private suspend fun task(id: EntityId) = assertNotNull(database.taskDao().activeTaskById(id))

    private suspend fun edit(
        task: TaskEntity,
        name: String = task.name,
        colorIds: List<EntityId> = emptyList(),
        requiredQuantity: Int? = task.requiredQuantity,
        notes: String? = task.notes,
        flags: TaskFlags? = null,
        clock: Clock = StoppedClock(moment),
    ): Boolean =
        editDao.editTask(
            taskId = task.id,
            name = name,
            colorIds = colorIds,
            requiredQuantity = requiredQuantity,
            notes = notes,
            trackingMode = task.trackingMode,
            flags = flags,
            clock = clock,
        )

    private suspend fun activeNamesIn(poolType: PoolType): List<String> =
        database
            .poolDao()
            .observeTasksOfPool(poolType)
            .first()
            .map { it.taskName }

    private suspend fun activeCountIn(poolType: PoolType): Int =
        database
            .poolDao()
            .observePoolCounts()
            .first()
            .firstOrNull { it.poolType == poolType }
            ?.activeCount ?: 0

    private suspend fun namesInGameTable(): List<String> =
        database
            .gameTableDao()
            .observeCellContents()
            .first()
            .mapNotNull { it.taskName }

    // ------------------------------------------------------------- the marks

    @Test
    fun `the four marks are saved together with the rest of the form`() =
        runBlocking<Unit> {
            val created = aCardTask()

            assertTrue(
                edit(
                    created,
                    name = "Kuş kartları",
                    notes = "ikinci baskı",
                    flags = TaskFlags(isMissing = true, needsInfo = true, needsClassification = true),
                ),
            )

            val saved = task(created.id)
            assertTrue(saved.isMissing)
            assertFalse(saved.isBorrowed)
            assertTrue(saved.needsInfo)
            assertTrue(saved.needsClassification)
            assertEquals("ikinci baskı", saved.notes)
        }

    @Test
    fun `changing the marks keeps the task and the piece of the cell it is written in`() =
        runBlocking<Unit> {
            val created = aCardTask()
            val segmentBefore = assertNotNull(editDao.segmentOfTask(created.id))

            edit(created, flags = TaskFlags(isBorrowed = true))

            assertEquals(created.id, task(created.id).id)
            val segmentAfter = assertNotNull(editDao.segmentOfTask(created.id))
            assertEquals(segmentBefore.id, segmentAfter.id)
            assertEquals(segmentBefore.cellId, segmentAfter.cellId)
            assertEquals(segmentBefore.orderIndex, segmentAfter.orderIndex)
        }

    @Test
    fun `changing the marks leaves the colours, the pipeline and the history alone`() =
        runBlocking<Unit> {
            val created = aCardTask()
            // One colour: PLAN 5.10 does not describe carrying a task between
            // being made in one and being made in several, and this test is
            // about the marks rather than about that.
            val colors =
                database
                    .colorDao()
                    .allColors()
                    .take(1)
                    .map { it.id }
            edit(created, colorIds = colors)
            database.taskProgressDao().setStageQuantities(
                taskId = created.id,
                targets = mapOf(ProductionStage.PRINT to 5),
                clock = StoppedClock(moment),
            )
            val stagesBefore = database.taskProgressDao().stagesOfTask(created.id)
            val eventsBefore = database.taskProgressDao().progressEventsOfTask(created.id)

            edit(created, colorIds = colors, flags = TaskFlags(needsInfo = true))

            assertEquals(colors, editDao.colorsOfTask(created.id).map { it.colorId })
            assertEquals(stagesBefore, database.taskProgressDao().stagesOfTask(created.id))
            assertEquals(eventsBefore, database.taskProgressDao().progressEventsOfTask(created.id))
        }

    @Test
    fun `missing and borrowed together are refused and every field is left as it was`() =
        runBlocking<Unit> {
            val created = aCardTask()
            edit(created, notes = "önceki not", flags = TaskFlags(isMissing = true))

            assertEquals(
                TaskEditFailure.MISSING_AND_BORROWED,
                assertFailsWith<TaskEditException> {
                    edit(
                        created,
                        name = "Değişti",
                        notes = "yeni not",
                        flags = TaskFlags(isMissing = true, isBorrowed = true),
                    )
                }.failure,
            )

            val saved = task(created.id)
            assertEquals("Kuş kartları", saved.name)
            assertEquals("önceki not", saved.notes)
            assertTrue(saved.isMissing)
            assertFalse(saved.isBorrowed)
        }

    @Test
    fun `an edit that says nothing about the marks leaves them exactly as they are`() =
        runBlocking<Unit> {
            val created = aCardTask()
            edit(created, flags = TaskFlags(isBorrowed = true, needsClassification = true))

            edit(created, name = "Başka ad", flags = null)

            val saved = task(created.id)
            assertEquals("Başka ad", saved.name)
            assertTrue(saved.isBorrowed)
            assertTrue(saved.needsClassification)
        }

    @Test
    fun `saving the same marks again changes nothing and reads no clock`() =
        runBlocking<Unit> {
            val created = aCardTask()
            val marks = TaskFlags(isMissing = true, needsInfo = true)
            edit(created, flags = marks)
            val clock = CountingClock(moment)

            val changed = edit(created, flags = marks, clock = clock)

            assertFalse(changed)
            assertEquals(0, clock.reads)
        }

    // ----------------------------------------- what the information mark does

    @Test
    fun `a task waiting on information leaves the active pool and its count`() =
        runBlocking<Unit> {
            val created = aCardTask()
            assertEquals(listOf("Kuş kartları"), activeNamesIn(PoolType.CARD))
            assertEquals(1, activeCountIn(PoolType.CARD))

            edit(created, flags = TaskFlags(needsInfo = true))

            assertEquals(emptyList(), activeNamesIn(PoolType.CARD))
            assertEquals(0, activeCountIn(PoolType.CARD))
        }

    @Test
    fun `taking the information mark off puts the task back in the pool and its count`() =
        runBlocking<Unit> {
            val created = aCardTask()
            edit(created, flags = TaskFlags(needsInfo = true))
            assertEquals(emptyList(), activeNamesIn(PoolType.CARD))

            edit(created, flags = TaskFlags(needsInfo = false))

            assertEquals(listOf("Kuş kartları"), activeNamesIn(PoolType.CARD))
            assertEquals(1, activeCountIn(PoolType.CARD))
        }

    @Test
    fun `the game table shows the task whether it waits on information or not`() =
        runBlocking<Unit> {
            val created = aCardTask()
            assertEquals(listOf("Kuş kartları"), namesInGameTable())

            edit(created, flags = TaskFlags(needsInfo = true))
            assertEquals(
                listOf("Kuş kartları"),
                namesInGameTable(),
                "PLAN 5.5 makes the cell the user's document, not a list of outstanding work",
            )

            edit(created, flags = TaskFlags(needsInfo = false))
            assertEquals(listOf("Kuş kartları"), namesInGameTable())
        }

    @Test
    fun `a task the user has classified themselves stays in its active pool`() =
        runBlocking<Unit> {
            val created = aCardTask()

            edit(created, flags = TaskFlags(needsClassification = true))

            assertEquals(
                listOf("Kuş kartları"),
                activeNamesIn(PoolType.CARD),
                "the mark records where the pool came from, not that it is missing",
            )
            assertEquals(1, activeCountIn(PoolType.CARD))
        }

    @Test
    fun `a missing or borrowed task stays in the pool the user put it in`() =
        runBlocking<Unit> {
            val created = aCardTask()

            edit(created, flags = TaskFlags(isMissing = true))
            assertEquals(listOf("Kuş kartları"), activeNamesIn(PoolType.CARD))

            edit(created, flags = TaskFlags(isBorrowed = true))
            assertEquals(listOf("Kuş kartları"), activeNamesIn(PoolType.CARD))
        }

    @Test
    fun `the pool carries the marks so the panel opened from it shows them`() =
        runBlocking<Unit> {
            val created = aCardTask()
            edit(created, flags = TaskFlags(isBorrowed = true, needsClassification = true))

            val row =
                database
                    .poolDao()
                    .observeTasksOfPool(PoolType.CARD)
                    .first()
                    .single()

            assertTrue(row.isBorrowed)
            assertTrue(row.needsClassification)
            assertFalse(row.isMissing)
            assertFalse(row.needsInfo)
        }

    @Test
    fun `a task that is gone is refused and nothing is written`() =
        runBlocking<Unit> {
            aCardTask()

            assertEquals(
                TaskEditFailure.TASK_NOT_AVAILABLE,
                assertFailsWith<TaskEditException> {
                    editDao.editTask(
                        taskId = IdGenerator.Random.newId(),
                        name = "Yok",
                        colorIds = emptyList(),
                        requiredQuantity = null,
                        notes = null,
                        trackingMode = TrackingMode.PIPELINE,
                        flags = TaskFlags(needsInfo = true),
                        clock = StoppedClock(moment),
                    )
                }.failure,
            )
        }
}
