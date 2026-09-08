package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.ui.input.key.Key
import dev.pnptracker.data.repository.EarlierImport
import dev.pnptracker.data.repository.ImportDrafts
import dev.pnptracker.data.repository.ImportRollback
import dev.pnptracker.data.repository.SavedImportSummary
import dev.pnptracker.data.repository.SettledImport
import dev.pnptracker.domain.importprep.ImportFileGateway
import dev.pnptracker.domain.importprep.ImportFileHandle
import dev.pnptracker.domain.importprep.ImportSourceReading
import dev.pnptracker.domain.importprep.PreparedImportDraft
import dev.pnptracker.domain.importprep.readCsvWorkbook
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
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreen
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val FIRST = IdGenerator.Random.newId()
private val SECOND = IdGenerator.Random.newId()

/**
 * The confirmed imports and the rollback surface, composed for real.
 *
 * Nothing here reads the source. What is asked of the screen is what a user
 * would see and press: the words really laid out, the button really disabled,
 * the keyboard really landing where it should. A test that asked the controller
 * whether it *would* refuse a second press would prove the controller and leave
 * the button untested, and the button is where a double click lands.
 */
class ImportRollbackScreenTest {
    private fun settled(
        batchId: EntityId = FIRST,
        fileName: String = "Kitap.xlsx",
        createdTaskCount: Int = 4,
        status: ImportBatchStatus = ImportBatchStatus.CONFIRMED,
    ) = SettledImport(batchId, fileName, "Sayfa1", createdTaskCount, status)

    private fun safePreview(
        taskCount: Int = 4,
        cellCount: Int = 2,
        gameCount: Int = 2,
    ) = ImportRollbackPreview(
        batchId = FIRST,
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
        taskCount = 0,
        cellCount = 0,
        gameCount = 0,
        blockedTasks = blockedTasks,
        blockedCells = blockedCells,
        blockingFailure = failure,
    )

    private class FakeRollback(
        imports: List<SettledImport>,
    ) : ImportRollback {
        val list = MutableStateFlow(imports)
        var preview: ImportRollbackPreview? = null
        var rollBackFailsWith: ImportRollbackException? = null
        var listFailsWith: ImportRollbackException? = null
        var result = ImportRollbackResult(FIRST, removedTaskCount = 4, restoredCellCount = 2, affectedGameCount = 2)

        var previewCalls = 0
            private set
        var rollBackCalls = 0
            private set

        var previewGate: CompletableDeferred<Unit>? = null
        var rollBackGate: CompletableDeferred<Unit>? = null

        override fun observeSettledImports(): Flow<List<SettledImport>> =
            listFailsWith?.let { refusal -> flow<List<SettledImport>> { throw refusal } } ?: list

        override suspend fun previewRollback(batchId: EntityId): ImportRollbackPreview {
            previewCalls += 1
            previewGate?.await()
            return checkNotNull(preview) { "the test did not say what the preview should be" }
        }

        override suspend fun rollBack(batchId: EntityId): ImportRollbackResult {
            rollBackCalls += 1
            rollBackGate?.await()
            rollBackFailsWith?.let { throw it }
            return result
        }
    }

    private fun onScreen(
        fake: FakeRollback,
        width: Int = 1100,
        height: Int = 860,
        body: (ComposeSceneHarness, ImportRollbackController) -> Unit,
    ) {
        val controller = ImportRollbackController(fake)
        ComposeSceneHarness(width = width, height = height) {
            PnpTrackerTheme(ThemeMode.LIGHT) {
                SettledImportsSection(controller = controller)
            }
        }.use { harness ->
            harness.render()
            harness.render()
            body(harness, controller)
        }
    }

    private fun ComposeSceneHarness.saying(fragment: String): Boolean =
        writtenText().any { fragment in it } || spokenNodes().flatMap { it.contentDescriptions() }.any { fragment in it }

    private fun ComposeSceneHarness.everyWord(): List<String> = writtenText() + spokenNodes().flatMap { it.contentDescriptions() }

    // --------------------------------------------------------------- the list

    @Test
    fun `the section names itself and says so when there is nothing in it`() {
        onScreen(FakeRollback(emptyList())) { harness, _ ->
            assertTrue(harness.saying("Onaylanmış içe aktarmalar"), harness.everyWord().toString())
            assertTrue(harness.saying("Henüz onaylanmış bir içe aktarma yok"), harness.everyWord().toString())
        }
    }

    @Test
    fun `a list storage would not read says so rather than looking empty`() {
        val fake = FakeRollback(emptyList())
        fake.listFailsWith = ImportRollbackException(ImportRollbackFailure.COULD_NOT_SAVE)
        onScreen(fake) { harness, _ ->
            assertTrue(harness.saying("okunamadı"), harness.everyWord().toString())
            assertFalse(harness.saying("Henüz onaylanmış bir içe aktarma yok"))
        }
    }

    @Test
    fun `a confirmed import is offered a way back and a taken back one is not`() {
        val fake =
            FakeRollback(
                listOf(
                    settled(FIRST, "Kitap.xlsx", createdTaskCount = 4),
                    settled(SECOND, "Eski.csv", createdTaskCount = 9, status = ImportBatchStatus.ROLLED_BACK),
                ),
            )
        onScreen(fake) { harness, _ ->
            assertTrue(harness.saying("Kitap.xlsx"), harness.everyWord().toString())
            assertTrue(harness.saying("Eski.csv"))
            assertNotNull(harness.boundsOf("Kitap.xlsx içe aktarmasını geri al"), "a confirmed import had no way back")
            assertEquals(
                null,
                harness.boundsOf("Eski.csv içe aktarmasını geri al"),
                "an import already taken back was offered again",
            )
        }
    }

    @Test
    fun `where an import stands is said in the user's own language`() {
        val fake =
            FakeRollback(
                listOf(
                    settled(FIRST, "Kitap.xlsx"),
                    settled(SECOND, "Eski.csv", status = ImportBatchStatus.ROLLED_BACK),
                ),
            )
        onScreen(fake) { harness, _ ->
            assertTrue(harness.saying("Onaylandı"), harness.everyWord().toString())
            assertTrue(harness.saying("Geri alındı"))
            assertTrue(harness.saying("Oluşturulan görev: 4"))
            assertNoDeveloperWords(harness)
        }
    }

    // ------------------------------------------------------ the safe confirmation

    @Test
    fun `the confirmation says how much would go and what will not be undone`() {
        val fake = FakeRollback(listOf(settled()))
        fake.preview = safePreview(taskCount = 6, cellCount = 3, gameCount = 2)
        onScreen(fake) { harness, _ ->
            assertTrue(harness.click("Kitap.xlsx içe aktarmasını geri al"), "the button did not answer a click")

            assertTrue(harness.saying("6 görev"), harness.everyWord().toString())
            assertTrue(harness.saying("3 hücre"))
            assertTrue(harness.saying("2 oyunun geçmişine"))
            // PLAN 11.4.4 has the confirmation say all three of these.
            assertTrue(harness.saying("bütün görevlerini kapsar"), "it never said the whole import goes")
            assertTrue(harness.saying("“tamamlandı” işaretleri geri alınmaz"), "the completion promise was not made")
            assertTrue(harness.saying("geri alınamaz"))
            assertNotNull(harness.boundsOf("Geri al"), "there was no way to go ahead")
            assertNotNull(harness.boundsOf("Vazgeç"), "there was no way out")
        }
    }

    @Test
    fun `going ahead takes it back once, and says so`() {
        val fake = FakeRollback(listOf(settled()))
        fake.preview = safePreview()
        onScreen(fake) { harness, _ ->
            harness.click("Kitap.xlsx içe aktarmasını geri al")

            assertTrue(harness.click("Geri al"))

            assertEquals(1, fake.rollBackCalls)
            assertTrue(harness.saying("İçe aktarma geri alındı"), harness.everyWord().toString())
            assertTrue(harness.saying("4 görev kaldırıldı"))
        }
    }

    @Test
    fun `the row follows the database into being taken back`() {
        val fake = FakeRollback(listOf(settled()))
        fake.preview = safePreview()
        onScreen(fake) { harness, _ ->
            harness.click("Kitap.xlsx içe aktarmasını geri al")
            harness.click("Geri al")
            // Which is what the observed table does next.
            fake.list.value = listOf(settled(status = ImportBatchStatus.ROLLED_BACK))
            harness.render()
            assertTrue(harness.click("Kapat"))

            assertTrue(harness.saying("Geri alındı"), harness.everyWord().toString())
            assertEquals(
                null,
                harness.boundsOf("Kitap.xlsx içe aktarmasını geri al"),
                "the button stayed on a row that had already been taken back",
            )
        }
    }

    // ------------------------------------------------------------ what stops it

    @Test
    fun `a blocked import names what is in the way and offers no way to do it anyway`() {
        val fake = FakeRollback(listOf(settled()))
        fake.preview =
            blockedPreview(
                ImportRollbackFailure.TASKS_WERE_EDITED,
                blockedTasks = listOf(BlockedTask(IdGenerator.Random.newId(), "Kırmızı ev", TaskObstacle.EDITED)),
                blockedCells =
                    listOf(
                        BlockedCell(
                            IdGenerator.Random.newId(),
                            IdGenerator.Random.newId(),
                            "Harmonies",
                            CellColumnType.THREE_D,
                            CellObstacle.DOCUMENT_CHANGED,
                        ),
                    ),
            )
        onScreen(fake) { harness, _ ->
            harness.click("Kitap.xlsx içe aktarmasını geri al")

            assertTrue(harness.saying("geri alınamıyor"), harness.everyWord().toString())
            assertTrue(harness.saying("Kırmızı ev"), "the task in the way was not named")
            assertTrue(harness.saying("sonradan düzenlendi"), "it did not say why the task was in the way")
            assertTrue(harness.saying("Harmonies"), "the cell in the way was not named by its game")
            assertTrue(harness.saying("3D Baskı"), "the cell in the way was not named by its column")
            // PLAN 11.4.4: there is no such thing as the safe part of it.
            assertEquals(null, harness.boundsOf("Geri al"), "a blocked import was offered a way to go ahead anyway")
            assertNotNull(harness.boundsOf("Kapat"), "there was no way out of the refusal")
            assertNoDeveloperWords(harness)
        }
    }

    @Test
    fun `an import too old to have kept a record says which kind of import it is`() {
        val fake = FakeRollback(listOf(settled()))
        fake.preview = blockedPreview(ImportRollbackFailure.NO_CELL_SNAPSHOT)
        onScreen(fake) { harness, _ ->
            harness.click("Kitap.xlsx içe aktarmasını geri al")

            assertTrue(harness.saying("eski bir içe aktarma"), harness.everyWord().toString())
            assertTrue(harness.saying("kaydedilmemiş"), "it did not say what is missing")
            assertEquals(null, harness.boundsOf("Geri al"))
        }
    }

    @Test
    fun `every refusal the engine has is a sentence and not an enum`() {
        ImportRollbackFailure.entries.forEach { failure ->
            val fake = FakeRollback(listOf(settled()))
            fake.preview = blockedPreview(failure)
            onScreen(fake) { harness, _ ->
                harness.click("Kitap.xlsx içe aktarmasını geri al")

                assertTrue(harness.saying("geri alınamıyor"), "$failure did not reach the screen")
                assertNoDeveloperWords(harness)
            }
        }
    }

    @Test
    fun `a conflict found only inside the transaction keeps the panel and its context`() {
        val fake = FakeRollback(listOf(settled()))
        fake.preview = safePreview(taskCount = 6)
        fake.rollBackFailsWith =
            ImportRollbackException(
                ImportRollbackFailure.CELLS_WERE_EDITED,
                blockedCells =
                    listOf(
                        BlockedCell(
                            IdGenerator.Random.newId(),
                            IdGenerator.Random.newId(),
                            "Harmonies",
                            CellColumnType.CARD,
                            CellObstacle.DOCUMENT_CHANGED,
                        ),
                    ),
            )
        onScreen(fake) { harness, _ ->
            harness.click("Kitap.xlsx içe aktarmasını geri al")
            harness.click("Geri al")

            // The panel is still open, still saying what the user was reading,
            // with the reason it did not happen underneath it.
            assertTrue(harness.saying("6 görev"), "the panel lost what the user was reading")
            assertTrue(harness.saying("metni sonradan değişti"), harness.everyWord().toString())
            assertTrue(harness.saying("Harmonies"))
        }
    }

    @Test
    fun `a long list of things in the way scrolls rather than pushing the way out off screen`() {
        val fake = FakeRollback(listOf(settled()))
        fake.preview =
            blockedPreview(
                ImportRollbackFailure.TASKS_WERE_EDITED,
                blockedTasks =
                    (1..42).map { BlockedTask(IdGenerator.Random.newId(), "Görev $it", TaskObstacle.EDITED) },
            )
        onScreen(fake, height = 700) { harness, _ ->
            harness.click("Kitap.xlsx içe aktarmasını geri al")

            val way = assertNotNull(harness.boundsOf("Kapat"), "forty-two blockers pushed the only way out away")
            assertTrue(way.bottom <= 700f, "the way out was drawn off the bottom of the window: $way")
            assertTrue(harness.saying("Görev 1"), "the first blocker was not shown")
        }
    }

    // ------------------------------------------------ keyboard, focus and repeats

    @Test
    fun `the way back is reachable by keyboard and one press starts one reading`() {
        val fake = FakeRollback(listOf(settled()))
        fake.preview = safePreview()
        onScreen(fake) { harness, _ ->
            assertTrue(harness.tabTo("Kitap.xlsx içe aktarmasını geri al"), "the button was not a Tab stop")

            harness.press(Key.Enter)
            harness.press(Key.Enter)

            // The second press lands on a disabled button, because a surface is
            // open over it: one reading, whatever the keyboard does.
            assertEquals(1, fake.previewCalls, "a repeated Enter started a second reading")
        }
    }

    @Test
    fun `Escape closes the confirmation and puts the keyboard back where it came from`() {
        val fake = FakeRollback(listOf(settled()))
        fake.preview = safePreview()
        onScreen(fake) { harness, controller ->
            harness.tabTo("Kitap.xlsx içe aktarmasını geri al")
            harness.press(Key.Enter)
            assertNotNull(harness.boundsOf("Vazgeç"), "the confirmation never opened")
            // A frame more, because the dialog asks for the keyboard once it is
            // really drawn; a real window is producing frames all the while.
            harness.render()

            // The dialog puts the keyboard on the way out, so Escape is being
            // sent to something inside the dialog rather than to the row behind it.
            assertEquals(listOf("Vazgeç"), harness.focusedNode()?.contentDescriptions(), "the dialog took no focus")

            harness.press(Key.Escape)
            harness.render()

            assertEquals(RollbackFlowState.Closed, controller.flow, "Escape did not close the confirmation")
            assertEquals(
                listOf("Kitap.xlsx içe aktarmasını geri al"),
                harness.focusedNode()?.contentDescriptions(),
                "the keyboard did not go back to the row it came from",
            )
        }
    }

    @Test
    fun `nothing can be started twice while it is being taken back`() {
        val fake = FakeRollback(listOf(settled()))
        fake.preview = safePreview()
        val gate = CompletableDeferred<Unit>()
        fake.rollBackGate = gate
        onScreen(fake) { harness, _ ->
            harness.click("Kitap.xlsx içe aktarmasını geri al")
            harness.click("Geri al")
            harness.render()

            assertTrue(harness.saying("geri alınıyor"), harness.everyWord().toString())
            // Pressed again while the transaction is still in flight.
            harness.click("Geri al")
            harness.press(Key.Enter)

            assertEquals(1, fake.rollBackCalls, "a repeated press started a second transaction")
            gate.complete(Unit)
        }
    }

    @Test
    fun `the preview being read is shown and cannot be asked for twice`() {
        val fake = FakeRollback(listOf(settled()))
        fake.preview = safePreview()
        val gate = CompletableDeferred<Unit>()
        fake.previewGate = gate
        onScreen(fake) { harness, _ ->
            harness.click("Kitap.xlsx içe aktarmasını geri al")
            harness.render()

            assertTrue(harness.saying("Geri alma inceleniyor"), harness.everyWord().toString())
            harness.click("Kitap.xlsx içe aktarmasını geri al")

            assertEquals(1, fake.previewCalls, "a second press was read while the first was still being read")
            gate.complete(Unit)
        }
    }

    // ------------------------------------------------------------- narrow window

    @Test
    fun `a narrow window keeps the confirmation's buttons on screen`() {
        val fake = FakeRollback(listOf(settled()))
        fake.preview = safePreview()
        onScreen(fake, width = 640, height = 620) { harness, _ ->
            harness.click("Kitap.xlsx içe aktarmasını geri al")

            val accept = assertNotNull(harness.boundsOf("Geri al"), "the way ahead was not drawn at all")
            val cancel = assertNotNull(harness.boundsOf("Vazgeç"), "the way out was not drawn at all")
            listOf(accept, cancel).forEach { bounds ->
                assertTrue(bounds.right <= 640f, "a button ran off the right edge: $bounds")
                assertTrue(bounds.bottom <= 620f, "a button ran off the bottom: $bounds")
                assertTrue(bounds.left >= 0f && bounds.top >= 0f, "a button was drawn off the top left: $bounds")
            }
        }
    }

    // -------------------------------------------- the status the warning used to leak

    @Test
    fun `the duplicate warning names the earlier import's state in Turkish`() {
        // The defect this is about: the stored value was written onto the screen,
        // so a Turkish sentence read "… — CONFIRMED".
        val earlier =
            listOf(
                EarlierImport(FIRST, "liste.csv", "Sayfa1", ImportBatchStatus.CONFIRMED),
                EarlierImport(SECOND, "liste.csv", "Sayfa1", ImportBatchStatus.ROLLED_BACK),
            )
        onDuplicateWarning(earlier) { harness ->
            assertTrue(harness.saying("Onaylandı"), harness.everyWord().toString())
            assertTrue(harness.saying("Geri alındı"))
            assertNoDeveloperWords(harness)
        }
    }

    private fun onDuplicateWarning(
        earlier: List<EarlierImport>,
        body: (ComposeSceneHarness) -> Unit,
    ) {
        val text = "game,source_type,raw_text\nHarmonies,3d,Kırmızı ev\n"
        val controller = ImportController(FixedGateway(CsvHandle("liste.csv", text)), RepeatingStore(earlier))
        runBlocking {
            controller.chooseFile()
            // The save runs into the repeat and stops on the warning, which is
            // the state this is about.
            controller.saveDraft()
        }
        ComposeSceneHarness(width = 1100, height = 800) {
            PnpTrackerTheme(ThemeMode.LIGHT) {
                ImportScreen(controller = controller, onOpenReview = {})
            }
        }.use { harness ->
            harness.render()
            harness.render()
            body(harness)
        }
    }

    /**
     * Nothing a developer would recognise and a user would not.
     *
     * The stored spellings of the three states, an identifier, a piece of SQL or
     * the word an exception carries: PLAN 17 keeps every one of them off the
     * screen, and each has been on it at some point.
     */
    private fun assertNoDeveloperWords(harness: ComposeSceneHarness) {
        val words = harness.everyWord()
        listOf("DRAFT", "CONFIRMED", "ROLLED_BACK", "SELECT ", "INSERT ", "UPDATE ", "Exception", "null").forEach { leak ->
            assertTrue(words.none { leak in it }, "\"$leak\" reached the screen: $words")
        }
        // A UUID, which is what every identifier in this application looks like.
        val identifier = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        assertTrue(words.none { identifier.containsMatchIn(it) }, "an identifier reached the screen: $words")
    }

    private class CsvHandle(
        override val fileName: String,
        private val text: String,
    ) : ImportFileHandle {
        override suspend fun fingerprint(): String = "%064x".format(text.hashCode().toLong() and 0xffffffffL)

        override suspend fun readWorkbook(): ImportSourceReading {
            val reading = readCsvWorkbook(fileName, text)
            return ImportSourceReading(reading.workbook, ImportSourceFormat.CSV, reading.delimiter)
        }
    }

    private class FixedGateway(
        private val handle: ImportFileHandle?,
    ) : ImportFileGateway {
        override suspend fun chooseFile(): ImportFileHandle? = handle
    }

    private class RepeatingStore(
        private val earlier: List<EarlierImport>,
    ) : ImportDrafts {
        override suspend fun earlierImportsOf(sha256: String): List<EarlierImport> = earlier

        override suspend fun save(draft: PreparedImportDraft): SavedImportSummary =
            SavedImportSummary(IdGenerator.Random.newId(), draft.fileName, draft.sheetName, draft.rawBlockCount)
    }
}
