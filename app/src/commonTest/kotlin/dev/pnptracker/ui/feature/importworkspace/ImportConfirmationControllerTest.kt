package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.data.repository.ImportConfirmation
import dev.pnptracker.domain.importconfirm.DraftTaskProblem
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importconfirm.ImportConfirmationResult
import dev.pnptracker.domain.importconfirm.ImportConfirmationSummary
import dev.pnptracker.domain.importconfirm.TargetCellChoice
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val BATCH = IdGenerator.Random.newId()
private val DRAFT_ONE = IdGenerator.Random.newId()
private val DRAFT_TWO = IdGenerator.Random.newId()
private val CELL = IdGenerator.Random.newId()
private val GAME = IdGenerator.Random.newId()

private fun summary(
    status: ImportBatchStatus = ImportBatchStatus.DRAFT,
    draftTaskCount: Int = 2,
    readyTaskCount: Int = 2,
    unprocessedBlockCount: Int = 0,
    problems: List<DraftTaskProblem> = emptyList(),
    hasAnyCell: Boolean = true,
) = ImportConfirmationSummary(
    batchId = BATCH,
    status = status,
    draftTaskCount = draftTaskCount,
    readyTaskCount = readyTaskCount,
    unprocessedBlockCount = unprocessedBlockCount,
    problems = problems,
    hasAnyCell = hasAnyCell,
)

/**
 * A stand in for the store that records what it was asked to do.
 *
 * Nothing here writes anything, which is what makes it usable for proving that
 * cancelling a confirmation asks the store for nothing at all.
 */
private class FakeConfirmation(
    var summary: ImportConfirmationSummary? = summary(),
) : ImportConfirmation {
    val cells = MutableStateFlow(listOf(TargetCellChoice(CELL, GAME, "Harmonies", CellColumnType.THREE_D)))

    var confirmCalls = 0
        private set
    var lastAcknowledged: Boolean? = null
        private set
    val aimed = mutableListOf<Triple<EntityId, EntityId?, PoolType?>>()
    var lastTrackingMode: TrackingMode? = null
        private set

    /** What the next confirmation should do; null means it succeeds. */
    var failWith: ImportConfirmationFailure? = null
    var aimFailsWith: ImportConfirmationFailure? = null

    /** Blocks the next confirmation until released, to test the in-flight guard. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override fun observeTargetCells(): Flow<List<TargetCellChoice>> = cells

    override suspend fun summarize(batchId: EntityId): ImportConfirmationSummary? = summary

    override suspend fun aimDraft(
        draftId: EntityId,
        targetCellId: EntityId?,
        poolType: PoolType?,
        trackingMode: TrackingMode?,
    ) {
        aimFailsWith?.let { throw ImportConfirmationException(it) }
        aimed += Triple(draftId, targetCellId, poolType)
        lastTrackingMode = trackingMode
    }

    override suspend fun confirm(
        batchId: EntityId,
        acknowledgeUnprocessedBlocks: Boolean,
    ): ImportConfirmationResult {
        gate?.await()
        confirmCalls++
        lastAcknowledged = acknowledgeUnprocessedBlocks
        failWith?.let { throw ImportConfirmationException(it) }
        return ImportConfirmationResult(batchId, createdTaskCount = 2, createdGameCount = 0)
    }
}

class ImportConfirmationControllerTest {
    // ------------------------------------------------------------- loading

    @Test
    fun `it starts out loading before anything has been read`() {
        val controller = ImportConfirmationController(FakeConfirmation())

        assertIs<ImportConfirmationState.Loading>(controller.state)
    }

    @Test
    fun `refreshing shows what confirming would do`() =
        runBlocking {
            val controller = ImportConfirmationController(FakeConfirmation())

            controller.refresh(BATCH)

            val ready = assertIs<ImportConfirmationState.Ready>(controller.state)
            assertEquals(2, ready.summary.readyTaskCount)
            assertTrue(ready.summary.canConfirm)
            assertNull(ready.failure)
        }

    @Test
    fun `an import that is gone is reported as gone rather than as empty`() =
        runBlocking<Unit> {
            val controller = ImportConfirmationController(FakeConfirmation(summary = null))

            controller.refresh(BATCH)

            assertIs<ImportConfirmationState.Unavailable>(controller.state)
        }

    @Test
    fun `the cells a draft can be aimed at arrive from the store`() =
        runBlocking {
            val fake = FakeConfirmation()
            val controller = ImportConfirmationController(fake)
            val job = CoroutineScope(Job() + Dispatchers.Unconfined).launch { controller.observeTargetCells() }
            yield()

            assertEquals(listOf(CELL), controller.targetCells.map { it.cellId })

            job.cancelAndJoin()
        }

    // --------------------------------------------------------- the summary

    @Test
    fun `a draft that is not ready is named and stops the confirmation`() =
        runBlocking {
            val fake =
                FakeConfirmation(
                    summary(
                        readyTaskCount = 1,
                        problems =
                            listOf(
                                DraftTaskProblem(
                                    DRAFT_TWO,
                                    "Yeşil kart",
                                    ImportConfirmationFailure.TARGET_CELL_MISSING,
                                ),
                            ),
                    ),
                )
            val controller = ImportConfirmationController(fake)

            controller.refresh(BATCH)

            val ready = assertIs<ImportConfirmationState.Ready>(controller.state)
            assertTrue(!ready.summary.canConfirm)
            assertEquals(ImportConfirmationFailure.TARGET_CELL_MISSING, ready.summary.blockingFailure)
        }

    @Test
    fun `an import with no cells anywhere cannot be confirmed`() =
        runBlocking {
            val controller = ImportConfirmationController(FakeConfirmation(summary(hasAnyCell = false)))

            controller.refresh(BATCH)

            val ready = assertIs<ImportConfirmationState.Ready>(controller.state)
            assertEquals(ImportConfirmationFailure.NO_CELLS_AVAILABLE, ready.summary.blockingFailure)
        }

    @Test
    fun `unreviewed cells warn without blocking on their own`() =
        runBlocking {
            val controller = ImportConfirmationController(FakeConfirmation(summary(unprocessedBlockCount = 3)))

            controller.refresh(BATCH)

            val ready = assertIs<ImportConfirmationState.Ready>(controller.state)
            assertTrue(ready.summary.needsUnprocessedAcknowledgement)
            assertTrue(ready.summary.canConfirm)
        }

    // ------------------------------------------------------------- aiming

    @Test
    fun `aiming a draft records the cell and the pool`() =
        runBlocking {
            val fake = FakeConfirmation()
            val controller = ImportConfirmationController(fake)

            controller.aim(BATCH, DRAFT_ONE, CELL, PoolType.CARD)

            val expected: List<Triple<EntityId, EntityId?, PoolType?>> =
                listOf(Triple(DRAFT_ONE, CELL, PoolType.CARD))
            assertEquals(expected, fake.aimed.toList())
        }

    @Test
    fun `a pool with only one tracking mode gets that mode without asking`() =
        runBlocking {
            val fake = FakeConfirmation()
            val controller = ImportConfirmationController(fake)

            controller.aim(BATCH, DRAFT_ONE, CELL, PoolType.THREE_D)

            assertEquals(TrackingMode.THREE_D_BATCH, fake.lastTrackingMode)
        }

    @Test
    fun `a pool with a real choice is left for the user to make`() {
        assertNull(ImportConfirmationController.onlyTrackingModeOf(PoolType.SPECIAL))
        assertEquals(
            setOf(TrackingMode.CHECKLIST, TrackingMode.COUNTED),
            ImportConfirmationController.trackingModesOf(PoolType.SPECIAL).toSet(),
        )
    }

    @Test
    fun `an aim that does not save is reported without losing the summary`() =
        runBlocking {
            val fake = FakeConfirmation()
            fake.aimFailsWith = ImportConfirmationFailure.COULD_NOT_SAVE
            val controller = ImportConfirmationController(fake)
            controller.refresh(BATCH)

            controller.aim(BATCH, DRAFT_ONE, CELL, PoolType.CARD)

            val ready = assertIs<ImportConfirmationState.Ready>(controller.state)
            assertEquals(ImportConfirmationFailure.COULD_NOT_SAVE, ready.failure)
            assertEquals(2, ready.summary.readyTaskCount, "the summary must still be on screen")
        }

    // ------------------------------------------------------- the two steps

    @Test
    fun `asking opens the second step and writes nothing`() =
        runBlocking {
            val fake = FakeConfirmation()
            val controller = ImportConfirmationController(fake)
            controller.refresh(BATCH)

            controller.ask()

            assertTrue(controller.isAsking)
            assertEquals(0, fake.confirmCalls, "opening the question must not confirm anything")
        }

    @Test
    fun `changing your mind writes nothing at all`() =
        runBlocking<Unit> {
            val fake = FakeConfirmation()
            val controller = ImportConfirmationController(fake)
            controller.refresh(BATCH)
            controller.ask()

            controller.stopAsking()

            assertTrue(!controller.isAsking)
            assertEquals(0, fake.confirmCalls)
            assertEquals(0, fake.aimed.size)
            assertIs<ImportConfirmationState.Ready>(controller.state)
        }

    @Test
    fun `changing your mind forgets that unreviewed cells were accepted`() =
        runBlocking {
            val controller = ImportConfirmationController(FakeConfirmation(summary(unprocessedBlockCount = 2)))
            controller.refresh(BATCH)
            controller.acknowledgeUnprocessed(true)

            controller.stopAsking()

            assertTrue(!controller.hasAcknowledgedUnprocessed)
        }

    // ------------------------------------------------------- confirming

    @Test
    fun `a confirmation that goes through reports what it created`() =
        runBlocking {
            val controller = ImportConfirmationController(FakeConfirmation())
            controller.refresh(BATCH)
            controller.ask()

            controller.confirm(BATCH)

            val confirmed = assertIs<ImportConfirmationState.Confirmed>(controller.state)
            assertEquals(2, confirmed.result.createdTaskCount)
            assertEquals(0, confirmed.result.createdGameCount)
            assertTrue(!controller.isAsking, "the question closes once it is answered")
        }

    @Test
    fun `the user's answer about unreviewed cells is passed to the store`() =
        runBlocking {
            val fake = FakeConfirmation(summary(unprocessedBlockCount = 2))
            val controller = ImportConfirmationController(fake)
            controller.refresh(BATCH)
            controller.acknowledgeUnprocessed(true)

            controller.confirm(BATCH)

            assertEquals(true, fake.lastAcknowledged)
        }

    @Test
    fun `a guarded refusal is shown as a refusal and never as a success`() =
        runBlocking {
            val fake = FakeConfirmation(summary(status = ImportBatchStatus.CONFIRMED))
            fake.failWith = ImportConfirmationFailure.ALREADY_CONFIRMED
            val controller = ImportConfirmationController(fake)
            controller.refresh(BATCH)
            controller.ask()

            controller.confirm(BATCH)

            val ready = assertIs<ImportConfirmationState.Ready>(controller.state)
            assertEquals(ImportConfirmationFailure.ALREADY_CONFIRMED, ready.failure)
            assertTrue(!controller.isAsking)
        }

    @Test
    fun `a confirmation that could not be saved says so and stays confirmable`() =
        runBlocking {
            val fake = FakeConfirmation()
            fake.failWith = ImportConfirmationFailure.COULD_NOT_SAVE
            val controller = ImportConfirmationController(fake)
            controller.refresh(BATCH)

            controller.confirm(BATCH)

            val ready = assertIs<ImportConfirmationState.Ready>(controller.state)
            assertEquals(ImportConfirmationFailure.COULD_NOT_SAVE, ready.failure)
            assertTrue(ready.summary.canConfirm, "a failed write leaves the import confirmable")
        }

    @Test
    fun `a second confirmation cannot start while the first is still running`() =
        runBlocking {
            val fake = FakeConfirmation()
            fake.gate = kotlinx.coroutines.CompletableDeferred()
            val controller = ImportConfirmationController(fake)
            controller.refresh(BATCH)

            val first = CoroutineScope(Job() + Dispatchers.Unconfined).launch { controller.confirm(BATCH) }
            yield()
            assertTrue(controller.isBusy)

            controller.confirm(BATCH)
            assertEquals(0, fake.confirmCalls, "the second call must not reach the store")

            fake.gate?.complete(Unit)
            first.join()
            assertEquals(1, fake.confirmCalls, "exactly one confirmation may reach the store")
        }

    @Test
    fun `a confirmation in flight also blocks aiming and asking`() =
        runBlocking {
            val fake = FakeConfirmation()
            fake.gate = kotlinx.coroutines.CompletableDeferred()
            val controller = ImportConfirmationController(fake)
            controller.refresh(BATCH)
            val running = CoroutineScope(Job() + Dispatchers.Unconfined).launch { controller.confirm(BATCH) }
            yield()

            controller.aim(BATCH, DRAFT_ONE, CELL, PoolType.CARD)
            controller.ask()

            assertEquals(0, fake.aimed.size, "a draft cannot be re-aimed mid confirmation")
            assertTrue(!controller.isAsking)

            fake.gate?.complete(Unit)
            running.join()
        }

    @Test
    fun `an unexpected error is not turned into a user mistake`() =
        runBlocking {
            val exploding =
                object : ImportConfirmation by FakeConfirmation() {
                    override suspend fun confirm(
                        batchId: EntityId,
                        acknowledgeUnprocessedBlocks: Boolean,
                    ): ImportConfirmationResult = throw IllegalStateException("a defect, not a user error")
                }
            val controller = ImportConfirmationController(exploding)
            controller.refresh(BATCH)

            val thrown = runCatching { controller.confirm(BATCH) }.exceptionOrNull()

            assertIs<IllegalStateException>(thrown, "a programming error must travel out as itself")
            assertTrue(!controller.isBusy, "the busy flag must be cleared even then")
        }

    @Test
    fun `a confirmed import is shown as read only rather than as confirmable`() =
        runBlocking {
            val controller =
                ImportConfirmationController(FakeConfirmation(summary(status = ImportBatchStatus.CONFIRMED)))

            controller.refresh(BATCH)

            val ready = assertIs<ImportConfirmationState.Ready>(controller.state)
            assertTrue(ready.summary.isConfirmed)
            assertTrue(!ready.summary.isStillADraft)
            assertTrue(!ready.summary.canConfirm)
        }
}
