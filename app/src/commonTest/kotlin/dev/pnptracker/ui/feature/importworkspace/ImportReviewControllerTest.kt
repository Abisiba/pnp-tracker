package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.data.repository.DraftEdit
import dev.pnptracker.data.repository.EarlierImport
import dev.pnptracker.data.repository.GameChoice
import dev.pnptracker.data.repository.ImportReview
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.importhint.ColorVocabulary
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.importreview.ImportReviewWorkspace
import dev.pnptracker.domain.importreview.ReviewDraftTask
import dev.pnptracker.domain.importreview.ReviewRawBlock
import dev.pnptracker.domain.importreview.initialDraftByHand
import dev.pnptracker.domain.importreview.initialDraftFromSelection
import dev.pnptracker.domain.importreview.selectTaskNameIn
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
import kotlin.test.assertNotNull
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
    val games = MutableStateFlow<List<GameChoice>>(emptyList())
    val colors = MutableStateFlow<List<ColorSummary>>(emptyList())
    val vocabulary = MutableStateFlow(ColorVocabulary.of(emptyList()))
    var failWith: ImportReviewFailure? = null
    val processedCalls = mutableListOf<Pair<EntityId, Boolean>>()
    val selectionCalls = mutableListOf<Triple<EntityId, Int, Int>>()
    val manualCalls = mutableListOf<Pair<EntityId, String>>()
    val edits = mutableListOf<DraftEdit>()
    val completionCalls = mutableListOf<Pair<EntityId, HintDecision>>()
    val gameHintCalls = mutableListOf<Triple<EntityId, HintDecision, EntityId?>>()

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

    override fun observeActiveGames(): Flow<List<GameChoice>> = games

    override fun observeColorVocabulary(): Flow<ColorVocabulary> = vocabulary

    override fun observeColors(): Flow<List<ColorSummary>> = colors

    override suspend fun createDraftFromSelection(
        blockId: EntityId,
        startIndex: Int,
        endIndex: Int,
    ): EntityId {
        failWith?.let { throw ImportReviewException(it) }
        val current = workspace.value ?: throw ImportReviewException(ImportReviewFailure.RAW_BLOCK_NOT_FOUND)
        val block =
            current.rawBlocks.firstOrNull { it.id == blockId }
                ?: throw ImportReviewException(ImportReviewFailure.RAW_BLOCK_NOT_FOUND)
        // The real contract, run against the stored text, so a controller test
        // cannot pass with offsets the transaction would refuse.
        val selection = selectTaskNameIn(block.rawText, startIndex, endIndex)
        val initial = initialDraftFromSelection(block.columnIndex, selection)
        selectionCalls += Triple(blockId, selection.startIndex, selection.endIndex)
        val draftId = IdGenerator.Random.newId()
        workspace.value =
            current.copy(
                draftTasks =
                    current.draftTasks +
                        ReviewDraftTask(
                            id = draftId,
                            rawImportBlockId = blockId,
                            name = initial.name,
                            suggestedPoolType = initial.suggestedPoolType,
                            completionHint = initial.completionHint,
                            requiredQuantity = initial.requiredQuantity,
                            selectionStartIndex = initial.selectionStartIndex,
                            selectionEndIndex = initial.selectionEndIndex,
                            isMissing = initial.isMissing,
                            isBorrowed = initial.isBorrowed,
                            needsInfo = initial.needsInfo,
                            needsClassification = initial.needsClassification,
                        ),
            )
        return draftId
    }

    override suspend fun createDraftByHand(
        blockId: EntityId,
        name: String,
    ): EntityId {
        failWith?.let { throw ImportReviewException(it) }
        val current = workspace.value ?: throw ImportReviewException(ImportReviewFailure.RAW_BLOCK_NOT_FOUND)
        val block =
            current.rawBlocks.firstOrNull { it.id == blockId }
                ?: throw ImportReviewException(ImportReviewFailure.RAW_BLOCK_NOT_FOUND)
        val initial = initialDraftByHand(block.columnIndex, name)
        manualCalls += blockId to initial.name
        val draftId = IdGenerator.Random.newId()
        workspace.value =
            current.copy(
                draftTasks =
                    current.draftTasks +
                        ReviewDraftTask(
                            id = draftId,
                            rawImportBlockId = blockId,
                            name = initial.name,
                            suggestedPoolType = initial.suggestedPoolType,
                            isMissing = initial.isMissing,
                            isBorrowed = initial.isBorrowed,
                            needsClassification = initial.needsClassification,
                        ),
            )
        return draftId
    }

    override suspend fun saveDraft(edit: DraftEdit): Boolean {
        failWith?.let { throw ImportReviewException(it) }
        edits += edit
        val current = workspace.value ?: return false
        workspace.value =
            current.copy(
                draftTasks =
                    current.draftTasks.map { draft ->
                        if (draft.id != edit.draftTaskId) {
                            draft
                        } else {
                            draft.copy(
                                name = edit.name,
                                targetCellId = edit.targetCellId,
                                selectedPoolType = edit.poolType,
                                selectedTrackingMode = edit.trackingMode,
                                requiredQuantity = edit.requiredQuantity,
                                notes = edit.notes,
                                isMissing = edit.isMissing,
                                isBorrowed = edit.isBorrowed,
                                needsInfo = edit.needsInfo,
                                needsClassification = edit.needsClassification,
                                completionHint = edit.completionHint,
                                colorIds = edit.colorIds,
                            )
                        }
                    },
            )
        return true
    }

    override suspend fun setCompletionDecision(
        draftTaskId: EntityId,
        decision: HintDecision,
    ): Boolean {
        failWith?.let { throw ImportReviewException(it) }
        completionCalls += draftTaskId to decision
        val current = workspace.value ?: return false
        workspace.value =
            current.copy(
                draftTasks =
                    current.draftTasks.map {
                        if (it.id == draftTaskId) it.copy(completionHint = decision) else it
                    },
            )
        return true
    }

    override suspend fun setDraftColors(
        draftTaskId: EntityId,
        colorIds: List<EntityId>,
    ): Boolean = throw UnsupportedOperationException("the panel saves colours with the rest of the form")

    override suspend fun setGameCompletionDecision(
        blockId: EntityId,
        decision: HintDecision,
        targetGameId: EntityId?,
    ): Boolean {
        failWith?.let { throw ImportReviewException(it) }
        gameHintCalls += Triple(blockId, decision, targetGameId)
        val current = workspace.value ?: return false
        workspace.value =
            current.copy(
                rawBlocks =
                    current.rawBlocks.map {
                        if (it.id == blockId) {
                            it.copy(gameCompletionHint = decision, completionTargetGameId = targetGameId)
                        } else {
                            it
                        }
                    },
            )
        return true
    }
}

class ImportReviewWorkspaceEditabilityTest {
    private fun contentOf(status: ImportBatchStatus): ImportReviewState.Content {
        val cell = block("15 KIRMIZI**", row = 1, column = 1)
        return ImportReviewState.Content(
            workspace =
                ImportReviewWorkspace(
                    batchId = BATCH_ID,
                    fileName = "sample-import.xlsx",
                    sheetName = "Sayfa1",
                    status = status,
                    rawBlocks = listOf(cell),
                    draftTasks = emptyList(),
                ),
            selectedBlockId = cell.id,
        )
    }

    @Test
    fun `a draft import may still be written to`() {
        assertTrue(contentOf(ImportBatchStatus.DRAFT).canEdit)
    }

    @Test
    fun `a confirmed import may not be written to`() {
        assertTrue(
            !contentOf(ImportBatchStatus.CONFIRMED).canEdit,
            "a confirmed import is read only, so no action that writes may be offered",
        )
    }

    @Test
    fun `a rolled back import may not be written to either`() {
        assertTrue(!contentOf(ImportBatchStatus.ROLLED_BACK).canEdit)
    }

    @Test
    fun `only a draft is editable, whatever else the batch is`() {
        val editable = ImportBatchStatus.entries.filter { contentOf(it).canEdit }

        assertEquals(listOf(ImportBatchStatus.DRAFT), editable)
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

    // ------------------------------------ cutting a task out of the cell text

    @Test
    fun `pointing at words in a cell records exactly the offsets the field reports`() {
        val one = block("12 KIRMIZI**", 1, 1)
        withObserving(FakeReview(workspaceOf(listOf(one)))) { controller ->
            controller.select(one.id)
            controller.pointAt(one.id, 3, 10)

            val pointed = assertNotNull(controller.selection)
            assertEquals(one.id, pointed.blockId)
            assertEquals(3, pointed.startIndex)
            assertEquals(10, pointed.endIndex)
        }
    }

    @Test
    fun `an empty or backwards selection is no selection at all`() {
        val one = block("12 KIRMIZI**", 1, 1)
        withObserving(FakeReview(workspaceOf(listOf(one)))) { controller ->
            controller.pointAt(one.id, 4, 4)
            assertNull(controller.selection)
            controller.pointAt(one.id, 6, 2)
            assertNull(controller.selection)
        }
    }

    @Test
    fun `moving to another cell drops the words pointed at in the last one`() {
        val one = block("bir", 1, 1)
        val two = block("iki", 2, 1)
        withObserving(FakeReview(workspaceOf(listOf(one, two)))) { controller ->
            controller.select(one.id)
            controller.pointAt(one.id, 0, 3)
            assertNotNull(controller.selection)

            controller.select(two.id)

            assertNull(controller.selection, "offsets only mean anything against the text they came from")
        }
    }

    @Test
    fun `a draft cut from the text takes the trimmed offsets and the cleared name`() {
        val one = block("  12 KIRMIZI**\n8 MAVİ  ", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        withObserving(review) { controller ->
            controller.select(one.id)
            // The user's drag takes the spaces on either side with it.
            controller.pointAt(one.id, 2, 15)
            controller.createFromSelection()
            yield()

            assertEquals(listOf(Triple(one.id, 2, 14)), review.selectionCalls)
            val draft = assertIs<ImportReviewState.Content>(controller.state).workspace.draftTasks.single()
            assertEquals("12 KIRMIZI", draft.name, "PLAN 11.5 clears the marker from the shown name")
            assertEquals(12, draft.requiredQuantity)
            assertEquals(HintDecision.PENDING, draft.completionHint, "a `**` is a question, never an answer")
            assertEquals(2, draft.selectionStartIndex)
            assertEquals(14, draft.selectionEndIndex)
        }
    }

    @Test
    fun `the cell text is not changed by cutting a task out of it`() {
        val text = "  12 KIRMIZI**\n8 MAVİ  "
        val one = block(text, 1, 1)
        withObserving(FakeReview(workspaceOf(listOf(one)))) { controller ->
            controller.select(one.id)
            controller.pointAt(one.id, 2, 15)
            controller.createFromSelection()
            yield()

            assertEquals(
                text,
                assertIs<ImportReviewState.Content>(controller.state)
                    .workspace.rawBlocks
                    .single()
                    .rawText,
            )
        }
    }

    @Test
    fun `a selection cutting a character in half is refused and keeps the selection`() {
        val one = block("aile 👨‍👩‍👧‍👦 burada", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        withObserving(review) { controller ->
            controller.select(one.id)
            // Half way into the family: eleven code units, one character.
            controller.pointAt(one.id, 5, 8)
            controller.createFromSelection()
            yield()

            assertEquals(emptyList(), review.selectionCalls)
            assertEquals(
                ImportReviewFailure.SELECTION_SPLITS_A_CHARACTER,
                assertIs<ImportReviewState.Content>(controller.state).failure,
            )
            assertNotNull(controller.selection, "the selection stays so it can be widened")
        }
    }

    @Test
    fun `a selection running across a line ending is refused`() {
        val one = block("gri token\n26 ağaç", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        withObserving(review) { controller ->
            controller.select(one.id)
            controller.pointAt(one.id, 4, 12)
            controller.createFromSelection()
            yield()

            assertEquals(emptyList(), review.selectionCalls)
            assertEquals(
                ImportReviewFailure.SELECTION_CONTAINS_LINE_BREAK,
                assertIs<ImportReviewState.Content>(controller.state).failure,
            )
        }
    }

    @Test
    fun `overlapping selections make independent drafts`() {
        val one = block("kırmızı token", 1, 1)
        withObserving(FakeReview(workspaceOf(listOf(one)))) { controller ->
            controller.select(one.id)
            controller.pointAt(one.id, 0, 13)
            controller.createFromSelection()
            yield()
            controller.pointAt(one.id, 8, 13)
            controller.createFromSelection()
            yield()

            val drafts = assertIs<ImportReviewState.Content>(controller.state).workspace.draftTasks
            assertEquals(listOf("kırmızı token", "token"), drafts.map { it.name })
            assertEquals(2, drafts.map { it.id }.toSet().size, "each draft is its own record")
        }
    }

    // --------------------------------------------- a task typed rather than cut

    @Test
    fun `a task typed by hand carries no selection at all`() {
        val one = block("bir", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        withObserving(review) { controller ->
            controller.beginManualDraft(one.id)
            controller.editManualName("Kırmızı token")
            controller.createByHand()
            yield()

            assertEquals(listOf(one.id to "Kırmızı token"), review.manualCalls)
            val draft = assertIs<ImportReviewState.Content>(controller.state).workspace.draftTasks.single()
            assertNull(draft.selectionStartIndex)
            assertNull(draft.selectionEndIndex)
            assertTrue(!draft.cameFromSelection)
            assertEquals(ImportReviewSurface.None, controller.surface)
        }
    }

    @Test
    fun `a blank typed name cannot be saved and writes nothing`() {
        val one = block("bir", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        withObserving(review) { controller ->
            controller.beginManualDraft(one.id)
            controller.editManualName("   ")

            assertTrue(!assertIs<ImportReviewSurface.ManualDraft>(controller.surface).canSave)
            controller.createByHand()

            assertEquals(emptyList(), review.manualCalls)
            assertIs<ImportReviewSurface.ManualDraft>(controller.surface)
        }
    }

    @Test
    fun `changing one's mind about a typed task writes nothing at all`() {
        val one = block("bir", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        withObserving(review) { controller ->
            controller.beginManualDraft(one.id)
            controller.editManualName("Kırmızı token")
            controller.cancelManualDraft()

            assertEquals(ImportReviewSurface.None, controller.surface)
            assertEquals(emptyList(), review.manualCalls)
            assertEquals(emptyList(), review.processedCalls)
            assertEquals(0, assertIs<ImportReviewState.Content>(controller.state).workspace.draftTaskCount)
        }
    }

    @Test
    fun `making a draft leaves the cell unmarked and the import a draft`() {
        val one = block("bir", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        withObserving(review) { controller ->
            controller.beginManualDraft(one.id)
            controller.editManualName("Kırmızı token")
            controller.createByHand()
            yield()

            val state = assertIs<ImportReviewState.Content>(controller.state)
            assertTrue(
                !state.workspace.rawBlocks
                    .single()
                    .isProcessed,
            )
            assertTrue(state.workspace.isStillADraft)
            assertEquals(emptyList(), review.processedCalls)
        }
    }

    @Test
    fun `several drafts can be made from one cell`() {
        val one = block("bir", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        withObserving(review) { controller ->
            controller.beginManualDraft(one.id)
            controller.editManualName("Kırmızı token")
            controller.createByHand()
            yield()
            controller.beginManualDraft(one.id)
            controller.editManualName("Mavi token")
            controller.createByHand()
            yield()

            assertEquals(listOf(one.id to "Kırmızı token", one.id to "Mavi token"), review.manualCalls)
            controller.select(one.id)
            assertEquals(2, assertIs<ImportReviewState.Content>(controller.state).visibleDrafts.size)
        }
    }

    @Test
    fun `a typed draft that could not be saved keeps the form open with what was typed`() {
        val one = block("bir", 1, 1)
        val review = FakeReview(workspaceOf(listOf(one)))
        review.failWith = ImportReviewFailure.COULD_NOT_SAVE
        withObserving(review) { controller ->
            controller.beginManualDraft(one.id)
            controller.editManualName("Kırmızı token")
            controller.createByHand()

            assertEquals(
                "Kırmızı token",
                assertIs<ImportReviewSurface.ManualDraft>(controller.surface).name,
            )
            assertEquals(
                ImportReviewFailure.COULD_NOT_SAVE,
                assertIs<ImportReviewState.Content>(controller.state).failure,
            )
            assertEquals(0, assertIs<ImportReviewState.Content>(controller.state).workspace.draftTaskCount)
        }
    }

    @Test
    fun `the two ways of making a draft cannot be open at once`() {
        val one = block("kırmızı token", 1, 1)
        withObserving(FakeReview(workspaceOf(listOf(one)))) { controller ->
            controller.beginManualDraft(one.id)
            controller.editManualName("elle yazılan")

            // A second form would be a second name nobody could see.
            controller.beginManualDraft(one.id)

            assertEquals("elle yazılan", assertIs<ImportReviewSurface.ManualDraft>(controller.surface).name)
        }
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

    @Test
    fun `a restore lets go of the workspace, because the import it was about has gone`() {
        val one = block("bir", 1, 1)
        val draft = ReviewDraftTask(IdGenerator.Random.newId(), one.id, "Taslak")
        withObserving(FakeReview(workspaceOf(listOf(one), listOf(draft)))) { controller ->
            controller.select(one.id)
            controller.openDraft(draft)
            assertIs<ImportReviewSurface.DraftEditor>(controller.surface)

            controller.abandonOpenWork()

            assertEquals(ImportReviewSurface.None, controller.surface, "a form was left open over a batch that had been replaced")
            assertNull(controller.selection)
        }
    }
}
