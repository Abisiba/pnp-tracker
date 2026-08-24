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
        val drawn = source.substringAfter("private fun cellContentOf(").substringBefore("private fun spokenContentOf(")
        assertTrue("append(segment.text)" in drawn, "a piece is not drawn as the text it carries")
        val spoken = source.substringAfter("private fun spokenContentOf(").substringBefore("private fun spokenTaskOf(")
        assertTrue("""joinToString(separator = "")""" in spoken, "the spoken cell puts something between its pieces")
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

    // ------------------------------------------- a task standing in a cell

    @Test
    fun `a task is painted in its own colour with ink chosen against it`() {
        // The colour is the user's, picked for filament rather than for reading
        // text on. PLAN 17 asks for text that can still be read, so the ink is
        // worked out from the ground instead of being fixed.
        val drawn = source.substringAfter("private fun cellContentOf(").substringBefore("private fun spokenContentOf(")
        assertTrue("opaqueColorOf(it.hex)" in drawn, "a task's colour is not taken from its colour record")
        assertTrue("readableInkOn" in drawn, "the ink on a task is not chosen against the colour behind it")
        assertTrue("background = ground" in drawn, "a task is not painted at all")
        // No value copied into the screen: whatever a colour is, it is its own.
        assertTrue(
            Regex("""["']#[0-9A-Fa-f]{6}["']""").find(source) == null,
            "a colour value is written into the screen instead of read from its record",
        )
    }

    @Test
    fun `the count beside a task comes from the task and not from anybody's text`() {
        val drawn = source.substringAfter("private fun cellContentOf(").substringBefore("private fun spokenContentOf(")
        assertTrue("segment.requiredQuantity" in drawn, "the count is not read from the task")
        assertTrue("Strings.CellTask.quantityMark" in drawn, "the count mark is not taken from the text catalogue")
        // The mark is drawn around the document and never inside it.
        assertTrue("QUANTITY_GAP" in drawn, "the count is drawn up against the word before it")
    }

    @Test
    fun `a finished task is struck through where it stands`() {
        // PLAN 5.6 leaves a finished task in its cell rather than removing it.
        val drawn = source.substringAfter("private fun cellContentOf(").substringBefore("private fun spokenContentOf(")
        assertTrue("segment.isCompletedTask" in drawn, "a finished task looks exactly like an unfinished one")
        assertTrue("TextDecoration.LineThrough" in drawn, "a finished task is not struck through")
    }

    @Test
    fun `a task is not drawn as something that can be clicked`() {
        // Its popover belongs to a later step. Anything that looked pressable
        // would promise something that is not there.
        val drawn = source.substringAfter("private fun cellContentOf(").substringBefore("private fun spokenContentOf(")
        listOf("clickable", "onClick", "pointerHoverIcon", "Button").forEach { interactive ->
            assertTrue(interactive !in drawn, "a task in a cell is drawn as a $interactive")
        }
    }

    @Test
    fun `a task says its name, its colours and its count out loud`() {
        // PLAN 17: colour is never the only thing carrying a meaning.
        val spoken = source.substringAfter("private fun spokenTaskOf(").substringBefore("/**")
        assertTrue("Strings.CellTask.description" in spoken, "a task has no spoken description")
        assertTrue("canonicalName" in spoken, "the colours are not said in words")
        assertTrue("Strings.CellTask.noColor" in spoken, "a task with no colour says nothing about it")
        assertTrue("Strings.CellTask.completed" in spoken, "a finished task does not say so out loud")
        assertTrue("Strings.Table.cellDescription" in source, "the cell no longer describes itself")
    }

    // ------------------------------------------- making a task out of words

    @Test
    fun `the offer to make a task waits for a real selection`() {
        // PLAN 12.6 has the user pick a word and then convert it, so an action
        // standing there with nothing chosen could not do what it says.
        val actions = source.substringAfter("private fun CellEditorActions(").substringBefore("private fun TaskComposerPanel(")
        assertTrue("field.selection.collapsed" in actions, "the offer does not ask whether anything is selected")
        assertTrue("Strings.CellTask.create" in actions, "there is no offer to make a task")
        assertTrue("controller.beginTaskComposer(" in actions, "the offer does nothing")
        assertTrue("field.selection.min" in actions && "field.selection.max" in actions, "the offsets are not the field's own")
    }

    @Test
    fun `the selection is the text field's own rather than something invented`() {
        val editor = source.substringAfter("private fun CellEditorSlot(").substringBefore("private fun CellEditorActions(")
        assertTrue("TextFieldValue" in editor, "the editor cannot report what is selected in it")
        assertTrue("readOnly = composer != null" in editor, "the text can move while a selection points into it")
    }

    @Test
    fun `unsaved words are not offered as a task`() {
        val actions = source.substringAfter("private fun CellEditorActions(").substringBefore("private fun TaskComposerPanel(")
        assertTrue("enabled = !editor.hasChanges" in actions, "a draft can be cut at offsets into stored text")
        assertTrue("Strings.CellTask.saveTextFirst" in actions, "nothing says why the offer is unavailable")
    }

    @Test
    fun `the task panel is drawn in the cell and not over the window`() {
        // PLAN 12.6 rules out a full screen modal or a panel covering the window.
        val panel = source.substringAfter("private fun TaskComposerPanel(").substringBefore("private fun ColorChoices(")
        listOf("Dialog", "AlertDialog", "Popup", "ModalBottomSheet", "fillMaxSize").forEach { overlay ->
            assertTrue(overlay !in panel, "the task panel opens a $overlay")
        }
        val editor = source.substringAfter("private fun CellEditorSlot(").substringBefore("private fun CellEditorActions(")
        assertTrue("width(CellColumnWidth)" in editor, "the cell being worked in is not the width of its own column")
    }

    @Test
    fun `the panel saves with Ctrl and Enter and gives up only on itself with Escape`() {
        val editor = source.substringAfter("private fun CellEditorSlot(").substringBefore("private fun CellEditorActions(")
        assertTrue("controller.cancelTaskComposer()" in editor, "Escape does not close the panel")
        assertTrue("saveTask()" in editor, "Ctrl+Enter does not save the task")
        assertTrue("event.isCtrlPressed" in editor, "there is no Ctrl+Enter at all")
        // The note is a note, so a plain Enter still makes a line in it.
        val panel = source.substringAfter("private fun TaskComposerPanel(").substringBefore("private fun ColorChoices(")
        assertTrue("singleLine = false" in panel, "the note is one line, so Enter cannot break a line in it")
    }

    @Test
    fun `the panel's own keys work wherever the keyboard is inside it`() {
        // Handling them on one field only left Ctrl+Enter dead as soon as the
        // user was typing a note, which is exactly where they finish.
        val panel = source.substringAfter("private fun TaskComposerPanel(").substringBefore("private fun ColorChoices(")
        val onTheColumn = panel.substringAfter("Column(").substringBefore(") {")
        assertTrue("onPreviewKeyEvent" in onTheColumn, "the panel's keys are caught by one field rather than the panel")
        assertTrue("controller.cancelTaskComposer()" in onTheColumn, "Escape does not close the panel from inside it")
        assertTrue("onSave()" in onTheColumn, "Ctrl+Enter does not save from inside the panel")
    }

    @Test
    fun `two tasks side by side do not run into one another`() {
        // Their names and counts would otherwise touch. The gap goes between two
        // things that are drawn, never between two pieces of the document.
        val drawn = source.substringAfter("private fun cellContentOf(").substringBefore("private fun spokenContentOf(")
        assertTrue("cell.segments.getOrNull(index - 1)?.isTask" in drawn, "two tasks are drawn touching one another")
    }

    @Test
    fun `the keyboard comes back to the open work after a refusal`() {
        val editor = source.substringAfter("private fun CellEditorSlot(").substringBefore("private fun CellEditorActions(")
        assertTrue("focusRecall" in editor, "the cell never takes the keyboard back")
        assertTrue("focus.requestFocus()" in editor, "the cell does not ask for the keyboard")
        val panel = source.substringAfter("private fun TaskComposerPanel(").substringBefore("private fun ColorChoices(")
        assertTrue("LaunchedEffect(focusRecall)" in panel, "the panel never takes the keyboard back")
    }

    @Test
    fun `a colour is chosen from the catalogue and never typed as a value`() {
        // PLAN 5.7 keeps every colour a named record; this step creates none.
        val panel = source.substringAfter("private fun TaskComposerPanel(").substringBefore("private fun ColorChoices(")
        assertTrue("Strings.CellTask.colorSearch" in panel, "the catalogue cannot be searched by name")
        assertTrue("Strings.Colors.hexLabel" !in panel, "the panel asks for a colour value")
        val choices = source.substringAfter("private fun ColorChoices(").substringBefore("/** One line of explanation")
        assertTrue("color.canonicalName" in choices, "a swatch is offered without its written name")
        assertTrue("opaqueColorOf(color.hex)" in choices, "the swatch is not the colour it stands for")
        assertTrue("stateDescription = stateText" in choices, "which colour is chosen is carried by fill alone")
    }

    @Test
    fun `the panel asks how a task is tracked only where the pool leaves a choice`() {
        val panel = source.substringAfter("private fun TaskComposerPanel(").substringBefore("private fun ColorChoices(")
        assertTrue("trackingModesOf(" in panel, "the tracking modes are not taken from the one place that decides them")
        assertTrue("trackingChoices.size > 1" in panel, "the user is asked a question that has only one answer")
    }

    @Test
    fun `nothing about the three creation modes is drawn yet`() {
        // PLAN 12.7 has three modes; this step is the first of them, and a
        // switch that did nothing would promise the other two.
        listOf("Çoklu", "multiTask", "multiColor", "creationMode").forEach { later ->
            assertTrue(later !in source, "the table draws a $later control that does nothing")
        }
    }
}
