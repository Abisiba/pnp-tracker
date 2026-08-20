package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aRawImportBlock
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.data.database.anItem
import dev.pnptracker.data.database.deletedAt
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskSetupException
import dev.pnptracker.domain.tasks.TaskSetupFailure
import dev.pnptracker.domain.tasks.TaskSummary
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
import kotlin.time.Instant

/**
 * Creating tasks by hand against a real database in a temporary directory.
 *
 * What cannot be shown against a stand in is exactly what matters here: that a
 * game's tasks really are kept to that game by the query, that a deleted item or
 * game is refused by the transaction rather than let through by a foreign key
 * that only sees the row is still present, and that what the user typed is still
 * there after the application closes.
 */
class TaskSetupStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: TaskSetupStore
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_781_000_000_000)

    @BeforeTest
    fun openTemporaryDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = TaskSetupStore(database.taskDao(), clock = StoppedClock(moment))
    }

    @AfterTest
    fun closeAndDeleteTemporaryDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    /** A game with one item under it, both active, ready to hang tasks from. */
    private suspend fun aGameWithAnItem(
        gameName: String = "Harmonies",
        itemName: String = "Token",
    ): Pair<EntityId, EntityId> {
        val game = aGame(name = gameName)
        val item = anItem(gameId = game.id, name = itemName)
        database.gameDao().insert(game)
        database.itemDao().insert(item)
        return game.id to item.id
    }

    private suspend fun tasksOf(gameId: EntityId): List<TaskSummary> = store.observeTasks(gameId).first()

    private suspend fun everyTaskRow() = database.taskDao().allTasksIncludingArchivedAndDeleted()

    @Test
    fun `a task the user typed is saved under the item and comes back in the list`() =
        runBlocking {
            val (gameId, itemId) = aGameWithAnItem()

            val id =
                store.createTask(
                    itemId = itemId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                )

            val saved = tasksOf(gameId).single()
            assertEquals(id, saved.id)
            assertEquals(itemId, saved.itemId)
            assertEquals("Token", saved.itemName)
            assertEquals("Gri token", saved.name)
            assertEquals(PoolType.THREE_D, saved.poolType)
            assertEquals(TrackingMode.THREE_D_BATCH, saved.trackingMode)
        }

    @Test
    fun `every field the form offers reaches the row unchanged`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()

            val id =
                store.createTask(
                    itemId = itemId,
                    name = "Mavi kart",
                    poolType = PoolType.CARD,
                    trackingMode = TrackingMode.PIPELINE,
                    requiredQuantity = 24,
                    notes = "Arka yüz mat",
                )

            val row = assertNotNull(database.taskDao().activeTaskById(id))
            assertEquals(itemId, row.itemId)
            assertEquals("Mavi kart", row.name)
            assertEquals(PoolType.CARD, row.poolType)
            assertEquals(TrackingMode.PIPELINE, row.trackingMode)
            assertEquals(24, row.requiredQuantity)
            assertEquals("Arka yüz mat", row.notes)
            assertTrue(!row.isArchived, "a new task is not archived")
            assertNull(row.deletedAt, "a new task is not deleted")
        }

    @Test
    fun `the name is trimmed at the ends and left alone inside`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()

            val id =
                store.createTask(
                    itemId = itemId,
                    name = "  Gri  büyük  token  ",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                )

            assertEquals("Gri  büyük  token", assertNotNull(database.taskDao().activeTaskById(id)).name)
        }

    @Test
    fun `a note that is only spaces is the same as no note`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()

            val id =
                store.createTask(
                    itemId = itemId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    notes = "   ",
                )

            assertNull(assertNotNull(database.taskDao().activeTaskById(id)).notes)
        }

    @Test
    fun `a task the user typed carries no imported cell behind it`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()

            val id =
                store.createTask(
                    itemId = itemId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                )

            val row = assertNotNull(database.taskDao().activeTaskById(id))
            assertNull(row.sourceRawImportBlockId, "a hand made task must not look like an imported one")
        }

    @Test
    fun `the row is created and updated at the one moment the act happened`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()

            val id =
                store.createTask(
                    itemId = itemId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                )

            val row = assertNotNull(database.taskDao().activeTaskById(id))
            assertEquals(moment, row.createdAt)
            assertEquals(moment, row.updatedAt)
        }

    @Test
    fun `a quantity left unknown is stored as unknown rather than as zero`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()

            val id =
                store.createTask(
                    itemId = itemId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = null,
                )

            assertNull(assertNotNull(database.taskDao().activeTaskById(id)).requiredQuantity)
        }

    @Test
    fun `a blank name is refused and nothing at all is written`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()

            assertFailsWith<IllegalArgumentException> {
                store.createTask(
                    itemId = itemId,
                    name = "   ",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                )
            }

            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `a quantity of zero is refused and nothing at all is written`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()

            assertFailsWith<IllegalArgumentException> {
                store.createTask(
                    itemId = itemId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = 0,
                )
            }

            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `a negative quantity is refused and nothing at all is written`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()

            assertFailsWith<IllegalArgumentException> {
                store.createTask(
                    itemId = itemId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = -3,
                )
            }

            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `a tracking mode the pool does not allow is refused and nothing is written`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()

            assertFailsWith<IllegalArgumentException> {
                store.createTask(
                    itemId = itemId,
                    name = "Gri token",
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.CHECKLIST,
                )
            }

            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `a broken rule comes out as itself rather than as a saving problem`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()

            // The three refusals above are invariants, not storage failures. Were
            // they reported as TaskSetupException the screen would tell the user
            // the database was at fault and offer them nothing to fix.
            val refusals =
                listOf<suspend () -> Unit>(
                    { store.createTask(itemId, " ", PoolType.THREE_D, TrackingMode.THREE_D_BATCH) },
                    { store.createTask(itemId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH, 0) },
                    { store.createTask(itemId, "Gri token", PoolType.CARD, TrackingMode.COUNTED) },
                )
            refusals.forEach { attempt -> assertFailsWith<IllegalArgumentException> { attempt() } }
        }

    @Test
    fun `an item that was deleted refuses the task even though its row is still there`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()
            assertEquals(1, database.itemDao().softDelete(itemId, deletedAt))

            val refusal =
                assertFailsWith<TaskSetupException> {
                    store.createTask(itemId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
                }

            assertEquals(TaskSetupFailure.ITEM_NOT_AVAILABLE, refusal.failure)
            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `a game that was deleted refuses a task under its item`() =
        runBlocking {
            val (gameId, itemId) = aGameWithAnItem()
            assertEquals(1, database.gameDao().softDelete(gameId, deletedAt))

            val refusal =
                assertFailsWith<TaskSetupException> {
                    store.createTask(itemId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
                }

            assertEquals(TaskSetupFailure.ITEM_NOT_AVAILABLE, refusal.failure)
            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `an item that was never there refuses the task`() =
        runBlocking {
            aGameWithAnItem()

            val refusal =
                assertFailsWith<TaskSetupException> {
                    store.createTask(
                        IdGenerator.Random.newId(),
                        "Gri token",
                        PoolType.THREE_D,
                        TrackingMode.THREE_D_BATCH,
                    )
                }

            assertEquals(TaskSetupFailure.ITEM_NOT_AVAILABLE, refusal.failure)
            assertEquals(emptyList(), everyTaskRow())
        }

    @Test
    fun `one game's tasks never include another game's`() =
        runBlocking {
            val (firstGame, firstItem) = aGameWithAnItem(gameName = "Harmonies", itemName = "Token")
            val (secondGame, secondItem) = aGameWithAnItem(gameName = "Root", itemName = "Meeple")
            store.createTask(firstItem, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
            store.createTask(secondItem, "Kedi meeple", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertEquals(listOf("Gri token"), tasksOf(firstGame).map { it.name })
            assertEquals(listOf("Kedi meeple"), tasksOf(secondGame).map { it.name })
        }

    @Test
    fun `each row names the item it really hangs from`() =
        runBlocking {
            val (gameId, tokens) = aGameWithAnItem(itemName = "Tokenlar")
            val cards = anItem(gameId = gameId, name = "Kartlar")
            database.itemDao().insert(cards)
            store.createTask(tokens, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
            store.createTask(cards.id, "Olay kartı", PoolType.CARD, TrackingMode.PIPELINE)

            val byName = tasksOf(gameId).associateBy { it.name }
            assertEquals("Tokenlar", assertNotNull(byName["Gri token"]).itemName)
            assertEquals("Kartlar", assertNotNull(byName["Olay kartı"]).itemName)
            assertEquals(cards.id, assertNotNull(byName["Olay kartı"]).itemId)
        }

    @Test
    fun `a deleted task drops out of the game's list`() =
        runBlocking {
            val (gameId, itemId) = aGameWithAnItem()
            val id = store.createTask(itemId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertEquals(1, database.taskDao().softDelete(id, deletedAt))

            assertEquals(emptyList(), tasksOf(gameId))
        }

    @Test
    fun `a deleted item takes its tasks out of the game's list`() =
        runBlocking {
            val (gameId, itemId) = aGameWithAnItem()
            store.createTask(itemId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertEquals(1, database.itemDao().softDelete(itemId, deletedAt))

            assertEquals(emptyList(), tasksOf(gameId))
        }

    @Test
    fun `a deleted game takes its tasks out of its own list`() =
        runBlocking {
            val (gameId, itemId) = aGameWithAnItem()
            store.createTask(itemId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertEquals(1, database.gameDao().softDelete(gameId, deletedAt))

            assertEquals(emptyList(), tasksOf(gameId))
        }

    @Test
    fun `an archived task drops out of the game's list without being deleted`() =
        runBlocking<Unit> {
            val (gameId, itemId) = aGameWithAnItem()
            val id = store.createTask(itemId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertEquals(1, database.taskDao().archive(id, deletedAt))

            assertEquals(emptyList(), tasksOf(gameId))
            assertNotNull(database.taskDao().taskByIdIncludingArchivedAndDeleted(id))
        }

    @Test
    fun `the row order is the same on every read`() =
        runBlocking {
            val (gameId, tokens) = aGameWithAnItem(itemName = "Tokenlar")
            val cards = anItem(gameId = gameId, name = "Kartlar")
            database.itemDao().insert(cards)
            store.createTask(cards.id, "Olay kartı", PoolType.CARD, TrackingMode.PIPELINE)
            store.createTask(tokens, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
            store.createTask(cards.id, "Anlaşma kartı", PoolType.CARD, TrackingMode.PIPELINE)

            val expected = listOf("Anlaşma kartı", "Olay kartı", "Gri token")
            assertEquals(expected, tasksOf(gameId).map { it.name })
            assertEquals(expected, tasksOf(gameId).map { it.name }, "a second read reordered the list")
        }

    @Test
    fun `two tasks may carry the same name`() =
        runBlocking {
            val (gameId, itemId) = aGameWithAnItem()

            val first = store.createTask(itemId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
            val second = store.createTask(itemId, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertTrue(first != second, "the two tasks are separate rows")
            assertEquals(2, tasksOf(gameId).size)
        }

    @Test
    fun `what the user typed is still there after the database is closed and opened`() =
        runBlocking {
            val (gameId, itemId) = aGameWithAnItem()
            store.createTask(
                itemId = itemId,
                name = "Gri token",
                poolType = PoolType.SPECIAL,
                trackingMode = TrackingMode.COUNTED,
                requiredQuantity = 7,
                notes = "Kutu içi ayraç",
            )

            database.close()
            database = DatabaseFactory().open(directory.databaseFile)
            store = TaskSetupStore(database.taskDao(), clock = StoppedClock(moment))

            val saved = tasksOf(gameId).single()
            assertEquals("Gri token", saved.name)
            assertEquals(PoolType.SPECIAL, saved.poolType)
            assertEquals(TrackingMode.COUNTED, saved.trackingMode)
            assertEquals(7, saved.requiredQuantity)
            assertEquals("Kutu içi ayraç", saved.notes)
        }

    @Test
    fun `a hand made task and an imported one sit in the same list and stay told apart`() =
        runBlocking {
            val (gameId, itemId) = aGameWithAnItem()
            val batch = anImportBatch()
            val block = aRawImportBlock(importBatchId = batch.id)
            database.importDao().insertBatch(batch)
            database.importDao().insertRawBlock(block)
            database.taskDao().insert(
                aTask(itemId = itemId, name = "İçe aktarılan token").copy(sourceRawImportBlockId = block.id),
            )

            store.createTask(itemId, "Elle yazılan token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            val byName = tasksOf(gameId).associateBy { it.name }
            assertEquals(2, byName.size)
            assertTrue(assertNotNull(byName["İçe aktarılan token"]).isFromImport, "the imported task lost its source")
            assertTrue(!assertNotNull(byName["Elle yazılan token"]).isFromImport, "a typed task gained a source")
            assertEquals(
                block.id,
                assertNotNull(
                    database.taskDao().activeTaskById(assertNotNull(byName["İçe aktarılan token"]).id),
                ).sourceRawImportBlockId,
                "the audit trail behind the imported task must survive untouched",
            )
        }

    @Test
    fun `creating a task by hand leaves the import tables exactly as they were`() =
        runBlocking {
            val (_, itemId) = aGameWithAnItem()
            val batch = anImportBatch(rawBlockCount = 1)
            val block = aRawImportBlock(importBatchId = batch.id)
            database.importDao().insertBatch(batch)
            database.importDao().insertRawBlock(block)
            val batchesBefore = database.importDao().allBatches()
            val blocksBefore = database.importDao().rawBlocksOfBatch(batch.id)
            val draftsBefore = database.importDao().draftTasksOfBatch(batch.id)

            store.createTask(itemId, "Elle yazılan token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            assertEquals(batchesBefore, database.importDao().allBatches())
            assertEquals(blocksBefore, database.importDao().rawBlocksOfBatch(batch.id))
            assertEquals(draftsBefore, database.importDao().draftTasksOfBatch(batch.id))
            assertEquals(0, assertNotNull(database.importDao().batchById(batch.id)).createdTaskCount)
        }
}
