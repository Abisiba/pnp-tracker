package dev.pnptracker.ui.feature.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.pnptracker.domain.settings.AutomaticBackupSettings
import dev.pnptracker.domain.settings.SettingsNotSaved
import dev.pnptracker.domain.settings.SettingsProblem
import dev.pnptracker.domain.settings.SettingsStore
import dev.pnptracker.domain.settings.SettingsWriteFailure
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Settings that are simply there, with no disk anywhere. */
class SceneSettings(
    private val stored: AutomaticBackupSettings = AutomaticBackupSettings(),
    private val refuseWith: SettingsWriteFailure? = null,
) : SettingsStore {
    val written = mutableListOf<Int>()

    override suspend fun read(): AutomaticBackupSettings = stored

    override suspend fun write(automaticBackupCount: Int) {
        written += automaticBackupCount
        refuseWith?.let { throw SettingsNotSaved(it) }
    }
}

/**
 * What the retention setting actually puts in front of somebody.
 *
 * A real Compose scene rather than a reading of the state, because three of the
 * things PLAN 12.16 asks for are only true on a drawn screen: that the three
 * surprising facts about the number are said out loud, that a refused value is
 * shown as refused rather than swallowed, and that none of it falls off the edge
 * of a narrow window.
 */
class RetentionSectionTest {
    private fun harness(
        controller: RetentionController,
        width: Int = 900,
        height: Int = 700,
        density: Density = Density(1f),
    ) = ComposeSceneHarness(width = width, height = height, density = density) {
        PnpTrackerTheme(ThemeMode.LIGHT) { RetentionSection(controller) }
    }

    private fun ComposeSceneHarness.text(): String = writtenText().joinToString(" | ")

    /**
     * What is in the box.
     *
     * A field's contents are `EditableText` rather than `Text`, so the harness's
     * own reading does not include them — and the box is exactly where a refused
     * value has to stay visible.
     */
    private fun ComposeSceneHarness.fieldText(): String =
        nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }.joinToString(" | ")

    @Test
    fun `it says what the number counts, what it does not, and when it takes effect`() {
        harness(RetentionController(SceneSettings())).use { harness ->
            val text = harness.text()

            assertTrue("Otomatik yedeklerin saklanması" in text, text)
            // Per kind, not overall.
            assertTrue("üç tür için ayrı ayrı" in text, text)
            assertTrue("içe aktarma öncesi" in text && "geri yükleme öncesi" in text, text)
            // Their own backups are outside it.
            assertTrue("Kendi aldığınız yedekler" in text && "hiçbir zaman kendiliğinden silinmez" in text, text)
            // Lowering it deletes nothing today.
            assertTrue("o anda silmez" in text, text)
            assertTrue("bir sonraki otomatik yedek" in text, text)
        }
    }

    @Test
    fun `the number in use is shown, and so is the range`() {
        harness(RetentionController(SceneSettings(AutomaticBackupSettings(12)))).use { harness ->
            val text = harness.text()

            assertTrue("12" in text, text)
            assertTrue("1 ile 50 arasında" in text, text)
            assertTrue("en yeni 12 yedek saklanıyor" in text, text)
        }
    }

    @Test
    fun `the two steppers have words of their own`() {
        harness(RetentionController(SceneSettings())).use { harness ->
            val spoken = harness.spokenNodes().flatMap { it.contentDescriptions() }

            // They show a symbol, so without these a screen reader says nothing
            // useful about either (PLAN 17).
            assertTrue(spoken.any { "bir azalt" in it }, spoken.toString())
            assertTrue(spoken.any { "bir artır" in it }, spoken.toString())
        }
    }

    @Test
    fun `a number that may not be kept is shown as refused, and the save is not offered`() {
        val controller = RetentionController(SceneSettings())
        harness(controller).use { harness ->
            harness.render()
            listOf("0", "-1", "51", "yedi").forEach { typed ->
                controller.type(typed)
                harness.render()

                val text = harness.text()
                assertEquals(typed, harness.fieldText(), "the field stopped showing what was typed")
                assertTrue("1 ile 50 arasında olmalıdır" in text, "$typed: $text")
                assertTrue("kapatılamaz" in text, "$typed: $text")
                assertFalse(controller.canSave, typed)
            }
        }
    }

    @Test
    fun `a file that could not be used is explained, with the default beside it`() {
        val cases =
            mapOf(
                SettingsProblem.COULD_NOT_READ to "okunamadı",
                SettingsProblem.NOT_THE_EXPECTED_SHAPE to "tanınmadı",
                SettingsProblem.VERSION_NOT_SUPPORTED to "tanımadığı bir biçimde",
                SettingsProblem.VALUE_OUT_OF_RANGE to "geçerli aralıkta değil",
            )

        cases.forEach { (problem, expected) ->
            harness(RetentionController(SceneSettings(AutomaticBackupSettings(problem = problem)))).use { harness ->
                val text = harness.text()

                assertTrue("Ayar dosyanız kullanılamadı" in text, "$problem: $text")
                assertTrue(expected in text, "$problem: $text")
                // The sentence that matters: their file is still their file.
                assertTrue("olduğu gibi bırakıldı" in text, "$problem: $text")
                assertTrue("varsayılan 7" in text, "$problem: $text")
            }
        }
    }

    @Test
    fun `every reason a save can fail becomes its own sentence`() {
        val seen =
            SettingsWriteFailure.entries.map { failure ->
                val controller = RetentionController(SceneSettings(AutomaticBackupSettings(9), refuseWith = failure))
                harness(controller).use { harness ->
                    harness.render()
                    controller.type("20")
                    runBlocking { controller.save() }
                    harness.render()

                    val text = harness.text()
                    assertTrue("Sayı kaydedilemedi" in text, "$failure: $text")
                    // And what is still true: the application is keeping to the
                    // old number, not the one that never landed.
                    assertTrue("9 saklamaya devam ediyor" in text, "$failure: $text")
                    text
                }
            }

        assertEquals(seen.size, seen.toSet().size, "two failures were given the same words")
    }

    @Test
    fun `saving says so, in the number that was saved`() {
        val controller = RetentionController(SceneSettings())
        harness(controller).use { harness ->
            harness.render()
            controller.type("21")
            runBlocking { controller.save() }
            harness.render()

            assertTrue("21 olarak kaydedildi" in harness.text(), harness.text())
        }
    }

    @Test
    fun `nothing here shows a path, a version or anything technical`() {
        val controller = RetentionController(SceneSettings(AutomaticBackupSettings(problem = SettingsProblem.COULD_NOT_READ)))
        harness(controller).use { harness ->
            controller.type("0")
            harness.render()
            val text = harness.text()

            listOf("settings.json", "formatVersion", "/", "\\", "json", "JSON", "Exception", "COULD_NOT").forEach { leak ->
                assertFalse(leak in text, "$leak leaked: $text")
            }
        }
    }

    @Test
    fun `a narrow window at twice the size still shows the field and the save`() {
        val controller = RetentionController(SceneSettings())
        // The wrapping row is what this is for: the save must not go off the
        // edge when the window is small and the text is large (PLAN 17).
        harness(controller, width = 560, height = 720, density = Density(2f)).use { harness ->
            val text = harness.text()

            assertTrue("Sayıyı kaydet" in text, text)
            assertTrue("1 ile 50 arasında" in text, text)
        }
    }
}
