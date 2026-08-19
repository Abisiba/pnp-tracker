package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.data.repository.EarlierImport
import dev.pnptracker.data.repository.ImportReview
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.importreview.ImportReviewWorkspace
import dev.pnptracker.domain.importreview.ReviewDraftTask
import dev.pnptracker.domain.importreview.ReviewRawBlock
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.SourceColumnType
import kotlinx.coroutines.CoroutineScope
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

private val BATCH_ID = IdGenerator.Random.newId()

private fun block(
    text: String,
    row: Int,
    column: Int,
    id: EntityId = IdGenerator.Random.newId(),
    isProcessed: Boolean = false,
    hint: HintDecision = HintDecision.NONE,
) = ReviewRawBlock(
    id = id,
    rawText = text,
    sheetName = "Sayfa1",
    rowIndex = row,
    columnIndex = column,
    sourceColumnType = SourceColumnType.THREE_D,
    gameCompletionHint = hint,
    isProcessed = isProcessed,
)

private fun workspaceOf(
    blocks: List<ReviewRawBlock>,
    drafts: List<ReviewDraftTask> = emptyList(),
) = ImportReviewWorkspace(
    batchId = BATCH_ID,
    fileName = "sample-import.xlsx",
    sheetName = "Sayfa1",
    status = ImportBatchStatus.DRAFT,
    rawBlocks = blocks,
    draftTasks = drafts,
)

/**
 * A stand in for storage that a test can push new workspaces through, so the
 * controller can be watched reacting to exactly the sequence a test wants.
 */
private class FakeReview(
    initial: ImportReviewWorkspace? = null,
) : ImportReview {
    val workspace = MutableStateFlow(initial)
    val draftBatches = MutableStateFlow<List<EarlierImport>>(emptyList())
    var failWith: ImportReviewFailure? = null
    val processedCalls = mutableListOf<Pair<EntityId, Boolean>>()

    override fun observeDraftBatches(): Flow<List<EarlierImport>> = draftBatches

    override fun observeWorkspace(batchId: EntityId): Flow<ImportReviewWorkspace?> = workspace

    override suspend fun setProcessed(
        blockId: EntityId,
        isProcessed: Boolean,
    ) {
        failWith?.let { throw ImportReviewException(it) }
        processedCalls += blockId to isProcessed
        val current = workspace.value ?: return
        workspace.value =
            current.copy(
                rawBlocks = current.rawBlocks.map { if (it.id == blockId) it.copy(isProcessed = isProcessed) else it },
            )
    }
}

class ImportReviewControllerTest {
    /** Runs [body] with the controller collecting, then stops collecting. */
    private fun withObserving(
        review: FakeReview,
        body: suspend CoroutineScope.(ImportReviewController) -> Unit,
    ) = runBlocking {
        val controller = ImportReviewController(review)
        val job: Job = launch { controller.observe(BATCH_ID) }
        yield()
        try {
            body(controller)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `it starts out loading`() {
        val controller = ImportReviewController(FakeReview())

        assertIs<ImportReviewState.Loading>(controller.state)
    }

    @Test
    fun `an import that is gone reports itself as unavailable`() {
        withObserving(FakeReview(initial = null)) { controller ->
            assertIs<ImportReviewState.Unavailable>(controller.state)
        }
    }

    @Test
    fun `an import with no cells reports the empty state with its file name`() {
        withObserving(FakeReview(workspaceOf(emptyList()))) { controller ->
            val state = assertIs<ImportReviewState.Empty>(controller.state)
            assertEquals("sample-import.xlsx", state.fileName)
            assertEquals("Sayfa1", state.sheetName)
        }
    }

    @Test
    fun `cells arrive in the order storage gave them`() {
        val blocks = listOf(block("bir", 1, 1), block("iki", 1, 3), block("uc", 2, 0))
        withObserving(FakeReview(workspaceOf(blocks))) { controller ->
            val state = assertIs<ImportReviewState.Content>(controller.state)
            assertEquals(listOf("bir", "iki", "uc"), state.workspace.rawBlocks.map { it.rawText })
        }
    }

    @Test
    fun `cell text reaches the screen exactly as it was stored`() {
        val awkward = "  12 KIRMIZI**\n8 MAVİ  "
        withObserving(FakeReview(workspaceOf(listOf(block(awkward, 1, 1))))) { controller ->
            val state = assertIs<ImportReviewState.Content>(controller.state)
            assertEquals(
                awkward,
                state.workspace.rawBlocks
                    .single()
                    .rawText,
            )
        }
    }

    @Test
    fun `nothing is selected to begin with and every draft is shown`() {
        val one = block("bir", 1, 1)
        val two = block("iki", 1, 2)
        val draft = ReviewDraftTask(IdGenerator.Random.newId(), two.id, "Taslak")
        withObserving(FakeReview(workspaceOf(listOf(one, two), listOf(draft)))) { controller ->
            val state = assertIs<ImportReviewState.Content>(controller.state)
            assertNull(state.selectedBlockId)
            assertEquals(listOf("Taslak"), state.visibleDrafts.map { it.name })
        }
    }

    @Test
    fun `selecting a cell narrows the drafts to that cell`() {
        val one = block("bir", 1, 1)
        val two = block("iki", 1, 2)
        val draft = ReviewDraftTask(IdGenerator.Random.newId(), two.id, "Taslak")
        withObserving(FakeReview(workspaceOf(listOf(one, two), listOf(draft)))) { controller ->
            controller.select(one.id)

            val state = assertIs<ImportReviewState.Content>(controller.state)
            assertEquals(one.id, state.selectedBlockId)
            assertEquals(emptyList(), state.visibleDrafts, "the other cell's draft must not show here")
        }
    }

    @Test
    fun `a cell with no drafts shows an empty right pane`() {
        val one = block("bir", 1, 1)
        withObserving(FakeReview(workspaceOf(listOf(one)))) { controller ->
            controller.select(one.id)

            val state = assertIs<ImportReviewState.Content>(controller.state)
            assertTrue(state.visibleDrafts.isEmpty())
            assertEquals(0, state.workspace.draftTaskCount)
        }
    }

    @Test
    fun `selecting a cell that is not there clears the selection`() {
        withObserving(FakeReview(workspaceOf(listOf(block("bir", 1, 1))))) { controller ->
            controller.select(IdGenerator.Random.newId())

            assertNull(assertIs<ImportReviewState.Content>(controller.state).selectedBlockId)
        }
    }

    @Test
    fun `marking a cell reviewed goes through and comes back`() {
        val one = block("bir", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        withObserving(review) { controller ->
            controller.setProcessed(one.id, true)
            yield()

            assertEquals(listOf(one.id to true), review.processedCalls)
            assertTrue(
                assertIs<ImportReviewState.Content>(controller.state)
                    .workspace.rawBlocks
                    .single()
                    .isProcessed,
            )
        }
    }

    @Test
    fun `taking the mark off again also goes through`() {
        val one = block("bir", 1, 1, isProcessed = true)
        val review = FakeReview(workspaceOf(listOf(one)))
        withObserving(review) { controller ->
            controller.setProcessed(one.id, false)
            yield()

            assertEquals(listOf(one.id to false), review.processedCalls)
            assertTrue(
                !assertIs<ImportReviewState.Content>(controller.state)
                    .workspace.rawBlocks
                    .single()
                    .isProcessed,
            )
        }
    }

    @Test
    fun `marking a cell creates no draft and leaves the import a draft`() {
        val one = block("bir", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        withObserving(review) { controller ->
            controller.setProcessed(one.id, true)
            yield()

            val state = assertIs<ImportReviewState.Content>(controller.state)
            assertEquals(0, state.workspace.draftTaskCount)
            assertTrue(state.workspace.isStillADraft)
        }
    }

    @Test
    fun `a change that could not be saved is reported beside the content`() {
        val one = block("bir", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        review.failWith = ImportReviewFailure.COULD_NOT_SAVE
        withObserving(review) { controller ->
            controller.setProcessed(one.id, true)

            val state = assertIs<ImportReviewState.Content>(controller.state)
            assertEquals(ImportReviewFailure.COULD_NOT_SAVE, state.failure)
            // The old value stands, because nothing was written.
            assertTrue(
                !state.workspace.rawBlocks
                    .single()
                    .isProcessed,
            )
            assertEquals(emptyList(), review.processedCalls)
        }
    }

    @Test
    fun `a later change that works clears the earlier failure`() {
        val one = block("bir", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        review.failWith = ImportReviewFailure.COULD_NOT_SAVE
        withObserving(review) { controller ->
            controller.setProcessed(one.id, true)
            review.failWith = null

            controller.setProcessed(one.id, true)
            yield()

            assertNull(assertIs<ImportReviewState.Content>(controller.state).failure)
        }
    }

    @Test
    fun `an unexpected failure is not turned into a save problem`() {
        val one = block("bir", 1, 1)
        val review =
            object : ImportReview by FakeReview(workspaceOf(listOf(one))) {
                override suspend fun setProcessed(
                    blockId: EntityId,
                    isProcessed: Boolean,
                ) = throw IllegalStateException("an invariant nobody expected to break")
            }
        val controller = ImportReviewController(review)

        val thrown =
            runBlocking {
                runCatching { controller.setProcessed(one.id, true) }.exceptionOrNull()
            }

        assertIs<IllegalStateException>(thrown, "a defect must travel out as it is, not become a save failure")
    }

    @Test
    fun `resumable imports are collected as storage reports them`() {
        val review = FakeReview()
        val entry = EarlierImport(BATCH_ID, "sample-import.xlsx", "Sayfa1", ImportBatchStatus.DRAFT)
        runBlocking {
            val controller = ImportReviewController(review)
            val job = launch { controller.observeDraftBatches() }
            yield()
            review.draftBatches.value = listOf(entry)
            yield()

            assertEquals(listOf(entry), controller.draftBatches)
            job.cancelAndJoin()
        }
    }
}
