package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.data.repository.ImportRollback
import dev.pnptracker.data.repository.SettledImport
import dev.pnptracker.domain.importrollback.BlockedCell
import dev.pnptracker.domain.importrollback.BlockedTask
import dev.pnptracker.domain.importrollback.CellObstacle
import dev.pnptracker.domain.importrollback.ImportRollbackException
import dev.pnptracker.domain.importrollback.ImportRollbackFailure
import dev.pnptracker.domain.importrollback.ImportRollbackPreview
import dev.pnptracker.domain.importrollback.ImportRollbackResult
import dev.pnptracker.domain.importrollback.TaskObstacle
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FIRST = IdGenerator.Random.newId()
private val SECOND = IdGenerator.Random.newId()
private val GAME = IdGenerator.Random.newId()
private val CELL = IdGenerator.Random.newId()
private val TASK = IdGenerator.Random.newId()

private fun settled(
    batchId: EntityId = FIRST,
    fileName: String = "Kitap.xlsx",
    createdTaskCount: Int = 2,
    status: ImportBatchStatus = ImportBatchStatus.CONFIRMED,
) = SettledImport(
    batchId = batchId,
    fileName = fileName,
    sheetName = "Sayfa1",
    createdTaskCount = createdTaskCount,
    status = status,
)

private fun safePreview(
    batchId: EntityId = FIRST,
    taskCount: Int = 2,
    cellCount: Int = 1,
    gameCount: Int = 1,
) = ImportRollbackPreview(
    batchId = batchId,
    status = ImportBatchStatus.CONFIRMED,
    taskCount = taskCount,
    cellCount = cellCount,
    gameCount = gameCount,
    blockedTasks = emptyList(),
    blockedCells = emptyList(),
    blockingFailure = null,
)

private fun blockedPreview(
    failure: ImportRollbackFailure,
    blockedTasks: List<BlockedTask> = emptyList(),
    blockedCells: List<BlockedCell> = emptyList(),
) = ImportRollbackPreview(
    batchId = FIRST,
    status = ImportBatchStatus.CONFIRMED,
    // A blocked plan carries nothing to do, which is what the engine returns.
    taskCount = 0,
    cellCount = 0,
    gameCount = 0,
    blockedTasks = blockedTasks,
    blockedCells = blockedCells,
    blockingFailure = failure,
)

/**
 * A stand in for the engine that records what it was asked to do.
 *
 * It decides nothing: every answer is set by the test. That is the point — what
 * is being proved here is that the screen asks once, shows whatever came back
 * and never works out an answer of its own.
 */
private class FakeRollback(
    imports: List<SettledImport> = listOf(settled()),
) : ImportRollback {
    val list = MutableStateFlow(imports)

    var previewCalls = 0
        private set
    var rollBackCalls = 0
        private set

    var preview: ImportRollbackPreview = safePreview()
    var previewFailsWith: ImportRollbackException? = null
    var rollBackFailsWith: ImportRollbackException? = null
    var result = ImportRollbackResult(FIRST, removedTaskCount = 2, restoredCellCount = 1, affectedGameCount = 1)

    /** Holds the next call open, so a second one can be tried while it is in flight. */
    var previewGate: CompletableDeferred<Unit>? = null
    var rollBackGate: CompletableDeferred<Unit>? = null

    /** Thrown by the list itself, the way storage refusing a read reaches the screen. */
    var listFailsWith: ImportRollbackException? = null

    override fun observeSettledImports(): Flow<List<SettledImport>> =
        listFailsWith?.let { refusal -> flow<List<SettledImport>> { throw refusal } } ?: list

    override suspend fun previewRollback(batchId: EntityId): ImportRollbackPreview {
        previewCalls += 1
        previewGate?.await()
        previewFailsWith?.let { throw it }
        return preview
    }

    override suspend fun rollBack(batchId: EntityId): ImportRollbackResult {
        rollBackCalls += 1
        rollBackGate?.await()
        rollBackFailsWith?.let { throw it }
        return result
    }
}

/**
 * Driving the confirmed-import list and one rollback, without a database.
 *
 * Everything asserted is a state the screen draws from. The rules themselves are
 * proved against a real database elsewhere; what matters here is that no rule is
 * decided a second time up in the interface, that exactly one surface is ever
 * open, and that a second press cannot start a second transaction.
 */
class ImportRollbackControllerTest {
    private fun collecting(
        fake: FakeRollback,
        body: suspend (ImportRollbackController) -> Unit,
    ) = runBlocking {
        val controller = ImportRollbackController(fake)
        val job: Job = CoroutineScope(Dispatchers.Unconfined).launch { controller.observeSettledImports() }
        yield()
        body(controller)
        job.cancelAndJoin()
    }

    // ----------------------------------------------------------------- the list

    @Test
    fun `the confirmed imports arrive in the order the store gave them`() {
        val fake =
            FakeRollback(
                listOf(
                    settled(FIRST, "Yeni.xlsx"),
                    settled(SECOND, "Eski.csv", status = ImportBatchStatus.ROLLED_BACK),
                ),
            )
        collecting(fake) { controller ->
            val ready = assertIs<SettledImportsState.Ready>(controller.imports)

            // The order is the query's, not one worked out again here: two
            // orderings of one list is how a row moves under the user's pointer.
            assertEquals(listOf(FIRST, SECOND), ready.imports.map { it.batchId })
            assertEquals(listOf(true, false), ready.imports.map { it.canBeTakenBack })
        }
    }

    @Test
    fun `no confirmed import at all is its own answer`() {
        collecting(FakeRollback(emptyList())) { controller ->
            assertEquals(SettledImportsState.Empty, controller.imports)
        }
    }

    @Test
    fun `a list storage would not read is not shown as an empty one`() {
        val fake = FakeRollback()
        fake.listFailsWith = ImportRollbackException(ImportRollbackFailure.COULD_NOT_SAVE)
        collecting(fake) { controller ->
            // Not Empty: "there are none" and "we could not look" are different
            // things to tell somebody who is looking for their import.
            assertEquals(SettledImportsState.Unreadable, controller.imports)
        }
    }

    @Test
    fun `the list starts out as not read yet rather than as empty`() {
        // Before anything is collected at all, which is the first frame a screen
        // draws and the one an empty message would be wrong on.
        assertEquals(SettledImportsState.Loading, ImportRollbackController(FakeRollback()).imports)
    }

    // -------------------------------------------------------------- the preview

    @Test
    fun `asking about a safe import offers what it would cost`() {
        val fake = FakeRollback()
        fake.preview = safePreview(taskCount = 6, cellCount = 2, gameCount = 2)
        collecting(fake) { controller ->
            controller.ask(FIRST)

            val offered = assertIs<RollbackFlowState.Offered>(controller.flow)
            assertEquals(6, offered.preview.taskCount)
            assertEquals(2, offered.preview.cellCount)
            assertEquals(2, offered.preview.gameCount)
            assertEquals(1, fake.previewCalls)
        }
    }

    @Test
    fun `every refusal the engine has reaches the screen as itself`() {
        // Exhaustive on purpose: a failure the screen cannot tell apart from
        // another is a failure the user is given the wrong next step for.
        ImportRollbackFailure.entries.forEach { failure ->
            val fake = FakeRollback()
            fake.preview = blockedPreview(failure)
            collecting(fake) { controller ->
                controller.ask(FIRST)

                val blocked = assertIs<RollbackFlowState.Blocked>(controller.flow, "$failure was not shown as blocked")
                assertEquals(failure, blocked.blockage.failure)
            }
        }
    }

    @Test
    fun `the tasks and cells in the way are carried through untouched`() {
        val fake = FakeRollback()
        fake.preview =
            blockedPreview(
                ImportRollbackFailure.TASKS_WERE_EDITED,
                blockedTasks = listOf(BlockedTask(TASK, "Kırmızı ev", TaskObstacle.EDITED)),
                blockedCells = listOf(BlockedCell(CELL, GAME, "Harmonies", CellColumnType.THREE_D, CellObstacle.DOCUMENT_CHANGED)),
            )
        collecting(fake) { controller ->
            controller.ask(FIRST)

            val blocked = assertIs<RollbackFlowState.Blocked>(controller.flow)
            assertEquals(listOf("Kırmızı ev"), blocked.blockage.blockedTasks.map { it.taskName })
            assertEquals(listOf("Harmonies"), blocked.blockage.blockedCells.map { it.gameName })
            val cell = blocked.blockage.blockedCells.single()
            assertEquals(CellColumnType.THREE_D, cell.columnType)
        }
    }

    @Test
    fun `a preview storage refused is explained rather than left blank`() {
        val fake = FakeRollback()
        fake.previewFailsWith = ImportRollbackException(ImportRollbackFailure.COULD_NOT_SAVE)
        collecting(fake) { controller ->
            controller.ask(FIRST)

            assertEquals(
                ImportRollbackFailure.COULD_NOT_SAVE,
                assertIs<RollbackFlowState.Blocked>(controller.flow).blockage.failure,
            )
        }
    }

    @Test
    fun `a second press while the preview is being read asks nothing more`() {
        val fake = FakeRollback()
        val gate = CompletableDeferred<Unit>()
        fake.previewGate = gate
        runBlocking {
            val controller = ImportRollbackController(fake)
            val job = CoroutineScope(Dispatchers.Unconfined).launch { controller.ask(FIRST) }
            yield()
            assertIs<RollbackFlowState.Asking>(controller.flow)

            controller.ask(FIRST)
            controller.ask(SECOND)

            assertEquals(1, fake.previewCalls, "a repeated press started a second reading")
            gate.complete(Unit)
            job.cancelAndJoin()
        }
    }

    // --------------------------------------------------------- taking one back

    @Test
    fun `taking it back reports what the engine says it did`() {
        val fake = FakeRollback()
        fake.result = ImportRollbackResult(FIRST, removedTaskCount = 6, restoredCellCount = 2, affectedGameCount = 2)
        collecting(fake) { controller ->
            controller.ask(FIRST)
            controller.takeBack()

            val done = assertIs<RollbackFlowState.TakenBack>(controller.flow)
            assertEquals(6, done.result.removedTaskCount)
            assertEquals(2, done.result.restoredCellCount)
            assertEquals(1, fake.rollBackCalls)
        }
    }

    @Test
    fun `a second press while it is being taken back starts nothing`() {
        val fake = FakeRollback()
        val gate = CompletableDeferred<Unit>()
        fake.rollBackGate = gate
        runBlocking {
            val controller = ImportRollbackController(fake)
            controller.ask(FIRST)
            val job = CoroutineScope(Dispatchers.Unconfined).launch { controller.takeBack() }
            yield()
            assertIs<RollbackFlowState.TakingBack>(controller.flow)

            controller.takeBack()
            controller.takeBack()

            assertEquals(1, fake.rollBackCalls, "a repeated press started a second transaction")
            gate.complete(Unit)
            job.cancelAndJoin()
        }
    }

    @Test
    fun `a conflict that appeared after the preview is shown without losing the panel`() {
        val fake = FakeRollback()
        fake.preview = safePreview(taskCount = 4)
        fake.rollBackFailsWith =
            ImportRollbackException(
                ImportRollbackFailure.CELLS_WERE_EDITED,
                blockedCells = listOf(BlockedCell(CELL, GAME, "Harmonies", CellColumnType.THREE_D, CellObstacle.DOCUMENT_CHANGED)),
            )
        collecting(fake) { controller ->
            controller.ask(FIRST)
            controller.takeBack()

            // The preview said yes and the transaction said no, which is exactly
            // why the transaction reads everything again. What the user was
            // looking at stays on screen with the reason beside it.
            val refused = assertIs<RollbackFlowState.Refused>(controller.flow)
            assertEquals(ImportRollbackFailure.CELLS_WERE_EDITED, refused.blockage.failure)
            assertEquals(4, refused.preview.taskCount, "the panel lost what the user was reading")
            assertEquals(listOf("Harmonies"), refused.blockage.blockedCells.map { it.gameName })
        }
    }

    @Test
    fun `a refused attempt can be closed and leaves nothing open`() {
        val fake = FakeRollback()
        fake.rollBackFailsWith = ImportRollbackException(ImportRollbackFailure.TASKS_WERE_EDITED)
        collecting(fake) { controller ->
            controller.ask(FIRST)
            controller.takeBack()
            controller.close()

            assertEquals(RollbackFlowState.Closed, controller.flow)
        }
    }

    @Test
    fun `nothing can be closed while it is happening`() {
        val fake = FakeRollback()
        val gate = CompletableDeferred<Unit>()
        fake.rollBackGate = gate
        runBlocking {
            val controller = ImportRollbackController(fake)
            controller.ask(FIRST)
            val job = CoroutineScope(Dispatchers.Unconfined).launch { controller.takeBack() }
            yield()

            controller.close()

            // Escape is not a way to walk away from a transaction that is
            // already running; there would be nowhere for its answer to land.
            assertIs<RollbackFlowState.TakingBack>(controller.flow)
            gate.complete(Unit)
            job.cancelAndJoin()
        }
    }

    // ------------------------------------------------- the list moving underneath

    @Test
    fun `a new reading of the list does not close what somebody is reading`() {
        val fake = FakeRollback(listOf(settled(FIRST), settled(SECOND, "Eski.csv")))
        collecting(fake) { controller ->
            controller.ask(FIRST)
            assertIs<RollbackFlowState.Offered>(controller.flow)

            // Something else changed in the table; the open import did not.
            fake.list.value = listOf(settled(FIRST), settled(SECOND, "Eski.csv", createdTaskCount = 9))
            yield()

            assertIs<RollbackFlowState.Offered>(controller.flow, "an unrelated change closed the panel")
        }
    }

    @Test
    fun `an offer for an import that has since been taken back says so`() {
        val fake = FakeRollback()
        collecting(fake) { controller ->
            controller.ask(FIRST)
            assertIs<RollbackFlowState.Offered>(controller.flow)

            fake.list.value = listOf(settled(FIRST, status = ImportBatchStatus.ROLLED_BACK))
            yield()

            // Pressing the button would fail for a reason the screen already
            // knew, so the reason is what is shown instead.
            val blocked = assertIs<RollbackFlowState.Blocked>(controller.flow)
            assertEquals(ImportRollbackFailure.ALREADY_ROLLED_BACK, blocked.blockage.failure)
        }
    }

    @Test
    fun `an offer for an import that has gone says that instead`() {
        val fake = FakeRollback()
        collecting(fake) { controller ->
            controller.ask(FIRST)

            fake.list.value = emptyList()
            yield()

            assertEquals(
                ImportRollbackFailure.BATCH_NOT_FOUND,
                assertIs<RollbackFlowState.Blocked>(controller.flow).blockage.failure,
            )
        }
    }

    @Test
    fun `a finished rollback is not overwritten by the list catching up`() {
        val fake = FakeRollback()
        collecting(fake) { controller ->
            controller.ask(FIRST)
            controller.takeBack()

            // Which is exactly what the database does next: the row it just
            // wrote arrives, saying the batch is rolled back.
            fake.list.value = listOf(settled(FIRST, status = ImportBatchStatus.ROLLED_BACK))
            yield()

            assertIs<RollbackFlowState.TakenBack>(controller.flow, "the success turned into a refusal")
            assertEquals(
                listOf(false),
                assertIs<SettledImportsState.Ready>(controller.imports).imports.map { it.canBeTakenBack },
            )
        }
    }

    // ------------------------------------------------------------------ tidying

    @Test
    fun `cancelling the collection leaves nothing listening`() {
        val fake = FakeRollback()
        runBlocking {
            val controller = ImportRollbackController(fake)
            val job = CoroutineScope(Dispatchers.Unconfined).launch { controller.observeSettledImports() }
            yield()
            assertIs<SettledImportsState.Ready>(controller.imports)

            job.cancelAndJoin()
            fake.list.value = listOf(settled(SECOND, "Sonradan.csv"))
            yield()

            assertEquals(
                listOf(FIRST),
                assertIs<SettledImportsState.Ready>(controller.imports).imports.map { it.batchId },
                "a cancelled collection was still updating the screen",
            )
        }
    }

    @Test
    fun `the row a surface was opened from is remembered until the keyboard is put back`() {
        collecting(FakeRollback()) { controller ->
            assertNull(controller.lastAsked)

            controller.ask(FIRST)
            assertEquals(FIRST, controller.lastAsked)

            controller.close()
            assertEquals(FIRST, controller.lastAsked, "the way back was forgotten before the screen used it")
            controller.focusHonoured()
            assertNull(controller.lastAsked)
        }
    }

    @Test
    fun `asking about one import at a time is all the screen can do`() {
        val fake = FakeRollback(listOf(settled(FIRST), settled(SECOND, "Eski.csv")))
        collecting(fake) { controller ->
            controller.ask(FIRST)
            controller.ask(SECOND)

            // The second is refused outright rather than replacing the first:
            // two open confirmations would be two questions about two different
            // imports with one answer between them.
            assertEquals(FIRST, assertIs<RollbackFlowState.Offered>(controller.flow).preview.batchId)
            assertTrue(fake.previewCalls == 1, "a second import was read while the first was open")
        }
    }

    @Test
    fun `a restore closes the surface, because the batch it was about has gone`() {
        val fake = FakeRollback()
        fake.preview = safePreview(taskCount = 6, cellCount = 2, gameCount = 2)
        collecting(fake) { controller ->
            controller.ask(FIRST)
            assertIs<RollbackFlowState.Offered>(controller.flow)

            controller.abandonOpenWork()

            assertEquals(RollbackFlowState.Closed, controller.flow, "an offer was left open over a batch that had been replaced")
            assertNull(controller.lastAsked)
        }
    }
}
