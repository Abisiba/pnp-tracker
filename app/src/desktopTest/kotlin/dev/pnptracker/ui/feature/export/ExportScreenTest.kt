package dev.pnptracker.ui.feature.export

import androidx.compose.ui.input.key.Key
import dev.pnptracker.data.repository.TaskExportSource
import dev.pnptracker.domain.export.ExportFailure
import dev.pnptracker.domain.export.ExportFileGateway
import dev.pnptracker.domain.export.ExportFileHandle
import dev.pnptracker.domain.export.ExportedTask
import dev.pnptracker.domain.export.TaskExportException
import dev.pnptracker.domain.export.TaskExportNames
import dev.pnptracker.domain.export.TaskExportStatus
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The export action composed for real, with real strokes.
 *
 * Nothing here reads the source. What it is for is the parts a source search
 * cannot see: whether the question can be answered from the keyboard, whether
 * Escape says no, whether the outcome is announced, and whether any of it spills
 * off the edge of a narrow window.
 */
class ExportScreenTest {
    private val names =
        TaskExportNames(
            pools = PoolType.entries.associateWith { it.name },
            columns = CellColumnType.entries.associateWith { it.name },
        )

    private fun task() =
        ExportedTask(
            gameName = "Harmonies",
            columnType = CellColumnType.THREE_D,
            taskName = "Kırmızı ev",
            poolType = PoolType.THREE_D,
            colorNames = emptyList(),
            requiredQuantity = 12,
            status = TaskExportStatus.OPEN,
            notes = null,
        )

    private class FakeFile(
        override val fileName: String = "gorevler.csv",
        private val alreadyThere: Boolean = false,
    ) : ExportFileHandle {
        var writes = 0
            private set

        override suspend fun exists(): Boolean = alreadyThere

        override suspend fun write(content: String) {
            writes++
        }
    }

    private class FakeGateway(
        private val destination: ExportFileHandle?,
    ) : ExportFileGateway {
        override suspend fun chooseDestination(suggestedName: String): ExportFileHandle? = destination
    }

    private class FixedTasks(
        private val tasks: List<ExportedTask>,
        private val failure: ExportFailure? = null,
    ) : TaskExportSource {
        override suspend fun exportedTasks(): List<ExportedTask> {
            failure?.let { throw TaskExportException(it) }
            return tasks
        }
    }

    private fun controllerFor(
        destination: ExportFileHandle? = FakeFile(),
        source: TaskExportSource = FixedTasks(listOf(task())),
    ) = ExportController(FakeGateway(destination), source) { names }

    private fun onScreen(
        controller: ExportController,
        width: Int = 1280,
        height: Int = 900,
        theme: ThemeMode = ThemeMode.LIGHT,
        body: (ComposeSceneHarness) -> Unit,
    ) {
        ComposeSceneHarness(width = width, height = height) {
            PnpTrackerTheme(theme) {
                TaskExportAction(controller)
            }
        }.use { harness ->
            harness.render()
            harness.render()
            body(harness)
        }
    }

    private fun ComposeSceneHarness.saying(fragment: String): Boolean = writtenText().any { fragment in it }

    // ------------------------------------------------------------- the action

    @Test
    fun `the action is on screen, named, and says what it covers`() {
        onScreen(controllerFor()) { harness ->
            assertTrue(harness.saying("Görevleri CSV’ye aktar"), harness.writtenText().toString())
            assertTrue(
                harness.spokenNodes().any { node -> node.contentDescriptions().any { "Bütün görevleri" in it } },
                "the action has no accessible name of its own",
            )
            assertTrue(
                harness.saying("Ekrandaki arama ve filtreler dosyanın kapsamını değiştirmez"),
                "nothing says the file ignores the filters",
            )
        }
    }

    @Test
    fun `the action can be reached from the keyboard`() {
        onScreen(controllerFor()) { harness ->
            assertTrue(harness.focusableNodes().isNotEmpty())
            harness.tab()
            assertTrue(harness.focusedNode() != null, "the keyboard could not reach the action")
        }
    }

    // -------------------------------------------------------------- outcomes

    @Test
    fun `a written file is announced with its name and its count`() {
        val controller = controllerFor()
        runBlocking { controller.exportTasks() }

        onScreen(controller) { harness ->
            assertTrue(harness.saying("1 görev gorevler.csv dosyasına yazıldı."), harness.writtenText().toString())
        }
    }

    @Test
    fun `nothing to export is said safely, with no path and no exception`() {
        val controller = controllerFor(source = FixedTasks(emptyList(), ExportFailure.NOTHING_TO_EXPORT))
        runBlocking { controller.exportTasks() }

        onScreen(controller) { harness ->
            assertTrue(harness.saying("Dışa aktarılacak görev yok"), harness.writtenText().toString())
            val shown = harness.writtenText().joinToString("\n")
            listOf("Exception", "SELECT", "/home/", "java.", "dev.pnptracker", "NOTHING_TO_EXPORT").forEach { forbidden ->
                assertTrue(forbidden !in shown, "`$forbidden` reached the screen:\n$shown")
            }
        }
    }

    @Test
    fun `a broken record is said in words a person can act on`() {
        val controller = controllerFor(source = FixedTasks(emptyList(), ExportFailure.BROKEN_DATA))
        runBlocking { controller.exportTasks() }

        onScreen(controller) { harness ->
            assertTrue(harness.saying("Görevler dışa aktarılamadı"))
            assertTrue(harness.saying("Hiçbir dosya oluşturulmadı veya değiştirilmedi"))
        }
    }

    @Test
    fun `every failure is said in its own words, with nothing technical in them`() {
        val sentences =
            ExportFailure.entries.associateWith { failure ->
                val controller = controllerFor(source = FixedTasks(emptyList(), failure))
                runBlocking { controller.exportTasks() }
                var shown = ""
                onScreen(controller, width = 720, height = 880) { harness ->
                    assertTrue(harness.saying("Görevler dışa aktarılamadı"), "$failure drew no failure: ${harness.writtenText()}")
                    shown = harness.writtenText().joinToString("\n")
                    harness.nodes().forEach { node ->
                        assertTrue(node.boundsInRoot.right <= 721f, "the $failure sentence ran past a 720 wide window")
                    }
                }
                val forbidden =
                    listOf("Exception", "SQL", "SELECT", "/", "\\", "java.", "dev.pnptracker", "null", "tasks", failure.name) +
                        ExportFailure.entries.map { it.name }
                forbidden.forEach { word -> assertTrue(word !in shown, "`$word` reached the screen for $failure:\n$shown") }
                shown
            }

        assertEquals(ExportFailure.entries.size, sentences.values.toSet().size, "two failures share one sentence: $sentences")
    }

    @Test
    fun `changing one's mind says nothing at all`() {
        val controller = controllerFor(destination = null)
        runBlocking { controller.exportTasks() }

        onScreen(controller) { harness ->
            assertTrue(!harness.saying("Görevler dışa aktarılamadı"), "cancelling was drawn as a failure")
            assertTrue(!harness.saying("dosyasına yazıldı"))
        }
    }

    // ------------------------------------------------------------ the question

    @Test
    fun `the overwrite question names the file and offers both answers`() {
        val controller = controllerFor(destination = FakeFile(alreadyThere = true))
        runBlocking { controller.exportTasks() }

        onScreen(controller) { harness ->
            assertTrue(harness.saying("Dosya zaten var"))
            assertTrue(harness.saying("gorevler.csv adlı dosyanın üzerine yazılsın mı?"))
            assertTrue(harness.saying("Üzerine yaz"))
            assertTrue(harness.saying("Vazgeç"))
        }
    }

    @Test
    fun `Escape answers the question with a no`() {
        val file = FakeFile(alreadyThere = true)
        val controller = controllerFor(destination = file)
        runBlocking { controller.exportTasks() }

        onScreen(controller) { harness ->
            // No Tab first: the question takes the keyboard as it appears, so
            // Escape means no from the moment it is asked.
            harness.press(Key.Escape)
            harness.render()

            assertEquals(ExportScreenState.Idle, controller.state)
            assertEquals(0, file.writes, "Escape replaced the file")
        }
    }

    @Test
    fun `both answers can be reached by keyboard`() {
        val controller = controllerFor(destination = FakeFile(alreadyThere = true))
        runBlocking { controller.exportTasks() }

        onScreen(controller) { harness ->
            assertTrue(harness.focusedNode() != null, "the question did not take the keyboard")
            val reached = mutableSetOf<String>()
            repeat(6) {
                harness.tab()
                harness.focusedNode()?.let { node -> reached += node.boundsInRoot.toString() }
            }

            assertTrue(reached.size >= 2, "the two answers could not both be reached: $reached")
        }
    }

    // --------------------------------------------------------------- the layout

    @Test
    fun `nothing spills off the edge of a narrow window`() {
        val controller = controllerFor(destination = FakeFile(alreadyThere = true))
        runBlocking { controller.exportTasks() }

        onScreen(controller, width = 720, height = 880, theme = ThemeMode.DARK) { harness ->
            assertTrue(harness.saying("Dosya zaten var"))
            harness.nodes().forEach { node ->
                assertTrue(node.boundsInRoot.right <= 721f, "something reached past the right edge of a 720 wide window")
            }
        }
    }

    @Test
    fun `a long file name wraps rather than running off the edge`() {
        val longName = "çok-uzun-bir-dosya-adı-" + "görevler-".repeat(6) + ".csv"
        val controller = controllerFor(destination = FakeFile(fileName = longName, alreadyThere = true))
        runBlocking { controller.exportTasks() }

        onScreen(controller, width = 720, height = 880) { harness ->
            harness.nodes().forEach { node ->
                assertTrue(node.boundsInRoot.right <= 721f, "a long name pushed something off the edge")
            }
        }
    }

    @Test
    fun `the dark theme draws the same words`() {
        val controller = controllerFor()
        runBlocking { controller.exportTasks() }

        onScreen(controller, theme = ThemeMode.DARK) { harness ->
            assertTrue(harness.saying("dosyasına yazıldı"))
            assertTrue(harness.saying("Görevleri CSV’ye aktar"))
        }
    }
}
