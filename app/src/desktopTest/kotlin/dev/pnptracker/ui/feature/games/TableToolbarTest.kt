package dev.pnptracker.ui.feature.games

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.reads
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The game table's one bar of tools (PLAN 12.3).
 *
 * At the size the window opens at, search, the three views, the two orders,
 * the filters, adding a game and the `⋯` menu share one line, nothing above them
 * repeats the navigation, and the table starts right under them.
 */
class TableToolbarTest {
    private fun ComposeSceneHarness.settle(
        what: String,
        done: () -> Boolean,
    ) {
        repeat(200) {
            render()
            if (done()) {
                repeat(2) { render() }
                return
            }
            Thread.sleep(10)
        }
        fail("never happened: $what")
    }

    private var exportAsked = 0

    private fun open(stack: RealStack): ComposeSceneHarness {
        val screen =
            ComposeSceneHarness(width = 1100, height = 720) {
                GameTableScreen(
                    stack.table,
                    export = TableExport(start = { exportAsked++ }, enabled = true, status = {}),
                )
            }
        screen.settle("the table is read") { stack.table.state.rows !is GameTableRowsState.Loading }
        runBlocking {
            stack.table.startGameComposer()
            stack.table.editGameName("Harmonies")
            stack.table.saveGame()
        }
        screen.settle("the game is shown") { stack.rows().isNotEmpty() }
        return screen
    }

    private fun ComposeSceneHarness.said(description: String): Rect =
        spokenNodes().firstOrNull { node -> description in node.contentDescriptions() }?.boundsInRoot
            ?: fail("`$description` is not on the screen")

    private fun ComposeSceneHarness.written(text: String): Rect =
        nodes()
            .filter { node -> node.reads(SemanticsProperties.Text).orEmpty().any { it.text == text } }
            .map { it.boundsInRoot }
            .minByOrNull { it.top } ?: fail("`$text` is not written")

    @Test
    fun `at the opening size every tool is on one line, with no title above it`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                val tools =
                    listOf(
                        screen.said("Ara"),
                        screen.written("Devam Eden"),
                        screen.written("Tümü"),
                        screen.written("Benim sıram"),
                        screen.written("A–Z"),
                        screen.written("Oyun ekle"),
                        screen.said("Diğer işlemler"),
                    )
                val line = tools.first().center.y
                tools.forEach { tool ->
                    assertTrue(line in tool.top..tool.bottom, "a tool is not on the toolbar's line: $tool, line at $line; $tools")
                }
                assertFalse("Oyun Tablosu" in screen.writtenText(), "the big title is still written")
                // The table's own heading is right under the bar.
                val heading = screen.written("Eksik")
                val barBottom = tools.maxOf { it.bottom }
                assertTrue(heading.top - barBottom < 60f, "the table does not start right under the toolbar: $barBottom → ${heading.top}")
            }
        }
    }

    @Test
    fun `the menu holds the size actions and the export, and the export is asked for`() {
        RealStack().use { stack ->
            open(stack).use { screen ->
                assertTrue(screen.click("Diğer işlemler"), "the menu does not open")
                screen.render()
                listOf("Sütunu içeriğe göre ayarla", "Hücre boyutlarını sıfırla", "Görevleri CSV’ye aktar").forEach { item ->
                    assertTrue(screen.spokenNodes().any { item in it.contentDescriptions() }, "the menu does not offer `$item`")
                }

                assertTrue(screen.click("Görevleri CSV’ye aktar"))
                screen.render()
                assertEquals(1, exportAsked, "the export was not asked for")
            }
        }
    }
}
