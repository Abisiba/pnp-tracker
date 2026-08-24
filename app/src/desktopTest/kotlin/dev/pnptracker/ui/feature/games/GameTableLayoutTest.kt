package dev.pnptracker.ui.feature.games

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The desktop behaviour of the table that no unit can observe from outside it.
 *
 * Everything about *what* the table shows is settled by
 * [GameTableControllerTest] and by the store's tests against a real database.
 * What is left is how it is laid out, and no Compose test dependency may be
 * added to look at that, so these read the screen's own source. They are pinned
 * to the properties that would break the window rather than to how it is
 * written: a fixed row height, a table that cannot be scrolled, a cell with no
 * limit on how far it grows.
 */
class GameTableLayoutTest {
    private val source: String =
        Path
            .of("src/commonMain/kotlin/dev/pnptracker/ui/feature/games/GameTableScreen.kt")
            .let { Files.readString(it) }

    @Test
    fun `the table scrolls both ways`() {
        // Six columns will not fit a narrow window, and a library does not fit a
        // tall one. Losing either would leave part of the table unreachable.
        assertTrue(source.contains("horizontalScroll("), "the columns cannot be reached in a narrow window")
        assertTrue(source.contains("LazyColumn("), "the rows do not scroll")
    }

    @Test
    fun `the header scrolls with the columns it names`() {
        // The header is inside the same horizontally scrolled column as the rows,
        // so a column and its name can never come apart.
        val scrolled = source.substringAfter(".horizontalScroll(horizontal)")
        assertTrue(scrolled.contains("TableHeader()"), "the header does not scroll with the table")
        assertTrue(scrolled.contains("LazyColumn("), "the rows do not scroll with the header")
    }

    @Test
    fun `the game name column has a readable width of its own`() {
        assertTrue(
            Regex("""private val GameColumnWidth = (\d+)\.dp""").find(source)!!.groupValues[1].toInt() >= 200,
            "the game name column is too narrow to read a name in",
        )
        assertTrue(
            Regex("""private val CellColumnWidth = (\d+)\.dp""").find(source)!!.groupValues[1].toInt() > 0,
            "a cell column has no width",
        )
    }

    @Test
    fun `the table is as wide as its columns and no wider`() {
        // A width worked out from the columns cannot drift from them, and cannot
        // go negative when the window is made small: it does not depend on the
        // window at all.
        assertTrue(
            source.contains("private val TableWidth = GameColumnWidth + CellColumnWidth * CellColumnType.entries.size"),
            "the table's width is not derived from the columns it holds",
        )
    }

    @Test
    fun `nothing in a row is given a fixed height`() {
        // PLAN 17 asks for text scaling to work. A row told exactly how tall to
        // be would clip its own text at 2.0x; a minimum lets it grow instead.
        val fixedHeights = Regex("""\.height\((?!In)""").findAll(source).count()
        assertTrue(fixedHeights == 0, "a fixed height would clip its text when the user scales it up")
        assertTrue(source.contains("heightIn(min ="), "rows have no minimum height to keep them tappable")
    }

    @Test
    fun `long cell content is cut rather than allowed to grow without end`() {
        assertTrue(source.contains("maxLines = CELL_PREVIEW_LINES"), "a cell can grow to any height")
        assertTrue(
            source.contains("overflow = TextOverflow.Ellipsis"),
            "nothing tells the user a cell has more in it than is shown",
        )
    }

    @Test
    fun `the view selector and adding a game are reachable by keyboard`() {
        // PLAN 17: every main action reachable from the keyboard, with a focus
        // ring that can be seen.
        assertTrue(source.contains("focusOutline("), "focus is invisible")
        assertTrue(source.contains("Key.Enter"), "a name cannot be saved from the keyboard")
        assertTrue(source.contains("Key.Escape"), "a half typed name cannot be abandoned from the keyboard")
        assertTrue(source.contains("focusRequester("), "the name box does not take focus when it opens")
    }

    @Test
    fun `the selected view is not told apart by colour alone`() {
        // The chip carries a state description in words beside the fill.
        assertTrue(source.contains("stateDescription = stateText"), "selection is carried by colour alone")
        assertTrue(source.contains("selectableGroup()"), "the three views are not read as one choice")
    }

    @Test
    fun `a cell says what column it is and what is in it`() {
        assertTrue(source.contains("Strings.Table.cellDescription"), "a cell has no accessible description")
        assertTrue(
            source.contains("Strings.Table.cellEmptyDescription"),
            "an empty cell does not say which column it is",
        )
    }

    @Test
    fun `nothing in a cell offers an editor that is not there yet`() {
        // Writing in a cell is the next step. A cell that looked clickable now
        // would promise something nothing behind it could do.
        val cellSlot = source.substringAfter("private fun CellSlot(").substringBefore("private fun EmptyTable(")
        listOf("clickable", "onClick", "Button", "TextField").forEach { interactive ->
            assertTrue(interactive !in cellSlot, "a read only cell offers $interactive")
        }
    }
}
