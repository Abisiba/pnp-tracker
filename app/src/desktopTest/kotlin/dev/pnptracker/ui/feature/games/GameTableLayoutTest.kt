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
    fun `a cell holding a task offers no editor and says why`() {
        // Flattening it would cost the task its colours, its stages and its
        // history, so the cell refuses the click rather than doing the damage.
        // The whole gesture is disabled, not merely ignored when pressed.
        val cellSlot = source.substringAfter("private fun CellSlot(").substringBefore("private fun CellEditorSlot(")
        assertTrue("val editable = cell.editableText != null" in cellSlot, "a cell does not ask whether it may be edited")
        assertTrue("enabled = editable" in cellSlot, "a cell holding a task can still be double clicked")
        assertTrue("if (!editable)" in cellSlot, "a cell holding a task does not say so")
        assertTrue("Strings.Cell.lockedByTasks" in cellSlot, "the reason is not taken from the text catalogue")
        // The editor itself is a separate composable; a read only cell has none.
        assertTrue("TextField" !in cellSlot, "the read only cell carries a text field")
    }

    // -------------------------------------------------- writing in a cell

    @Test
    fun `a cell is opened by double click and from the keyboard`() {
        // PLAN 17 asks for every main action to be reachable by keyboard, and a
        // dense table wants the single click to do nothing but take the focus,
        // so passing over a cell never puts an editor into it.
        assertTrue(source.contains("combinedClickable("), "a cell cannot be double clicked")
        assertTrue(source.contains("onDoubleClick = onEdit"), "the double click does not open the editor")
        assertTrue(source.contains("onClick = {}"), "a single click does more than take the focus")
        assertTrue(source.contains("Key.F2"), "F2 does not open the editor")
        assertTrue(source.contains(".focusable()"), "a cell cannot take the keyboard at all")
    }

    @Test
    fun `the editor saves with Ctrl and Enter and gives up with Escape`() {
        val editor = source.substringAfter("private fun CellEditorSlot(").substringBefore("private fun messageOf(")
        assertTrue("event.isCtrlPressed" in editor, "Ctrl+Enter does not save")
        assertTrue("Key.Escape" in editor, "Escape does not give up")
        assertTrue("controller.cancelEditing()" in editor, "giving up does nothing")
        // A plain Enter has to fall through to the field so it makes a line.
        assertTrue("singleLine = false" in editor, "the editor is one line, so Enter cannot break a line")
        assertTrue("minLines" in editor, "the editor opens too small to write a note in")
    }

    @Test
    fun `the editor takes the keyboard when it opens and names its two actions`() {
        val editor = source.substringAfter("private fun CellEditorSlot(").substringBefore("private fun messageOf(")
        assertTrue("focus.requestFocus()" in editor, "the editor does not take the keyboard when it opens")
        assertTrue("contentDescription = saveLabel" in editor, "the save action has no accessible name")
        assertTrue("contentDescription = discardLabel" in editor, "the discard action has no accessible name")
    }

    @Test
    fun `the editor is drawn in the cell and not over the window`() {
        // PLAN 12.5 keeps editing where the content is; a panel over the middle
        // of the window would hide the very row being worked on.
        val editor = source.substringAfter("private fun CellEditorSlot(").substringBefore("private fun messageOf(")
        assertTrue("width(CellColumnWidth)" in editor, "the editor is not the width of its own column")
        listOf("Dialog", "AlertDialog", "Popup", "ModalBottomSheet").forEach { overlay ->
            assertTrue(overlay !in source, "the table opens a $overlay over the window")
        }
    }

    @Test
    fun `nothing parses what is typed or pasted`() {
        // Turning lines into tasks is a later step and an action the user asks
        // for. A split here would quietly make decisions about their words.
        val editor = source.substringAfter("private fun CellEditorSlot(").substringBefore("private fun messageOf(")
        listOf(".split(", ".lines()", ".trim(", ".replace(").forEach { transform ->
            assertTrue(transform !in editor, "the editor performs $transform on what the user typed")
        }
    }

    @Test
    fun `the table joins a cell's pieces with nothing at all`() {
        // The pieces carry their own spacing. Anything between them would be a
        // character the user never typed, shown in the table and read out by a
        // screen reader as though it were theirs.
        assertTrue(
            "SEGMENT_SEPARATOR" !in source,
            "the table still puts something between a cell's pieces",
        )
        assertTrue("cell.text" in source, "the cell's text is not read from the model that concatenates it")
    }

    @Test
    fun `a finished row uses the stated green rather than a Material role`() {
        assertTrue(
            "PnpStatus.colors.completedContainer" in source,
            "the finished row is painted with something other than the stated status colour",
        )
        assertTrue(
            "tertiaryContainer" !in source,
            "the finished row still borrows a Material role whose name only sounds green",
        )
    }

    @Test
    fun `the table stays dense rather than turning into cards`() {
        // A cell is a cell in a grid, not a card with a shadow. The padding is
        // small and the border is a line.
        listOf("Card(", "elevation", "shadow").forEach { cardlike ->
            assertTrue(cardlike !in source, "a cell is drawn as a $cardlike")
        }
        assertTrue("cellBorder(" in source, "cells have no readable boundary")
        val cellPadding =
            Regex("""padding\(horizontal = (\d+)\.dp, vertical = (\d+)\.dp\)""")
                .findAll(source)
                .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                .toList()
        assertTrue(cellPadding.isNotEmpty(), "no cell padding is stated at all")
        assertTrue(
            cellPadding.all { (horizontal, vertical) -> horizontal <= 12 && vertical <= 10 },
            "a cell is padded like a card rather than a table cell: $cellPadding",
        )
    }
}
