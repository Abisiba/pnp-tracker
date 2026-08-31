package dev.pnptracker.ui.feature.pools

import dev.pnptracker.domain.colors.baseColors
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.poolDescriptionOf
import dev.pnptracker.ui.poolNavigationNameOf
import dev.pnptracker.ui.stageNameOf
import dev.pnptracker.ui.theme.contrastRatio
import dev.pnptracker.ui.theme.opaqueColorOf
import dev.pnptracker.ui.theme.visibleEdgeOn
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a pool screen says and how it is put together.
 *
 * Half of this reads the real Turkish catalogue, because a pool is mostly words:
 * a heading, a count, the stage a piece of work has reached. The other half
 * reads the screen's own source, pinned to the properties that would break the
 * window — no Compose test dependency may be added to look at a screen from
 * outside, so the checks are on the shape of what is drawn rather than on pixels.
 */
class PoolScreenLayoutTest {
    private fun textOf(
        resource: StringResource,
        vararg arguments: Any,
    ): String = runBlocking { getString(resource, *arguments) }

    private fun read(path: String): String = Files.readString(Path.of(path))

    private val screen = read("src/commonMain/kotlin/dev/pnptracker/ui/feature/pools/PoolScreen.kt")
    private val controller = read("src/commonMain/kotlin/dev/pnptracker/ui/feature/pools/PoolController.kt")
    private val state = read("src/commonMain/kotlin/dev/pnptracker/ui/feature/pools/PoolScreenState.kt")
    private val scaffold = read("src/commonMain/kotlin/dev/pnptracker/ui/navigation/AppScaffold.kt")

    // --------------------------------------------------------------- the words

    @Test
    fun `every pool has a name and a sentence of its own`() {
        val names = PoolType.entries.map { textOf(poolNavigationNameOf(it)) }
        val sentences = PoolType.entries.map { textOf(poolDescriptionOf(it)) }

        assertEquals(names.size, names.toSet().size, "two pools are called the same thing: $names")
        assertEquals(sentences.size, sentences.toSet().size, "two pools are described the same way")
        (names + sentences).forEach { assertTrue(it.isNotBlank(), "a pool has nothing to say for itself") }
    }

    @Test
    fun `the sidebar names the card pool in the plural the plan uses`() {
        // PLAN 12.1 lists the section as `Kartlar` while the table column beside
        // it is `Kart`: one is a place, the other is a kind of work.
        assertEquals("Kartlar", textOf(Strings.Pool.navCard))
        assertEquals("3D Baskı", textOf(Strings.Pool.navThreeD))
        assertEquals("Mukavva", textOf(Strings.Pool.navBoard))
        assertEquals("Özel", textOf(Strings.Pool.navSpecial))
    }

    @Test
    fun `the three sections of the 3D pool read differently from one another`() {
        val sections =
            listOf(
                Strings.Pool.sectionAwaitingColor,
                Strings.Pool.sectionSingleColor,
                Strings.Pool.sectionMulticolor,
            ).map(::textOf)

        assertEquals("Renk seçilecek", sections.first(), "PLAN 5.10 names this section in so many words")
        assertEquals(sections.size, sections.toSet().size, "two sections read the same: $sections")
    }

    @Test
    fun `every stage has a Turkish name and no two read alike`() {
        val names = ProductionStage.entries.map { textOf(stageNameOf(it)) }

        assertEquals(names.size, names.toSet().size, "two stages read the same: $names")
        // PLAN 7 and 8 write the pipelines as states rather than as acts.
        assertEquals("Basıldı", textOf(stageNameOf(ProductionStage.PRINT)))
        assertEquals("Kesildi", textOf(stageNameOf(ProductionStage.CUT)))
    }

    @Test
    fun `an empty pool says so rather than showing nothing`() {
        val said = textOf(Strings.Pool.empty)

        assertTrue(said.isNotBlank())
        assertTrue(said != textOf(Strings.Pool.loading), "loading and empty read the same")
        assertTrue(said != textOf(Strings.Pool.error), "empty and broken read the same")
    }

    @Test
    fun `the numbers a group rests on are written out`() {
        assertTrue(textOf(Strings.Pool.groupSummary, "3", "42").contains("3"))
        assertTrue(textOf(Strings.Pool.groupSummary, "3", "42").contains("42"))
        assertTrue(textOf(Strings.Pool.groupMissing, "5").contains("5"))
        assertTrue(textOf(Strings.Pool.failures, "7").contains("7"))
    }

    @Test
    fun `the two kinds of special work are told apart in words`() {
        val checklist = textOf(Strings.Pool.checklist)
        val counted = textOf(Strings.Pool.counted)

        assertTrue(checklist != counted, "a checklist and a counted task read the same")
        assertTrue(checklist.isNotBlank() && counted.isNotBlank())
    }

    @Test
    fun `the stage badge names a stage or says there is none left`() {
        val next = textOf(Strings.Pool.stageBadge, textOf(stageNameOf(ProductionStage.LAMINATE)))

        assertTrue(next.contains("Lamine edildi"))
        assertTrue(textOf(Strings.Pool.stageDone) == "Tamamlandı")
        assertTrue(next != textOf(Strings.Pool.stageDone))
    }

    @Test
    fun `opening and folding the stage details are described differently`() {
        val open = textOf(Strings.Pool.stageDetailsOpen, "Yarasa")
        val close = textOf(Strings.Pool.stageDetailsClose, "Yarasa")

        assertTrue(open != close, "the control says the same thing whichever way it will go")
        listOf(open, close).forEach { assertTrue(it.contains("Yarasa"), "the control does not say which task: $it") }
    }

    @Test
    fun `a card is described by its task, its game and its total together`() {
        val said = textOf(Strings.Pool.spokenTask, "Yarasa", "Harmonies", "×10")

        listOf("Yarasa", "Harmonies", "×10").forEach {
            assertTrue(said.contains(it), "$it was left out of what a reader hears: $said")
        }
    }

    @Test
    fun `a multi colour card reads out every colour it is made in`() {
        val said = textOf(Strings.Pool.colors, "Kırmızı, Sarı, Siyah")

        listOf("Kırmızı", "Sarı", "Siyah").forEach { assertTrue(said.contains(it)) }
        assertTrue(
            textOf(Strings.Pool.currentColor, "Sarı") != said,
            "which group is being read is not said apart from the list",
        )
    }

    @Test
    fun `the sidebar says how much work a pool is holding`() {
        val said = textOf(Strings.Pool.navActiveCount, "0")

        assertTrue(said.contains("0"), "the count is not in the sentence: $said")
        assertTrue(said.isNotBlank())
    }

    @Test
    fun `the count a pool is holding is written on the entry and not only spoken`() {
        // PLAN 9 and its ninth scenario: once the last special task is done the
        // pool stays in the sidebar and shows `0 aktif`. A number only a screen
        // reader can reach would leave that promise unkept for everyone else.
        assertEquals("0 aktif", textOf(Strings.Pool.navActiveBadge, "0"))

        assertTrue(
            "badge =" in scaffold && "Strings.Pool.navActiveBadge" in scaffold,
            "the entry draws no count of its own",
        )
        // Drawn but not spoken: the entry already carries the count in its state,
        // and a badge with semantics of its own would say it a second time.
        val badge = scaffold.substringAfter("badge =").substringBefore("icon =")
        assertTrue("clearAndSetSemantics" in badge, "the badge is read out as well as the entry's state")
        assertTrue("navActiveCount" in scaffold, "the spoken count was dropped")
    }

    @Test
    fun `only a pool carries a count`() {
        // The count comes from the pool summary, so the sections that are not
        // pools are given none rather than a nought that would mean nothing.
        assertTrue(
            "(screen as? Screen.Pool)?.let { summary.activeCountOf(it.poolType) }" in scaffold,
            "the count is no longer taken from the pool alone",
        )
    }

    // ---------------------------------------------------------- what is drawn

    @Test
    fun `a pool reads down the page and never sideways`() {
        assertTrue("LazyColumn(" in screen, "the rows do not scroll")
        assertTrue(
            "horizontalScroll" !in screen,
            "a pool was made to scroll sideways; a narrow window would hide part of it",
        )
        assertTrue("fillMaxHeight().widthIn(max = MaxContentWidth)" in screen, "the content is not capped or not tall")
    }

    @Test
    fun `the colour chips wrap rather than running off a narrow card`() {
        val chips = screen.substringAfter("private fun ColorChips(").substringBefore("private fun TaskFacts(")

        assertTrue("FlowRow(" in chips, "the chips are laid out in one line however narrow the card is")
        assertTrue("clickable" !in chips, "a colour chip can be pressed, so a card has several targets")
        assertTrue("focusRequester" !in chips, "a colour chip takes the keyboard away from its card")
    }

    @Test
    fun `every colour of a task is drawn and the group's own one is marked`() {
        val chips = screen.substringAfter("private fun ColorChips(").substringBefore("private fun TaskFacts(")

        assertTrue("task.colors.forEach" in chips, "only some of a task's colours are drawn")
        assertTrue("color.canonicalName" in chips, "a chip is a square with no name on it")
        assertTrue("isCurrent" in chips, "the group's own colour is not marked")
        assertTrue("FontWeight.Bold" in chips, "which colour this group is rests on a border alone")
    }

    @Test
    fun `a card is one thing to press and one thing to hear`() {
        val card = screen.substringAfter("private fun TaskCard(").substringBefore("private fun ColorChips(")

        assertTrue("mergeDescendants = true" in card, "a card is read out in pieces")
        assertEquals(
            1,
            Regex("""\.clickable\(""").findAll(card).count(),
            "a card has more than one place that opens the task",
        )
        assertTrue("focusOutline(CardShape)" in card, "there is no visible focus on a card")
    }

    @Test
    fun `the stage details are a control of their own with a name`() {
        val badge = screen.substringAfter("private fun StageBadge(").substringBefore("/**\n * Everything a reader")

        assertTrue("contentDescription = toggle" in badge, "the disclosure has no accessible name")
        assertTrue("controller.toggleStageDetails" in badge)
        // Reading only in this step: nothing here writes a counter.
        listOf("completePrimaryBatch", "reportFailure", "Checkbox", "onValueChange").forEach {
            assertTrue(it !in badge, "the badge offers $it, which belongs to a later step")
        }
    }

    @Test
    fun `a heading is announced as one`() {
        assertTrue("heading()" in screen, "nothing on the screen is announced as a heading")
        // Three: the pool's own title, the section titles, and the head of a
        // colour group. The section headings draw the section title, so there is
        // one place saying it and not two.
        assertEquals(
            3,
            Regex("""heading\(\)""").findAll(screen).count(),
            "the pool title, the sections and the colour groups are not all headings",
        )
    }

    @Test
    fun `the swatch of a colour group is never the only thing naming it`() {
        val heading = screen.substringAfter("private fun ColorGroupHeading(").substringBefore("/** A square of one")

        assertTrue("group.color.canonicalName" in heading, "a group is headed by a square with no name")
    }

    @Test
    fun `a swatch has an edge that can be seen on any colour there is`() {
        (baseColors.map { it.hex } + listOf("#FFFFFF", "#000000", "#FEFEFE", "#010101")).forEach { hex ->
            val fill = opaqueColorOf(hex)
            assertTrue(
                contrastRatio(visibleEdgeOn(fill), fill) >= 4.5,
                "the edge of a $hex square cannot be made out",
            )
        }
    }

    @Test
    fun `the popover answers Escape and Ctrl and Enter from inside itself`() {
        val popover = screen.substringAfter("private fun TaskPopover(").substringBefore("/**\n * What can be done")

        assertTrue("Key.Escape" in popover, "Escape is not caught")
        assertTrue("isCtrlPressed" in popover, "Ctrl+Enter is not caught")
        assertTrue("controller.closeInnermost()" in popover, "Escape does not close one layer")
    }

    @Test
    fun `the popover is hung off the card rather than dropped on top of it`() {
        val popover = screen.substringAfter("private fun TaskPopover(").substringBefore("/**\n * What can be done")

        // Without a position provider the popup opened over the very card it was
        // about, hiding the task the user had just pressed.
        assertTrue("popupPositionProvider = provider" in popover, "the popover lands wherever it likes")
        assertTrue("AnchoredAboveWord(POPOVER_GAP)" in popover, "the popover is not placed beside its card")
    }

    @Test
    fun `the popover offers only what the application already does to a task`() {
        val menu = screen.substringAfter("private fun TaskMenuActions(").substringBefore("/** Asking whether a task")

        assertTrue("Strings.TaskMenu.edit" in menu)
        assertTrue("Strings.TaskMenu.convertToText" in menu)
        // Everything below belongs to a later step and would be a dead control.
        listOf("Checkbox", "complete", "shortage", "Eksik").forEach {
            assertTrue(it !in menu, "the menu offers $it, which this step does not do")
        }
    }

    @Test
    fun `the pool uses the one shared task editor rather than a second one`() {
        assertTrue("TaskEditPanel(" in screen, "the pool draws its own edit form")
        assertTrue(
            "import dev.pnptracker.ui.feature.tasks.TaskEditPanel" in screen,
            "the form is not the shared one",
        )
        assertTrue("OutlinedTextField(" !in screen, "the pool grew a field of its own beside the shared form")
    }

    @Test
    fun `the pool writes through the shared editing transaction and nothing else`() {
        assertTrue("taskEditing.editTask(" in controller, "the pool does not use the shared edit")
        assertTrue("taskEditing.convertTaskToText(" in controller)
        // Nothing that would make a record of its own.
        listOf("taskDao", "insert(", "addTaskToCell", "poolDao.insert").forEach {
            assertTrue(it !in controller, "the pool reaches $it and could write a record of its own")
        }
    }

    @Test
    fun `the pool keeps one open surface rather than a flag for each`() {
        assertTrue("sealed interface PoolWork" in state, "the open work is not one thing")
        listOf("isEditing", "isConfirming", "showMenu", "showEditor").forEach {
            assertTrue(it !in state && it !in controller, "an overlapping flag appeared: $it")
        }
    }

    @Test
    fun `a card is keyed by the colour it is drawn under as well as by its task`() {
        // The same task really is on several cards. Keyed by the task alone, a
        // lazy list would be told two rows have one key.
        assertTrue("data class PoolCardKey" in state)
        assertTrue("group.color.colorId + group.tasks[at].taskId" in screen, "two cards of one task share a key")
    }

    // --------------------------------------------------------- the sidebar

    @Test
    fun `the sidebar offers a pool only while it is meant to be there`() {
        assertTrue("Screen.offered(summary)" in scaffold, "the sidebar lists every pool whatever the state")
        assertTrue(
            "navigation.navigateTo(Screen.threeDPool)" in scaffold,
            "standing on a pool that stops being offered leaves the window on a section nobody can leave",
        )
    }

    @Test
    fun `moving between pools starts one and stops the other`() {
        assertTrue(
            "key(screen.poolType) { PoolScreen(" in scaffold,
            "the pools share one composition, so leaving one does not stop its reads",
        )
    }

    @Test
    fun `the sidebar reads the pools once rather than opening them`() {
        assertTrue("observeNavigationSummary()" in scaffold, "the sidebar has no summary to read")
        assertTrue(
            "poolControllers.of(" in scaffold,
            "the sidebar builds a pool controller rather than being handed one",
        )
    }
}
