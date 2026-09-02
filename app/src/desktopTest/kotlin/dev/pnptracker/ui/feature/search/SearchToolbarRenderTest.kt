package dev.pnptracker.ui.feature.search

import androidx.compose.ui.input.key.Key
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.pools.PoolColor
import dev.pnptracker.domain.pools.PoolNavigationSummary
import dev.pnptracker.domain.pools.PoolSnapshot
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.domain.search.TaskStateFilter
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.NoColors
import dev.pnptracker.ui.NoEditing
import dev.pnptracker.ui.NoProgress
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.feature.pools.PoolController
import dev.pnptracker.ui.feature.pools.PoolFilterSurface
import dev.pnptracker.ui.feature.pools.PoolScreen
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The search box and the filter panel, composed for real.
 *
 * Everything a filter is *about* is settled by the pure tests. What is left is
 * what only a real composition can show: that the controls are reachable by the
 * keyboard, that Escape closes the panel and gives the keyboard back, that a
 * colour is offered by its name and not only by a square of paint, and that the
 * panel fits inside a window the size PLAN's own manual round uses.
 */
class SearchToolbarRenderTest {
    private val red = PoolColor(IdGenerator.Random.newId(), "Kırmızı", "#C62828", sortOrder = 4)
    private val white = PoolColor(IdGenerator.Random.newId(), "Beyaz", "#FFFFFF", sortOrder = 0)
    private val black = PoolColor(IdGenerator.Random.newId(), "Siyah", "#000000", sortOrder = 1)

    private fun textOf(
        resource: StringResource,
        vararg args: Any,
    ): String = runBlocking { if (args.isEmpty()) getString(resource) else getString(resource, *args) }

    private fun task(
        name: String,
        colors: List<PoolColor> = listOf(red),
        isMissing: Boolean = false,
        isBorrowed: Boolean = false,
        needsInfo: Boolean = false,
        needsClassification: Boolean = false,
        taskId: EntityId = IdGenerator.Random.newId(),
    ) = PoolTask(
        taskId = taskId,
        segmentId = IdGenerator.Random.newId(),
        cellId = IdGenerator.Random.newId(),
        gameId = IdGenerator.Random.newId(),
        gameName = "Harmonies",
        name = name,
        requiredQuantity = 10,
        notes = null,
        trackingMode = TrackingMode.THREE_D_BATCH,
        primaryBatchCompleted = false,
        currentMissingQuantity = 0,
        colors = colors,
        isMissing = isMissing,
        isBorrowed = isBorrowed,
        needsInfo = needsInfo,
        needsClassification = needsClassification,
    )

    /**
     * The pool's own stream, holding whatever the test put in it.
     *
     * `PoolScreen` starts collecting the moment it is composed, so a double that
     * emitted nothing would wipe the tasks the test had just handed over — which
     * is exactly what the screen is meant to do with a real reading.
     */
    private class FixedPools(
        tasks: List<PoolTask>,
    ) : dev.pnptracker.data.repository.PoolSource {
        val snapshots = MutableStateFlow(PoolSnapshot(PoolType.THREE_D, tasks))

        override fun observePool(poolType: PoolType): Flow<PoolSnapshot> = snapshots

        override fun observeNavigationSummary(): Flow<PoolNavigationSummary> = MutableStateFlow(PoolNavigationSummary.EMPTY)
    }

    private fun controllerWith(
        tasks: List<PoolTask>,
        catalogue: List<PoolColor> = listOf(red),
    ): PoolController {
        val summaries = catalogue.map { ColorSummary(it.colorId, it.canonicalName, it.hex, it.sortOrder) }
        val controller =
            PoolController(
                poolType = PoolType.THREE_D,
                pools = FixedPools(tasks),
                colors = NoColors(summaries),
                taskEditing = NoEditing(),
                taskProgress = NoProgress(),
            )
        controller.showColors(summaries)
        controller.show(PoolSnapshot(PoolType.THREE_D, tasks))
        return controller
    }

    private fun scene(
        controller: PoolController,
        width: Int = 1280,
        height: Int = 900,
        theme: ThemeMode = ThemeMode.LIGHT,
        block: (ComposeSceneHarness) -> Unit,
    ) {
        ComposeSceneHarness(width = width, height = height) {
            PnpTrackerTheme(theme) { PoolScreen(controller) }
        }.use { harness ->
            repeat(SETTLING_FRAMES) { harness.render() }
            block(harness)
        }
    }

    /** Everything a reader would be given: the descriptions and the plain words. */
    private fun ComposeSceneHarness.said(): List<String> = spokenNodes().flatMap { it.contentDescriptions() } + writtenText()

    // ------------------------------------------------------- what is drawn

    @Test
    fun `the search box and the filter button are both on the screen at either width`() {
        listOf(1280 to 900, 720 to 880).forEach { (width, height) ->
            scene(controllerWith(listOf(task("Kırmızı ev"))), width = width, height = height) { harness ->
                assertTrue(textOf(Strings.Search.label) in harness.said(), "no search box at $width")
                assertTrue(textOf(Strings.Search.openFilters) in harness.said(), "no filter button at $width")
            }
        }
    }

    @Test
    fun `the filter button says how many choices are in force`() {
        val controller = controllerWith(listOf(task("Kırmızı ev")))
        controller.showState(TaskStateFilter.COMPLETED)
        controller.toggleColor(red.colorId)

        scene(controller) { harness ->
            assertTrue(
                textOf(Strings.Search.openFiltersWithCount, "2") in harness.said(),
                "the button did not say how much was chosen",
            )
        }
    }

    @Test
    fun `the summary says in words what is in force`() {
        val controller = controllerWith(listOf(task("Kırmızı ev")))
        controller.search("kırmızı")
        controller.toggleColor(red.colorId)

        scene(controller) { harness ->
            val summary = harness.said().firstOrNull { it.startsWith(textOf(Strings.Search.summaryLabel, "").trimEnd()) }
            assertNotNull(summary, "nothing said what was in force")
            assertTrue("kırmızı" in summary, "the search term was not said")
            assertTrue("Kırmızı" in summary, "the colour was not said")
        }
    }

    @Test
    fun `nothing chosen is said as nothing chosen`() {
        scene(controllerWith(listOf(task("Kırmızı ev")))) { harness ->
            assertTrue(textOf(Strings.Search.summaryNone) in harness.said())
        }
    }

    // -------------------------------------------------------- the keyboard

    @Test
    fun `the keyboard reaches the filter button and opens the panel with Enter`() {
        val controller = controllerWith(listOf(task("Kırmızı ev")))
        scene(controller) { harness ->
            assertTrue(harness.tabTo(textOf(Strings.Search.openFilters)), "the keyboard never reached the filter button")

            harness.press(Key.Enter)
            harness.render()

            assertEquals(PoolFilterSurface.OPEN, controller.state.filterSurface)
        }
    }

    @Test
    fun `Space opens the panel too`() {
        val controller = controllerWith(listOf(task("Kırmızı ev")))
        scene(controller) { harness ->
            assertTrue(harness.tabTo(textOf(Strings.Search.openFilters)))

            harness.press(Key.Spacebar)
            harness.render()

            assertEquals(PoolFilterSurface.OPEN, controller.state.filterSurface)
        }
    }

    @Test
    fun `Escape closes the panel and the keyboard comes back to the button`() {
        val controller = controllerWith(listOf(task("Kırmızı ev")))
        scene(controller) { harness ->
            assertTrue(harness.tabTo(textOf(Strings.Search.openFilters)))
            harness.press(Key.Enter)
            harness.render()

            harness.press(Key.Escape)
            repeat(SETTLING_FRAMES) { harness.render() }

            assertEquals(PoolFilterSurface.CLOSED, controller.state.filterSurface)
            val focused = harness.focusedNode()?.contentDescriptions().orEmpty()
            assertTrue(
                textOf(Strings.Search.openFilters) in focused,
                "the keyboard was left somewhere else, at $focused",
            )
        }
    }

    @Test
    fun `the clear button appears with the search text and is named in words`() {
        val controller = controllerWith(listOf(task("Kırmızı ev")))
        scene(controller) { harness ->
            assertTrue(textOf(Strings.Search.clear) !in harness.said(), "a clear button with nothing to clear")
        }

        controller.search("kırmızı")
        scene(controller) { harness ->
            assertTrue(textOf(Strings.Search.clear) in harness.said(), "nothing offered to clear the search")
        }
    }

    // --------------------------------------------------------- the panel

    @Test
    fun `every colour of the catalogue is offered by its own name`() {
        val controller = controllerWith(listOf(task("Kırmızı ev")), catalogue = listOf(white, black, red))
        controller.openFilters()

        scene(controller) { harness ->
            listOf(white, black, red).forEach { color ->
                assertTrue(
                    textOf(Strings.Search.colorOption, color.canonicalName) in harness.said(),
                    "${color.canonicalName} was offered without its name",
                )
            }
        }
    }

    @Test
    fun `white and black are offered like every other colour`() {
        // The pair that a swatch alone cannot tell apart from its background.
        val controller = controllerWith(listOf(task("Kırmızı ev")), catalogue = listOf(white, black))
        controller.openFilters()

        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            scene(controller, theme = theme) { harness ->
                assertTrue(textOf(Strings.Search.colorOption, "Beyaz") in harness.said(), "Beyaz went missing in $theme")
                assertTrue(textOf(Strings.Search.colorOption, "Siyah") in harness.said(), "Siyah went missing in $theme")
            }
        }
    }

    @Test
    fun `no colour is offered is written out rather than left to a blank chip`() {
        val controller = controllerWith(listOf(task("Renksiz", colors = emptyList())))
        controller.openFilters()

        scene(controller) { harness ->
            assertTrue(textOf(Strings.Search.awaitingColor) in harness.said())
        }
    }

    @Test
    fun `the panel fits inside the narrow window`() {
        val controller = controllerWith(listOf(task("Kırmızı ev")), catalogue = manyColors())
        controller.openFilters()

        scene(controller, width = 720, height = 880) { harness ->
            // Located by the panel's own close button rather than by its heading:
            // the heading is text and the button carries a description.
            val close = assertNotNull(harness.boundsOf(textOf(Strings.Search.closeFilters)), "the panel was not drawn")
            assertTrue(close.left >= 0f && close.top >= 0f, "the panel started off the screen at $close")
            assertTrue(close.right <= 720f, "the panel ran off the right at $close")
            // The last choice is still inside the window, because the panel
            // scrolls inside itself rather than growing past the bottom.
            val last = assertNotNull(harness.boundsOf(textOf(Strings.Search.shortagesFirst)))
            assertTrue(last.bottom <= 880f, "the panel ran off the bottom at $last")
            assertTrue(last.right <= 720f, "a choice ran off the right at $last")
        }
    }

    // -------------------------------------------------------- the marks

    @Test
    fun `a marked task says which mark it carries`() {
        val controller =
            controllerWith(
                listOf(task("Eksik ev", isMissing = true), task("Ödünç zar", isBorrowed = true)),
            )

        scene(controller) { harness ->
            val said = harness.said().joinToString("\n")
            assertTrue(textOf(Strings.Search.markMissing) in said, "the missing mark was not said")
            assertTrue(textOf(Strings.Search.markBorrowed) in said, "the borrowed mark was not said")
        }
    }

    // ------------------------------------------------------- empty results

    @Test
    fun `a filter that found nothing offers to be cleared`() {
        val controller = controllerWith(listOf(task("Kırmızı ev")))
        controller.search("bulunamaz")

        scene(controller) { harness ->
            assertTrue(controller.state.hasHiddenTasks)
            val said = harness.said().joinToString("\n")
            assertTrue(textOf(Strings.Search.emptyPool) in said, "the empty result was not explained")
            assertTrue(textOf(Strings.Search.clearAll) in said, "nothing offered to loosen the filter")
        }
    }

    @Test
    fun `an empty pool says something else entirely`() {
        val controller = controllerWith(emptyList())

        scene(controller) { harness ->
            val said = harness.said().joinToString("\n")
            assertTrue(textOf(Strings.Search.emptyPool) !in said, "an empty pool was blamed on the filter")
        }
    }

    @Test
    fun `a long name and a large text scale do not push the panel off the screen`() {
        val controller =
            controllerWith(
                listOf(task("Kırmızı çatılı iki katlı köşe evinin arka bahçe zemin parçası 👨‍👩‍👧‍👦")),
                catalogue = manyColors(),
            )
        controller.openFilters()

        scene(controller, width = 720, height = 880) { harness ->
            val close = assertNotNull(harness.boundsOf(textOf(Strings.Search.closeFilters)))
            assertTrue(close.right <= 720f && close.left >= 0f, "the panel was pushed off the screen at $close")
        }
    }

    private fun manyColors(): List<PoolColor> =
        (0..<24).map { at ->
            PoolColor(IdGenerator.Random.newId(), "Ton $at", "#%06X".format(at * 100000), sortOrder = at)
        }

    private companion object {
        const val SETTLING_FRAMES = 6
    }
}
