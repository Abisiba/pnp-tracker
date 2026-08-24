package dev.pnptracker.ui

import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
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

    private fun userInterfaceSources(): List<Path> {
        val root = Path.of("src/commonMain/kotlin/dev/pnptracker/ui")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.asSequence().filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
        }
    }
}
