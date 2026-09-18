package dev.pnptracker.ui.feature.startup

import androidx.compose.ui.unit.Density
import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The window a person sees when the application would not open their database.
 *
 * This is the last thing PLAN 14.4.10 asks for and the easiest to get wrong,
 * because it is the screen nobody sees while everything works. What it has to do
 * is say which of the eight things happened, in words somebody can act on, and
 * leak none of the four things PLAN 14.4.5 keeps off a screen — a path, a piece
 * of SQL, an identifier, or the text of an exception.
 */
class StartupErrorScreenTest {
    private fun harness(
        problem: StartupProblem,
        width: Int = 560,
        height: Int = 360,
        density: Density = Density(1f),
    ) = ComposeSceneHarness(width = width, height = height, density = density) {
        PnpTrackerTheme(ThemeMode.LIGHT) { StartupErrorScreen(problem = problem) {} }
    }

    private fun ComposeSceneHarness.text(): String = writtenText().joinToString(" | ")

    @Test
    fun `every reason has words of its own`() {
        val seen =
            StartupProblem.entries.map { problem ->
                harness(problem).use { harness ->
                    val text = harness.text()
                    assertTrue("PNP açılamadı" in text, "$problem: $text")
                    assertTrue("Kapat" in text, "$problem: $text")
                    text
                }
            }

        assertEquals(seen.size, seen.toSet().size, "two reasons were given the same words")
    }

    @Test
    fun `each reason says the thing a person can do about it`() {
        val expected =
            mapOf(
                StartupProblem.ANOTHER_COPY_IS_RUNNING to "Açık olan pencereyi kapatıp",
                StartupProblem.FOLDERS_NOT_CREATED to "kendi klasörlerini oluşturamadı",
                StartupProblem.DATABASE_NOT_READABLE to "okunamadı",
                StartupProblem.SCHEMA_TOO_NEW to "güncelleyip yeniden deneyin",
                StartupProblem.SNAPSHOT_NOT_CLONED to "Diskte yer olduğundan emin olup",
                StartupProblem.SNAPSHOT_NOT_MIGRATED to "asıl verilerinize hiç dokunulmadı",
                StartupProblem.SNAPSHOT_NOT_WRITTEN to "diske yazılamadı",
                StartupProblem.SNAPSHOT_NOT_VERIFIED to "doğrulanamadı",
                StartupProblem.MIGRATION_FAILED to "yedek klasörünüzde duruyor",
            )

        expected.forEach { (problem, words) ->
            harness(problem).use { harness ->
                assertTrue(words in harness.text(), "$problem: ${harness.text()}")
            }
        }
    }

    @Test
    fun `it says nothing was changed, except where that would be untrue`() {
        // Seven of the eight never reach the user's database at all, and saying
        // so is the most useful sentence on the screen. The eighth is the one
        // where a migration was really attempted, and there the screen points at
        // the backup instead of making a promise it cannot keep.
        StartupProblem.entries.filter { it != StartupProblem.MIGRATION_FAILED }.forEach { problem ->
            harness(problem).use { harness ->
                assertTrue("Verileriniz olduğu gibi duruyor" in harness.text(), "$problem")
            }
        }

        harness(StartupProblem.MIGRATION_FAILED).use { harness ->
            assertFalse("Verileriniz olduğu gibi duruyor" in harness.text(), "a failed migration claimed to have changed nothing")
        }
    }

    @Test
    fun `nothing technical reaches the screen`() {
        StartupProblem.entries.forEach { problem ->
            harness(problem).use { harness ->
                val text = harness.text()
                listOf(
                    "SNAPSHOT_",
                    "MIGRATION_FAILED",
                    "ANOTHER_COPY",
                    "FOLDERS_NOT_CREATED",
                    "Exception",
                    "SQLite",
                    "SELECT",
                    "PRAGMA",
                    "/home",
                    "pnp.db",
                    ".json",
                    ".db",
                    "user_version",
                    "Room",
                ).forEach { leak ->
                    assertFalse(leak in text, "$leak leaked for $problem: $text")
                }
            }
        }
    }

    @Test
    fun `the way out is reachable and named`() {
        harness(StartupProblem.MIGRATION_FAILED).use { harness ->
            val spoken = harness.spokenNodes().flatMap { it.contentDescriptions() }
            assertTrue(spoken.any { "Kapat" in it }, spoken.toString())
        }
    }

    @Test
    fun `a small window at twice the size still shows the reason and the way out`() {
        harness(StartupProblem.SNAPSHOT_NOT_VERIFIED, width = 420, height = 300, density = Density(2f)).use { harness ->
            val text = harness.text()
            assertTrue("PNP açılamadı" in text, text)
            assertTrue("Kapat" in text, text)
        }
    }
}
