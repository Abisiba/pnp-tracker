package dev.pnptracker.data.repository

import androidx.room3.useWriterConnection
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.activePoolTasks
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Where a task still waiting on information does and does not appear.
 *
 * PLAN 11.7 says work whose colour or quantity is unknown is kept out of the
 * active pool by default. Kept out is not hidden and not finished: the task is
 * still in its cell, still in its game, still counted among what the pool holds,
 * and still openable. It is simply not on the list of what can be picked up now,
 * because it cannot be.
 *
 * The three other flags are deliberately not filters. PLAN 10 makes them context
 * a task carries after the user has already chosen its pool by hand — a piece
 * borrowed from another box is still a piece to make.
 */
class NeedsInfoProjectionTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var pools: PoolStore
    private lateinit var table: GameTableStore
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        pools = PoolStore(database.poolDao())
        table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao())
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private var cellId: EntityId? = null
    private var gameId: EntityId? = null

    private suspend fun aGameWithACell(poolType: PoolType = PoolType.THREE_D) {
        val game = aGame(name = "Harmonies")
        val cell = aCell(gameId = game.id, columnType = CellColumnType.of(poolType))
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        gameId = game.id
        cellId = cell.id
    }

    private suspend fun aTaskIn(
        name: String,
        poolType: PoolType = PoolType.THREE_D,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        quantity: Int? = 20,
        needsInfo: Boolean = false,
        isMissing: Boolean = false,
        isBorrowed: Boolean = false,
        needsClassification: Boolean = false,
    ): EntityId {
        val task =
            aTask(poolType = poolType, trackingMode = trackingMode, name = name, requiredQuantity = quantity)
                .copy(
                    needsInfo = needsInfo,
                    isMissing = isMissing,
                    isBorrowed = isBorrowed,
                    needsClassification = needsClassification,
                )
        database.taskDao().addTaskToCell(task, assertNotNull(cellId), IdGenerator.Random.newId(), createdAt)
        return task.id
    }

    private suspend fun activeNames(poolType: PoolType = PoolType.THREE_D): List<String> =
        activePoolTasks(pools.observePool(poolType).first()).map { it.name }

    private suspend fun counts() = database.poolDao().observePoolCounts().first()

    /** Clears the flag straight in the table; PLAN leaves the act to a later slice. */
    private suspend fun clearNeedsInfo(taskId: EntityId) {
        database.useWriterConnection { transactor ->
            transactor.usePrepared("UPDATE tasks SET needs_info = 0 WHERE id = ?") { statement ->
                statement.bindText(1, taskId.toString())
                statement.step()
            }
        }
    }

    @Test
    fun `a task waiting on information is not on the active list`() =
        runBlocking<Unit> {
            aGameWithACell()
            aTaskIn("Gri token")
            aTaskIn("Sayısına bakılacak", quantity = null, needsInfo = true)

            assertEquals(listOf("Gri token"), activeNames())
        }

    @Test
    fun `it is not counted among the work the sidebar says is waiting`() =
        runBlocking<Unit> {
            aGameWithACell()
            aTaskIn("Gri token")
            aTaskIn("Sayısına bakılacak", quantity = null, needsInfo = true)

            val row = assertNotNull(counts().firstOrNull { it.poolType == PoolType.THREE_D })
            assertEquals(1, row.activeCount, "a task waiting on information was counted as work to do")
            // But the pool still holds it, which is what keeps the Special pool
            // from vanishing while it has something in it (PLAN 9).
            assertEquals(2, row.taskCount, "the pool forgot it was holding anything")
        }

    @Test
    fun `it stays in the cell it was written in`() =
        runBlocking<Unit> {
            aGameWithACell()
            val waiting = aTaskIn("Sayısına bakılacak", quantity = null, needsInfo = true)

            val row = table.observeTable().first().single()
            val piece = row.cell(CellColumnType.THREE_D).tasks.single()
            assertEquals(waiting, piece.taskId, "the task disappeared from its cell")
            assertEquals("Sayısına bakılacak", piece.text)
            assertTrue(!piece.isCompletedTask, "a task waiting on information was drawn as finished")
        }

    @Test
    fun `it is not finished, and can still be found and worked on`() =
        runBlocking<Unit> {
            aGameWithACell()
            val waiting = aTaskIn("Sayısına bakılacak", quantity = null, needsInfo = true)

            val task = assertNotNull(database.taskDao().activeTaskById(waiting))
            assertTrue(!task.isCompleted)
            assertTrue(task.needsInfo)
        }

    @Test
    fun `filling in what was missing puts it back on the list`() =
        runBlocking<Unit> {
            aGameWithACell()
            val waiting = aTaskIn("Sayısına bakılacak", quantity = null, needsInfo = true)
            assertEquals(emptyList(), activeNames())

            // No user-facing way to do this yet; the projection is what is being
            // pinned here, not the act.
            clearNeedsInfo(waiting)

            assertEquals(listOf("Sayısına bakılacak"), activeNames())
            assertEquals(1, assertNotNull(counts().firstOrNull { it.poolType == PoolType.THREE_D }).activeCount)
        }

    @Test
    fun `missing and borrowed work is ordinary active work`() =
        runBlocking<Unit> {
            aGameWithACell()
            aTaskIn("Eksik kart", isMissing = true, needsClassification = true)
            aTaskIn("Ödünç zar", isBorrowed = true)

            assertEquals(listOf("Eksik kart", "Ödünç zar"), activeNames().sorted())
            assertEquals(2, assertNotNull(counts().firstOrNull { it.poolType == PoolType.THREE_D }).activeCount)
        }

    @Test
    fun `a task the user classified by hand is on the list like any other`() =
        runBlocking<Unit> {
            aGameWithACell()
            aTaskIn("Sınıflandırılmış", needsClassification = true)

            assertEquals(listOf("Sınıflandırılmış"), activeNames())
        }

    @Test
    fun `a card pipeline waiting on information is left out of every pool read`() =
        runBlocking<Unit> {
            aGameWithACell(poolType = PoolType.CARD)
            aTaskIn("Deste", poolType = PoolType.CARD, trackingMode = TrackingMode.PIPELINE)
            aTaskIn(
                "Kaç renk olacak",
                poolType = PoolType.CARD,
                trackingMode = TrackingMode.PIPELINE,
                quantity = null,
                needsInfo = true,
            )

            val shown = activePoolTasks(pools.observePool(PoolType.CARD).first())
            assertEquals(listOf("Deste"), shown.map { it.name })
            assertTrue(
                shown.all { it.stages.isNotEmpty() },
                "the pipeline of the task that is on the list went missing",
            )
        }

    @Test
    fun `a finished task waiting on information is still simply finished`() =
        runBlocking<Unit> {
            aGameWithACell()
            val waiting = aTaskIn("Sayısına bakılacak", quantity = null, needsInfo = true)
            database.taskProgressDao().completeTask(waiting, StoppedClock(updatedAt), IdGenerator.Random)

            assertEquals(emptyList(), activeNames())
            val row = assertNotNull(counts().firstOrNull { it.poolType == PoolType.THREE_D })
            assertEquals(0, row.activeCount)
            assertEquals(1, row.taskCount)
        }
}
