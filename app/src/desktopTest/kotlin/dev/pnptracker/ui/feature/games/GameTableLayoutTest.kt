package dev.pnptracker.ui.feature.games

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** Every word the application says, where a retired one has to stay retired. */
    private val strings: String =
        Path
            .of("src/commonMain/composeResources/values/strings.xml")
            .let { Files.readString(it) }

    /** The controller behind the screen, for the decisions that are not drawn. */
    private val controllerSource: String =
        Path
            .of("src/commonMain/kotlin/dev/pnptracker/ui/feature/games/GameTableController.kt")
            .let { Files.readString(it) }

    /**
     * The task editor, which the pools share with the table.
     *
     * It moved out of the screen when the pool screens arrived, because the two
     * of them offer the same form over the same task (PLAN 12.10). What these
     * tests ask of it is unchanged; only where it lives is.
     */
    private val editingSurface: String =
        Path
            .of("src/commonMain/kotlin/dev/pnptracker/ui/feature/tasks/TaskEditingSurface.kt")
            .let { Files.readString(it) }

    /** One named stretch of the screen's source, for the guards that read it. */
    private fun sourceOf(
        from: String,
        until: String,
    ): String = source.substringAfter(from).substringBefore(until)

    @Test
    fun `every place these tests read the screen at is really in it`() {
        // Without this, renaming a composable turns a test into one that reads
        // the whole file: `substringAfter` on a name that is not there hands
        // back everything, and a check that was pinning one function quietly
        // starts passing on some other one. Two of these had already gone stale
        // that way.
        val anchors =
            Regex("""substring(?:After|Before)\("((?:[^"\\]|\\.)*)"\)""")
                .findAll(
                    Path.of("src/desktopTest/kotlin/dev/pnptracker/ui/feature/games/GameTableLayoutTest.kt").let { Files.readString(it) },
                ).map { it.groupValues[1].replace("\\n", "\n") }
                .toSet()

        anchors.forEach { anchor ->
            assertTrue(
                anchor in source || anchor in editingSurface || anchor in controllerSource,
                "these tests read the screen at a place it no longer has: $anchor",
            )
        }
    }

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
    fun `a cell holding a task is opened like any other`() {
        // PLAN 12.5 and 5.5 make the task atomic, not the cell. The whole-cell
        // lock of the previous step is gone: the text around a task is the
        // user's to edit, and the task itself is protected by the change rule
        // rather than by refusing to open the cell at all.
        val cellSlot = source.substringAfter("private fun CellSlot(").substringBefore("private fun TaskHandle(")
        assertTrue("cell.editableText != null" !in cellSlot, "a cell still asks whether it may be opened")
        assertTrue("Strings.Cell.lockedByTasks" !in source, "a cell still says it is locked by its tasks")
        assertTrue("onDoubleTap = { onEdit() }" in cellSlot, "a cell can no longer be opened by double click")
    }

    @Test
    fun `a cell is opened by double click and from the keyboard`() {
        // PLAN 17 asks for every main action to be reachable by keyboard, and a
        // dense table wants the single click to do nothing but take the focus,
        // so passing over a cell never puts an editor into it.
        val cell = source.substringAfter("private fun CellSlot(").substringBefore("verticalArrangement = Arrangement.spacedBy(2.dp)")
        assertTrue(cell.contains("onDoubleTap = { onEdit() }"), "a cell cannot be double clicked open")
        assertTrue(cell.contains("onTap = { runCatching { cellFocus.requestFocus() } }"), "a single click does more than take the focus")
        assertTrue(cell.contains("Key.F2"), "F2 does not open the editor")
        assertTrue(cell.contains(".focusable()"), "a cell cannot take the keyboard at all")
        // One focus target, not two: `clickable` is one of its own, and beside
        // `focusable` it made every cell a second, silent tab stop. The keyboard
        // really stopping once per cell is proved in AppKeyboardAndScalingTest.
        assertTrue("clickable(" !in cell && "combinedClickable(" !in cell, "a cell is two tab stops again")
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
    fun `nothing opens over the middle of the window`() {
        // PLAN 12.5 and 12.6 rule out a full screen modal or a panel covering
        // the window. A popover anchored to the word it belongs to is what PLAN
        // asks for and is not one of these.
        listOf("Dialog", "AlertDialog", "ModalBottomSheet").forEach { overlay ->
            assertTrue(overlay !in source, "the table opens a $overlay over the window")
        }
        val editor = source.substringAfter("private fun CellEditorSlot(").substringBefore("private class TaskPainting(")
        assertTrue("width(CellColumnWidth)" in editor, "the cell being worked in is not the width of its own column")
        assertTrue("Popup" !in editor, "the cell editor opens a popup of its own")
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
        val drawn = source.substringAfter("private fun drawnDocumentOf(").substringBefore("private fun spokenContentOf(")
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
    fun `a task is painted in its own colour, with ink and an edge chosen against it`() {
        // The colour is the user's, picked for filament rather than for reading
        // text on. PLAN 17 asks for text that can still be read and PLAN 12.7
        // for a contrast frame where the colour alone would not show — which is
        // exactly the white-on-white and black-on-black case.
        val paint = source.substringAfter("private fun paintsOf(").substringBefore("private data class DrawnStripe(")
        assertTrue("opaqueColorOf(color.hex)" in paint, "a task's colour is not taken from its colour record")
        assertTrue("readableInkOn(fill)" in paint, "the ink on a task is not chosen against the colour behind it")
        assertTrue("visibleEdgeOn(fill)" in paint, "a task has no edge, so a colour matching the theme vanishes")
        // One paint per colour, so every piece of a several-colour task — the
        // swatches included — gets its own ink and its own edge. White beside
        // black has to be visible in either theme, and that is what the edge is.
        assertTrue("segment.colors.map" in paint, "a several-colour task is painted from one colour")
        assertTrue("drawTaskEdges(" in source, "the edge is worked out and never drawn")
        // No value copied into the screen: whatever a colour is, it is its own.
        assertTrue(
            Regex("""["']#[0-9A-Fa-f]{6}["']""").find(source) == null,
            "a colour value is written into the screen instead of read from its record",
        )
    }

    @Test
    fun `the count beside a task comes from the task and not from anybody's text`() {
        val drawn = source.substringAfter("private fun drawnDocumentOf(").substringBefore("private fun spokenContentOf(")
        assertTrue("segment.requiredQuantity" in drawn, "the count is not read from the task")
        assertTrue("Strings.CellTask.quantityMark" in drawn, "the count mark is not taken from the text catalogue")
        // The mark is drawn around the document and never inside it.
        assertTrue("QUANTITY_GAP" in drawn, "the count is drawn up against the word before it")
    }

    @Test
    fun `a finished task is struck through where it stands`() {
        // PLAN 5.6 leaves a finished task in its cell rather than removing it.
        val drawn = source.substringAfter("private fun drawnDocumentOf(").substringBefore("private fun spokenContentOf(")
        assertTrue("segment.isCompletedTask" in drawn, "a finished task looks exactly like an unfinished one")
        assertTrue("TextDecoration.LineThrough" in drawn, "a finished task is not struck through")
    }

    @Test
    fun `a task is something the user can press, by pointer and by keyboard`() {
        // PLAN 12.5: hovering a task makes it clear it can be pressed, and PLAN
        // 17 wants the same reachable from the keyboard with a visible focus.
        val handle = source.substringAfter("private fun TaskHandle(").substringBefore("private fun CellEditorSlot(")
        assertTrue("PointerIcon.Hand" in handle, "a task does not take the hand cursor")
        assertTrue("hoverable(" in handle, "a task does not respond to being hovered")
        assertTrue("Key.Spacebar" in handle, "Space does not open a task's menu")
        assertTrue("Key.Enter" in handle, "Enter does not open a task's menu")
        assertTrue("role = Role.Button" in handle, "a task does not say it is something to press")
        assertTrue("controller.openTaskMenu(" in handle, "pressing a task does nothing")
        // The keyboard comes from `clickable` and from nothing beside it. A
        // `focusable` of its own made the word two focus targets, and Tab landed
        // on the one no key handler sat above — Enter and Space then did nothing
        // at all. `TaskChipKeyboardTest` presses the keys for real; this keeps
        // the shape that lets them arrive.
        assertTrue(".focusable()" !in handle, "the word is two focus targets drawn as one")
        assertTrue(
            handle.indexOf(".onPreviewKeyEvent") < handle.indexOf(".clickable("),
            "the key handler sits below the control it is about, where no key reaches it",
        )
        // A ring rather than a wash: the colour underneath is the information.
        assertTrue("style = Stroke(" in handle, "hovering a task covers the colour it is telling the user about")
    }

    @Test
    fun `the cell asks about a key last, so a task inside it answers first`() {
        // A preview above everything in the cell reached the cell *before* the
        // task the keyboard was really on: Enter opened the cell's editor and
        // the task never saw the key. Bubbling asks the cell only for what
        // nothing inside it claimed.
        val cell = sourceOf("private fun CellSlot(", "private fun tickContentOf(")
        assertTrue(".onKeyEvent {" in cell, "the cell no longer answers Enter at all")
        assertTrue(".onPreviewKeyEvent" !in cell, "the cell takes the key before whatever is really focused")
        assertTrue(cell.indexOf(".onKeyEvent {") < cell.indexOf(".focusable()"), "the cell's own key never reaches its handler")
        assertTrue("Key.F2" in cell, "F2 no longer opens the cell")
    }

    @Test
    fun `a task says its name, its colours and its count out loud`() {
        // PLAN 17: colour is never the only thing carrying a meaning.
        val spoken = source.substringAfter("private fun spokenTaskOf(").substringBefore("/**")
        assertTrue("Strings.CellTask.description" in spoken, "a task has no spoken description")
        assertTrue("canonicalName" in spoken, "the colours are not said in words")
        assertTrue("Strings.CellTask.noColor" in spoken, "a task with no colour says nothing about it")
        // Whether it is finished is said as a state of the piece rather than
        // folded into its name, so it is heard once and heard changing.
        assertTrue("Strings.CellTask.completed" !in spoken, "a finished task says so twice over")
        val handle = source.substringAfter("private fun TaskHandle(").substringBefore("private fun CellEditorSlot(")
        assertTrue("stateDescription = completion" in handle, "a task never says whether it is finished")
        assertTrue("Strings.Shortage.stateCompleted" in handle, "a finished task does not say so out loud")
        assertTrue("Strings.Shortage.stateOpen" in handle, "an unfinished task does not say so out loud")
        assertTrue("Strings.Table.cellDescription" in source, "the cell no longer describes itself")
    }

    // ------------------------------------------- making a task out of words

    @Test
    fun `the offer to make a task waits for a real selection`() {
        // PLAN 12.6 has the user pick a word and then convert it, so an action
        // standing there with nothing chosen could not do what it says.
        val actions = source.substringAfter("private fun CellEditorActions(").substringBefore("private fun TaskComposerPanel(")
        // The selection is the one the user made, kept beside the field's own:
        // a press on the offer takes focus off the field and a field that loses
        // focus drops its selection, which used to take the offer off the screen
        // between the press and the release (found in real use, PLAN 12.6).
        assertTrue("chosen != null && !chosen.collapsed" in actions, "the offer does not ask whether anything is selected")
        assertTrue("Strings.CellTask.create" in actions, "there is no offer to make a task")
        assertTrue("controller.beginTaskComposer(" in actions, "the offer does nothing")
        assertTrue("it.min" in actions && "it.max" in actions, "the offsets are not the selected stretch's own")
    }

    @Test
    fun `the selection is the text field's own rather than something invented`() {
        val editor = source.substringAfter("private fun CellEditorSlot(").substringBefore("private fun CellEditorActions(")
        assertTrue("TextFieldValue" in editor, "the editor cannot report what is selected in it")
        assertTrue("readOnly = composer != null" in editor, "the text can move while a selection points into it")
    }

    @Test
    fun `unsaved words are not offered as a task`() {
        val actions = source.substringAfter("private fun CellEditorActions(").substringBefore("/**\n * Naming the colour")
        assertTrue("enabled = !editor.hasUnsavedChanges" in actions, "a draft can be cut at offsets into stored text")
        assertTrue("Strings.CellTask.saveTextFirst" in actions, "nothing says why the offer is unavailable")
    }

    @Test
    fun `the task panel is drawn in the cell and not over the window`() {
        // PLAN 12.6 rules out a full screen modal or a panel covering the window.
        val panel = source.substringAfter("private fun TaskComposerPanel(").substringBefore("private fun sentenceOf(")
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
        val panel = source.substringAfter("private fun TaskComposerPanel(").substringBefore("private fun sentenceOf(")
        assertTrue("singleLine = false" in panel, "the note is one line, so Enter cannot break a line in it")
    }

    @Test
    fun `the panel's own keys work wherever the keyboard is inside it`() {
        // Handling them on one field only left Ctrl+Enter dead as soon as the
        // user was typing a note, which is exactly where they finish.
        val panel = source.substringAfter("private fun TaskComposerPanel(").substringBefore("private fun sentenceOf(")
        val onTheColumn = panel.substringAfter("Column(").substringBefore(") {")
        assertTrue("onPreviewKeyEvent" in onTheColumn, "the panel's keys are caught by one field rather than the panel")
        assertTrue("controller.cancelTaskComposer()" in onTheColumn, "Escape does not close the panel from inside it")
        assertTrue("onSave()" in onTheColumn, "Ctrl+Enter does not save from inside the panel")
    }

    @Test
    fun `two tasks side by side do not run into one another`() {
        // Their names and counts would otherwise touch. The gap goes between two
        // things that are drawn, never between two pieces of the document.
        val drawn = source.substringAfter("private fun drawnDocumentOf(").substringBefore("private fun spokenContentOf(")
        assertTrue("cell.segments.getOrNull(index - 1)?.isTask" in drawn, "two tasks are drawn touching one another")
    }

    @Test
    fun `the keyboard comes back to the open work after a refusal`() {
        val editor = source.substringAfter("private fun CellEditorSlot(").substringBefore("private fun CellEditorActions(")
        assertTrue("focusRecall" in editor, "the cell never takes the keyboard back")
        assertTrue("focus.requestFocus()" in editor, "the cell does not ask for the keyboard")
        val panel = source.substringAfter("private fun TaskComposerPanel(").substringBefore("private fun sentenceOf(")
        assertTrue("LaunchedEffect(focusRecall)" in panel, "the panel never takes the keyboard back")
    }

    @Test
    fun `a colour is chosen from the catalogue and never typed as a value`() {
        // PLAN 5.7 keeps every colour a named record; this step creates none.
        val panel = source.substringAfter("private fun TaskComposerPanel(").substringBefore("private fun sentenceOf(")
        assertTrue("Strings.CellTask.colorSearch" in panel, "the catalogue cannot be searched by name")
        assertTrue("Strings.Colors.hexLabel" !in panel, "the panel asks for a colour value")
        val choices = editingSurface.substringAfter("internal fun ColorList(").substringBefore("/** One line of explanation")
        assertTrue("color.canonicalName" in choices, "a swatch is offered without its written name")
        assertTrue("opaqueColorOf(color.hex)" in choices, "the swatch is not the colour it stands for")
        assertTrue("stateDescription = stateText" in choices, "which colour is chosen is carried by fill alone")
    }

    @Test
    fun `the panel asks how a task is tracked only where the pool leaves a choice`() {
        val panel = source.substringAfter("private fun TaskComposerPanel(").substringBefore("private fun sentenceOf(")
        assertTrue("trackingModesOf(" in panel, "the tracking modes are not taken from the one place that decides them")
        assertTrue("trackingChoices.size > 1" in panel, "the user is asked a question that has only one answer")
    }

    @Test
    fun `a task made in several colours is one word painted in each of them`() {
        val document =
            source
                .substringAfter(
                    "private fun drawnDocumentOf(",
                ).substringBefore("private fun AnnotatedString.Builder.paintedName(")
        // PLAN 12.7 splits the name over the user's own characters, in slot
        // order, and derives the split rather than storing it.
        assertTrue("taskColorLayoutOf(" in document, "the name is not shared out among the colours")
        assertTrue("layout.slices.forEach" in document, "the colours after the first paint nothing")
        assertTrue("substring(slice.start, slice.end)" in document, "the name is not painted piece by piece")
        assertEquals(
            1,
            Regex("marks\\[index\\]").findAll(document).count(),
            "the count is drawn more than once",
        )
    }

    @Test
    fun `a colour with no share of the name is drawn beside it rather than lost`() {
        val document =
            source
                .substringAfter(
                    "private fun drawnDocumentOf(",
                ).substringBefore("private fun AnnotatedString.Builder.paintedName(")
        assertTrue("layout.markerSlots.forEach" in document, "a colour the name does not reach is drawn nowhere")
        assertTrue("MARKER_MARK" in document, "the swatch has nothing to paint")
        // Before the count, which stays last, and inside the task's own run, so
        // it is part of the same thing to press.
        assertTrue(
            document.indexOf("layout.markerSlots.forEach") < document.indexOf("marks[index]"),
            "the swatches are drawn after the count rather than before it",
        )
        assertTrue(
            "DrawnTask(segment = segment, start = start, end = length" in document,
            "the swatches fall outside the task's own run",
        )
        // Never in the string the editor lines up with the document, because
        // there every offset has to go on meaning what it meant.
        assertTrue("if (withCounts) {" in document, "the swatches are drawn into the text being edited")
    }

    @Test
    fun `a task with one colour or none is drawn without any of the splitting`() {
        val document =
            source
                .substringAfter(
                    "private fun drawnDocumentOf(",
                ).substringBefore("private fun AnnotatedString.Builder.paintedName(")
        // One colour is one slice covering the whole name, so nothing about it
        // goes near a swatch. None is the branch below: the whole name in the
        // one fallback paint rather than an empty drawing.
        assertTrue("layout.slices.isEmpty()" in document, "a task with no colour has nothing to draw it with")
        assertTrue("segment.text, taskPaints.first()" in document, "a task with no colour loses its name")
        val paint = source.substringAfter("private fun paintsOf(").substringBefore("private data class DrawnStripe(")
        assertTrue("segment.colors.isEmpty()" in paint, "a task with no colour has no paint of its own")
        assertTrue("surfaceVariant" in paint, "a task with no colour is not drawn as a task at all")
    }

    @Test
    fun `every coloured piece gets its own contrast edge`() {
        val edges =
            source
                .substringAfter("private fun DrawScope.drawTaskEdges(")
                .substringBefore("private fun CellSlot(")
        // One rectangle around a several-colour task would put one colour's edge
        // around all of them, and PLAN 12.7 asks for the frame per piece.
        assertTrue("task.stripes.forEach" in edges, "a several-colour task is edged as one block")
        assertTrue("stripe.edge" in edges, "the edge does not come from the colour it surrounds")
    }

    // ------------------------------------- the menu anchored to a task

    @Test
    fun `the popover hangs off the task's own position`() {
        // PLAN 12.5 opens it over the word. Anything remembering a coordinate
        // would be pointing at whatever was there after a scroll.
        val handle = source.substringAfter("private fun TaskHandle(").substringBefore("private fun CellEditorSlot(")
        assertTrue("TaskPopover(" in handle, "the popover is not hung off the task")
        val popover = source.substringAfter("private fun TaskPopover(").substringBefore("private fun TaskMenuActions(")
        assertTrue("AnchoredAboveWord(" in popover, "the popover does not place itself against the word")
        assertTrue("Popup(" in popover, "the popover is not a popup at all")
        assertTrue("Key.Escape" in popover, "Escape does not close the popover")
        assertTrue("controller.closeInnermost()" in popover, "Escape closes more than the innermost surface")
        // Handled for the whole popover, not for one field in it: handling it
        // deeper left Ctrl+Enter dead in exactly the field the user ends in.
        assertTrue("event.isCtrlPressed" in popover, "Ctrl+Enter does nothing in the popover")
        assertTrue("controller.saveTaskEdit()" in popover, "Ctrl+Enter does not save an open panel")
        assertTrue("controller.confirmConvertToText()" in popover, "Ctrl+Enter does not answer an open question")
    }

    @Test
    fun `the bounds a popover is placed against come from the real layout`() {
        val cellSlot = source.substringAfter("private fun CellSlot(").substringBefore("private fun TaskHandle(")
        assertTrue("onTextLayout = { layout = it }" in cellSlot, "the cell never learns where it laid its text")
        assertTrue("boxesOfRange(" in cellSlot, "a task's boxes are not taken from the layout")
        assertTrue(
            Regex("""IntOffset\(\s*\d+\s*,""").find(source) == null,
            "a fixed screen coordinate is used to place something",
        )
    }

    @Test
    fun `a name that wrapped is pressable on every line it reaches`() {
        val handle = source.substringAfter("private fun TaskHandle(").substringBefore("private fun CellEditorSlot(")
        assertTrue("boxes.forEachIndexed" in handle, "only part of a wrapped name is a control")
        // One control, several shapes: the keyboard stops at a task once, a
        // reader is told about it once, and the popover hangs off the line the
        // word starts on rather than off each piece of it. The later lines
        // answer the pointer through a tap detector, which takes no focus —
        // `clickable` there would be a stop of its own per line.
        assertEquals(1, Regex("""\.clickable\(""").findAll(handle).count(), "a wrapped name takes the keyboard once a line")
        assertTrue("detectTapGestures" in handle, "the later lines of a wrapped name cannot be clicked")
        assertTrue("leading && menu != null" in handle, "a wrapped name would open two popovers")
        assertTrue("clearAndSetSemantics" in handle, "a wrapped name is announced once for each line")
    }

    @Test
    fun `the creation panel offers the three modes it can actually save`() {
        val panel = source.substringAfter("private fun CreationModeChoice(").substringBefore("private fun BatchTaskRow(")
        assertTrue("TaskCreationMode.SINGLE_COLOR" in panel, "one task cannot be made")
        assertTrue("TaskCreationMode.INDEPENDENT_TASKS" in panel, "several tasks cannot be made")
        assertTrue("TaskCreationMode.SINGLE_ITEM_MULTICOLOR" in panel, "one task in several colours cannot be made")
        // All three really save, so none of them is drawn dead.
        assertTrue(
            Regex("""enabled\s*=\s*false""").find(source) == null,
            "a control is drawn permanently disabled",
        )
    }

    @Test
    fun `the choices in the panel wrap instead of running off the cell`() {
        // The panel is one column of the table wide. In a plain row the third
        // mode was pushed past the edge where nothing could reach it, and its
        // label wrapped to one word per line and stretched the row.
        val panel = source.substringAfter("private fun CreationModeChoice(").substringBefore("private fun BatchTaskRow(")
        assertTrue("FlowRow(" in panel, "the modes are laid out in one line however narrow the cell is")
        val tracking = editingSurface.substringAfter("internal fun TrackingChoice(").substringBefore("/**\n * The colours a task")
        assertTrue("FlowRow(" in tracking, "the tracking choices are laid out in one line")
    }

    @Test
    fun `the several colour panel describes one task and not several`() {
        val fields =
            source
                .substringAfter("private fun MulticolorFields(")
                .substringBefore("private fun sentenceOf(")
        // PLAN 12.7 gives such a task one total and one counter, so there is one
        // of each field here and no row to repeat them in.
        listOf("quantityLabel", "notesLabel").forEach { field ->
            assertEquals(
                1,
                Regex("Strings\\.CellTask\\.$field").findAll(fields).count(),
                "$field is asked for more than once for one task",
            )
        }
        assertTrue("Strings.CellTask.rowTitle" !in fields, "one task is drawn as a numbered row of several")
        assertTrue("controller::toggleMulticolorColor" in fields, "the colours cannot be chosen")
    }

    @Test
    fun `the colours of a task are an ordered list that can be reordered by keyboard`() {
        val list = editingSurface.substringAfter("internal fun ChosenColorList(").substringBefore("internal fun ColorList(")
        // PLAN 5.10 makes the slot the user's own order and PLAN 17 wants every
        // action reachable from the keyboard, so this is buttons and not a drag.
        assertTrue("Strings.CellTask.colorSlot" in list, "an entry does not say which place it holds")
        assertTrue("onMoveUp(slot)" in list && "onMoveDown(slot)" in list, "the order cannot be changed")
        assertTrue("onDrop(colorId)" in list, "a colour cannot be taken out")
        assertTrue("slot > 0" in list, "the first entry offers to move further up")
        assertTrue("slot < colorIds.lastIndex" in list, "the last entry offers to move further down")
        assertTrue("failedSlot == slot" in list, "a refused colour is not marked on its own entry")
        // PLAN 17: the swatch is never the only thing saying which colour it is.
        // Given a weight in a plain row the name was squeezed to nothing by the
        // three actions, so the entry wraps instead.
        assertTrue("Strings.CellTask.colorSlot" in list, "an entry does not name its colour in words")
        assertTrue("FlowRow(" in list, "an entry cannot give the name a line of its own when it needs one")
        assertTrue("weight(1f)" !in list, "the name is squeezed by whatever is beside it")
    }

    @Test
    fun `a batch row is a row of the panel rather than a card of its own`() {
        // The panel lives inside a table cell. Every border it draws is width
        // the row does not have, so the rows are separated by a line.
        val row = source.substringAfter("private fun BatchTaskRow(").substringBefore("private fun TaskRowFields(")
        assertTrue("HorizontalDivider(" in row, "the rows of a batch run into one another")
        assertTrue("Strings.CellTask.rowTitle" in row, "a row does not say which task it is")
        assertTrue("controller.removeTaskRow(row)" in row, "a row cannot be taken away")
        assertTrue("composer.canRemoveRow" in row, "the last rows of a batch can be taken away")
    }

    @Test
    fun `the batch floor is said once rather than under every row`() {
        // It is a fact about the batch, not about a row. Drawn inside the row it
        // appeared once per row, so a two row panel said it twice.
        val row = source.substringAfter("private fun BatchTaskRow(").substringBefore("private fun TaskRowFields(")
        assertTrue("Strings.CellTask.rowFloor" !in row, "the floor is said once for every row")
        assertEquals(
            1,
            Regex("""Strings\.CellTask\.rowFloor""").findAll(source).count(),
            "the floor is drawn in more than one place",
        )
    }

    @Test
    fun `the panel says which row repeats a colour rather than only that one does`() {
        val fields = source.substringAfter("private fun TaskRowFields(").substringBefore("private fun MulticolorFields(")
        assertTrue("isRepeatedColor" in fields, "a repeated colour is not marked on its own row")
        assertTrue("Strings.CellTask.rowDuplicate" in fields, "a repeated colour is not said in words")
    }

    @Test
    fun `a refusal about one row is said on that row`() {
        val fields = source.substringAfter("private fun TaskRowFields(").substringBefore("private fun MulticolorFields(")
        assertTrue("composer.failureRow == row" in fields, "a refused row is not told which one it was")
    }

    @Test
    fun `every field of the panel belongs to the row it is drawn in`() {
        // The one thing that would quietly ruin a batch: a field wired to the
        // composer rather than to its row, so typing in the third task changed
        // the first. Every call carries the row it came from.
        val fields = source.substringAfter("private fun TaskRowFields(").substringBefore("private fun MulticolorFields(")
        listOf("editTaskColorQuery", "chooseTaskColor", "editTaskQuantity", "editTaskNotes", "chooseTaskTracking")
            .forEach { call ->
                assertTrue("controller.$call(row, " in fields, "$call does not say which row it is for")
            }
    }

    @Test
    fun `clicking away does not throw away something typed`() {
        val popover = source.substringAfter("private fun TaskPopover(").substringBefore("private fun TaskMenuActions(")
        assertTrue("hasUnsavedChanges != true" in popover, "clicking away can lose what was typed")
    }

    @Test
    fun `the menu offers what this step really has and nothing dead`() {
        // PLAN 12.5's three actions and the two PLAN 6.3 adds to them.
        val menu = source.substringAfter("private fun TaskMenuActions(").substringBefore("private fun ConvertConfirmation(")
        assertTrue("Strings.TaskMenu.edit" in menu, "there is no way to edit a task")
        assertTrue("Strings.TaskMenu.convertToText" in menu, "there is no way to turn a task back into text")
        assertTrue("Strings.TaskMenu.reportShortage" in menu, "there is no way to say something came out short")
        assertTrue("enabled = false" !in menu, "the menu carries a button that cannot do anything")
        // A pipeline is worked on from its pool card (PLAN 12.11), not from the
        // cell, and finishing a whole game is a later slice's. Neither belongs
        // in the table.
        listOf("setStageQuantity", "completePrimaryBatch", "setManuallyCompleted", "StageBadge", "stageBadge").forEach { later ->
            assertTrue(later !in source, "the table offers a $later that is not the cell's to offer")
        }
    }

    @Test
    fun `the shortage form asks for one amount and offers to say more`() {
        val panel = source.substringAfter("private fun ShortagePanel(").substringBefore("/** One step of a pipeline")
        assertTrue("Strings.Shortage.quantityLabel" in panel, "there is nowhere to say how many")
        assertTrue("Strings.Shortage.noteLabel" in panel, "there is nowhere to say what happened")
        assertTrue("Strings.Shortage.save" in panel && "Strings.Shortage.cancel" in panel, "the form cannot be finished")
        // PLAN 7.4 gives naming a card to the card pipeline, and a step to a pool
        // that runs through steps. Elsewhere they would collect an answer the
        // record cannot keep.
        assertTrue("poolType == PoolType.CARD" in panel, "any task at all is asked which card came up short")
        assertTrue("pipeline.isNotEmpty()" in panel, "a pool with no steps is asked which step")
        // PLAN 7.3 gives moving a counter to the badge, which is the next slice.
        assertTrue("setStageQuantity" !in panel, "the shortage form moves a pipeline counter")
    }

    @Test
    fun `one form serves both saying what came up short and what was made good`() {
        // PLAN 6.3 asks the same things of each, so a user who has filled one in
        // has already learnt the other.
        assertEquals(1, Regex("private fun ShortagePanel\\(").findAll(source).count())
        val popover = source.substringAfter("private fun TaskPopover(").substringBefore("private fun ShortagePanel(")
        assertTrue("is CellWork.ReportingShortage ->" in popover, "there is no way to say something came up short")
        assertTrue("is CellWork.ResolvingShortage ->" in popover, "there is no way to say something was made good")
        assertTrue("Strings.Shortage.reportTitle" in popover && "Strings.Shortage.resolveTitle" in popover)
    }

    @Test
    fun `what went wrong is said in words about the work and never in the database's own`() {
        val message = source.substringAfter("private fun shortageMessageOf(").substringBefore("private fun TaskMenuActions(")
        // Every case the user can reach has a sentence, and anything else falls
        // back on one rather than on the failure's own name.
        assertTrue("Strings.Shortage.errorGeneral" in message, "an unnamed refusal would show nothing")
        assertTrue("failure.name" !in message, "the refusal is shown by its own name")
        listOf("errorQuantity", "errorTooMany", "errorGone", "errorEventUsed", "errorDetail").forEach {
            assertTrue(it in message, "$it is never said")
        }
    }

    @Test
    fun `the amount is where the keyboard lands, and where it is called back to`() {
        val panel = source.substringAfter("private fun ShortagePanel(").substringBefore("/** One step of a pipeline")
        assertTrue("LaunchedEffect(title, failure) { amount.requestFocus() }" in panel, "a refused amount keeps the focus")
        assertTrue("focusRequester(amount)" in panel, "the amount field cannot take the focus")
    }

    @Test
    fun `sending a form again is the same movement rather than a second one`() {
        // PLAN 5.12: the identity is the whole of what tells a retry from a
        // second report, so it is chosen when the form opens and not when it is
        // sent — and a send already on its way is not sent again.
        assertTrue(
            "ShortageDraft(eventId = idGenerator.newId())" in controllerSource,
            "the name of a movement is not fixed when its form opens",
        )
        val save = controllerSource.substringAfter("suspend fun saveShortage()").substringBefore("private fun isSavingShortage(")
        assertTrue("eventId = draft.eventId" in save, "a retry would be sent under a new name")
        assertTrue("if (isSavingShortage(open)) return" in save, "one form could send two reports")
        assertTrue("newId()" !in save, "a name is made at the moment of sending")
    }

    @Test
    fun `the question about finishing a game hangs off the row that asked it`() {
        // PLAN 12.9 asks it about one row, and PLAN 17 will not have a surface
        // cover the screen: it is anchored to the tick, so the row stays visible
        // while the decision is made and scrolling moves both together.
        val popover = source.substringAfter("private fun GameCompletionPopover(").substringBefore("/**\n * How a task is drawn")
        assertTrue("AnchoredAboveWord(gap)" in popover, "the question is placed at a remembered coordinate")
        assertTrue("Strings.Table.completeGameQuestion" in popover, "PLAN 12.9's question is not asked")
        assertTrue("completeGameUnfinished" in popover, "the user is not told how much they are agreeing to")
        assertTrue("Strings.Table.completeGameYes" in popover && "Strings.Table.completeGameNo" in popover, "there is no answer to give")
        // Escape closes one layer; Ctrl+Enter answers once; clicking away is
        // never an answer, because closing is what `Hayır` does.
        assertTrue("event.key == Key.Escape" in popover, "Escape does not close the question")
        assertTrue("event.isCtrlPressed" in popover, "Ctrl+Enter cannot answer the question")
        assertTrue("onDismissRequest = controller::closeInnermost" in popover, "clicking away writes something")
        assertTrue("enabled = !confirming.isSaving" in popover, "one answer could be sent twice")
    }

    @Test
    fun `the words a finished game used to be refused with are gone`() {
        assertTrue("task_progress_game_completed" !in strings, "the message about work that is not built is still offered")
        assertTrue("gameIsCompleted" !in source, "the screen still decides whether a game is finished")
    }

    @Test
    fun `a task in a finished game is reported against like any other`() {
        // PLAN 6.3 reopens the task and the game in one transaction, so nothing
        // on this side holds the action back any more — and nothing on this side
        // decides whether the game has to be reopened either. That is read from
        // the database inside the write.
        val begin = controllerSource.substringAfter("fun beginReportShortage()").substringBefore("fun beginResolveShortage()")
        assertTrue("gameIsCompleted" !in begin, "the form still refuses to open over a finished game")
        val save = controllerSource.substringAfter("suspend fun saveShortage()").substringBefore("private fun isSavingShortage(")
        assertTrue("gameIsCompleted" !in save, "the screen still decides whether a game is finished")
        assertTrue("task_progress_game_completed" !in strings, "the message about work that is not built is still offered")
    }

    @Test
    fun `a game row carries the one action a game has`() {
        // PLAN 12.3 puts the completion tick in the game name column, and PLAN
        // 12.9 makes it the whole of what a row can be asked to do. It is in that
        // column and not in a column of its own, so it is reachable in a narrow
        // window without scrolling the table sideways.
        val cell = source.substringAfter("private fun GameNameCell(").substringBefore("private val TickSize")
        assertTrue("GameCompletionTick(" in cell, "a game row has no way to be finished")
        assertTrue("GameColumnWidth" in cell, "the tick moved out of the name column")
        // Still five cell columns beside the name, so the tick cost the table no
        // width and nothing moved out of reach in a narrow window.
        assertTrue(
            "GameColumnWidth + CellColumnWidth * CellColumnType.entries.size" in source,
            "the table grew a column of its own for the tick",
        )
        val tick = source.substringAfter("private fun GameCompletionTick(").substringBefore("private fun GameCompletionPopover(")
        // One stop and one thing said, so a reader hears the game once.
        assertTrue("role = Role.Button" in tick, "the tick is not announced as something that can be pressed")
        assertTrue("stateDescription = stateText" in tick, "the tick does not say how the game stands")
        assertTrue("Key.Enter, Key.NumPadEnter, Key.Spacebar" in tick, "the tick cannot be pressed from the keyboard")
        // A preview reaches the nodes above the one holding the keyboard, so a
        // handler written below `clickable` never runs — and a `focusable` of
        // its own beside it would make one control two tab stops. Both were
        // written that way once and both were found only by pressing the key.
        assertTrue(
            tick.indexOf(".onPreviewKeyEvent") < tick.indexOf(".clickable("),
            "the key handler sits below the control it is about, where no key reaches it",
        )
        assertTrue(".focusable()" !in tick, "the tick is two tab stops drawn as one")
        assertTrue("if (row.isCompleted)" in tick, "a finished game is still offered a control that would un-finish it")
    }

    @Test
    fun `a task can be finished and taken back from its own menu`() {
        val menu = source.substringAfter("private fun TaskMenuActions(").substringBefore("private fun ConvertConfirmation(")
        // One control both ways round, chosen from the task rather than from
        // two buttons standing side by side.
        assertTrue("Strings.TaskMenu.reopen" in menu && "Strings.TaskMenu.complete" in menu, "a task cannot be finished")
        assertTrue("if (menu.isCompleted)" in menu, "the menu offers the same word whichever way the task stands")
        assertTrue("toggleCompletionFromMenu()" in menu, "the menu's finish does nothing")
    }

    @Test
    fun `making good is offered only when there is something to make good`() {
        val menu = source.substringAfter("private fun TaskMenuActions(").substringBefore("private fun ConvertConfirmation(")
        val guarded = menu.substringAfter("if (menu.owesSomething) {", "")
        assertTrue("Strings.TaskMenu.resolveShortage" in guarded, "making good is offered on a task that owes nothing")
        assertTrue("beginResolveShortage" in menu, "making good does nothing")
    }

    @Test
    fun `a task carries its own tick, drawn with the text rather than written into it`() {
        // PLAN 12.5 puts a tick on the piece and PLAN 5.6 leaves a finished task
        // in its cell. The room for it is reserved in the laid out text, so the
        // name is never covered and a name that wraps wraps around it — but the
        // document the user typed does not gain a character.
        assertTrue("appendInlineContent(tickIdOf(" in source, "the tick is not laid out with the text")
        assertTrue("if (withCounts) {" in source, "the tick would be drawn where the text has to match the document")
        assertTrue("private fun CompletionTick(" in source, "there is no tick")
        val tick = source.substringAfter("private fun CompletionTick(").substringBefore("/**")
        // No focus and no voice of its own: one task is one stop and one node.
        assertTrue("clearAndSetSemantics" in tick, "the tick speaks over the task it belongs to")
        assertTrue("clickable" !in tick, "the tick takes a keyboard stop of its own")
        assertTrue("detectTapGestures" in tick, "the tick cannot be pressed")
    }

    @Test
    fun `finishing a task is reachable from the keyboard without a stop of its own`() {
        val handle = source.substringAfter("private fun TaskHandle(").substringBefore("private fun CellEditorSlot(")
        assertTrue("CustomAccessibilityAction(finishLabel)" in handle, "there is no way to finish a task by keyboard")
        assertTrue("toggleTaskCompletion(gameId, columnType, taskId)" in handle, "the action does nothing")
        // The action hangs off the leading box, which is the one that takes
        // focus, so a task in several colours still offers it exactly once.
        assertTrue("if (leading) {" in handle, "every piece of a task carries its own action")
    }

    // ------------------------------------------------- changing a task

    @Test
    fun `the edit panel asks for everything the task is, together`() {
        val panel = editingSurface.substringAfter("internal fun TaskEditPanel(").substringBefore("/** The tracking modes")
        assertTrue("Strings.TaskEdit.nameLabel" in panel, "the name cannot be changed")
        assertTrue("ColorList(" in panel, "the colour cannot be changed")
        assertTrue("Strings.CellTask.quantityLabel" in panel, "the total cannot be changed")
        assertTrue("Strings.CellTask.notesLabel" in panel, "the note cannot be changed")
        assertTrue("host.saveTaskEdit()" in panel, "nothing saves the panel")
    }

    @Test
    fun `a task with several colours has its whole ordered list to work in`() {
        val panel = editingSurface.substringAfter("internal fun TaskEditPanel(").substringBefore("/** The tracking modes")
        assertTrue("editor.holdsSeveralColors" in panel, "a several-colour task is offered one colour box")
        assertTrue("ChosenColorList(" in panel, "the ordered list cannot be edited")
        assertTrue("host::moveTaskEditColorUp" in panel, "the colours cannot be reordered from the panel")
        assertTrue("Strings.TaskEdit.colorFloor" in panel, "nothing says a several-colour task keeps two colours")
    }

    @Test
    fun `turning a task into text is asked about first, in words`() {
        val confirm = source.substringAfter("private fun ConvertConfirmation(").substringBefore("/** The tracking modes")
        assertTrue("Strings.TaskConvert.body" in confirm, "the question does not say what happens to the word")
        assertTrue("work.hasProgress" in confirm, "the question does not say when there is a history to lose")
        assertTrue("Strings.TaskConvert.historyWarning" in confirm, "the history is not mentioned")
        assertTrue("Strings.TaskConvert.irreversible" in confirm, "the question does not say it is final")
        assertTrue("controller.confirmConvertToText()" in confirm, "agreeing does nothing")
    }

    // ------------------------------------------- the editor and its tasks

    @Test
    fun `the editor paints the tasks without changing a character`() {
        val painting = source.substringAfter("private class TaskPainting(").substringBefore("/**\n * The two things")
        assertTrue("OffsetMapping.Identity" in painting, "the editor's offsets can drift from the document")
        val editor = source.substringAfter("private fun CellEditorSlot(").substringBefore("private class TaskPainting(")
        assertTrue("visualTransformation = painted" in editor, "the tasks are not painted while the cell is written in")
        assertTrue("withCounts = false" in editor, "the count is drawn into the text being edited")
    }

    @Test
    fun `a refused keystroke is said in words rather than by nothing happening`() {
        val actions = source.substringAfter("private fun CellEditorActions(").substringBefore("/**\n * Naming the colour")
        assertTrue("editor.refusal" in actions, "a refused change says nothing at all")
    }

    @Test
    fun `only the form that reports asks where the pieces were noticed`() {
        // Nothing is kept about where a shortage was made good again — only
        // about where it went wrong — so a step chooser on the other form would
        // take an answer and drop it. The panel is shared between the two, so
        // the pool having a pipeline is not on its own enough to show it.
        val panel =
            source
                .substringAfter("private fun ShortagePanel(")
                .substringBefore("@Composable\nprivate fun StageChoice(")
        assertTrue("asksWhereNoticed" in panel, "the panel offers a step without being asked for one")
        assertTrue(
            "if (asksWhereNoticed) poolType?.let { stagesOf(it) }.orEmpty() else emptyList()" in panel,
            "the step chooser is decided by the pool alone",
        )
        val calls =
            source
                .substringAfter("is CellWork.ReportingShortage ->")
                .substringBefore("else -> TaskMenuActions(")
        assertTrue("asksWhereNoticed = true," in calls, "the report form stopped asking where")
        assertTrue("asksWhereNoticed = false," in calls, "the making good form was left asking where")
    }

    @Test
    fun `a pressed tick is held until the table has answered it`() {
        // Letting go when the write comes back is too early: the row saying so
        // arrives later, and in that gap the tick is still drawn in its old
        // state, so a second press reads what it is about to leave and asks for
        // the opposite. Two presses meant as one would undo each other.
        val toggle =
            controllerSource
                .substringAfter("suspend fun toggleTaskCompletion(")
                .substringBefore("/** The same, asked for from the menu")
        assertTrue("pressedTick = PressedTick(" in toggle, "the tick is not held while it waits")
        assertTrue("busyTaskId = null" !in toggle, "the tick is let go of before the table has answered")
        val settle =
            controllerSource
                .substringAfter("private fun settleTick()")
                .substringBefore("/**\n     * Finishes an unfinished task")
        assertTrue("shown == null || shown.isCompletedTask == waiting.finishing" in settle, "the answer is not recognised")
        val watching =
            controllerSource
                .substringAfter("suspend fun observeTable()")
                .substringBefore("suspend fun observeColorCatalogue()")
        assertTrue("settleTick()" in watching, "the rows arriving never let a pressed tick go")
    }
}
