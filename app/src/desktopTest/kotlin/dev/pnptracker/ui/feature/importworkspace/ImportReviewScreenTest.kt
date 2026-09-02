package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import dev.pnptracker.data.repository.DraftEdit
import dev.pnptracker.data.repository.EarlierImport
import dev.pnptracker.data.repository.GameChoice
import dev.pnptracker.data.repository.ImportConfirmation
import dev.pnptracker.data.repository.ImportReview
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.importconfirm.ImportConfirmationResult
import dev.pnptracker.domain.importconfirm.ImportConfirmationSummary
import dev.pnptracker.domain.importconfirm.TargetCellChoice
import dev.pnptracker.domain.importhint.ColorVocabulary
import dev.pnptracker.domain.importreview.ImportReviewWorkspace
import dev.pnptracker.domain.importreview.ReviewDraftTask
import dev.pnptracker.domain.importreview.ReviewRawBlock
import dev.pnptracker.domain.importreview.initialDraftByHand
import dev.pnptracker.domain.importreview.initialDraftFromSelection
import dev.pnptracker.domain.importreview.selectTaskNameIn
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The review screen composed for real, with real strokes and a real pointer.
 *
 * Nothing here reads the source. The defects this exists for — a selection the
 * controller never hears about, a key that never reaches a panel, a colour said
 * only by its swatch — all live in the parts a source search cannot see, so the
 * screen is laid out, the events go in the way a window sends them, and what is
 * asserted afterwards is the real semantics tree.
 */
class ImportReviewScreenTest {
    private val batchId = IdGenerator.Random.newId()
    private val gameId = IdGenerator.Random.newId()
    private val cellId = IdGenerator.Random.newId()

    private val red = ColorSummary(IdGenerator.Random.newId(), "Kırmızı", "#D02020", sortOrder = 1)
    private val blue = ColorSummary(IdGenerator.Random.newId(), "Mavi", "#2040D0", sortOrder = 2)

    private fun block(
        text: String = "12 KIRMIZI**",
        column: Int = 1,
        sourceColumnType: SourceColumnType = SourceColumnType.THREE_D,
        hint: HintDecision = HintDecision.NONE,
        targetGameId: EntityId? = null,
    ) = ReviewRawBlock(
        id = IdGenerator.Random.newId(),
        rawText = text,
        sheetName = "Sayfa1",
        rowIndex = 1,
        columnIndex = column,
        sourceColumnType = sourceColumnType,
        gameCompletionHint = hint,
        completionTargetGameId = targetGameId,
    )

    private fun workspace(
        blocks: List<ReviewRawBlock>,
        drafts: List<ReviewDraftTask> = emptyList(),
        status: ImportBatchStatus = ImportBatchStatus.DRAFT,
    ) = ImportReviewWorkspace(
        batchId = batchId,
        fileName = "Kitap.xlsx",
        sheetName = "Sayfa1",
        status = status,
        rawBlocks = blocks,
        draftTasks = drafts,
    )

    private fun draftOf(
        blockId: EntityId,
        name: String = "12 KIRMIZI",
        hint: HintDecision = HintDecision.NONE,
        colorIds: List<EntityId> = emptyList(),
        quantity: Int? = 12,
    ) = ReviewDraftTask(
        id = IdGenerator.Random.newId(),
        rawImportBlockId = blockId,
        name = name,
        completionHint = hint,
        requiredQuantity = quantity,
        colorIds = colorIds,
    )

    /**
     * Composes the screen over one workspace and hands the harness to the test.
     *
     * The whole screen, not a piece of it: the point is that events travel the
     * real chain from the window down, and a fragment lifted out of its screen
     * would not have that chain.
     */
    private fun onScreen(
        review: FakeReview,
        confirmation: FakeConfirmation = FakeConfirmation(),
        width: Int = 1280,
        height: Int = 900,
        body: (ComposeSceneHarness, ImportReviewController, ImportConfirmationController) -> Unit,
    ) {
        val controller = ImportReviewController(review)
        val confirmController = ImportConfirmationController(confirmation)
        ComposeSceneHarness(width = width, height = height) {
            PnpTrackerTheme(ThemeMode.LIGHT) {
                ImportReviewScreen(
                    controller = controller,
                    confirmation = confirmController,
                    batchId = batchId,
                    onBack = {},
                )
            }
        }.use { harness ->
            harness.render()
            harness.render()
            body(harness, controller, confirmController)
        }
    }

    private fun ComposeSceneHarness.spoken(): List<String> = spokenNodes().flatMap { it.contentDescriptions() }

    // ------------------------------------------------------- real selection

    @Test
    fun `a real pointer drag across the cell text reports real offsets`() {
        val one = block("12 KIRMIZI kutu")
        val review = FakeReview(workspace(listOf(one)))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            harness.render()

            val field = assertNotNull(harness.rawTextBounds(one), "the cell text was not drawn")
            // Down the field rather than at its very top, where the label sits.
            val line = field.top + field.height * 0.6f
            // From the very start of the line to well past its end: what is
            // selected is whatever the pointer really travelled over.
            harness.dragFrom(Offset(field.left + 14f, line), Offset(field.right - 4f, line))

            val pointed = assertNotNull(controller.selection, "the drag never reached the controller")
            assertEquals(one.id, pointed.blockId)
            assertEquals(0, pointed.startIndex)
            assertTrue(pointed.endIndex > 0)
            assertTrue(pointed.endIndex <= one.rawText.length)
        }
    }

    @Test
    fun `a real keyboard selection reports real offsets`() {
        val one = block("12 KIRMIZI kutu")
        val review = FakeReview(workspace(listOf(one)))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            harness.render()

            val field = assertNotNull(harness.rawTextBounds(one))
            val line = field.top + field.height * 0.6f
            // A press puts the caret at the start; the keyboard widens from there.
            harness.dragFrom(Offset(field.left + 14f, line), Offset(field.left + 14f, line))
            repeat(5) { harness.press(Key.DirectionRight, shift = true) }

            val pointed = assertNotNull(controller.selection, "the strokes never reached the controller")
            assertEquals(0, pointed.startIndex)
            assertEquals(5, pointed.endIndex)
        }
    }

    @Test
    fun `the cell text cannot be typed over`() {
        val one = block("12 KIRMIZI")
        val review = FakeReview(workspace(listOf(one)))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            harness.render()

            val field = assertNotNull(harness.rawTextBounds(one))
            val line = field.top + field.height * 0.6f
            harness.dragFrom(Offset(field.left + 14f, line), Offset(field.right - 4f, line))
            harness.press(Key.Backspace)
            harness.press(Key.A)

            assertEquals(
                "12 KIRMIZI",
                assertIs<ImportReviewState.Content>(controller.state)
                    .workspace.rawBlocks
                    .single()
                    .rawText,
            )
        }
    }

    // -------------------------------------------------- keys and one submit

    @Test
    fun `Ctrl+Enter saves the open form exactly once`() {
        val one = block()
        val draft = draftOf(one.id)
        val review = FakeReview(workspace(listOf(one), listOf(draft)))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            controller.openDraft(draft)
            controller.editName("Kırmızı kutu")
            harness.render()

            harness.press(Key.Enter, ctrl = true)
            harness.render()
            harness.press(Key.Enter, ctrl = true)
            harness.render()

            assertEquals(1, review.edits.size, "one form, one write")
            assertEquals("Kırmızı kutu", review.edits.single().name)
        }
    }

    @Test
    fun `Escape closes the colour list back onto the form, and only then the form`() {
        val one = block()
        val draft = draftOf(one.id)
        val review = FakeReview(workspace(listOf(one), listOf(draft)))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            controller.openDraft(draft)
            controller.editName("Yarım kalan ad")
            controller.openColorChoice()
            harness.render()

            harness.press(Key.Escape)
            harness.render()

            val stillOpen = assertIs<ImportReviewSurface.DraftEditor>(controller.surface)
            assertEquals("Yarım kalan ad", stillOpen.form.name, "the form lost what was typed in it")

            harness.press(Key.Escape)
            harness.render()

            assertEquals(ImportReviewSurface.None, controller.surface)
            assertEquals(0, review.edits.size, "closing a form wrote something")
        }
    }

    @Test
    fun `Escape with nothing open only clears the selection`() {
        val one = block()
        val review = FakeReview(workspace(listOf(one)))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            controller.pointAt(one.id, 0, 5)
            harness.render()

            // Something has to hold the keyboard for a stroke to travel at all.
            harness.tab()
            harness.press(Key.Escape)
            harness.render()

            assertNull(controller.selection)
            assertIs<ImportReviewState.Content>(controller.state)
        }
    }

    // ------------------------------------------------- focus after an action

    @Test
    fun `the keyboard lands on the new draft after one is made`() {
        val one = block("12 KIRMIZI kutu")
        val review = FakeReview(workspace(listOf(one)))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            controller.pointAt(one.id, 0, 10)
            runBlocking { controller.createFromSelection() }
            harness.render()
            harness.render()

            val made = assertIs<ImportReviewState.Content>(controller.state).workspace.draftTasks.single()
            assertNull(controller.focus, "the screen was asked to move the keyboard and did not")
            val focused = assertNotNull(harness.focusedNode(), "nothing has the keyboard")
            assertTrue(
                focused.contentDescriptions().any { made.name in it },
                "the keyboard did not land on the draft that was just made: ${focused.contentDescriptions()}",
            )
        }
    }

    @Test
    fun `the keyboard follows a colour that is moved`() {
        val one = block()
        val draft = draftOf(one.id, colorIds = listOf(red.id, blue.id))
        val review = FakeReview(workspace(listOf(one), listOf(draft)))
        review.colors.value = listOf(red, blue)
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            controller.openDraft(draft)
            harness.render()

            controller.moveColorUp(1)
            harness.render()
            harness.render()

            val focused = assertNotNull(harness.focusedNode())
            assertTrue(
                focused.contentDescriptions().any { "Mavi" in it },
                "the colour that moved lost the keyboard: ${focused.contentDescriptions()}",
            )
        }
    }

    @Test
    fun `the keyboard goes to the first clashing colour when one is named twice`() {
        val one = block()
        val draft = draftOf(one.id, colorIds = listOf(red.id, blue.id))
        val review = FakeReview(workspace(listOf(one), listOf(draft)))
        review.colors.value = listOf(red, blue)
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            controller.openDraft(draft)
            controller.openColorChoice()
            harness.render()

            // The same colour cannot be chosen twice through the list, so the
            // clash is built directly and the panel has to point at both ends.
            controller.chooseColor(red.id)
            harness.render()

            val form = assertNotNull(controller.surface.openForm)
            assertEquals(listOf(blue.id), form.colorIds, "choosing a chosen colour takes it off")
            assertNull(form.duplicateColorSlots)
        }
    }

    // -------------------------------------------------------- what is spoken

    @Test
    fun `a draft is announced once, with its colours named and not only drawn`() {
        val one = block()
        val draft = draftOf(one.id, name = "Kırmızı kutu", colorIds = listOf(red.id, blue.id))
        val review = FakeReview(workspace(listOf(one), listOf(draft)))
        review.colors.value = listOf(red, blue)
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            harness.render()

            val about = harness.spoken().filter { "Kırmızı kutu" in it && "Taslak" in it }
            assertEquals(1, about.size, "the draft was announced ${about.size} times")
            val sentence = about.single()
            assertTrue("Kırmızı" in sentence && "Mavi" in sentence, "the colours were not named: $sentence")
            assertTrue("12" in sentence, "the amount was not said: $sentence")
        }
    }

    @Test
    fun `the cell says where it came from and what it holds, once`() {
        val one = block("12 KIRMIZI**")
        val review = FakeReview(workspace(listOf(one)))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            harness.render()

            val about = harness.spoken().filter { "12 KIRMIZI**" in it }
            assertEquals(1, about.size, "the cell text was announced ${about.size} times")
            assertTrue("2. satır" in about.single(), "the source row was not said: ${about.single()}")
        }
    }

    @Test
    fun `an unanswered marker offers both answers in words`() {
        val one = block()
        val draft = draftOf(one.id, hint = HintDecision.PENDING)
        val review = FakeReview(workspace(listOf(one), listOf(draft)))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            harness.render()

            val spoken = harness.spoken()
            assertTrue(spoken.any { "Tamamlanmış olarak işaretle" in it }, "no way to accept the marker")
            assertTrue(spoken.any { "Açık görev olarak oluştur" in it }, "no way to reject the marker")
        }
    }

    @Test
    fun `an unanswered green cell offers both answers, and naming a game is one of them`() {
        val one =
            block(
                text = "Wingspan",
                column = 0,
                sourceColumnType = SourceColumnType.GAME,
                hint = HintDecision.PENDING,
            )
        val review = FakeReview(workspace(listOf(one)))
        review.games.value = listOf(GameChoice(gameId, "Wingspan", isCompleted = false))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            harness.render()

            val spoken = harness.spoken()
            assertTrue(spoken.any { "Oyunu tamamlanmış say" in it }, "no way to accept the green cell")
            assertTrue(spoken.any { "Tamamlanmış sayma" in it }, "no way to reject the green cell")

            controller.openGameTarget(one.id)
            harness.render()
            assertTrue(harness.spoken().any { it == "Wingspan" }, "the game could not be picked by name")
        }
    }

    @Test
    fun `two games sharing a name are told apart in the picker`() {
        val other = IdGenerator.Random.newId()
        val one =
            block(
                text = "Wingspan",
                column = 0,
                sourceColumnType = SourceColumnType.GAME,
                hint = HintDecision.PENDING,
            )
        val review = FakeReview(workspace(listOf(one)))
        review.games.value =
            listOf(
                GameChoice(gameId, "Wingspan", isCompleted = false, sharedNameOrdinal = 1),
                GameChoice(other, "Wingspan", isCompleted = false, sharedNameOrdinal = 2),
            )
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            controller.openGameTarget(one.id)
            harness.render()

            val named = harness.spoken().filter { it.startsWith("Wingspan") }
            assertEquals(
                2,
                named.toSet().size,
                "two games sharing a name read the same in the picker: $named",
            )
        }
    }

    // ------------------------------------------------------------ the layout

    @Test
    fun `a narrow window draws one column with a control to move between the panes`() {
        val one = block()
        val review = FakeReview(workspace(listOf(one), listOf(draftOf(one.id))))
        onScreen(review, width = 720, height = 880) { harness, controller, _ ->
            controller.select(one.id)
            harness.render()

            val spoken = harness.spoken()
            assertTrue(spoken.any { it == "Hücreler" }, "the cells pane has no way to be chosen")
            assertTrue(spoken.any { it == "Taslaklar" }, "the drafts pane has no way to be chosen")
        }
    }

    @Test
    fun `a wide window draws both panes at once and no pane control`() {
        val one = block()
        val review = FakeReview(workspace(listOf(one), listOf(draftOf(one.id))))
        onScreen(review, width = 1280, height = 900) { harness, controller, _ ->
            controller.select(one.id)
            harness.render()

            val spoken = harness.spoken()
            assertTrue(spoken.none { it == "Hücreler" }, "a wide window drew the narrow pane control")
        }
    }

    @Test
    fun `everything on a narrow screen can still be reached with the keyboard`() {
        val one = block()
        val draft = draftOf(one.id, colorIds = listOf(red.id))
        val review = FakeReview(workspace(listOf(one), listOf(draft)))
        review.colors.value = listOf(red, blue)
        onScreen(review, width = 720, height = 880) { harness, controller, _ ->
            controller.select(one.id)
            controller.openDraft(draft)
            harness.render()

            var stops = 0
            repeat(40) {
                harness.tab()
                if (harness.focusedNode() != null) stops++
            }
            assertTrue(stops > 0, "nothing on the narrow layout takes the keyboard")
        }
    }

    @Test
    fun `Shift+Tab walks back the way Tab walked forward`() {
        val one = block()
        val review = FakeReview(workspace(listOf(one), listOf(draftOf(one.id))))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            harness.render()

            harness.tab()
            harness.tab()
            val second = harness.focusedNode()?.boundsInRoot
            harness.tab()
            val third = harness.focusedNode()?.boundsInRoot
            assertTrue(second != third, "Tab did not move the keyboard")

            harness.shiftTab()
            assertEquals(second, harness.focusedNode()?.boundsInRoot, "Shift+Tab did not come back")
        }
    }

    // -------------------------------------------------- the workspace stream

    @Test
    fun `a new workspace does not overwrite what is being typed`() {
        val one = block()
        val draft = draftOf(one.id)
        val review = FakeReview(workspace(listOf(one), listOf(draft)))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            controller.openDraft(draft)
            controller.editName("yarım kalan")
            controller.editNotes("yarım not")
            harness.render()

            // Something else changes in the same import: another cell is marked
            // read, and the whole workspace is re-emitted.
            review.workspace.value =
                workspace(listOf(one.copy(isProcessed = true)), listOf(draft.copy(name = "başka bir ad")))
            harness.render()

            val form = assertNotNull(controller.surface.openForm)
            assertEquals("yarım kalan", form.name)
            assertEquals("yarım not", form.notes)
        }
    }

    @Test
    fun `a form whose draft has gone closes rather than editing nothing`() {
        val one = block()
        val draft = draftOf(one.id)
        val review = FakeReview(workspace(listOf(one), listOf(draft)))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            controller.openDraft(draft)
            harness.render()

            review.workspace.value = workspace(listOf(one))
            harness.render()

            assertEquals(ImportReviewSurface.None, controller.surface)
        }
    }

    @Test
    fun `an import confirmed elsewhere closes the form and offers nothing that writes`() {
        val one = block()
        val draft = draftOf(one.id)
        val review = FakeReview(workspace(listOf(one), listOf(draft)))
        onScreen(review) { harness, controller, _ ->
            controller.select(one.id)
            controller.openDraft(draft)
            harness.render()

            review.workspace.value =
                workspace(listOf(one), listOf(draft), status = ImportBatchStatus.CONFIRMED)
            harness.render()

            assertEquals(ImportReviewSurface.None, controller.surface)
            assertTrue(harness.spoken().none { it == "Düzenle" }, "a confirmed import still offered editing")
        }
    }

    /**
     * Where the cell's own text is drawn, found by what a reader is told about
     * it rather than by a coordinate anybody guessed.
     */
    private fun ComposeSceneHarness.rawTextBounds(block: ReviewRawBlock) =
        spokenNodes()
            .firstOrNull { node ->
                node.contentDescriptions().any { it.endsWith(": ${block.rawText}") }
            }?.boundsInRoot

    // ------------------------------------------------------------ the doubles

    private inner class FakeReview(
        initial: ImportReviewWorkspace,
    ) : ImportReview {
        val workspace = MutableStateFlow<ImportReviewWorkspace?>(initial)
        val games = MutableStateFlow<List<GameChoice>>(emptyList())
        val colors = MutableStateFlow<List<ColorSummary>>(emptyList())
        val edits = mutableListOf<DraftEdit>()

        override fun observeDraftBatches(): Flow<List<EarlierImport>> = MutableStateFlow(emptyList())

        override fun observeWorkspace(batchId: EntityId): Flow<ImportReviewWorkspace?> = workspace

        override fun observeActiveGames(): Flow<List<GameChoice>> = games

        override fun observeColorVocabulary(): Flow<ColorVocabulary> = MutableStateFlow(ColorVocabulary.of(emptyList()))

        override fun observeColors(): Flow<List<ColorSummary>> = colors

        override suspend fun setProcessed(
            blockId: EntityId,
            isProcessed: Boolean,
        ) = Unit

        override suspend fun createDraftFromSelection(
            blockId: EntityId,
            startIndex: Int,
            endIndex: Int,
        ): EntityId {
            val current = requireNotNull(workspace.value)
            val block = current.rawBlocks.first { it.id == blockId }
            val initial = initialDraftFromSelection(block.columnIndex, selectTaskNameIn(block.rawText, startIndex, endIndex))
            val draft =
                ReviewDraftTask(
                    id = IdGenerator.Random.newId(),
                    rawImportBlockId = blockId,
                    name = initial.name,
                    completionHint = initial.completionHint,
                    requiredQuantity = initial.requiredQuantity,
                    selectionStartIndex = initial.selectionStartIndex,
                    selectionEndIndex = initial.selectionEndIndex,
                    needsInfo = initial.needsInfo,
                )
            workspace.value = current.copy(draftTasks = current.draftTasks + draft)
            return draft.id
        }

        override suspend fun createDraftByHand(
            blockId: EntityId,
            name: String,
        ): EntityId {
            val current = requireNotNull(workspace.value)
            val block = current.rawBlocks.first { it.id == blockId }
            val initial = initialDraftByHand(block.columnIndex, name)
            val draft =
                ReviewDraftTask(
                    id = IdGenerator.Random.newId(),
                    rawImportBlockId = blockId,
                    name = initial.name,
                )
            workspace.value = current.copy(draftTasks = current.draftTasks + draft)
            return draft.id
        }

        override suspend fun saveDraft(edit: DraftEdit): Boolean {
            edits += edit
            return true
        }

        override suspend fun setCompletionDecision(
            draftTaskId: EntityId,
            decision: HintDecision,
        ): Boolean = true

        override suspend fun setDraftColors(
            draftTaskId: EntityId,
            colorIds: List<EntityId>,
        ): Boolean = error("the panel saves colours with the rest of the form")

        override suspend fun setGameCompletionDecision(
            blockId: EntityId,
            decision: HintDecision,
            targetGameId: EntityId?,
        ): Boolean = true
    }

    private inner class FakeConfirmation : ImportConfirmation {
        override fun observeTargetCells(): Flow<List<TargetCellChoice>> =
            MutableStateFlow(listOf(TargetCellChoice(cellId, gameId, "Wingspan", CellColumnType.THREE_D)))

        override suspend fun summarize(batchId: EntityId): ImportConfirmationSummary =
            ImportConfirmationSummary(
                batchId = batchId,
                status = ImportBatchStatus.DRAFT,
                draftTaskCount = 1,
                readyTaskCount = 0,
                unprocessedBlockCount = 0,
                problems = emptyList(),
                hasAnyCell = true,
            )

        override suspend fun aimDraft(
            draftId: EntityId,
            targetCellId: EntityId?,
            poolType: PoolType?,
            trackingMode: TrackingMode?,
        ) = Unit

        override suspend fun confirm(
            batchId: EntityId,
            acknowledgeUnprocessedBlocks: Boolean,
        ): ImportConfirmationResult = ImportConfirmationResult(batchId, 0, 0)
    }
}
