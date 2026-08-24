package dev.pnptracker.ui

import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.TrackingMode
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
            TaskFromTextFailure.entries.map { messageOfFailure(it) } +
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
            TaskFromTextFailure.entries.map { textOf(messageOfFailure(it)) } +
                TrackingMode.entries.map { textOf(labelOf(it)) }

        shown.forEach { text ->
            constants.forEach { constant ->
                assertTrue(constant !in text, "the user is being shown the raw constant $constant")
            }
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
            TaskFromTextFailure.COULD_NOT_SAVE -> Strings.CellTask.errorCouldNotSave
        }

    private fun userInterfaceSources(): List<Path> {
        val root = Path.of("src/commonMain/kotlin/dev/pnptracker/ui")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.asSequence().filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
        }
    }
}
