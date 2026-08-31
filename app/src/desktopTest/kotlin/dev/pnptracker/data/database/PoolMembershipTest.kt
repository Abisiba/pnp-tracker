package dev.pnptracker.data.database

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.repository.PoolStore
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.pools.PoolSnapshot
import dev.pnptracker.domain.tasks.TaskSetupException
import dev.pnptracker.domain.tasks.TaskSetupFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** A clock that does not move, so a fixture is the same every time it is built. */
private class FixedClock(
    private val moment: Instant,
) : Clock {
    override fun now(): Instant = moment
}

/**
 * What a pool holds, on a real database.
 *
 * A pool is decided by four things at once — the pool a task is in, whether it
 * is finished, whether it or its game has been deleted, and whether it is still
 * written in a cell — and every one of them is asked here rather than reasoned
 * about. PLAN 12.10 makes this a reflection of the same tasks, so what it must
 * never do is invent one or lose one.
 */
class PoolMembershipTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var pools: PoolStore
    private var realDatabaseExistedBefore = false
    private val moment = Instant.fromEpochMilliseconds(1_781_000_000_000)

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        pools = PoolStore(database.poolDao())
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun colorNamed(name: String) = assertNotNull(database.colorDao().resolve(name))

    private suspend fun task(
        game: String = "Harmonies",
        name: String = "Token",
        pool: PoolType = PoolType.THREE_D,
        tracking: TrackingMode = TrackingMode.THREE_D_BATCH,
        quantity: Int? = 14,
        colors: List<EntityId> = emptyList(),
    ): EntityId {
        val created =
            insertGameCellAndTask(database, gameName = game, columnType = CellColumnType.of(pool)) {
                aTask(poolType = pool, trackingMode = tracking, name = name, requiredQuantity = quantity)
            }
        colors.forEachIndexed { slot, colorId ->
            database.taskColorDao().insert(TaskColorEntity(created.id, colorId, slot))
        }
        return created.id
    }

    private suspend fun snapshotOf(poolType: PoolType): PoolSnapshot = pools.observePool(poolType).first()

    private suspend fun idsIn(poolType: PoolType): List<EntityId> = snapshotOf(poolType).tasks.map { it.taskId }

    // ------------------------------------------------------ one pool each

    @Test
    fun `a task of one pool is in that pool and in no other`() =
        runBlocking<Unit> {
            val threeD = task(pool = PoolType.THREE_D)
            val card = task(pool = PoolType.CARD, tracking = TrackingMode.PIPELINE)
            val board = task(pool = PoolType.BOARD, tracking = TrackingMode.PIPELINE)
            val special = task(pool = PoolType.SPECIAL, tracking = TrackingMode.CHECKLIST, quantity = null)

            assertEquals(listOf(threeD), idsIn(PoolType.THREE_D))
            assertEquals(listOf(card), idsIn(PoolType.CARD))
            assertEquals(listOf(board), idsIn(PoolType.BOARD))
            assertEquals(listOf(special), idsIn(PoolType.SPECIAL))
        }

    @Test
    fun `cards and board pieces are told apart though they are tracked the same way`() =
        runBlocking<Unit> {
            // Both are PIPELINE, so anything that decided a pool by tracking mode
            // would put them in one place.
            val card = task(pool = PoolType.CARD, tracking = TrackingMode.PIPELINE, name = "Kart")
            val board = task(pool = PoolType.BOARD, tracking = TrackingMode.PIPELINE, name = "Tahta")

            assertEquals(listOf(card), idsIn(PoolType.CARD))
            assertEquals(listOf(board), idsIn(PoolType.BOARD))
        }

    // ----------------------------------------------------- what stays out

    @Test
    fun `a finished task leaves the active pool`() =
        runBlocking<Unit> {
            val done = task(name = "Bitmiş")
            val open = task(name = "Süren")

            database.taskProgressDao().completeTask(done, FixedClock(moment), IdGenerator.Random)

            assertEquals(listOf(open), idsIn(PoolType.THREE_D))
        }

    @Test
    fun `a task in a finished game stays in the pool`() =
        runBlocking<Unit> {
            // PLAN's own scenario: marking the game done says something about the
            // game, not about the work left in it.
            val id = task(game = "Ark Nova")
            val game = database.gameDao().activeGames().first { it.name == "Ark Nova" }

            database.gameDao().setManuallyCompleted(game.id, true, moment, moment)

            assertEquals(listOf(id), idsIn(PoolType.THREE_D))
        }

    @Test
    fun `a deleted task leaves the pool`() =
        runBlocking<Unit> {
            val gone = task(name = "Silinmiş")
            val kept = task(name = "Kalan")

            database.taskDao().softDelete(gone, moment)

            assertEquals(listOf(kept), idsIn(PoolType.THREE_D))
        }

    @Test
    fun `the tasks of a deleted game leave the pool`() =
        runBlocking<Unit> {
            task(game = "Silinecek", name = "Gidecek")
            val kept = task(game = "Kalacak", name = "Kalan")
            val doomed = database.gameDao().activeGames().first { it.name == "Silinecek" }

            database.gameDao().softDelete(doomed.id, moment)

            assertEquals(listOf(kept), idsIn(PoolType.THREE_D))
        }

    @Test
    fun `a task written in no cell is in no pool`() =
        runBlocking<Unit> {
            // PLAN 16 will not have an anchorless task, and nothing in the
            // application makes one. If one ever appeared it would be shown
            // nowhere rather than in a pool that could not say where it lives.
            val orphan = aTask(name = "Çapasız")
            database.taskDao().insert(orphan)

            assertEquals(emptyList(), idsIn(PoolType.THREE_D))
        }

    @Test
    fun `one task cannot be written in two cells`() =
        runBlocking<Unit> {
            val id = task(game = "Bir")
            val other = insertGameAndCell(database, gameName = "İki")

            val refusal =
                assertFailsWith<SQLiteException> {
                    database.taskDao().insertSegment(
                        CellSegmentEntity.task(
                            id = IdGenerator.Random.newId(),
                            cellId = other.id,
                            orderIndex = 0,
                            taskId = id,
                            moment = moment,
                        ),
                    )
                }

            assertTrue("UNIQUE" in refusal.message.orEmpty(), "a second anchor was accepted: ${refusal.message}")
        }

    @Test
    fun `a task cannot be written into a cell of another pool`() =
        runBlocking<Unit> {
            val cardCell = insertGameAndCell(database, gameName = "Wingspan", columnType = CellColumnType.CARD)

            val refusal =
                assertFailsWith<TaskSetupException> {
                    database.taskDao().addTaskToCell(
                        task = aTask(poolType = PoolType.THREE_D, name = "Yanlış hücre"),
                        cellId = cardCell.id,
                        segmentId = IdGenerator.Random.newId(),
                        moment = moment,
                    )
                }

            assertEquals(TaskSetupFailure.CELL_POOL_MISMATCH, refusal.failure)
        }

    // ------------------------------------------------------ what it holds

    @Test
    fun `a task made in three colours arrives once with all three`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val yellow = colorNamed("Sarı")
            val black = colorNamed("Siyah")
            val yarasa = task(name = "Yarasa", quantity = 10, colors = listOf(red.id, yellow.id, black.id))

            val tasks = snapshotOf(PoolType.THREE_D).tasks

            assertEquals(listOf(yarasa), tasks.map { it.taskId }, "one task came back more than once")
            assertEquals(
                listOf("Kırmızı", "Sarı", "Siyah"),
                tasks.single().colors.map { it.canonicalName },
                "the colours did not arrive in the order the user put them in",
            )
            assertEquals(10, tasks.single().requiredQuantity, "the total was multiplied by the colours")
        }

    @Test
    fun `a task with no colour arrives with none rather than being left out`() =
        runBlocking<Unit> {
            val id = task(name = "Renksiz")

            val tasks = snapshotOf(PoolType.THREE_D).tasks

            assertEquals(listOf(id), tasks.map { it.taskId })
            assertEquals(emptyList(), tasks.single().colors)
        }

    @Test
    fun `a pipeline task arrives with its stages in pipeline order`() =
        runBlocking<Unit> {
            task(pool = PoolType.CARD, tracking = TrackingMode.PIPELINE, name = "Kart")
            task(pool = PoolType.BOARD, tracking = TrackingMode.PIPELINE, name = "Tahta")

            assertEquals(
                listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT),
                snapshotOf(PoolType.CARD)
                    .tasks
                    .single()
                    .stages
                    .map { it.stage },
            )
            assertEquals(
                listOf(ProductionStage.PRINT, ProductionStage.GLUE, ProductionStage.CUT),
                snapshotOf(PoolType.BOARD)
                    .tasks
                    .single()
                    .stages
                    .map { it.stage },
            )
        }

    @Test
    fun `the 3D pool asks for no stages and the card pool for no colours`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            task(colors = listOf(red.id))
            task(pool = PoolType.CARD, tracking = TrackingMode.PIPELINE)

            assertEquals(emptyList(), snapshotOf(PoolType.THREE_D).tasks.single().stages)
            assertEquals(emptyList(), snapshotOf(PoolType.CARD).tasks.single().colors)
        }

    @Test
    fun `what was reported wrong is added up once however many colours the task has`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val yellow = colorNamed("Sarı")
            val id = task(quantity = 20, colors = listOf(red.id, yellow.id))
            repeat(3) {
                database.taskProgressDao().reportFailure(
                    eventId = IdGenerator.Random.newId(),
                    taskId = id,
                    quantity = 2,
                    clock = FixedClock(moment),
                )
            }

            val task = snapshotOf(PoolType.THREE_D).tasks.single()

            // Six, not twelve: two colours must not double a total of six.
            assertEquals(6, task.failureTotal)
            assertEquals(6, task.currentMissingQuantity)
        }

    @Test
    fun `a task nothing was reported against reads as none rather than as nothing`() =
        runBlocking<Unit> {
            task()

            assertEquals(0, snapshotOf(PoolType.THREE_D).tasks.single().failureTotal)
        }

    @Test
    fun `a pipeline task's stages do not multiply what was reported against it`() =
        runBlocking<Unit> {
            val id = task(pool = PoolType.CARD, tracking = TrackingMode.PIPELINE, quantity = 20)
            database.taskProgressDao().reportFailure(
                eventId = IdGenerator.Random.newId(),
                taskId = id,
                quantity = 5,
                clock = FixedClock(moment),
            )

            // Three stages and one report is five, not fifteen.
            assertEquals(5, snapshotOf(PoolType.CARD).tasks.single().failureTotal)
        }

    @Test
    fun `the cell and the piece come with the task so the editor can be opened`() =
        runBlocking<Unit> {
            val id = task(game = "Harmonies")
            val segment = assertNotNull(database.taskEditDao().segmentOfTask(id))

            val task = snapshotOf(PoolType.THREE_D).tasks.single()

            assertEquals(segment.id, task.segmentId)
            assertEquals(segment.cellId, task.cellId)
            assertEquals("Harmonies", task.gameName)
        }

    // -------------------------------------------------- the sidebar summary

    @Test
    fun `the summary counts the work of every pool in one answer`() =
        runBlocking<Unit> {
            task(pool = PoolType.THREE_D)
            task(pool = PoolType.THREE_D, name = "İkinci")
            task(pool = PoolType.CARD, tracking = TrackingMode.PIPELINE)

            val summary = pools.observeNavigationSummary().first()

            assertEquals(2, summary.activeCountOf(PoolType.THREE_D))
            assertEquals(1, summary.activeCountOf(PoolType.CARD))
            assertEquals(0, summary.activeCountOf(PoolType.BOARD))
        }

    @Test
    fun `the special pool is not offered until there is special work`() =
        runBlocking<Unit> {
            task(pool = PoolType.THREE_D)

            assertTrue(!pools.observeNavigationSummary().first().showsSpecial)

            task(pool = PoolType.SPECIAL, tracking = TrackingMode.CHECKLIST, quantity = null)

            assertTrue(pools.observeNavigationSummary().first().showsSpecial)
        }

    @Test
    fun `the special pool stays offered once its last task is finished`() =
        runBlocking<Unit> {
            val id = task(pool = PoolType.SPECIAL, tracking = TrackingMode.COUNTED, quantity = 8)

            database.taskProgressDao().completeTask(id, FixedClock(moment), IdGenerator.Random)
            val summary = pools.observeNavigationSummary().first()

            // PLAN 9: visible, showing nothing left to do.
            assertTrue(summary.showsSpecial)
            assertEquals(0, summary.activeCountOf(PoolType.SPECIAL))
        }

    @Test
    fun `the special pool is put away once its tasks are deleted`() =
        runBlocking<Unit> {
            val id = task(pool = PoolType.SPECIAL, tracking = TrackingMode.CHECKLIST, quantity = null)

            database.taskDao().softDelete(id, moment)

            assertTrue(!pools.observeNavigationSummary().first().showsSpecial)
        }

    @Test
    fun `the special pool is put away when the game holding its work goes`() =
        runBlocking<Unit> {
            task(game = "Wingspan", pool = PoolType.SPECIAL, tracking = TrackingMode.CHECKLIST, quantity = null)
            val game = database.gameDao().activeGames().first { it.name == "Wingspan" }

            database.gameDao().softDelete(game.id, moment)

            assertTrue(!pools.observeNavigationSummary().first().showsSpecial)
        }
}
