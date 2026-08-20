package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aDraftTask
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aRawImportBlock
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.data.database.anItem
import dev.pnptracker.data.database.entity.DraftTaskEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.entity.ImportBatchEntity
import dev.pnptracker.data.database.entity.ItemEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
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
 * Confirming an import against a real database in a temporary directory.
 *
 * What is worth proving here cannot be shown against a stand in: that the whole
 * confirmation is one transaction, that a failure late in it leaves the earlier
 * writes nowhere, and that a second confirmation adds nothing.
 *
 * Failure cases are checked against a full snapshot of every relevant table —
 * whole rows, so `createdAt`, `updatedAt` and every status column are compared,
 * not just how many rows there are.
 */
class ImportConfirmationStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: ImportConfirmationStore
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_780_000_000_000)

    @BeforeTest
    fun openTemporaryDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = newStore(IdGenerator.Random)
    }

    @AfterTest
    fun closeAndDeleteTemporaryDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private fun newStore(idGenerator: IdGenerator) =
        ImportConfirmationStore(
            importDao = database.importDao(),
            itemDao = database.itemDao(),
            gameDao = database.gameDao(),
            idGenerator = idGenerator,
            clock = StoppedClock(moment),
        )

    // ----------------------------------------------------------------- setup

    /** A game with two items, an import with two cells, and a draft on each cell. */
    private class Fixture(
        val batchId: EntityId,
        val gameId: EntityId,
        val itemOneId: EntityId,
        val itemTwoId: EntityId,
        val blockOneId: EntityId,
        val blockTwoId: EntityId,
        val draftOneId: EntityId,
        val draftTwoId: EntityId,
    )

    private suspend fun given(
        draftCount: Int = 2,
        processed: Boolean = true,
    ): Fixture {
        val game = aGame(name = "Harmonies")
        val itemOne = anItem(gameId = game.id, name = "Token")
        val itemTwo = anItem(gameId = game.id, name = "Kart")
        database.gameDao().insert(game)
        database.itemDao().insert(itemOne)
        database.itemDao().insert(itemTwo)

        val batch = anImportBatch(rawBlockCount = 2)
        val blockOne = aRawImportBlock(batch.id, rowIndex = 1, columnIndex = 1, rawText = "15 KIRMIZI**")
        val blockTwo = aRawImportBlock(batch.id, rowIndex = 2, columnIndex = 1, rawText = "19 YEŞİL**")
        database.importDao().saveDraftBatch(batch, listOf(blockOne, blockTwo))
        if (processed) {
            database.importDao().setRawBlockProcessed(blockOne.id, true, moment)
            database.importDao().setRawBlockProcessed(blockTwo.id, true, moment)
        }

        val draftOne = aDraftTask(blockOne.id, name = "Kırmızı token")
        val draftTwo = aDraftTask(blockTwo.id, name = "Yeşil kart")
        database.importDao().addDraftTaskUnderReview(draftOne)
        if (draftCount > 1) database.importDao().addDraftTaskUnderReview(draftTwo)

        return Fixture(
            batchId = batch.id,
            gameId = game.id,
            itemOneId = itemOne.id,
            itemTwoId = itemTwo.id,
            blockOneId = blockOne.id,
            blockTwoId = blockTwo.id,
            draftOneId = draftOne.id,
            draftTwoId = draftTwo.id,
        )
    }

    /** Points both drafts at items and gives them a pool, so the batch is ready. */
    private suspend fun aimBothDrafts(
        fixture: Fixture,
        firstItemId: EntityId = fixture.itemOneId,
        secondItemId: EntityId = fixture.itemOneId,
    ) {
        store.aimDraft(fixture.draftOneId, firstItemId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
        store.aimDraft(fixture.draftTwoId, secondItemId, PoolType.CARD, TrackingMode.PIPELINE)
    }

    // -------------------------------------------------------------- snapshot

    private data class Snapshot(
        val batches: List<ImportBatchEntity>,
        val blocks: List<RawImportBlockEntity>,
        val drafts: List<DraftTaskEntity>,
        val games: List<GameEntity>,
        val items: List<ItemEntity>,
        val tasks: List<TaskEntity>,
    )

    /** Every column of every row that a confirmation could possibly touch. */
    private suspend fun snapshot(batchId: EntityId) =
        Snapshot(
            batches = database.importDao().allBatches(),
            blocks = database.importDao().rawBlocksOfBatch(batchId),
            drafts = database.importDao().draftTasksOfBatch(batchId),
            games = database.gameDao().allGamesIncludingDeleted(),
            items = database.itemDao().allItemsIncludingDeleted(),
            tasks = database.taskDao().allTasksIncludingArchivedAndDeleted(),
        )

    private suspend fun tasks() = database.taskDao().allTasksIncludingArchivedAndDeleted()

    private suspend fun batch(batchId: EntityId) = assertNotNull(database.importDao().batchById(batchId))

    private suspend fun draft(draftId: EntityId) = assertNotNull(database.importDao().draftTaskById(draftId))

    // ------------------------------------------------------- the happy path

    @Test
    fun `confirming a ready import creates one task per draft under the chosen item`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)

            val result = store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)

            assertEquals(2, result.createdTaskCount)
            val created = tasks()
            assertEquals(2, created.size)
            assertEquals(setOf(fixture.itemOneId), created.map { it.itemId }.toSet())
        }

    @Test
    fun `every field the user decided survives into the real task`() =
        runBlocking {
            val fixture = given(draftCount = 1)
            store.aimDraft(fixture.draftOneId, fixture.itemTwoId, PoolType.SPECIAL, TrackingMode.COUNTED)

            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)

            val task = tasks().single()
            assertEquals("Kırmızı token", task.name)
            assertEquals(fixture.itemTwoId, task.itemId)
            assertEquals(PoolType.SPECIAL, task.poolType)
            assertEquals(TrackingMode.COUNTED, task.trackingMode)
            assertEquals(moment, task.createdAt)
            assertEquals(moment, task.updatedAt)
            assertNull(task.deletedAt)
        }

    @Test
    fun `a task keeps a trail back to the cell it came from`() =
        runBlocking {
            val fixture = given(draftCount = 1)
            store.aimDraft(fixture.draftOneId, fixture.itemOneId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)

            assertEquals(fixture.blockOneId, tasks().single().sourceRawImportBlockId)
        }

    @Test
    fun `each draft records the task it produced`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)

            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)

            val taskIds = tasks().map { it.id }.toSet()
            val materialized = listOf(fixture.draftOneId, fixture.draftTwoId).map { draft(it).materializedTaskId }
            assertEquals(taskIds, materialized.filterNotNull().toSet())
            assertEquals(2, materialized.filterNotNull().distinct().size, "two drafts cannot share one task")
        }

    @Test
    fun `drafts aimed at two different items each go to their own item`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture, firstItemId = fixture.itemOneId, secondItemId = fixture.itemTwoId)

            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)

            val byName = tasks().associate { it.name to it.itemId }
            assertEquals(fixture.itemOneId, byName.getValue("Kırmızı token"))
            assertEquals(fixture.itemTwoId, byName.getValue("Yeşil kart"))
        }

    @Test
    fun `confirming an import creates no game and no item`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)
            val gamesBefore = database.gameDao().allGamesIncludingDeleted()
            val itemsBefore = database.itemDao().allItemsIncludingDeleted()

            val result = store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)

            assertEquals(0, result.createdGameCount)
            assertEquals(0, batch(fixture.batchId).createdGameCount)
            assertEquals(gamesBefore, database.gameDao().allGamesIncludingDeleted())
            assertEquals(itemsBefore, database.itemDao().allItemsIncludingDeleted())
        }

    @Test
    fun `a confirmed batch records the status and the exact number of tasks it made`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)

            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)

            val confirmed = batch(fixture.batchId)
            assertEquals(ImportBatchStatus.CONFIRMED, confirmed.status)
            assertEquals(2, confirmed.createdTaskCount)
            assertEquals(tasks().size, confirmed.createdTaskCount)
            assertEquals(moment, confirmed.updatedAt)
        }

    @Test
    fun `raw cells and drafts are kept after a confirmation as the source they are`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)

            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)

            assertEquals(2, database.importDao().rawBlocksOfBatch(fixture.batchId).size)
            assertEquals(2, database.importDao().draftTasksOfBatch(fixture.batchId).size)
        }

    // ------------------------------------------------- the guarded refusals

    @Test
    fun `a second confirmation is refused and writes nothing at all`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)
            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)
            val afterFirst = snapshot(fixture.batchId)

            val refusal =
                assertFailsWith<ImportConfirmationException> {
                    store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)
                }

            assertEquals(ImportConfirmationFailure.ALREADY_CONFIRMED, refusal.failure)
            assertEquals(afterFirst, snapshot(fixture.batchId), "a refused confirmation must change nothing")
            assertEquals(2, tasks().size, "a second confirmation must not double the tasks")
        }

    @Test
    fun `an unknown import is refused`() =
        runBlocking {
            val refusal =
                assertFailsWith<ImportConfirmationException> {
                    store.confirm(IdGenerator.Random.newId(), acknowledgeUnprocessedBlocks = false)
                }

            assertEquals(ImportConfirmationFailure.BATCH_NOT_FOUND, refusal.failure)
            assertEquals(emptyList(), tasks())
        }

    @Test
    fun `an import with no drafts is refused`() =
        runBlocking {
            val batch = anImportBatch(rawBlockCount = 1)
            val block = aRawImportBlock(batch.id)
            database.importDao().saveDraftBatch(batch, listOf(block))
            database.importDao().setRawBlockProcessed(block.id, true, moment)
            val before = snapshot(batch.id)

            val refusal =
                assertFailsWith<ImportConfirmationException> {
                    store.confirm(batch.id, acknowledgeUnprocessedBlocks = false)
                }

            assertEquals(ImportConfirmationFailure.NO_DRAFTS_TO_CONFIRM, refusal.failure)
            assertEquals(before, snapshot(batch.id))
        }

    @Test
    fun `an import cannot be confirmed while there is no item anywhere to put a task under`() =
        runBlocking {
            val batch = anImportBatch(rawBlockCount = 1)
            val block = aRawImportBlock(batch.id)
            database.importDao().saveDraftBatch(batch, listOf(block))
            database.importDao().setRawBlockProcessed(block.id, true, moment)
            database.importDao().addDraftTaskUnderReview(aDraftTask(block.id))
            val before = snapshot(batch.id)

            val refusal =
                assertFailsWith<ImportConfirmationException> {
                    store.confirm(batch.id, acknowledgeUnprocessedBlocks = false)
                }

            assertEquals(ImportConfirmationFailure.NO_ITEMS_AVAILABLE, refusal.failure)
            assertEquals(before, snapshot(batch.id))
        }

    @Test
    fun `unreviewed cells stop a confirmation until the user says to go ahead`() =
        runBlocking {
            val fixture = given(processed = false)
            aimBothDrafts(fixture)
            val before = snapshot(fixture.batchId)

            val refusal =
                assertFailsWith<ImportConfirmationException> {
                    store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)
                }

            assertEquals(ImportConfirmationFailure.UNPROCESSED_BLOCKS_NOT_ACKNOWLEDGED, refusal.failure)
            assertEquals(before, snapshot(fixture.batchId))

            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = true)
            assertEquals(2, tasks().size, "acknowledging the warning must let the confirmation through")
        }

    @Test
    fun `a draft with no target item stops the whole batch`() =
        runBlocking {
            val fixture = given()
            store.aimDraft(fixture.draftOneId, fixture.itemOneId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
            val before = snapshot(fixture.batchId)

            val refusal =
                assertFailsWith<ImportConfirmationException> {
                    store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)
                }

            assertEquals(ImportConfirmationFailure.TARGET_ITEM_MISSING, refusal.failure)
            assertEquals(fixture.draftTwoId, refusal.draftTaskId)
            assertEquals(before, snapshot(fixture.batchId), "the ready draft must not be written either")
            assertEquals(emptyList(), tasks())
        }

    @Test
    fun `a draft with no pool chosen stops the whole batch`() =
        runBlocking {
            val fixture = given(draftCount = 1)
            store.aimDraft(fixture.draftOneId, fixture.itemOneId, null, null)
            val before = snapshot(fixture.batchId)

            val refusal =
                assertFailsWith<ImportConfirmationException> {
                    store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)
                }

            assertEquals(ImportConfirmationFailure.POOL_TYPE_MISSING, refusal.failure)
            assertEquals(before, snapshot(fixture.batchId))
        }

    @Test
    fun `a draft aimed at a soft deleted item is refused`() =
        runBlocking {
            val fixture = given(draftCount = 1)
            store.aimDraft(fixture.draftOneId, fixture.itemOneId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
            database.itemDao().softDelete(fixture.itemOneId, moment)
            val before = snapshot(fixture.batchId)

            val refusal =
                assertFailsWith<ImportConfirmationException> {
                    store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)
                }

            assertEquals(ImportConfirmationFailure.TARGET_ITEM_NOT_AVAILABLE, refusal.failure)
            assertEquals(before, snapshot(fixture.batchId))
            assertEquals(emptyList(), tasks())
        }

    @Test
    fun `a draft whose game was deleted under it is refused`() =
        runBlocking {
            val fixture = given(draftCount = 1)
            // A second game keeps items in existence, so what this proves is the
            // per-draft chain check and not the "no items at all" guard above it.
            val otherGame = aGame(name = "Root")
            database.gameDao().insert(otherGame)
            database.itemDao().insert(anItem(gameId = otherGame.id, name = "Meeple"))
            store.aimDraft(fixture.draftOneId, fixture.itemOneId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
            database.gameDao().softDelete(fixture.gameId, moment)
            val before = snapshot(fixture.batchId)

            val refusal =
                assertFailsWith<ImportConfirmationException> {
                    store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)
                }

            assertEquals(ImportConfirmationFailure.TARGET_ITEM_NOT_AVAILABLE, refusal.failure)
            assertEquals(before, snapshot(fixture.batchId))
        }

    @Test
    fun `another import's drafts are never swept into this one's confirmation`() =
        runBlocking {
            val mine = given(draftCount = 1)
            store.aimDraft(mine.draftOneId, mine.itemOneId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            val other = anImportBatch(sha256 = dev.pnptracker.data.database.SHA_256_TWO, rawBlockCount = 1)
            val otherBlock = aRawImportBlock(other.id, rawText = "başka dosya")
            database.importDao().saveDraftBatch(other, listOf(otherBlock))
            database.importDao().setRawBlockProcessed(otherBlock.id, true, moment)
            val otherDraft = aDraftTask(otherBlock.id, name = "Başka taslak")
            database.importDao().addDraftTaskUnderReview(otherDraft)

            store.confirm(mine.batchId, acknowledgeUnprocessedBlocks = false)

            assertEquals(listOf("Kırmızı token"), tasks().map { it.name })
            assertNull(draft(otherDraft.id).materializedTaskId, "the other import's draft must be untouched")
            assertEquals(ImportBatchStatus.DRAFT, batch(other.id).status)
        }

    // --------------------------------------------------- the rollback proof

    /**
     * Hands out one identifier twice.
     *
     * This is a real thing that can go wrong rather than a switch put in for the
     * test: two rows claiming one primary key is exactly what a broken source of
     * identifiers would produce. It fails on the *second* task, so by the time
     * the database refuses, a task row and a draft update are already written —
     * which is what makes it a proof that they are rolled back and not merely
     * never attempted.
     */
    private class CollidingIdGenerator(
        private val repeated: EntityId,
    ) : IdGenerator {
        override fun newId(): EntityId = repeated
    }

    @Test
    fun `a failure late in the confirmation rolls back the writes that already succeeded`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)
            val before = snapshot(fixture.batchId)
            val colliding = newStore(CollidingIdGenerator(IdGenerator.Random.newId()))

            val failure =
                assertFailsWith<ImportConfirmationException> {
                    colliding.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)
                }

            assertEquals(ImportConfirmationFailure.COULD_NOT_SAVE, failure.failure)
            assertEquals(emptyList(), tasks(), "the first task must not survive the failure")
            assertEquals(
                before,
                snapshot(fixture.batchId),
                "every column of every table must be exactly as it was before the attempt",
            )
        }

    @Test
    fun `a batch that failed to confirm is still a draft and can be confirmed afterwards`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)
            val colliding = newStore(CollidingIdGenerator(IdGenerator.Random.newId()))
            assertFailsWith<ImportConfirmationException> {
                colliding.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)
            }

            assertEquals(ImportBatchStatus.DRAFT, batch(fixture.batchId).status)
            assertEquals(0, batch(fixture.batchId).createdTaskCount)

            val result = store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)
            assertEquals(2, result.createdTaskCount)
        }

    @Test
    fun `only one of two confirmations of the same import can succeed`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)

            val outcomes =
                listOf(1, 2).map {
                    runCatching { store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false) }
                }

            assertEquals(1, outcomes.count { it.isSuccess }, "exactly one confirmation may go through")
            assertEquals(2, tasks().size, "the losing confirmation must add no tasks")
            assertEquals(
                ImportConfirmationFailure.ALREADY_CONFIRMED,
                (outcomes.first { it.isFailure }.exceptionOrNull() as ImportConfirmationException).failure,
            )
        }

    // ------------------------------------------------- after a confirmation

    @Test
    fun `a confirmed import refuses to have its cells re-marked`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)
            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)
            val after = snapshot(fixture.batchId)

            assertFailsWith<IllegalArgumentException> {
                database.importDao().setRawBlockProcessed(fixture.blockOneId, false, moment)
            }

            assertEquals(after, snapshot(fixture.batchId))
        }

    @Test
    fun `a confirmed import refuses new drafts`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)
            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)

            assertFailsWith<IllegalArgumentException> {
                database.importDao().addDraftTaskUnderReview(aDraftTask(fixture.blockOneId, name = "Sonradan"))
            }

            assertEquals(2, database.importDao().draftTasksOfBatch(fixture.batchId).size)
        }

    @Test
    fun `a confirmed import refuses to have a draft re-aimed`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)
            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)
            val after = snapshot(fixture.batchId)

            assertFailsWith<IllegalArgumentException> {
                store.aimDraft(fixture.draftOneId, fixture.itemTwoId, PoolType.CARD, TrackingMode.PIPELINE)
            }

            assertEquals(after, snapshot(fixture.batchId))
        }

    @Test
    fun `a confirmed import cannot be discarded`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)
            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)

            assertFailsWith<IllegalArgumentException> {
                database.importDao().discardDraftBatch(fixture.batchId)
            }

            assertEquals(ImportBatchStatus.CONFIRMED, batch(fixture.batchId).status)
        }

    @Test
    fun `tasks and the confirmed batch are still there after closing and reopening`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture, firstItemId = fixture.itemOneId, secondItemId = fixture.itemTwoId)
            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)

            database.close()
            database = DatabaseFactory().open(directory.databaseFile)
            store = newStore(IdGenerator.Random)

            val reopened = batch(fixture.batchId)
            assertEquals(ImportBatchStatus.CONFIRMED, reopened.status)
            assertEquals(2, reopened.createdTaskCount)
            assertEquals(0, reopened.createdGameCount)
            val byName = tasks().associate { it.name to it.itemId }
            assertEquals(fixture.itemOneId, byName.getValue("Kırmızı token"))
            assertEquals(fixture.itemTwoId, byName.getValue("Yeşil kart"))
        }

    // ------------------------------------------------------------- aiming

    @Test
    fun `aiming a draft at an item that is not there is refused and writes nothing`() =
        runBlocking {
            val fixture = given(draftCount = 1)
            val before = snapshot(fixture.batchId)

            assertFailsWith<IllegalArgumentException> {
                store.aimDraft(
                    fixture.draftOneId,
                    IdGenerator.Random.newId(),
                    PoolType.THREE_D,
                    TrackingMode.THREE_D_BATCH,
                )
            }

            assertEquals(before, snapshot(fixture.batchId))
        }

    @Test
    fun `a pool and a tracking mode that do not go together are refused`() =
        runBlocking {
            val fixture = given(draftCount = 1)

            assertFailsWith<IllegalArgumentException> {
                store.aimDraft(fixture.draftOneId, fixture.itemOneId, PoolType.THREE_D, TrackingMode.PIPELINE)
            }

            assertNull(draft(fixture.draftOneId).selectedPoolType)
        }

    @Test
    fun `aiming a draft records the item the pool and the mode`() =
        runBlocking {
            val fixture = given(draftCount = 1)

            store.aimDraft(fixture.draftOneId, fixture.itemTwoId, PoolType.SPECIAL, TrackingMode.CHECKLIST)

            val aimed = draft(fixture.draftOneId)
            assertEquals(fixture.itemTwoId, aimed.targetItemId)
            assertEquals(PoolType.SPECIAL, aimed.selectedPoolType)
            assertEquals(TrackingMode.CHECKLIST, aimed.selectedTrackingMode)
            assertEquals(moment, aimed.updatedAt)
        }

    // ------------------------------------------------------------ summaries

    @Test
    fun `a summary counts what is ready and names what is not`() =
        runBlocking {
            val fixture = given()
            store.aimDraft(fixture.draftOneId, fixture.itemOneId, PoolType.THREE_D, TrackingMode.THREE_D_BATCH)

            val summary = assertNotNull(store.summarize(fixture.batchId))

            assertEquals(2, summary.draftTaskCount)
            assertEquals(1, summary.readyTaskCount)
            assertEquals(listOf(fixture.draftTwoId), summary.problems.map { it.draftTaskId })
            assertEquals(ImportConfirmationFailure.TARGET_ITEM_MISSING, summary.problems.single().failure)
            assertTrue(!summary.canConfirm)
        }

    @Test
    fun `a summary of a ready import allows confirming and counts every draft`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)

            val summary = assertNotNull(store.summarize(fixture.batchId))

            assertEquals(2, summary.readyTaskCount)
            assertEquals(emptyList(), summary.problems)
            assertEquals(0, summary.unprocessedBlockCount)
            assertTrue(summary.canConfirm)
        }

    @Test
    fun `a summary counts the cells the user has not reviewed yet`() =
        runBlocking {
            val fixture = given(processed = false)
            aimBothDrafts(fixture)

            val summary = assertNotNull(store.summarize(fixture.batchId))

            assertEquals(2, summary.unprocessedBlockCount)
            assertTrue(summary.needsUnprocessedAcknowledgement)
            assertTrue(summary.canConfirm, "unreviewed cells warn but do not block on their own")
        }

    @Test
    fun `a summary of a confirmed import refuses another confirmation`() =
        runBlocking {
            val fixture = given()
            aimBothDrafts(fixture)
            store.confirm(fixture.batchId, acknowledgeUnprocessedBlocks = false)

            val summary = assertNotNull(store.summarize(fixture.batchId))

            assertTrue(summary.isConfirmed)
            assertTrue(!summary.isStillADraft)
            assertTrue(!summary.canConfirm)
            assertEquals(ImportConfirmationFailure.ALREADY_CONFIRMED, summary.blockingFailure)
        }

    @Test
    fun `a summary of an import that is gone is absent rather than empty`() =
        runBlocking {
            assertNull(store.summarize(IdGenerator.Random.newId()))
        }
}
