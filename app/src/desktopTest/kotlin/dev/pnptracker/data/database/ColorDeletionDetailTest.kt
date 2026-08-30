package dev.pnptracker.data.database

import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorUsage
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * What the user is told before losing a colour, and what actually goes.
 *
 * PLAN 5.9 lets a colour be removed with everything pointing at it and nothing
 * else. Two halves are tested here: the numbers the confirmation rests on, which
 * have to be true of distinct tasks rather than of joined rows, and the slot
 * order the remaining colours are left in.
 */
class ColorDeletionDetailTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: ColorCatalogueStore
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = ColorCatalogueStore(database.colorDao())
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun colorNamed(name: String) = assertNotNull(database.colorDao().resolve(name))

    private suspend fun aTaskIn(
        colorIds: List<EntityId>,
        gameName: String = "Harmonies",
        taskName: String = "Token",
        pool: PoolType = PoolType.THREE_D,
        tracking: TrackingMode = TrackingMode.THREE_D_BATCH,
        column: CellColumnType = CellColumnType.THREE_D,
        quantity: Int? = 14,
    ): EntityId {
        val task =
            insertGameCellAndTask(database, gameName = gameName, columnType = column) {
                aTask(poolType = pool, trackingMode = tracking, name = taskName, requiredQuantity = quantity)
            }
        colorIds.forEachIndexed { slot, colorId ->
            database.taskColorDao().insert(TaskColorEntity(taskId = task.id, colorId = colorId, slotIndex = slot))
        }
        return task.id
    }

    private suspend fun slotsOf(taskId: EntityId) = database.taskColorDao().colorsOfTask(taskId).map { it.slotIndex }

    // ------------------------------------------------------------- the numbers

    @Test
    fun `a colour nobody uses is still something to confirm, and says so`() =
        runBlocking<Unit> {
            val usage = store.usageOf(colorNamed("Pembe").id)

            assertEquals(ColorUsage.UNUSED, usage)
            assertTrue(!usage.isUsed)
        }

    @Test
    fun `an unused base colour reads exactly like an unused custom one`() =
        runBlocking<Unit> {
            val made = database.colorDao().addColorToEndOfCatalogue(IdGenerator.Random.newId(), "Bordo", "#7B1F2B")

            assertEquals(store.usageOf(made.id), store.usageOf(colorNamed("Pembe").id))
        }

    @Test
    fun `one task in one game is counted once`() =
        runBlocking<Unit> {
            val grey = colorNamed("Gri")
            aTaskIn(listOf(grey.id), gameName = "Harmonies", taskName = "Token")

            val usage = store.usageOf(grey.id)

            assertEquals(1, usage.taskCount)
            assertEquals(1, usage.unfinishedTaskCount)
            assertEquals(1, usage.gameCount)
            assertEquals(1, usage.tasksLosingTheirLastColor)
            assertEquals(listOf("Harmonies" to "Token"), usage.samples.map { it.gameName to it.taskName })
        }

    @Test
    fun `forty two tasks are counted as forty two, not as their joined rows`() =
        runBlocking<Unit> {
            val yellow = colorNamed("Sarı")
            val white = colorNamed("Beyaz")
            repeat(42) { aTaskIn(listOf(yellow.id, white.id), gameName = "Oyun $it", taskName = "Is $it") }

            val usage = store.usageOf(yellow.id)

            assertEquals(42, usage.taskCount)
            assertEquals(42, usage.gameCount)
            // Every one of them keeps a second colour, so none is left with none.
            assertEquals(0, usage.tasksLosingTheirLastColor)
        }

    @Test
    fun `a finished task is counted but not counted as unfinished`() =
        runBlocking<Unit> {
            val grey = colorNamed("Gri")
            val open = aTaskIn(listOf(grey.id), gameName = "Bir", taskName = "Acik")
            aTaskIn(listOf(grey.id), gameName = "Iki", taskName = "Bitmis").also { done ->
                database.taskProgressDao().completePrimaryBatch(done, StoppedClock(createdAt))
            }

            val usage = store.usageOf(grey.id)

            assertEquals(2, usage.taskCount)
            assertEquals(1, usage.unfinishedTaskCount)
            assertTrue(usage.samples.any { it.taskName == "Acik" })
            assertEquals(2, database.taskColorDao().usageCountOfColor(grey.id))
            assertTrue(open != EntityId(kotlin.uuid.Uuid.NIL))
        }

    @Test
    fun `two tasks in one game count as one game`() =
        runBlocking<Unit> {
            val grey = colorNamed("Gri")
            aTaskIn(listOf(grey.id), gameName = "Harmonies", taskName = "Bir")
            aTaskIn(listOf(grey.id), gameName = "Harmonies", taskName = "Iki")

            val usage = store.usageOf(grey.id)

            assertEquals(2, usage.taskCount)
            assertEquals(2, usage.gameCount, "two cells of the same name are two games in this fixture")
        }

    @Test
    fun `only the tasks that would be left with nothing are counted as such`() =
        runBlocking<Unit> {
            val grey = colorNamed("Gri")
            val black = colorNamed("Siyah")
            aTaskIn(listOf(grey.id), gameName = "Tek", taskName = "Tek")
            aTaskIn(listOf(black.id, grey.id), gameName = "Cift", taskName = "Cift")

            assertEquals(1, store.usageOf(grey.id).tasksLosingTheirLastColor)
        }

    @Test
    fun `at most five tasks are named and the rest are counted`() =
        runBlocking<Unit> {
            val grey = colorNamed("Gri")
            repeat(9) { aTaskIn(listOf(grey.id), gameName = "Oyun $it", taskName = "Is $it") }

            val usage = store.usageOf(grey.id)

            assertEquals(9, usage.taskCount)
            assertEquals(5, usage.samples.size)
            assertEquals(4, usage.beyondTheSamples)
            assertEquals(usage.samples.size, usage.samples.toSet().size, "a task was named twice")
        }

    @Test
    fun `the same task is never named twice however it is reached`() =
        runBlocking<Unit> {
            val grey = colorNamed("Gri")
            val black = colorNamed("Siyah")
            // Two colours on one task: the relation table holds two rows for it.
            aTaskIn(listOf(grey.id, black.id), gameName = "Harmonies", taskName = "Token")

            val usage = store.usageOf(grey.id)

            assertEquals(1, usage.taskCount)
            assertEquals(1, usage.samples.size)
            assertEquals(0, usage.beyondTheSamples)
        }

    // --------------------------------------------------------------- the slots

    @Test
    fun `losing the first colour leaves the rest starting at zero in order`() =
        runBlocking<Unit> {
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val blue = colorNamed("Mavi")
            val task = aTaskIn(listOf(white.id, red.id, blue.id))

            store.deleteColor(white.id)

            assertEquals(listOf(0, 1), slotsOf(task))
            assertEquals(
                listOf(red.id, blue.id),
                database.taskColorDao().colorsOfTask(task).map { it.colorId },
                "the order the user chose was not kept",
            )
        }

    @Test
    fun `losing a middle colour closes the gap it left`() =
        runBlocking<Unit> {
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val blue = colorNamed("Mavi")
            val task = aTaskIn(listOf(white.id, red.id, blue.id))

            store.deleteColor(red.id)

            assertEquals(listOf(0, 1), slotsOf(task))
            assertEquals(listOf(white.id, blue.id), database.taskColorDao().colorsOfTask(task).map { it.colorId })
        }

    @Test
    fun `losing the last colour leaves the others where they already were`() =
        runBlocking<Unit> {
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val blue = colorNamed("Mavi")
            val task = aTaskIn(listOf(white.id, red.id, blue.id))

            store.deleteColor(blue.id)

            assertEquals(listOf(0, 1), slotsOf(task))
            assertEquals(listOf(white.id, red.id), database.taskColorDao().colorsOfTask(task).map { it.colorId })
        }

    @Test
    fun `tasks holding the colour in different places are all closed up at once`() =
        runBlocking<Unit> {
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val blue = colorNamed("Mavi")
            val green = colorNamed("Yeşil")
            val first = aTaskIn(listOf(red.id, white.id, blue.id), gameName = "Bir")
            val middle = aTaskIn(listOf(white.id, red.id, blue.id), gameName = "Iki")
            val last = aTaskIn(listOf(white.id, blue.id, red.id), gameName = "Uc")
            val untouched = aTaskIn(listOf(white.id, green.id), gameName = "Dort")

            store.deleteColor(red.id)

            assertEquals(listOf(0, 1), slotsOf(first))
            assertEquals(listOf(white.id, blue.id), database.taskColorDao().colorsOfTask(first).map { it.colorId })
            assertEquals(listOf(0, 1), slotsOf(middle))
            assertEquals(listOf(white.id, blue.id), database.taskColorDao().colorsOfTask(middle).map { it.colorId })
            assertEquals(listOf(0, 1), slotsOf(last))
            assertEquals(listOf(white.id, blue.id), database.taskColorDao().colorsOfTask(last).map { it.colorId })
            assertEquals(
                listOf(white.id to 0, green.id to 1),
                database.taskColorDao().colorsOfTask(untouched).map { it.colorId to it.slotIndex },
                "a task that never used the colour was renumbered anyway",
            )
        }

    @Test
    fun `a task down to one colour is an ordinary single colour task`() =
        runBlocking<Unit> {
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val task = aTaskIn(listOf(white.id, red.id))

            store.deleteColor(red.id)

            // Nothing says "this used to be a several-colour task": PLAN 12.7
            // derives the drawing from the colour list, and the list is now one.
            assertEquals(listOf(white.id to 0), database.taskColorDao().colorsOfTask(task).map { it.colorId to it.slotIndex })
        }

    @Test
    fun `a task down to no colour keeps everything except its colour`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val task = aTaskIn(listOf(red.id), taskName = "Yarasa", quantity = 7)

            store.deleteColor(red.id)

            assertEquals(emptyList(), database.taskColorDao().colorsOfTask(task))
            val row = assertNotNull(database.taskProgressDao().taskById(task))
            assertEquals("Yarasa", row.name)
            assertEquals(7, row.requiredQuantity)
            assertEquals(false, row.isCompleted)
            assertEquals(TrackingMode.THREE_D_BATCH, row.trackingMode)
        }

    // ------------------------------------------------------------- what stays

    @Test
    fun `the task and the segment holding it keep their identities`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val task = aTaskIn(listOf(red.id))
            val segmentBefore = assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(task))

            store.deleteColor(red.id)

            assertNotNull(database.taskProgressDao().taskById(task), "the task went with the colour")
            assertEquals(
                segmentBefore,
                database.cellSegmentDao().segmentOfTaskIncludingDeleted(task),
                "the word the task is anchored to was rewritten",
            )
        }

    @Test
    fun `a card task keeps every stage it had counted`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val card =
                aTaskIn(
                    listOf(red.id),
                    pool = PoolType.CARD,
                    tracking = TrackingMode.PIPELINE,
                    column = CellColumnType.CARD,
                    quantity = 10,
                )
            database.taskProgressDao().setStageQuantity(card, ProductionStage.PRINT, 4, StoppedClock(createdAt))
            val before = database.taskProgressDao().stagesOfTask(card)

            store.deleteColor(red.id)

            assertEquals(before, database.taskProgressDao().stagesOfTask(card))
        }

    @Test
    fun `a board task keeps every stage it had counted`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val board =
                aTaskIn(
                    listOf(red.id),
                    pool = PoolType.BOARD,
                    tracking = TrackingMode.PIPELINE,
                    column = CellColumnType.BOARD,
                    quantity = 8,
                )
            database.taskProgressDao().setStageQuantity(board, ProductionStage.PRINT, 3, StoppedClock(createdAt))
            val before = database.taskProgressDao().stagesOfTask(board)

            store.deleteColor(red.id)

            assertEquals(before, database.taskProgressDao().stagesOfTask(board))
        }

    @Test
    fun `the record of what went wrong in production is not touched`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val task = aTaskIn(listOf(red.id))
            database.taskProgressDao().completePrimaryBatch(task, StoppedClock(createdAt))
            database.taskProgressDao().reportFailure(IdGenerator.Random.newId(), task, 3, StoppedClock(updatedAt))
            val eventsBefore = database.taskProgressDao().progressEventsOfTask(task)
            val rowBefore = database.taskProgressDao().taskById(task)

            store.deleteColor(red.id)

            assertEquals(eventsBefore, database.taskProgressDao().progressEventsOfTask(task))
            assertEquals(3, database.taskProgressDao().failureTotalOf(task))
            assertEquals(rowBefore, database.taskProgressDao().taskById(task), "the task row itself was rewritten")
        }

    @Test
    fun `the other colours of the catalogue are left alone`() =
        runBlocking<Unit> {
            val before = database.colorDao().allColors().filterNot { it.canonicalName == "Kırmızı" }

            store.deleteColor(colorNamed("Kırmızı").id)

            assertEquals(before, database.colorDao().allColors())
        }

    @Test
    fun `two deletions of the same colour only one of them happens`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            aTaskIn(listOf(red.id))

            val outcomes =
                listOf(
                    async { runCatching { store.deleteColor(red.id) } },
                    async { runCatching { store.deleteColor(red.id) } },
                ).awaitAll()

            assertEquals(1, outcomes.count { it.isSuccess }, "the colour was removed twice")
            val refusal = assertNotNull(outcomes.first { it.isFailure }.exceptionOrNull()) as ColorSetupException
            assertEquals(ColorSetupFailure.COLOR_NO_LONGER_EXISTS, refusal.failure)
        }

    @Test
    fun `asking again after it is gone is refused in words the screen can use`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            store.deleteColor(red.id)

            val failure = assertFailsWith<ColorSetupException> { store.deleteColor(red.id) }

            assertEquals(ColorSetupFailure.COLOR_NO_LONGER_EXISTS, failure.failure)
        }

    @Test
    fun `a task added after the question was asked still loses the colour`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val asked = store.usageOf(red.id)
            // The user is looking at a confirmation saying nothing uses it.
            assertEquals(0, asked.taskCount)
            val late = aTaskIn(listOf(red.id))

            val removal = store.deleteColor(red.id)

            assertEquals(1, removal.removedRelationCount, "the stale count decided what was removed")
            assertEquals(emptyList(), database.taskColorDao().colorsOfTask(late))
            assertNotNull(database.taskProgressDao().taskById(late))
        }
}
