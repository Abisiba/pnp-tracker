package dev.pnptracker.ui.feature.games

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.games.AUTOMATIC_WIDTH_LIMIT_DP
import dev.pnptracker.domain.games.DEFAULT_CELL_COLUMN_WIDTH_DP
import dev.pnptracker.domain.games.MINIMUM_COLUMN_WIDTH_DP
import dev.pnptracker.domain.games.TableColumn
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.platform.settings.DesktopTableSizesStore
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.reads
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The table fitted to what is written in it (PLAN 12.17).
 *
 * Every claim here is about where something is drawn, so it is measured on the
 * real screen through the real stack. The window and the text size are held in
 * state around the screen, so "it adapts when the window changes" is a change
 * made while the screen is open rather than two screens compared.
 */
class TableAutoSizeTest {
    private lateinit var folder: Path
    private lateinit var sizesFile: Path

    @BeforeTest
    fun createFolder() {
        folder = Files.createTempDirectory("pnp-tracker-table-autosize-test")
        sizesFile = folder.resolve("table-sizes.json")
    }

    @AfterTest
    fun deleteFolder() {
        val absolute = folder.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
        Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private var windowWidth by mutableStateOf(WIDE.dp)
    private var fontScale by mutableStateOf(1f)

    private fun ComposeSceneHarness.settle(
        what: String,
        done: () -> Boolean,
    ) {
        repeat(200) {
            render()
            if (done()) {
                repeat(2) { render() }
                return
            }
            Thread.sleep(10)
        }
        fail("never happened: $what")
    }

    private fun openTable(stack: RealStack): Pair<ComposeSceneHarness, GameTableController> {
        val table = stack.tableControllerWith(sizes = DesktopTableSizesStore(sizesFile))
        val screen =
            ComposeSceneHarness(width = WIDE, height = 900) {
                val outer = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(outer.density, fontScale)) {
                    Box(modifier = Modifier.width(windowWidth).fillMaxHeight()) { GameTableScreen(table) }
                }
            }
        screen.settle("the table is read") { table.state.rows !is GameTableRowsState.Loading }
        return screen to table
    }

    private fun rowsOf(table: GameTableController) = (table.state.rows as? GameTableRowsState.Content)?.rows.orEmpty()

    /** A game called [name] with [text] written in its [column] cell. */
    private fun write(
        screen: ComposeSceneHarness,
        table: GameTableController,
        name: String,
        column: CellColumnType,
        text: String,
    ): EntityId {
        runBlocking {
            table.startGameComposer()
            table.editGameName(name)
            table.saveGame()
        }
        screen.settle("the game is shown") { rowsOf(table).any { it.gameName == name } }
        val gameId = rowsOf(table).first { it.gameName == name }.gameId
        table.beginEditing(gameId, column)
        screen.settle("the editor opens") { table.state.work is CellWork.WritingText }
        table.editCellText(text)
        screen.render()
        runBlocking { table.saveEditing() }
        screen.settle("the writing is stored") {
            rowsOf(table)
                .first { it.gameId == gameId }
                .cell(column)
                .editableText == text
        }
        screen.settle("the editor closes") { table.state.work == null }
        return gameId
    }

    /** The cell of [columnName] that has writing in it. */
    private fun ComposeSceneHarness.writtenCell(columnName: String): Rect =
        spokenNodes()
            .firstOrNull { node -> node.contentDescriptions().any { it.startsWith("$columnName hücresi: ") } }
            ?.boundsInRoot
            ?: fail("no `$columnName` cell with writing in it is drawn")

    /** The drawn text that holds [piece]. */
    private fun ComposeSceneHarness.textHolding(piece: String): SemanticsNode =
        nodes().firstOrNull { node -> node.reads(SemanticsProperties.Text).orEmpty().any { piece in it.text } }
            ?: fail("`$piece` is not drawn")

    /** Every one of [text]'s lines sits inside the cell it was written in. */
    private fun ComposeSceneHarness.assertWholeInside(
        columnName: String,
        piece: String,
    ) {
        val cell = writtenCell(columnName)
        val text = textHolding(piece).boundsInRoot
        assertTrue(text.bottom <= cell.bottom + TOLERANCE, "the writing runs out of the bottom of its cell: $text in $cell")
        assertTrue(text.right <= cell.right + TOLERANCE, "the writing runs out of the side of its cell: $text in $cell")
    }

    @Test
    fun `a line too long for its column widens it, and the rest wraps`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                write(screen, table, "Harmonies", CellColumnType.NOTES, LONG_LINE)

                val cell = screen.writtenCell("Notlar")
                assertTrue(cell.width > DEFAULT_CELL_COLUMN_WIDTH_DP + TOLERANCE, "the column did not grow: $cell")
                assertTrue(cell.width <= AUTOMATIC_WIDTH_LIMIT_DP + TOLERANCE, "the column grew past its bound: $cell")
                screen.assertWholeInside("Notlar", "ikinci baskı")
                val text = screen.textHolding("ikinci baskı").boundsInRoot
                assertTrue(text.height > 1.5f * LINE, "the long line did not wrap onto more lines: $text")
            }
        }
    }

    @Test
    fun `growing to fit is never written down as a size somebody chose`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                write(screen, table, "Harmonies", CellColumnType.NOTES, LONG_LINE)

                assertTrue(screen.writtenCell("Notlar").width > DEFAULT_CELL_COLUMN_WIDTH_DP + TOLERANCE)
                assertTrue(table.state.sizes.isDefault, "the fitted width became a chosen one: ${table.state.sizes}")
                assertFalse(Files.exists(sizesFile), "fitting to the content wrote the sizes file")
            }
        }
    }

    @Test
    fun `a word longer than any column is broken inside itself rather than cut`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                write(screen, table, "Harmonies", CellColumnType.THREE_D, LONG_WORD)

                val cell = screen.writtenCell("3D Baskı")
                assertTrue(cell.width <= AUTOMATIC_WIDTH_LIMIT_DP + TOLERANCE, "one word pushed its column past the bound")
                screen.assertWholeInside("3D Baskı", LONG_WORD.take(12))
                assertTrue(cell.height > 2 * SINGLE_LINE_ROW, "the word was not broken onto several lines: $cell")
            }
        }
    }

    @Test
    fun `a small width the user chose does not cut the writing`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                write(screen, table, "Harmonies", CellColumnType.CARD, LONG_LINE)
                // The whole window taken by columns the user made wide, so there is
                // no room to grow into and the small width is the one drawn. Kart
                // is the third column, so it is still on the screen.
                TableColumn.entries.filter { it != TableColumn.CARD }.forEach { table.resizeColumn(it, 400f) }
                table.resizeColumn(TableColumn.CARD, MINIMUM_COLUMN_WIDTH_DP)
                screen.render()
                screen.render()

                val cell = screen.writtenCell("Kart")
                assertEquals(MINIMUM_COLUMN_WIDTH_DP, cell.width, TOLERANCE)
                screen.assertWholeInside("Kart", "ikinci baskı")
            }
        }
    }

    @Test
    fun `the columns share the window and one long cell does not push the table off it`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                write(screen, table, "Harmonies", CellColumnType.NOTES, LONG_LINE)
                write(screen, table, "Sky Team", CellColumnType.CARD, LONG_LINE)
                windowWidth = NARROW.dp
                screen.render()
                screen.render()

                val notes = screen.writtenCell("Notlar")
                val cards = screen.writtenCell("Kart")
                assertTrue(notes.right <= NARROW + TOLERANCE, "the table is wider than the window: $notes")
                assertTrue(cards.width > DEFAULT_CELL_COLUMN_WIDTH_DP + TOLERANCE, "the Kart column was given nothing: $cards")
                assertTrue(notes.width > DEFAULT_CELL_COLUMN_WIDTH_DP + TOLERANCE, "the Notlar column was given nothing: $notes")
                assertEquals(cards.width, notes.width, TOLERANCE, "two columns wanting as much were not given as much")
                screen.assertWholeInside("Notlar", "ikinci baskı")
            }
        }
    }

    @Test
    fun `a narrower window takes the extra width back and a wider one gives it again`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                write(screen, table, "Harmonies", CellColumnType.NOTES, LONG_LINE)
                val wide = screen.writtenCell("Notlar")

                windowWidth = NARROW.dp
                screen.render()
                screen.render()
                val narrow = screen.writtenCell("Notlar")
                assertTrue(narrow.width < wide.width - TOLERANCE, "a narrower window did not narrow the column: $narrow")
                assertTrue(narrow.height > wide.height + TOLERANCE, "the narrower column did not make the row taller")
                screen.assertWholeInside("Notlar", "ikinci baskı")

                windowWidth = WIDE.dp
                screen.render()
                screen.render()
                assertEquals(wide.width, screen.writtenCell("Notlar").width, TOLERANCE)
                assertTrue(table.state.sizes.isDefault, "adapting to the window wrote a size down")
            }
        }
    }

    @Test
    fun `a bigger text size asks for more room on the same screen`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                write(screen, table, "Harmonies", CellColumnType.NOTES, MEDIUM_LINE)
                val normal = screen.writtenCell("Notlar")

                fontScale = 1.5f
                screen.render()
                screen.render()
                val bigger = screen.writtenCell("Notlar")

                assertTrue(bigger.width > normal.width + TOLERANCE, "the column did not follow the text size: $normal -> $bigger")
                screen.assertWholeInside("Notlar", "Ejderha")
                assertTrue(table.state.sizes.isDefault, "adapting to the text size wrote a size down")
            }
        }
    }

    @Test
    fun `dragging still sets a column and a row, and the content still shows whole`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                // Kart, in the middle of the table, so it stays on the screen after
                // it has been dragged wider than the window has room for.
                val gameId = write(screen, table, "Harmonies", CellColumnType.CARD, LONG_LINE)
                val before = screen.writtenCell("Kart")
                val boundary = Offset(before.right, screen.headingTop("Kart"))

                screen.dragFrom(from = boundary, to = boundary + Offset(150f, 0f))
                screen.render()
                val wider = screen.writtenCell("Kart")
                assertTrue(before.width > DEFAULT_CELL_COLUMN_WIDTH_DP + TOLERANCE, "the column had not grown to begin with")
                assertEquals(before.width + 150f, wider.width, TOLERANCE, "the drag did not start from the drawn width")
                assertEquals(before.width + 150f, table.state.sizes.widthOf(TableColumn.CARD), TOLERANCE)

                val bottom = Offset(wider.center.x, wider.bottom - 2f)
                screen.dragFrom(from = bottom, to = bottom + Offset(0f, 80f))
                screen.render()
                assertEquals(wider.height + 80f, screen.writtenCell("Kart").height, TOLERANCE)
                assertEquals(wider.height + 80f, table.state.sizes.heightOf(gameId) ?: 0f, TOLERANCE)
                screen.assertWholeInside("Kart", "ikinci baskı")
            }
        }
    }

    /** How far down a heading is drawn, which is where its column's boundary is grabbed. */
    private fun ComposeSceneHarness.headingTop(name: String): Float =
        nodes()
            .filter { node -> node.reads(SemanticsProperties.Text).orEmpty().any { it.text == name } }
            .minOfOrNull { it.boundsInRoot.center.y }
            ?: fail("the heading `$name` is not drawn")

    private companion object {
        const val WIDE = 1500
        const val NARROW = 1400
        const val TOLERANCE = 2f

        /** One line of cell text: 14 sp at a line height of 20. */
        const val LINE = 20f

        /** Taller than a row of one line; a row past it has wrapped. */
        const val SINGLE_LINE_ROW = 64f

        /** One line wider than the bound, so it widens its column all it may and still wraps. */
        const val LONG_LINE =
            "Ejderha kalkanı, kule ve surlar için ikinci baskı gerekecek; kartlar kesildi, mukavva kutusu yapıştırılmayı bekliyor"

        /** Inside a default column at the normal text size, too wide for one at half as large again. */
        const val MEDIUM_LINE = "Ejderha kalkanı ve kule"

        /** One word, far wider than any column may become. */
        val LONG_WORD = "Ejderhakalkanıkulesurlar".repeat(20)
    }
}
