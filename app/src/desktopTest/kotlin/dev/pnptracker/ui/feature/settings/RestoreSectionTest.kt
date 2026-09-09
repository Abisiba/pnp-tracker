package dev.pnptracker.ui.feature.settings

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.RestoreProblem
import dev.pnptracker.domain.backup.restore.fileOf
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val RESTORE_BUTTON = "Bir yedek dosyası seçer ve bütün verinizin yerine koyar"

/**
 * The restore, composed for real, with real keys.
 *
 * What a source search cannot see is exactly what matters here: whether the
 * destructive answer is one stray Enter away from the safe one, whether the
 * question can appear before the file has been checked, whether anything out of
 * an unverified file ends up on screen in the application's own voice, and
 * whether a narrow window puts either answer out of reach.
 *
 * The controller under it is the real one with the real reader, so a question
 * appearing at all means a file really passed every check.
 */
class RestoreSectionTest {
    private fun harness(
        controller: RestoreController,
        width: Int = 900,
        height: Int = 760,
        density: Density = Density(1f),
    ) = ComposeSceneHarness(width = width, height = height, density = density) {
        PnpTrackerTheme(ThemeMode.LIGHT) { RestoreSection(controller) }
    }

    private fun ComposeSceneHarness.text(): String = writtenText().joinToString(" | ")

    @Test
    fun `the section says what a restore does and what it does first`() {
        harness(aRestoreController(FakeSourceGateway(aRealBackupFile()))).use { harness ->
            val text = harness.text()
            assertTrue("Yedekten geri yükle" in text, text)
            // The two things somebody has to know before pressing it: that this
            // replaces rather than merges, and that a copy is taken first.
            assertTrue("yerine geçer" in text, text)
            assertTrue("Birleştirme yapılmaz" in text, text)
            assertTrue("güvenlik yedeği" in text, text)
        }
    }

    @Test
    fun `nothing about the format is on the screen`() {
        harness(aRestoreController(FakeSourceGateway(aRealBackupFile()))).use { harness ->
            val text = harness.text()
            listOf("formatVersion", "sha256", "SHA-256", "schema", "UTF-8", "checksum", "transaction", "SQL").forEach {
                assertFalse(it in text, "'$it' is this application's own business, not the user's: $text")
            }
        }
    }

    @Test
    fun `the action can be reached and started from the keyboard alone`() {
        val gateway = FakeSourceGateway(aRealBackupFile())
        harness(aRestoreController(gateway)).use { harness ->
            assertTrue(harness.tabTo(RESTORE_BUTTON), "the button cannot be tabbed to")
            harness.press(Key.Enter)
            harness.render()

            assertEquals(1, gateway.asked)
        }
    }

    @Test
    fun `a held Enter opens one dialog and not a stream of them`() {
        val gateway = FakeSourceGateway(aRealBackupFile())
        gateway.gate = CompletableDeferred()
        harness(aRestoreController(gateway)).use { harness ->
            harness.tabTo(RESTORE_BUTTON)
            repeat(4) {
                harness.press(Key.Enter)
                harness.render()
            }

            assertEquals(1, gateway.asked, "a repeated key opened the dialog more than once")
            assertTrue("Yedek dosyası seçiliyor…" in harness.text(), harness.text())
            gateway.gate?.complete(Unit)
        }
    }

    @Test
    fun `while the file is being checked there is no destructive question on screen`() {
        // PLAN 12.16 in one test: the answer that replaces everything cannot be
        // reached until the checking has finished and said yes.
        val gateway = FakeSourceGateway(aRealBackupFile())
        gateway.gate = CompletableDeferred()
        harness(aRestoreController(gateway)).use { harness ->
            harness.click(RESTORE_BUTTON)
            harness.render()

            val text = harness.text()
            assertFalse("Bu yedek geri yüklensin mi?" in text, text)
            assertFalse("Geri yükle" in text.removePrefix("Yedekten geri yükle"), text)
            gateway.gate?.complete(Unit)
        }
    }

    @Test
    fun `a checked backup asks the question, with the counts and the date and nothing else`() {
        val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()))
        harness(controller).use { harness ->
            harness.click(RESTORE_BUTTON)
            harness.render()

            val text = harness.text()
            assertTrue("Bu yedek geri yüklensin mi?" in text, text)
            assertTrue("tamamen değiştirilecek" in text, text)
            assertTrue("güvenlik yedeği oluşturulacak" in text, text)
            assertTrue("pnp-yedek-2026-09-08.json" in text, text)
            // One game, two tasks, one colour — the fixture, counted, in the
            // user's own calendar rather than in the file's UTC text.
            assertTrue("1 oyun, 2 görev ve 1 renk" in text, text)
            assertTrue("08.09.2026" in text, "the date is not in the arrangement the rest of the application uses: $text")
            // Nothing out of the file itself, however well it checked out.
            listOf("Harmonies", "Kırmızı", "oyunlar.xlsx", "aa000000").forEach {
                assertFalse(it in text, "the question showed '$it' out of an unverified file: $text")
            }
        }
    }

    @Test
    fun `the keyboard lands on the answer that changes nothing`() {
        val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()))
        harness(controller).use { harness ->
            harness.click(RESTORE_BUTTON)
            harness.render()
            harness.render()

            assertEquals(
                listOf("Vazgeç"),
                harness.focusedNode()?.contentDescriptions(),
                "a stray Enter would have replaced everything",
            )
        }
    }

    @Test
    fun `escape answers no, writes nothing and gives the keyboard back`() {
        val safety = FakeSafetyWriter()
        val restorer = FakeRestorer()
        val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer)
        harness(controller).use { harness ->
            harness.click(RESTORE_BUTTON)
            harness.render()

            harness.press(Key.Escape)
            harness.render()
            harness.render()

            assertFalse("Bu yedek geri yüklensin mi?" in harness.text(), harness.text())
            assertEquals(0, safety.writes, "escape made a safety backup")
            assertEquals(0, restorer.applied, "escape replaced the data")
            assertTrue(
                harness.focusedNode()?.contentDescriptions()?.contains(RESTORE_BUTTON) == true,
                "the keyboard did not go back to the button that asked",
            )
        }
    }

    @Test
    fun `backing out writes nothing at all`() {
        val safety = FakeSafetyWriter()
        val restorer = FakeRestorer()
        val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer)
        harness(controller).use { harness ->
            harness.click(RESTORE_BUTTON)
            harness.render()

            harness.click("Vazgeç")
            harness.render()

            assertEquals(0, safety.writes)
            assertEquals(0, restorer.applied)
        }
    }

    @Test
    fun `agreeing replaces the data and names both files afterwards`() {
        val safety = FakeSafetyWriter()
        val restorer = FakeRestorer()
        val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer)
        harness(controller).use { harness ->
            harness.click(RESTORE_BUTTON)
            harness.render()

            harness.click("Geri yükle")
            harness.render()

            assertEquals(1, safety.writes)
            assertEquals(1, restorer.applied)
            val text = harness.text()
            assertTrue("Yedek geri yüklendi: pnp-yedek-2026-09-08.json" in text, text)
            assertTrue("pnp-oncesi-2026-09-09-090924.json" in text, "the way back is not named: $text")
        }
    }

    @Test
    fun `a second press on the destructive button does nothing`() {
        val safety = FakeSafetyWriter()
        safety.gate = CompletableDeferred()
        val restorer = FakeRestorer()
        val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer)
        harness(controller).use { harness ->
            harness.click(RESTORE_BUTTON)
            harness.render()

            harness.click("Geri yükle")
            harness.render()
            // The question has gone, so there is nothing left to press a second
            // time — which is the point: the surface cannot be answered twice and
            // cannot be closed while the work is running (PLAN 12.16).
            assertFalse("Bu yedek geri yüklensin mi?" in harness.text(), harness.text())
            harness.press(Key.Escape)
            harness.render()
            assertTrue("Güvenlik yedeği yazılıyor…" in harness.text(), harness.text())

            safety.gate?.complete(Unit)
            harness.render()
            assertEquals(1, restorer.applied)
        }
    }

    @Test
    fun `the button is closed while anything is running`() {
        val gateway = FakeSourceGateway(aRealBackupFile())
        gateway.gate = CompletableDeferred()
        harness(aRestoreController(gateway)).use { harness ->
            harness.click(RESTORE_BUTTON)
            harness.render()

            harness.click(RESTORE_BUTTON)
            harness.render()

            assertEquals(1, gateway.asked, "a second press got through while the dialog was open")
            gateway.gate?.complete(Unit)
        }
    }

    @Test
    fun `a refused file says why in Turkish and offers no way to go ahead`() {
        val safety = FakeSafetyWriter()
        val controller = aRestoreController(FakeSourceGateway(fileOf("{ this is not a backup")), safety = safety)
        harness(controller).use { harness ->
            harness.click(RESTORE_BUTTON)
            harness.render()

            val text = harness.text()
            assertTrue("Geri yükleme yapılmadı" in text, text)
            assertTrue("bozuk görünüyor" in text, text)
            assertFalse("Bu yedek geri yüklensin mi?" in text, "a refused file still asked the destructive question: $text")
            assertEquals(0, safety.writes)
            // Nothing of the reader's own vocabulary reaches the screen.
            listOf("MALFORMED", "JSON", "BackupProblem", "Exception", "/home/").forEach {
                assertFalse(it in text, "'$it' reached the screen: $text")
            }
        }
    }

    @Test
    fun `a restore that could not happen names the file the user can go back to`() {
        val safety = FakeSafetyWriter()
        val restorer = FakeRestorer(RestoreProblem.DATA_CHANGED_MEANWHILE)
        val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer)
        harness(controller).use { harness ->
            harness.click(RESTORE_BUTTON)
            harness.render()
            harness.click("Geri yükle")
            harness.render()

            val text = harness.text()
            assertTrue("işlem sırasında değişti" in text, text)
            assertTrue("pnp-oncesi-2026-09-09-090924.json" in text, "the way back is not named: $text")
        }
    }

    @Test
    fun `a safety backup that could not be written says the data is untouched`() {
        val safety = FakeSafetyWriter(refuse = BackupFailure.NOT_WRITABLE)
        val restorer = FakeRestorer()
        val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer)
        harness(controller).use { harness ->
            harness.click(RESTORE_BUTTON)
            harness.render()
            harness.click("Geri yükle")
            harness.render()

            val text = harness.text()
            assertTrue("Güvenlik yedeği diske yazılamadı" in text, text)
            assertTrue("olduğu gibi duruyor" in text, text)
            assertEquals(0, restorer.applied)
        }
    }

    @Test
    fun `every refusal and every failure has a Turkish sentence of its own to choose from`() {
        // Exhaustiveness is the compiler's job; what this checks is that the
        // mapping never lands on nothing, and that no reason is answered with the
        // name of an enum.
        val problems = BackupProblem.entries.map { messageFor(it) }
        val failures = RestoreProblem.entries.map { messageFor(it) }

        assertEquals(BackupProblem.entries.size, problems.size)
        assertEquals(RestoreProblem.entries.size, failures.size)
        // Six ways a good backup can fail to go back, and six things to say: all
        // of them mean something different about the user's data.
        assertEquals(RestoreProblem.entries.size, failures.toSet().size, "two restore failures share a sentence")
        assertTrue(problems.toSet().size >= 10, "the refusals were flattened into too few sentences")
    }

    @Test
    fun `in a narrow window at a large scale both answers are still on screen`() {
        val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()))
        harness(controller, width = 640, height = 620, density = Density(2f)).use { harness ->
            harness.click(RESTORE_BUTTON)
            harness.render()
            harness.render()

            val cancel = assertNotNull(harness.boundsOf("Vazgeç"), "the safe answer is missing at 640x620 and twice the scale")
            val confirm = assertNotNull(harness.boundsOf("Geri yükle"), "the restore answer is missing at 640x620 and twice the scale")
            listOf(cancel, confirm).forEach { bounds ->
                assertTrue(bounds.left >= 0f && bounds.right <= 640f, "an answer runs off the side: $bounds")
            }
        }
    }
}
