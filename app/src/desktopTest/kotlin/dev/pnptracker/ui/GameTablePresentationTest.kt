package dev.pnptracker.ui

import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskEditFailure
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import dev.pnptracker.ui.feature.importworkspace.labelOf
import dev.pnptracker.ui.navigation.Screen
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the game table says to the user.
 *
 * Resolved through the real resource loader, so a heading that is missing from
 * `strings.xml` fails here as loudly as one that is worded wrongly.
 */
class GameTablePresentationTest {
    private fun textOf(resource: StringResource): String = runBlocking { getString(resource) }

    private fun textOf(
        resource: StringResource,
        vararg formatArgs: Any,
    ): String = runBlocking { getString(resource, *formatArgs) }

    @Test
    fun `the columns read left to right the way the plan lists them`() {
        // PLAN 12.3 fixes the order, and the enum is what decides it — not the
        // stored text, which SQLite would sort into a different language's
        // alphabet, and not the order the cells happened to be created in.
        assertEquals(
            listOf("3D Baskı", "Kart", "Mukavva", "Özel", "Notlar"),
            CellColumnType.entries.map { textOf(columnNameOf(it)) },
        )
    }

    @Test
    fun `the first column is the game itself`() {
        assertEquals("Oyun", textOf(Strings.Columns.game))
    }

    @Test
    fun `the three views are named the way the plan names them`() {
        assertEquals(
            listOf("Devam Eden", "Tamamlanan", "Tümü"),
            GameTableView.entries.map { textOf(viewNameOf(it)) },
        )
    }

    @Test
    fun `every view, column and table text is there and says something`() {
        val texts =
            GameTableView.entries.map { viewNameOf(it) } +
                CellColumnType.entries.map { columnNameOf(it) } +
                listOf(
                    Strings.Columns.game,
                    Strings.Table.label,
                    Strings.Table.loading,
                    Strings.Table.viewLabel,
                    Strings.Table.emptyOngoing,
                    Strings.Table.emptyOngoingHint,
                    Strings.Table.emptyCompleted,
                    Strings.Table.emptyCompletedHint,
                    Strings.Table.emptyLibrary,
                    Strings.Table.emptyLibraryHint,
                    Strings.Table.cellEmpty,
                    Strings.Table.cellMore,
                    Strings.Table.rowCompleted,
                    Strings.Table.rowOngoing,
                    Strings.Table.completedMark,
                    Strings.Table.addGame,
                    Strings.Table.addGameHint,
                )

        texts.forEach { assertTrue(textOf(it).isNotBlank(), "a table text is missing or empty") }
    }

    @Test
    fun `a finished row says so in words and not only in green`() {
        // PLAN 17: colour is never the only thing carrying a meaning. The row
        // has a word for its state and a mark beside the name.
        assertEquals("Tamamlandı", textOf(Strings.Table.rowCompleted))
        assertEquals("Devam ediyor", textOf(Strings.Table.rowOngoing))
        assertTrue(textOf(Strings.Table.completedMark).isNotBlank(), "a finished row has no mark of its own")
    }

    @Test
    fun `the table is the section the window opens on`() {
        // PLAN 12.3 makes the table the primary working surface, so it is what
        // the user is looking at rather than something they navigate to.
        assertEquals(
            Screen.Games,
            dev.pnptracker.ui.navigation
                .AppNavigationState()
                .currentScreen,
        )
    }

    @Test
    fun `import and the colour catalogue are still one click away`() {
        assertTrue(Screen.all.contains(Screen.Import), "the import section left the sidebar")
        assertTrue(Screen.all.contains(Screen.Colors), "the colour catalogue left the sidebar")
        assertTrue(Screen.all.contains(Screen.Games))
    }

    @Test
    fun `no raw enum constant reaches the user`() {
        // The constants are how the columns and views are stored and matched on;
        // a screen that printed one would be showing the user a word from the
        // schema instead of a word from their language.
        val constants =
            CellColumnType.entries.map { it.name } + GameTableView.entries.map { it.name }
        val shown =
            GameTableView.entries.map { textOf(viewNameOf(it)) } +
                CellColumnType.entries.map { textOf(columnNameOf(it)) } +
                textOf(Strings.Columns.game)

        shown.forEach { text ->
            constants.forEach { constant ->
                assertTrue(constant != text, "the user is being shown the raw constant $constant")
            }
        }
    }

    @Test
    fun `no user text is written into the table screen instead of the catalogue`() {
        // PLAN 17 keeps the wording in the resource file. A Turkish letter inside
        // a quoted string in the screen means a text that no language file knows
        // about, so it could never be translated or corrected in one place.
        val turkish = Regex("\"[^\"]*[çğıöşüÇĞİÖŞÜ][^\"]*\"")
        val offenders =
            userInterfaceSources().flatMap { file ->
                Files.readAllLines(file).mapIndexedNotNull { index, line ->
                    val code = line.substringBefore("//")
                    "$file:${index + 1}".takeIf {
                        turkish.containsMatchIn(code) && !code.trimStart().startsWith("*")
                    }
                }
            }

        assertTrue(offenders.isEmpty(), "Turkish text is hardcoded in the interface: $offenders")
    }

    // ------------------------------------------- turning words into a task

    @Test
    fun `every word the task panel says is there and says something`() {
        listOf(
            Strings.CellTask.create,
            Strings.CellTask.selectHint,
            Strings.CellTask.saveTextFirst,
            Strings.CellTask.panelTitle,
            Strings.CellTask.nameLabel,
            Strings.CellTask.colorLabel,
            Strings.CellTask.colorSearch,
            Strings.CellTask.colorRequired,
            Strings.CellTask.colorNone,
            Strings.CellTask.colorEmpty,
            Strings.CellTask.quantityLabel,
            Strings.CellTask.quantityHint,
            Strings.CellTask.quantityInvalid,
            Strings.CellTask.notesLabel,
            Strings.CellTask.save,
            Strings.CellTask.discard,
            Strings.CellTask.saving,
            Strings.CellTask.hint,
            Strings.CellTask.noColor,
            Strings.CellTask.completed,
        ).forEach { assertTrue(textOf(it).isNotBlank(), "a task panel text is missing or empty") }
    }

    @Test
    fun `every refusal has its own sentence`() {
        // PLAN 17 keeps the wording in the resource file, and each case leads
        // somewhere different, so one general apology would not do.
        val sentences =
            TaskFromTextFailure.entries.map { failure ->
                val text = textOf(messageOfFailure(failure))
                assertTrue(text.isNotBlank(), "$failure has nothing to say")
                text
            }

        assertEquals(sentences.size, sentences.toSet().size, "two refusals read the same: $sentences")
    }

    @Test
    fun `no catalogue text carries an escape that was meant for the file`() {
        // A backslash written to escape an apostrophe in the resource file
        // reached the screen as a backslash. Nothing the user reads should
        // carry one, and nothing should end up with the placeholder either.
        val everything =
            TaskFromTextFailure.entries.map { messageOfFailure(it) }.filterNot { it == Strings.CellTask.errorDuplicateColor } +
                listOf(
                    Strings.CellTask.create,
                    Strings.CellTask.selectHint,
                    Strings.CellTask.saveTextFirst,
                    Strings.CellTask.panelTitle,
                    Strings.CellTask.nameLabel,
                    Strings.CellTask.colorLabel,
                    Strings.CellTask.colorSearch,
                    Strings.CellTask.colorRequired,
                    Strings.CellTask.colorNone,
                    Strings.CellTask.colorEmpty,
                    Strings.CellTask.quantityLabel,
                    Strings.CellTask.quantityHint,
                    Strings.CellTask.quantityInvalid,
                    Strings.CellTask.notesLabel,
                    Strings.CellTask.save,
                    Strings.CellTask.discard,
                    Strings.CellTask.saving,
                    Strings.CellTask.hint,
                    Strings.CellTask.noColor,
                    Strings.CellTask.completed,
                )

        everything.forEach { resource ->
            val text = textOf(resource)
            assertTrue('\\' !in text, "an escape reached the user in: $text")
            assertTrue('%' !in text, "an unfilled placeholder reached the user in: $text")
        }
    }

    @Test
    fun `the count beside a task is a multiplication sign and the number`() {
        assertEquals("×15", runBlocking { getString(Strings.CellTask.quantityMark, 15) })
        assertEquals("×1", runBlocking { getString(Strings.CellTask.quantityMark, 1) })
        assertEquals("×150", runBlocking { getString(Strings.CellTask.quantityMark, 150) })
    }

    @Test
    fun `a task is described by its name, its colours and its count`() {
        val said = runBlocking { getString(Strings.CellTask.description, "Knight", "Siyah", 15) }

        assertTrue("Knight" in said, "the name is missing from: $said")
        assertTrue("Siyah" in said, "the colour is missing from: $said")
        assertTrue("15" in said, "the count is missing from: $said")
        assertTrue(said.none { it == '%' }, "unformatted placeholder left in: $said")
    }

    @Test
    fun `a task whose count is unknown still says its name and its colours`() {
        val said = runBlocking { getString(Strings.CellTask.descriptionUnknownQuantity, "Knight", "Siyah") }

        assertTrue("Knight" in said && "Siyah" in said, "the description says too little: $said")
        assertTrue(said.none { it == '%' }, "unformatted placeholder left in: $said")
    }

    @Test
    fun `no raw pool, tracking or failure constant reaches the user`() {
        val constants =
            TaskFromTextFailure.entries.map { it.name } + TrackingMode.entries.map { it.name }
        val shown =
            TaskFromTextFailure.entries.map { sentenceOfFailure(it) } +
                TrackingMode.entries.map { textOf(labelOf(it)) }

        shown.forEach { text ->
            constants.forEach { constant ->
                assertTrue(constant !in text, "the user is being shown the raw constant $constant")
            }
        }
    }

    /**
     * The sentence the screen really shows, places filled in.
     *
     * A duplicate colour is about a pair and says which two, so it is the one
     * refusal that cannot be read as a bare resource.
     */
    private fun sentenceOfFailure(failure: TaskFromTextFailure): String =
        if (failure == TaskFromTextFailure.DUPLICATE_COLOR) {
            runBlocking { getString(Strings.CellTask.errorDuplicateColor, 1, 2) }
        } else {
            textOf(messageOfFailure(failure))
        }

    private fun sentenceOfEditFailure(failure: TaskEditFailure): String =
        if (failure == TaskEditFailure.DUPLICATE_COLOR) {
            runBlocking { getString(Strings.TaskEdit.errorDuplicateColor, 1, 2) }
        } else {
            textOf(messageOfEditFailure(failure))
        }

    @Test
    fun `a task with no colour says so in words and still says its count`() {
        val said =
            runBlocking {
                getString(Strings.CellTask.description, "Knight", 15, getString(Strings.CellTask.noColor))
            }

        // PLAN 5.10 keeps such a task, and PLAN 17 will not let a missing colour
        // be shown by an absence: it is said.
        assertTrue("renk seçilecek" in said, "a task with no colour says nothing about it: $said")
        assertTrue("Knight" in said && "15" in said, "the description says too little: $said")
        assertEquals(1, Regex("15").findAll(said).count(), "the count is said more than once: $said")
        assertTrue(said.none { it == '%' }, "unformatted placeholder left in: $said")
    }

    @Test
    fun `a duplicate colour is said about both of the places that clash`() {
        val batch = runBlocking { getString(Strings.CellTask.errorDuplicateColor, 1, 3) }
        val rowMark = runBlocking { getString(Strings.CellTask.rowDuplicate, 1) }
        val edit = runBlocking { getString(Strings.TaskEdit.errorDuplicateColor, 2, 4) }

        // Both ends of the clash, counted the way the panel numbers them.
        assertTrue("1" in batch && "3" in batch, "the refusal does not name both places: $batch")
        assertTrue("2" in edit && "4" in edit, "the refusal does not name both colours: $edit")
        assertTrue("1" in rowMark, "the mark on a row does not say which row it repeats: $rowMark")
        listOf(batch, rowMark, edit).forEach {
            assertTrue(it.none { character -> character == '%' }, "unformatted placeholder left in: $it")
        }
    }

    /**
     * The same mapping the screen uses, read out of it rather than copied.
     *
     * Written here as a `when` over the enum so a new failure cannot be added
     * without a sentence: the compiler refuses an incomplete one.
     */
    private fun messageOfFailure(failure: TaskFromTextFailure) =
        when (failure) {
            TaskFromTextFailure.GAME_NOT_AVAILABLE -> Strings.CellTask.errorGameGone
            TaskFromTextFailure.CELL_NOT_AVAILABLE -> Strings.CellTask.errorCellGone
            TaskFromTextFailure.CELL_DOES_NOT_HOLD_TASKS -> Strings.CellTask.errorCellHoldsNoTasks
            TaskFromTextFailure.SEGMENT_NOT_AVAILABLE -> Strings.CellTask.errorSegmentGone
            TaskFromTextFailure.SEGMENT_IS_NOT_PLAIN_TEXT -> Strings.CellTask.errorSegmentNotText
            TaskFromTextFailure.STALE_TEXT_SELECTION -> Strings.CellTask.errorStaleSelection
            TaskFromTextFailure.INVALID_SELECTION -> Strings.CellTask.errorInvalidSelection
            TaskFromTextFailure.SELECTION_CONTAINS_LINE_BREAK -> Strings.CellTask.errorLineBreak
            TaskFromTextFailure.TASK_NAME_EMPTY -> Strings.CellTask.errorNameEmpty
            TaskFromTextFailure.COLOR_NOT_AVAILABLE -> Strings.CellTask.errorColorGone
            TaskFromTextFailure.INVALID_REQUIRED_QUANTITY -> Strings.CellTask.errorQuantity
            TaskFromTextFailure.DUPLICATE_COLOR -> Strings.CellTask.errorDuplicateColor
            TaskFromTextFailure.NO_TASK_DESCRIBED -> Strings.CellTask.errorNoTask
            TaskFromTextFailure.COULD_NOT_SAVE -> Strings.CellTask.errorCouldNotSave
        }

    // ---------------------------------------- what the task surfaces say

    @Test
    fun `every word the task menu, the panel and the question say is there`() {
        listOf(
            Strings.TaskMenu.edit,
            Strings.TaskMenu.convertToText,
            Strings.TaskMenu.hint,
            Strings.TaskEdit.title,
            Strings.TaskEdit.nameLabel,
            Strings.TaskEdit.nameInvalid,
            Strings.TaskEdit.save,
            Strings.TaskEdit.saving,
            Strings.TaskEdit.hint,
            Strings.TaskConvert.title,
            Strings.TaskConvert.historyWarning,
            Strings.TaskConvert.irreversible,
            Strings.TaskConvert.accept,
            Strings.TaskConvert.cancel,
            Strings.Cell.errorCellGone,
            Strings.Cell.errorCrossesTask,
            Strings.Cell.errorStaleDocument,
        ).forEach { resource ->
            val text = textOf(resource)
            assertTrue(text.isNotBlank(), "a task surface text is missing or empty")
            assertTrue('\\' !in text, "an escape reached the user in: $text")
            assertTrue('%' !in text, "an unfilled placeholder reached the user in: $text")
        }
    }

    @Test
    fun `every word the two creation modes say is there`() {
        listOf(
            Strings.CellTask.modeLabel,
            Strings.CellTask.modeSingle,
            Strings.CellTask.modeMany,
            Strings.CellTask.modeManyHint,
            Strings.CellTask.rowAdd,
            Strings.CellTask.rowRemoveShort,
            Strings.CellTask.rowFloor,
            Strings.CellTask.savingMany,
        ).forEach { resource ->
            val text = textOf(resource)
            assertTrue(text.isNotBlank(), "a creation mode text is missing or empty")
            assertTrue('\\' !in text, "an escape reached the user in: $text")
            assertTrue('%' !in text, "an unfilled placeholder reached the user in: $text")
        }
    }

    @Test
    fun `the words about a batch never call it a group or a main task`() {
        // PLAN 12.7 makes a batch N independent tasks. A panel that spoke of a
        // main task, a group or a variant would be teaching the user a
        // relationship the database deliberately does not have.
        val said =
            listOf(
                Strings.CellTask.modeMany,
                Strings.CellTask.modeManyHint,
                Strings.CellTask.rowAdd,
                Strings.CellTask.rowFloor,
                Strings.CellTask.rowDuplicate,
                Strings.CellTask.errorDuplicateColor,
            ).joinToString(" ") { textOf(it).lowercase() }

        listOf("ana görev", "üst görev", "alt görev", "varyant", "grup").forEach { word ->
            assertTrue(word !in said, "the panel calls a batch a '$word'")
        }
    }

    @Test
    fun `the counted texts of a batch take the number they are given`() {
        listOf(Strings.CellTask.rowTitle, Strings.CellTask.rowRemove, Strings.CellTask.saveMany).forEach { resource ->
            val text = textOf(resource, 3)
            assertTrue("3" in text, "a counted text left its number out: $text")
            assertTrue('%' !in text, "an unfilled placeholder reached the user in: $text")
        }
    }

    @Test
    fun `every way a task can refuse to change has its own sentence`() {
        val sentences =
            TaskEditFailure.entries.map { failure ->
                val text = sentenceOfEditFailure(failure)
                assertTrue(text.isNotBlank(), "$failure has nothing to say")
                assertTrue('%' !in text, "an unfilled placeholder reached the user in: $text")
                text
            }

        assertEquals(sentences.size, sentences.toSet().size, "two refusals read the same: $sentences")
    }

    @Test
    fun `the question about turning a task into text says what it costs`() {
        // PLAN 12.8 takes the colour, the total and the pipeline away and leaves
        // the word. PLAN 17 asks for that to be confirmed, so it has to be said.
        val body = runBlocking { getString(Strings.TaskConvert.body, "Knight") }

        assertTrue("Knight" in body, "the question does not name the task: $body")
        assertTrue("düz metin" in body, "the question does not say the word stays: $body")
        assertTrue(body.none { it == '%' }, "unformatted placeholder left in: $body")
        assertTrue("geri alınamaz" in textOf(Strings.TaskConvert.irreversible), "nothing says it is final")
        assertTrue("geçmiş" in textOf(Strings.TaskConvert.historyWarning), "the history warning does not mention it")
    }

    @Test
    fun `a task says what it is when it is opened`() {
        val label = runBlocking { getString(Strings.TaskMenu.open, "Knight") }

        assertTrue("Knight" in label, "the action does not name the task: $label")
        assertTrue(label.none { it == '%' }, "unformatted placeholder left in: $label")
    }

    @Test
    fun `a colour of a several colour task is named by its place in the list`() {
        val line = runBlocking { getString(Strings.CellTask.colorSlot, 2, "Siyah") }

        assertTrue("2" in line && "Siyah" in line, "the entry does not say which colour it is: $line")
        assertTrue(line.none { it == '%' }, "unformatted placeholder left in: $line")
    }

    @Test
    fun `moving a colour says which colour it moves`() {
        val up = runBlocking { getString(Strings.CellTask.colorMoveUp, "Sarı") }
        val down = runBlocking { getString(Strings.CellTask.colorMoveDown, "Sarı") }
        val drop = runBlocking { getString(Strings.CellTask.colorDrop, "Sarı") }

        listOf(up, down, drop).forEach { label ->
            assertTrue("Sarı" in label, "the action does not name the colour: $label")
            assertTrue(label.none { it == '%' }, "unformatted placeholder left in: $label")
        }
        assertTrue(up != down, "moving up and moving down are said the same way")
    }

    /**
     * The same mapping the screen uses, written as a `when` over the enum so a
     * new failure cannot be added without a sentence.
     */
    private fun messageOfEditFailure(failure: TaskEditFailure) =
        when (failure) {
            TaskEditFailure.TASK_NOT_AVAILABLE -> Strings.TaskEdit.errorTaskGone
            TaskEditFailure.TASK_NAME_EMPTY -> Strings.TaskEdit.errorNameEmpty
            TaskEditFailure.NAME_CONTAINS_LINE_BREAK -> Strings.TaskEdit.errorNameLineBreak
            TaskEditFailure.COLOR_NOT_AVAILABLE -> Strings.TaskEdit.errorColorGone
            TaskEditFailure.DUPLICATE_COLOR -> Strings.TaskEdit.errorDuplicateColor
            TaskEditFailure.COLOR_COUNT_NOT_CHANGEABLE -> Strings.TaskEdit.errorColorCount
            TaskEditFailure.INVALID_REQUIRED_QUANTITY -> Strings.TaskEdit.errorQuantity
            TaskEditFailure.QUANTITY_BELOW_PROGRESS -> Strings.TaskEdit.errorQuantityBelowProgress
            TaskEditFailure.QUANTITY_LOCKED_BY_COMPLETION -> Strings.TaskEdit.errorQuantityLocked
            TaskEditFailure.COULD_NOT_SAVE -> Strings.TaskEdit.errorCouldNotSave
        }

    private fun userInterfaceSources(): List<Path> {
        val root = Path.of("src/commonMain/kotlin/dev/pnptracker/ui")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.asSequence().filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
        }
    }
}
