package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FIRST = IdGenerator.Random.newId()
private val SECOND = IdGenerator.Random.newId()
private val THIRD = IdGenerator.Random.newId()

/**
 * The unfinished imports and the removal surface, composed for real.
 *
 * What is asked of the screen is what a user would see and press: the words
 * really laid out, the buttons really disabled, the keyboard really landing
 * where it should — and a draft reaching the review screen only once its fresh
 * check has said it may.
 */
class UnfinishedImportsSectionTest {
    private fun rows() =
        listOf(
            anUnfinishedImport(FIRST, "bir.csv"),
            anUnfinishedImport(THIRD, "üç.csv"),
            anUnfinishedImport(SECOND, "bozuk.xlsx", isContradicting = true),
        )

    private fun onScreen(
        fake: FakeUnfinishedImports = FakeUnfinishedImports(rows()),
        width: Int = 1100,
        height: Int = 860,
        density: Density = Density(1f),
        body: (ComposeSceneHarness, UnfinishedImportsController, MutableList<EntityId>) -> Unit,
    ) {
        val controller = UnfinishedImportsController(fake)
        val opened = mutableListOf<EntityId>()
        ComposeSceneHarness(width = width, height = height, density = density) {
            PnpTrackerTheme(ThemeMode.LIGHT) {
                UnfinishedImportsSection(controller = controller, onOpen = { opened += it })
            }
        }.use { harness ->
            harness.render()
            harness.render()
            body(harness, controller, opened)
        }
    }

    private fun ComposeSceneHarness.everyWord(): List<String> = writtenText() + spokenNodes().flatMap { it.contentDescriptions() }

    private fun ComposeSceneHarness.saying(fragment: String): Boolean = everyWord().any { fragment in it }

    private fun assertNoDeveloperWords(harness: ComposeSceneHarness) {
        val words = harness.everyWord()
        listOf(
            "DRAFT",
            "CONFIRMED",
            "HELD_BY_RECORDS",
            "ALREADY_REMOVED",
            "COULD_NOT_SAVE",
            "NOT_A_DRAFT",
            "D1",
            "D6",
            "D9",
            "import_batches",
            "raw_import_blocks",
            "draft_tasks",
            "SELECT ",
            "Exception",
            "null",
            "/home",
            ".db",
        ).forEach { leak -> assertTrue(words.none { leak in it }, "\"$leak\" reached the screen: $words") }
        val identifier = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        assertTrue(words.none { identifier.containsMatchIn(it) }, "an identifier reached the screen: $words")
    }

    // --------------------------------------------------------------- the lists

    @Test
    fun `sound drafts can be continued or removed and contradicting ones only removed, under a warning`() {
        onScreen { harness, _, _ ->
            assertTrue(harness.saying("Devam eden içe aktarmalar"), harness.everyWord().toString())
            assertNotNull(harness.boundsOf("bir.csv taslağına devam et"))
            assertNotNull(harness.boundsOf("bir.csv taslağını kaldır"))
            assertTrue(harness.saying("Kayıtları uyuşmayan taslaklar"), "the contradicting drafts were not set apart")
            assertTrue(harness.saying("Açılamaz ve onaylanamaz"), "the warning did not say what cannot be done")
            assertNull(harness.boundsOf("bozuk.xlsx taslağına devam et"), "a contradicting draft was offered a way in")
            assertNotNull(harness.boundsOf("bozuk.xlsx taslağını kaldır"))
            assertNoDeveloperWords(harness)
        }
    }

    @Test
    fun `a draft real records point at does not promise that it can be removed`() {
        val fake =
            FakeUnfinishedImports(listOf(anUnfinishedImport(SECOND, "bozuk.xlsx", isContradicting = true, mayBeHeldByRecords = true)))
        onScreen(fake) { harness, _, _ ->
            assertTrue(harness.saying("kaldırma reddedilebilir"), harness.everyWord().toString())
            harness.click("bozuk.xlsx taslağını kaldır")
            harness.render()
            assertTrue(harness.everyWord().count { "kaldırma reddedilebilir" in it } >= 2, "the question did not repeat it")
        }
    }

    @Test
    fun `nothing is drawn with no drafts, and an unreadable list says so`() {
        onScreen(FakeUnfinishedImports(emptyList())) { harness, _, _ ->
            assertTrue(!harness.saying("Devam eden içe aktarmalar"), harness.everyWord().toString())
        }
        onScreen(FakeUnfinishedImports(rows()).apply { listUnreadable = true }) { harness, _, _ ->
            assertTrue(harness.saying("okunamadı"), harness.everyWord().toString())
            assertNoDeveloperWords(harness)
        }
    }

    // --------------------------------------------------------------- opening

    @Test
    fun `continuing opens the review only after the fresh check`() {
        onScreen { harness, _, opened ->
            assertTrue(harness.click("bir.csv taslağına devam et"))
            harness.render()
            assertEquals(listOf(FIRST), opened)
        }
    }

    @Test
    fun `a draft that went stale is not opened and the reason is said`() {
        val fake = FakeUnfinishedImports(rows()).apply { health[FIRST] = FakeUnfinishedImports.contradicting(FIRST) }
        onScreen(fake) { harness, _, opened ->
            harness.click("bir.csv taslağına devam et")
            harness.render()

            assertEquals(emptyList(), opened, "a contradicting draft reached the review screen")
            assertTrue(harness.saying("“bir.csv” taslağının kayıtları birbiriyle uyuşmuyor"), harness.everyWord().toString())
            assertNoDeveloperWords(harness)
        }
    }

    @Test
    fun `continuing is reachable by keyboard and a repeated Enter checks once`() {
        val fake = FakeUnfinishedImports(rows())
        val gate = CompletableDeferred<Unit>()
        fake.healthGate = gate
        onScreen(fake) { harness, _, opened ->
            assertTrue(harness.tabTo("bir.csv taslağına devam et"), "the button was not a Tab stop")
            harness.press(Key.Enter)
            harness.render()
            assertTrue(harness.saying("Taslak denetleniyor"), harness.everyWord().toString())
            harness.press(Key.Enter)
            harness.click("üç.csv taslağına devam et")

            assertEquals(1, fake.healthCalls, "a second check started")
            gate.complete(Unit)
            harness.render()
            harness.render()
            assertEquals(listOf(FIRST), opened)
        }
    }

    // --------------------------------------------------------------- removing

    @Test
    fun `the question says the four things PLAN asks, and starts on the way out`() {
        onScreen { harness, _, _ ->
            harness.click("bir.csv taslağını kaldır")
            harness.render()
            harness.render()

            assertTrue(harness.saying("“bir.csv” dosyasının içe aktarma taslağı kaldırılacak"), harness.everyWord().toString())
            assertTrue(harness.saying("ham hücreleri ve görev taslakları kalıcı olarak silinir"))
            assertTrue(harness.saying("Hiçbir oyun, görev veya hücre değişmez"))
            assertTrue(harness.saying("geri alınamaz"))
            assertEquals(listOf("Vazgeç"), harness.focusedNode()?.contentDescriptions(), "the question did not start on Vazgeç")
            assertNoDeveloperWords(harness)
        }
    }

    @Test
    fun `Enter on the question cancels, and Escape closes it and gives the keyboard back`() {
        val fake = FakeUnfinishedImports(rows())
        onScreen(fake) { harness, controller, _ ->
            harness.tabTo("üç.csv taslağını kaldır")
            harness.press(Key.Enter)
            harness.render()
            harness.render()
            harness.press(Key.Enter)
            harness.render()
            harness.render()
            assertEquals(RemovalFlowState.Closed, controller.removal, "Enter on the first focus did not cancel")
            assertEquals(0, fake.removeCalls, "a stray Enter removed a draft")
            assertEquals(listOf("üç.csv taslağını kaldır"), harness.focusedNode()?.contentDescriptions())

            harness.press(Key.Enter)
            harness.render()
            harness.render()
            harness.press(Key.Escape)
            harness.render()
            harness.render()
            assertEquals(RemovalFlowState.Closed, controller.removal, "Escape did not close the question")
            assertEquals(listOf("üç.csv taslağını kaldır"), harness.focusedNode()?.contentDescriptions())
        }
    }

    @Test
    fun `while the removal runs nothing starts twice and nothing closes it`() {
        val fake = FakeUnfinishedImports(rows())
        val gate = CompletableDeferred<Unit>()
        fake.removeGate = gate
        onScreen(fake) { harness, controller, _ ->
            harness.click("bir.csv taslağını kaldır")
            harness.render()
            assertTrue(harness.click("Kaldır"))
            harness.render()

            assertTrue(harness.saying("Taslak kaldırılıyor"), harness.everyWord().toString())
            harness.click("Kaldır")
            harness.press(Key.Enter)
            harness.press(Key.Escape)
            harness.click("Vazgeç")
            harness.render()

            assertEquals(1, fake.removeCalls, "a repeated press started a second transaction")
            assertTrue(controller.removal is RemovalFlowState.Removing, "the running removal was closed: ${controller.removal}")
            gate.complete(Unit)
            harness.render()
            assertTrue(harness.saying("Taslak kaldırıldı"), harness.everyWord().toString())
        }
    }

    @Test
    fun `a removal says what it took, and the keyboard goes to the next row`() {
        onScreen { harness, _, _ ->
            harness.click("bir.csv taslağını kaldır")
            harness.render()
            harness.click("Kaldır")
            harness.render()

            assertTrue(harness.saying("3 ham hücre ve 2 görev taslağı silindi"), harness.everyWord().toString())
            harness.click("Kapat")
            harness.render()
            harness.render()
            harness.render()
            assertEquals(listOf("üç.csv taslağına devam et"), harness.focusedNode()?.contentDescriptions())
        }
    }

    @Test
    fun `every refusal the engine has is its own sentence`() {
        val sentences =
            mapOf(
                DraftRemovalRefusal.ALREADY_REMOVED to "zaten kaldırılmış",
                DraftRemovalRefusal.NOT_A_DRAFT to "artık taslak değil",
                DraftRemovalRefusal.HELD_BY_RECORDS to "gerçek kayıtlar bu taslağa bağlı olduğu için",
                DraftRemovalRefusal.COULD_NOT_SAVE to "kaldırılamadı; hiçbir şey değişmedi",
            )
        assertEquals(DraftRemovalRefusal.entries.toSet(), sentences.keys)
        sentences.forEach { (refusal, sentence) ->
            val fake = FakeUnfinishedImports(rows()).apply { outcome = { DraftRemovalOutcome.Refused(it, refusal) } }
            onScreen(fake) { harness, _, _ ->
                harness.click("bozuk.xlsx taslağını kaldır")
                harness.render()
                harness.click("Kaldır")
                harness.render()

                assertTrue(harness.saying("Taslak kaldırılmadı"), "$refusal: ${harness.everyWord()}")
                assertTrue(harness.saying(sentence), "$refusal: ${harness.everyWord()}")
                assertNoDeveloperWords(harness)
            }
        }
    }

    // --------------------------------------------------------- window and scale

    @Test
    fun `a narrow window at twice the density keeps every button on screen`() {
        val width = 640
        val height = 620
        onScreen(width = width, height = height, density = Density(2f)) { harness, _, _ ->
            listOf("bir.csv taslağına devam et", "bir.csv taslağını kaldır").forEach { name ->
                val bounds = assertNotNull(harness.boundsOf(name), "$name was not drawn")
                assertTrue(bounds.right <= width && bounds.left >= 0f, "$name ran off the side: $bounds")
            }
            harness.click("bir.csv taslağını kaldır")
            harness.render()
            listOf("Kaldır", "Vazgeç").forEach { name ->
                val bounds = assertNotNull(harness.boundsOf(name), "$name was not drawn")
                assertTrue(bounds.right <= width && bounds.bottom <= height, "$name ran off the window: $bounds")
                assertTrue(bounds.left >= 0f && bounds.top >= 0f, "$name was drawn off the top left: $bounds")
            }
        }
    }
}
