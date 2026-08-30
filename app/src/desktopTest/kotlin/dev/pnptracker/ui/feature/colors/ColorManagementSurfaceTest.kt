package dev.pnptracker.ui.feature.colors

import dev.pnptracker.domain.colors.baseColors
import dev.pnptracker.ui.Strings
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
 * The three surfaces this step adds: changing a colour, losing one, and putting
 * the missing ones back.
 *
 * Half of this reads what they say, through the real Turkish catalogue, because
 * a confirmation about something irreversible is mostly words. The other half
 * reads the source, pinned to what PLAN either requires or forbids, because no
 * Compose test dependency may be added to look at a screen from outside.
 */
class ColorManagementSurfaceTest {
    private fun textOf(
        resource: StringResource,
        vararg arguments: Any,
    ): String = runBlocking { getString(resource, *arguments) }

    private fun read(path: String): String = Files.readString(Path.of(path))

    private val screen = read("src/commonMain/kotlin/dev/pnptracker/ui/feature/colors/ColorCatalogueScreen.kt")
    private val controller = read("src/commonMain/kotlin/dev/pnptracker/ui/feature/colors/ColorCatalogueController.kt")
    private val state = read("src/commonMain/kotlin/dev/pnptracker/ui/feature/colors/ColorCatalogueState.kt")
    private val gameScreen = read("src/commonMain/kotlin/dev/pnptracker/ui/feature/games/GameTableScreen.kt")

    // --------------------------------------------------------------- the words

    @Test
    fun `every new sentence is really in the catalogue and reads as Turkish`() {
        val everything =
            listOf(
                Strings.Colors.editShort,
                Strings.Colors.editTitle,
                Strings.Colors.deleteShort,
                Strings.Colors.deleteUnused,
                Strings.Colors.deleteExamples,
                Strings.Colors.deleteIrreversible,
                Strings.Colors.deleteConfirm,
                Strings.Colors.deleting,
                Strings.Colors.restoreAction,
                Strings.Colors.restoreTitle,
                Strings.Colors.restoreExplains,
                Strings.Colors.restoreMissing,
                Strings.Colors.restoreBlocked,
                Strings.Colors.restoreBlockedNote,
                Strings.Colors.restoreConfirm,
                Strings.Colors.restoring,
                Strings.Colors.restoreNothingMissing,
                Strings.Colors.noticeDismiss,
                Strings.Colors.errorChanged,
            ).map(::textOf)

        everything.forEach { assertTrue(it.isNotBlank(), "a sentence resolved to nothing") }
        assertEquals(everything.size, everything.toSet().size, "two of them read the same: $everything")
    }

    @Test
    fun `the two confirmations do not read like one another`() {
        val deleting = textOf(Strings.Colors.deleteTitle, "Gri")
        val restoring = textOf(Strings.Colors.restoreTitle)

        assertTrue(deleting.contains("Gri"), "the question did not name the colour")
        assertTrue(deleting != restoring)
        assertTrue(
            textOf(Strings.Colors.deleteConfirm) != textOf(Strings.Colors.restoreConfirm),
            "the two buttons the user has to tell apart read the same",
        )
    }

    @Test
    fun `losing a colour is said to be something that cannot be taken back`() {
        val said = textOf(Strings.Colors.deleteIrreversible)

        assertTrue(said.contains("geri alınamaz"), "the user is not told this cannot be undone: $said")
        // And what survives is named, because that is the other half of the fear.
        listOf("görev", "oyun").forEach {
            assertTrue(said.contains(it, ignoreCase = true), "the sentence does not say $it are kept: $said")
        }
    }

    @Test
    fun `the numbers a confirmation rests on are all written out`() {
        val said = textOf(Strings.Colors.deleteUsed, "3", "2", "1")

        listOf("3", "2", "1").forEach {
            assertTrue(said.contains(it), "the count $it was left out of the sentence: $said")
        }
    }

    @Test
    fun `the restore says it only brings back what is missing`() {
        val said = textOf(Strings.Colors.restoreExplains)

        assertTrue(said.contains("Yalnız"), "the sentence does not say only: $said")
        assertTrue(said.contains("değişmez"), "the sentence does not promise nothing else changes: $said")
        assertTrue(
            !said.contains("sıfırla", ignoreCase = true),
            "the sentence reads as resetting every colour: $said",
        )
    }

    @Test
    fun `every reason a base colour cannot come back reads differently`() {
        val reasons =
            listOf(
                Strings.Colors.restoreBlockName,
                Strings.Colors.restoreBlockAlias,
                Strings.Colors.restoreBlockRace,
            ).map { textOf(it, "Beyaz", "#FFFFFF") }

        assertEquals(reasons.size, reasons.toSet().size, "two reasons read the same: $reasons")
        reasons.forEach {
            assertTrue(it.contains("Beyaz") && it.contains("#FFFFFF"), "a reason did not name the colour: $it")
        }
        assertTrue(
            reasons.none { it.contains("alias", ignoreCase = true) },
            "the user was shown the word alias: $reasons",
        )
    }

    @Test
    fun `an action names the colour it would act on, for a reader who cannot see the row`() {
        assertTrue(textOf(Strings.Colors.editAction, "Gri").contains("Gri"))
        assertTrue(textOf(Strings.Colors.deleteAction, "Gri").contains("Gri"))
        assertTrue(
            textOf(Strings.Colors.editAction, "Gri") != textOf(Strings.Colors.deleteAction, "Gri"),
            "the two actions of a row are described the same way",
        )
    }

    @Test
    fun `a swatch is described by its name and its value together`() {
        val said = textOf(Strings.Colors.swatchOf, "Gri", "#808080")

        assertTrue(said.contains("Gri"))
        assertTrue(said.contains("#808080"), "the value a colour is is not read out: $said")
    }

    @Test
    fun `a task that has lost a colour is told so in words`() {
        val said = textOf(Strings.CellTask.colorGone)

        assertTrue(said.isNotBlank())
        assertTrue(said.contains("silindi"), "the row does not say the colour went: $said")
    }

    // ---------------------------------------------------------- what is drawn

    @Test
    fun `the swatch border can be seen on every colour there is`() {
        // White on white and black on black are exactly where a fixed border
        // disappears, and PLAN 17 will not have a colour be the only thing
        // carrying a meaning — which it is not, but the square still has to be a
        // square.
        (baseColors.map { it.hex } + listOf("#FFFFFF", "#000000", "#FEFEFE", "#010101")).forEach { hex ->
            val fill = opaqueColorOf(hex)
            assertTrue(
                contrastRatio(visibleEdgeOn(fill), fill) >= 4.5,
                "the edge of a $hex square cannot be made out",
            )
        }
    }

    @Test
    fun `both actions are in the row itself and not behind anything`() {
        val row = screen.substringAfter("private fun ColorRow(").substringBefore("private fun Swatch(")

        assertTrue("Strings.Colors.editShort" in row, "the row does not offer changing the colour")
        assertTrue("Strings.Colors.deleteShort" in row, "the row does not offer removing the colour")
        assertTrue("DropdownMenu" !in row, "the actions were put behind a menu")
        assertTrue("FlowRow(" in row, "a narrow row would push the actions off the side")
    }

    @Test
    fun `the form is the picker from the step before and not a second one`() {
        assertTrue("ColorPicker(" in screen, "the shared picker is not used")
        assertEquals(
            1,
            Regex("""ColorPicker\(""").findAll(screen).count(),
            "the screen builds the picker more than once",
        )
        assertTrue(
            "OutlinedTextField(" in screen,
            "there is no field to type the name in",
        )
        assertEquals(
            1,
            Regex("""OutlinedTextField\(""").findAll(screen).count(),
            "a second text field appeared; PLAN 5.7 allows the name and nothing else",
        )
    }

    @Test
    fun `nothing here offers a standing hex field`() {
        listOf(screen, controller, state).forEach { source ->
            assertTrue(
                "hexLabel" !in source && "hexHint" !in source && "hexInvalid" !in source,
                "a field for typing a colour as text is still offered",
            )
        }
    }

    @Test
    fun `the section holds one open surface rather than a flag for each`() {
        assertTrue("sealed interface ColorWork" in state, "the open work is not one thing")
        listOf("isEditing", "isDeleting", "isRestoring", "showDelete", "showRestore").forEach {
            assertTrue(it !in state && it !in controller, "an overlapping flag came back: $it")
        }
        assertTrue("val work: ColorWork?" in state, "the screen state does not carry one open surface")
    }

    @Test
    fun `every open surface answers Escape and Ctrl+Enter from inside itself`() {
        val card = screen.substringAfter("private fun SurfaceCard(").substringBefore("private fun NoteLine(")

        assertTrue("Key.Escape" in card, "Escape is not caught")
        assertTrue("isCtrlPressed" in card, "Ctrl+Enter is not caught")
        assertEquals(
            3,
            Regex("""SurfaceCard\(""").findAll(screen).count() - 1,
            "not every surface sits in the card that answers the keyboard",
        )
    }

    @Test
    fun `the catalogue is capped rather than left to fill a whole monitor`() {
        assertTrue("MAX_CONTENT_WIDTH" in screen)
        assertTrue(
            "fillMaxHeight().widthIn(max = MAX_CONTENT_WIDTH)" in screen,
            "filling the size before the cap fixes the width and the cap can never bring it down",
        )
        assertTrue(
            "fillMaxSize().widthIn" !in screen,
            "the order that made the form span a whole monitor came back",
        )
    }

    @Test
    fun `a draft naming a colour that is gone is refused rather than quietly mended`() {
        assertTrue("stillHasEvery(" in gameScreen, "the panels do not check the colours they name")
        assertTrue("Strings.CellTask.colorGone" in gameScreen, "nothing says why the save is refused")
        // Nothing removes the choice on the user's behalf.
        assertTrue(
            "strandedColorIds" in read("src/commonMain/kotlin/dev/pnptracker/ui/feature/games/GameTableController.kt"),
            "the controller does not refuse a draft naming a colour that is gone",
        )
    }

    @Test
    fun `nothing in the colour section reaches a task, a stage or an event`() {
        listOf(screen, controller, state).forEach { source ->
            listOf("taskDao", "TaskStage", "ProgressEvent", "taskEditing", "taskCreation").forEach { forbidden ->
                assertTrue(forbidden !in source, "the colour section reached $forbidden")
            }
        }
    }

    @Test
    fun `a restore nothing can be done with is still reachable by the keyboard`() {
        val card = screen.substringAfter("private fun RestoreCard(").substringBefore("private fun reasonOf(")

        // A disabled button cannot hold the focus, so asking the confirm button
        // to hold it while it is disabled left the focus outside the card — and
        // then Escape reached the list behind rather than the card in front, with
        // every row disabled and no way out but the mouse.
        assertTrue("dismiss" in card, "there is no second place for the keyboard to go")
        assertTrue(
            "if (canGoAhead) confirm.requestFocus() else dismiss.requestFocus()" in card,
            "the keyboard is handed to a button that may be disabled",
        )
        assertTrue(
            "focusRequester(dismiss)" in card,
            "the way out of a blocked restore cannot take the focus",
        )
    }

    @Test
    fun `the two actions sit at the edge of a row rather than after the name`() {
        val row = screen.substringAfter("private fun ColorRow(").substringBefore("private fun Swatch(")

        assertTrue(
            "Modifier.weight(1f)" in row,
            "the actions trail the name, so no two rows line them up in the same place",
        )
    }
}
